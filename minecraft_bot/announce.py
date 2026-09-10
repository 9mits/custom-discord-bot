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
from typing import Any, Awaitable, Callable, Iterable, Optional, Sequence

import discord

from . import logroutes

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

#: How often a live progress report may fire. Editing a message costs a request of
#: its own, and a broadcast that spends its budget narrating itself is slower for no
#: benefit. Start and finish always report regardless of this.
PROGRESS_INTERVAL_SECONDS = 5.0

#: The house orange, used when a draft names no colour of its own.
DEFAULT_COLOUR = 0xF06000


async def log_update_notice(
    bot: Any,
    *,
    title: str,
    member: Any,
    detail: str,
    success: bool = True,
) -> None:
    """Write one recipient-level update-DM event to its configured log stream.

    Delivery logging is best-effort and never turns a successful DM into a failed one.
    The bot's normal log sender queues the embed when Discord's channel is temporarily
    unavailable, so a short outage does not silently erase the record.
    """
    settings = getattr(bot, "settings", None)
    sender = getattr(bot, "_send_configured_log", None)
    if settings is None or sender is None:
        return
    try:
        member_id = int(getattr(member, "id", 0) or 0)
        label = str(member)
        recipient = f"<@{member_id}> (`{member_id}`)" if member_id else label
        embed = discord.Embed(
            title=title,
            description=f"> {detail}\n\n**Member:** {recipient}\n**Account:** {label}",
            colour=discord.Colour(0x57F287 if success else 0xF06000),
            timestamp=discord.utils.utcnow(),
        )
        await sender(logroutes.resolve(settings, "announcement"), embed)
    except Exception:
        logger.exception("Could not log update-DM event %s", title)


