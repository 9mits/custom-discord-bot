"""Command audit trail for the Minecraft access bot.

Every `/minecraft`, `/mcstaff`, and `/mcadmin` invocation, review button, and modal
submission produces a record here. Routine ones go to the activity log; access-changing and failed ones
go to the quieter important log.

Self-contained by design: this module imports only stdlib, discord, and sibling
`minecraft_bot` modules, so the Minecraft bot stays independent of the moderation
bot's `core/` and `cogs/` packages.
"""

from __future__ import annotations

import asyncio
import logging
import time
from dataclasses import dataclass, field, replace
from typing import Any, Iterable, Mapping, Optional, Sequence

import discord
from discord import InteractionType, app_commands

from . import logroutes
from .presentation import branded_send, head_url, info_embed


logger = logging.getLogger("MinecraftAccessBot")

# Option values whose *name* matches one of these are never written to a channel or
# the database.
SECRET_OPTION_PARTS = ("token", "secret", "password", "credential", "webhook")
REDACTED = "[redacted]"
OPTION_VALUE_LIMIT = 200

SOURCE_COMMAND = "command"
SOURCE_COMPONENT = "component"
SOURCE_MODAL = "modal"
#: Something a player did in game, reported over the bridge rather than by an interaction.
SOURCE_SERVER = "server"

OUTCOME_SUCCESS = "success"
OUTCOME_FAILED = "failed"
OUTCOME_DENIED = "denied"

RISK_READ_ONLY = "read_only"
RISK_CONFIGURATION = "configuration"
RISK_MODERATE = "moderate"
RISK_DESTRUCTIVE = "destructive"

# The Minecraft bot has no shared action registry, so risk is declared here.
# Anything absent is treated as read-only.
COMMAND_RISK: Mapping[str, str] = {
    "minecraft cancel": RISK_DESTRUCTIVE,
    "mcstaff cancel": RISK_DESTRUCTIVE,
    "mcstaff revoke": RISK_DESTRUCTIVE,
    "mcstaff unlink": RISK_DESTRUCTIVE,
    "mcstaff kick": RISK_DESTRUCTIVE,
    "mcstaff mute": RISK_DESTRUCTIVE,
    "mcstaff ban": RISK_DESTRUCTIVE,
    "mcstaff tempban": RISK_DESTRUCTIVE,
    "mcstaff unban": RISK_DESTRUCTIVE,
    "mcstaff broadcast": RISK_DESTRUCTIVE,
    "mcstaff update": RISK_MODERATE,
    "mcstaff heal": RISK_MODERATE,
    "mcstaff retry": RISK_MODERATE,
    "minecraft clan invite": RISK_MODERATE,
    "minecraft clan kick": RISK_MODERATE,
    "minecraft clan promote": RISK_MODERATE,
    "minecraft clan demote": RISK_MODERATE,
    "minecraft clan coowner": RISK_MODERATE,
    "minecraft clan uncoowner": RISK_MODERATE,
    "minecraft clan transfer": RISK_MODERATE,
    "minecraft clan rename": RISK_MODERATE,
    "minecraft clan color": RISK_MODERATE,
    "minecraft clan disband": RISK_MODERATE,
    "minecraft clan leave": RISK_MODERATE,
    "mcadmin setup": RISK_CONFIGURATION,
    "mcadmin log-channel": RISK_CONFIGURATION,
    "mcadmin logs": RISK_CONFIGURATION,
    "mcadmin log-route": RISK_CONFIGURATION,
    "mcadmin chat-channel": RISK_CONFIGURATION,
    "mcadmin leaderboard": RISK_CONFIGURATION,
    "mcadmin wipe": RISK_DESTRUCTIVE,
    "mcadmin maintenance": RISK_DESTRUCTIVE,
    "mcadmin event": RISK_CONFIGURATION,
    "mcadmin information": RISK_CONFIGURATION,
    "mcadmin cleanheads": RISK_CONFIGURATION,
}

