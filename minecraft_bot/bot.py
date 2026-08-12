"""Dedicated Discord bot for Minecraft applications and access management."""

from __future__ import annotations

import asyncio
import logging
import time
from contextlib import suppress
from typing import Optional

import discord
from discord import app_commands
from discord.ext import commands, tasks

from .bridge import MinecraftBridgeServer
from .config import MinecraftConfig
from .data import MinecraftDataManager
from .models import ApplicationStatus, BridgeAction, InvalidTransition, MinecraftApplication, OutboxRecord
from .presentation import (
    application_panel,
    application_embeds,
    application_panel_files,
    application_log_embed,
    approval_embed,
    branded_edit,
    branded_send,
    denial_embed,
    decision_log_embed,
    info_embed,
    review_embed,
    player_activity_embed,
    verification_log_embed,
    verified_embed,
)
from .settings import MinecraftSettings, SETTING_KEYS
from .ui import ReviewView


logger = logging.getLogger("MinecraftAccessBot")


class RateLimiter:
    def __init__(self, seconds: float, *, max_entries: int = 10_000) -> None:
        self.seconds = seconds
        self.max_entries = max_entries
        self._entries: dict[int, float] = {}

    def claim(self, key: int, *, now: Optional[float] = None) -> bool:
        current = time.monotonic() if now is None else now
        previous = self._entries.get(int(key))
        if previous is not None and current - previous < self.seconds:
            return False
        if len(self._entries) >= self.max_entries:
            cutoff = current - self.seconds
            self._entries = {entry: seen for entry, seen in self._entries.items() if seen >= cutoff}
            if len(self._entries) >= self.max_entries:
                oldest = min(self._entries, key=self._entries.get)
                self._entries.pop(oldest, None)
        self._entries[int(key)] = current
        return True


