"""Administrator-only Components V2 setup dashboard for the Minecraft bot."""

from __future__ import annotations

import logging
from contextlib import suppress
from dataclasses import dataclass
from typing import Optional

import discord

from .presentation import BRAND_NAME, THEME_COLOUR, branded_edit, branded_send, info_embed


logger = logging.getLogger("MinecraftAccessBot.Setup")


async def _report_internal_error(interaction: discord.Interaction, error: Exception) -> None:
    correlation_id = f"mc-setup-{interaction.id:x}"
    logger.error(
        "Minecraft setup failed correlation_id=%s",
        correlation_id,
        exc_info=(type(error), error, error.__traceback__),
    )
    embed = info_embed(
        "Setup Action Failed",
        "> The setup action could not be completed.\n\n"
        f"**Reference:** `{correlation_id}`\n"
        "Share this reference with staff if the problem continues.",
        error=True,
    )
    if interaction.response.is_done():
        await interaction.followup.send(**branded_send(embed), ephemeral=True)
    else:
        await interaction.response.send_message(**branded_send(embed), ephemeral=True)


@dataclass(frozen=True)
class SetupFinding:
    setting: str
    detail: str


def configuration_findings(bot, guild: Optional[discord.Guild]) -> list[SetupFinding]:
    settings = bot.settings
    findings: list[SetupFinding] = []
    if guild is None:
        return [SetupFinding("Server", "The configured Discord server is unavailable.")]

    channels = (
        ("Application channel", settings.application_channel_id, False, True),
        ("Review channel", settings.review_channel_id, True, True),
        ("Application log", settings.application_log_channel_id, True, False),
        ("Verification log", settings.verification_log_channel_id, True, False),
        ("Player activity log", settings.player_log_channel_id, True, False),
        ("Command log", settings.command_log_channel_id, True, False),
        ("Important command log", settings.critical_log_channel_id, True, False),
    )
    for label, channel_id, needs_embeds, required_channel in channels:
        if not channel_id:
            if required_channel:
                findings.append(SetupFinding(label, f"Select the {label.lower()}."))
            continue
        channel = guild.get_channel(channel_id)
        if channel is None:
            findings.append(SetupFinding(label, "The saved channel no longer exists or is unavailable."))
            continue
        bot_member = guild.me
        if bot_member is None or not hasattr(channel, "permissions_for"):
            findings.append(SetupFinding(label, "Bot channel permissions could not be checked."))
            continue
        permissions = channel.permissions_for(bot_member)
        required = (
            ("View Channel", permissions.view_channel),
            ("Send Messages", permissions.send_messages),
            ("Read Message History", permissions.read_message_history),
            ("Attach Files", permissions.attach_files),
        )
        missing = [name for name, allowed in required if not allowed]
        if needs_embeds and not permissions.embed_links:
            missing.append("Embed Links")
        if missing:
            findings.append(SetupFinding(label, "Missing bot permissions: " + ", ".join(missing) + "."))

    if (
        settings.application_channel_id
        and settings.application_channel_id == settings.review_channel_id
    ):
        findings.append(SetupFinding("Channels", "Application and review channels must be different."))

    roles = (
        ("Moderator role", settings.mod_role_id),
        ("Approved-member role", settings.member_role_id),
    )
    resolved_roles = {}
    for label, role_id in roles:
        if not role_id:
            findings.append(SetupFinding(label, f"Select the {label.lower()}."))
            continue
        role = guild.get_role(role_id)
        if role is None:
            findings.append(SetupFinding(label, "The saved role no longer exists."))
            continue
        resolved_roles[label] = role

    if settings.mod_role_id and settings.mod_role_id == settings.member_role_id:
        findings.append(SetupFinding("Roles", "Moderator and approved-member roles must be different."))

    member_role = resolved_roles.get("Approved-member role")
    if member_role is not None:
        if member_role.is_default() or member_role.managed:
            findings.append(SetupFinding("Approved-member role", "Choose a normal assignable role."))
        bot_member = guild.me
        if bot_member is None:
            findings.append(SetupFinding("Approved-member role", "Bot role hierarchy could not be checked."))
        else:
            if not bot_member.guild_permissions.manage_roles:
                findings.append(SetupFinding("Approved-member role", "The bot needs Manage Roles."))
            if member_role.position >= bot_member.top_role.position:
                findings.append(
                    SetupFinding("Approved-member role", "Move the bot's role above the approved-member role.")
                )

    return findings


