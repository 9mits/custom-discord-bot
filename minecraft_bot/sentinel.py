"""The Discord side of Sentinel, the plugin's watch for duplication, hacking and abuse.

The plugin decides what is suspicious; this module makes each incident something
staff can act on. Every incident is stored before anything is sent, written to the
Security stream as one compact card coloured by severity, and carries persistent
buttons to acknowledge it, mark it a false positive, or pull the player's history.
HIGH and CRITICAL incidents mention the configured alert role, with a flood cap so a
burst of findings cannot turn into a burst of pings.

`/mgxstaff security` is the dashboard over the same table: counts by severity, the
riskiest players, and a paged, filterable incident list.

Self-contained like the rest of `minecraft_bot`: stdlib, discord and siblings only.
"""

from __future__ import annotations

import logging
import time
from collections import deque
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any, Mapping, Optional

import discord

from . import logroutes
from .presentation import BRAND_NAME, FOOTER_ICON_URL, branded_send, head_url, info_embed


logger = logging.getLogger("MinecraftAccessBot")

EVENT_PREFIX = "security_"
CONFIG_ALERT_ROLE = "sentinel_alert_role_id"

SEVERITIES = ("LOW", "MEDIUM", "HIGH", "CRITICAL")
SEVERITY_COLOURS: Mapping[str, discord.Colour] = {
    "LOW": discord.Colour.from_rgb(149, 165, 166),
    "MEDIUM": discord.Colour.from_rgb(241, 196, 15),
    "HIGH": discord.Colour.from_rgb(230, 126, 34),
    "CRITICAL": discord.Colour.from_rgb(231, 76, 60),
}
PING_SEVERITIES = frozenset({"HIGH", "CRITICAL"})
#: At most this many alert pings in PING_WINDOW seconds; later cards still post, silently.
PING_LIMIT = 6
PING_WINDOW = 600

STATUS_OPEN = "OPEN"
STATUS_ACKNOWLEDGED = "ACKNOWLEDGED"
STATUS_FALSE_POSITIVE = "FALSE_POSITIVE"
STATUS_LABELS: Mapping[str, str] = {
    STATUS_OPEN: "Open",
    STATUS_ACKNOWLEDGED: "Acknowledged",
    STATUS_FALSE_POSITIVE: "False positive",
}

#: Rule prefixes to the short category shown on a card.
_CATEGORIES: tuple[tuple[str, str], ...] = (
    ("unexplained_gain", "Duplication"),
    ("large_gain", "Economy"),
    ("large_holdings", "Economy"),
    ("duplicate_serial", "Duplication"),
    ("cloned_container", "Duplication"),
    ("illegal_stack", "Duplication"),
    ("illegal_enchant", "Illegal item"),
    ("disconnect_after_drop", "Duplication"),
    ("creative_copy", "Privilege abuse"),
    ("gamemode", "Privilege abuse"),
    ("command_", "Command"),
    ("luckperms", "Permissions"),
    ("security_settings", "Sentinel settings"),
    ("balance_set", "Economy"),
    ("large_deposit", "Economy"),
    ("money_velocity", "Economy"),
    ("anticheat", "Anticheat"),
    ("xray_pattern", "X-ray"),
)

PERIODS: Mapping[str, tuple[str, int]] = {
    "day": ("Last 24 hours", 86_400),
    "week": ("Last 7 days", 7 * 86_400),
    "month": ("Last 30 days", 30 * 86_400),
}
SEVERITY_FILTERS: Mapping[str, tuple[str, Optional[tuple[str, ...]]]] = {
    "all": ("Every severity", None),
    "serious": ("High and critical", ("HIGH", "CRITICAL")),
    "critical": ("Critical only", ("CRITICAL",)),
}
STATUS_FILTERS: Mapping[str, tuple[str, Optional[str]]] = {
    "open": ("Open", STATUS_OPEN),
    "all": ("Every status", None),
    "acknowledged": ("Acknowledged", STATUS_ACKNOWLEDGED),
    "false": ("False positives", STATUS_FALSE_POSITIVE),
}
PAGE_SIZE = 8