class MinecraftAccessBot(commands.Bot):
    def __init__(self, config: MinecraftConfig) -> None:
        intents = discord.Intents.none()
        intents.guilds = True
        intents.members = True
        super().__init__(
            command_prefix=commands.when_mentioned,
            intents=intents,
            help_command=None,
            allowed_mentions=discord.AllowedMentions.none(),
        )
        self.config = config
        self.settings = MinecraftSettings.from_sources(config, {})
        self._settings_lock = asyncio.Lock()
        self.data = MinecraftDataManager(config.database_path)
        self.bridge = MinecraftBridgeServer(
            config,
            self.data,
            verification_handler=self.handle_bridge_verification,
            action_result_handler=self.handle_bridge_action_result,
            player_event_handler=self.handle_player_event,
        )
        self.apply_rate_limit = RateLimiter(5)
        self.status_rate_limit = RateLimiter(10)
        self._application_interactions: dict[int, tuple[discord.Interaction, float]] = {}
        self._commands_synced = False
        self._application_panel_refreshed = False
        self.tree.on_error = self.on_tree_error
        self._minecraft_group = self._build_command_group()
        self.tree.add_command(self._minecraft_group, guild=discord.Object(id=config.guild_id))

    async def setup_hook(self) -> None:
        await self.data.open()
        stored_settings = await self.data.get_configs(SETTING_KEYS)
        self.settings = MinecraftSettings.from_sources(self.config, stored_settings)
        await self.data.set_configs(self.settings.persistent_values())
        self.add_view(ReviewView())
        self.add_view(application_panel())
        await self.bridge.start()
        self.application_maintenance.start()

    async def close(self) -> None:
        self.application_maintenance.cancel()
        await self.bridge.close()
        await self.data.close()
        await super().close()

    async def on_ready(self) -> None:
        if not self._commands_synced:
            try:
                synced = await self.tree.sync(guild=discord.Object(id=self.config.guild_id))
            except discord.HTTPException:
                logger.exception("Minecraft command sync failed")
            else:
                self._commands_synced = True
                logger.info("Synced %d Minecraft commands", len(synced))
        if self.settings.application_channel_id and not self._application_panel_refreshed:
            try:
                await self.post_application_panel()
            except Exception:
                logger.exception("Could not refresh the Minecraft application panel")
            else:
                self._application_panel_refreshed = True
        await self.change_presence(activity=discord.Game(name="Mysterious SMP X applications"))
        print(f"Minecraft access bot connected as {self.user} — successfully finished startup", flush=True)

    async def on_tree_error(self, interaction: discord.Interaction, error: app_commands.AppCommandError) -> None:
        correlation_id = f"mc-{interaction.id:x}"
        logger.error(
            "Minecraft command failed correlation_id=%s",
            correlation_id,
            exc_info=(type(error), error, error.__traceback__),
        )
        embed = info_embed(
            "Minecraft Command Failed",
            "> The requested action could not be completed.\n\n"
            f"**Reference:** `{correlation_id}`\n"
            "Share this reference with staff if the problem continues.",
            error=True,
        )
        if interaction.response.is_done():
            await interaction.followup.send(**branded_send(embed), ephemeral=True)
        else:
            await interaction.response.send_message(**branded_send(embed), ephemeral=True)

    def is_moderator(self, member: discord.Member | discord.User) -> bool:
        permissions = getattr(member, "guild_permissions", None)
        if permissions is not None and permissions.administrator:
            return True
        return any(role.id == self.settings.mod_role_id for role in getattr(member, "roles", ()))

    @staticmethod
    def is_administrator(member: discord.Member | discord.User) -> bool:
        permissions = getattr(member, "guild_permissions", None)
        return bool(permissions is not None and permissions.administrator)

    async def require_moderator(self, interaction: discord.Interaction) -> bool:
        if self.is_moderator(interaction.user):
            return True
        await interaction.response.send_message(
            **branded_send(
                info_embed(
                    "Access Required",
                    "> You do not have permission to manage Minecraft applications or access.",
                    error=True,
                )
            ),
            ephemeral=True,
        )
        return False

    async def require_administrator(self, interaction: discord.Interaction) -> bool:
        if self.is_administrator(interaction.user):
            return True
        await interaction.response.send_message(
            **branded_send(
                info_embed(
                    "Administrator Access Required",
                    "> Only server administrators can change the Minecraft setup.",
                    error=True,
                )
            ),
            ephemeral=True,
        )
        return False

    async def update_settings(
        self,
        *,
        actor_id: Optional[int | str] = None,
        **updates,
    ) -> MinecraftSettings:
        async with self._settings_lock:
            candidate = self.settings.with_updates(**updates)
            await self.data.set_configs(
                {key: getattr(candidate, key) for key in updates},
                actor_id=actor_id,
            )
            self.settings = candidate
            return candidate

    def remember_application_interaction(self, application_id: int, interaction: discord.Interaction) -> None:
        now = time.monotonic()
        self._application_interactions = {
            key: value for key, value in self._application_interactions.items() if value[1] > now
        }
        if len(self._application_interactions) >= 1000:
            oldest = min(self._application_interactions, key=lambda key: self._application_interactions[key][1])
            self._application_interactions.pop(oldest, None)
        self._application_interactions[int(application_id)] = (interaction, now + 14 * 60)

    async def cancel_pending_verification(
        self,
        *,
        guild_id: int,
        discord_user_id: int,
    ) -> MinecraftApplication:
        application = await self.data.cancel_pending_verification_for_user(
            guild_id=guild_id,
            discord_user_id=discord_user_id,
        )
        await self.log_application_decision(application)
        self._application_interactions.pop(application.id, None)
        if self.bridge.connected:
            await self.bridge.dispatch_outbox()
        return application

    async def _configured_channel(self, channel_id: int):
        if not channel_id:
            return None
        channel = self.get_channel(channel_id)
        if channel is not None:
            return channel
        try:
            return await self.fetch_channel(channel_id)
        except (discord.NotFound, discord.Forbidden, discord.HTTPException):
            return None

    async def _configured_guild(self) -> Optional[discord.Guild]:
        guild = self.get_guild(self.config.guild_id)
        if guild is not None:
            return guild
        return None

    async def _send_configured_log(self, channel_id: int, embed: discord.Embed) -> None:
        if not channel_id:
            return
        channel = await self._configured_channel(channel_id)
        if channel is None or not hasattr(channel, "send"):
            logger.warning("Configured Minecraft log channel %s is unavailable", channel_id)
            return
        try:
            await channel.send(
                **branded_send(embed),
                allowed_mentions=discord.AllowedMentions.none(),
            )
        except (discord.Forbidden, discord.HTTPException):
            logger.exception("Could not send Minecraft log to channel %s", channel_id)

    async def log_application_submission(self, application: MinecraftApplication) -> None:
        await self._send_configured_log(
            self.settings.application_log_channel_id,
            application_log_embed(application),
        )

    async def log_application_decision(self, application: MinecraftApplication) -> None:
        await self._send_configured_log(
            self.settings.application_log_channel_id,
            decision_log_embed(application),
        )

    async def handle_player_event(
        self,
        *,
        joined: bool,
        minecraft_uuid: str,
        current_username: str,
        edition: str,
        xuid: Optional[str],
        event_idempotency_key: str,
    ) -> None:
        claimed = await self.data.claim_bridge_event(
            event_idempotency_key,
            "PLAYER_JOIN" if joined else "PLAYER_LEAVE",
        )
        if not claimed:
            return
        discord_user_id = await self.data.get_account_owner(edition, minecraft_uuid)
        await self._send_configured_log(
            self.settings.player_log_channel_id,
            player_activity_embed(
                joined=joined,
                username=current_username,
                minecraft_uuid=minecraft_uuid,
                edition=edition,
                xuid=xuid,
                discord_user_id=discord_user_id,
            ),
        )

    async def post_application_panel(self) -> discord.Message:
        channel = await self._configured_channel(self.settings.application_channel_id)
        if channel is None or not hasattr(channel, "send"):
            raise RuntimeError("The configured Minecraft application channel is unavailable")

        async def fetch_saved_message(config_key: str) -> Optional[discord.Message]:
            message_id = await self.data.get_config(config_key)
            if not message_id:
                return None
            try:
                return await channel.fetch_message(int(message_id))
            except (discord.NotFound, discord.Forbidden, discord.HTTPException, AttributeError):
                return None

        banner = await fetch_saved_message("application_banner_message_id")
        panel = await fetch_saved_message("application_panel_message_id")
        panel_uses_v2 = bool(
            panel is not None
            and getattr(getattr(panel, "flags", None), "components_v2", False)
        )
        if panel is not None and not panel_uses_v2:
            if banner is not None:
                with suppress(discord.NotFound, discord.Forbidden, discord.HTTPException):
                    await banner.delete()
            await panel.edit(
                content=None,
                embeds=application_embeds(),
                attachments=application_panel_files(),
                view=application_panel(),
            )
            await self.data.set_configs(
                {
                    "application_banner_message_id": "",
                    "application_panel_message_id": str(panel.id),
                }
            )
            return panel

        for old_message in (banner, panel):
            if old_message is not None:
                with suppress(discord.NotFound, discord.Forbidden, discord.HTTPException):
                    await old_message.delete()

        panel = await channel.send(
            embeds=application_embeds(),
            files=application_panel_files(),
            view=application_panel(),
        )
        await self.data.set_configs(
            {
                "application_banner_message_id": "",
                "application_panel_message_id": str(panel.id),
            }
        )
        return panel

    async def handle_bridge_verification(
        self,
        *,
        application_id: int,
        edition,
        minecraft_uuid: str,
        current_username: str,
        xuid: Optional[str],
        event_idempotency_key: str,
    ) -> None:
        application, changed = await self.data.record_verification(
            application_id=application_id,
            edition=edition,
            minecraft_uuid=minecraft_uuid,
            current_username=current_username,
            xuid=xuid,
            event_idempotency_key=event_idempotency_key,
        )
        if not changed:
            if application.review_message_id is None:
                with suppress(Exception):
                    await self.post_or_update_review(application)
            return
        try:
            await self.post_or_update_review(application)
        except Exception:
            logger.exception("Could not publish review for Minecraft application %s", application.id)
        await self._send_configured_log(
            self.settings.verification_log_channel_id,
            verification_log_embed(application),
        )
        user = self.get_user(int(application.discord_user_id))
        if user is None:
            with suppress(discord.NotFound, discord.HTTPException):
                user = await self.fetch_user(int(application.discord_user_id))
        if user is not None:
            with suppress(discord.Forbidden, discord.HTTPException):
                await user.send(
                    **branded_send(verified_embed(application))
                )
        remembered = self._application_interactions.pop(application.id, None)
        if remembered is not None and remembered[1] > time.monotonic():
            with suppress(discord.NotFound, discord.HTTPException):
                await remembered[0].edit_original_response(
                    **branded_edit(verified_embed(application))
                )

    async def post_or_update_review(self, application: MinecraftApplication) -> None:
        if application.review_message_id:
            await self.update_review_message(application)
            return
        channel = await self._configured_channel(self.settings.review_channel_id)
        if channel is None or not hasattr(channel, "send"):
            logger.error("Review channel unavailable for Minecraft application %s", application.id)
            return
        guild = await self._configured_guild()
        member = guild.get_member(int(application.discord_user_id)) if guild else None
        user = member or self.get_user(int(application.discord_user_id))
        if user is None:
            with suppress(discord.NotFound, discord.HTTPException):
                user = await self.fetch_user(int(application.discord_user_id))
        message = await channel.send(
            **branded_send(review_embed(application, user=user, member=member)),
            view=ReviewView(disabled=application.status is not ApplicationStatus.PENDING_REVIEW),
            allowed_mentions=discord.AllowedMentions.none(),
        )
        await self.data.set_review_message(application.id, channel.id, message.id)

    async def update_review_message(self, application: MinecraftApplication) -> None:
        if not application.review_channel_id or not application.review_message_id:
            if application.status is ApplicationStatus.PENDING_REVIEW:
                await self.post_or_update_review(application)
            return
        channel = await self._configured_channel(int(application.review_channel_id))
        if channel is None or not hasattr(channel, "fetch_message"):
            logger.warning("Review channel missing for Minecraft application %s", application.id)
            return
        try:
            message = await channel.fetch_message(int(application.review_message_id))
        except (discord.NotFound, discord.Forbidden, discord.HTTPException):
            logger.warning("Review message missing for Minecraft application %s", application.id)
            return
        guild = await self._configured_guild()
        member = guild.get_member(int(application.discord_user_id)) if guild else None
        user = member or self.get_user(int(application.discord_user_id))
        await message.edit(
            **branded_edit(review_embed(application, user=user, member=member)),
            view=ReviewView(disabled=application.status is not ApplicationStatus.PENDING_REVIEW),
            allowed_mentions=discord.AllowedMentions.none(),
        )

    async def finish_denial(self, application: MinecraftApplication) -> None:
        await self.update_review_message(application)
        await self.log_application_decision(application)
        user = self.get_user(int(application.discord_user_id))
        if user is None:
            with suppress(discord.NotFound, discord.HTTPException):
                user = await self.fetch_user(int(application.discord_user_id))
        if user is not None:
            with suppress(discord.Forbidden, discord.HTTPException):
                await user.send(
                    **branded_send(denial_embed(application))
                )

    async def handle_bridge_action_result(
        self,
        record: OutboxRecord,
        application: Optional[MinecraftApplication],
    ) -> None:
        if application is None:
            if record.action in {BridgeAction.APPROVE, BridgeAction.REVOKE}:
                logger.warning(
                    "Minecraft action %s failed for application %s: %s",
                    record.action.value,
                    record.application_id,
                    record.last_error or "Paper rejected the action",
                )
            return
        if record.action is BridgeAction.APPROVE and application.status is ApplicationStatus.APPROVED:
            try:
                await self._finish_approval(application)
                await self.log_application_decision(application)
            except Exception:
                logger.exception("Discord approval finalization failed for application %s", application.id)
        elif record.action is BridgeAction.REVOKE and application.status is ApplicationStatus.REVOKED:
            try:
                await self._finish_revocation(application)
                await self.log_application_decision(application)
            except Exception:
                logger.exception("Discord revocation finalization failed for application %s", application.id)
        try:
            await self.update_review_message(application)
        except Exception:
            logger.exception("Review-message finalization failed for application %s", application.id)

    async def _finish_approval(self, application: MinecraftApplication) -> None:
        guild = await self._configured_guild()
        member = guild.get_member(int(application.discord_user_id)) if guild else None
        role = guild.get_role(self.settings.member_role_id) if guild else None
        if member is not None and role is not None:
            try:
                await member.add_roles(role, reason="Minecraft application approved")
            except (discord.Forbidden, discord.HTTPException) as exc:
                logger.exception("Approved-role assignment failed for application %s", application.id)
                try:
                    await self.data.write_audit(
                        "APPROVED_ROLE_FAILED",
                        application_id=application.id,
                        target_id=application.discord_user_id,
                        payload={"error_type": type(exc).__name__},
                    )
                except Exception:
                    logger.exception("Could not record approved-role failure for application %s", application.id)
        user = member or self.get_user(int(application.discord_user_id))
        if user is not None:
            with suppress(discord.Forbidden, discord.HTTPException):
                await user.send(
                    **branded_send(approval_embed(self.settings))
                )

    async def _finish_revocation(self, application: MinecraftApplication) -> None:
        guild = await self._configured_guild()
        member = guild.get_member(int(application.discord_user_id)) if guild else None
        role = guild.get_role(self.settings.member_role_id) if guild else None
        remaining = [
            item
            for item in await self.data.list_applications_for_user(application.discord_user_id, limit=100)
            if item.status is ApplicationStatus.APPROVED
        ]
        if member is not None and role is not None and not remaining:
            with suppress(discord.Forbidden, discord.HTTPException):
                await member.remove_roles(role, reason="Minecraft access revoked")

    @tasks.loop(seconds=30)
    async def application_maintenance(self) -> None:
        try:
            expired = await self.data.expire_pending(limit=100)
            for application in expired:
                await self.log_application_decision(application)
            if self.bridge.connected:
                await self.bridge.dispatch_outbox()
            await self._restore_missing_reviews()
        except asyncio.CancelledError:
            raise
        except Exception:
            logger.exception("Minecraft application maintenance failed")

    @application_maintenance.before_loop
    async def before_application_maintenance(self) -> None:
        await self.wait_until_ready()

    async def _restore_missing_reviews(self) -> None:
        for application in await self.data.list_missing_review_messages(limit=20):
            await self.post_or_update_review(application)

    def _build_command_group(self) -> app_commands.Group:
        group = app_commands.Group(name="minecraft", description="Manage Minecraft applications and access.")

        @group.command(name="setup", description="Open the Minecraft application setup dashboard.")
        @app_commands.default_permissions(administrator=True)
        async def setup(interaction: discord.Interaction) -> None:
            if not await self.require_administrator(interaction):
                return
            from .setup import MinecraftSetupView

            await interaction.response.send_message(
                view=MinecraftSetupView(self, interaction.user.id, interaction.guild),
                ephemeral=True,
            )

        @group.command(name="status", description="Show Minecraft bridge and queue health.")
        async def status(interaction: discord.Interaction) -> None:
            if not await self.require_moderator(interaction):
                return
            if not self.status_rate_limit.claim(interaction.user.id):
                await interaction.response.send_message(
                    **branded_send(
                        info_embed(
                            "Status Recently Requested",
                            "> Please wait a few seconds before requesting runtime status again.",
                        )
                    ),
                    ephemeral=True,
                )
                return
            await interaction.response.defer(ephemeral=True)
            outbox = await self.data.outbox_counts()
            applications = await self.data.application_status_counts()
            from .setup import configuration_findings

            setup_findings = configuration_findings(self, interaction.guild)
            heartbeat = (
                f"<t:{int(self.bridge.last_heartbeat_at)}:R>"
                if self.bridge.last_heartbeat_at is not None
                else "Never"
            )
            embed = info_embed(
                "Minecraft Access Status",
                "Live health for the dedicated Discord application bot and Minecraft bridge.",
            )
            embed.add_field(
                name="Runtime",
                value=(
                    f"**Bridge:** {'Connected' if self.bridge.connected else 'Offline'}\n"
                    f"**Setup:** {'Ready' if not setup_findings else 'Needs attention'}\n"
                    f"**Last heartbeat:** {heartbeat}"
                ),
                inline=False,
            )
            embed.add_field(
                name="Applications",
                value=(
                    f"**Pending verification:** "
                    f"{applications.get(ApplicationStatus.PENDING_VERIFICATION.value, 0)}\n"
                    f"**Pending review:** {applications.get(ApplicationStatus.PENDING_REVIEW.value, 0)}"
                ),
                inline=True,
            )
            embed.add_field(
                name="Bridge Queue",
                value=(
                    f"**Queued:** {outbox.get('PENDING', 0)}\n"
                    f"**Awaiting confirmation:** {outbox.get('SENT', 0)}\n"
                    f"**Failed:** {outbox.get('FAILED', 0)}"
                ),
                inline=True,
            )
            if setup_findings:
                embed.add_field(
                    name="Setup Attention",
                    value="\n".join(
                        f"**{finding.setting}:** {finding.detail}" for finding in setup_findings[:4]
                    ),
                    inline=False,
                )
            await interaction.edit_original_response(**branded_edit(embed))

        @group.command(name="lookup", description="Show a member's linked accounts and application history.")
        @app_commands.describe(user="Discord member to look up")
        async def lookup(interaction: discord.Interaction, user: discord.Member) -> None:
            if not await self.require_moderator(interaction):
                return
            await interaction.response.defer(ephemeral=True)
            accounts = await self.data.list_accounts_for_user(user.id)
            history = await self.data.list_applications_for_user(user.id, limit=25)
            account_lines = [
                f"{row['edition'].title()} · `{row['current_username']}` · `{row['minecraft_uuid']}`"
                for row in accounts
            ] or ["No linked accounts."]
            history_lines = [
                f"`#{item.id}` · {item.edition.value.title()} · {item.status.value.replace('_', ' ').title()} · <t:{item.created_at}:d>"
                for item in history
            ] or ["No applications."]
            await interaction.edit_original_response(
                **branded_edit(
                    info_embed(
                        f"Minecraft Lookup: {user}",
                        "**Linked Accounts**\n"
                        + "\n".join(account_lines)
                        + "\n\n**Applications**\n"
                        + "\n".join(history_lines),
                    )
                )
            )

        @group.command(name="revoke", description="Remove a member's Minecraft access.")
        @app_commands.describe(user="Discord member to revoke", reason="Internal audit reason")
        async def revoke(interaction: discord.Interaction, user: discord.Member, reason: app_commands.Range[str, 1, 500]) -> None:
            if not await self.require_moderator(interaction):
                return
            await interaction.response.defer(ephemeral=True, thinking=True)
            applications = await self.data.queue_revocations(user.id, interaction.user.id, reason)
            if not applications:
                await interaction.edit_original_response(
                    **branded_edit(
                        info_embed(
                            "No Access to Revoke",
                            "> That member has no approved Minecraft account access.",
                            error=True,
                        )
                    )
                )
                return
            if self.bridge.connected:
                await self.bridge.dispatch_outbox()
            await interaction.edit_original_response(
                **branded_edit(
                    info_embed(
                        "Revocation Queued",
                        f"> **{len(applications)}** Minecraft account revocation(s) were queued.\n\n"
                        "Discord access will be removed only after the Minecraft server confirms every action.",
                        success=True,
                    )
                )
            )

        @group.command(name="retry", description="Retry failed bridge actions for an application.")
        @app_commands.describe(application="Application ID")
        async def retry(interaction: discord.Interaction, application: app_commands.Range[int, 1]) -> None:
            if not await self.require_moderator(interaction):
                return
            await interaction.response.defer(ephemeral=True)
            count = await self.data.retry_application(application)
            if self.bridge.connected and count:
                await self.bridge.dispatch_outbox()
            await interaction.edit_original_response(
                **branded_edit(
                    info_embed(
                        "Retry Scheduled",
                        f"> Reset **{count}** failed bridge action(s) for another delivery attempt.",
                        success=bool(count),
                    )
                )
            )

        @group.command(name="log-channel", description="Configure or disable a Minecraft event log.")
        @app_commands.default_permissions(administrator=True)
        @app_commands.describe(
            log="Event stream to configure",
            channel="Destination channel; leave empty to disable this log",
        )
        @app_commands.choices(
            log=[
                app_commands.Choice(name="Applications and decisions", value="application"),
                app_commands.Choice(name="Account verifications", value="verification"),
                app_commands.Choice(name="Player joins and leaves", value="player"),
            ]
        )
        async def log_channel(
            interaction: discord.Interaction,
            log: app_commands.Choice[str],
            channel: Optional[discord.TextChannel] = None,
        ) -> None:
            if not await self.require_administrator(interaction):
                return
            await interaction.response.defer(ephemeral=True, thinking=True)
            setting = {
                "application": "application_log_channel_id",
                "verification": "verification_log_channel_id",
                "player": "player_log_channel_id",
            }[log.value]
            await self.update_settings(
                actor_id=interaction.user.id,
                **{setting: channel.id if channel is not None else 0},
            )
            destination = channel.mention if channel is not None else "Disabled"
            await interaction.edit_original_response(
                **branded_edit(
                    info_embed(
                        "Minecraft Log Updated",
                        f"> **{log.name}**\n\n**Destination:** {destination}\n"
                        "The change takes effect immediately.",
                        success=True,
                    )
                )
            )

        @group.command(name="applications", description="List recent Minecraft applications by status.")
        @app_commands.describe(status="Optional application state", limit="Number of records to show")
        @app_commands.choices(
            status=[
                app_commands.Choice(
                    name=state.value.replace("_", " ").title(),
                    value=state.value,
                )
                for state in ApplicationStatus
            ]
        )
        async def applications(
            interaction: discord.Interaction,
            status: Optional[app_commands.Choice[str]] = None,
            limit: app_commands.Range[int, 1, 25] = 10,
        ) -> None:
            if not await self.require_moderator(interaction):
                return
            await interaction.response.defer(ephemeral=True)
            parsed_status = ApplicationStatus(status.value) if status is not None else None
            records = await self.data.list_applications(status=parsed_status, limit=limit)
            lines = [
                f"`#{item.id}` · <@{item.discord_user_id}> · {item.edition.value.title()} · "
                f"`{item.verified_username or item.claimed_username}` · "
                f"{item.status.value.replace('_', ' ').title()} · <t:{item.created_at}:R>"
                for item in records
            ]
            await interaction.edit_original_response(
                **branded_edit(
                    info_embed(
                        "Minecraft Applications",
                        "\n".join(lines) if lines else "> No matching applications were found.",
                    )
                )
            )

        @group.command(name="audit", description="Show the recorded lifecycle for an application.")
        @app_commands.describe(application="Application ID")
        async def audit(
            interaction: discord.Interaction,
            application: app_commands.Range[int, 1],
        ) -> None:
            if not await self.require_moderator(interaction):
                return
            await interaction.response.defer(ephemeral=True)
            record = await self.data.get_application(application)
            if record is None:
                await interaction.edit_original_response(
                    **branded_edit(
                        info_embed(
                            "Application Not Found",
                            f"> Application `#{application}` does not exist.",
                            error=True,
                        )
                    )
                )
                return
            rows = await self.data.audit_rows(application)
            lines = [
                f"<t:{int(row['created_at'])}:F> · **{str(row['action']).replace('_', ' ').title()}**"
                for row in rows[-20:]
            ]
            await interaction.edit_original_response(
                **branded_edit(
                    info_embed(
                        f"Application Audit #{application}",
                        f"> <@{record.discord_user_id}> · `{record.claimed_username}` · "
                        f"**{record.status.value.replace('_', ' ').title()}**\n\n"
                        + ("\n".join(lines) if lines else "No audit events were recorded."),
                    )
                )
            )

        @group.command(name="cancel", description="Cancel your pending verification or a staff-managed application.")
        @app_commands.describe(application="Staff only: application ID to cancel")
        async def cancel(
            interaction: discord.Interaction,
            application: Optional[app_commands.Range[int, 1]] = None,
        ) -> None:
            if application is None:
                await interaction.response.defer(ephemeral=True, thinking=True)
                try:
                    await self.cancel_pending_verification(
                        guild_id=interaction.guild_id,
                        discord_user_id=interaction.user.id,
                    )
                except InvalidTransition as exc:
                    await interaction.edit_original_response(
                        **branded_edit(
                            info_embed("Nothing to Cancel", f"> {exc}", error=True)
                        )
                    )
                    return
                await interaction.edit_original_response(
                    **branded_edit(
                        info_embed(
                            "Verification Cancelled",
                            "> Your pending Minecraft verification was cancelled successfully.\n\n"
                            "Return to the application panel and press **Apply** when you are ready to enter the correct username.",
                            success=True,
                        )
                    )
                )
                return
            if not await self.require_moderator(interaction):
                return
            await interaction.response.defer(ephemeral=True, thinking=True)
            try:
                updated = await self.data.cancel_application(application, interaction.user.id)
            except InvalidTransition as exc:
                await interaction.edit_original_response(
                    **branded_edit(
                        info_embed("Application Not Cancelled", f"> {exc}", error=True)
                    )
                )
                return
            await self.update_review_message(updated)
            await self.log_application_decision(updated)
            if self.bridge.connected:
                await self.bridge.dispatch_outbox()
            await interaction.edit_original_response(
                **branded_edit(
                    info_embed(
                        "Application Cancelled",
                        f"> Application `#{updated.id}` was cancelled and its Minecraft access actions were updated.",
                        success=True,
                    )
                )
            )

        return group


def run() -> None:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )
    config = MinecraftConfig.from_env()
    bot = MinecraftAccessBot(config)
    bot.run(config.discord_token, log_handler=None)