def _channel_value(guild: Optional[discord.Guild], channel_id: int) -> str:
    if not channel_id:
        return "Not configured"
    if guild is not None and guild.get_channel(channel_id) is not None:
        return f"<#{channel_id}>"
    return f"`{channel_id}` (missing)"


def _role_value(guild: Optional[discord.Guild], role_id: int) -> str:
    if not role_id:
        return "Not configured"
    if guild is not None and guild.get_role(role_id) is not None:
        return f"<@&{role_id}>"
    return f"`{role_id}` (missing)"


class MinecraftChannelSelect(discord.ui.ChannelSelect):
    def __init__(self, setting: str, label: str) -> None:
        self.setting = setting
        self.label = label
        super().__init__(
            custom_id=f"minecraft:setup:{setting}",
            placeholder=f"Select {label.lower()}",
            min_values=1,
            max_values=1,
            channel_types=[discord.ChannelType.text],
        )

    async def callback(self, interaction: discord.Interaction) -> None:
        view = self.view
        if not isinstance(view, MinecraftSetupView):
            return
        await interaction.response.defer(ephemeral=True)
        try:
            await view.bot.update_settings(
                actor_id=interaction.user.id,
                **{self.setting: self.values[0].id},
            )
        except ValueError as exc:
            await interaction.followup.send(
                **branded_send(info_embed("Setting Not Updated", f"> {exc}", error=True)),
                ephemeral=True,
            )
            return
        await interaction.edit_original_response(
            view=MinecraftSetupView(view.bot, view.requester_id, interaction.guild)
        )


class MinecraftRoleSelect(discord.ui.RoleSelect):
    def __init__(self, setting: str, label: str) -> None:
        self.setting = setting
        self.label = label
        super().__init__(
            custom_id=f"minecraft:setup:{setting}",
            placeholder=f"Select {label.lower()}",
            min_values=1,
            max_values=1,
        )

    async def callback(self, interaction: discord.Interaction) -> None:
        view = self.view
        if not isinstance(view, MinecraftSetupView):
            return
        await interaction.response.defer(ephemeral=True)
        try:
            await view.bot.update_settings(
                actor_id=interaction.user.id,
                **{self.setting: self.values[0].id},
            )
        except ValueError as exc:
            await interaction.followup.send(
                **branded_send(info_embed("Setting Not Updated", f"> {exc}", error=True)),
                ephemeral=True,
            )
            return
        await interaction.edit_original_response(
            view=MinecraftSetupView(view.bot, view.requester_id, interaction.guild)
        )


