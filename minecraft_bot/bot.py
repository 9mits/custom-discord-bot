"""Dedicated Discord bot for Minecraft applications and access management."""

from __future__ import annotations

import asyncio
import logging
import time
from contextlib import suppress
from typing import Any, Awaitable, Optional

import discord
from discord import app_commands
from discord.ext import commands, tasks

from .audit import (
    SOURCE_SERVER,
    CommandAuditRecord,
    MinecraftCommandTree,
    OPTION_VALUE_LIMIT,
    build_command_log_embed as command_log_embed,
    deliver_server_event,
    install_component_audit,
    record_denial,
    server_event_risk,
    truncate as truncate_audit_value,
)
from . import clans
from .bridge import MinecraftBridgeServer
from .config import MinecraftConfig
from .data import ACTIVE_APPLICATION_STATUSES, MinecraftDataManager
from .models import ApplicationStatus, BridgeAction, Edition, InvalidTransition, MinecraftApplication, OutboxRecord
from .perks import (
    BOOSTER_ROLE_ID,
    LEVEL_ROLE_MILESTONES,
    RANK_ROLES,
    is_booster,
    profile_for_role_ids,
    rank_for_role_ids,
)
from .presentation import (
    BRAND_NAME,
    FOOTER_ICON_URL,
    MINECRAFT_HEAD_URL,
    application_apply_embed,
    application_guide_embed,
    application_guide_view,
    application_panel,
    application_welcome_embed,
    application_panel_files,
    application_dm_embed,
    application_log_embed,
    approval_embed,
    brand_icon_file,
    branded_edit,
    branded_send,
    denial_embed,
    decision_log_embed,
    info_embed,
    review_embed,
    player_activity_embed,
    verification_log_embed,
    application_card_files,
    live_status_embed,
)
from .settings import MinecraftSettings, SETTING_KEYS
from .ui import (
    AccountView,
    LinkEditionView,
    LiveApplicationView,
    MinecraftControlView,
    ReviewView,
)


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


class WipeConfirmationModal(discord.ui.Modal, title="Wipe All Minecraft Data"):
    confirmation = discord.ui.TextInput(
        label="Type WIPE to confirm",
        placeholder="This deletes every application and whitelist record.",
        min_length=1,
        max_length=10,
    )

    async def on_submit(self, interaction: discord.Interaction) -> None:
        bot = interaction.client
        if not bot.is_owner_member(interaction.user):
            await interaction.response.send_message(
                **branded_send(
                    info_embed(
                        "Owner Access Required",
                        "> Only the server owner can wipe Minecraft data.",
                        error=True,
                    )
                ),
                ephemeral=True,
            )
            return
        if str(self.confirmation).strip() != "WIPE":
            await interaction.response.send_message(
                **branded_send(
                    info_embed(
                        "Wipe Not Confirmed",
                        "> Nothing was deleted. Type exactly `WIPE` to confirm.",
                        error=True,
                    )
                ),
                ephemeral=True,
            )
            return
        await interaction.response.defer(ephemeral=True, thinking=True)
        counts = await bot.execute_data_wipe(interaction.user)
        summary = (
            f"> **{counts.get('minecraft_applications', 0)}** application(s), "
            f"**{counts.get('minecraft_accounts', 0)}** linked account(s), and every queue, "
            "audit, and log record were deleted. Settings were kept.\n\n"
            f"**Approved-member roles removed:** {counts.get('member_roles_removed', 0)}\n"
            "The Minecraft server clears its whitelist as soon as the queued removals reach it. "
            "Old review and log messages already posted in Discord stay where they are."
        )
        await interaction.edit_original_response(
            **branded_edit(info_embed("Minecraft Data Wiped", summary, success=True))
        )


