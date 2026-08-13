"""Command audit delivery: buffered channel logging, persistence, and /auditlog.

`core.command_audit` produces the records; this cog owns delivery. Routine records
are batched so a staff member clicking through a panel produces one message rather
than one per click, while critical records are flushed immediately so destructive
actions never sit in a buffer.
"""

from __future__ import annotations

import asyncio
import logging
from collections import deque
from datetime import datetime, timezone
from typing import Deque, List, Optional

import discord
from discord import app_commands
from discord.ext import commands

from core import command_audit
from core.command_audit import CommandAuditRecord, TIER_CRITICAL, route_for
from core.constants import SCOPE_SYSTEM
from core.context import bot, tree
from core.responding import InteractionResponder
from core.utils import truncate_text

from .shared import (
    _send_log_to_channels,
    has_permission_capability,
    make_embed,
    make_empty_state_embed,
    respond_with_error,
    respond_with_operation_failure,
)


logger = logging.getLogger(__name__)

FLUSH_INTERVAL_SECONDS = 5.0
FLUSH_BATCH_SIZE = 10
BUFFER_LIMIT = 500

# Emoji are not permitted in user-facing output, so outcome is carried by wording.
_OUTCOME_LABELS = {
    command_audit.OUTCOME_SUCCESS: "OK",
    command_audit.OUTCOME_FAILED: "FAILED",
}

_SOURCE_LABELS = {
    command_audit.SOURCE_SLASH: "Slash command",
    command_audit.SOURCE_CONTEXT: "Context action",
    command_audit.SOURCE_COMPONENT: "Panel control",
    command_audit.SOURCE_MODAL: "Form",
    command_audit.SOURCE_PREFIX: "Prefix command",
}


def _channel_id(config: dict, key: str) -> List[int]:
    try:
        channel_id = int(config.get(key) or 0)
    except (TypeError, ValueError):
        return []
    return [channel_id] if channel_id else []


def get_command_log_channel_ids(config: Optional[dict] = None) -> List[int]:
    # `config is None` rather than a falsy check: an explicitly empty config means
    # "nothing is configured", not "fall back to the live config".
    if config is None:
        config = bot.data_manager.config
    return _channel_id(config, "command_log_channel_id")


def get_critical_log_channel_ids(config: Optional[dict] = None) -> List[int]:
    if config is None:
        config = bot.data_manager.config
    channel_ids = _channel_id(config, "critical_log_channel_id")
    # With no dedicated critical channel the important records still need a home,
    # so they fall back to the general command log rather than being dropped.
    return channel_ids or get_command_log_channel_ids(config)


def format_record_line(record: CommandAuditRecord) -> str:
    """One compact line per record for the batched command log."""
    parts = [f"`{_OUTCOME_LABELS.get(record.outcome, record.outcome)}`", f"**{record.command}**"]
    parts.append(f"by <@{record.user_id}>")
    if record.target_id:
        parts.append(f"on <@{record.target_id}>")
    if record.channel_id:
        parts.append(f"in <#{record.channel_id}>")
    line = " ".join(parts)

    summary = record.option_summary()
    if summary:
        line += f"\n> {truncate_text(summary, 300)}"
    if record.failed:
        failure = record.detail or "error"
        if record.correlation_id:
            failure += f" · ref `{record.correlation_id}`"
        line += f"\n> {failure}"
    return line


def build_batch_embed(records: List[CommandAuditRecord], *, guild: Optional[discord.Guild]) -> discord.Embed:
    lines = []
    for record in records:
        stamp = _relative_stamp(record.timestamp)
        lines.append(f"{stamp} {format_record_line(record)}")
    description = "\n\n".join(lines)
    return make_embed(
        f"Command Log ({len(records)})",
        truncate_text(description, 4000),
        kind="info",
        scope=SCOPE_SYSTEM,
        guild=guild,
    )