# In-game actions reported over the bridge. Everything absent is routine, which is the
# common case: founding a clan or donating to one belongs in the activity log, not in
# the quieter important log beside revocations and bans.
SERVER_EVENT_RISK: Mapping[str, str] = {
    "clan_disband": RISK_DESTRUCTIVE,
    "data_reset": RISK_DESTRUCTIVE,
    "rank_hold": RISK_CONFIGURATION,
    "rank_release": RISK_CONFIGURATION,
    "admin_pvp": RISK_CONFIGURATION,
    "clan_kick": RISK_MODERATE,
    "clan_transfer": RISK_MODERATE,
    "clan_promote": RISK_MODERATE,
    "clan_demote": RISK_MODERATE,
    "clan_coowner": RISK_MODERATE,
    "clan_uncoowner": RISK_MODERATE,
    "clan_upgrade": RISK_MODERATE,
    "clan_roster_buy": RISK_MODERATE,
    "crate_key_grant": RISK_MODERATE,
    "lootbox_key_grant": RISK_MODERATE,
}

# Titles for the events Paper reports. Anything unmapped falls back to its own name,
# so a new event type logs sensibly before this table catches up with it.
SERVER_EVENT_TITLES: Mapping[str, str] = {
    "clan_create": "Clan Founded",
    "clan_disband": "Clan Disbanded",
    "clan_rename": "Clan Renamed",
    "clan_color": "Clan Colour Changed",
    "clan_invite": "Clan Invite Sent",
    "clan_join": "Clan Joined",
    "clan_leave": "Clan Left",
    "clan_kick": "Clan Member Removed",
    "clan_promote": "Clan Staff Promoted",
    "clan_demote": "Clan Staff Demoted",
    "clan_coowner": "Clan Co-Owner Assigned",
    "clan_uncoowner": "Clan Co-Owner Removed",
    "clan_transfer": "Clan Leadership Transferred",
    "clan_donate": "Clan Donation",
    "clan_upgrade": "Clan Level Bought",
    "clan_roster_buy": "Clan Roster Slot Bought",
    "admin_pvp": "PvP Pinned",
    "rank_hold": "Rank Sync Held",
    "rank_release": "Rank Sync Released",
    "data_reset": "Server Data Reset",
    "shop_buy": "Shop Purchase",
    "shop_sell": "Items Sold",
    "crate_key_grant": "Crate Keys Issued",
    "crate_rare_win": "Rare Crate Reward Won",
    "lootbox_key_grant": "Crate Keys Issued",
    "lootbox_rare_win": "Rare Crate Reward Won",
    "crate_open": "Crate Opened",
    "airdrop_open": "Amethyst Airdrop Opened",
    "crate_reward": "Crate Reward Won",
    "player_kill": "Player Killed",
    "player_death": "Player Died",
    "boss_kill": "Boss Defeated",
    "mobs_killed": "Mobs Killed",
    "rare_ore": "Rare Ore Found",
    "ores_mined": "Ores Mined",
    "advancement": "Advancement Earned",
    "level_milestone": "Experience Milestone",
    "cosmetic_equip": "Cosmetic Equipped",
    "cosmetic_steal": "Cosmetic Stolen",
}


def server_event_risk(event: str) -> str:
    return SERVER_EVENT_RISK.get(str(event).strip().casefold(), RISK_READ_ONLY)


def server_event_title(event: str) -> str:
    key = str(event).strip().casefold()
    mapped = SERVER_EVENT_TITLES.get(key)
    if mapped:
        return f"Minecraft {mapped}"
    return f"Minecraft {key.replace('_', ' ').title()}" if key else "Minecraft Server Action"

# Review-panel controls that change a member's access. Matched against the view or
# item class name so button clicks are tiered like the equivalent command.
DESTRUCTIVE_COMPONENT_HINTS = ("approve", "deny", "revoke", "cancel", "unlink")

# Option names that identify the member an action was aimed at, most specific first.
_TARGET_OPTION_NAMES = ("user", "member", "target", "application")

_UI_NAME_SUFFIXES = ("Button", "Select", "Modal", "View", "Container")

# Opening the application, reading/agreeing to the rules, and submitting its
# modal are navigation within one workflow. The application lifecycle has its
# own detailed submission, verification, and decision logs, so logging these
# clicks as separate commands only creates misleading duplicates.
_ROUTINE_APPLICATION_ITEMS = {"VerifyButton", "EditionSelection"}
_ROUTINE_APPLICATION_VIEWS = {"RulesAgreementView"}
_ROUTINE_APPLICATION_MODALS = {"MinecraftApplicationModal"}