def category_for(rule: str) -> str:
    key = str(rule or "").casefold()
    for prefix, label in _CATEGORIES:
        if key.startswith(prefix):
            return label
    return "Security"


def normalize_severity(value: Any) -> str:
    text = str(value or "").strip().upper()
    return text if text in SEVERITIES else "MEDIUM"


@dataclass
class Incident:
    incident_id: str
    rule: str
    severity: str
    title: str
    player_uuid: str = ""
    player_name: str = ""
    discord_id: str = ""
    evidence: list[str] = field(default_factory=list)
    risk: float = 0.0
    repeats: int = 0
    occurred_at: int = 0
    status: str = STATUS_OPEN
    handled_by: str = ""
    handled_at: int = 0
    channel_id: str = ""
    message_id: str = ""

    @classmethod
    def from_event(
        cls,
        event: str,
        *,
        actor_uuid: str,
        actor_name: str,
        summary: str,
        details: Mapping[str, Any],
        occurred_at: int,
        discord_id: int | str = "",
        fallback_id: str = "",
    ) -> Optional["Incident"]:
        if not str(event or "").startswith(EVENT_PREFIX):
            return None
        evidence = [
            str(details[key]).strip()
            for key in sorted(
                (key for key in details if str(key).startswith("evidence_")),
                key=lambda key: int(str(key).split("_", 1)[1]) if str(key).split("_", 1)[1].isdigit() else 99,
            )
            if str(details[key]).strip()
        ]
        try:
            risk = float(details.get("risk") or 0)
        except (TypeError, ValueError):
            risk = 0.0
        try:
            repeats = int(details.get("repeats") or 0)
        except (TypeError, ValueError):
            repeats = 0
        incident_id = "".join(ch for ch in str(details.get("incident") or fallback_id) if ch.isalnum())[:16]
        return cls(
            incident_id=incident_id or f"{int(occurred_at):x}",
            rule=str(event)[len(EVENT_PREFIX):],
            severity=normalize_severity(details.get("severity")),
            title=str(summary or "Suspicious activity").strip()[:200],
            player_uuid=str(actor_uuid or ""),
            player_name=str(actor_name or ""),
            discord_id=str(discord_id or "") if str(discord_id or "") not in ("", "0") else "",
            evidence=evidence,
            risk=risk,
            repeats=repeats,
            occurred_at=int(occurred_at),
        )

    @classmethod
    def from_row(cls, row: Mapping[str, Any]) -> "Incident":
        return cls(
            incident_id=str(row.get("incident_id") or ""),
            rule=str(row.get("rule") or ""),
            severity=normalize_severity(row.get("severity")),
            title=str(row.get("title") or ""),
            player_uuid=str(row.get("player_uuid") or ""),
            player_name=str(row.get("player_name") or ""),
            discord_id=str(row.get("discord_id") or ""),
            evidence=list(row.get("evidence") or []),
            risk=float(row.get("risk") or 0),
            repeats=int(row.get("repeats") or 0),
            occurred_at=int(row.get("occurred_at") or 0),
            status=str(row.get("status") or STATUS_OPEN),
            handled_by=str(row.get("handled_by") or ""),
            handled_at=int(row.get("handled_at") or 0),
            channel_id=str(row.get("channel_id") or ""),
            message_id=str(row.get("message_id") or ""),
        )

    def as_row(self) -> dict[str, Any]:
        return {
            "incident_id": self.incident_id,
            "rule": self.rule,
            "severity": self.severity,
            "title": self.title,
            "player_uuid": self.player_uuid,
            "player_name": self.player_name,
            "discord_id": self.discord_id,
            "evidence": list(self.evidence),
            "risk": self.risk,
            "repeats": self.repeats,
            "occurred_at": self.occurred_at,
        }

    @property
    def subject(self) -> str:
        name = self.player_name or "Unknown"
        return f"{name} (<@{self.discord_id}>)" if self.discord_id else name