class MinecraftAccessBot(commands.Bot):
    def __init__(self, config: MinecraftConfig) -> None:
        intents = discord.Intents.none()
        intents.guilds = True
        intents.members = True
        intents.messages = True
        intents.message_content = True
        # Without this the emoji cache never updates after startup, so freshly created
        # podium heads look missing and get created again on every refresh.
        intents.emojis_and_stickers = True
        super().__init__(
            command_prefix=commands.when_mentioned,
            intents=intents,
            help_command=None,
            allowed_mentions=discord.AllowedMentions.none(),
            tree_cls=MinecraftCommandTree,
        )
        self.config = config
        self.settings = MinecraftSettings.from_sources(config, {})
        self._settings_lock = asyncio.Lock()
        self._live_card_lock = asyncio.Lock()
        # Serializes first-time review posts so a verification handler and the
        # 30-second maintenance sweep cannot both post the same review embed.
        self._review_post_lock = asyncio.Lock()
        self.data = MinecraftDataManager(config.database_path)
        self.bridge = MinecraftBridgeServer(
            config,
            self.data,
            verification_handler=self.handle_bridge_verification,
            action_result_handler=self.handle_bridge_action_result,
            player_event_handler=self.handle_player_event,
            chat_message_handler=self.handle_minecraft_chat,
            connected_handler=self.handle_bridge_connected,
            server_event_handler=self.handle_server_event,
        )
        self.apply_rate_limit = RateLimiter(5)
        self.status_rate_limit = RateLimiter(10)
        # Minecraft UUID -> linked Discord id, refreshed with the leaderboard so the
        # dropdowns can render mentions without hitting the database again.
        self.leaderboard_links: dict[str, str] = {}
        # Minecraft UUID -> podium head emoji, shared with the dropdown boards.
        self.leaderboard_heads: dict[str, str] = {}
        self._application_messages: dict[int, tuple[discord.Message, float]] = {}
        self._background_tasks: set[asyncio.Task] = set()
        self._commands_synced = False
        self._application_panel_refreshed = False
        self._last_command_log_prune = 0.0
        self._chat_webhooks: dict[int, discord.Webhook] = {}
        self.tree.on_error = self.on_tree_error
        self._command_groups = self._build_command_groups()
        guild_object = discord.Object(id=config.guild_id)
        for command_group in self._command_groups:
            self.tree.add_command(command_group, guild=guild_object)

    async def setup_hook(self) -> None:
        install_component_audit(self)
        await self.data.open()
        stored_settings = await self.data.get_configs(SETTING_KEYS)
        self.settings = MinecraftSettings.from_sources(self.config, stored_settings)
        await self.data.set_configs(self.settings.persistent_values())
        self.add_view(ReviewView())
        self.add_view(application_panel())
        self.add_view(LiveApplicationView())
        # Without this the leaderboard dropdowns have no handler, so Discord reports
        # that the bot did not respond in time.
        from .information import InformationButton, LinkEditionButton, SectionButton
        from .leaderboard import BoardSelect
        from .ui import ContinueApplicationButton

        self.add_dynamic_items(
            BoardSelect,
            InformationButton,
            LinkEditionButton,
            SectionButton,
            ContinueApplicationButton,
        )
        await self.bridge.start()
        self.application_maintenance.start()
        self.leaderboard_refresh.start()

    async def close(self) -> None:
        self.application_maintenance.cancel()
        self.leaderboard_refresh.cancel()
        await self.bridge.close()
        if self._background_tasks:
            _done, pending = await asyncio.wait(self._background_tasks, timeout=5)
            for task in pending:
                task.cancel()
            if pending:
                await asyncio.gather(*pending, return_exceptions=True)
        await self.data.close()
        await super().close()

    def spawn_background_task(self, work: Awaitable[object], *, name: str) -> asyncio.Task:
        task = asyncio.create_task(work, name=name)
        tasks = getattr(self, "_background_tasks", None)
        if tasks is None:
            tasks = set()
            self._background_tasks = tasks
        tasks.add(task)

        def finished(completed: asyncio.Task) -> None:
            tasks.discard(completed)
            if completed.cancelled():
                return
            error = completed.exception()
            if error is not None:
                logger.error(
                    "Minecraft background task %s failed",
                    completed.get_name(),
                    exc_info=(type(error), error, error.__traceback__),
                )

        task.add_done_callback(finished)
        return task

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

    def is_owner_member(self, member: discord.Member | discord.User) -> bool:
        """The server owner, or a member holding a role literally named Owner."""
        guild = self.get_guild(self.config.guild_id)
        if guild is not None and int(member.id) == int(guild.owner_id or 0):
            return True
        return any(
            str(getattr(role, "name", "")).strip().casefold() == "owner"
            for role in getattr(member, "roles", ())
        )

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

    def remember_application_message(self, application_id: int, message: discord.Message) -> None:
        now = time.monotonic()
        remembered = getattr(self, "_application_messages", {})
        self._application_messages = {
            key: value for key, value in remembered.items() if value[1] > now
        }
        if len(self._application_messages) >= 1000:
            oldest = min(self._application_messages, key=lambda key: self._application_messages[key][1])
            self._application_messages.pop(oldest, None)
        self._application_messages[int(application_id)] = (message, now + 14 * 60)

    async def replace_application_card(
        self, application_id: int, message: discord.Message
    ) -> None:
        """Makes `message` the application's card and deletes the one it replaces.

        An applicant reaches the written form from a card that is already on screen,
        and answering it produces a fresh reply. Remembering the new one without
        removing the old left two cards for one application, both of which the next
        refresh edited — so the applicant watched a duplicate update alongside the
        real one.
        """
        previous = getattr(self, "_application_messages", {}).get(int(application_id))
        self.remember_application_message(application_id, message)
        if previous is None or previous[0].id == message.id:
            return
        # Ephemeral replies are only deletable through the interaction that made
        # them, and only while its token lives. A stale one is left as it is.
        with suppress(discord.NotFound, discord.Forbidden, discord.HTTPException):
            await previous[0].delete()

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
        getattr(self, "_application_messages", {}).pop(application.id, None)
        self.spawn_background_task(
            self._finish_cancellation(application),
            name=f"minecraft-cancel:{application.id}",
        )
        return application

    async def _finish_cancellation(self, application: MinecraftApplication) -> None:
        await self.log_application_decision(application)
        if self.bridge.connected:
            await self.bridge.dispatch_outbox()

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

    async def _send_configured_log(
        self,
        channel_id: int,
        embed: discord.Embed,
        *,
        queue_on_failure: bool = True,
        dedupe_key: Optional[str] = None,
    ) -> bool:
        if not channel_id:
            return True
        channel = await self._configured_channel(channel_id)
        if channel is None or not hasattr(channel, "send"):
            logger.warning("Configured Minecraft log channel %s is unavailable", channel_id)
            if queue_on_failure:
                await self.data.enqueue_delivery(
                    dedupe_key=dedupe_key or f"channel:{channel_id}:{time.time_ns()}",
                    kind="CHANNEL_EMBED",
                    target_id=channel_id,
                    payload=embed.to_dict(),
                )
            return False
        try:
            await channel.send(
                **branded_send(embed),
                allowed_mentions=discord.AllowedMentions.none(),
            )
        except (discord.Forbidden, discord.HTTPException):
            logger.exception("Could not send Minecraft log to channel %s", channel_id)
            if queue_on_failure:
                await self.data.enqueue_delivery(
                    dedupe_key=dedupe_key or f"channel:{channel_id}:{time.time_ns()}",
                    kind="CHANNEL_EMBED",
                    target_id=channel_id,
                    payload=embed.to_dict(),
                )
            return False
        return True

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

    async def finish_application_submission(self, application: MinecraftApplication) -> None:
        await self.log_application_submission(application)
        if self.bridge.connected:
            await self.bridge.dispatch_outbox()

    async def finish_approval_queue(self, application: MinecraftApplication) -> None:
        await self.update_review_message(application)
        await self.log_application_decision(application)
        if self.bridge.connected:
            await self.bridge.dispatch_outbox()

    async def finish_unlink(self, applications: list[MinecraftApplication]) -> None:
        for application in applications:
            if application.status is ApplicationStatus.CANCELLED:
                await self.update_review_message(application)
        if self.bridge.connected:
            await self.bridge.dispatch_outbox()

    async def update_live_card(
        self,
        application: MinecraftApplication,
        *,
        queue_on_failure: bool = True,
        create_if_missing: bool = True,
    ) -> bool:
        remembered = getattr(self, "_application_messages", {}).get(application.id)
        if remembered is not None:
            if remembered[1] <= time.monotonic():
                self._application_messages.pop(application.id, None)
            else:
                from .ui import application_card_view

                # Same answer the modal that drew this card reached. Deciding it
                # separately here is what made Get Help flash and vanish: the card
                # was drawn with it, then refreshed a moment later without it.
                card_view = application_card_view(application.status)
                try:
                    await remembered[0].edit(
                        **branded_edit(self._application_card_embed(application)),
                        attachments=application_card_files(application),
                        view=card_view,
                    )
                    if application.status not in {
                        ApplicationStatus.PENDING_VERIFICATION,
                        ApplicationStatus.PENDING_APPLICATION,
                        ApplicationStatus.PENDING_REVIEW,
                        ApplicationStatus.APPROVAL_QUEUED,
                    }:
                        self._application_messages.pop(application.id, None)
                except (discord.NotFound, discord.Forbidden, discord.HTTPException):
                    self._application_messages.pop(application.id, None)
        if not create_if_missing:
            return True
        lock = getattr(self, "_live_card_lock", None)
        if lock is None:
            lock = self._live_card_lock = asyncio.Lock()
        async with lock:
            current = await self.data.get_application(application.id)
            if current is not None:
                application = current
            # Verification is deliberately not DMed. The application card already
            # shows the same thing and updates in place, so the DM was a second copy
            # of it arriving seconds later. A decision still is: staff may act days
            # afterwards, when no card is on screen to update.
            notifications: list[str] = []
            if application.status in {ApplicationStatus.APPROVED, ApplicationStatus.DENIED}:
                notifications.append("decision")
            delivered = True
            for notification in notifications:
                delivered = await self._send_application_dm(
                    application,
                    notification,
                    queue_on_failure=queue_on_failure,
                ) and delivered
            return delivered

    def _application_card_embed(self, application: MinecraftApplication) -> discord.Embed:
        """The card's own embed for a status.

        Every state in flight renders through `live_status_embed`. It used to swap
        to a differently worded "Account Verified" embed once the form arrived, so
        the same application was described two ways depending on which code path
        drew it last. Only the two final decisions get their own embed, because
        they carry more than a status line.
        """
        if application.status is ApplicationStatus.APPROVED:
            return approval_embed(self.settings)
        if application.status is ApplicationStatus.DENIED:
            return denial_embed(application)
        return live_status_embed(application, self.settings)

    async def _send_application_dm(
        self,
        application: MinecraftApplication,
        notification: str,
        *,
        queue_on_failure: bool,
    ) -> bool:
        """DMs the applicant a staff decision.

        Only decisions. Verification used to DM as well, which duplicated the
        application card the applicant was already looking at.
        """
        if notification != "decision":
            return True
        if getattr(application, "decision_message_id", None):
            return True
        try:
            user = self.get_user(int(application.discord_user_id))
            if user is None:
                with suppress(discord.NotFound, discord.Forbidden, discord.HTTPException):
                    user = await self.fetch_user(int(application.discord_user_id))
            if user is None:
                raise RuntimeError("Discord user unavailable")
            icon = brand_icon_file()
            try:
                message = await user.send(
                    **branded_send(application_dm_embed(application, self.settings, notification)),
                    file=icon,
                )
            finally:
                icon.close()
            await self.data.set_decision_message(application.id, message.channel.id, message.id)
            return True
        except (discord.Forbidden, discord.HTTPException, RuntimeError) as exc:
            if queue_on_failure:
                await self.data.enqueue_delivery(
                    dedupe_key=f"application:{application.id}:{notification}-dm",
                    kind="USER_EMBED",
                    target_id=application.id,
                    payload={"application_id": application.id, "notification": notification},
                )
            logger.warning(
                "Minecraft %s DM deferred for application %s: %s",
                notification,
                application.id,
                type(exc).__name__,
            )
            return False

    async def _process_delivery_recovery(self) -> None:
        for delivery in await self.data.get_due_deliveries(limit=25):
            try:
                if delivery.kind == "CHANNEL_EMBED":
                    delivered = await self._send_configured_log(
                        int(delivery.target_id),
                        discord.Embed.from_dict(delivery.payload),
                        queue_on_failure=False,
                    )
                elif delivery.kind == "USER_EMBED":
                    application = await self.data.get_application(int(delivery.target_id))
                    notification = str(delivery.payload.get("notification") or "")
                    # "verification" is no longer delivered; anything left in the
                    # queue from before is completed rather than retried forever.
                    if notification != "decision":
                        delivered = True
                    elif application is not None:
                        delivered = await self._send_application_dm(
                            application,
                            notification,
                            queue_on_failure=False,
                        )
                    else:
                        delivered = True
                else:
                    application = await self.data.get_application(int(delivery.target_id))
                    delivered = bool(application) and await self.update_live_card(
                        application, queue_on_failure=False
                    )
                if delivered:
                    await self.data.complete_delivery(delivery.id)
                else:
                    await self.data.fail_delivery(delivery.id, "Destination unavailable", delivery.attempts)
            except Exception as exc:
                await self.data.fail_delivery(delivery.id, type(exc).__name__, delivery.attempts)

    async def build_link_edition_prompt(
        self, user_id: int | str
    ) -> tuple[discord.Embed, Optional[discord.ui.View]]:
        """The card offering to link whichever edition a member is missing.

        Reads their real state rather than assuming, so the same button answers
        correctly whether they hold one edition, both, or none. Linking only ever
        adds; unlinking stays a staff action.
        """
        accounts, applications = await asyncio.gather(
            self.data.list_accounts_for_user(user_id),
            self.data.list_applications_for_user(user_id, limit=1),
        )
        linked = {str(row["edition"]).upper() for row in accounts}
        panel_hint = (
            f"<#{self.settings.application_channel_id}>"
            if self.settings.application_channel_id
            else "the application channel"
        )
        if not linked:
            return (
                info_embed(
                    "No Linked Account Yet",
                    "> Linking a second edition is for members who already play here.\n\n"
                    f"Press **Apply** in {panel_hint} to get your first account set up.",
                ),
                None,
            )
        if len(linked) >= len(Edition):
            return (
                info_embed(
                    "Both Editions Linked",
                    "> You already have a Java and a Bedrock account linked.\n\n"
                    "Ask staff if one of them needs changing.",
                ),
                None,
            )
        latest = applications[0] if applications else None
        if latest is not None and latest.status in ACTIVE_APPLICATION_STATUSES:
            return (
                info_embed(
                    "Application Already Active",
                    "> You already have an application in progress, so a second one "
                    "cannot start yet.\n\n"
                    "Finish or cancel it first — `/minecraft account` shows where it is.",
                    error=True,
                ),
                None,
            )
        missing = next(edition for edition in Edition if edition.value not in linked)
        return (
            info_embed(
                f"Link Your {missing.value.title()} Account",
                f"> You are already accepted, so this only needs you to prove the "
                f"{missing.value.title()} account is yours.\n\n"
                "**1.** Press the button and enter the exact account name.\n"
                "**2.** Join the server once with it. You will be disconnected.\n"
                "**3.** That is it — no form, no waiting for staff.",
            ),
            LinkEditionView(user_id, missing),
        )

    async def build_account_embed(self, user_id: int | str) -> discord.Embed:
        accounts, applications = await asyncio.gather(
            self.data.list_accounts_for_user(user_id),
            self.data.list_applications_for_user(user_id, limit=1),
        )
        account_lines = [
            f"**{row['edition'].title()}** · `{row['current_username']}` · Last seen <t:{row['last_seen_at']}:R>"
            for row in accounts
        ] or ["No linked Minecraft accounts yet."]
        latest = applications[0] if applications else None
        status = latest.status.value.replace("_", " ").title() if latest else "No application"
        panel_hint = (
            f"<#{self.settings.application_channel_id}>"
            if self.settings.application_channel_id
            else "the application channel"
        )
        status_note = ""
        if latest is not None and latest.status is ApplicationStatus.EXPIRED:
            reason = (
                "nobody joined the Minecraft server in time to verify the account"
                if not latest.verified_at
                else "the written form was not finished in time"
            )
            status_note = (
                f"\n> **Application Expired** means it timed out: {reason}. "
                f"Nothing is lost — press **Apply** in {panel_hint} to start again."
            )
        elif latest is not None and latest.status is ApplicationStatus.PENDING_APPLICATION:
            status_note = (
                "\n> Your account is verified. Finish the written form by pressing "
                f"**Apply** in {panel_hint} — it continues where you left off."
            )
        return info_embed(
            "Your Minecraft Account",
            "> One private place for your application and linked accounts.\n\n"
            f"**Application**\n{status}{status_note}\n\n**Linked Accounts**\n" + "\n".join(account_lines),
        )

    async def build_whitelist_embed(self) -> discord.Embed:
        rows = await self.data.list_whitelisted()
        if not rows:
            return info_embed(
                "Whitelisted Players",
                "> Nobody is whitelisted yet. Approved applications appear here automatically.",
            )
        sections: list[str] = []
        for edition, label in (("JAVA", "Java"), ("BEDROCK", "Bedrock")):
            lines = [
                f"> `{row['username']}` — <@{row['discord_user_id']}>"
                for row in rows
                if row["edition"] == edition
            ]
            if lines:
                sections.append(f"**{label} · {len(lines)}**\n" + "\n".join(lines))
        description = (
            f"> **{len(rows)}** player(s) currently have whitelist access, each shown with "
            "their linked Discord account.\n\n" + "\n\n".join(sections)
        )
        if len(description) > 3900:
            description = description[:3900] + "\n> …and more. Ask staff for the full list."
        return info_embed("Whitelisted Players", description)

    async def publish_whitelist_snapshot(self) -> None:
        """Pushes the whitelist directory to Paper so /whitelisted works in game."""
        if not self.bridge.supports_whitelist_sync:
            return
        rows = await self.data.list_whitelisted(limit=500)
        guild = self.get_guild(self.config.guild_id)
        players = []
        for row in rows:
            try:
                member_id = int(row["discord_user_id"])
            except (TypeError, ValueError):
                member_id = 0
            user = guild.get_member(member_id) if guild is not None and member_id else None
            user = user or (self.get_user(member_id) if member_id else None)
            players.append(
                {
                    "username": row["username"],
                    "edition": row["edition"],
                    "minecraft_uuid": row["minecraft_uuid"],
                    "discord_username": getattr(user, "name", ""),
                }
            )
        await self.bridge.send_whitelist_snapshot(players)

    async def handle_bridge_connected(self) -> None:
        try:
            await self.publish_whitelist_snapshot()
        except Exception:
            logger.exception("Could not publish the whitelist snapshot on connect")

    async def execute_data_wipe(self, actor: discord.Member | discord.User) -> dict[str, int]:
        """Clears every application and whitelist record while keeping settings.

        The queued REVOKE and REMOVE_PENDING actions written by the data layer make
        Paper drop its whitelist entries too; the empty pending sync and whitelist
        snapshot bring its caches in line immediately when it is connected.
        """
        counts = await self.data.wipe_all_data(actor.id)
        self._application_messages = {}
        self.leaderboard_links = {}
        guild = self.get_guild(self.config.guild_id)
        role = guild.get_role(self.settings.member_role_id) if guild is not None else None
        removed_roles = 0
        if role is not None:
            for member in list(role.members):
                with suppress(discord.Forbidden, discord.HTTPException):
                    await member.remove_roles(role, reason="Minecraft data wipe")
                    removed_roles += 1
        counts["member_roles_removed"] = removed_roles
        if self.bridge.connected:
            with suppress(ConnectionError):
                await self.bridge.send_full_pending_sync()
            await self.bridge.dispatch_outbox()
            await self.publish_whitelist_snapshot()
        return counts

    async def build_diagnostics_embed(self, guild: Optional[discord.Guild]) -> discord.Embed:
        from .setup import configuration_findings

        findings = configuration_findings(self, guild)
        outbox, deliveries = await asyncio.gather(
            self.data.outbox_counts(), self.data.delivery_counts()
        )
        issues = []
        if not self.bridge.connected:
            issues.append("The Minecraft server connection is offline. Queued actions will retry automatically.")
        elif self.bridge.last_heartbeat_at and time.time() - self.bridge.last_heartbeat_at > 45:
            issues.append("The Minecraft server connection is delayed. Recovery is already in progress.")
        issues.extend(f"{finding.setting}: {finding.detail}" for finding in findings)
        failed = outbox.get("FAILED", 0)
        if failed:
            issues.append(f"{failed} Minecraft action(s) need a manual retry.")
        recovering = sum(deliveries.values())
        if recovering:
            issues.append(f"{recovering} Discord notification(s) are waiting for automatic delivery.")
        if not issues:
            issues.append("Everything is connected, configured, and responding normally.")
        return info_embed(
            "Minecraft Diagnostics",
            "> A plain-language health check. Private IDs, errors, and secrets are intentionally hidden.\n\n"
            + "\n".join(f"- {issue}" for issue in issues),
            success=len(issues) == 1 and issues[0].startswith("Everything"),
        )

    async def dispatch_outbox_if_connected(self) -> None:
        if self.bridge.connected:
            await self.bridge.dispatch_outbox()

    async def finish_staff_cancellation(self, application: MinecraftApplication) -> None:
        await self.update_review_message(application)
        await self.log_application_decision(application)
        await self.update_live_card(application)
        if self.bridge.connected:
            await self.bridge.dispatch_outbox()

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
        record_seen = getattr(self.data, "record_player_seen", None)
        if record_seen is not None:
            discord_user_id = await record_seen(edition, minecraft_uuid, current_username, xuid)
        else:
            discord_user_id = await self.data.get_account_owner(edition, minecraft_uuid)
        if joined:
            await self.sync_player_profile(minecraft_uuid, discord_user_id)
        self.spawn_background_task(
            self._send_configured_log(
                self.settings.player_log_channel_id,
                player_activity_embed(
                    joined=joined,
                    username=current_username,
                    minecraft_uuid=minecraft_uuid,
                    edition=edition,
                    xuid=xuid,
                    discord_user_id=discord_user_id,
                ),
            ),
            name="minecraft-player-log",
        )

    async def handle_server_event(
        self,
        *,
        event: str,
        category: str,
        actor_uuid: str,
        actor_name: str,
        summary: str,
        details: dict[str, Any],
        occurred_at: int,
        event_idempotency_key: str,
    ) -> None:
        """Writes something a player did in game to the Discord activity log.

        The counterpart to the interaction auditing in `audit.py`: that covers what
        staff and members do through Discord, this covers the same kinds of action —
        founding a clan, donating to one, buying an upgrade — taken on the server,
        which previously left no record anywhere.
        """
        claimed = await self.data.claim_bridge_event(event_idempotency_key, "SERVER_EVENT")
        if not claimed or not event:
            return
        owners = await self.data.owners_for_uuids([actor_uuid] if actor_uuid else [])
        try:
            discord_user_id = int(owners.get(str(actor_uuid), 0) or 0)
        except (TypeError, ValueError):
            discord_user_id = 0
        record = CommandAuditRecord(
            source=SOURCE_SERVER,
            command=str(event),
            user_id=discord_user_id,
            user_label=str(actor_name or actor_uuid or "Unknown player"),
            options=tuple(
                (str(name), truncate_audit_value(value, OPTION_VALUE_LIMIT))
                for name, value in details.items()
            ),
            risk=server_event_risk(event),
            correlation_id=str(category or "server"),
            created_at=int(occurred_at),
        )
        await deliver_server_event(
            self,
            record,
            minecraft_uuid=str(actor_uuid or ""),
            minecraft_username=str(actor_name or ""),
            summary=str(summary or ""),
        )

    async def on_message(self, message: discord.Message) -> None:
        if (
            message.guild is not None
            and message.guild.id == self.config.guild_id
            and message.channel.id == self.settings.chat_channel_id
            and not message.author.bot
            and message.webhook_id is None
        ):
            accounts = await self.data.list_accounts_for_user(message.author.id)
            minecraft_uuid = str(accounts[0]["minecraft_uuid"]) if accounts else None
            minecraft_username = str(accounts[0]["current_username"]) if accounts else None
            content = str(message.content or "")[:2000]
            attachment_url = message.jump_url if message.attachments else None
            await self.bridge.send_discord_chat(
                discord_user_id=message.author.id,
                discord_username=message.author.name,
                minecraft_uuid=minecraft_uuid,
                minecraft_username=minecraft_username,
                message=content,
                attachment_url=attachment_url,
                attachment_count=len(message.attachments),
            )
        await self.process_commands(message)

    async def handle_minecraft_chat(
        self,
        *,
        minecraft_uuid: str,
        current_username: str,
        edition: str,
        message: str,
        event_idempotency_key: str,
    ) -> None:
        claimed = await self.data.claim_bridge_event(event_idempotency_key, "MINECRAFT_CHAT")
        if not claimed or not self.settings.chat_channel_id:
            return
        channel = await self._configured_channel(self.settings.chat_channel_id)
        if not isinstance(channel, discord.TextChannel):
            logger.warning("Configured Minecraft chat channel %s is unavailable", self.settings.chat_channel_id)
            return
        owner_id = await self.data.get_account_owner(edition, minecraft_uuid)
        linked_user: Optional[discord.User | discord.Member] = None
        if owner_id is not None:
            try:
                user_id = int(owner_id)
            except (TypeError, ValueError):
                user_id = 0
            if user_id:
                guild = self.get_guild(self.config.guild_id)
                linked_user = guild.get_member(user_id) if guild is not None else None
                linked_user = linked_user or self.get_user(user_id)
                if linked_user is None:
                    with suppress(discord.NotFound, discord.Forbidden, discord.HTTPException):
                        linked_user = await self.fetch_user(user_id)
        try:
            webhook = await self._chat_webhook(channel)
            username = f"{current_username} • Minecraft"
            avatar_url = MINECRAFT_HEAD_URL.format(identifier=minecraft_uuid)
            if linked_user is not None:
                username = f"{current_username} • @{linked_user.name}"
                avatar_url = str(linked_user.display_avatar.url)
            rendered_message = discord.utils.escape_markdown(
                discord.utils.escape_mentions(str(message)[:2000]),
                as_needed=True,
            )
            embed = discord.Embed(
                description=rendered_message or "*Empty message*",
                colour=discord.Colour.from_rgb(255, 153, 0),
            )
            embed.set_author(
                name=f"Minecraft · {current_username}",
                icon_url=MINECRAFT_HEAD_URL.format(identifier=minecraft_uuid),
            )
            embed.set_footer(text=BRAND_NAME, icon_url=FOOTER_ICON_URL)
            await webhook.send(
                username=username[:80],
                avatar_url=avatar_url,
                embed=embed,
                allowed_mentions=discord.AllowedMentions.none(),
                wait=True,
            )
        except (discord.Forbidden, discord.NotFound, discord.HTTPException):
            logger.exception("Could not relay Minecraft chat into Discord channel %s", channel.id)

    async def _chat_webhook(self, channel: discord.TextChannel) -> discord.Webhook:
        cached = self._chat_webhooks.get(channel.id)
        if cached is not None:
            return cached
        for webhook in await channel.webhooks():
            if (
                webhook.name == "Mysterious SMP X Chat"
                and webhook.user is not None
                and self.user is not None
                and webhook.user.id == self.user.id
                and webhook.token
            ):
                self._chat_webhooks[channel.id] = webhook
                return webhook
        webhook = await channel.create_webhook(
            name="Mysterious SMP X Chat",
            reason="Two-way Minecraft and Discord chat relay",
        )
        self._chat_webhooks[channel.id] = webhook
        return webhook

    async def sync_player_profile(
        self,
        minecraft_uuid: str,
        discord_user_id: Optional[int | str],
        *,
        member: Optional[discord.Member] = None,
    ) -> bool:
        if member is None and discord_user_id is not None:
            guild = self.get_guild(self.config.guild_id)
            if guild is not None:
                try:
                    member_id = int(discord_user_id)
                except (TypeError, ValueError):
                    member_id = 0
                if member_id:
                    member = guild.get_member(member_id)
                    if member is None:
                        with suppress(discord.NotFound, discord.Forbidden, discord.HTTPException):
                            member = await guild.fetch_member(member_id)
        linked_user: Optional[discord.User | discord.Member] = member
        if linked_user is None and discord_user_id is not None and hasattr(self, "_connection"):
            try:
                linked_user = self.get_user(int(discord_user_id))
            except (TypeError, ValueError):
                linked_user = None
        # Highest Discord role first, so role hierarchy decides which rank wins.
        member_roles = sorted(
            getattr(member, "roles", ()),
            key=lambda role: getattr(role, "position", 0),
            reverse=True,
        )
        member_role_ids = [role.id for role in member_roles]
        profile = profile_for_role_ids(member_role_ids)
        rank = rank_for_role_ids(member_role_ids)
        # Player-list ordering mirrors Discord hierarchy, so send the winning
        # role's position as the sort weight.
        rank_weight = 0
        if rank is not None:
            rank_weight = next(
                (
                    getattr(role, "position", 0)
                    for role in member_roles
                    if getattr(role, "id", None) in {r[0] for r in RANK_ROLES}
                ),
                0,
            )
        return await self.bridge.send_player_profile(
            minecraft_uuid=minecraft_uuid,
            level=profile.level,
            extra_hearts=profile.extra_hearts,
            elite=profile.elite,
            discord_username=getattr(linked_user, "name", ""),
            rank_group=rank.group if rank else "",
            rank_label=rank.label if rank else "",
            rank_colour=rank.colour if rank else 0,
            rank_weight=rank_weight,
            booster=is_booster(member_role_ids),
            # Without a resolved member we do not know their Discord roles. Saying
            # "no rank" would wipe their LuckPerms groups, including any set by hand.
            rank_known=member is not None,
            # No linked account at all is knowledge: the plugin should forget the name
            # it cached, which is what leaves a wiped member's Discord name showing in
            # game. A link we know about but could not resolve to a user is not — the
            # empty name there would be a failed lookup, not an absent account.
            link_known=discord_user_id is None or linked_user is not None,
        )

    async def on_member_update(self, before: discord.Member, after: discord.Member) -> None:
        synced_ids = {role_id for role_id, _level in LEVEL_ROLE_MILESTONES}
        synced_ids.update(role_id for role_id, _group, _label, _colour in RANK_ROLES)
        synced_ids.add(BOOSTER_ROLE_ID)
        before_roles = {role.id for role in before.roles} & synced_ids
        after_roles = {role.id for role in after.roles} & synced_ids
        if before_roles == after_roles or not self.bridge.supports_profile_sync:
            return
        for account in await self.data.list_accounts_for_user(after.id):
            await self.sync_player_profile(
                str(account["minecraft_uuid"]),
                after.id,
                member=after,
            )

    async def on_user_update(self, before: discord.User, after: discord.User) -> None:
        if before.name == after.name or not self.bridge.supports_profile_sync:
            return
        for account in await self.data.list_accounts_for_user(after.id):
            await self.sync_player_profile(str(account["minecraft_uuid"]), after.id)

    async def post_application_panel(self) -> discord.Message:
        """Publishes the application channel as three messages.

        Split because one message carries one set of buttons, and the reading and
        the Apply button want to be apart: what the server is, what to know before
        deciding, and then the single button that starts an application. The Apply
        message keeps the `application_panel_message_id` key, because that is the
        one `_validate_application_panel` checks a press against.
        """
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

        # (config key, embed, view, files) in the order they are posted.
        parts = (
            (
                "application_welcome_message_id",
                application_welcome_embed(),
                None,
                application_panel_files(),
            ),
            ("application_guide_message_id", application_guide_embed(), application_guide_view(), []),
            ("application_panel_message_id", application_apply_embed(), application_panel(), []),
        )
        existing = {key: await fetch_saved_message(key) for key, *_ in parts}
        banner = await fetch_saved_message("application_banner_message_id")

        # Components V2 messages cannot be edited back into plain embeds, and a
        # missing part would leave the channel half-published, so either every
        # message is edited in place or the whole panel is reposted.
        reusable = all(
            message is not None
            and not getattr(getattr(message, "flags", None), "components_v2", False)
            for message in existing.values()
        )
        if reusable and banner is None:
            for key, embed, view, files in parts:
                await existing[key].edit(
                    content=None, embeds=[embed], attachments=files, view=view
                )
            return existing["application_panel_message_id"]

        for old_message in (banner, *existing.values()):
            if old_message is not None:
                with suppress(discord.NotFound, discord.Forbidden, discord.HTTPException):
                    await old_message.delete()

        posted: dict[str, discord.Message] = {}
        for key, embed, view, files in parts:
            posted[key] = await channel.send(embed=embed, files=files, view=view)
        await self.data.set_configs(
            {
                "application_banner_message_id": "",
                **{key: str(message.id) for key, message in posted.items()},
            }
        )
        return posted["application_panel_message_id"]

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
        self.spawn_background_task(
            self._publish_verification(application, changed=changed),
            name=f"minecraft-verification:{application.id}",
        )

    async def _publish_verification(
        self,
        application: MinecraftApplication,
        *,
        changed: bool,
    ) -> None:
        if not changed:
            # Reviews exist only once the written form is in; a duplicate
            # verification event must not create one early.
            if (
                application.status is ApplicationStatus.PENDING_REVIEW
                and application.review_message_id is None
            ):
                with suppress(Exception):
                    await self.post_or_update_review(application)
            # A replayed event changes nothing, so this only redraws the card the
            # applicant already has. It used to be gated on the verification DM
            # having been sent; there is no such DM now, and the gate would always
            # have been open.
            await self.update_live_card(application, create_if_missing=False)
            return
        if application.status is ApplicationStatus.PENDING_REVIEW:
            try:
                await self.post_or_update_review(application)
            except Exception:
                logger.exception("Could not publish review for Minecraft application %s", application.id)
        await self._send_configured_log(
            self.settings.verification_log_channel_id,
            verification_log_embed(application),
        )
        await self.update_live_card(application)

    async def finish_answers_submission(self, application: MinecraftApplication) -> None:
        """The written form arrived: the application is now ready for staff."""
        try:
            await self.post_or_update_review(application)
        except Exception:
            logger.exception("Could not publish review for Minecraft application %s", application.id)
        await self.log_application_submission(application)
        await self.update_live_card(application, create_if_missing=False)
        if self.bridge.connected:
            await self.bridge.dispatch_outbox()

    async def post_or_update_review(self, application: MinecraftApplication) -> None:
        if application.review_message_id:
            await self.update_review_message(application)
            return
        # Single-flight: the verification handler, the answers handler, and the
        # maintenance sweep can all decide to post the first review at the same
        # time. Re-reading inside the lock turns the losers into edits.
        async with self._review_post_lock:
            current = await self.data.get_application(application.id)
            if current is not None:
                application = current
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
        await self.update_live_card(application)

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
        self.spawn_background_task(
            self._finalize_bridge_action(record, application),
            name=f"minecraft-action-result:{record.id}",
        )

    async def _finalize_bridge_action(
        self,
        record: OutboxRecord,
        application: MinecraftApplication,
    ) -> None:
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
        await self.update_live_card(application)
        if record.action in {BridgeAction.APPROVE, BridgeAction.REVOKE}:
            with suppress(Exception):
                await self.publish_whitelist_snapshot()

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

    @tasks.loop(seconds=300)
    async def leaderboard_refresh(self) -> None:
        """Keeps the permanent leaderboard message current by editing it in place."""
        try:
            await self._refresh_leaderboard_message()
        except Exception:
            logger.exception("Leaderboard refresh failed; retrying on the next interval")
        try:
            # Same cadence keeps Paper's /whitelisted directory fresh even when a
            # push after an approval was missed.
            await self.publish_whitelist_snapshot()
        except Exception:
            logger.exception("Whitelist snapshot refresh failed; retrying on the next interval")

    @leaderboard_refresh.before_loop
    async def _before_leaderboard_refresh(self) -> None:
        await self.wait_until_ready()

    async def run_clan_action(
        self, discord_user_id: int, action: str, argument: str
    ) -> discord.Embed:
        """Requests a clan action for the caller's linked account.

        The offered actions are filtered by the server's own report of their role, and
        the server checks again before doing anything — a stale menu grants nothing.
        """
        from .capabilities import capabilities_for
        from .presentation import info_embed

        accounts = await self.data.list_accounts_for_user(discord_user_id)
        if not accounts:
            return info_embed(
                "No Linked Account",
                "> Link a Minecraft account before using clan commands.",
                error=True,
            )
        if not self.bridge.connected:
            return info_embed(
                "Server Offline",
                "> The Minecraft server is not reachable right now. Try again shortly.",
                error=True,
            )
        needs_argument = action in {"kick", "promote", "demote", "transfer", "rename", "color"}
        if needs_argument and not argument:
            return info_embed(
                "Missing Detail",
                f"> `{action}` needs a player or value. Add one and run it again.",
                error=True,
            )

        snapshot = getattr(self.bridge, "latest_capabilities", {}) or {}
        for account in accounts:
            uuid = str(account["minecraft_uuid"])
            if not capabilities_for(snapshot, uuid).may(action):
                continue
            success, message = await self.bridge.run_clan_action(
                actor_uuid=uuid, action=action, argument=argument
            )
            detail = message or (
                "The Minecraft server accepted the request."
                if success
                else "The Minecraft server declined the request without details."
            )
            return info_embed(
                "Clan Updated" if success else "Clan Action Declined",
                f"> {detail}",
                success=success,
                error=not success,
            )
        return info_embed(
            "Not Allowed",
            "> Your clan role does not allow that. Use `/minecraft clan view` "
            "to see what you can do.",
            error=True,
        )

    async def run_staff_action(
        self,
        discord_user_id: int,
        tool: str,
        *,
        target: str,
        reason: str,
        duration: str,
    ) -> discord.Embed:
        """Requests a staff tool for the caller's linked account.

        Offered tools are filtered by the server's own report of what the linked
        account's LuckPerms permissions allow — the Discord moderator role that gated
        the command is only the first of two checks. Paper checks again, through
        LuckPerms directly, before running anything.
        """
        from .capabilities import REMOTE_STAFF_TOOLS, capabilities_for
        from .presentation import info_embed

        accounts = await self.data.list_accounts_for_user(discord_user_id)
        if not accounts:
            return info_embed(
                "No Linked Account",
                "> Link a Minecraft account before using moderation tools.",
                error=True,
            )
        if not self.bridge.connected:
            return info_embed(
                "Server Offline",
                "> The Minecraft server is not reachable right now. Try again shortly.",
                error=True,
            )
        needs = REMOTE_STAFF_TOOLS.get(tool)
        if needs is None:
            return info_embed(
                "Not Available",
                "> That tool cannot be run from Discord. Use `/mcstaff tools` to see "
                "what you hold, and run it in game instead.",
                error=True,
            )
        if needs["needs_target"] and not target:
            return info_embed(
                "Missing Detail", "> That tool needs a player. Add one and try again.", error=True
            )
        if needs["needs_duration"] and not duration:
            return info_embed(
                "Missing Detail",
                "> That tool needs a duration, such as `10m` or `1d`.",
                error=True,
            )

        snapshot = getattr(self.bridge, "latest_capabilities", {}) or {}
        for account in accounts:
            uuid = str(account["minecraft_uuid"])
            if not capabilities_for(snapshot, uuid).may_run_remotely(tool):
                continue
            success, message = await self.bridge.run_staff_action(
                actor_uuid=uuid,
                tool=tool,
                target=target,
                reason=reason,
                duration=duration,
            )
            detail = message or (
                "The Minecraft server accepted the request."
                if success
                else "The Minecraft server declined the request without details."
            )
            return info_embed(
                "Action Completed" if success else "Action Declined",
                f"> {detail}",
                success=success,
                error=not success,
            )
        return info_embed(
            "Not Allowed",
            "> Your account does not hold that permission. Use `/mcstaff tools` to "
            "see what you can run.",
            error=True,
        )

    async def build_capability_embed(self, discord_user_id: int, *, staff: bool) -> discord.Embed:
        """Shows only what the server says this player may do.

        Nothing here is a substitute for the server's own check — Paper re-checks the
        permission when a command actually runs. This decides what is worth offering.
        """
        from .capabilities import capabilities_for
        from .presentation import info_embed

        accounts = await self.data.list_accounts_for_user(discord_user_id)
        if not accounts:
            return info_embed(
                "No Linked Account",
                "> Link a Minecraft account first. Apply from the application panel, "
                "then check `/minecraft account`.",
                error=True,
            )
        snapshot = getattr(self.bridge, "latest_capabilities", {}) or {}
        if not snapshot:
            return info_embed(
                "Server Not Reachable",
                "> The Minecraft server has not reported in yet. Try again shortly.",
                error=True,
            )

        lines: list[str] = []
        for account in accounts:
            caps = capabilities_for(snapshot, str(account["minecraft_uuid"]))
            if staff:
                tools = caps.available_staff_tools()
                if not tools:
                    continue
                lines.append("**In game you can:**")
                lines.extend(f"> {label}" for _key, label in tools)
            else:
                if not caps.in_clan:
                    continue
                lines.append(
                    f"**{clans.tag(caps.clan, caps.clan_level)}** — you are the "
                    f"{caps.clan_role} of {caps.clan_members} member(s)."
                )
                lines.append("")
                lines.append(f"**{clans.describe(caps.clan_level)}**")
                lines.append(f"> Balance **{caps.clan_balance:,}**")
                slots = caps.clan_member_slots or clans.STARTING_MEMBER_SLOTS
                lines.append(f"> Roster **{caps.clan_members}/{slots}**")
                held = clans.perks_for(caps.clan_level)
                if held.is_none():
                    lines.append("> No clan perks yet")
                else:
                    lines.extend(f"> {line}" for line in held.described())
                lines.append("")
                lines.append("**Next**")
                following = clans.next_level(caps.clan_level)
                if following is not None:
                    lines.append(f"> Level {following} — {clans.described_cost(following)}")
                roster = clans.next_member_tier(slots)
                if roster is not None:
                    lines.append(
                        f"> {roster[0]} members — {clans.described_member_cost(roster)}"
                    )
                if following is None and roster is None:
                    lines.append("> Everything is bought.")
                else:
                    lines.append("> Give items with `/clans donate` in game.")
                actions = caps.available_clan_actions()
                if actions:
                    lines.append("")
                    lines.append("**Your role lets you:**")
                    lines.extend(f"> `/clans {action}` — {label}" for action, label in actions)

        if not lines:
            if staff:
                return info_embed(
                    "No Staff Tools",
                    "> Your account holds no Minecraft staff permissions.\n"
                    "> Staff ranks come from your Discord role, so this updates on its own.",
                )
            return info_embed(
                "No Clan",
                "> You are not in a clan yet.\n"
                "> Join one with an invite, or start your own with `/clans create <name>` "
                "in game.",
            )
        return info_embed(
            "Your Staff Tools" if staff else "Your Clan",
            "\n".join(lines),
        )

    async def publish_information_panel(self, channel: discord.TextChannel) -> Optional[str]:
        """Posts or updates the guide. Returns a reason when it could not."""
        from .information import CONFIG_CHANNEL, CONFIG_MESSAGE, message_payload

        payload = message_payload(self.settings)
        await self.data.set_config(CONFIG_CHANNEL, channel.id)
        message_id = await self.data.get_config(CONFIG_MESSAGE)
        if message_id:
            try:
                message = await channel.fetch_message(int(message_id))
                await message.edit(
                    embed=payload["embed"],
                    attachments=payload["attachments"],
                    view=payload["view"],
                )
                return None
            except discord.NotFound:
                pass  # deleted; fall through and repost
            except discord.HTTPException:
                return "Discord refused the edit. Try again in a moment."
        try:
            posted = await channel.send(
                embed=payload["embed"],
                files=payload["attachments"],
                view=payload["view"],
            )
        except discord.HTTPException:
            logger.exception("Could not post the information panel")
            return "I could not post there. Check my permissions in that channel."
        await self.data.set_config(CONFIG_MESSAGE, posted.id)
        return None

    async def _resolve_leaderboard_links(self, snapshot: dict) -> dict[str, str]:
        """Maps every ranked Minecraft UUID to its linked Discord id, once per refresh."""
        uuids: set[str] = set()
        for board in (snapshot.get("individual") or {}).values():
            for row in board if isinstance(board, list) else []:
                uuid = str(row.get("minecraft_uuid") or "")
                if uuid:
                    uuids.add(uuid)
        return await self.data.owners_for_uuids(uuids)

    async def _refresh_leaderboard_message(self) -> Optional[str]:
        """Posts or edits the permanent leaderboard. Returns a reason when it could not."""
        from .leaderboard import CONFIG_CHANNEL, CONFIG_MESSAGE, HeadEmojiStore, message_payload

        # Posted even before Paper has pushed anything: the board renders its own
        # "no standings yet" state, which is far better than silently doing nothing.
        snapshot = getattr(self.bridge, "latest_leaderboard", {}) or {}
        channel_id = await self.data.get_config(CONFIG_CHANNEL)
        if not channel_id:
            return "No leaderboard channel is configured."
        message_id = await self.data.get_config(CONFIG_MESSAGE)
        if not snapshot and message_id:
            # Standings live in memory, so a bot restart empties them until Paper's
            # next push. Leave the last good board up rather than blanking it.
            return None
        channel = self.get_channel(int(channel_id))
        if not isinstance(channel, discord.TextChannel):
            return "The configured leaderboard channel is not reachable."

        heads: dict[str, str] = {}
        if channel.guild is not None:
            heads = await HeadEmojiStore(self).sync(channel.guild, snapshot)
        self.leaderboard_heads = heads
        self.leaderboard_links = await self._resolve_leaderboard_links(snapshot)
        payload = message_payload(snapshot, heads, self.leaderboard_links)

        if message_id:
            try:
                message = await channel.fetch_message(int(message_id))
                await message.edit(
                    embeds=payload["embeds"],
                    attachments=payload["attachments"],
                    view=payload["view"],
                )
                return None
            except discord.NotFound:
                pass  # deleted by someone; fall through and repost
            except discord.HTTPException:
                return "Discord refused the edit; retrying on the next refresh."
        try:
            posted = await channel.send(
                embeds=payload["embeds"],
                files=payload["attachments"],
                view=payload["view"],
            )
        except discord.HTTPException:
            logger.exception("Could not post the leaderboard message")
            return "I could not post there. Check my permissions in that channel."
        await self.data.set_config(CONFIG_MESSAGE, posted.id)
        return None

    @tasks.loop(seconds=30)
    async def application_maintenance(self) -> None:
        try:
            expired = await self.data.expire_pending(limit=100)
            for application in expired:
                await self.log_application_decision(application)
            if self.bridge.connected:
                await self.bridge.dispatch_outbox()
            await self._process_delivery_recovery()
            await self._restore_missing_reviews()
            await self._prune_command_log()
        except asyncio.CancelledError:
            raise
        except Exception:
            logger.exception("Minecraft application maintenance failed")

    async def _prune_command_log(self) -> None:
        """Age out the command trail roughly hourly rather than every 30s tick."""
        now = time.monotonic()
        if now - self._last_command_log_prune < 3600:
            return
        self._last_command_log_prune = now
        await self.data.prune_command_log()

    @application_maintenance.before_loop
    async def before_application_maintenance(self) -> None:
        await self.wait_until_ready()

    async def _restore_missing_reviews(self) -> None:
        for application in await self.data.list_missing_review_messages(limit=20):
            await self.post_or_update_review(application)

    def control_view(self, interaction: discord.Interaction) -> MinecraftControlView:
        return MinecraftControlView(
            self,
            interaction.user.id,
            include_setup=self.is_administrator(interaction.user),
        )

    async def build_control_overview(self, guild: Optional[discord.Guild]) -> discord.Embed:
        outbox, applications, latency, deliveries = await asyncio.gather(
            self.data.outbox_counts(),
            self.data.application_status_counts(),
            self.data.response_time_metrics(),
            self.data.delivery_counts(),
        )
        from .setup import configuration_findings

        findings = configuration_findings(self, guild)
        heartbeat = (
            f"<t:{int(self.bridge.last_heartbeat_at)}:R>"
            if self.bridge.last_heartbeat_at is not None
            else "Never"
        )
        total = sum(applications.values())
        embed = info_embed(
            "Minecraft Control",
            "> Live access, application, and bridge status. Use the menu below for read-only tools; "
            "use `/mcstaff lookup` or the focused access commands for member-specific actions.",
        )
        embed.add_field(
            name="Service",
            value=(
                f"**Bridge:** {'Connected' if self.bridge.connected else 'Offline'}\n"
                f"**Setup:** {'Ready' if not findings else 'Needs attention'}\n"
                f"**Heartbeat:** {heartbeat}"
            ),
            inline=True,
        )
        embed.add_field(
            name="Applications",
            value=(
                f"**Total:** {total}\n"
                f"**Verification:** {applications.get(ApplicationStatus.PENDING_VERIFICATION.value, 0)}\n"
                f"**Review:** {applications.get(ApplicationStatus.PENDING_REVIEW.value, 0)}"
            ),
            inline=True,
        )
        embed.add_field(
            name="Action Queue",
            value=(
                f"**Queued:** {outbox.get('PENDING', 0)}\n"
                f"**Sent:** {outbox.get('SENT', 0)}\n"
                f"**Failed:** {outbox.get('FAILED', 0)}"
            ),
            inline=True,
        )
        embed.add_field(
            name="Response Time · 24h",
            value=(
                f"**Median:** {latency['median_ms']} ms\n"
                f"**95th percentile:** {latency['p95_ms']} ms\n"
                f"**Samples:** {latency['samples']}"
            ),
            inline=True,
        )
        embed.add_field(
            name="Automatic Recovery",
            value=f"**Waiting deliveries:** {sum(deliveries.values())}\n**State:** {'Recovering' if deliveries else 'Clear'}",
            inline=True,
        )
        if findings:
            embed.add_field(
                name="Needs Attention",
                value="\n".join(
                    f"**{finding.setting}:** {finding.detail}" for finding in findings[:4]
                ),
                inline=False,
            )
        return embed

    async def build_member_lookup_embed(
        self,
        user: discord.User | discord.Member,
    ) -> discord.Embed:
        accounts, history = await asyncio.gather(
            self.data.list_accounts_for_user(user.id),
            self.data.list_applications_for_user(user.id, limit=25),
        )
        account_lines = [
            f"**{row['edition'].title()}** · `{row['current_username']}` · `{row['minecraft_uuid']}`"
            for row in accounts
        ] or ["No linked accounts."]
        history_lines = [
            f"`#{item.id}` · {item.edition.value.title()} · "
            f"{item.status.value.replace('_', ' ').title()} · <t:{item.created_at}:d>"
            for item in history
        ] or ["No applications."]
        return info_embed(
            f"Minecraft Lookup: {user}",
            f"> {user.mention} · `{user.id}`\n\n"
            "**Linked Accounts**\n"
            + "\n".join(account_lines)
            + "\n\n**Application History**\n"
            + "\n".join(history_lines),
        )

    async def build_username_lookup_embed(self, username: str) -> discord.Embed:
        query = " ".join(str(username).strip().split())
        accounts, applications = await asyncio.gather(
            self.data.find_accounts_by_username(query, limit=10),
            self.data.find_applications_by_username(query, limit=10),
        )
        if not accounts and not applications:
            return info_embed(
                "No Username Match",
                f"> No linked account or Minecraft application matches `{query}`.",
            )
        account_lines = [
            f"<@{row['discord_user_id']}> · `{row['current_username']}` · "
            f"{row['edition'].title()} · `{row['minecraft_uuid']}`"
            for row in accounts
        ] or ["No currently linked accounts."]
        application_lines = [
            f"`#{record.id}` · <@{record.discord_user_id}> · `{record.claimed_username}` · "
            f"{record.edition.value.title()} · {record.status.value.replace('_', ' ').title()}"
            for record in applications
        ] or ["No application history."]
        return info_embed(
            "Minecraft Username Lookup",
            f"> Results matching `{query}`.\n\n"
            "**Linked Accounts**\n"
            + "\n".join(account_lines)
            + "\n\n**Application History**\n"
            + "\n".join(application_lines),
        )

    async def build_applications_embed(
        self,
        *,
        status: Optional[ApplicationStatus] = None,
        limit: int = 10,
    ) -> discord.Embed:
        records = await self.data.list_applications(status=status, limit=limit)
        lines = [
            f"`#{item.id}` · <@{item.discord_user_id}> · {item.edition.value.title()} · "
            f"`{item.verified_username or item.claimed_username}` · "
            f"{item.status.value.replace('_', ' ').title()} · <t:{item.created_at}:R>"
            for item in records
        ]
        return info_embed(
            "Minecraft Applications",
            "\n".join(lines) if lines else "> No matching applications were found.",
        )

    async def build_command_log_embed(
        self,
        *,
        user_id: Optional[int] = None,
        command: Optional[str] = None,
        limit: int = 20,
    ) -> discord.Embed:
        rows, total = await asyncio.gather(
            self.data.list_command_log(actor_id=user_id, command=command, limit=limit),
            self.data.count_command_log(actor_id=user_id, command=command),
        )
        return command_log_embed(rows, total=total)

    async def _respond_with_clan_action(
        self, interaction: discord.Interaction, action: str, argument: str = ""
    ) -> None:
        await interaction.response.defer(ephemeral=True)
        await interaction.edit_original_response(
            embed=await self.run_clan_action(interaction.user.id, action, argument)
        )

    async def _respond_with_staff_tool(
        self,
        interaction: discord.Interaction,
        tool: str,
        *,
        target: str = "",
        reason: str = "",
        duration: str = "",
    ) -> None:
        # Gate one: the Discord-side moderator role. Gate two, inside
        # run_staff_action, is the linked account's real LuckPerms permission —
        # both have to hold for anything to run.
        if not await self.require_moderator(interaction):
            return
        await interaction.response.defer(ephemeral=True)
        await interaction.edit_original_response(
            embed=await self.run_staff_action(
                interaction.user.id, tool, target=target, reason=reason, duration=duration
            )
        )

    def _build_command_groups(self) -> tuple[app_commands.Group, ...]:
        """Builds the member, staff, and admin command groups, split by audience.

        Discord only honours default_member_permissions on top-level commands —
        on a subcommand it is silently ignored — so staff and admin commands
        must live in their own top-level groups to stay hidden from members
        who cannot run them.
        """
        group = app_commands.Group(
            name="minecraft",
            description="Minecraft applications, accounts, and clans.",
        )
        staff_group = app_commands.Group(
            name="mcstaff",
            description="Minecraft staff tools and records.",
            default_permissions=discord.Permissions(manage_messages=True),
        )
        admin_group = app_commands.Group(
            name="mcadmin",
            description="Minecraft bot administration.",
            default_permissions=discord.Permissions(administrator=True),
        )

        @admin_group.command(name="setup", description="Open the Minecraft application setup dashboard.")
        async def setup(interaction: discord.Interaction) -> None:
            if not await self.require_administrator(interaction):
                return
            from .setup import MinecraftSetupView

            await interaction.response.send_message(
                view=MinecraftSetupView(self, interaction.user.id, interaction.guild),
                ephemeral=True,
            )

        @staff_group.command(name="panel", description="Open the Minecraft moderator control panel.")
        async def panel(interaction: discord.Interaction) -> None:
            if not await self.require_moderator(interaction):
                return
            await interaction.response.defer(ephemeral=True)
            await interaction.edit_original_response(
                **branded_edit(await self.build_control_overview(interaction.guild)),
                view=self.control_view(interaction),
            )

        @admin_group.command(
            name="leaderboard",
            description="Choose the channel that holds the permanent leaderboard message.",
        )
        @app_commands.describe(channel="Where the self-updating leaderboard should live.")
        async def leaderboard(
            interaction: discord.Interaction, channel: discord.TextChannel
        ) -> None:
            if not await self.require_administrator(interaction):
                return
            from .leaderboard import CONFIG_CHANNEL, CONFIG_MESSAGE

            await interaction.response.defer(ephemeral=True)
            # A new channel means the old message is orphaned, so forget it and repost.
            await self.data.set_config(CONFIG_CHANNEL, channel.id)
            await self.data.set_config(CONFIG_MESSAGE, None)
            problem = await self._refresh_leaderboard_message()
            if problem:
                await interaction.edit_original_response(content=problem)
                return
            await interaction.edit_original_response(
                content=(
                    f"The leaderboard is now in {channel.mention} and refreshes every 5 minutes. "
                    "Standings fill in once the Minecraft server reports them."
                )
            )

        clan_group = app_commands.Group(
            name="clan",
            description="Your clan, and the actions your role allows.",
            parent=group,
        )

        @clan_group.command(name="view", description="Your clan, its members, and what you can do.")
        async def clan_view(interaction: discord.Interaction) -> None:
            await interaction.response.defer(ephemeral=True)
            await interaction.edit_original_response(
                embed=await self.build_capability_embed(interaction.user.id, staff=False)
            )

        @clan_group.command(name="invite", description="Invite a player to your clan.")
        @app_commands.describe(player="The player to invite.")
        async def clan_invite(interaction: discord.Interaction, player: str) -> None:
            await self._respond_with_clan_action(interaction, "invite", player)

        @clan_group.command(name="kick", description="Remove a member from your clan.")
        @app_commands.describe(player="The clan member to remove.")
        async def clan_kick(interaction: discord.Interaction, player: str) -> None:
            await self._respond_with_clan_action(interaction, "kick", player)

        @clan_group.command(name="promote", description="Promote a clan member to clan staff.")
        @app_commands.describe(player="The clan member to promote.")
        async def clan_promote(interaction: discord.Interaction, player: str) -> None:
            await self._respond_with_clan_action(interaction, "promote", player)

        @clan_group.command(name="demote", description="Demote a clan staff member.")
        @app_commands.describe(player="The clan staff member to demote.")
        async def clan_demote(interaction: discord.Interaction, player: str) -> None:
            await self._respond_with_clan_action(interaction, "demote", player)

        @clan_group.command(name="transfer", description="Transfer clan leadership.")
        @app_commands.describe(player="The clan member who should lead.")
        async def clan_transfer(interaction: discord.Interaction, player: str) -> None:
            await self._respond_with_clan_action(interaction, "transfer", player)

        @clan_group.command(name="rename", description="Rename your clan.")
        @app_commands.describe(name="The new clan name.")
        async def clan_rename(
            interaction: discord.Interaction, name: app_commands.Range[str, 1, 32]
        ) -> None:
            await self._respond_with_clan_action(interaction, "rename", name)

        @clan_group.command(name="color", description="Change your clan's colour.")
        @app_commands.describe(colour="The new clan colour.")
        async def clan_color(
            interaction: discord.Interaction, colour: app_commands.Range[str, 1, 32]
        ) -> None:
            await self._respond_with_clan_action(interaction, "color", colour)

        @clan_group.command(name="disband", description="Disband your clan.")
        async def clan_disband(interaction: discord.Interaction) -> None:
            await self._respond_with_clan_action(interaction, "disband")

        @clan_group.command(name="leave", description="Leave your clan.")
        async def clan_leave(interaction: discord.Interaction) -> None:
            await self._respond_with_clan_action(interaction, "leave")

        @staff_group.command(
            name="tools",
            description="The Minecraft staff tools your permissions allow.",
        )
        async def staff_tools(interaction: discord.Interaction) -> None:
            await interaction.response.defer(ephemeral=True)
            await interaction.edit_original_response(
                embed=await self.build_capability_embed(interaction.user.id, staff=True)
            )

        @staff_group.command(name="kick", description="Kick a player from the Minecraft server.")
        @app_commands.describe(
            player="The player to kick.",
            reason="Shown to the player and logged.",
        )
        async def staff_kick(
            interaction: discord.Interaction,
            player: str,
            reason: Optional[str] = None,
        ) -> None:
            await self._respond_with_staff_tool(
                interaction, "kick", target=player, reason=reason or ""
            )

        @staff_group.command(name="mute", description="Mute a player in Minecraft chat.")
        @app_commands.describe(
            player="The player to mute.",
            duration="How long, such as 10m or 1d; leave empty for indefinite.",
            reason="Shown to the player and logged.",
        )
        async def staff_mute(
            interaction: discord.Interaction,
            player: str,
            duration: Optional[str] = None,
            reason: Optional[str] = None,
        ) -> None:
            await self._respond_with_staff_tool(
                interaction, "mute", target=player, reason=reason or "", duration=duration or ""
            )

        @staff_group.command(name="ban", description="Ban a player from the Minecraft server.")
        @app_commands.describe(
            player="The player to ban.",
            reason="Shown to the player and logged.",
        )
        async def staff_ban(
            interaction: discord.Interaction,
            player: str,
            reason: Optional[str] = None,
        ) -> None:
            await self._respond_with_staff_tool(
                interaction, "ban", target=player, reason=reason or ""
            )

        @staff_group.command(
            name="tempban", description="Temporarily ban a player from the Minecraft server."
        )
        @app_commands.describe(
            player="The player to ban.",
            duration="How long, such as 10m or 1d.",
            reason="Shown to the player and logged.",
        )
        async def staff_tempban(
            interaction: discord.Interaction,
            player: str,
            duration: str,
            reason: Optional[str] = None,
        ) -> None:
            await self._respond_with_staff_tool(
                interaction, "tempban", target=player, duration=duration, reason=reason or ""
            )

        @staff_group.command(name="unban", description="Lift a Minecraft ban.")
        @app_commands.describe(player="The player to unban.")
        async def staff_unban(interaction: discord.Interaction, player: str) -> None:
            await self._respond_with_staff_tool(interaction, "unban", target=player)

        @staff_group.command(name="heal", description="Heal a player on the Minecraft server.")
        @app_commands.describe(player="The player to heal.")
        async def staff_heal(interaction: discord.Interaction, player: str) -> None:
            await self._respond_with_staff_tool(interaction, "heal", target=player)

        @staff_group.command(
            name="broadcast",
            description="Announce a message to everyone online, from Discord.",
        )
        @app_commands.describe(message="The message every online player will see.")
        async def staff_broadcast(interaction: discord.Interaction, message: str) -> None:
            await self._respond_with_staff_tool(interaction, "broadcast", reason=message)

        @admin_group.command(
            name="information",
            description="Post the server guide panel in a channel.",
        )
        @app_commands.describe(channel="Where the permanent guide should live.")
        async def information(
            interaction: discord.Interaction, channel: discord.TextChannel
        ) -> None:
            if not await self.require_administrator(interaction):
                return
            await interaction.response.defer(ephemeral=True)
            problem = await self.publish_information_panel(channel)
            if problem:
                await interaction.edit_original_response(content=problem)
                return
            await interaction.edit_original_response(
                content=f"The server guide is now in {channel.mention}."
            )

        @admin_group.command(
            name="cleanheads",
            description="Remove every leaderboard head emoji from this server.",
        )
        async def cleanheads(interaction: discord.Interaction) -> None:
            if not await self.require_administrator(interaction):
                return
            if interaction.guild is None:
                await interaction.response.send_message(
                    "Run this in the server you want cleaned.", ephemeral=True
                )
                return
            from .leaderboard import CONFIG_EMOJIS, purge_head_emojis

            await interaction.response.defer(ephemeral=True)
            removed, failed = await purge_head_emojis(interaction.guild)
            # Forget the registry too, or the next refresh believes they still exist.
            await self.data.set_config(CONFIG_EMOJIS, {})
            self.leaderboard_heads = {}
            summary = f"Removed {removed} leaderboard head emoji."
            if failed:
                summary += f" {failed} could not be deleted; run it again in a minute."
            await interaction.edit_original_response(content=summary)

        @group.command(name="account", description="Open your private Minecraft account and application panel.")
        async def account(interaction: discord.Interaction) -> None:
            await interaction.response.defer(ephemeral=True)
            await interaction.edit_original_response(
                **branded_edit(await self.build_account_embed(interaction.user.id)),
                view=AccountView(interaction.user.id),
            )

        @group.command(
            name="whitelist",
            description="See everyone with whitelist access and their linked Discord account.",
        )
        async def whitelist(interaction: discord.Interaction) -> None:
            await interaction.response.defer(ephemeral=True)
            await interaction.edit_original_response(
                **branded_edit(await self.build_whitelist_embed())
            )

        @admin_group.command(
            name="wipe",
            description="Owner only: delete every application and whitelist record, keeping settings.",
        )
        async def wipe(interaction: discord.Interaction) -> None:
            if not self.is_owner_member(interaction.user):
                await interaction.response.send_message(
                    **branded_send(
                        info_embed(
                            "Owner Access Required",
                            "> Only the server owner (or a member with the Owner role) can wipe Minecraft data.",
                            error=True,
                        )
                    ),
                    ephemeral=True,
                )
                return
            await interaction.response.send_modal(WipeConfirmationModal())

        @staff_group.command(name="status", description="Show Minecraft bridge and queue health.")
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
            await interaction.edit_original_response(
                **branded_edit(await self.build_control_overview(interaction.guild)),
                view=self.control_view(interaction),
            )

        @staff_group.command(name="lookup", description="Find Minecraft records by Discord member or username.")
        @app_commands.describe(
            user="Discord member to look up",
            username="Java username or Bedrock gamertag to search for",
        )
        async def lookup(
            interaction: discord.Interaction,
            user: Optional[discord.Member] = None,
            username: Optional[app_commands.Range[str, 1, 32]] = None,
        ) -> None:
            if not await self.require_moderator(interaction):
                return
            await interaction.response.defer(ephemeral=True)
            if user is not None and username is not None:
                embed = info_embed(
                    "Choose One Lookup",
                    "> Search by either a Discord member or a Minecraft username, not both.",
                    error=True,
                )
            elif user is not None:
                embed = await self.build_member_lookup_embed(user)
            elif username is not None:
                embed = await self.build_username_lookup_embed(username)
            else:
                embed = info_embed(
                    "Minecraft Lookup",
                    "> Select a Discord member below, choose **Username Lookup**, or rerun "
                    "`/mcstaff lookup` with one search field.",
                )
            await interaction.edit_original_response(
                **branded_edit(embed),
                view=self.control_view(interaction),
            )

        @staff_group.command(name="revoke", description="Remove a member's Minecraft access.")
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
                    ),
                    view=self.control_view(interaction),
                )
                return
            await interaction.edit_original_response(
                **branded_edit(
                    info_embed(
                        "Revocation Queued",
                        f"> **{len(applications)}** Minecraft account revocation(s) were queued.\n\n"
                        "Discord access will be removed only after the Minecraft server confirms every action.",
                        success=True,
                    )
                ),
                view=self.control_view(interaction),
            )
            self.spawn_background_task(
                self.dispatch_outbox_if_connected(),
                name="minecraft-revocations",
            )

        @staff_group.command(name="unlink", description="Unlink one Java or Bedrock account from a member.")
        @app_commands.describe(
            user="Discord member whose account should be unlinked",
            edition="Minecraft edition to unlink",
            reason="Internal audit reason",
        )
        @app_commands.choices(
            edition=[
                app_commands.Choice(name="Java", value=Edition.JAVA.value),
                app_commands.Choice(name="Bedrock", value=Edition.BEDROCK.value),
            ]
        )
        async def unlink(
            interaction: discord.Interaction,
            user: discord.Member,
            edition: app_commands.Choice[str],
            reason: app_commands.Range[str, 1, 500],
        ) -> None:
            if not await self.require_moderator(interaction):
                return
            await interaction.response.defer(ephemeral=True, thinking=True)
            parsed_edition = Edition(edition.value)
            account, applications, revocation_queued = await self.data.unlink_account(
                user.id,
                parsed_edition,
                interaction.user.id,
                reason,
            )
            if account is None:
                await interaction.edit_original_response(
                    **branded_edit(
                        info_embed(
                            "Minecraft Account Not Linked",
                            f"> {user.mention} does not have a linked **{edition.name}** account.",
                            error=True,
                        )
                    ),
                    view=self.control_view(interaction),
                )
                return
            if revocation_queued:
                description = (
                    f"> `{account['current_username']}` will be unlinked from {user.mention} after Paper "
                    "confirms removal of Minecraft access.\n\nThe member cannot replace that account until confirmation."
                )
                title = "Minecraft Unlink Queued"
            else:
                description = (
                    f"> `{account['current_username']}` was unlinked from {user.mention}.\n\n"
                    f"They may now link a different **{edition.name}** account."
                )
                title = "Minecraft Account Unlinked"
            await interaction.edit_original_response(
                **branded_edit(info_embed(title, description, success=True)),
                view=self.control_view(interaction),
            )
            self.spawn_background_task(
                self.finish_unlink(applications),
                name=f"minecraft-unlink:{user.id}",
            )

        @staff_group.command(name="retry", description="Retry failed bridge actions for an application.")
        @app_commands.describe(application="Application ID")
        async def retry(interaction: discord.Interaction, application: app_commands.Range[int, 1]) -> None:
            if not await self.require_moderator(interaction):
                return
            await interaction.response.defer(ephemeral=True)
            count = await self.data.retry_application(application)
            await interaction.edit_original_response(
                **branded_edit(
                    info_embed(
                        "Retry Scheduled",
                        f"> Reset **{count}** failed bridge action(s) for another delivery attempt.",
                        success=bool(count),
                    )
                ),
                view=self.control_view(interaction),
            )
            if count:
                self.spawn_background_task(
                    self.dispatch_outbox_if_connected(),
                    name=f"minecraft-retry:{application}",
                )

        @admin_group.command(name="log-channel", description="Configure or disable a Minecraft event log.")
        @app_commands.describe(
            log="Event stream to configure",
            channel="Destination channel; leave empty to disable this log",
        )
        @app_commands.choices(
            log=[
                app_commands.Choice(name="Activity Log", value="activity"),
                app_commands.Choice(name="Important Log", value="important"),
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
            channel_id = channel.id if channel is not None else 0
            if log.value == "activity":
                updates = {
                    "application_log_channel_id": channel_id,
                    "verification_log_channel_id": channel_id,
                    "player_log_channel_id": channel_id,
                    "command_log_channel_id": channel_id,
                }
                explanation = (
                    "Routine applications, verifications, player activity, and command usage share this channel."
                )
            else:
                updates = {"critical_log_channel_id": channel_id}
                explanation = (
                    "Denied or failed actions and access-changing staff commands are isolated here."
                )
            await self.update_settings(
                actor_id=interaction.user.id,
                **updates,
            )
            destination = channel.mention if channel is not None else "Disabled"
            await interaction.edit_original_response(
                **branded_edit(
                    info_embed(
                        "Minecraft Log Updated",
                        f"> **{log.name}**\n\n**Destination:** {destination}\n\n"
                        f"{explanation}\nThe change takes effect immediately.",
                        success=True,
                    )
                ),
                view=self.control_view(interaction),
            )

        @admin_group.command(name="chat-channel", description="Configure or disable two-way Minecraft chat sync.")
        @app_commands.describe(channel="Discord channel to mirror with Minecraft; leave empty to disable")
        async def chat_channel(
            interaction: discord.Interaction,
            channel: Optional[discord.TextChannel] = None,
        ) -> None:
            if not await self.require_administrator(interaction):
                return
            if channel is not None and interaction.guild is not None:
                bot_member = interaction.guild.me
                permissions = channel.permissions_for(bot_member) if bot_member is not None else None
                if permissions is None or not all((
                    permissions.view_channel,
                    permissions.send_messages,
                    permissions.embed_links,
                    permissions.read_message_history,
                    permissions.manage_webhooks,
                )):
                    await interaction.response.send_message(
                        **branded_send(info_embed(
                            "Chat Sync Not Updated",
                            "> The bot needs View Channel, Send Messages, Embed Links, Read Message History, "
                            "and Manage Webhooks in that channel.",
                            error=True,
                        )),
                        ephemeral=True,
                    )
                    return
            await interaction.response.defer(ephemeral=True)
            await self.update_settings(
                actor_id=interaction.user.id,
                chat_channel_id=channel.id if channel is not None else 0,
            )
            self._chat_webhooks.clear()
            destination = channel.mention if channel is not None else "Disabled"
            await interaction.edit_original_response(
                **branded_edit(info_embed(
                    "Minecraft Chat Sync Updated",
                    f"> **Destination:** {destination}\n\n"
                    "Messages from Minecraft use the linked Discord profile through a webhook. "
                    "Discord messages appear in-game with a distinct Discord badge. Bots and webhooks are ignored to prevent loops.",
                    success=True,
                )),
                view=self.control_view(interaction),
            )

        @staff_group.command(name="applications", description="List recent Minecraft applications by status.")
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
            await interaction.edit_original_response(
                **branded_edit(
                    await self.build_applications_embed(status=parsed_status, limit=limit)
                ),
                view=self.control_view(interaction),
            )

        @staff_group.command(name="audit", description="Show the recorded lifecycle for an application.")
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
                    ),
                    view=self.control_view(interaction),
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
                ),
                view=self.control_view(interaction),
            )

        @group.command(name="cancel", description="Cancel your pending Minecraft verification.")
        async def cancel(interaction: discord.Interaction) -> None:
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

        @staff_group.command(name="cancel", description="Cancel a staff-managed application.")
        @app_commands.describe(application="Application ID to cancel")
        async def staff_cancel(
            interaction: discord.Interaction,
            application: app_commands.Range[int, 1],
        ) -> None:
            if not await self.require_moderator(interaction):
                return
            await interaction.response.defer(ephemeral=True, thinking=True)
            try:
                updated = await self.data.cancel_application(application, interaction.user.id)
            except InvalidTransition as exc:
                await interaction.edit_original_response(
                    **branded_edit(
                        info_embed("Application Not Cancelled", f"> {exc}", error=True)
                    ),
                    view=self.control_view(interaction),
                )
                return
            await interaction.edit_original_response(
                **branded_edit(
                    info_embed(
                        "Application Cancelled",
                        f"> Application `#{updated.id}` was cancelled and its Minecraft access actions were updated.",
                        success=True,
                    )
                ),
                view=self.control_view(interaction),
            )
            self.spawn_background_task(
                self.finish_staff_cancellation(updated),
                name=f"minecraft-staff-cancel:{updated.id}",
            )

        @group.command(name="help", description="List the Minecraft commands you can use.")
        async def help_command(interaction: discord.Interaction) -> None:
            is_staff = self.is_moderator(interaction.user)
            is_admin = self.is_administrator(interaction.user)

            member_lines = [
                "`/minecraft help` — this list",
                "`/minecraft account` — your application and linked account",
                "`/minecraft server` — server address and how to connect",
                "`/minecraft whitelist` — everyone with access and their Discord account",
                "`/minecraft clan view` — your clan and the actions your role allows",
                "`/minecraft cancel` — cancel your own pending verification",
            ]
            embed = info_embed(
                "Minecraft Commands",
                "> Commands are filtered to what your roles allow.",
            )
            embed.add_field(name="Everyone", value="\n".join(member_lines), inline=False)
            if is_staff:
                embed.add_field(
                    name="Moderator Essentials",
                    value=(
                        "`/mcstaff panel` — interactive moderator controls\n"
                        "`/mcstaff lookup` — search by member or Minecraft username\n"
                        "`/mcstaff applications` — recent applications by status\n"
                        "`/mcstaff tools` — the in-game tools your permissions allow"
                    ),
                    inline=False,
                )
                embed.add_field(
                    name="In-Game Moderation",
                    value=(
                        "`/mcstaff kick` · `/mcstaff mute` · `/mcstaff ban` · `/mcstaff tempban` · "
                        "`/mcstaff unban` · `/mcstaff heal`\n"
                        "`/mcstaff broadcast` — announce a message to everyone online"
                    ),
                    inline=False,
                )
                embed.add_field(
                    name="Access Actions",
                    value=(
                        "`/mcstaff revoke` — remove a member's access\n"
                        "`/mcstaff unlink` — unlink one account\n"
                        "`/mcstaff cancel` — cancel a staff-managed application"
                    ),
                    inline=False,
                )
                embed.add_field(
                    name="Health & Records",
                    value=(
                        "`/mcstaff status` — bridge and queue health\n"
                        "`/mcstaff retry` — retry failed bridge actions\n"
                        "`/mcstaff audit` — one application's lifecycle\n"
                        "`/mcstaff commandlog` — who ran which command\n"
                        "`/mcstaff stats` — application and access totals"
                    ),
                    inline=False,
                )
            if is_admin:
                embed.add_field(
                    name="Administrators",
                    value=(
                        "`/mcadmin setup` — the setup dashboard\n"
                        "`/mcadmin information` — post the server guide panel\n"
                        "`/mcadmin leaderboard` — choose the leaderboard channel\n"
                        "`/mcadmin log-channel` — choose the Activity and Important log channels\n"
                        "`/mcadmin chat-channel` — two-way Minecraft chat sync\n"
                        "`/mcadmin cleanheads` — remove leaderboard head emoji\n"
                        "`/mcadmin wipe` — owner only; delete all application and whitelist data"
                    ),
                    inline=False,
                )
                embed.add_field(
                    name="In Game (server console or an operator)",
                    value=(
                        "`/mgxadmin ranks hold <player>` — keep a LuckPerms group set by "
                        "hand, so Discord rank sync stops undoing it\n"
                        "`/mgxadmin ranks release <player>` — hand them back to rank sync\n"
                        "`/mgxadmin reset all confirm` — clear every trace: statistics "
                        "and death counts, advancements, inventories, clans and "
                        "balances, linked Discord names, settings, verification and "
                        "the whitelist. The world and everything built in it, "
                        "operators, bans and rank holds are kept.\n"
                        "Run `/mcadmin wipe` here as well — the two sides keep "
                        "separate data and neither can clear the other."
                    ),
                    inline=False,
                )
            if not is_staff:
                embed.add_field(
                    name="Need help?",
                    value="Contact the server team through the normal support channel.",
                    inline=False,
                )
            await interaction.response.send_message(
                **branded_send(embed),
                view=self.control_view(interaction) if is_staff else None,
                ephemeral=True,
            )

        @group.command(name="server", description="Show the Minecraft server address and how to join.")
        async def server(interaction: discord.Interaction) -> None:
            await interaction.response.send_message(
                **branded_send(
                    info_embed(
                        "Minecraft Server Details",
                        "> Connection details for approved members.\n\n"
                        "**Java server address**\n"
                        f"```text\n{self.settings.java_address}\n```\n"
                        "**Bedrock server address**\n"
                        f"```text\n{self.settings.bedrock_address}\n```\n"
                        "**Bedrock port**\n"
                        f"```text\n{self.settings.bedrock_port}\n```\n"
                        "If the server rejects your connection, make sure your application "
                        "was approved and that you are joining with the username you applied with.",
                    )
                ),
                ephemeral=True,
            )

        @staff_group.command(name="stats", description="Show Minecraft application and access totals.")
        async def stats(interaction: discord.Interaction) -> None:
            if not await self.require_moderator(interaction):
                await record_denial(self, interaction, "mcstaff stats", "Not a moderator")
                return
            await interaction.response.defer(ephemeral=True, thinking=True)
            counts = await self.data.application_status_counts()
            outbox = await self.data.outbox_counts()
            total = sum(counts.values())
            approved = counts.get(ApplicationStatus.APPROVED.value, 0)
            denied = counts.get(ApplicationStatus.DENIED.value, 0)
            decided = approved + denied
            approval_rate = f"{(approved / decided * 100):.0f}%" if decided else "No decisions yet"

            embed = info_embed(
                "Minecraft Statistics",
                f"> {total} application(s) recorded in total.",
            )
            embed.add_field(
                name="Outcomes",
                value=(
                    f"**Approved:** {approved}\n"
                    f"**Denied:** {denied}\n"
                    f"**Approval rate:** {approval_rate}"
                ),
                inline=True,
            )
            embed.add_field(
                name="In Flight",
                value=(
                    f"**Pending verification:** "
                    f"{counts.get(ApplicationStatus.PENDING_VERIFICATION.value, 0)}\n"
                    f"**Pending review:** {counts.get(ApplicationStatus.PENDING_REVIEW.value, 0)}\n"
                    f"**Bridge queue:** {outbox.get('PENDING', 0)}"
                ),
                inline=True,
            )
            await interaction.edit_original_response(
                **branded_edit(embed),
                view=self.control_view(interaction),
            )

        @staff_group.command(name="commandlog", description="Review who ran which Minecraft command.")
        @app_commands.describe(
            user="Only show commands run by this member",
            command="Filter by command name",
            limit="How many records to show (1-50)",
        )
        async def commandlog(
            interaction: discord.Interaction,
            user: Optional[discord.User] = None,
            command: Optional[str] = None,
            limit: Optional[app_commands.Range[int, 1, 50]] = None,
        ) -> None:
            if not await self.require_moderator(interaction):
                await record_denial(self, interaction, "mcstaff commandlog", "Not a moderator")
                return
            await interaction.response.defer(ephemeral=True, thinking=True)
            await interaction.edit_original_response(
                **branded_edit(
                    await self.build_command_log_embed(
                        user_id=user.id if user else None,
                        command=command,
                        limit=int(limit or 20),
                    )
                ),
                view=self.control_view(interaction),
            )

        return group, staff_group, admin_group


def run() -> None:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )
    config = MinecraftConfig.from_env()
    bot = MinecraftAccessBot(config)
    bot.run(config.discord_token, log_handler=None)