@dataclass(frozen=True)
class CommandAuditRecord:
    source: str
    command: str
    user_id: int
    user_label: str
    channel_id: Optional[int] = None
    guild_id: Optional[int] = None
    target_id: Optional[int] = None
    options: tuple[tuple[str, str], ...] = ()
    outcome: str = OUTCOME_SUCCESS
    risk: str = RISK_READ_ONLY
    duration_ms: int = 0
    correlation_id: Optional[str] = None
    detail: Optional[str] = None
    created_at: int = field(default_factory=lambda: int(time.time()))

    @property
    def failed(self) -> bool:
        return self.outcome in (OUTCOME_FAILED, OUTCOME_DENIED)

    @property
    def important(self) -> bool:
        """Whether this belongs in the dedicated important-command channel."""
        return self.risk == RISK_DESTRUCTIVE or self.failed

    def option_summary(self) -> str:
        if not self.options:
            return ""
        return " · ".join(f"{name}: {value}" for name, value in self.options)


def truncate(value: Any, limit: int) -> str:
    text = str(value if value is not None else "")
    if len(text) <= limit:
        return text
    return text[: max(0, limit - 3)] + "..."


def risk_for(command_name: str) -> str:
    return COMMAND_RISK.get(str(command_name).strip().casefold(), RISK_READ_ONLY)


def _is_secret_option(name: str) -> bool:
    lowered = str(name).casefold()
    return any(part in lowered for part in SECRET_OPTION_PARTS)


def redact_options(
    options: Sequence[tuple[str, Any]],
    *,
    redact: bool = True,
) -> tuple[tuple[str, str], ...]:
    """Drop secret-looking values and bound every option to a loggable length."""
    cleaned: list[tuple[str, str]] = []
    for name, value in options:
        label = str(name)
        if redact and _is_secret_option(label):
            cleaned.append((label, REDACTED))
            continue
        cleaned.append((label, truncate(value, OPTION_VALUE_LIMIT) or "—"))
    return tuple(cleaned)


def flatten_options(data: Optional[Mapping[str, Any]]) -> list[tuple[str, Any]]:
    """Pull the leaf options out of a raw interaction payload.

    `/minecraft <sub>` arrives as a subcommand wrapper (type 1) holding the real
    options one level down, so wrappers are unwrapped rather than reported.
    """
    raw = (data or {}).get("options") or []
    queue: list[Any] = list(raw) if isinstance(raw, list) else []
    flattened: list[tuple[str, Any]] = []
    while queue:
        option = queue.pop(0)
        if not isinstance(option, Mapping):
            continue
        if option.get("type") in (1, 2):
            nested = option.get("options") or []
            if isinstance(nested, list):
                queue = list(nested) + queue
            continue
        if "value" in option:
            flattened.append((str(option.get("name") or "option"), option.get("value")))
    return flattened


def resolve_target_id(interaction: Any, options: Sequence[tuple[str, Any]]) -> Optional[int]:
    resolved = ((getattr(interaction, "data", None) or {}).get("resolved") or {})
    users = resolved.get("users") if isinstance(resolved, Mapping) else None
    if isinstance(users, Mapping) and users:
        try:
            return int(next(iter(users)))
        except (TypeError, ValueError):
            pass
    lookup = {str(name).casefold(): value for name, value in options}
    for name in _TARGET_OPTION_NAMES:
        if name in lookup:
            try:
                return int(str(lookup[name]).strip())
            except (TypeError, ValueError):
                continue
    return None


def command_name_for(interaction: Any) -> str:
    command = getattr(interaction, "command", None)
    qualified = getattr(command, "qualified_name", None)
    if qualified:
        return str(qualified)

    data: Mapping[str, Any] = getattr(interaction, "data", None) or {}
    names = [str(data.get("name") or "")]
    options = data.get("options") or []
    while options and isinstance(options, list):
        nested = next(
            (option for option in options if isinstance(option, Mapping) and option.get("type") in (1, 2)),
            None,
        )
        if nested is None:
            break
        names.append(str(nested.get("name") or ""))
        options = nested.get("options") or []
    return " ".join(name for name in names if name) or "unknown"


def humanize_ui_name(name: str) -> str:
    """`ApproveApplicationButton` -> `Approve Application`."""
    trimmed = str(name or "Component")
    for suffix in _UI_NAME_SUFFIXES:
        if trimmed.endswith(suffix) and len(trimmed) > len(suffix):
            trimmed = trimmed[: -len(suffix)]
            break
    spaced = "".join(
        f" {char}" if index and char.isupper() and not trimmed[index - 1].isupper() else char
        for index, char in enumerate(trimmed)
    ).strip()
    return spaced or str(name or "Component")