def _clip(text: Any, limit: int) -> str:
    value = str(text or "")
    return value if len(value) <= limit else value[: limit - 3] + "..."


def incident_embed(incident: Incident) -> discord.Embed:
    """The compact card: severity, what happened, the evidence, and who."""
    bullets = "\n".join(f"- {_clip(line, 300)}" for line in incident.evidence[:8]) or "- No evidence lines"
    meta = [f"**Player** {incident.subject}"]
    if incident.player_uuid:
        meta.append(f"**Risk** {incident.risk:.0f}")
    if incident.repeats:
        meta.append(f"**Repeats** {incident.repeats}")
    meta.append(f"`#{incident.incident_id}`")
    status = STATUS_LABELS.get(incident.status, incident.status.title())
    if incident.status == STATUS_OPEN:
        status_line = "**Status** Open"
    else:
        handled = f" by <@{incident.handled_by}>" if incident.handled_by else ""
        when = f" <t:{incident.handled_at}:R>" if incident.handled_at else ""
        status_line = f"**Status** {status}{handled}{when}"
    embed = discord.Embed(
        title=_clip(incident.title, 250),
        description=_clip(f"{bullets}\n\n{' · '.join(meta)}\n{status_line}", 4_000),
        colour=SEVERITY_COLOURS.get(incident.severity, SEVERITY_COLOURS["MEDIUM"]),
    )
    if incident.status == STATUS_FALSE_POSITIVE:
        embed.colour = SEVERITY_COLOURS["LOW"]
    embed.set_author(name=f"SENTINEL · {incident.severity} · {category_for(incident.rule)}")
    if incident.occurred_at:
        embed.timestamp = datetime.fromtimestamp(incident.occurred_at, timezone.utc)
    if incident.player_uuid:
        embed.set_thumbnail(url=head_url(incident.player_uuid, incident.player_name))
    embed.set_footer(text=BRAND_NAME, icon_url=FOOTER_ICON_URL)
    return embed


# ---------------------------------------------------------------------------
# Persistent card buttons
# ---------------------------------------------------------------------------

BUTTON_TEMPLATE = r"mgx_sentinel:(?P<action>ack|false|reopen|history):(?P<incident>[A-Za-z0-9]{1,16})"
_BUTTON_LABELS = {
    "ack": ("Acknowledge", discord.ButtonStyle.success),
    "false": ("False positive", discord.ButtonStyle.secondary),
    "reopen": ("Reopen", discord.ButtonStyle.secondary),
    "history": ("Player history", discord.ButtonStyle.primary),
}


class SentinelButton(discord.ui.DynamicItem[discord.ui.Button], template=BUTTON_TEMPLATE):
    """One action on an incident card. Persistent, so cards keep working after a restart."""

    def __init__(self, action: str, incident_id: str, *, item: Optional[discord.ui.Button] = None) -> None:
        self.action = action
        self.incident_id = incident_id
        label, style = _BUTTON_LABELS.get(action, ("Open", discord.ButtonStyle.secondary))
        super().__init__(
            item
            or discord.ui.Button(
                label=label,
                style=style,
                custom_id=f"mgx_sentinel:{action}:{incident_id}",
            )
        )

    @classmethod
    async def from_custom_id(cls, interaction, item, match):  # type: ignore[override]
        return cls(match["action"], match["incident"], item=item)

    async def callback(self, interaction: discord.Interaction) -> None:
        bot = interaction.client
        if not getattr(bot, "is_moderator", lambda _user: False)(interaction.user):
            await interaction.response.send_message(
                **branded_send(info_embed("Staff Only", "> Only Minecraft staff can act on incidents.", error=True)),
                ephemeral=True,
            )
            return
        row = await bot.data.get_security_incident(self.incident_id)
        if row is None:
            await interaction.response.send_message(
                **branded_send(info_embed("Incident Not Found", "> That incident is no longer stored.", error=True)),
                ephemeral=True,
            )
            return
        incident = Incident.from_row(row)
        if self.action == "history":
            await interaction.response.defer(ephemeral=True, thinking=True)
            embed = await history_embed(bot.data, incident)
            await interaction.followup.send(**branded_send(embed), ephemeral=True)
            return
        status = {
            "ack": STATUS_ACKNOWLEDGED,
            "false": STATUS_FALSE_POSITIVE,
            "reopen": STATUS_OPEN,
        }[self.action]
        updated = await bot.data.set_security_status(
            incident.incident_id,
            status,
            interaction.user.id if status != STATUS_OPEN else "",
            now=int(time.time()) if status != STATUS_OPEN else 0,
        )
        refreshed = Incident.from_row(updated or row)
        await interaction.response.edit_message(embed=incident_embed(refreshed), view=incident_view(refreshed))


