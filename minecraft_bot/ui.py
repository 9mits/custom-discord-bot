"""Persistent application and staff-review controls."""

from __future__ import annotations

import discord

from .models import (
    AccountEditionAlreadyLinked,
    ApplicationStatus,
    DuplicateActiveApplication,
    Edition,
    InvalidTransition,
)
from .presentation import (
    branded_edit,
    branded_send,
    info_embed,
    application_card_files,
    live_status_embed,
    rules_embed,
    rules_image_file,
    verification_image_file,
)
from .support import enqueue_support_request


async def _validate_application_panel(interaction: discord.Interaction) -> bool:
    bot = interaction.client
    if interaction.guild_id != bot.config.guild_id:
        await interaction.response.send_message(
            **branded_send(
                info_embed(
                    "Applications Unavailable",
                    "> Minecraft applications are not available in this Discord server.",
                    error=True,
                )
            ),
            ephemeral=True,
        )
        return False
    panel_id = await bot.data.get_config("application_panel_message_id")
    if (
        interaction.channel_id != bot.settings.application_channel_id
        or panel_id
        and (interaction.message is None or str(interaction.message.id) != str(panel_id))
    ):
        await interaction.response.send_message(
            **branded_send(
                info_embed(
                    "Application Panel Updated",
                    "> This panel is no longer active. Use the newest panel in the configured application channel.",
                    error=True,
                )
            ),
            ephemeral=True,
        )
        return False
    return True


class ApplyButton(discord.ui.Button):
    def __init__(self) -> None:
        super().__init__(
            label="Apply",
            style=discord.ButtonStyle.primary,
            custom_id="minecraft:application:apply",
        )

    async def callback(self, interaction: discord.Interaction) -> None:
        bot = interaction.client
        if not await _validate_application_panel(interaction):
            return
        active = await bot.data.get_active_application_for_user(
            guild_id=interaction.guild_id,
            discord_user_id=interaction.user.id,
        )
        if active is not None:
            pending_verification = active.status is ApplicationStatus.PENDING_VERIFICATION
            message = {
                **branded_send(live_status_embed(active, bot.settings)),
                "view": (
                    CancelPendingConfirmationView(interaction.user.id)
                    if pending_verification
                    else LiveApplicationView()
                ),
                "ephemeral": True,
            }
            if pending_verification:
                message["file"] = verification_image_file()
            await interaction.response.send_message(
                **message,
            )
            return
        if not bot.apply_rate_limit.claim(interaction.user.id):
            await interaction.response.send_message(
                **branded_send(
                    info_embed(
                        "Application Recently Opened",
                        "> Please wait a few seconds before opening the application form again.",
                    )
                ),
                ephemeral=True,
            )
            return
        await interaction.response.send_message(
            **branded_send(rules_embed(agreement=True)),
            file=rules_image_file(),
            view=RulesAgreementView(interaction.user.id),
            ephemeral=True,
        )


class RulesButton(discord.ui.Button):
    def __init__(self) -> None:
        super().__init__(
            label="Rules",
            style=discord.ButtonStyle.secondary,
            custom_id="minecraft:application:rules",
        )

    async def callback(self, interaction: discord.Interaction) -> None:
        if not await _validate_application_panel(interaction):
            return
        await interaction.response.send_message(
            **branded_send(rules_embed()),
            file=rules_image_file(),
            ephemeral=True,
        )


