"""Universal command invocation audit: record building, redaction, and routing.

Every application command, context-menu action, and panel component interaction is
turned into a :class:`CommandAuditRecord` here and handed to a sink registered by the
cogs layer. This module stays free of Discord UI code so `core/` never imports
`cogs/`, and so the routing rules can be unit-tested without Discord objects.
"""

from __future__ import annotations

import asyncio
import logging
from dataclasses import dataclass, field
from typing import Any, Awaitable, Callable, Dict, List, Mapping, Optional, Sequence, Tuple

from .utils import now_iso, truncate_text


logger = logging.getLogger(__name__)

# Risk values mirror core.actions.RiskLevel, restated here as plain strings so this
# module stays importable by the isolated Minecraft bot without pulling in the
# moderation config layer. core.actions is imported lazily inside build_record.
RISK_READ_ONLY = "read_only"
RISK_CONFIGURATION = "configuration"
RISK_MODERATE = "moderate"
RISK_DESTRUCTIVE = "destructive"

# Option values whose *name* matches one of these are never written to a log channel
# or the database. Mirrors core.services._SECRET_KEY_PARTS.
SECRET_OPTION_PARTS = ("token", "secret", "password", "credential", "webhook")

REDACTED = "[redacted]"
OPTION_VALUE_LIMIT = 200

SOURCE_SLASH = "slash"
SOURCE_CONTEXT = "context"
SOURCE_COMPONENT = "component"
SOURCE_MODAL = "modal"
SOURCE_PREFIX = "prefix"

OUTCOME_SUCCESS = "success"
OUTCOME_FAILED = "failed"

TIER_COMMAND = "command"
TIER_CRITICAL = "critical"

# Commands the risk registry does not cover (the Minecraft bot has no ACTION_SPECS)
# but which must always reach the critical channel.
DEFAULT_CRITICAL_COMMANDS = frozenset({
    "minecraft revoke",
    "minecraft unlink",
    "minecraft cancel",
    "sync",
})

DEFAULT_SETTINGS: Dict[str, Any] = {
    "enabled": True,
    "log_components": True,
    "log_read_only": True,
    "redact": True,
    "critical_commands": [],
}

# Option names that identify the member an action was aimed at, most specific first.
_TARGET_OPTION_NAMES = ("user", "member", "target", "userid", "user_id")


@dataclass(frozen=True)
class CommandAuditRecord:
    source: str
    command: str
    user_id: int
    user_label: str
    channel_id: Optional[int] = None
    guild_id: Optional[int] = None
    target_id: Optional[int] = None
    target_label: Optional[str] = None
    options: Tuple[Tuple[str, str], ...] = ()
    outcome: str = OUTCOME_SUCCESS
    correlation_id: Optional[str] = None
    duration_ms: int = 0
    risk: str = RISK_READ_ONLY
    detail: Optional[str] = None
    timestamp: str = field(default_factory=now_iso)

    @property
    def failed(self) -> bool:
        return self.outcome == OUTCOME_FAILED

    def option_summary(self) -> str:
        """`name: value` pairs on one line, for the compact batched log format."""
        if not self.options:
            return ""
        return " · ".join(f"{name}: {value}" for name, value in self.options)


def get_settings(config: Optional[Mapping[str, Any]]) -> Dict[str, Any]:
    settings = dict(DEFAULT_SETTINGS)
    stored = (config or {}).get("command_log_settings")
    if isinstance(stored, Mapping):
        for key in DEFAULT_SETTINGS:
            if key in stored:
                settings[key] = stored[key]
    return settings


def _is_secret_option(name: str) -> bool:
    lowered = str(name).casefold()
    return any(part in lowered for part in SECRET_OPTION_PARTS)


def redact_options(
    options: Sequence[Tuple[str, Any]],
    *,
    redact: bool = True,
) -> Tuple[Tuple[str, str], ...]:
    """Drop secret-looking values and bound every option to a loggable length."""
    cleaned: List[Tuple[str, str]] = []
    for name, value in options:
        label = str(name)
        if redact and _is_secret_option(label):
            cleaned.append((label, REDACTED))
            continue
        cleaned.append((label, truncate_text(str(value), OPTION_VALUE_LIMIT) or "—"))
    return tuple(cleaned)


def flatten_interaction_options(data: Optional[Mapping[str, Any]]) -> List[Tuple[str, Any]]:
    """Pull the leaf options out of a raw interaction payload.

    Subcommand and subcommand-group wrappers (types 1 and 2) carry the real options
    one level down, so they are unwrapped rather than reported as options.
    """
    options = (data or {}).get("options") or []
    flattened: List[Tuple[str, Any]] = []
    queue = list(options) if isinstance(options, list) else []
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


def _resolve_target(
    interaction: Any,
    options: Sequence[Tuple[str, Any]],
) -> Tuple[Optional[int], Optional[str]]:
    """Best-effort identification of the member an action was aimed at."""
    resolved = ((getattr(interaction, "data", None) or {}).get("resolved") or {})
    users = resolved.get("users") if isinstance(resolved, Mapping) else None
    if isinstance(users, Mapping) and users:
        user_id, payload = next(iter(users.items()))
        username = ""
        if isinstance(payload, Mapping):
            username = str(payload.get("username") or "")
        try:
            return int(user_id), username or str(user_id)
        except (TypeError, ValueError):
            pass

    lookup = {str(name).casefold(): value for name, value in options}
    for name in _TARGET_OPTION_NAMES:
        if name not in lookup:
            continue
        try:
            target_id = int(str(lookup[name]).strip())
        except (TypeError, ValueError):
            continue
        return target_id, str(target_id)
    return None, None