class MinecraftAddressModal(discord.ui.Modal):
    def __init__(self, source_interaction: discord.Interaction, view: "MinecraftSetupView") -> None:
        super().__init__(title="Edit Minecraft Addresses", timeout=300)
        self.source_interaction = source_interaction
        self.setup_view = view
        settings = view.bot.settings
        self.java_address = discord.ui.TextInput(
            label="Java address",
            placeholder="server.example:25565",
            default=settings.java_address,
            max_length=260,
        )
        self.bedrock_address = discord.ui.TextInput(
            label="Bedrock host",
            placeholder="server.example",
            default=settings.bedrock_address,
            max_length=253,
        )
        self.bedrock_port = discord.ui.TextInput(
            label="Bedrock port",
            placeholder="19132",
            default=str(settings.bedrock_port),
            max_length=5,
        )
        self.add_item(self.java_address)
        self.add_item(self.bedrock_address)
        self.add_item(self.bedrock_port)

    async def on_submit(self, interaction: discord.Interaction) -> None:
        if (
            interaction.user.id != self.setup_view.requester_id
            or not self.setup_view.bot.is_administrator(interaction.user)
        ):
            await interaction.response.send_message(
                **branded_send(
                    info_embed(
                        "Setup Access Required",
                        "> Only the administrator who opened this dashboard can change Minecraft setup.",
                        error=True,
                    )
                ),
                ephemeral=True,
            )
            return
        updates = {
            "java_address": str(self.java_address),
            "bedrock_address": str(self.bedrock_address),
            "bedrock_port": str(self.bedrock_port),
        }
        try:
            self.setup_view.bot.settings.with_updates(**updates)
        except ValueError as exc:
            await interaction.response.send_message(
                **branded_send(info_embed("Addresses Not Updated", f"> {exc}", error=True)),
                ephemeral=True,
            )
            return
        await interaction.response.defer(ephemeral=True)
        await self.setup_view.bot.update_settings(actor_id=interaction.user.id, **updates)
        with suppress(discord.HTTPException):
            await self.source_interaction.edit_original_response(
                view=MinecraftSetupView(
                    self.setup_view.bot,
                    self.setup_view.requester_id,
                    interaction.guild,
                )
            )
        await interaction.edit_original_response(
            **branded_edit(
                info_embed(
                    "Addresses Updated",
                    "> The Java and Bedrock connection addresses were validated and saved.\n\n"
                    "New applicant instructions will use these values immediately.",
                    success=True,
                )
            )
        )

    async def on_error(self, interaction: discord.Interaction, error: Exception) -> None:
        await _report_internal_error(interaction, error)


class MinecraftSetupAction(discord.ui.Button):
    def __init__(
        self,
        action: str,
        label: str,
        style: discord.ButtonStyle,
        *,
        disabled: bool = False,
    ) -> None:
        self.action = action
        super().__init__(
            label=label,
            style=style,
            custom_id=f"minecraft:setup:action:{action}",
            disabled=disabled,
        )

    async def callback(self, interaction: discord.Interaction) -> None:
        view = self.view
        if not isinstance(view, MinecraftSetupView):
            return
        if self.action == "addresses":
            await interaction.response.send_modal(MinecraftAddressModal(interaction, view))
            return
        if self.action == "refresh":
            await interaction.response.edit_message(
                view=MinecraftSetupView(view.bot, view.requester_id, interaction.guild)
            )
            return

        findings = configuration_findings(view.bot, interaction.guild)
        if findings:
            details = "\n".join(f"**{item.setting}:** {item.detail}" for item in findings[:8])
            await interaction.response.send_message(
                **branded_send(
                    info_embed(
                        "Setup Needs Attention",
                        "> Resolve the findings below before posting the application panel.\n\n"
                        + details,
                        error=True,
                    )
                ),
                ephemeral=True,
            )
            return
        await interaction.response.defer()
        try:
            message = await view.bot.post_application_panel()
        except RuntimeError as exc:
            await interaction.edit_original_response(
                **branded_edit(info_embed("Panel Not Posted", f"> {exc}", error=True)),
                view=None,
            )
            return
        await interaction.edit_original_response(
            **branded_edit(
                info_embed(
                    "Application Panel Ready",
                    f"> The latest application panel is available in {message.channel.mention}.\n\n"
                    "Applicants can begin the Discord and Minecraft verification flow immediately.",
                    success=True,
                )
            ),
            view=None,
        )


