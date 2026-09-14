"""Live pings: turning Discord members into players at the moment it matters.

A server with a handful of players online loses people to one experience more than any
other: logging in to an empty world. Discord holds far more members than the server
holds at once, so the fix is to tell the ones who want to know, exactly when something
is about to happen:

* a PvP queue has somebody waiting and needs one more;
* an event starts in ten minutes, or has just gone live;
* enough players are online that joining now means company.

Every ping is opt-in through a role the member toggles on the live status panel, and
every kind has a cooldown, so a role nobody regrets holding stays a role people keep.
The panel itself is one message the bot keeps current: whether the server is up, how
many are on, how to join, and what is booked next.
"""

from __future__ import annotations

import logging
import time
from dataclasses import dataclass, field
from typing import Any, Iterable, Optional

import discord

logger = logging.getLogger("MinecraftAccessBot.livepings")

THEME_COLOUR = discord.Color.from_rgb(255, 153, 0)

CONFIG_CHANNEL = "live_ping_channel_id"
CONFIG_MESSAGE = "live_status_message_id"
CONFIG_THRESHOLD = "live_ping_active_threshold"

DEFAULT_THRESHOLD = 5

#: A ping that reaches the bot later than this describes a moment that has passed.
STALE_SECONDS = 5 * 60

#: How far ahead a booked event is announced, as a window the minute loop cannot miss.
SCHEDULE_LEAD_SECONDS = 10 * 60
SCHEDULE_WINDOW_SECONDS = 90

BUTTON_TEMPLATE = r"mgx_liveping:(?P<topic>\w+)"


@dataclass(frozen=True)
class Topic:
    key: str
    role_name: str
    label: str
    description: str
    cooldown_seconds: int

    @property
    def config_key(self) -> str:
        return f"live_ping_role_{self.key}"


TOPICS: tuple[Topic, ...] = (
    Topic("pvp", "PvP Pings", "PvP Queues",
          "Someone is waiting for a PvP match.", 20 * 60),
    Topic("events", "Event Pings", "Events",
          "An event starts in 10 minutes or has just gone live.", 15 * 60),
    Topic("active", "Server Active Pings", "Server Active",
          "Enough players are online to jump in.", 3 * 60 * 60),
)
TOPIC_BY_KEY = {topic.key: topic for topic in TOPICS}


def topic_for_event(event: str) -> Optional[Topic]:
    if event == "ping_pvp_queue":
        return TOPIC_BY_KEY["pvp"]
    if event in {"ping_event_soon", "ping_event_live"}:
        return TOPIC_BY_KEY["events"]
    return None


@dataclass
class PingGate:
    """Per-subject cooldowns, so one busy queue cannot ping every few seconds."""

    last_sent: dict[str, float] = field(default_factory=dict)

    def allow(self, key: str, now: float, cooldown_seconds: int) -> bool:
        previous = self.last_sent.get(key)
        if previous is not None and now - previous < cooldown_seconds:
            return False
        self.last_sent[key] = now
        return True


@dataclass
class ActiveWatch:
    """Fires once when the player count reaches the threshold.

    It re-arms only after the count falls two below, so a population hovering on the
    line does not ping on every join and leave, and never within the topic cooldown.
    """

    armed: bool = True
    last_fired: Optional[float] = None

    def observe(self, count: int, threshold: int, now: float, cooldown_seconds: int) -> bool:
        threshold = max(1, int(threshold))
        if count <= max(0, threshold - 2):
            self.armed = True
            return False
        if (
            count >= threshold
            and self.armed
            and (self.last_fired is None or now - self.last_fired >= cooldown_seconds)
        ):
            self.armed = False
            self.last_fired = now
            return True
        return False


def ping_content(event: str, details: dict[str, Any], *, now: Optional[float] = None) -> Optional[str]:
    """The line under the role mention, or None when the event is not a ping."""
    if event == "ping_pvp_queue":
        mode = str(details.get("mode") or "A PvP match")
        waiting = str(details.get("waiting") or "1")
        required = str(details.get("required") or "")
        count = f" • **{waiting}/{required}** ready" if required else ""
        return f"**{mode}** has a player waiting{count}. Join now and the match can start."
    if event == "ping_event_soon":
        name = str(details.get("event") or "An event")
        minutes = str(details.get("minutes") or "10")
        start = int((now or time.time()) + 60 * int(minutes)) if minutes.isdigit() else None
        when = f"<t:{start}:R>" if start else f"in {minutes} minutes"
        return f"**{name}** starts {when}. Get online now."
    if event == "ping_event_live":
        name = str(details.get("event") or "An event")
        duration = str(details.get("duration") or "")
        suffix = f" for **{duration}**" if duration else ""
        return f"**{name}** is live now{suffix}."
    return None


