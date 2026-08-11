"""Metadata-driven command catalog and capability declarations."""
from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
from typing import Any, Iterable, Mapping, Optional, Sequence

import discord

from .errors import BotPermissionError, CallerPermissionError, InvalidConfigurationError
from .services import has_capability


class AcknowledgementPolicy(str, Enum):
    IMMEDIATE = "immediate"
    DEFER = "defer"


class RiskLevel(str, Enum):
    READ_ONLY = "read_only"
    CONFIGURATION = "configuration"
    MODERATE = "moderate"
    DESTRUCTIVE = "destructive"


@dataclass(frozen=True)
class ActionSpec:
    action_id: str
    command_name: str
    category: str
    help_text: str
    capability: Optional[str] = None
    caller_permissions: tuple[str, ...] = ()
    bot_permissions: tuple[str, ...] = ()
    acknowledgement_policy: AcknowledgementPolicy = AcknowledgementPolicy.IMMEDIATE
    risk_level: RiskLevel = RiskLevel.READ_ONLY
    feature_flag: Optional[str] = None
    examples: tuple[str, ...] = ()
    related_actions: tuple[str, ...] = ()


def _spec(
    name: str,
    category: str,
    help_text: str,
    *,
    capability: Optional[str] = None,
    caller: Sequence[str] = (),
    bot: Sequence[str] = (),
    ack: AcknowledgementPolicy = AcknowledgementPolicy.IMMEDIATE,
    risk: RiskLevel = RiskLevel.READ_ONLY,
    feature: Optional[str] = None,
    examples: Sequence[str] = (),
    related: Sequence[str] = (),
) -> ActionSpec:
    return ActionSpec(
        action_id=name.lower().replace(" ", ".").replace("-", "_"),
        command_name=name,
        category=category,
        help_text=help_text,
        capability=capability,
        caller_permissions=tuple(caller),
        bot_permissions=tuple(bot),
        acknowledgement_policy=ack,
        risk_level=risk,
        feature_flag=feature,
        examples=tuple(examples),
        related_actions=tuple(related),
    )


_STAFF = ("Configured staff role",)
_ADMIN = ("Administrator or configured admin role",)

