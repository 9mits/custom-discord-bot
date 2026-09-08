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

#: The house orange, used when a draft names no colour of its own.
DEFAULT_COLOUR = 0xF06000


def build_announcement_embed(
    *,
    title: str = "",
    description: str = "",
    colour: str = "",
    image: str = "",
    footer: str = "",
) -> discord.Embed:
    """Turns one draft into the embed recipients will see.

    Both the owner console and the preview command build through here, so what a
    preview shows is what a real announcement sends. Raises ValueError with the
    wording to show the author.
    """
    title = str(title or "").strip()
    description = str(description or "").strip()
    if not title and not description:
        raise ValueError("An announcement needs a title or a body.")
    if len(title) > 256:
        raise ValueError("The title must be 256 characters or fewer.")
    if len(description) > 4000:
        raise ValueError("The body must be 4000 characters or fewer.")

    raw_colour = str(colour or "").strip().lstrip("#")
    try:
        parsed = (
            discord.Colour(int(raw_colour, 16))
            if raw_colour
            else discord.Colour(DEFAULT_COLOUR)
        )
    except ValueError:
        raise ValueError("The colour must be a hex value such as F06000.") from None

    embed = discord.Embed(
        title=title or None, description=description or None, colour=parsed
    )
    # Anything else would let a draft point Discord at a plaintext or file URL.
    if str(image or "").strip().startswith("https://"):
        embed.set_image(url=str(image).strip())
    footer = str(footer or "").strip()
    if footer:
        embed.set_footer(text=footer[:2048])
    return embed


def announcer_for(bot: Any) -> UpdateAnnouncer:
    """The one announcer for this bot.

    Shared deliberately: the in-flight guard and the per-recipient cooldown only
    mean anything if the console and the preview command consult the same object.
    """
    existing = getattr(bot, "_update_announcer", None)
    if existing is None:
        existing = UpdateAnnouncer(bot)
        bot._update_announcer = existing
    return existing


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
        one is refused and counts against the refusal rate for no reason, and anyone who
        pressed "Stop update DMs" is removed here rather than at the send: a refusal the
        person already asked for should never count towards the abort rate.
        """
        guild = await self.bot._configured_guild()
        role_id = int(getattr(self.bot.settings, "member_role_id", 0) or 0)
        if guild is None or not role_id:
            return []
        role = guild.get_role(role_id)
        if role is None:
            return []
        opted_out = await self.bot.data.update_optout_ids()
        return [
            member
            for member in role.members
            if not member.bot and str(member.id) not in opted_out
        ]

    async def enabled(self) -> bool:
        """Whether announcements are switched on. Off is the safe default."""
        value = await self.bot.data.get_config("minecraft_announce_enabled", "0")
        return str(value) in {"1", "true", "True"}

    async def set_enabled(self, enabled: bool) -> None:
        await self.bot.data.set_config(
            "minecraft_announce_enabled", "1" if enabled else "0"
        )

    async def preview(
        self,
        *,
        embed: discord.Embed,
        member: discord.abc.Messageable,
        content: Optional[str] = None,
        view: Optional[discord.ui.View] = None,
    ) -> None:
        """Sends one copy to whoever is composing it.

        Deliberately outside every guard that protects a real broadcast. It ignores
        the on/off switch because the point is to read the thing before arming it,
        and it ignores the per-recipient cooldown because drafting means sending
        yourself the same notice repeatedly.

        It must never record the send in ``_last_sent``: the author usually holds
        the member role too, and a recorded preview would make the real
        announcement skip the one person who knows it went out.
        """
        await member.send(content=content or None, embed=embed, view=view)

    async def send(
        self,
        *,
        embed: discord.Embed,
        content: Optional[str] = None,
        actor: str = "owner",
        targets: Optional[Iterable[discord.Member]] = None,
        view: Optional[discord.ui.View] = None,
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
                    await member.send(content=content or None, embed=embed, view=view)
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