def build_critical_embed(record: CommandAuditRecord, *, guild: Optional[discord.Guild]) -> discord.Embed:
    embed = make_embed(
        "Important Command",
        f"> **{record.command}** was run by <@{record.user_id}>.",
        kind="danger" if record.failed else "warning",
        scope=SCOPE_SYSTEM,
        guild=guild,
    )
    embed.add_field(name="Source", value=_SOURCE_LABELS.get(record.source, record.source), inline=True)
    embed.add_field(name="Outcome", value=_OUTCOME_LABELS.get(record.outcome, record.outcome), inline=True)
    embed.add_field(name="Risk", value=str(record.risk).replace("_", " ").title(), inline=True)
    embed.add_field(name="Staff", value=f"<@{record.user_id}> (`{record.user_id}`)", inline=True)
    if record.target_id:
        embed.add_field(name="Target", value=f"<@{record.target_id}> (`{record.target_id}`)", inline=True)
    if record.channel_id:
        embed.add_field(name="Channel", value=f"<#{record.channel_id}>", inline=True)
    summary = record.option_summary()
    if summary:
        embed.add_field(name="Arguments", value=truncate_text(summary, 1024), inline=False)
    if record.failed:
        failure = record.detail or "error"
        if record.correlation_id:
            failure += f"\nReference: `{record.correlation_id}`"
        embed.add_field(name="Failure", value=truncate_text(failure, 1024), inline=False)
    embed.add_field(name="Took", value=f"{record.duration_ms} ms", inline=True)
    return embed


def _relative_stamp(timestamp: str) -> str:
    try:
        parsed = datetime.fromisoformat(timestamp)
    except (TypeError, ValueError):
        return ""
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return f"<t:{int(parsed.timestamp())}:T>"


class CommandLogCog(commands.Cog):
    """Buffers audit records and writes them to their channels and the database."""

    def __init__(self) -> None:
        self._buffer: Deque[CommandAuditRecord] = deque(maxlen=BUFFER_LIMIT)
        # Created on first use: asyncio.Event() binds to the running loop, and the
        # cog is constructed before the loop is necessarily available.
        self._flush_event: Optional[asyncio.Event] = None
        self._task: Optional[asyncio.Task] = None
        self._dropped = 0

    def _wake(self) -> asyncio.Event:
        if self._flush_event is None:
            self._flush_event = asyncio.Event()
        return self._flush_event

    # -- lifecycle -----------------------------------------------------

    def start(self) -> None:
        self._wake()
        if self._task is None or self._task.done():
            self._task = asyncio.create_task(self._flush_loop(), name="command-audit-flush")
        command_audit.set_sink(self.handle_record)

    async def cog_unload(self) -> None:
        command_audit.set_sink(None)
        if self._task is not None:
            self._task.cancel()
            await asyncio.gather(self._task, return_exceptions=True)
            self._task = None
        await self._flush()

    # -- ingest --------------------------------------------------------

    async def handle_record(self, record: CommandAuditRecord) -> None:
        config = bot.data_manager.config if bot.data_manager else {}
        tiers = route_for(record, config)

        if len(self._buffer) == BUFFER_LIMIT:
            self._dropped += 1
        self._buffer.append(record)

        if TIER_CRITICAL in tiers:
            # Important actions bypass the buffer entirely.
            await self._send_critical(record)
        if len(self._buffer) >= FLUSH_BATCH_SIZE:
            self._wake().set()

    async def _flush_loop(self) -> None:
        metrics = getattr(bot, "metrics", None)
        wake = self._wake()
        while True:
            try:
                await asyncio.wait_for(wake.wait(), timeout=FLUSH_INTERVAL_SECONDS)
            except asyncio.TimeoutError:
                pass
            except asyncio.CancelledError:
                raise
            wake.clear()
            try:
                await self._flush()
                if metrics is not None:
                    metrics.record_loop_success("command audit")
            except asyncio.CancelledError:
                raise
            except Exception as error:
                logger.exception("Command audit flush failed")
                if metrics is not None:
                    metrics.record_loop_failure("command audit", error)

    # -- delivery ------------------------------------------------------

    async def _flush(self) -> None:
        if not self._buffer:
            return
        batch = [self._buffer.popleft() for _ in range(len(self._buffer))]
        if self._dropped:
            logger.warning("Command audit buffer overflowed; dropped %s records", self._dropped)
            self._dropped = 0

        await self._persist(batch)

        guild = self._guild()
        if guild is None:
            return
        config = bot.data_manager.config if bot.data_manager else {}
        channel_ids = get_command_log_channel_ids(config)
        if not channel_ids:
            return
        # Discord caps embed descriptions, so long batches go out in chunks.
        for chunk in _chunk(batch, FLUSH_BATCH_SIZE):
            await _send_log_to_channels(
                guild,
                channel_ids,
                build_batch_embed(chunk, guild=guild),
                log_label="command log",
            )

    async def _send_critical(self, record: CommandAuditRecord) -> None:
        guild = self._guild()
        if guild is None:
            return
        config = bot.data_manager.config if bot.data_manager else {}
        channel_ids = get_critical_log_channel_ids(config)
        if not channel_ids:
            return
        await _send_log_to_channels(
            guild,
            channel_ids,
            build_critical_embed(record, guild=guild),
            log_label="critical command log",
        )

    async def _persist(self, batch: List[CommandAuditRecord]) -> None:
        if bot.data_manager is None:
            return
        try:
            await bot.data_manager.record_command_audit(batch)
        except Exception:
            logger.exception("Failed to persist %s command audit records", len(batch))

    def _guild(self) -> Optional[discord.Guild]:
        if bot.data_manager is None:
            return None
        try:
            guild_id = int(bot.data_manager.config.get("guild_id") or 0)
        except (TypeError, ValueError):
            guild_id = 0
        if guild_id:
            guild = bot.get_guild(guild_id)
            if guild is not None:
                return guild
        return bot.guilds[0] if bot.guilds else None