def component_label(view: Any, item: Any) -> str:
    control = getattr(item, "label", None) or getattr(item, "placeholder", None)
    if not control:
        control = humanize_ui_name(type(item).__name__)
    return f"{humanize_ui_name(type(view).__name__)} → {control}"


def component_risk(label: str) -> str:
    lowered = str(label).casefold()
    if any(hint in lowered for hint in DESTRUCTIVE_COMPONENT_HINTS):
        return RISK_DESTRUCTIVE
    return RISK_READ_ONLY


def _user_label(user: Any) -> str:
    if user is None:
        return "Unknown"
    for attribute in ("global_name", "display_name", "name"):
        value = getattr(user, attribute, None)
        if value:
            return str(value)
    return str(getattr(user, "id", "Unknown"))


def build_record(
    interaction: Any,
    *,
    source: str = SOURCE_COMMAND,
    command: Optional[str] = None,
    outcome: str = OUTCOME_SUCCESS,
    risk: Optional[str] = None,
    duration_ms: int = 0,
    correlation_id: Optional[str] = None,
    detail: Optional[str] = None,
    redact: bool = True,
) -> CommandAuditRecord:
    name = command or command_name_for(interaction)
    raw_options = flatten_options(getattr(interaction, "data", None)) if source == SOURCE_COMMAND else []
    user = getattr(interaction, "user", None)
    channel_id = getattr(interaction, "channel_id", None)
    guild = getattr(interaction, "guild", None)

    return CommandAuditRecord(
        source=source,
        command=name,
        user_id=int(getattr(user, "id", 0) or 0),
        user_label=_user_label(user),
        channel_id=int(channel_id) if channel_id else None,
        guild_id=int(getattr(guild, "id", 0)) if guild is not None else None,
        target_id=resolve_target_id(interaction, raw_options),
        options=redact_options(raw_options, redact=redact),
        outcome=outcome,
        risk=risk if risk is not None else risk_for(name),
        duration_ms=max(0, int(duration_ms)),
        correlation_id=correlation_id,
        detail=detail,
    )


# ---------------------------------------------------------------------------
# Rendering
# ---------------------------------------------------------------------------

_OUTCOME_LABELS = {
    OUTCOME_SUCCESS: "Processed",
    OUTCOME_FAILED: "Failed",
    OUTCOME_DENIED: "Denied",
}

_OUTCOME_CODES = {
    OUTCOME_SUCCESS: "OK",
    OUTCOME_FAILED: "FAILED",
    OUTCOME_DENIED: "DENIED",
}


def _action_title(record: CommandAuditRecord) -> str:
    command = str(record.command).strip()
    for prefix in ("minecraft ", "mcstaff ", "mcadmin "):
        if command.casefold().startswith(prefix):
            return f"Minecraft {command[len(prefix):].replace('-', ' ').title()}"
    return command.replace("→", "—") or "Minecraft Action"


def build_action_embed(record: CommandAuditRecord) -> discord.Embed:
    source_label = {
        SOURCE_COMMAND: "command",
        SOURCE_COMPONENT: "panel action",
        SOURCE_MODAL: "form submission",
    }.get(record.source, "action")
    outcome = _OUTCOME_LABELS.get(record.outcome, str(record.outcome).title())
    if record.outcome == OUTCOME_SUCCESS:
        summary = f"> The bot processed this Minecraft {source_label} without an internal error."
    elif record.outcome == OUTCOME_DENIED:
        summary = f"> This Minecraft {source_label} was refused before it could make a change."
    else:
        summary = f"> This Minecraft {source_label} could not be completed."
    embed = info_embed(_action_title(record), summary, error=record.failed)
    embed.add_field(
        name="Actor",
        value=f"<@{record.user_id}> (`{record.user_id}`)",
        inline=True,
    )
    if record.target_id:
        embed.add_field(
            name="Target",
            value=f"<@{record.target_id}> (`{record.target_id}`)",
            inline=True,
        )
    embed.add_field(name="Outcome", value=outcome, inline=True)
    action = f"/{record.command}" if record.source == SOURCE_COMMAND else record.command
    embed.add_field(name="Action", value=f"`{truncate(action, 200)}`", inline=True)
    embed.add_field(
        name="Category",
        value=str(record.risk).replace("_", " ").title(),
        inline=True,
    )
    embed.add_field(name="Execution Time", value=f"{record.duration_ms} ms", inline=True)
    if record.channel_id:
        embed.add_field(
            name="Channel",
            value=f"<#{record.channel_id}> (`{record.channel_id}`)",
            inline=False,
        )
    if record.options:
        arguments = "\n".join(
            f"**{str(name).replace('_', ' ').title()}:** {value}"
            for name, value in record.options
        )
        embed.add_field(name="Arguments", value=truncate(arguments, 1024), inline=False)
    if record.failed:
        failure = record.detail or "No additional error detail was available."
        if record.correlation_id:
            failure += f"\nReference: `{record.correlation_id}`"
        embed.add_field(name="Failure", value=truncate(failure, 1024), inline=False)
    return embed