class RulesAgreementView(discord.ui.View):
    def __init__(self, requester_id: int) -> None:
        super().__init__(timeout=300)
        self.requester_id = int(requester_id)

    async def interaction_check(self, interaction: discord.Interaction) -> bool:
        if interaction.user.id == self.requester_id:
            return True
        await interaction.response.send_message(
            **branded_send(
                info_embed(
                    "Agreement Unavailable",
                    "> This rules agreement belongs to another applicant.",
                    error=True,
                )
            ),
            ephemeral=True,
        )
        return False

    @discord.ui.button(label="I Agree", style=discord.ButtonStyle.success)
    async def agree(self, interaction: discord.Interaction, _button: discord.ui.Button) -> None:
        await interaction.response.send_modal(MinecraftApplicationModal(
            require_edition=not interaction.client.bridge.supports_auto_edition,
        ))

    @discord.ui.button(label="I Disagree", style=discord.ButtonStyle.secondary)
    async def disagree(self, interaction: discord.Interaction, _button: discord.ui.Button) -> None:
        await interaction.response.edit_message(
            **branded_edit(
                info_embed(
                    "Application Closed",
                    "> You chose not to accept the server rules, so no application was started.\n\n"
                    "You may return to the public panel and apply later if you decide to accept them.",
                )
            ),
            attachments=[],
            view=None,
        )


class CancelPendingConfirmationView(discord.ui.View):
    def __init__(self, requester_id: int) -> None:
        super().__init__(timeout=60)
        self.requester_id = int(requester_id)

    @discord.ui.button(label="Cancel Pending Verification", style=discord.ButtonStyle.danger)
    async def confirm(self, interaction: discord.Interaction, _button: discord.ui.Button) -> None:
        if interaction.user.id != self.requester_id:
            await interaction.response.send_message(
                **branded_send(
                    info_embed(
                        "Confirmation Unavailable",
                        "> This cancellation prompt belongs to another applicant.",
                        error=True,
                    )
                ),
                ephemeral=True,
            )
            return
        await interaction.response.defer()
        bot = interaction.client
        try:
            await bot.cancel_pending_verification(
                guild_id=interaction.guild_id or bot.config.guild_id,
                discord_user_id=interaction.user.id,
            )
        except InvalidTransition as exc:
            await interaction.edit_original_response(
                **branded_edit(info_embed("Nothing to Cancel", f"> {exc}", error=True)),
                attachments=[],
                view=None,
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
            ),
            attachments=[],
            view=None,
        )


class EditionSelection(discord.ui.Select):
    def __init__(self, requester_id: int) -> None:
        self.requester_id = requester_id
        super().__init__(
            placeholder="Choose Java or Bedrock",
            min_values=1,
            max_values=1,
            options=[
                discord.SelectOption(label="Java", value=Edition.JAVA.value),
                discord.SelectOption(label="Bedrock", value=Edition.BEDROCK.value),
            ],
        )

    async def callback(self, interaction: discord.Interaction) -> None:
        if interaction.user.id != self.requester_id:
            await interaction.response.send_message(
                **branded_send(
                    info_embed(
                        "Edition Selection Unavailable",
                        "> This edition selector belongs to another applicant.",
                        error=True,
                    )
                ),
                ephemeral=True,
            )
            return
        await interaction.response.send_modal(MinecraftApplicationModal(Edition(self.values[0])))


class EditionSelectionView(discord.ui.View):
    def __init__(self, requester_id: int) -> None:
        super().__init__(timeout=120)
        self.add_item(EditionSelection(requester_id))


