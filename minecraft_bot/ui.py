"""Persistent application and staff-review controls."""

from __future__ import annotations

import discord

from .models import ApplicationStatus, DuplicateActiveApplication, Edition, InvalidTransition
from .presentation import branded_edit, branded_send, info_embed, verification_embed


class ApplyButton(discord.ui.Button):
    def __init__(self) -> None:
        super().__init__(
            label="Apply",
            style=discord.ButtonStyle.primary,
            custom_id="minecraft:application:apply",
        )

    async def callback(self, interaction: discord.Interaction) -> None:
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
            return
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
            return
        active = await bot.data.get_active_application_for_user(
            guild_id=interaction.guild_id,
            discord_user_id=interaction.user.id,
        )
        if active is not None:
            if active.status is ApplicationStatus.PENDING_VERIFICATION:
                await interaction.response.send_message(
                    **branded_send(verification_embed(active, bot.settings)),
                    view=CancelPendingConfirmationView(interaction.user.id),
                    ephemeral=True,
                )
            else:
                await interaction.response.send_message(
                    **branded_send(
                        info_embed(
                            "Application Already Active",
                            f"> Application `#{active.id}` is currently **"
                            f"{active.status.value.replace('_', ' ').title()}**.\n\n"
                            "It can no longer be cancelled by the applicant. Staff aim to send a decision within **24 hours**.",
                        )
                    ),
                    ephemeral=True,
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
        if hasattr(discord.ui, "Label"):
            await interaction.response.send_modal(MinecraftApplicationModal())
        else:
            await interaction.response.send_message(
                **branded_send(
                    info_embed(
                        "Choose Your Minecraft Edition",
                        "> Select the edition used by the account you want to verify.",
                    )
                ),
                view=EditionSelectionView(interaction.user.id),
                ephemeral=True,
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
                guild_id=interaction.guild_id,
                discord_user_id=interaction.user.id,
            )
        except InvalidTransition as exc:
            await interaction.edit_original_response(
                **branded_edit(info_embed("Nothing to Cancel", f"> {exc}", error=True)),
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
    def __init__(self, fixed_edition: Edition | None = None) -> None:
        super().__init__(timeout=600, custom_id="minecraft:application:modal")
        self.fixed_edition = fixed_edition
        self.edition = None
        if fixed_edition is None:
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
        await interaction.response.defer(ephemeral=True, thinking=True)
        bot = interaction.client
        try:
            edition = self.fixed_edition or Edition(self.edition.values[0])
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
            await interaction.edit_original_response(
                **branded_edit(
                    info_embed(
                        "Application Already Active",
                        "> You already have an application being verified or reviewed.\n\n"
                        "If it is still awaiting verification, you can cancel it below and apply again.",
                        error=True,
                    )
                ),
                view=view,
            )
            return
        except ValueError as exc:
            await interaction.edit_original_response(
                **branded_edit(info_embed("Application Invalid", f"> {exc}", error=True))
            )
            return

        bot.remember_application_interaction(application.id, interaction)
        if bot.bridge.connected:
            await bot.bridge.dispatch_outbox()
        await interaction.edit_original_response(
            **branded_edit(verification_embed(application, bot.settings))
        )


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
        await bot.finish_denial(updated)
        await interaction.edit_original_response(
            **branded_edit(
                info_embed(
                    "Application Denied",
                    "> The decision was saved and the staff review record was updated.\n\n"
                    "The applicant was notified by DM without exposing the reviewing moderator.",
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
        await bot.update_review_message(updated)
        if bot.bridge.connected:
            await bot.bridge.dispatch_outbox()
        state = "sent to the Minecraft server" if bot.bridge.connected else "queued until the Minecraft bridge reconnects"
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

    @discord.ui.button(
        label="View Previous Applications",
        style=discord.ButtonStyle.secondary,
        custom_id="minecraft:review:history",
    )
    async def history(self, interaction: discord.Interaction, _button: discord.ui.Button) -> None:
        application = await self._authorize(interaction)
        if application is None:
            return
        history = await interaction.client.data.list_applications_for_user(
            application.discord_user_id,
            limit=25,
        )
        lines = [
            f"`#{entry.id}` · {entry.edition.value.title()} · {entry.status.value.replace('_', ' ').title()} · <t:{entry.created_at}:d>"
            for entry in history
        ]
        await interaction.response.send_message(
            **branded_send(
                info_embed(
                    "Previous Minecraft Applications",
                    "\n".join(lines) or "> No previous applications were found.",
                )
            ),
            ephemeral=True,
        )
