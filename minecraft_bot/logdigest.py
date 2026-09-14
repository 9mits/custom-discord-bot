"""Compact digests for routine Minecraft logs.

One embed per kill, ore find and crate opening made the log channels unreadable: a
busy hour was hundreds of cards, and the one line that mattered scrolled away under
them. Routine lines are now collected per channel and written as a single digest a
few seconds later, one line per action. Identical lines from the same player collapse
into one with a count, so a player mining a vein reads as one line, not twelve.

Important lines never wait here. Anything access-changing, failed, denied or security
related is still sent on its own the moment it arrives.

Self-contained like the rest of `minecraft_bot`: stdlib and discord only.
"""

from __future__ import annotations

import asyncio
import logging
import time
from dataclasses import dataclass, field
from typing import Any, Awaitable, Callable, Optional

import discord

from .presentation import info_embed


logger = logging.getLogger("MinecraftAccessBot")

#: How long a line may wait for company before its digest is written.
FLUSH_SECONDS = 15.0
#: A digest is written early once it holds this many lines.
MAX_LINES = 20
#: Embed descriptions stop at 4,096 characters; leave room for the count suffixes.
MAX_CHARS = 3_800
LINE_LIMIT = 300


@dataclass
class _Line:
    key: str
    text: str
    at: int
    count: int = 1

    def render(self) -> str:
        suffix = f" **×{self.count}**" if self.count > 1 else ""
        return f"<t:{self.at}:T> {self.text}{suffix}"


@dataclass
class _Pending:
    label: str
    lines: list[_Line] = field(default_factory=list)
    labels: set[str] = field(default_factory=set)
    first_at: float = 0.0

    def size(self) -> int:
        return sum(len(line.render()) + 1 for line in self.lines)


def clip(text: Any, limit: int = LINE_LIMIT) -> str:
    value = " ".join(str(text or "").split())
    return value if len(value) <= limit else value[: limit - 3] + "..."


def digest_embed(label: str, lines: list[str], *, total: int) -> discord.Embed:
    title = f"{label} · {total} {'action' if total == 1 else 'actions'}"
    return info_embed(title, "\n".join(lines)[:4_000])


class LogDigest:
    """Buffers routine log lines per channel and writes them as compact digests."""

    def __init__(
        self,
        send: Callable[[int, discord.Embed], Awaitable[Any]],
        *,
        flush_seconds: float = FLUSH_SECONDS,
        max_lines: int = MAX_LINES,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self._send = send
        self._flush_seconds = flush_seconds
        self._max_lines = max_lines
        self._clock = clock
        self._pending: dict[int, _Pending] = {}
        self._lock = asyncio.Lock()
        self._task: Optional[asyncio.Task] = None

    def start(self) -> None:
        if self._task is None or self._task.done():
            self._task = asyncio.create_task(self._run(), name="minecraft-log-digest")

    async def close(self) -> None:
        if self._task is not None:
            self._task.cancel()
            try:
                await self._task
            except (asyncio.CancelledError, Exception):
                pass
            self._task = None
        await self.flush(force=True)

    async def add(
        self, channel_id: int, label: str, text: str, *, key: str = "", at: Optional[int] = None
    ) -> None:
        """Queues one line. `key` groups repeats: the same key and text only raise a count."""
        if not channel_id:
            return
        stamp = int(at if at is not None else time.time())
        body = clip(text)
        ready: Optional[tuple[str, list[str], int]] = None
        async with self._lock:
            pending = self._pending.get(channel_id)
            if pending is None:
                pending = _Pending(label=label, first_at=self._clock())
                self._pending[channel_id] = pending
            pending.labels.add(label)
            previous = next(
                (line for line in reversed(pending.lines) if line.key == key and line.text == body),
                None,
            ) if key else None
            if previous is not None:
                previous.count += 1
                previous.at = stamp
            else:
                pending.lines.append(_Line(key=key, text=body, at=stamp))
            if len(pending.lines) >= self._max_lines or pending.size() >= MAX_CHARS:
                ready = self._take(channel_id)
        if ready is not None:
            await self._write(channel_id, *ready)

    async def flush(self, *, force: bool = False) -> None:
        now = self._clock()
        due: list[tuple[int, tuple[str, list[str], int]]] = []
        async with self._lock:
            for channel_id in list(self._pending):
                pending = self._pending[channel_id]
                if force or now - pending.first_at >= self._flush_seconds:
                    due.append((channel_id, self._take(channel_id)))
        for channel_id, ready in due:
            await self._write(channel_id, *ready)

    def pending_lines(self, channel_id: int) -> int:
        pending = self._pending.get(channel_id)
        return len(pending.lines) if pending else 0

    def _take(self, channel_id: int) -> tuple[str, list[str], int]:
        pending = self._pending.pop(channel_id)
        label = pending.label if len(pending.labels) == 1 else "Server Activity"
        total = sum(line.count for line in pending.lines)
        return label, [line.render() for line in pending.lines], total

    async def _write(self, channel_id: int, label: str, lines: list[str], total: int) -> None:
        if not lines:
            return
        try:
            await self._send(channel_id, digest_embed(label, lines, total=total))
        except Exception:
            logger.exception("Could not write a Minecraft log digest to channel %s", channel_id)

    async def _run(self) -> None:
        while True:
            await asyncio.sleep(max(1.0, self._flush_seconds / 3))
            try:
                await self.flush()
            except Exception:
                logger.exception("Minecraft log digest flush failed")