class MinecraftApplicationModal(discord.ui.Modal, title="Mysterious SMP X Application"):
    def __init__(
        self,
        fixed_edition: Edition | None = None,
        *,
        require_edition: bool = False,
    ) -> None:
        super().__init__(timeout=600, custom_id="minecraft:application:modal")
        self.fixed_edition = fixed_edition
        self.edition = None
        if fixed_edition is None and require_edition:
            self.edition = discord.ui.Select(
                custom_id="minecraft:application:edition",
                placeholder="Choose Java or Bedrock",
                min_values=1,
                max_values=1,
                options=[
                    discord.SelectOption(label="Java", value=Edition.JAVA.value),
                    discord.SelectOption(label="Bedrock", value=Edition.BEDROCK.value),
                ],
            )
        self.username = discord.ui.TextInput(
            label="Minecraft username or Xbox gamertag",
            placeholder="Enter the exact account name",
            min_length=1,
            max_length=16,
        )
        self.why = discord.ui.TextInput(
            label="Why do you want to join Mysterious SMP X?",
            style=discord.TextStyle.paragraph,
            min_length=10,
            max_length=500,
        )
        self.about = discord.ui.TextInput(
            label="What would you bring to the server?",
            placeholder="Tell us a little about yourself.",
            style=discord.TextStyle.paragraph,
            min_length=10,
            max_length=1000,
        )
        if self.edition is not None:
            self.add_item(discord.ui.Label(text="Minecraft edition", component=self.edition))
        self.add_item(self.username)
        self.add_item(self.why)
        self.add_item(self.about)

    async def on_submit(self, interaction: discord.Interaction) -> None:
        # A modal opened from a component can defer a message update. Editing
        # that response keeps the entire application on the original ephemeral
        # card and avoids relying on a stale Message object captured earlier.
        await interaction.response.defer()
        bot = interaction.client

        async def edit_card(*, embed: discord.Embed, attachments=None, view=None) -> None:
            payload = {**branded_edit(embed), "view": view}
            if attachments is not None:
                payload["attachments"] = attachments
            await interaction.edit_original_response(**payload)

        try:
            edition = self.fixed_edition
            if edition is None and self.edition is not None:
                edition = Edition(self.edition.values[0])
            application = await bot.data.create_application(
                guild_id=interaction.guild_id,
                discord_user_id=interaction.user.id,
                edition=edition,
                claimed_username=str(self.username),
                answers={"why": str(self.why), "about": str(self.about)},
            )
        except DuplicateActiveApplication:
            active = await bot.data.get_active_application_for_user(
                guild_id=interaction.guild_id,
                discord_user_id=interaction.user.id,
            )
            view = (
                CancelPendingConfirmationView(interaction.user.id)
                if active is not None and active.status is ApplicationStatus.PENDING_VERIFICATION
                else None
            )
            await edit_card(
                embed=info_embed(
                    "Application Already Active",
                    "> You already have an application being verified or reviewed.\n\n"
                    "If it is still awaiting verification, you can cancel it below and apply again.",
                    error=True,
                ),
                attachments=[],
                view=view,
            )
            return
        except AccountEditionAlreadyLinked as exc:
            await edit_card(
                embed=info_embed(
                    "Minecraft Account Limit Reached",
                    f"> {exc}.\n\n"
                    "Each Discord member may link **one Java account and one Bedrock account**.",
                    error=True,
                ),
                attachments=[],
            )
            return
        except ValueError as exc:
            await edit_card(
                embed=info_embed("Application Invalid", f"> {exc}", error=True),
                attachments=[],
            )
            return

        card_message = await interaction.original_response()
        bot.remember_application_message(application.id, card_message)
        bot.spawn_background_task(
            bot.finish_application_submission(application),
            name=f"minecraft-application:{application.id}",
        )
        await edit_card(
            embed=live_status_embed(application, bot.settings),
            attachments=application_card_files(application),
            view=LiveApplicationView(),
        )


class SupportConfirmationView(discord.ui.View):
    def __init__(self, application_id: int, requester_id: int) -> None:
        super().__init__(timeout=60)
        self.application_id = int(application_id)
        self.requester_id = int(requester_id)

    @discord.ui.button(label="Open Support Ticket", style=discord.ButtonStyle.danger)
    async def confirm(self, interaction: discord.Interaction, _button: discord.ui.Button) -> None:
        if interaction.user.id != self.requester_id:
            await interaction.response.send_message("This confirmation belongs to another applicant.", ephemeral=True)
            return
        application = await interaction.client.data.get_application(self.application_id)
        if application is None or int(application.discord_user_id) != interaction.user.id:
            await interaction.response.send_message("This application is no longer available.", ephemeral=True)
            return
        try:
            enqueue_support_request(
                guild_id=application.guild_id,
                discord_user_id=application.discord_user_id,
                application_id=application.id,
                status=application.status.value,
                username=application.verified_username or application.claimed_username,
            )
        except OSError:
            await interaction.response.send_message(
                **branded_send(info_embed("Support Temporarily Unavailable", "> Your request could not be saved. Please try again shortly.", error=True)),
                ephemeral=True,
            )
            return
        await interaction.response.edit_message(
            **branded_edit(info_embed("Support Requested", "> Your request was saved securely. The support bot will open a normal modmail ticket and notify you by DM.", success=True)),
            view=None,
        )


