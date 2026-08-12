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
    approval_embed,
    brand_logo_file,
    info_embed,
    review_embed,
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
        message = f"The command failed. Reference `{correlation_id}` if you contact staff."
        if interaction.response.is_done():
            await interaction.followup.send(message, ephemeral=True)
        else:
            await interaction.response.send_message(message, ephemeral=True)

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
        await interaction.response.send_message("You are not allowed to manage Minecraft access.", ephemeral=True)
        return False

    async def require_administrator(self, interaction: discord.Interaction) -> bool:
        if self.is_administrator(interaction.user):
            return True
        await interaction.response.send_message(
            "Only server administrators can change Minecraft setup.",
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

    async def post_application_panel(self) -> discord.Message:
        channel = await self._configured_channel(self.settings.application_channel_id)
        if channel is None or not hasattr(channel, "send"):
            raise RuntimeError("The configured Minecraft application channel is unavailable")
        message_id = await self.data.get_config("application_panel_message_id")
        if message_id:
            try:
                message = await channel.fetch_message(int(message_id))
            except (discord.NotFound, discord.Forbidden, discord.HTTPException, AttributeError):
                message = None
            if message is not None:
                await message.edit(
                    content=None,
                    embed=None,
                    attachments=[brand_logo_file()],
                    view=application_panel(),
                )
                return message
        message = await channel.send(file=brand_logo_file(), view=application_panel())
        await self.data.set_config("application_panel_message_id", str(message.id))
        return message

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
        user = self.get_user(int(application.discord_user_id))
        if user is None:
            with suppress(discord.NotFound, discord.HTTPException):
                user = await self.fetch_user(int(application.discord_user_id))
        if user is not None:
            with suppress(discord.Forbidden, discord.HTTPException):
                await user.send(
                    embed=info_embed(
                        "Minecraft Account Verified",
                        "Your account was verified and the application was sent to the staff team for review.",
                        success=True,
                    )
                )
        remembered = self._application_interactions.pop(application.id, None)
        if remembered is not None and remembered[1] > time.monotonic():
            with suppress(discord.NotFound, discord.HTTPException):
                await remembered[0].edit_original_response(
                    embed=info_embed(
                        "Minecraft Account Verified",
                        "Your account was verified and the application was sent to the staff team for review.",
                        success=True,
                    )
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
            file=brand_logo_file(),
            embed=review_embed(application, user=user, member=member),
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
            attachments=[brand_logo_file()],
            embed=review_embed(application, user=user, member=member),
            view=ReviewView(disabled=application.status is not ApplicationStatus.PENDING_REVIEW),
            allowed_mentions=discord.AllowedMentions.none(),
        )

    async def finish_denial(self, application: MinecraftApplication) -> None:
        await self.update_review_message(application)
        if not application.applicant_reason:
            return
        user = self.get_user(int(application.discord_user_id))
        if user is None:
            with suppress(discord.NotFound, discord.HTTPException):
                user = await self.fetch_user(int(application.discord_user_id))
        if user is not None:
            with suppress(discord.Forbidden, discord.HTTPException):
                await user.send(
                    embed=info_embed(
                        "Minecraft Application Denied",
                        application.applicant_reason,
                        error=True,
                    )
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
            except Exception:
                logger.exception("Discord approval finalization failed for application %s", application.id)
        elif record.action is BridgeAction.REVOKE and application.status is ApplicationStatus.REVOKED:
            try:
                await self._finish_revocation(application)
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
                    embed=approval_embed(self.settings)
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
            await self.data.expire_pending(limit=100)
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
                await interaction.response.send_message("Please wait before requesting status again.", ephemeral=True)
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
            await interaction.edit_original_response(embed=embed)

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
                embed=info_embed(
                    f"Minecraft Lookup: {user}",
                    "**Linked Accounts**\n" + "\n".join(account_lines) + "\n\n**Applications**\n" + "\n".join(history_lines),
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
                await interaction.edit_original_response(content="That member has no approved Minecraft access to revoke.")
                return
            if self.bridge.connected:
                await self.bridge.dispatch_outbox()
            await interaction.edit_original_response(
                embed=info_embed(
                    "Revocation Queued",
                    f"Queued **{len(applications)}** account revocation(s). Discord access is removed only after Paper confirms each action.",
                    success=True,
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
                embed=info_embed("Retry Scheduled", f"Reset **{count}** failed bridge action(s).", success=bool(count))
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
                        embed=info_embed("Nothing to Cancel", str(exc), error=True)
                    )
                    return
                await interaction.edit_original_response(
                    embed=info_embed(
                        "Verification Cancelled",
                        "Your pending verification was cancelled. You can apply again now with the correct username.",
                        success=True,
                    )
                )
                return
            if not await self.require_moderator(interaction):
                return
            await interaction.response.defer(ephemeral=True, thinking=True)
            try:
                updated = await self.data.cancel_application(application, interaction.user.id)
            except InvalidTransition as exc:
                await interaction.edit_original_response(content=str(exc))
                return
            await self.update_review_message(updated)
            if self.bridge.connected:
                await self.bridge.dispatch_outbox()
            await interaction.edit_original_response(
                embed=info_embed("Application Cancelled", f"Application `#{updated.id}` was cancelled.", success=True)
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