def incident_view(incident: Incident) -> discord.ui.View:
    view = discord.ui.View(timeout=None)
    if incident.status == STATUS_OPEN:
        view.add_item(SentinelButton("ack", incident.incident_id))
        view.add_item(SentinelButton("false", incident.incident_id))
    else:
        view.add_item(SentinelButton("reopen", incident.incident_id))
    if incident.player_uuid or incident.player_name:
        view.add_item(SentinelButton("history", incident.incident_id))
    return view


async def history_embed(data: Any, incident: Incident) -> discord.Embed:
    filters: dict[str, Any] = {"since": 0}
    if incident.player_uuid:
        filters["player_uuid"] = incident.player_uuid
    else:
        filters["player"] = incident.player_name
    rows = await data.list_security_incidents(limit=12, **filters)
    total = await data.count_security_incidents(**filters)
    lines = [incident_line(Incident.from_row(row)) for row in rows]
    peak = max((float(row.get("risk") or 0) for row in rows), default=0.0)
    embed = info_embed(
        f"Sentinel History · {incident.player_name or 'Unknown'}",
        "\n".join(lines) or "> Nothing else recorded.",
    )
    embed.add_field(name="Incidents", value=str(total), inline=True)
    embed.add_field(name="Peak risk", value=f"{peak:.0f}", inline=True)
    if incident.discord_id:
        embed.add_field(name="Discord", value=f"<@{incident.discord_id}>", inline=True)
    if incident.player_uuid:
        embed.set_thumbnail(url=head_url(incident.player_uuid, incident.player_name))
    return embed


def incident_line(incident: Incident, *, guild_id: int = 0) -> str:
    status = "" if incident.status == STATUS_OPEN else f" · {STATUS_LABELS.get(incident.status, incident.status)}"
    title = _clip(incident.title, 70)
    if guild_id and incident.channel_id and incident.message_id:
        title = f"[{title}](https://discord.com/channels/{guild_id}/{incident.channel_id}/{incident.message_id})"
    who = f" — {incident.player_name}" if incident.player_name else ""
    return f"<t:{incident.occurred_at}:R> `{incident.severity}` {title}{who}{status}"


# ---------------------------------------------------------------------------
# Delivery
# ---------------------------------------------------------------------------


