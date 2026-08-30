"""MGXBot class, background tasks, extension loading, and bot lifecycle."""
from __future__ import annotations

import asyncio
import hashlib
import json
import logging
import os
import time
from typing import List, Optional

import aiohttp
import discord
from discord.ext import commands, tasks

logger = logging.getLogger("MGXBot")

from core.constants import DEFAULT_GUILD_ID, SCOPE_ROLES, SCOPE_SUPPORT, TEST_GUILD_ID
from core.context import set_bot
from core.data import DataManager, resolve_bot_token
from core.heavy_jobs import HeavyJobQueue, RecentMessageIndex
from core.metrics import MetricsCommandTree, OperationMetrics, install_component_metrics
from core.runtime import AsyncTTLCache, TTLMap, retry_with_backoff
from core.services import get_feature_flag, ticket_needs_sla_alert
from core.utils import now_iso


EXTENSIONS = (
    "cogs.cases",
    "cogs.history",
    "cogs.case_panel",
    "cogs.moderation",
    "cogs.export",
    "cogs.roles",
    "cogs.server_tags",
    "cogs.derole",
    "cogs.modmail",
    "cogs.automod",
    "cogs.config",
    "cogs.analytics",
    "cogs.admin",
    "cogs.control_plane",
    "cogs.events",
    "cogs.event_leaderboard",
)

DISABLED_APPLICATION_COMMANDS = frozenset({
    "ban",
    "kick",
    "modmail",
    "onboarding",
    "feature",
    "publicpunish",
    "role-create",
    "rolecreate",
    "create-role",
    "listcommands",
    "rolehelp",
    "rolesettings",
    "undopunish",
    "unlockdown",
    "antinuke",
})


def _build_intents() -> discord.Intents:
    intents = discord.Intents.default()
    intents.guilds = True
    intents.members = True
    intents.message_content = True
    intents.voice_states = True  # required for the VC event leaderboard
    if hasattr(intents, "auto_moderation_configuration"):
        intents.auto_moderation_configuration = True
    if hasattr(intents, "auto_moderation_execution"):
        intents.auto_moderation_execution = True
    return intents


def command_payloads(
    tree: discord.app_commands.CommandTree,
    *,
    guild: Optional[discord.abc.Snowflake] = None,
) -> List[dict]:
    """Serialise the current command tree scope to plain dicts for hashing."""
    payloads = []
    for command in tree.get_commands(guild=guild):
        try:
            payloads.append(command.to_dict(tree))
        except Exception:
            # Fall back to a coarse identity if a command can't be serialised, so
            # a change there still nudges the fingerprint rather than crashing.
            payloads.append({"name": getattr(command, "qualified_name", repr(command))})
    return payloads


def fingerprint_payloads(payloads: List[dict]) -> str:
    """Order-independent SHA-256 of serialised commands; same set → same hash."""
    encoded = sorted(json.dumps(p, sort_keys=True, default=str) for p in payloads)
    return hashlib.sha256("\n".join(encoded).encode("utf-8")).hexdigest()