def _chunk(items: List[CommandAuditRecord], size: int) -> List[List[CommandAuditRecord]]:
    return [items[index:index + size] for index in range(0, len(items), size)]


# ---------------------------------------------------------------------------
# /auditlog
# ---------------------------------------------------------------------------


def build_auditlog_embed(
    rows: List[dict],
    *,
    guild: Optional[discord.Guild],
    page: int,
    total: int,
) -> discord.Embed:
    if not rows:
        return make_empty_state_embed(
            "Command Audit",
            "> No command invocations match that filter yet.",
            scope=SCOPE_SYSTEM,
            guild=guild,
        )
    lines = []
    for row in rows:
        outcome = _OUTCOME_LABELS.get(str(row.get("outcome")), str(row.get("outcome")))
        stamp = f"<t:{int(row.get('occurred_at') or 0)}:R>"
        line = f"`{outcome}` **{row.get('command')}** by <@{row.get('user_id')}> {stamp}"
        if row.get("target_id"):
            line += f" on <@{row['target_id']}>"
        lines.append(line)
    pages = max(1, (total + len(rows) - 1) // max(1, len(rows)))
    embed = make_embed(
        "Command Audit",
        truncate_text("\n".join(lines), 4000),
        kind="info",
        scope=SCOPE_SYSTEM,
        guild=guild,
    )
    embed.add_field(name="Page", value=f"{page + 1} of {pages}", inline=True)
    embed.add_field(name="Matching Records", value=str(total), inline=True)
    return embed


@tree.command(name="auditlog", description="Review recent command invocations.")
@app_commands.describe(
    user="Only show commands run by this member",
    command="Filter by command name",
    limit="How many records to show (1-50)",
)
async def auditlog(
    interaction: discord.Interaction,
    user: Optional[discord.User] = None,
    command: Optional[str] = None,
    limit: Optional[app_commands.Range[int, 1, 50]] = None,
) -> None:
    if not has_permission_capability(interaction, "cases.read"):
        await respond_with_error(interaction, "You do not have permission to review the command audit.", scope=SCOPE_SYSTEM)
        return

    responder = InteractionResponder(interaction)
    await responder.defer(ephemeral=True)
    try:
        page_size = int(limit or 20)
        rows = await bot.data_manager.list_command_audit(
            user_id=user.id if user else None,
            command=command,
            limit=page_size,
        )
        total = await bot.data_manager.count_command_audit(
            user_id=user.id if user else None,
            command=command,
        )
    except Exception as error:
        await respond_with_operation_failure(interaction, error, operation="audit log lookup")
        return

    await responder.send(
        embed=build_auditlog_embed(rows, guild=interaction.guild, page=0, total=total),
        ephemeral=True,
    )


async def setup(bot_instance: commands.Bot) -> None:
    cog = CommandLogCog()
    await bot_instance.add_cog(cog)
    bot_instance.tree.add_command(auditlog)
    cog.start()