class SentinelFeed:
    """Stores incidents, posts their cards, and decides who is pinged."""

    def __init__(self, bot: Any, *, clock=time.time) -> None:
        self.bot = bot
        self._clock = clock
        self._pings: deque[float] = deque()

    def claim_ping(self) -> bool:
        now = self._clock()
        while self._pings and now - self._pings[0] > PING_WINDOW:
            self._pings.popleft()
        if len(self._pings) >= PING_LIMIT:
            return False
        self._pings.append(now)
        return True

    async def handle(self, incident: Incident) -> bool:
        """Stores and posts one incident. False when it had already been handled."""
        if not await self.bot.data.record_security_incident(incident.as_row()):
            return False
        channel_id = logroutes.resolve(self.bot.settings, "security")
        if not channel_id:
            logger.warning("Sentinel %s incident %s has no Security channel", incident.severity, incident.incident_id)
            return True
        embed = incident_embed(incident)
        channel = await self.bot._configured_channel(channel_id)
        if channel is None or not hasattr(channel, "send"):
            await self.bot._send_configured_log(channel_id, embed, dedupe_key=f"sentinel:{incident.incident_id}")
            return True
        content = None
        mentions = discord.AllowedMentions.none()
        if incident.severity in PING_SEVERITIES:
            target = await self._alert_target(channel, incident)
            if target is not None and self.claim_ping():
                content = target.mention
                mentions = (
                    discord.AllowedMentions(roles=[target], users=False, everyone=False)
                    if isinstance(target, discord.Role)
                    else discord.AllowedMentions(users=[target], roles=False, everyone=False)
                )
        try:
            message = await channel.send(
                content=content, embed=embed, view=incident_view(incident), allowed_mentions=mentions
            )
        except (discord.Forbidden, discord.HTTPException):
            logger.exception("Could not post Sentinel incident %s", incident.incident_id)
            await self.bot._send_configured_log(channel_id, embed, dedupe_key=f"sentinel:{incident.incident_id}")
            return True
        await self.bot.data.set_security_message(incident.incident_id, channel_id, message.id)
        return True

    async def _alert_target(self, channel: Any, incident: Incident) -> Optional[discord.abc.Snowflake]:
        guild = getattr(channel, "guild", None)
        if guild is None:
            return None
        role_id = await self.bot.data.get_config(CONFIG_ALERT_ROLE)
        try:
            role = guild.get_role(int(role_id)) if role_id else None
        except (TypeError, ValueError):
            role = None
        if role is not None:
            return role
        # With no role chosen, only a CRITICAL incident reaches the server owner directly.
        if incident.severity == "CRITICAL":
            return guild.owner
        return None


# ---------------------------------------------------------------------------
# Dashboard
# ---------------------------------------------------------------------------