class LiveApplicationView(discord.ui.View):
    """Persistent controls; the application is resolved from the stored message ID."""

    def __init__(self) -> None:
        super().__init__(timeout=None)

    async def _application(self, interaction: discord.Interaction):
        message = interaction.message
        application = None
        if message is not None:
            application = await interaction.client.data.get_application_by_status_message(message.id)
        if application is None:
            application = await interaction.client.data.get_active_application_for_user(
                guild_id=interaction.client.config.guild_id,
                discord_user_id=interaction.user.id,
            )
        if application is None or int(application.discord_user_id) != interaction.user.id:
            await interaction.response.send_message(
                **branded_send(info_embed("Application Unavailable", "> This card is no longer connected to an application.", error=True)),
                ephemeral=True,
            )
            return None
        return application

    @discord.ui.button(label="Get Help", style=discord.ButtonStyle.danger, custom_id="minecraft:live:help")
    async def help(self, interaction: discord.Interaction, _button: discord.ui.Button) -> None:
        application = await self._application(interaction)
        if application is not None:
            await interaction.response.send_message(
                **branded_send(info_embed("Open Minecraft Support?", "> This creates a private modmail ticket with your application status attached. Continue?")),
                view=SupportConfirmationView(application.id, interaction.user.id),
                ephemeral=True,
            )


class AccountView(discord.ui.View):
    def __init__(self, requester_id: int) -> None:
        super().__init__(timeout=600)
        self.requester_id = int(requester_id)

    async def interaction_check(self, interaction: discord.Interaction) -> bool:
        if interaction.user.id == self.requester_id:
            return True
        await interaction.response.send_message("This account panel belongs to another member.", ephemeral=True)
        return False

    @discord.ui.button(label="Refresh", style=discord.ButtonStyle.secondary)
    async def refresh(self, interaction: discord.Interaction, _button: discord.ui.Button) -> None:
        await interaction.response.edit_message(
            **branded_edit(await interaction.client.build_account_embed(interaction.user.id)),
            view=self,
        )

    @discord.ui.button(label="Cancel Verification", style=discord.ButtonStyle.danger)
    async def cancel(self, interaction: discord.Interaction, _button: discord.ui.Button) -> None:
        await interaction.response.send_message(
            **branded_send(info_embed("Cancel Verification?", "> This only cancels an application that has not yet been verified.")),
            view=CancelPendingConfirmationView(interaction.user.id),
            ephemeral=True,
        )

class MinecraftUsernameLookupModal(discord.ui.Modal, title="Minecraft Username Lookup"):
    username = discord.ui.TextInput(
        label="Java username or Bedrock gamertag",
        placeholder="Enter all or part of the account name",
        min_length=1,
        max_length=32,
    )

    def __init__(self, bot, requester_id: int) -> None:
        super().__init__(timeout=300)
        self.bot = bot
        self.requester_id = int(requester_id)

    async def on_submit(self, interaction: discord.Interaction) -> None:
        if interaction.user.id != self.requester_id or not self.bot.is_moderator(interaction.user):
            await interaction.response.send_message(
                **branded_send(
                    info_embed(
                        "Lookup Unavailable",
                        "> This Minecraft control panel belongs to another moderator.",
                        error=True,
                    )
                ),
                ephemeral=True,
            )
            return
        await interaction.response.defer(ephemeral=True)
        embed = await self.bot.build_username_lookup_embed(str(self.username))
        await interaction.edit_original_response(
            **branded_edit(embed),
            view=self.bot.control_view(interaction),
        )


