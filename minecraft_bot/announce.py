"""Update notices sent to members by direct message.

These are legitimate server announcements to people who opted in by holding the member
role, but Discord does not judge intent — it judges shape. A burst of identical DMs from
one bot, especially one where many recipients have DMs closed, is indistinguishable from
spam at the API level and is what gets an application flagged. So the sending here is
deliberately slow and deliberately quick to give up:

* one DM at a time, with a fixed pause between them, well inside the rate limit;
* a hard stop when too great a share of the early attempts are refused, because a high
  refusal rate is the strongest signal that a bot is messaging people who never asked;
* a per-recipient cooldown, so a mistaken double-send cannot reach anybody twice;
* a recipient list that only ever comes from role membership, never from a guild scrape.

The cost of being slow is that a large announcement takes minutes. That is the correct
trade: the alternative failure is the bot being terminated.
"""

from __future__ import annotations

import asyncio
import logging
import time
from dataclasses import dataclass, field
from typing import Any, Iterable, Optional

import discord

logger = logging.getLogger("MinecraftAccessBot.announce")

#: Seconds between two direct messages. Discord's own ceiling is far higher; this is
#: paced for how the traffic looks rather than for throughput.
SEND_INTERVAL_SECONDS = 1.2

#: Attempts to make before the refusal rate is worth judging. Below this a couple of
#: closed inboxes would look like a catastrophe.
FAILURE_SAMPLE = 12

#: Share of attempts that may be refused before sending stops. Someone with DMs closed
#: is normal; most of a role having them closed means this is not reaching an audience
#: that wants it.
FAILURE_LIMIT = 0.5

#: A recipient reached this recently is skipped, so a double-click cannot double-send.
RECIPIENT_COOLDOWN_SECONDS = 6 * 60 * 60


@dataclass
class BroadcastResult:
    """What actually happened, in the terms an owner needs to see."""

    delivered: int = 0
    refused: int = 0
    skipped: int = 0
    stopped_early: bool = False
    reason: str = ""
    failures: list[str] = field(default_factory=list)

    def as_dict(self) -> dict[str, Any]:
        return {
            "delivered": self.delivered,
            "refused": self.refused,
            "skipped": self.skipped,
            "stopped_early": self.stopped_early,
            "reason": self.reason,
            "failures": self.failures[:10],
        }


class UpdateAnnouncer:
    """Sends one update notice to everyone holding the member role."""

    def __init__(self, bot: Any) -> None:
        self.bot = bot
        self._last_sent: dict[int, float] = {}
        self._running = False

    @property
    def running(self) -> bool:
        return self._running

    async def recipients(self) -> list[discord.Member]:
        """Everyone who would receive a notice right now.

        Membership of the role is the entire opt-in. Bots are excluded because a DM to
        one is refused and counts against the refusal rate for no reason.
        """
        guild = await self.bot._configured_guild()
        role_id = int(getattr(self.bot.settings, "member_role_id", 0) or 0)
        if guild is None or not role_id:
            return []
        role = guild.get_role(role_id)
        if role is None:
            return []
        return [member for member in role.members if not member.bot]

    async def enabled(self) -> bool:
        """Whether announcements are switched on. Off is the safe default."""
        value = await self.bot.data.get_config("minecraft_announce_enabled", "0")
        return str(value) in {"1", "true", "True"}

    async def set_enabled(self, enabled: bool) -> None:
        await self.bot.data.set_config(
            "minecraft_announce_enabled", "1" if enabled else "0"
        )

    async def send(
        self,
        *,
        embed: discord.Embed,
        content: Optional[str] = None,
        actor: str = "owner",
        targets: Optional[Iterable[discord.Member]] = None,
    ) -> BroadcastResult:
        result = BroadcastResult()
        if self._running:
            result.stopped_early = True
            result.reason = "An announcement is already being sent."
            return result
        if not await self.enabled():
            result.stopped_early = True
            result.reason = "Announcements are switched off in the control panel."
            return result

        members = list(targets) if targets is not None else await self.recipients()
        if not members:
            result.stopped_early = True
            result.reason = "Nobody holds the member role, so there is nobody to tell."
            return result

        self._running = True
        attempted = 0
        try:
            for member in members:
                now = time.time()
                if now - self._last_sent.get(member.id, 0.0) < RECIPIENT_COOLDOWN_SECONDS:
                    result.skipped += 1
                    continue
                try:
                    await member.send(content=content or None, embed=embed)
                    result.delivered += 1
                    self._last_sent[member.id] = now
                except discord.Forbidden:
                    # Their inbox is closed. Expected, and not an error worth retrying.
                    result.refused += 1
                    result.failures.append(f"{member} has direct messages closed")
                except discord.HTTPException as exc:
                    result.refused += 1
                    result.failures.append(f"{member}: {exc}")
                attempted += 1

                if (
                    attempted >= FAILURE_SAMPLE
                    and result.refused / attempted > FAILURE_LIMIT
                ):
                    result.stopped_early = True
                    result.reason = (
                        f"Stopped after {attempted} attempts: {result.refused} were "
                        "refused. That refusal rate is what gets a bot flagged, so the "
                        "rest were not attempted."
                    )
                    logger.warning("Announcement stopped early: %s", result.reason)
                    break
                await asyncio.sleep(SEND_INTERVAL_SECONDS)
        finally:
            self._running = False

        logger.info(
            "Announcement by %s: %d delivered, %d refused, %d skipped",
            actor, result.delivered, result.refused, result.skipped,
        )
        return result