def build_server_event_embed(
    record: CommandAuditRecord,
    *,
    minecraft_username: str,
    summary: str,
) -> discord.Embed:
    """Renders an in-game action for the activity log.

    Kept apart from :func:`build_action_embed` because the two describe different
    things: that one reports whether the bot processed a Discord interaction, this one
    reports what a player did on the server. The server sends the sentence already
    written, so this only frames it.
    """
    embed = info_embed(
        server_event_title(record.command),
        f"> {truncate(summary, 3000)}" if summary else "> A player acted on the Minecraft server.",
    )
    actor = minecraft_username or record.user_label or "Unknown player"
    if record.user_id:
        actor = f"{actor} — <@{record.user_id}>"
    embed.add_field(name="Player", value=truncate(actor, 1024), inline=True)
    embed.add_field(name="Source", value="In game", inline=True)
    embed.add_field(
        name="Category",
        value=str(record.risk).replace("_", " ").title(),
        inline=True,
    )
    if record.options:
        details = "\n".join(
            f"**{str(name).replace('_', ' ').title()}:** {value}"
            for name, value in record.options
        )
        embed.add_field(name="Details", value=truncate(details, 1024), inline=False)
    embed.add_field(name="When", value=f"<t:{record.created_at}:f>", inline=False)
    return embed


async def deliver_server_event(
    client: Any,
    record: CommandAuditRecord,
    *,
    minecraft_uuid: str,
    minecraft_username: str,
    summary: str,
) -> None:
    """Persists an in-game action and writes it to the activity log.

    Persistence completes before Paper acknowledges the event. Channel delivery remains
    queued/best-effort, so a temporary Discord outage does not lose the audit record.
    """
    await _persist(client, record)

    settings = getattr(client, "settings", None)
    if settings is None:
        return
    # The server says which category the action belongs to; the routing table says
    # which channel that category is read in.
    channel_id = logroutes.resolve(
        settings, record.correlation_id or "server", important=record.important
    )
    if not channel_id:
        return

    embed = build_server_event_embed(
        record, minecraft_username=minecraft_username, summary=summary
    )
    if minecraft_uuid:
        embed.set_thumbnail(url=head_url(minecraft_uuid, minecraft_username))
    await _send(client, channel_id, embed)


def format_record(record: CommandAuditRecord) -> str:
    parts = [
        f"`{_OUTCOME_CODES.get(record.outcome, record.outcome.upper())}`",
        f"**{record.command}**",
        f"by <@{record.user_id}>",
    ]
    if record.target_id:
        parts.append(f"on <@{record.target_id}>")
    if record.channel_id:
        parts.append(f"in <#{record.channel_id}>")
    line = " ".join(parts)
    summary = record.option_summary()
    if summary:
        line += f"\n{truncate(summary, 300)}"
    if record.failed and record.detail:
        failure = record.detail
        if record.correlation_id:
            failure += f" · ref `{record.correlation_id}`"
        line += f"\n{failure}"
    return line


def build_batch_embed(records: Sequence[CommandAuditRecord]) -> discord.Embed:
    lines = [f"<t:{record.created_at}:T> {format_record(record)}" for record in records]
    return info_embed(
        f"Command Log ({len(records)})",
        truncate("\n\n".join(lines), 4000),
    )


def build_important_embed(record: CommandAuditRecord) -> discord.Embed:
    return build_action_embed(record)


