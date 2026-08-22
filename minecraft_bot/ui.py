"""Persistent application and staff-review controls."""

from __future__ import annotations

from typing import Optional

import discord

from .models import (
    AccountEditionAlreadyLinked,
    AccessStatus,
    DuplicateActiveVerification,
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
                    "> Minecraft applications are not available in this Discord "
                    "server.",
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
                    "Panel No Longer Active",
                    "> This application panel has been replaced.\n"
                    "> Please use the most recent panel in the application channel.",
                    error=True,
                )
            ),
            ephemeral=True,
        )
        return False
    return True


class VerifyButton(discord.ui.Button):
    def __init__(self) -> None:
        super().__init__(
            label="Verify",
            style=discord.ButtonStyle.primary,
            custom_id="minecraft:access:verify",
        )

    async def callback(self, interaction: discord.Interaction) -> None:
        bot = interaction.client
        if not await _validate_application_panel(interaction):
            return
        active = await bot.data.get_active_access_for_user(
            guild_id=interaction.guild_id,
            discord_user_id=interaction.user.id,
        )
        if active is not None:
            pending_verification = active.status is AccessStatus.PENDING_VERIFICATION
            message = {
                **branded_send(live_status_embed(active, bot.settings)),
                # Same controls the card gets anywhere else, including while it is
                # awaiting verification: this path used to substitute a cancel-only
                # view, so reopening the card through Apply silently dropped Get
                # Help. Deciding controls here at all is also how a Get Help button
                # survived being removed from the submitted card.
                "view": application_card_view(active.status),
                "ephemeral": True,
            }
            if pending_verification:
                message["file"] = verification_image_file()
            await interaction.response.send_message(
                **message,
            )
            if pending_verification:
                await bot.replace_application_card(
                    active.id, await interaction.original_response()
                )
            return
        if not bot.apply_rate_limit.claim(interaction.user.id):
            await interaction.response.send_message(
                **branded_send(
                    info_embed(
                        "Please Wait",
                        "> The application form was opened moments ago.\n"
                        "> Please wait a few seconds before opening it again.",
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
                    "> The server rules were not accepted, so no application was "
                    "started.\n"
                    "> Press **Apply** on the application panel whenever you wish "
                    "to begin again.",
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
                    "> Your pending verification has been cancelled.\n"
                    "> Press **Apply** on the application panel to begin again with "
                    "the correct account name.",
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


class _LinkEditionButton(discord.ui.Button):
    """Opens the account-name modal for one edition."""

    def __init__(self, edition: Edition) -> None:
        super().__init__(
            label=f"Link {edition.value.title()}",
            style=discord.ButtonStyle.success,
        )
        self.edition = edition

    async def callback(self, interaction: discord.Interaction) -> None:
        await interaction.response.send_modal(MinecraftApplicationModal(self.edition))


class LinkEditionView(discord.ui.View):
    """The ephemeral step before the link modal.

    The modal edits its original response, so it has to be opened from a private
    card — opening it straight from the public information panel would edit that
    panel for everybody.

    One button per edition offered. A member missing one edition is offered that
    one; a member with nothing linked is offered both, because the panel is the
    only surface they can reach — accepted members lose the application channel.
    """

    def __init__(self, requester_id: int, *editions: Edition) -> None:
        super().__init__(timeout=300)
        self.requester_id = int(requester_id)
        self.editions = tuple(editions)
        for edition in self.editions:
            self.add_item(_LinkEditionButton(edition))

    async def interaction_check(self, interaction: discord.Interaction) -> bool:
        if interaction.user.id == self.requester_id:
            return True
        await interaction.response.send_message(
            "This card belongs to another member.", ephemeral=True
        )
        return False


class MinecraftApplicationModal(discord.ui.Modal, title="Mysterious SMP X Application"):
    """Stage one: just the account name. The written form follows verification."""

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
        if self.edition is not None:
            self.add_item(discord.ui.Label(text="Minecraft edition", component=self.edition))
        self.add_item(self.username)

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
            application = await bot.data.create_verification(
                guild_id=interaction.guild_id,
                discord_user_id=interaction.user.id,
                edition=edition,
                claimed_username=str(self.username.value or ""),
            )
        except DuplicateActiveVerification:
            active = await bot.data.get_active_access_for_user(
                guild_id=interaction.guild_id,
                discord_user_id=interaction.user.id,
            )
            view = (
                CancelPendingConfirmationView(interaction.user.id)
                if active is not None and active.status is AccessStatus.PENDING_VERIFICATION
                else None
            )
            await edit_card(
                embed=info_embed(
                    "Application Already Active",
                    "> An application of yours is already being verified or "
                    "reviewed.\n"
                    "> If it is still awaiting verification, it can be cancelled "
                    "below so that you may apply again.",
                    error=True,
                ),
                attachments=[],
                view=view,
            )
            return
        except AccountEditionAlreadyLinked as exc:
            await edit_card(
                embed=info_embed(
                    "Account Limit Reached",
                    f"> {exc}.\n"
                    "> Each Discord account may link **one Java account and one "
                    "Bedrock account**.",
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
        except Exception:
            await edit_card(
                embed=info_embed(
                    "Application Failed",
                    "> The application could not be started. Wait a moment and try again.",
                    error=True,
                ),
                attachments=[],
            )
            raise

        card_message = await interaction.original_response()
        bot.remember_application_message(application.id, card_message)
        bot.spawn_background_task(
            bot.finish_application_submission(application),
            name=f"minecraft-application:{application.id}",
        )
        await edit_card(
            embed=live_status_embed(application, bot.settings),
            attachments=application_card_files(application),
            view=application_card_view(application.status),
        )


class SupportConfirmationView(discord.ui.View):
    def __init__(self, access_id: int, requester_id: int) -> None:
        super().__init__(timeout=60)
        self.access_id = int(access_id)
        self.requester_id = int(requester_id)

    @discord.ui.button(label="Open Support Ticket", style=discord.ButtonStyle.danger)
    async def confirm(self, interaction: discord.Interaction, _button: discord.ui.Button) -> None:
        if interaction.user.id != self.requester_id:
            await interaction.response.send_message("This confirmation belongs to another applicant.", ephemeral=True)
            return
        application = await interaction.client.data.get_access(self.access_id)
        if application is None or int(application.discord_user_id) != interaction.user.id:
            await interaction.response.send_message("This application is no longer available.", ephemeral=True)
            return
        try:
            enqueue_support_request(
                guild_id=application.guild_id,
                discord_user_id=application.discord_user_id,
                access_id=application.id,
                status=application.status.value,
                username=application.verified_username or application.claimed_username,
            )
        except OSError:
            await interaction.response.send_message(
                **branded_send(info_embed(
                        "Support Unavailable",
                        "> Your request could not be saved.\n"
                        "> Please try again shortly.",
                        error=True,
                    )),
                ephemeral=True,
            )
            return
        await interaction.response.edit_message(
            **branded_edit(info_embed(
                "Support Requested",
                "> Your request has been recorded.\n"
                "> A support ticket will be opened and you will be notified by "
                "direct message.",
                success=True,
            )),
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
            application = await interaction.client.data.get_access_by_status_message(message.id)
        if application is None:
            application = await interaction.client.data.get_active_access_for_user(
                guild_id=interaction.client.config.guild_id,
                discord_user_id=interaction.user.id,
            )
        if application is None or int(application.discord_user_id) != interaction.user.id:
            await interaction.response.send_message(
                **branded_send(info_embed(
                    "Application Unavailable",
                    "> This card is no longer connected to an application.",
                    error=True,
                )),
                ephemeral=True,
            )
            return None
        return application

    @discord.ui.button(
        label="Cancel Pending Verification",
        style=discord.ButtonStyle.danger,
        custom_id="minecraft:live:cancel",
    )
    async def cancel(self, interaction: discord.Interaction, _button: discord.ui.Button) -> None:
        """Cancels the presser's own pending verification.

        Persistent, so it acts on whoever pressed it rather than on a member id
        captured when the card was drawn — the card outlives the interaction that
        created it, and a captured id would be wrong after a restart.

        The outcome is written onto the card itself. Cancelling drops the card out
        of the refresh table, so a separate reply would have left the dead card on
        screen still offering to verify an application that no longer exists.
        """
        await interaction.response.defer()
        bot = interaction.client
        try:
            await bot.cancel_pending_verification(
                guild_id=interaction.guild_id or bot.config.guild_id,
                discord_user_id=interaction.user.id,
            )
        except InvalidTransition as exc:
            # Nothing was cancelled, so the card still describes something real and
            # is left exactly as it is.
            await interaction.followup.send(
                **branded_send(info_embed("Nothing to Cancel", f"> {exc}", error=True)),
                ephemeral=True,
            )
            return
        await interaction.edit_original_response(
            **branded_edit(
                info_embed(
                    "Verification Cancelled",
                    "> Your pending verification has been cancelled.\n"
                    "> Press **Apply** on the application panel to begin again with "
                    "the correct account name.",
                    success=True,
                )
            ),
            # The verify screenshot goes with it; the embed no longer points at it.
            attachments=[],
            view=None,
        )

    @discord.ui.button(
        label="Get Help", style=discord.ButtonStyle.secondary, custom_id="minecraft:live:help"
    )
    async def help(self, interaction: discord.Interaction, _button: discord.ui.Button) -> None:
        application = await self._application(interaction)
        if application is not None:
            await interaction.response.send_message(
                **branded_send(info_embed(
                    "Open a Support Ticket?",
                    "> A private support ticket will be opened, with your "
                    "application status attached.\n"
                    "> Press the button below to confirm.",
                )),
                view=SupportConfirmationView(application.id, interaction.user.id),
                ephemeral=True,
            )


def application_card_view(status: AccessStatus) -> Optional[discord.ui.View]:
    """The controls that belong on a live application card at this status.

    The card is drawn from two directions: the modal that submits the application
    edits it straight away, and the background task that follows refreshes it a
    moment later through ``update_live_card``. Both have to reach the same answer.
    They used to decide separately, and the refresh only knew about the continue
    button — so Get Help was drawn by the modal and then stripped by the refresh a
    second later, which read as the button flickering and vanishing.

    Only persistent views belong here. A card is refreshed long after the
    interaction that made it, and a timeout-bound view re-attached from a
    background edit would be dead on arrival.
    """
    if status is AccessStatus.PENDING_VERIFICATION:
        # The one step a member can get stuck on: a mistyped username, or a
        # connection that will not go through. Every other state is settled, so
        # offering help there only invites a ticket that says "waiting".
        return LiveApplicationView()
    # Approved, denied, expired, cancelled and revoked are final: there is nothing
    # left to do from the card.
    return None


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

    @discord.ui.button(label="Link Other Edition", style=discord.ButtonStyle.primary)
    async def link_other(self, interaction: discord.Interaction, _button: discord.ui.Button) -> None:
        embed, view = await interaction.client.build_link_edition_prompt(interaction.user.id)
        await interaction.response.send_message(
            **branded_send(embed), view=view, ephemeral=True
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