def _notice_title(
    *,
    embed: Optional[discord.Embed],
    embeds: Optional[Sequence[discord.Embed]],
) -> str:
    first = embed or (embeds[0] if embeds else None)
    return str(getattr(first, "title", "") or "Untitled update")


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
class BroadcastProgress:
    """One live reading of a broadcast in flight."""

    processed: int
    total: int
    delivered: int
    refused: int
    skipped: int
    seconds_left: float

    @property
    def percent(self) -> int:
        if self.total <= 0:
            return 100
        return min(100, round(self.processed * 100 / self.total))

    @property
    def remaining(self) -> int:
        return max(0, self.total - self.processed)


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
        embed: Optional[discord.Embed] = None,
        embeds: Optional[Sequence[discord.Embed]] = None,
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
        payload = self._embed_payload(embed=embed, embeds=embeds)
        await member.send(content=content or None, view=view, **payload)
        await log_update_notice(
            self.bot,
            title="Announcement Preview DM Sent",
            member=member,
            detail=f"A preview of **{_notice_title(embed=embed, embeds=embeds)}** was delivered.",
        )

    async def send(
        self,
        *,
        embed: Optional[discord.Embed] = None,
        embeds: Optional[Sequence[discord.Embed]] = None,
        content: Optional[str] = None,
        actor: str = "owner",
        targets: Optional[Iterable[discord.Member]] = None,
        view: Optional[discord.ui.View] = None,
        progress: Optional[Callable[[BroadcastProgress], Awaitable[None]]] = None,
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
        total = len(members)
        processed = 0
        last_report = 0.0

        async def report(force: bool = False) -> None:
            """Tell the caller where we are, without letting it derail the send.

            A progress reporter is a nicety; a broadcast that dies because a status
            message could not be edited would be a much worse failure than a stale
            percentage.
            """
            nonlocal last_report
            if progress is None:
                return
            now = time.monotonic()
            if not force and now - last_report < PROGRESS_INTERVAL_SECONDS:
                return
            last_report = now
            try:
                await progress(BroadcastProgress(
                    processed=processed,
                    total=total,
                    delivered=result.delivered,
                    refused=result.refused,
                    skipped=result.skipped,
                    seconds_left=max(0, total - processed) * SEND_INTERVAL_SECONDS,
                ))
            except Exception:
                logger.warning("Progress report failed; the send continues", exc_info=True)

        try:
            await report(force=True)
            for member in members:
                now = time.time()
                if now - self._last_sent.get(member.id, 0.0) < RECIPIENT_COOLDOWN_SECONDS:
                    result.skipped += 1
                    processed += 1
                    await report()
                    continue
                try:
                    payload = self._embed_payload(embed=embed, embeds=embeds)
                    await member.send(content=content or None, view=view, **payload)
                    result.delivered += 1
                    self._last_sent[member.id] = now
                    await log_update_notice(
                        self.bot,
                        title="Update DM Sent",
                        member=member,
                        detail=(
                            f"**{_notice_title(embed=embed, embeds=embeds)}** was delivered "
                            f"during the announcement started by **{actor}**."
                        ),
                    )
                except discord.Forbidden:
                    # Their inbox is closed. Expected, and not an error worth retrying.
                    result.refused += 1
                    result.failures.append(f"{member} has direct messages closed")
                except discord.HTTPException as exc:
                    result.refused += 1
                    result.failures.append(f"{member}: {exc}")
                attempted += 1
                processed += 1
                await report()

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
            await report(force=True)

        logger.info(
            "Announcement by %s: %d delivered, %d refused, %d skipped",
            actor, result.delivered, result.refused, result.skipped,
        )
        return result

    @staticmethod
    def _embed_payload(
        *,
        embed: Optional[discord.Embed],
        embeds: Optional[Sequence[discord.Embed]],
    ) -> dict[str, Any]:
        if embed is not None and embeds:
            raise ValueError("Send either embed or embeds, not both.")
        if embeds:
            selected = list(embeds)
            if len(selected) > 10:
                raise ValueError("Discord allows no more than 10 embeds in one message.")
            return {"embeds": selected}
        if embed is None:
            raise ValueError("An announcement needs at least one embed.")
        return {"embed": embed}


# --- sending it from Discord ------------------------------------------------

def progress_embed(reading: "BroadcastProgress", *, title: str, done: bool = False) -> discord.Embed:
    """The live card an owner watches while a broadcast runs."""
    filled = round(reading.percent / 5)
    bar = "▰" * filled + "▱" * (20 - filled)
    if done:
        headline = "Finished."
    elif reading.seconds_left >= 60:
        headline = f"About {round(reading.seconds_left / 60)} minute(s) left."
    else:
        headline = f"About {round(reading.seconds_left)} second(s) left."
    return discord.Embed(
        title=title,
        description=(
            f"`{bar}`  **{reading.percent}%**\n\n"
            f"**Delivered:** {reading.delivered}\n"
            f"**Refused (DMs closed):** {reading.refused}\n"
            f"**Skipped (already had it):** {reading.skipped}\n"
            f"**Remaining:** {reading.remaining} of {reading.total}\n\n"
            f"{headline}"
        ),
        colour=discord.Colour(0x57F287 if done else DEFAULT_COLOUR),
    )


class ProgressReporter:
    """Keeps one status message current for the whole of a broadcast.

    An interaction token dies after fifteen minutes, and at the deliberate pace
    above that is only a few hundred recipients — so a long announcement would lose
    its progress display exactly when it is most worth watching. The first failure
    to edit moves the display into a direct message to whoever started it, which the
    bot can keep editing for as long as the send runs.
    """

    def __init__(self, interaction: discord.Interaction, title: str) -> None:
        self.interaction = interaction
        self.title = title
        self.fallback: Optional[discord.Message] = None

    async def __call__(self, reading: "BroadcastProgress") -> None:
        await self.show(progress_embed(reading, title=self.title))

    async def show(self, embed: discord.Embed) -> None:
        if self.fallback is not None:
            await self.fallback.edit(embed=embed, view=None)
            return
        try:
            await self.interaction.edit_original_response(embed=embed, view=None)
        except discord.HTTPException:
            # The token has expired. Carry on somewhere the bot can still write.
            self.fallback = await self.interaction.user.send(embed=embed)


@dataclass
class PendingBroadcast:
    """Everything the confirmation steps have to carry to the send."""

    title: str
    embeds: list
    view: Any = None
    content: Optional[str] = None
    actor: str = "owner"
    recipients: int = 0

    @property
    def minutes(self) -> int:
        return max(1, round(self.recipients * SEND_INTERVAL_SECONDS / 60))


class _BroadcastStep(discord.ui.View):
    """One door of the three. Only the owner who opened it may press anything."""

    def __init__(self, bot: Any, owner_id: int, pending: PendingBroadcast) -> None:
        super().__init__(timeout=300)
        self.bot = bot
        self.owner_id = owner_id
        self.pending = pending

    async def interaction_check(self, interaction: discord.Interaction) -> bool:
        if interaction.user.id != self.owner_id:
            await interaction.response.send_message(
                "This confirmation belongs to somebody else.", ephemeral=True
            )
            return False
        return True

    async def cancel(self, interaction: discord.Interaction) -> None:
        await interaction.response.edit_message(
            embed=discord.Embed(
                title="Nothing Was Sent",
                description="> The announcement was cancelled. Nobody was messaged.",
                colour=discord.Colour(0x57F287),
            ),
            view=None,
        )


class BroadcastConfirmOne(_BroadcastStep):
    """First of three. States the size of the thing before anything else."""

    @discord.ui.button(label="Cancel", style=discord.ButtonStyle.success)
    async def stop_here(self, interaction: discord.Interaction, button: discord.ui.Button) -> None:
        await self.cancel(interaction)

    @discord.ui.button(label="Continue (1 of 3)", style=discord.ButtonStyle.secondary)
    async def go(self, interaction: discord.Interaction, button: discord.ui.Button) -> None:
        await interaction.response.edit_message(
            embed=discord.Embed(
                title="Confirmation 2 of 3 — This Reaches Real People",
                description=(
                    f"> **{self.pending.title}** would be sent as a direct message to "
                    f"**{self.pending.recipients} member(s)**.\n\n"
                    "A direct message cannot be recalled, edited or deleted for the "
                    "person who received it. Everyone on that list gets a notification "
                    "on every device they are signed in to.\n\n"
                    f"The send takes about **{self.pending.minutes} minute(s)** and "
                    "cannot be sped up — the pace is what keeps the bot from looking "
                    "like a spammer to Discord."
                ),
                colour=discord.Colour(0xF06000),
            ),
            view=BroadcastConfirmTwo(self.bot, self.owner_id, self.pending),
        )


class BroadcastConfirmTwo(_BroadcastStep):
    """Second of three. The one that names the actual risk."""

    @discord.ui.button(label="Cancel", style=discord.ButtonStyle.success)
    async def stop_here(self, interaction: discord.Interaction, button: discord.ui.Button) -> None:
        await self.cancel(interaction)

    @discord.ui.button(label="Continue (2 of 3)", style=discord.ButtonStyle.secondary)
    async def go(self, interaction: discord.Interaction, button: discord.ui.Button) -> None:
        await interaction.response.edit_message(
            embed=discord.Embed(
                title="Confirmation 3 of 3 — Last Chance",
                description=(
                    f"> Press the red button and **{self.pending.recipients} member(s)** "
                    "start receiving this immediately.\n\n"
                    "Send the wrong thing and the only fix is another announcement, "
                    "which is worse than the mistake.\n\n"
                    "**Preview it first** with `/mgxadmin announce-preview` if you have "
                    "not already read it as a member would."
                ),
                colour=discord.Colour(0xED4245),
            ),
            view=BroadcastConfirmThree(self.bot, self.owner_id, self.pending),
        )


class BroadcastConfirmThree(_BroadcastStep):
    """The last door, and the one that actually sends."""

    @discord.ui.button(label="Cancel", style=discord.ButtonStyle.success)
    async def stop_here(self, interaction: discord.Interaction, button: discord.ui.Button) -> None:
        await self.cancel(interaction)

    @discord.ui.button(label="Send it now", style=discord.ButtonStyle.danger)
    async def confirm(self, interaction: discord.Interaction, button: discord.ui.Button) -> None:
        announcer = announcer_for(self.bot)
        title = f"Sending — {self.pending.title}"
        await interaction.response.edit_message(
            embed=discord.Embed(
                title=title,
                description="> Starting...",
                colour=discord.Colour(DEFAULT_COLOUR),
            ),
            view=None,
        )
        reporter = ProgressReporter(interaction, title)
        result = await announcer.send(
            embeds=self.pending.embeds or None,
            content=self.pending.content,
            actor=self.pending.actor,
            view=self.pending.view,
            progress=reporter,
        )
        summary = discord.Embed(
            title=f"Announcement Finished — {self.pending.title}",
            description=(
                f"**Delivered:** {result.delivered}\n"
                f"**Refused (DMs closed):** {result.refused}\n"
                f"**Skipped (already had it):** {result.skipped}\n"
                + (f"\n**Stopped early:** {result.reason}" if result.stopped_early else "")
            ),
            colour=discord.Colour(0xED4245 if result.stopped_early else 0x57F287),
        )
        await reporter.show(summary)