def build_command_log_embed(rows: Sequence[Mapping[str, Any]], *, total: int) -> discord.Embed:
    if not rows:
        return info_embed(
            "Command Log",
            "> No command invocations match that filter yet.",
        )
    lines = []
    for row in rows:
        outcome = _OUTCOME_LABELS.get(str(row.get("outcome")), str(row.get("outcome")))
        line = (
            f"`{outcome}` **{row.get('command')}** by <@{row.get('actor_discord_id')}> "
            f"<t:{int(row.get('created_at') or 0)}:R>"
        )
        if row.get("target_discord_id"):
            line += f" on <@{row['target_discord_id']}>"
        lines.append(line)
    embed = info_embed("Command Log", truncate("\n".join(lines), 4000))
    embed.add_field(name="Shown", value=str(len(rows)), inline=True)
    embed.add_field(name="Matching Records", value=str(total), inline=True)
    return embed


# ---------------------------------------------------------------------------
# Delivery
# ---------------------------------------------------------------------------


async def deliver(client: Any, record: CommandAuditRecord) -> None:
    """Persist the record, then write it to whichever log channels are configured.

    Auditing is best-effort: a logging failure must never take a command down.
    """
    try:
        await _persist(client, record)
    except Exception:
        logger.exception("Could not persist Minecraft command audit for %s", record.command)

    settings = getattr(client, "settings", None)
    if settings is None:
        return

    targets = [logroutes.resolve(settings, "command", important=record.important)]

    embed = build_action_embed(record)
    await _set_player_thumbnail(client, embed, record.target_id or record.user_id)
    for channel_id in (channel_id for channel_id in targets if channel_id):
        await _send(client, channel_id, embed)


async def _set_player_thumbnail(client: Any, embed: discord.Embed, discord_user_id: int) -> None:
    data = getattr(client, "data", None)
    reader = getattr(data, "list_accounts_for_user", None)
    if reader is None:
        return
    try:
        accounts = await reader(discord_user_id)
    except Exception:
        logger.exception("Could not resolve the Minecraft skin for command audit %s", discord_user_id)
        return
    if not accounts:
        return
    minecraft_uuid = str(accounts[0].get("minecraft_uuid") or "").strip()
    username = str(accounts[0].get("current_username") or "").strip()
    if minecraft_uuid:
        embed.set_thumbnail(url=head_url(minecraft_uuid, username))


def schedule_delivery(client: Any, record: CommandAuditRecord) -> None:
    """Queue best-effort auditing without extending interaction response time."""
    scheduler = getattr(client, "spawn_background_task", None)
    coroutine = deliver(client, record)
    if scheduler is not None:
        scheduler(coroutine, name=f"minecraft-audit:{record.source}")
        return
    task = asyncio.create_task(coroutine, name=f"minecraft-audit:{record.source}")

    def report_failure(completed: asyncio.Task) -> None:
        if completed.cancelled():
            return
        try:
            completed.result()
        except Exception:
            logger.exception("Minecraft audit delivery failed")

    task.add_done_callback(report_failure)


async def _persist(client: Any, record: CommandAuditRecord) -> None:
    data = getattr(client, "data", None)
    writer = getattr(data, "record_command_log", None)
    if writer is None:
        return
    await writer(record)


async def _send(client: Any, channel_id: int, embed: discord.Embed) -> None:
    send_log = getattr(client, "_send_configured_log", None)
    if send_log is not None:
        try:
            await send_log(channel_id, embed)
        except Exception:
            logger.exception("Could not write Minecraft command audit to channel %s", channel_id)
        return

    channel = client.get_channel(channel_id)
    if channel is None or not hasattr(channel, "send"):
        return
    try:
        await channel.send(**branded_send(embed), allowed_mentions=discord.AllowedMentions.none())
    except Exception:
        logger.exception("Could not write Minecraft command audit to channel %s", channel_id)


# ---------------------------------------------------------------------------
# Interaction hooks
# ---------------------------------------------------------------------------


class MinecraftCommandTree(app_commands.CommandTree):
    """Times every command and writes an audit record on the way out."""

    async def _call(self, interaction: discord.Interaction) -> None:
        if interaction.type is InteractionType.autocomplete:
            await super()._call(interaction)
            return

        started_at = time.perf_counter()
        outcome = OUTCOME_SUCCESS
        detail: Optional[str] = None
        try:
            await super()._call(interaction)
        except Exception as error:
            outcome = OUTCOME_FAILED
            detail = type(error).__name__
            raise
        finally:
            try:
                record = build_record(
                    interaction,
                    source=SOURCE_COMMAND,
                    outcome=outcome,
                    duration_ms=int((time.perf_counter() - started_at) * 1000),
                    detail=detail,
                    correlation_id=f"mc-{interaction.id:x}" if outcome == OUTCOME_FAILED else None,
                )
                schedule_delivery(self.client, record)
            except Exception:
                logger.exception("Minecraft command audit failed")