class MinecraftControlSelect(discord.ui.Select):
    def __init__(self, *, include_setup: bool = False) -> None:
        options = [
            discord.SelectOption(label="Overview", value="overview", description="Service and queue health"),
            discord.SelectOption(label="Diagnostics", value="diagnostics", description="Explain anything that needs attention"),
            discord.SelectOption(label="Applications", value="applications", description="Recent application activity"),
            discord.SelectOption(label="Command Log", value="commandlog", description="Recent moderator actions"),
            discord.SelectOption(label="Username Lookup", value="username", description="Search Minecraft account names"),
        ]
        if include_setup:
            options.append(
                discord.SelectOption(label="Setup", value="setup", description="Minecraft configuration")
            )
        super().__init__(
            placeholder="Choose a moderator tool",
            custom_id="minecraft:control:tools",
            min_values=1,
            max_values=1,
            options=options,
        )

    async def callback(self, interaction: discord.Interaction) -> None:
        view = self.view
        if not isinstance(view, MinecraftControlView):
            return
        action = self.values[0]
        if action == "username":
            await interaction.response.send_modal(
                MinecraftUsernameLookupModal(view.bot, view.requester_id)
            )
            return
        if action == "setup":
            from .setup import MinecraftSetupView

            if not view.bot.is_administrator(interaction.user):
                await interaction.response.send_message(
                    **branded_send(
                        info_embed(
                            "Administrator Access Required",
                            "> Only server administrators can change Minecraft setup.",
                            error=True,
                        )
                    ),
                    ephemeral=True,
                )
                return
            await interaction.response.send_message(
                view=MinecraftSetupView(view.bot, interaction.user.id, interaction.guild),
                ephemeral=True,
            )
            return

        await interaction.response.defer(ephemeral=True)
        builders = {
            "overview": lambda: view.bot.build_control_overview(interaction.guild),
            "diagnostics": lambda: view.bot.build_diagnostics_embed(interaction.guild),
            "applications": view.bot.build_applications_embed,
            "commandlog": view.bot.build_command_log_embed,
        }
        embed = await builders[action]()
        await interaction.edit_original_response(
            **branded_edit(embed),
            view=view.bot.control_view(interaction),
        )


class MinecraftControlView(discord.ui.View):
    """One compact navigation menu shared by Minecraft moderator commands."""

    def __init__(self, bot, requester_id: int, *, include_setup: bool = False) -> None:
        super().__init__(timeout=900)
        self.bot = bot
        self.requester_id = int(requester_id)
        self.add_item(MinecraftControlSelect(include_setup=include_setup))

    async def interaction_check(self, interaction: discord.Interaction) -> bool:
        if interaction.user.id == self.requester_id and self.bot.is_moderator(interaction.user):
            return True
        await interaction.response.send_message(
            **branded_send(
                info_embed(
                    "Control Panel Unavailable",
                    "> This Minecraft control panel belongs to another moderator.",
                    error=True,
                )
            ),
            ephemeral=True,
        )
        return False


class DenialModal(discord.ui.Modal, title="Deny Minecraft Application"):
    internal_note = discord.ui.TextInput(
        label="Internal moderator note",
        style=discord.TextStyle.paragraph,
        min_length=1,
        max_length=1000,
    )
    applicant_reason = discord.ui.TextInput(
        label="Optional applicant-facing reason",
        style=discord.TextStyle.paragraph,
        required=False,
        max_length=1000,
    )

    def __init__(self, application_id: int) -> None:
        super().__init__(timeout=300, custom_id=f"minecraft:deny:{application_id}")
        self.application_id = application_id

    async def on_submit(self, interaction: discord.Interaction) -> None:
        bot = interaction.client
        if not bot.is_moderator(interaction.user):
            await interaction.response.send_message(
                **branded_send(
                    info_embed(
                        "Review Access Required",
                        "> You do not have permission to review Minecraft applications.",
                        error=True,
                    )
                ),
                ephemeral=True,
            )
            return
        await interaction.response.defer(ephemeral=True, thinking=True)
        application = await bot.data.get_application(self.application_id)
        if application is None:
            await interaction.edit_original_response(
                **branded_edit(
                    info_embed(
                        "Application Not Found",
                        "> That application no longer exists.",
                        error=True,
                    )
                )
            )
            return
        try:
            updated = await bot.data.deny_application(
                application.id,
                interaction.user.id,
                internal_note=str(self.internal_note).strip(),
                applicant_reason=str(self.applicant_reason).strip(),
            )
        except InvalidTransition as exc:
            await interaction.edit_original_response(
                **branded_edit(info_embed("Application Not Updated", f"> {exc}", error=True))
            )
            return
        bot.spawn_background_task(
            bot.finish_denial(updated),
            name=f"minecraft-denial:{updated.id}",
        )
        await interaction.edit_original_response(
            **branded_edit(
                info_embed(
                    "Application Denied",
                    "> The decision was saved and the staff review record is updating.\n\n"
                    "The applicant is notified by DM without exposing the reviewing moderator.",
                    success=True,
                )
            )
        )