class MinecraftSetupView(discord.ui.LayoutView):
    def __init__(self, bot, requester_id: int, guild: Optional[discord.Guild]) -> None:
        super().__init__(timeout=900)
        self.bot = bot
        self.requester_id = int(requester_id)
        settings = bot.settings
        findings = configuration_findings(bot, guild)
        state = "Ready" if not findings else "Needs attention"
        bridge_state = "Connected" if bot.bridge.connected else "Offline"

        container = discord.ui.Container(accent_colour=THEME_COLOUR)
        container.add_item(discord.ui.TextDisplay("## Minecraft Application Setup"))
        container.add_item(
            discord.ui.TextDisplay(
                "Configure the Discord-facing application system here. Changes save immediately "
                "to the dedicated Minecraft database."
            )
        )
        container.add_item(discord.ui.Separator())
        container.add_item(
            discord.ui.TextDisplay(
                f"**Status:** {state}\n"
                f"**Bridge:** {bridge_state}\n"
                f"**Application channel:** {_channel_value(guild, settings.application_channel_id)}\n"
                f"**Review channel:** {_channel_value(guild, settings.review_channel_id)}\n"
                f"**Application log:** {_channel_value(guild, settings.application_log_channel_id)}\n"
                f"**Verification log:** {_channel_value(guild, settings.verification_log_channel_id)}\n"
                f"**Player activity log:** {_channel_value(guild, settings.player_log_channel_id)}\n"
                f"**Command log:** {_channel_value(guild, settings.command_log_channel_id)}\n"
                f"**Important command log:** {_channel_value(guild, settings.critical_log_channel_id)}\n"
                f"**Moderator role:** {_role_value(guild, settings.mod_role_id)}\n"
                f"**Approved-member role:** {_role_value(guild, settings.member_role_id)}"
            )
        )
        if findings:
            container.add_item(
                discord.ui.TextDisplay(
                    "**Validation**\n"
                    + "\n".join(f"- **{item.setting}:** {item.detail}" for item in findings[:6])
                )
            )
        container.add_item(discord.ui.Separator())

        for item in (
            MinecraftChannelSelect("application_channel_id", "Application channel"),
            MinecraftChannelSelect("review_channel_id", "Review channel"),
            MinecraftChannelSelect("application_log_channel_id", "Application log"),
            MinecraftChannelSelect("verification_log_channel_id", "Verification log"),
            MinecraftChannelSelect("player_log_channel_id", "Player activity log"),
            MinecraftRoleSelect("mod_role_id", "Moderator role"),
            MinecraftRoleSelect("member_role_id", "Approved-member role"),
        ):
            row = discord.ui.ActionRow()
            row.add_item(item)
            container.add_item(row)

        container.add_item(discord.ui.Separator())
        container.add_item(
            discord.ui.TextDisplay(
                "**Java server address**\n"
                f"```text\n{settings.java_address}\n```\n"
                "**Bedrock server address**\n"
                f"```text\n{settings.bedrock_address}\n```\n"
                "**Bedrock port**\n"
                f"```text\n{settings.bedrock_port}\n```\n"
                "The Discord token, guild ID, bridge secret, server ID, bind address, and data path remain in the environment."
            )
        )
        actions = discord.ui.ActionRow()
        actions.add_item(
            MinecraftSetupAction("addresses", "Edit Addresses", discord.ButtonStyle.primary)
        )
        actions.add_item(
            MinecraftSetupAction(
                "post",
                "Post Application Panel",
                discord.ButtonStyle.success,
                disabled=bool(findings),
            )
        )
        actions.add_item(MinecraftSetupAction("refresh", "Refresh", discord.ButtonStyle.secondary))
        container.add_item(actions)
        container.add_item(discord.ui.Separator(visible=False))
        container.add_item(discord.ui.TextDisplay(f"-# {BRAND_NAME} — Minecraft access control"))
        self.add_item(container)

    async def interaction_check(self, interaction: discord.Interaction) -> bool:
        if interaction.user.id != self.requester_id:
            await interaction.response.send_message(
                **branded_send(
                    info_embed(
                        "Setup Dashboard Unavailable",
                        "> This setup dashboard belongs to another administrator.",
                        error=True,
                    )
                ),
                ephemeral=True,
            )
            return False
        if not self.bot.is_administrator(interaction.user):
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
            return False
        return True

    async def on_error(
        self,
        interaction: discord.Interaction,
        error: Exception,
        _item: discord.ui.Item,
    ) -> None:
        await _report_internal_error(interaction, error)