ACTION_SPECS: tuple[ActionSpec, ...] = (
    _spec("settings", "Configuration", "Open the unified server settings hub.", capability="config_panel", caller=_ADMIN, ack=AcknowledgementPolicy.DEFER, feature="config_panel", examples=("/settings",), related=("setup", "config")),
    _spec("help", "Information", "Search the permission-aware command catalog.", examples=("/help", "/help query:punish"), related=("commands",)),
    _spec("commands", "Information", "Open the same command catalog as /help.", capability="config_panel", caller=_ADMIN, examples=("/commands",), related=("help",)),
    _spec("setup", "Configuration", "Configure server roles and channels.", capability="setup_panel", caller=_ADMIN, risk=RiskLevel.CONFIGURATION, related=("settings",)),
    _spec("config", "Configuration", "Manage settings, backups, and imports.", capability="config_panel", caller=_ADMIN, risk=RiskLevel.CONFIGURATION, feature="config_panel", related=("settings",)),
    _spec("modmail-panel", "Configuration", "Post the public support-ticket panel.", capability="config_panel", caller=_ADMIN, bot=("send_messages", "embed_links"), ack=AcknowledgementPolicy.DEFER, risk=RiskLevel.CONFIGURATION, related=("settings",)),
    _spec("automod", "Configuration", "Manage native and smart AutoMod behavior.", capability="config_panel", caller=_ADMIN, risk=RiskLevel.CONFIGURATION, feature="automod_panel", related=("settings", "security")),
    _spec("rules", "Configuration", "Configure punishment reasons and durations.", capability="config_panel", caller=_ADMIN, risk=RiskLevel.CONFIGURATION, related=("settings", "punish")),
    _spec("security", "Security", "Manage anti-nuke protections.", capability="config_panel", caller=_ADMIN, risk=RiskLevel.DESTRUCTIVE, related=("settings", "lockdown")),
    _spec("access", "Security", "Manage roles that may use moderation tools.", capability="owner_panel", caller=("Configured owner role",), risk=RiskLevel.CONFIGURATION, related=("settings",)),
    _spec("role-settings", "Configuration", "Configure custom-role access and limits.", capability="config_panel", caller=_ADMIN, risk=RiskLevel.CONFIGURATION, related=("settings", "role")),
    _spec("branding global", "Configuration", "Edit the bot's global profile.", capability="owner_panel", caller=("Configured owner role",), risk=RiskLevel.CONFIGURATION, related=("branding server", "settings")),
    _spec("branding server", "Configuration", "Edit this server's bot profile.", capability="owner_panel", caller=("Configured owner role",), risk=RiskLevel.CONFIGURATION, related=("branding global", "settings")),
    _spec("punish", "Moderation", "Open the punishment workflow for a member.", capability="punishments.issue", caller=_STAFF, ack=AcknowledgementPolicy.DEFER, risk=RiskLevel.DESTRUCTIVE, examples=("/punish user:@member", "/punish userid:123456789"), related=("history", "undo", "rules")),
    _spec("publicexecution", "Moderation", "Start a community-vote punishment.", capability="punishments.issue", caller=_STAFF, ack=AcknowledgementPolicy.DEFER, risk=RiskLevel.DESTRUCTIVE, related=("punish",)),
    _spec("history", "Cases", "View a member's moderation history.", capability="cases.read", caller=_STAFF, ack=AcknowledgementPolicy.DEFER, examples=("/history user:@member",), related=("cases", "case")),
    _spec("cases", "Cases", "Browse moderation cases in pages.", capability="cases.read", caller=_STAFF, ack=AcknowledgementPolicy.DEFER, related=("case", "history")),
    _spec("case", "Cases", "Open one moderation case.", capability="cases.read", caller=_STAFF, ack=AcknowledgementPolicy.DEFER, examples=("/case caseid:42",), related=("cases", "undo")),
    _spec("undo", "Moderation", "Reverse a logged moderation action.", capability="punishments.undo", caller=_STAFF, ack=AcknowledgementPolicy.DEFER, risk=RiskLevel.DESTRUCTIVE, examples=("/undo user:@member",), related=("case", "history")),
    _spec("purge", "Moderation", "Delete recent messages with optional filters.", capability="messages.purge", caller=_STAFF, bot=("manage_messages", "read_message_history"), risk=RiskLevel.DESTRUCTIVE, examples=("/purge amount:50",), related=("export",)),
    _spec("export", "Moderation", "Export bounded message history to HTML.", capability="messages.export", caller=_STAFF, bot=("read_message_history",), ack=AcknowledgementPolicy.DEFER, examples=("/export userid:123456789",), related=("purge",)),
    _spec("lock", "Moderation", "Lock the current channel.", capability="channels.lock", caller=_STAFF, bot=("manage_channels",), ack=AcknowledgementPolicy.DEFER, risk=RiskLevel.DESTRUCTIVE, related=("unlock",)),
    _spec("unlock", "Moderation", "Restore messaging in the current channel.", capability="channels.lock", caller=_STAFF, bot=("manage_channels",), ack=AcknowledgementPolicy.DEFER, risk=RiskLevel.DESTRUCTIVE, related=("lock",)),
    _spec("mod-guide", "Information", "Open the moderation quick-start guide.", capability="cases.read", caller=_STAFF, related=("help",)),
    _spec("stats", "Analytics", "View moderation analytics.", capability="cases.read", caller=_STAFF, ack=AcknowledgementPolicy.DEFER, related=("directory",)),
    _spec("directory", "Analytics", "View the staff directory.", capability="cases.read", caller=_STAFF, ack=AcknowledgementPolicy.DEFER, related=("stats",)),
    _spec("about", "Information", "View project-wide bot statistics.", ack=AcknowledgementPolicy.DEFER, related=("status",)),
    _spec("status", "Information", "View runtime health and latency.", capability="config_panel", caller=_ADMIN, related=("about",)),
    _spec("serverinfo", "Information", "View detailed server information.", capability="config_panel", caller=_ADMIN, ack=AcknowledgementPolicy.DEFER, related=("about",)),
    _spec("archive", "Channels", "Archive the current channel.", capability="channels.lock", caller=_STAFF, bot=("manage_channels",), risk=RiskLevel.DESTRUCTIVE, related=("unarchive", "clone")),
    _spec("unarchive", "Channels", "Restore an archived channel.", capability="channels.lock", caller=_STAFF, bot=("manage_channels",), ack=AcknowledgementPolicy.DEFER, risk=RiskLevel.CONFIGURATION, related=("archive",)),
    _spec("clone", "Channels", "Archive a channel and create a replacement.", capability="channels.lock", caller=_STAFF, bot=("manage_channels",), risk=RiskLevel.DESTRUCTIVE, related=("archive",)),
    _spec("lockdown", "Security", "Hide server channels during an emergency.", capability="channels.lock", caller=_STAFF, bot=("manage_channels",), ack=AcknowledgementPolicy.DEFER, risk=RiskLevel.DESTRUCTIVE, related=("lift-lockdown", "security")),
    _spec("lift-lockdown", "Security", "Restore channel visibility after lockdown.", capability="channels.lock", caller=_STAFF, bot=("manage_channels",), ack=AcknowledgementPolicy.DEFER, risk=RiskLevel.DESTRUCTIVE, related=("lockdown",)),
    _spec("role", "Custom Roles", "Create or manage your custom role.", bot=("manage_roles",), ack=AcknowledgementPolicy.DEFER, related=("role-settings",)),
    _spec("derole", "Custom Roles", "Remove selected roles from every member who has them.", capability="cases.read", caller=_STAFF, bot=("manage_roles",), risk=RiskLevel.DESTRUCTIVE, related=("role-settings",)),
    _spec("event setup", "Events", "Choose where the event leaderboard is displayed.", capability="owner_panel", caller=("Configured owner role",), ack=AcknowledgementPolicy.DEFER, risk=RiskLevel.CONFIGURATION),
    _spec("event start", "Events", "Start or resume event voice tracking.", capability="owner_panel", caller=("Configured owner role",), ack=AcknowledgementPolicy.DEFER, risk=RiskLevel.CONFIGURATION),
    _spec("event stop", "Events", "Pause event voice tracking.", capability="owner_panel", caller=("Configured owner role",), ack=AcknowledgementPolicy.DEFER, risk=RiskLevel.CONFIGURATION),
    _spec("event reset", "Events", "Reset tracked event time.", capability="owner_panel", caller=("Configured owner role",), ack=AcknowledgementPolicy.DEFER, risk=RiskLevel.DESTRUCTIVE),
    _spec("event goal", "Events", "Set the event target in hours.", capability="owner_panel", caller=("Configured owner role",), ack=AcknowledgementPolicy.DEFER, risk=RiskLevel.CONFIGURATION),
    _spec("event next", "Events", "Archive the event and begin another.", capability="owner_panel", caller=("Configured owner role",), ack=AcknowledgementPolicy.DEFER, risk=RiskLevel.DESTRUCTIVE),
    _spec("event vc", "Events", "Choose the tracked voice channel.", capability="owner_panel", caller=("Configured owner role",), ack=AcknowledgementPolicy.DEFER, risk=RiskLevel.CONFIGURATION),
    _spec("event elapsed", "Events", "Correct the event elapsed time.", capability="owner_panel", caller=("Configured owner role",), ack=AcknowledgementPolicy.DEFER, risk=RiskLevel.CONFIGURATION),
    _spec("event status", "Events", "Show event configuration and runtime state.", capability="owner_panel", caller=("Configured owner role",), ack=AcknowledgementPolicy.DEFER),
    _spec("endtimestamp", "Events", "Show Discord timestamp formats for the projected event end.", capability="owner_panel", caller=("Configured owner role",), ack=AcknowledgementPolicy.DEFER),
    _spec("Add to Scam Image Dataset", "Context Actions", "Add images from a message to the scam dataset.", capability="config_panel", caller=_STAFF, ack=AcknowledgementPolicy.DEFER, risk=RiskLevel.CONFIGURATION),
    _spec("Punish Message", "Context Actions", "Punish a message author and retain evidence.", capability="punishments.issue", caller=_STAFF, ack=AcknowledgementPolicy.DEFER, risk=RiskLevel.DESTRUCTIVE),
    _spec("Moderation History", "Context Actions", "View a selected member's case history.", capability="cases.read", caller=_STAFF, ack=AcknowledgementPolicy.DEFER),
)