class ReviewView(discord.ui.View):
    def __init__(self, *, disabled: bool = False) -> None:
        super().__init__(timeout=None)
        for item in self.children:
            item.disabled = disabled

    async def _application(self, interaction: discord.Interaction):
        message = interaction.message
        if message is None:
            return None
        return await interaction.client.data.get_application_by_review_message(message.id)

    async def _authorize(self, interaction: discord.Interaction):
        bot = interaction.client
        if not bot.is_moderator(interaction.user):
            await interaction.response.send_message(
                **branded_send(
                    info_embed(
                        "Review Access Required",
                        "> You do not have permission to review Minecraft applications.",
                        error=True,
                    )
                ),
                ephemeral=True,
            )
            return None
        application = await self._application(interaction)
        if application is None:
            await interaction.response.send_message(
                **branded_send(
                    info_embed(
                        "Review Record Unavailable",
                        "> This review message is stale or its application record is missing.",
                        error=True,
                    )
                ),
                ephemeral=True,
            )
            return None
        return application

    @discord.ui.button(
        label="Approve",
        style=discord.ButtonStyle.success,
        custom_id="minecraft:review:approve",
    )
    async def approve(self, interaction: discord.Interaction, _button: discord.ui.Button) -> None:
        application = await self._authorize(interaction)
        if application is None:
            return
        bot = interaction.client
        has_mod_role = any(role.id == bot.settings.mod_role_id for role in interaction.user.roles)
        if int(application.discord_user_id) == interaction.user.id and not has_mod_role:
            await interaction.response.send_message(
                **branded_send(
                    info_embed(
                        "Approval Restricted",
                        "> Administrators cannot approve their own application unless they also hold the configured moderator role.",
                        error=True,
                    )
                ),
                ephemeral=True,
            )
            return
        await interaction.response.defer(ephemeral=True, thinking=True)
        try:
            updated = await bot.data.queue_approval(application.id, interaction.user.id)
        except InvalidTransition as exc:
            await interaction.edit_original_response(
                **branded_edit(info_embed("Approval Not Queued", f"> {exc}", error=True))
            )
            return
        state = "being sent to the Minecraft server" if bot.bridge.connected else "queued until the Minecraft bridge reconnects"
        bot.spawn_background_task(
            bot.finish_approval_queue(updated),
            name=f"minecraft-approval:{updated.id}",
        )
        await interaction.edit_original_response(
            **branded_edit(
                info_embed(
                    "Approval Queued",
                    f"> The whitelist action was **{state}**.\n\n"
                    "Approval finalizes only after the Minecraft server confirms the account change. "
                    "The applicant will then receive the server addresses by DM.",
                    success=True,
                )
            )
        )

    @discord.ui.button(
        label="Deny",
        style=discord.ButtonStyle.danger,
        custom_id="minecraft:review:deny",
    )
    async def deny(self, interaction: discord.Interaction, _button: discord.ui.Button) -> None:
        application = await self._authorize(interaction)
        if application is None:
            return
        if application.status is not ApplicationStatus.PENDING_REVIEW:
            await interaction.response.send_message(
                **branded_send(
                    info_embed(
                        "Application Already Reviewed",
                        "> This application already has a completed or queued decision.",
                        error=True,
                    )
                ),
                ephemeral=True,
            )
            return
        await interaction.response.send_modal(DenialModal(application.id))