class MGXBot(commands.Bot):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.session: Optional[aiohttp.ClientSession] = None
        self.data_manager: Optional[DataManager] = None
        self.start_time = time.time()
        self.metrics = OperationMetrics()
        self.active_executions = TTLMap(max_size=2000, ttl_seconds=3600)
        self.dm_modmail_prompt_cooldowns = TTLMap(max_size=10_000, ttl_seconds=86400)
        self.native_automod_event_cache = TTLMap(max_size=20_000, ttl_seconds=300)
        self.native_automod_rule_cache = AsyncTTLCache(max_size=1000, ttl_seconds=300)
        self.heavy_jobs: Optional[HeavyJobQueue] = None
        self.recent_messages = RecentMessageIndex()
        self.abuse_system = None
        self._commands_synced = False
        for loop_name, interval in (
            ("tempban expiry", 60),
            ("storage maintenance", 3600),
            ("presence", 1800),
            ("fleet snapshot", 300),
            ("modmail SLA", 600),
            ("role cleanup", 21600),
        ):
            self.metrics.register_loop(loop_name, expected_interval_seconds=interval)

    async def setup_hook(self) -> None:
        from core.data import AntiAbuseSystem

        self.session = aiohttp.ClientSession()
        self.data_manager = DataManager(self)
        self.abuse_system = AntiAbuseSystem()
        self.heavy_jobs = HeavyJobQueue(metrics=self.metrics)
        install_component_metrics()
        await self.data_manager.load_all()

        for extension in EXTENSIONS:
            await self.load_extension(extension)

        if os.environ.get("TEST_MODE"):
            await self.load_extension("cogs.testkit")
            logger.info("TEST_MODE active — testkit cog loaded")

        self._remove_disabled_application_commands()
        self._validate_action_registry()
        await self._restore_persistent_views()
        self.heavy_jobs.start()
        self.metrics.start_event_loop_monitor()

        self.check_tempbans.start()
        self.storage_maintenance_task.start()
        self.status_task.start()
        self.modmail_sla_task.start()
        self.role_cleanup_task.start()
        self.project_stats_task.start()

    async def _restore_persistent_views(self) -> None:
        from cogs.automod import (
            AutoModReportResponseSelect,
            AutoModWarningReportButton,
            ImageFalsePositiveButton,
            ImageReviewPunishButton,
        )
        from cogs.case_panel import OpenCaseButton
        from cogs.admin import AntiNukeResolveButton
        from cogs.event_leaderboard import EventHistorySelect
        from cogs.moderation import RevokeUndoButton
        from cogs.modmail import ModmailActionButton, ModmailPanelView
        from cogs.roles import AppealButton, AppealDenyButton, AppealRevokeButton

        self.add_dynamic_items(
            OpenCaseButton,
            AppealButton,
            AppealRevokeButton,
            AppealDenyButton,
            RevokeUndoButton,
            ImageFalsePositiveButton,
            ImageReviewPunishButton,
            ModmailActionButton,
            EventHistorySelect,
            AutoModReportResponseSelect,
            AutoModWarningReportButton,
            AntiNukeResolveButton,
        )
        self.add_view(ModmailPanelView())

    def _remove_disabled_application_commands(self) -> None:
        for command_name in DISABLED_APPLICATION_COMMANDS:
            for command_type in (
                discord.AppCommandType.chat_input,
                discord.AppCommandType.user,
                discord.AppCommandType.message,
            ):
                self.tree.remove_command(command_name, type=command_type)

    def _validate_action_registry(self) -> None:
        from core.actions import validate_registered_actions

        undocumented, unavailable = validate_registered_actions(self.tree.walk_commands())
        if os.environ.get("TEST_MODE"):
            undocumented = {name for name in undocumented if not name.startswith("test-")}
        if undocumented or unavailable:
            raise RuntimeError(
                "Action registry mismatch: "
                f"undocumented={sorted(undocumented)}, unavailable={sorted(unavailable)}"
            )

    def _resolve_sync_targets(self) -> list:
        """Guild id(s) to register commands in, decided once the bot is ready.

        Under TEST_MODE the target is strictly ``TEST_GUILD_ID`` with no fallback,
        so a staging bot can never leak commands into a live server it happens to
        be in. In production the configured ``guild_id`` is used when the bot is
        actually a member of it; otherwise (unset/wrong guild, e.g. a fresh
        instance before /setup) it falls back to whatever guild(s) the bot is in so
        commands still register.
        """
        if os.environ.get("TEST_MODE"):
            return [TEST_GUILD_ID]

        configured = self.data_manager.config.get("guild_id", DEFAULT_GUILD_ID)
        if configured and self.get_guild(int(configured)):
            return [int(configured)]

        if configured:
            logger.warning(
                "Not a member of configured guild %s (run /setup) — syncing to current guild(s) instead",
                configured,
            )
        return [g.id for g in self.guilds]

    def _resolve_scoped_sync_targets(self) -> list:
        """Return joined guilds that have commands explicitly scoped to them."""
        targets = []
        for guild in self.guilds:
            guild_ref = discord.Object(id=int(guild.id))
            if self.tree.get_commands(guild=guild_ref):
                targets.append(int(guild.id))
        return targets

    async def _auto_sync_commands(self) -> None:
        """Sync slash commands to this instance's guild(s) once the bot is ready.

        Replaces the manual ``!sync`` for the common case: each single-guild
        instance keeps its own guild current on deploy. A per-guild fingerprint of
        the command set is stored in config so unchanged restarts skip the API call
        and don't burn Discord's command-sync rate limit. ``!sync`` stays as a
        manual override; a sync failure here must never block startup.
        """
        if not self.data_manager:
            return

        global_targets = self._resolve_sync_targets()
        scoped_targets = self._resolve_scoped_sync_targets()
        targets = list(dict.fromkeys([*global_targets, *scoped_targets]))
        if not targets:
            return

        synced_fingerprints = {}
        for target_id in targets:
            guild = discord.Object(id=int(target_id))
            if target_id in global_targets:
                self.tree.copy_global_to(guild=guild)
            fingerprint = fingerprint_payloads(command_payloads(self.tree, guild=guild))
            state_key = f"synced_command_fingerprint_{target_id}"
            if self.data_manager.config.get(state_key) == fingerprint:
                logger.info("Slash commands unchanged for guild %s — skipping sync", target_id)
                continue

            try:
                synced = await self.tree.sync(guild=guild)
            except discord.HTTPException as exc:
                logger.warning("Auto-sync to guild %s failed: %s", target_id, exc)
                continue

            synced_fingerprints[state_key] = fingerprint
            logger.info("Auto-synced %d slash commands to guild %s", len(synced), target_id)

        if synced_fingerprints:
            await self.data_manager.set_config_values(**synced_fingerprints)

    async def close(self) -> None:
        for task_loop in (
            self.check_tempbans,
            self.storage_maintenance_task,
            self.status_task,
            self.modmail_sla_task,
            self.role_cleanup_task,
            self.project_stats_task,
        ):
            task_loop.cancel()

        if self.heavy_jobs:
            await self.heavy_jobs.shutdown(timeout=10.0)
        await self.metrics.stop_event_loop_monitor()
        if self.data_manager:
            await self.data_manager.close()
        if self.session:
            await self.session.close()
        await super().close()

    async def _run_background_loop(self, name: str, operation) -> None:
        metrics = getattr(self, "metrics", None)
        try:
            await retry_with_backoff(operation)
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            if metrics is not None:
                metrics.record_loop_failure(name, exc)
            logger.exception("Background loop %s failed; it will retry on the next interval", name)
        else:
            if metrics is not None:
                metrics.record_loop_success(name)

    @tasks.loop(minutes=1)
    async def check_tempbans(self) -> None:
        await MGXBot._run_background_loop(
            self,
            "tempban expiry",
            lambda: MGXBot._check_tempbans_once(self),
        )

    async def _check_tempbans_once(self) -> None:
        if not self.data_manager:
            return
        guild = self.get_guild(self.data_manager.config.get("guild_id", DEFAULT_GUILD_ID))
        if guild is None:
            return

        for uid, record in await self.data_manager.get_due_tempbans(limit=100):
            case_id = record.get("case_id")
            if not isinstance(case_id, int):
                continue
            try:
                await guild.unban(discord.Object(id=int(uid)), reason="Tempban Expired")
            except discord.NotFound:
                pass
            except (discord.Forbidden, discord.HTTPException) as exc:
                logger.warning("Could not expire tempban case %s: %s", case_id, exc)
                continue
            except Exception as exc:
                logger.exception("Unexpected tempban expiry failure for case %s: %s", case_id, exc)
                continue
            await self.data_manager.mark_punishment_inactive(case_id)

    @tasks.loop(hours=1)
    async def storage_maintenance_task(self) -> None:
        await MGXBot._run_background_loop(
            self,
            "storage maintenance",
            lambda: MGXBot._storage_maintenance_once(self),
        )

    async def _storage_maintenance_once(self) -> None:
        if self.data_manager:
            await self.data_manager.prune_native_automod_history()

    @tasks.loop(minutes=30)
    async def status_task(self) -> None:
        await MGXBot._run_background_loop(self, "presence", lambda: MGXBot._status_once(self))

    async def _status_once(self) -> None:
        # "Listening to DMs for support" — reads cleanly and reflects modmail
        await self.change_presence(
            activity=discord.Activity(type=discord.ActivityType.listening, name="DMs for support")
        )

    @tasks.loop(minutes=5)
    async def project_stats_task(self) -> None:
        await MGXBot._run_background_loop(
            self,
            "fleet snapshot",
            lambda: MGXBot._project_stats_once(self),
        )

    async def _project_stats_once(self) -> None:
        # Publish this instance's stats so /about can aggregate the whole fleet.
        from core.project_stats import write_snapshot

        await write_snapshot(self)

    @tasks.loop(minutes=10)
    async def modmail_sla_task(self) -> None:
        await MGXBot._run_background_loop(
            self,
            "modmail SLA",
            lambda: MGXBot._modmail_sla_once(self),
        )

    async def _modmail_sla_once(self) -> None:
        from cogs.shared import make_embed

        if not self.data_manager or not get_feature_flag(self.data_manager.config, "advanced_modmail", True):
            return

        guild = self.get_guild(self.data_manager.config.get("guild_id", DEFAULT_GUILD_ID))
        if not guild:
            return

        now = discord.utils.utcnow()
        sla_minutes = max(5, int(self.data_manager.config.get("modmail_sla_minutes", 60)))
        for user_id, ticket in list(self.data_manager.modmail.items()):
            if not isinstance(ticket, dict):
                continue
            if not ticket_needs_sla_alert(ticket, now, sla_minutes):
                continue

            thread_id = ticket.get("thread_id")
            thread = guild.get_thread(thread_id) if thread_id else None
            if not thread and thread_id:
                try:
                    thread = await self.fetch_channel(thread_id)
                except Exception:
                    thread = None

            assigned = ticket.get("assigned_moderator")
            assigned_text = f"<@{assigned}>" if assigned else "Unassigned"
            embed = make_embed(
                "Reply Reminder",
                f"> This ticket has not received a staff reply in over **{sla_minutes} minute{'s' if sla_minutes != 1 else ''}**.",
                kind="warning",
                scope=SCOPE_SUPPORT,
            )
            embed.add_field(name="Assigned To", value=assigned_text, inline=True)
            embed.add_field(name="SLA Threshold", value=f"{sla_minutes} min", inline=True)
            if thread:
                try:
                    await thread.send(embed=embed)
                except Exception:
                    pass

            await self.data_manager.mutate_modmail_ticket(
                user_id,
                lambda candidate: {**candidate, "last_sla_alert_at": now_iso()},
            )

    @tasks.loop(hours=6)
    async def role_cleanup_task(self) -> None:
        await MGXBot._run_background_loop(
            self,
            "role cleanup",
            lambda: MGXBot._role_cleanup_once(self),
        )

    async def _role_cleanup_once(self) -> None:
        from cogs.shared import send_log
        from cogs.shared import get_custom_role_limit
        from cogs.shared import format_reason_value, make_embed

        if not self.data_manager or not get_feature_flag(self.data_manager.config, "role_cleanup", True):
            return

        guild = self.get_guild(self.data_manager.config.get("guild_id", DEFAULT_GUILD_ID))
        if not guild:
            return

        for user_id, records in list(self.data_manager.roles.items()):
            # Records are stored as a list of role dicts per user
            records = records if isinstance(records, list) else [records]

            member = guild.get_member(int(user_id))
            if not member:
                try:
                    member = await guild.fetch_member(int(user_id))
                except Exception:
                    member = None

            # Still eligible — leave all their roles intact
            if member and get_custom_role_limit(member) > 0:
                continue

            # No longer eligible — remove every custom role this user owns
            remaining_records = []
            removed_count = 0
            for record in records:
                if not isinstance(record, dict):
                    continue
                role_id = record.get("role_id")
                role = guild.get_role(role_id) if role_id else None
                if role:
                    try:
                        await role.delete(reason="Custom role eligibility cleanup")
                    except discord.NotFound:
                        removed_count += 1
                    except (discord.Forbidden, discord.HTTPException) as exc:
                        logger.warning("Could not clean up custom role %s for user %s: %s", role_id, user_id, exc)
                        remaining_records.append(record)
                    except Exception as exc:
                        logger.exception("Unexpected custom role cleanup failure for role %s: %s", role_id, exc)
                        remaining_records.append(record)
                    else:
                        removed_count += 1
                else:
                    removed_count += 1

            await self.data_manager.set_role_records(user_id, remaining_records or None)

            if not removed_count:
                continue

            embed = make_embed(
                "Custom Role Cleanup",
                "> A custom role was removed because the owner no longer meets the eligibility requirements.",
                kind="warning",
                scope=SCOPE_ROLES,
                guild=guild,
            )
            embed.add_field(name="Target", value=f"<@{user_id}> (`{user_id}`)", inline=True)
            embed.add_field(
                name="Reason",
                value=format_reason_value("Lost booster or approved-role eligibility", limit=300),
                inline=False,
            )
            await send_log(guild, embed)

    async def on_ready(self) -> None:
        logger.info("Logged in as %s (id=%s)", self.user, self.user.id)
        # BisectHosting panel watches for this exact phrase to flip the server
        # state from "starting" to "running". Print it before syncing so a slow
        # command sync never delays the panel's running signal.
        print("successfully finished startup", flush=True)

        # Sync once per process; on_ready also fires on every gateway reconnect.
        if not self._commands_synced:
            self._commands_synced = True
            await self._auto_sync_commands()

    @status_task.before_loop
    async def before_status_task(self) -> None:
        await self.wait_until_ready()

    @check_tempbans.before_loop
    async def before_check_tempbans(self) -> None:
        await self.wait_until_ready()

    @storage_maintenance_task.before_loop
    async def before_storage_maintenance_task(self) -> None:
        await self.wait_until_ready()

    @project_stats_task.before_loop
    async def before_project_stats_task(self) -> None:
        await self.wait_until_ready()

    @modmail_sla_task.before_loop
    async def before_modmail_sla_task(self) -> None:
        await self.wait_until_ready()

    @role_cleanup_task.before_loop
    async def before_role_cleanup_task(self) -> None:
        await self.wait_until_ready()

    async def _background_loop_error(self, name: str, error: BaseException) -> None:
        self.metrics.record_loop_failure(name, error)
        logger.error(
            "Background loop %s stopped unexpectedly",
            name,
            exc_info=(type(error), error, error.__traceback__),
        )

    @check_tempbans.error
    async def check_tempbans_error(self, error: BaseException) -> None:
        await self._background_loop_error("tempban expiry", error)

    @storage_maintenance_task.error
    async def storage_maintenance_error(self, error: BaseException) -> None:
        await self._background_loop_error("storage maintenance", error)

    @status_task.error
    async def status_task_error(self, error: BaseException) -> None:
        await self._background_loop_error("presence", error)

    @project_stats_task.error
    async def project_stats_error(self, error: BaseException) -> None:
        await self._background_loop_error("fleet snapshot", error)

    @modmail_sla_task.error
    async def modmail_sla_error(self, error: BaseException) -> None:
        await self._background_loop_error("modmail SLA", error)

    @role_cleanup_task.error
    async def role_cleanup_error(self, error: BaseException) -> None:
        await self._background_loop_error("role cleanup", error)


def create_bot() -> MGXBot:
    bot = MGXBot(command_prefix="!", intents=_build_intents(), tree_cls=MetricsCommandTree)
    set_bot(bot)
    return bot


def run() -> None:
    bot = create_bot()
    bot.run(resolve_bot_token())