ACTION_BY_COMMAND = {spec.command_name.casefold(): spec for spec in ACTION_SPECS}
ACTION_BY_ID = {spec.action_id: spec for spec in ACTION_SPECS}


def get_action_spec(name: str) -> Optional[ActionSpec]:
    return ACTION_BY_COMMAND.get(str(name).strip().lstrip("/").casefold())


def interaction_action_name(interaction: discord.Interaction) -> str:
    command = getattr(interaction, "command", None)
    qualified_name = getattr(command, "qualified_name", None)
    if qualified_name:
        return str(qualified_name)

    data: Mapping[str, Any] = getattr(interaction, "data", None) or {}
    names = [str(data.get("name") or "")]
    options = data.get("options") or []
    while options and isinstance(options, list):
        selected = next(
            (option for option in options if isinstance(option, dict) and option.get("type") in (1, 2)),
            None,
        )
        if selected is None:
            break
        names.append(str(selected.get("name") or ""))
        options = selected.get("options") or []
    return " ".join(name for name in names if name)


def authorize_interaction(interaction: discord.Interaction, config: Mapping[str, Any]) -> Optional[ActionSpec]:
    """Enforce the registered feature, caller, and bot-permission policy."""
    spec = get_action_spec(interaction_action_name(interaction))
    if spec is None or interaction.type is discord.InteractionType.autocomplete:
        return spec

    if spec.feature_flag:
        flags = config.get("feature_flags", {})
        if isinstance(flags, Mapping) and not bool(flags.get(spec.feature_flag, True)):
            raise InvalidConfigurationError(f"Feature disabled: {spec.feature_flag}")

    member = interaction.user
    guild = interaction.guild
    if spec.capability:
        if not isinstance(member, discord.Member) or guild is None:
            raise CallerPermissionError(f"Capability requires a guild member: {spec.capability}")
        if not has_capability(
            [role.id for role in member.roles],
            spec.capability,
            dict(config),
            administrator=member.guild_permissions.administrator,
            user_id=member.id,
            guild_owner_id=guild.owner_id,
        ):
            raise CallerPermissionError(f"Missing capability: {spec.capability}")

    if spec.bot_permissions and guild is not None:
        me = guild.me
        if me is None and interaction.client.user is not None:
            me = guild.get_member(interaction.client.user.id)
        permissions = getattr(me, "guild_permissions", None)
        missing = [name for name in spec.bot_permissions if not bool(getattr(permissions, name, False))]
        if missing:
            raise BotPermissionError(f"Missing bot permissions: {', '.join(missing)}")
    return spec


def search_actions(query: str = "", *, enabled_flags: Optional[dict] = None) -> list[ActionSpec]:
    needle = str(query or "").strip().casefold()
    results = []
    for spec in ACTION_SPECS:
        if spec.feature_flag and enabled_flags is not None and not enabled_flags.get(spec.feature_flag, True):
            continue
        haystack = " ".join((spec.command_name, spec.category, spec.help_text, *spec.examples)).casefold()
        if not needle or needle in haystack:
            results.append(spec)
    return sorted(results, key=lambda item: (item.category.casefold(), item.command_name.casefold()))


def validate_registered_actions(commands: Iterable[object]) -> tuple[set[str], set[str]]:
    registered = {
        str(getattr(command, "qualified_name", getattr(command, "name", ""))).casefold()
        for command in commands
        if getattr(command, "qualified_name", getattr(command, "name", ""))
        and command.__class__.__name__ != "Group"
    }
    documented = set(ACTION_BY_COMMAND)
    environment_optional = {
        name for name in documented
        if name.startswith("event ") or name in {
            "endtimestamp",
            "add to scam image dataset",
            "punish message",
            "moderation history",
        }
    }
    return registered - documented, documented - registered - environment_optional