def active_content(count: int) -> str:
    return f"**{count} players** are online right now. Jump in while it is busy."


def upcoming_schedule(entries: Iterable[Any], now: int, *, limit: int = 3) -> list[tuple[int, str]]:
    """The next enabled bookings, soonest first, as (run_at, label)."""
    rows = [
        (int(entry.run_at), str(entry.label or entry.action).strip() or "Scheduled event")
        for entry in entries
        if getattr(entry, "enabled", False) and int(getattr(entry, "run_at", 0)) > now
    ]
    return sorted(rows)[:limit]


def schedule_due_for_ping(entries: Iterable[Any], now: int) -> list[tuple[str, int, str]]:
    """Bookings about ten minutes out, as (dedupe key, run_at, label)."""
    due = []
    for run_at, label in upcoming_schedule(entries, now, limit=100):
        lead = run_at - now
        if SCHEDULE_LEAD_SECONDS - SCHEDULE_WINDOW_SECONDS <= lead <= SCHEDULE_LEAD_SECONDS:
            due.append((f"schedule:{label}:{run_at}", run_at, label))
    return due


def status_lines(
    *,
    connected: bool,
    online: int,
    java_address: str,
    bedrock_address: str,
    bedrock_port: int,
    upcoming: list[tuple[int, str]],
    now: int,
) -> list[str]:
    state = "**Online**" if connected else "**Offline**"
    lines = [
        f"**Server** {state}",
        f"**Players online** {online if connected else 0}",
        f"**Java** `{java_address}`",
        f"**Bedrock** `{bedrock_address}` port `{bedrock_port}`",
    ]
    if upcoming:
        lines.append("")
        lines.append("**Coming up**")
        lines.extend(f"• {label} — <t:{run_at}:R>" for run_at, label in upcoming)
    lines.append("")
    lines.append(f"-# Updated <t:{now}:R>")
    return lines


class PingToggleButton(
    discord.ui.DynamicItem[discord.ui.Button],
    template=BUTTON_TEMPLATE,
):
    """Adds or removes one ping role. Persistent, so the panel survives a restart."""

    def __init__(self, topic: str, *, item: Optional[discord.ui.Button] = None) -> None:
        self.topic = topic
        label = TOPIC_BY_KEY[topic].label if topic in TOPIC_BY_KEY else topic.title()
        super().__init__(
            item
            or discord.ui.Button(
                label=label,
                style=discord.ButtonStyle.secondary,
                custom_id=f"mgx_liveping:{topic}",
            )
        )

    @classmethod
    async def from_custom_id(cls, interaction, item, match):  # type: ignore[override]
        return cls(match["topic"], item=item)

    async def callback(self, interaction: discord.Interaction) -> None:
        topic = TOPIC_BY_KEY.get(self.topic)
        guild = interaction.guild
        member = interaction.user
        if topic is None or guild is None or not isinstance(member, discord.Member):
            await interaction.response.send_message("That option is no longer available.", ephemeral=True)
            return
        role_id = await interaction.client.data.get_config(topic.config_key)
        role = guild.get_role(int(role_id)) if role_id else None
        if role is None:
            await interaction.response.send_message(
                "This ping has not been set up yet.", ephemeral=True
            )
            return
        try:
            if role in member.roles:
                await member.remove_roles(role, reason="Live ping opt-out")
                message = f"You will no longer be pinged for **{topic.label}**."
            else:
                await member.add_roles(role, reason="Live ping opt-in")
                message = f"You will be pinged when: {topic.description.lower()}"
        except discord.HTTPException:
            await interaction.response.send_message(
                "I could not change that role. An administrator needs to check my permissions.",
                ephemeral=True,
            )
            return
        await interaction.response.send_message(message, ephemeral=True)


