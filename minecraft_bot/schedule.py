"""Server events booked ahead of time.

Every event could be started now or left to its own timer; nothing could be arranged.
"2x keys on Saturday evening" meant being at a keyboard on Saturday evening.

An entry names one of the actions the plugin already declares, the arguments it takes,
and when to run it. The bot owns the clock rather than the plugin, because the bot is the
side that stays up across a Minecraft restart and already has a database and a task loop.

Two properties matter more than precision here:

* **A missed window is skipped, not replayed.** If the bot was down all Saturday, nobody
  wants six hours of 2x keys starting on Sunday morning. An entry more than `GRACE`
  behind is marked run and left alone.
* **A repeat cannot stack.** Each firing advances the entry to its next occurrence before
  the action is dispatched, so a slow or failing action cannot be started twice.
"""

from __future__ import annotations

import asyncio
import json
import logging
import time
import uuid
from dataclasses import dataclass, field
from typing import Any, Optional

logger = logging.getLogger("MinecraftAccessBot.schedule")

#: How late an entry may fire. Past this it is treated as missed rather than overdue.
GRACE_SECONDS = 15 * 60

#: Where the schedule lives in the bot's own config table.
STORAGE_KEY = "minecraft_action_schedule"

MAX_ENTRIES = 100


@dataclass
class Entry:
    """One booked action."""

    id: str
    action: str
    arguments: dict[str, Any]
    run_at: int
    repeat_days: int = 0
    enabled: bool = True
    label: str = ""
    actor_uuid: str = ""
    actor_label: str = ""
    last_run_at: Optional[int] = None
    last_result: str = ""

    def as_dict(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "action": self.action,
            "arguments": self.arguments,
            "run_at": self.run_at,
            "repeat_days": self.repeat_days,
            "enabled": self.enabled,
            "label": self.label,
            "actor_uuid": self.actor_uuid,
            "actor_label": self.actor_label,
            "last_run_at": self.last_run_at,
            "last_result": self.last_result,
        }

    @classmethod
    def from_dict(cls, raw: dict[str, Any]) -> "Entry":
        return cls(
            id=str(raw.get("id") or uuid.uuid4().hex),
            action=str(raw.get("action", "")),
            arguments=dict(raw.get("arguments") or {}),
            run_at=int(raw.get("run_at") or 0),
            repeat_days=max(0, int(raw.get("repeat_days") or 0)),
            enabled=bool(raw.get("enabled", True)),
            label=str(raw.get("label", ""))[:100],
            actor_uuid=str(raw.get("actor_uuid", "")).strip(),
            actor_label=str(raw.get("actor_label", ""))[:80],
            last_run_at=raw.get("last_run_at"),
            last_result=str(raw.get("last_result", "")),
        )

    def advance(self, now: int) -> bool:
        """Moves to the next occurrence. False when the entry is finished.

        A repeat steps forward until it is in the future, so an entry that missed several
        occurrences lands on the next real one rather than firing once per missed week.
        """
        if self.repeat_days <= 0:
            return False
        step = self.repeat_days * 86400
        while self.run_at <= now:
            self.run_at += step
        return True


@dataclass
class Schedule:
    """The booked actions, loaded from and saved to the bot's config."""

    bot: Any
    entries: list[Entry] = field(default_factory=list)
    _lock: asyncio.Lock = field(default_factory=asyncio.Lock, init=False, repr=False)

    async def load(self) -> None:
        async with self._lock:
            await self._load_unlocked()

    async def _load_unlocked(self) -> None:
        raw = await self.bot.data.get_config(STORAGE_KEY, "[]")
        try:
            rows = json.loads(raw) if isinstance(raw, str) else (raw or [])
        except (json.JSONDecodeError, TypeError):
            rows = []
        if not isinstance(rows, list):
            rows = []
        entries: list[Entry] = []
        for row in rows:
            if not isinstance(row, dict):
                continue
            try:
                entries.append(Entry.from_dict(row))
            except (TypeError, ValueError, OverflowError):
                logger.warning("Ignored a malformed scheduled-action row")
        self.entries = entries[:MAX_ENTRIES]

    async def save(self) -> None:
        async with self._lock:
            await self._save_unlocked()

    async def _save_unlocked(self) -> None:
        await self.bot.data.set_config(
            STORAGE_KEY, json.dumps([entry.as_dict() for entry in self.entries])
        )

    async def upsert(
        self,
        raw: dict[str, Any],
        *,
        actor_uuid: str,
        actor_label: str = "",
    ) -> Entry:
        try:
            normalized_actor = str(uuid.UUID(str(actor_uuid).strip()))
        except (ValueError, AttributeError, TypeError) as exc:
            raise ValueError("A linked Minecraft owner account is required.") from exc
        candidate = dict(raw)
        candidate["actor_uuid"] = normalized_actor
        candidate["actor_label"] = str(actor_label)[:80]
        try:
            entry = Entry.from_dict(candidate)
        except (TypeError, ValueError, OverflowError) as exc:
            raise ValueError("That schedule entry contains an invalid value.") from exc
        if not entry.action:
            raise ValueError("Choose what the schedule should run.")
        if entry.run_at <= 0:
            raise ValueError("Choose when it should run.")
        async with self._lock:
            await self._load_unlocked()
            existing = [row for row in self.entries if row.id == entry.id]
            if existing:
                self.entries = [entry if row.id == entry.id else row for row in self.entries]
            else:
                if len(self.entries) >= MAX_ENTRIES:
                    raise ValueError("That is as many scheduled actions as the panel holds.")
                self.entries.append(entry)
            await self._save_unlocked()
        return entry

    async def remove(self, entry_id: str) -> None:
        async with self._lock:
            await self._load_unlocked()
            self.entries = [row for row in self.entries if row.id != entry_id]
            await self._save_unlocked()

    async def due(self, now: Optional[int] = None) -> list[Entry]:
        """Entries to run right now, advancing or retiring each as it is taken.

        The advance happens before anything is dispatched: a slow or failing action must
        not leave an entry still due and start twice on the next tick.
        """
        moment = int(time.time()) if now is None else int(now)
        async with self._lock:
            await self._load_unlocked()
            ready: list[Entry] = []
            changed = False
            for entry in list(self.entries):
                if not entry.enabled or entry.run_at > moment:
                    continue
                missed = moment - entry.run_at > GRACE_SECONDS
                if not entry.advance(moment):
                    entry.enabled = False
                changed = True
                if missed:
                    # Nobody wants Saturday's event starting on Sunday morning.
                    entry.last_result = "skipped: the moment had passed"
                    entry.last_run_at = moment
                    logger.info("Skipped a scheduled %s that was overdue", entry.action)
                    continue
                ready.append(entry)
            if changed:
                await self._save_unlocked()
            return ready

    async def record(self, entry_id: str, message: str) -> None:
        async with self._lock:
            await self._load_unlocked()
            for entry in self.entries:
                if entry.id == entry_id:
                    entry.last_run_at = int(time.time())
                    entry.last_result = message[:200]
            await self._save_unlocked()