def install_component_audit(client: Any) -> None:
    """Audit button, dropdown, and modal use on the Minecraft bot's panels.

    discord.ui dispatch is process-global, so every callback is guarded on the
    interaction's client being this bot. The Minecraft bot runs in its own process
    and the guard keeps the patch inert anywhere else.
    """
    if getattr(discord.ui.View, "_mc_audit_wrapped", False):
        return

    original_view_task = discord.ui.View._scheduled_task
    original_modal_task = discord.ui.Modal._scheduled_task

    def _is_target(interaction: Any) -> bool:
        return getattr(interaction, "client", None) is client

    async def audited_view_task(view, item, interaction):
        if not _is_target(interaction):
            return await original_view_task(view, item, interaction)
        started_at = time.perf_counter()
        outcome = OUTCOME_SUCCESS
        detail = None
        try:
            return await original_view_task(view, item, interaction)
        except Exception as error:
            outcome = OUTCOME_FAILED
            detail = type(error).__name__
            raise
        finally:
            routine_navigation = outcome == OUTCOME_SUCCESS and (
                type(item).__name__ in _ROUTINE_APPLICATION_ITEMS
                or type(view).__name__ in _ROUTINE_APPLICATION_VIEWS
            )
            if not routine_navigation:
                label = component_label(view, item)
                _safe_schedule_delivery(
                    client,
                    interaction,
                    source=SOURCE_COMPONENT,
                    command=label,
                    risk=component_risk(label),
                    outcome=outcome,
                    detail=detail,
                    duration_ms=int((time.perf_counter() - started_at) * 1000),
                )

    async def audited_modal_task(modal, interaction, components, resolved):
        if not _is_target(interaction):
            return await original_modal_task(modal, interaction, components, resolved)
        started_at = time.perf_counter()
        outcome = OUTCOME_SUCCESS
        detail = None
        try:
            return await original_modal_task(modal, interaction, components, resolved)
        except Exception as error:
            outcome = OUTCOME_FAILED
            detail = type(error).__name__
            raise
        finally:
            if not (outcome == OUTCOME_SUCCESS and type(modal).__name__ in _ROUTINE_APPLICATION_MODALS):
                # Modal field values are free text (denial reasons, usernames) and are
                # deliberately not captured; the title alone identifies the action.
                label = str(getattr(modal, "title", "") or humanize_ui_name(type(modal).__name__))
                _safe_schedule_delivery(
                    client,
                    interaction,
                    source=SOURCE_MODAL,
                    command=label,
                    risk=component_risk(label),
                    outcome=outcome,
                    detail=detail,
                    duration_ms=int((time.perf_counter() - started_at) * 1000),
                )

    discord.ui.View._scheduled_task = audited_view_task
    discord.ui.Modal._scheduled_task = audited_modal_task
    discord.ui.View._mc_audit_wrapped = True


def _safe_schedule_delivery(client: Any, interaction: Any, **kwargs: Any) -> None:
    try:
        schedule_delivery(client, build_record(interaction, **kwargs))
    except Exception:
        logger.exception("Minecraft component audit failed")


async def record_denial(client: Any, interaction: Any, command: str, reason: str) -> None:
    """Log an attempt that was refused by a permission check."""
    try:
        schedule_delivery(
            client,
            build_record(
                interaction,
                command=command,
                outcome=OUTCOME_DENIED,
                risk=risk_for(command),
                detail=reason,
            ),
        )
    except Exception:
        logger.exception("Minecraft denial audit failed")


def with_risk(record: CommandAuditRecord, risk: str) -> CommandAuditRecord:
    return replace(record, risk=risk)


__all__: Iterable[str] = (
    "COMMAND_RISK",
    "CommandAuditRecord",
    "MinecraftCommandTree",
    "build_batch_embed",
    "build_action_embed",
    "build_command_log_embed",
    "build_important_embed",
    "build_record",
    "component_label",
    "component_risk",
    "deliver",
    "flatten_options",
    "format_record",
    "humanize_ui_name",
    "install_component_audit",
    "record_denial",
    "redact_options",
    "resolve_target_id",
    "risk_for",
    "schedule_delivery",
    "with_risk",
)
