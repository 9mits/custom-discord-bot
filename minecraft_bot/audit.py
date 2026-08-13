"""Command audit trail for the Minecraft access bot.

Mirrors the moderation bot's behaviour: every `/minecraft ...` invocation is logged
to a staff-only command channel, and the access-changing ones (`revoke`, `unlink`,
`cancel`) are mirrored to a dedicated important-command channel.

Only `core.command_audit` is borrowed — it is stdlib-only by design, so this keeps
the Minecraft bot's isolation from the moderation `core`/`cogs` layers intact.
"""

from __future__ import annotations

import logging
import time
from typing import Optional

import discord
from discord import InteractionType, app_commands

from core.command_audit import (
    OUTCOME_FAILED,
    OUTCOME_SUCCESS,
    RISK_CONFIGURATION,
    RISK_DESTRUCTIVE,
    RISK_MODERATE,
    RISK_READ_ONLY,
    CommandAuditRecord,
    build_record,
)

from .presentation import info_embed


logger = logging.getLogger("MinecraftAccessBot")

# The Minecraft bot has no ACTION_SPECS registry, so risk is declared here.
COMMAND_RISK = {
    "minecraft revoke": RISK_DESTRUCTIVE,
    "minecraft unlink": RISK_DESTRUCTIVE,
    "minecraft cancel": RISK_DESTRUCTIVE,
    "minecraft retry": RISK_MODERATE,
    "minecraft setup": RISK_CONFIGURATION,
    "minecraft log-channel": RISK_CONFIGURATION,
}


def risk_for(command_name: str) -> str:
    return COMMAND_RISK.get(str(command_name).strip().casefold(), RISK_READ_ONLY)


def format_record(record: CommandAuditRecord) -> str:
    parts = [
        f"`{'FAILED' if record.failed else 'OK'}`",
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
        line += f"\n{summary}"
    if record.failed and record.detail:
        line += f"\n{record.detail}"
    return line


def build_audit_embed(record: CommandAuditRecord) -> discord.Embed:
    title = "Important Command" if record.risk == RISK_DESTRUCTIVE else "Command Log"
    return info_embed(
        title,
        f"{format_record(record)}\n\nTook {record.duration_ms} ms.",
        error=record.failed,
    )


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
            client = self.client
            try:
                record = build_record(
                    interaction,
                    outcome=outcome,
                    duration_ms=int((time.perf_counter() - started_at) * 1000),
                    detail=detail,
                    correlation_id=f"mc-{interaction.id:x}" if outcome == OUTCOME_FAILED else None,
                )
                record = _with_risk(record)
                await deliver(client, record)
            except Exception:
                logger.exception("Minecraft command audit failed")


def _with_risk(record: CommandAuditRecord) -> CommandAuditRecord:
    from dataclasses import replace

    return replace(record, risk=risk_for(record.command))


async def deliver(client, record: CommandAuditRecord) -> None:
    """Send to the command log, plus the important-command log when warranted."""
    settings = getattr(client, "settings", None)
    if settings is None:
        return

    command_channel_id = int(getattr(settings, "command_log_channel_id", 0) or 0)
    critical_channel_id = int(getattr(settings, "critical_log_channel_id", 0) or 0)
    is_critical = record.risk == RISK_DESTRUCTIVE or record.failed

    targets = []
    if command_channel_id:
        targets.append(command_channel_id)
    if is_critical:
        # Falls back to the command log so important records are never dropped.
        targets.append(critical_channel_id or command_channel_id)

    embed = build_audit_embed(record)
    for channel_id in dict.fromkeys(target for target in targets if target):
        await _send(client, channel_id, embed)


async def _send(client, channel_id: int, embed: discord.Embed) -> None:
    send_log = getattr(client, "_send_configured_log", None)
    if send_log is None:
        return
    try:
        await send_log(channel_id, embed)
    except Exception:
        logger.exception("Could not write Minecraft command audit to channel %s", channel_id)


__all__ = [
    "COMMAND_RISK",
    "MinecraftCommandTree",
    "build_audit_embed",
    "deliver",
    "format_record",
    "risk_for",
]