def _user_label(user: Any) -> str:
    if user is None:
        return "Unknown"
    for attribute in ("global_name", "display_name", "name"):
        value = getattr(user, attribute, None)
        if value:
            return str(value)
    return str(getattr(user, "id", "Unknown"))


def _spec_risk(spec: Any) -> str:
    risk_level = getattr(spec, "risk_level", None)
    return str(getattr(risk_level, "value", risk_level) or RISK_READ_ONLY)


def resolve_command_name(interaction: Any) -> str:
    """Qualified command name for an interaction, without importing core.actions
    at module scope (the Minecraft bot imports this module standalone)."""
    command = getattr(interaction, "command", None)
    qualified_name = getattr(command, "qualified_name", None)
    if qualified_name:
        return str(qualified_name)
    try:
        from .actions import interaction_action_name
    except ImportError:
        return ""
    return interaction_action_name(interaction)


def _lookup_spec(command_name: str) -> Any:
    try:
        from .actions import get_action_spec
    except ImportError:
        return None
    return get_action_spec(command_name)


def build_record(
    interaction: Any,
    *,
    source: str = SOURCE_SLASH,
    spec: Any = None,
    command: Optional[str] = None,
    outcome: str = OUTCOME_SUCCESS,
    duration_ms: int = 0,
    correlation_id: Optional[str] = None,
    detail: Optional[str] = None,
    redact: bool = True,
) -> CommandAuditRecord:
    command_name = command or resolve_command_name(interaction) or "unknown"
    if spec is None:
        spec = _lookup_spec(command_name)

    raw_options = flatten_interaction_options(getattr(interaction, "data", None))
    target_id, target_label = _resolve_target(interaction, raw_options)

    user = getattr(interaction, "user", None)
    channel_id = getattr(interaction, "channel_id", None)
    guild = getattr(interaction, "guild", None)

    return CommandAuditRecord(
        source=source,
        command=command_name,
        user_id=int(getattr(user, "id", 0) or 0),
        user_label=_user_label(user),
        channel_id=int(channel_id) if channel_id else None,
        guild_id=int(getattr(guild, "id", 0)) if guild is not None else None,
        target_id=target_id,
        target_label=target_label,
        options=redact_options(raw_options, redact=redact),
        outcome=outcome,
        correlation_id=correlation_id,
        duration_ms=max(0, int(duration_ms)),
        risk=(_spec_risk(spec)),
        detail=detail,
    )


def build_prefix_record(
    ctx: Any,
    *,
    command: str,
    outcome: str = OUTCOME_SUCCESS,
    detail: Optional[str] = None,
    options: Sequence[Tuple[str, Any]] = (),
    redact: bool = True,
) -> CommandAuditRecord:
    """Audit record for a text (prefix) command, which has no interaction object."""
    author = getattr(ctx, "author", None)
    channel = getattr(ctx, "channel", None)
    guild = getattr(ctx, "guild", None)
    return CommandAuditRecord(
        source=SOURCE_PREFIX,
        command=command,
        user_id=int(getattr(author, "id", 0) or 0),
        user_label=_user_label(author),
        channel_id=int(getattr(channel, "id", 0)) if channel is not None else None,
        guild_id=int(getattr(guild, "id", 0)) if guild is not None else None,
        options=redact_options(options, redact=redact),
        outcome=outcome,
        detail=detail,
        risk=RISK_DESTRUCTIVE,
    )


def route_for(record: CommandAuditRecord, config: Optional[Mapping[str, Any]] = None) -> List[str]:
    """Which log tiers a record belongs in.

    Everything lands in the command log. Destructive actions, anything that failed,
    and explicitly listed commands are additionally mirrored to the critical log so
    the important events stay readable in their own channel.
    """
    settings = get_settings(config)
    tiers = [TIER_COMMAND]

    overrides = settings.get("critical_commands") or []
    critical_names = {str(name).strip().casefold() for name in overrides if str(name).strip()}
    critical_names |= DEFAULT_CRITICAL_COMMANDS

    if (
        record.risk == RISK_DESTRUCTIVE
        or record.failed
        or record.command.casefold() in critical_names
    ):
        tiers.append(TIER_CRITICAL)
    return tiers


def should_record(record: CommandAuditRecord, config: Optional[Mapping[str, Any]] = None) -> bool:
    settings = get_settings(config)
    if not settings.get("enabled", True):
        return False
    if record.source in (SOURCE_COMPONENT, SOURCE_MODAL) and not settings.get("log_components", True):
        return False
    if (
        not settings.get("log_read_only", True)
        and record.risk == RISK_READ_ONLY
        and not record.failed
    ):
        return False
    return True


# ---------------------------------------------------------------------------
# Sink registration — the cogs layer owns delivery, core only emits.
# ---------------------------------------------------------------------------

_sink: Optional[Callable[[CommandAuditRecord], Awaitable[None]]] = None


def set_sink(sink: Optional[Callable[[CommandAuditRecord], Awaitable[None]]]) -> None:
    global _sink
    _sink = sink


def has_sink() -> bool:
    return _sink is not None


async def emit(record: CommandAuditRecord) -> None:
    """Deliver a record. Auditing must never take a command down with it."""
    if _sink is None:
        return
    try:
        await _sink(record)
    except asyncio.CancelledError:
        raise
    except Exception:
        logger.exception("Command audit sink failed for %s", record.command)