class LiveStatusView(discord.ui.LayoutView):
    """The one live message: status up top, the three opt-in toggles underneath."""

    def __init__(self, lines: list[str], brand: str) -> None:
        super().__init__(timeout=None)
        container = discord.ui.Container(accent_colour=THEME_COLOUR)
        container.add_item(discord.ui.TextDisplay(f"## {brand} — Live"))
        container.add_item(discord.ui.TextDisplay("\n".join(lines)))
        container.add_item(discord.ui.Separator())
        container.add_item(
            discord.ui.TextDisplay(
                "**Get pinged**\n"
                + "\n".join(f"• **{topic.label}** — {topic.description}" for topic in TOPICS)
            )
        )
        row = discord.ui.ActionRow()
        for topic in TOPICS:
            row.add_item(PingToggleButton(topic.key))
        container.add_item(row)
        self.add_item(container)


class LivePings:
    """Owns the cooldowns, the active-player watch and the live status message."""

    def __init__(self, bot: Any) -> None:
        self.bot = bot
        self.gate = PingGate()
        self.active = ActiveWatch()

    async def _channel(self) -> Optional[discord.TextChannel]:
        channel_id = await self.bot.data.get_config(CONFIG_CHANNEL)
        if not channel_id:
            return None
        channel = self.bot.get_channel(int(channel_id))
        return channel if isinstance(channel, discord.TextChannel) else None

    async def _send(self, topic: Topic, content: str) -> bool:
        channel = await self._channel()
        if channel is None:
            return False
        role_id = await self.bot.data.get_config(topic.config_key)
        role = channel.guild.get_role(int(role_id)) if role_id else None
        if role is None:
            return False
        try:
            await channel.send(
                f"{role.mention} {content}",
                allowed_mentions=discord.AllowedMentions(roles=[role], users=False, everyone=False),
            )
        except discord.HTTPException:
            logger.warning("Could not send a %s live ping", topic.key)
            return False
        return True

    async def handle_plugin_event(
        self, event: str, details: dict[str, Any], occurred_at: int
    ) -> bool:
        """True when the event was a ping, handled or deliberately dropped."""
        topic = topic_for_event(event)
        if topic is None:
            return False
        now = time.time()
        if occurred_at and now - int(occurred_at) > STALE_SECONDS:
            return True
        subject = str(details.get("mode") or details.get("event") or event)
        if not self.gate.allow(f"{event}:{subject}", now, topic.cooldown_seconds):
            return True
        content = ping_content(event, details, now=now)
        if content:
            await self._send(topic, content)
        return True

    async def observe_online(self, online_count: int) -> None:
        threshold = await self.bot.data.get_config(CONFIG_THRESHOLD, DEFAULT_THRESHOLD)
        topic = TOPIC_BY_KEY["active"]
        if self.active.observe(int(online_count), int(threshold or DEFAULT_THRESHOLD),
                               time.time(), topic.cooldown_seconds):
            await self._send(topic, active_content(int(online_count)))

    async def ping_schedule(self, entries: Iterable[Any]) -> None:
        now = int(time.time())
        topic = TOPIC_BY_KEY["events"]
        for key, run_at, label in schedule_due_for_ping(entries, now):
            if self.gate.allow(key, now, SCHEDULE_LEAD_SECONDS * 2):
                await self._send(topic, f"**{label}** starts <t:{run_at}:R>. Get online now.")

    async def refresh_status(self, *, online: int, upcoming: list[tuple[int, str]]) -> Optional[str]:
        channel = await self._channel()
        if channel is None:
            return "No live ping channel is configured."
        settings = self.bot.settings
        from .presentation import BRAND_NAME

        view = LiveStatusView(
            status_lines(
                connected=bool(self.bot.bridge.connected),
                online=online,
                java_address=settings.java_address,
                bedrock_address=settings.bedrock_address,
                bedrock_port=settings.bedrock_port,
                upcoming=upcoming,
                now=int(time.time()),
            ),
            BRAND_NAME,
        )
        message_id = await self.bot.data.get_config(CONFIG_MESSAGE)
        if message_id:
            try:
                message = await channel.fetch_message(int(message_id))
                await message.edit(view=view)
                return None
            except discord.NotFound:
                pass
            except discord.HTTPException:
                return "I could not update the live status message."
        try:
            posted = await channel.send(view=view)
        except discord.HTTPException:
            return "I could not post in that channel. Check my permissions there."
        await self.bot.data.set_config(CONFIG_MESSAGE, posted.id)
        return None