async def dashboard_embed(
    data: Any,
    *,
    period: str = "week",
    severity: str = "all",
    status: str = "open",
    page: int = 0,
    player: Optional[str] = None,
    guild_id: int = 0,
    now: Optional[int] = None,
) -> tuple[discord.Embed, int]:
    """The dashboard for one filter state, and how many pages that filter has."""
    label, seconds = PERIODS.get(period, PERIODS["week"])
    since = int(now if now is not None else time.time()) - seconds
    severities = SEVERITY_FILTERS.get(severity, SEVERITY_FILTERS["all"])[1]
    status_value = STATUS_FILTERS.get(status, STATUS_FILTERS["open"])[1]
    filters: dict[str, Any] = {"since": since, "severities": severities, "status": status_value}
    if player:
        filters["player"] = player
    counts = await data.security_severity_counts(since=since)
    top = await data.security_top_players(since=since)
    total = await data.count_security_incidents(**filters)
    pages = max(1, -(-total // PAGE_SIZE))
    page = max(0, min(page, pages - 1))
    rows = await data.list_security_incidents(limit=PAGE_SIZE, offset=page * PAGE_SIZE, **filters)

    summary = " · ".join(
        f"**{name.title()}** {counts.get(name, {}).get('total', 0)}"
        + (f" ({counts[name]['open']} open)" if counts.get(name, {}).get("open") else "")
        for name in reversed(SEVERITIES)
    )
    open_serious = sum(counts.get(name, {}).get("open", 0) for name in ("HIGH", "CRITICAL"))
    headline = (
        f"> **{open_serious}** high or critical incident{'s' if open_serious != 1 else ''} still open."
        if open_serious
        else "> No high or critical incidents are waiting."
    )
    embed = info_embed("Sentinel Security", f"{headline}\n{summary}")
    if any(row.get("critical") for row in top):
        embed.colour = SEVERITY_COLOURS["CRITICAL"]
    elif open_serious:
        embed.colour = SEVERITY_COLOURS["HIGH"]
    if top:
        embed.add_field(
            name="Highest risk players",
            value="\n".join(
                f"**{_clip(row.get('player_name') or 'Unknown', 32)}**"
                + (f" <@{row['discord_id']}>" if row.get("discord_id") else "")
                + f" · risk {float(row.get('peak_risk') or 0):.0f} · {int(row.get('incidents') or 0)} incidents"
                + (f" · {int(row['critical'])} critical" if row.get("critical") else "")
                for row in top
            )[:1024],
            inline=False,
        )
    lines = [incident_line(Incident.from_row(row), guild_id=guild_id) for row in rows]
    filter_label = " · ".join(
        part
        for part in (
            label,
            SEVERITY_FILTERS.get(severity, SEVERITY_FILTERS["all"])[0],
            STATUS_FILTERS.get(status, STATUS_FILTERS["open"])[0],
            f"player {player}" if player else "",
        )
        if part
    )
    embed.add_field(
        name=f"Incidents · {total} · page {page + 1}/{pages}",
        value=_clip("\n".join(lines), 1024) or "Nothing matches these filters.",
        inline=False,
    )
    embed.set_footer(text=f"{BRAND_NAME} · {filter_label}", icon_url=FOOTER_ICON_URL)
    return embed, pages


class _FilterSelect(discord.ui.Select):
    def __init__(self, dashboard: "SecurityDashboardView", attribute: str, choices: Mapping[str, Any], row: int) -> None:
        current = getattr(dashboard, attribute)
        super().__init__(
            options=[
                discord.SelectOption(label=value[0], value=key, default=key == current)
                for key, value in choices.items()
            ],
            min_values=1,
            max_values=1,
            row=row,
        )
        self.attribute = attribute

    async def callback(self, interaction: discord.Interaction) -> None:
        view: SecurityDashboardView = self.view  # type: ignore[assignment]
        setattr(view, self.attribute, self.values[0])
        view.page = 0
        await view.redraw(interaction)


class _PageButton(discord.ui.Button):
    def __init__(self, label: str, step: int, *, disabled: bool) -> None:
        super().__init__(label=label, style=discord.ButtonStyle.secondary, disabled=disabled, row=3)
        self.step = step

    async def callback(self, interaction: discord.Interaction) -> None:
        view: SecurityDashboardView = self.view  # type: ignore[assignment]
        view.page += self.step
        await view.redraw(interaction)


class SecurityDashboardView(discord.ui.View):
    """`/mgxstaff security`: ephemeral, owned by whoever opened it."""

    def __init__(self, bot: Any, requester_id: int, *, player: Optional[str] = None, guild_id: int = 0) -> None:
        super().__init__(timeout=900)
        self.bot = bot
        self.requester_id = int(requester_id)
        self.player = player
        self.guild_id = guild_id
        self.period = "week"
        self.severity = "all"
        self.status = "open"
        self.page = 0
        self.pages = 1

    async def render(self) -> discord.Embed:
        embed, self.pages = await dashboard_embed(
            self.bot.data,
            period=self.period,
            severity=self.severity,
            status=self.status,
            page=self.page,
            player=self.player,
            guild_id=self.guild_id,
        )
        self.page = max(0, min(self.page, self.pages - 1))
        self.clear_items()
        self.add_item(_FilterSelect(self, "period", PERIODS, 0))
        self.add_item(_FilterSelect(self, "severity", SEVERITY_FILTERS, 1))
        self.add_item(_FilterSelect(self, "status", STATUS_FILTERS, 2))
        self.add_item(_PageButton("Previous", -1, disabled=self.page <= 0))
        self.add_item(_PageButton("Next", 1, disabled=self.page >= self.pages - 1))
        self.add_item(_PageButton("Refresh", 0, disabled=False))
        return embed

    async def interaction_check(self, interaction: discord.Interaction) -> bool:
        if interaction.user.id == self.requester_id:
            return True
        await interaction.response.send_message(
            **branded_send(info_embed("Not Your Dashboard", "> Run `/mgxstaff security` to open your own.", error=True)),
            ephemeral=True,
        )
        return False

    async def redraw(self, interaction: discord.Interaction) -> None:
        await interaction.response.defer()
        embed = await self.render()
        await interaction.edit_original_response(embed=embed, view=self)
