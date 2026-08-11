"""Unified Discord-native settings and permission-aware help surfaces."""
from __future__ import annotations

import copy
import io
import json
from pathlib import Path
from typing import Optional

import discord
from discord import app_commands
from discord.ext import commands

from core.actions import ActionSpec, get_action_spec, search_actions
from core.constants import SCOPE_SYSTEM
from core.context import bot, tree
from core.responding import InteractionResponder
from core.services import (
    ConfigImportError,
    export_config_payload,
    get_feature_flag,
    has_capability,
    import_config_payload,
    validate_config_import_payload,
    validate_guild_configuration,
)
from core.utils import truncate_text
from .shared import (
    format_user_ref,
    make_confirmation_embed,
    make_embed,
    panel_container,
    respond_with_error,
    send_log,
)


SETTINGS_SECTIONS = (
    ("overview", "Overview", "Overall readiness, backups, and configuration checks."),
    ("roles", "Roles", "Owner, administrator, moderator, and role-placement settings."),
    ("channels", "Channels", "Logs, appeals, AutoMod, archive, and support channels."),
    ("moderation", "Moderation", "Punishment reasons, durations, and escalation behavior."),
    ("automod", "AutoMod", "Native AutoMod follow-up and scam-image protection."),
    ("modmail", "Modmail", "Support inbox, panel, action logs, and response behavior."),
    ("custom_roles", "Custom Roles", "Custom-role eligibility, lists, and limits."),
    ("security", "Security", "Anti-nuke protection and moderation access."),
    ("branding", "Branding", "Bot profile and server-specific appearance."),
)
_SECTION_LABELS = dict((key, label) for key, label, _description in SETTINGS_SECTIONS)
_FINDING_SECTIONS = {
    "roles": "roles",
    "channels": "channels",
    "permissions": "overview",
    "moderation": "moderation",
    "automod": "automod",
    "modmail": "modmail",
    "security": "security",
    "branding": "branding",
}
_OPTIONAL_SECTIONS = {"custom_roles", "branding"}
_IMPORT_MAX_BYTES = 256 * 1024


def _can_use_spec(interaction: discord.Interaction, spec: ActionSpec) -> bool:
    if not spec.capability:
        return True
    member = interaction.user
    if not isinstance(member, discord.Member):
        return False
    return has_capability(
        [role.id for role in member.roles],
        spec.capability,
        bot.data_manager.config,
        administrator=member.guild_permissions.administrator,
        user_id=member.id,
        guild_owner_id=interaction.guild.owner_id if interaction.guild else None,
    )


def available_actions(interaction: discord.Interaction, query: str = "") -> list[ActionSpec]:
    flags = bot.data_manager.config.get("feature_flags", {})
    return [spec for spec in search_actions(query, enabled_flags=flags) if _can_use_spec(interaction, spec)]


def _configuration_findings(guild: Optional[discord.Guild]):
    if guild is None or bot.user is None:
        return []
    me = guild.me or guild.get_member(bot.user.id)
    if me is None:
        return []
    return validate_guild_configuration(bot.data_manager.config, guild, me)


def settings_section_states(guild: Optional[discord.Guild]) -> dict[str, str]:
    findings = _configuration_findings(guild)
    affected = {
        _FINDING_SECTIONS.get(str(finding.section).casefold(), "overview")
        for finding in findings
        if finding.level in {"error", "warning"}
    }
    states = {}
    for key, _label, _description in SETTINGS_SECTIONS:
        if key in affected or (key == "overview" and findings):
            states[key] = "Needs attention"
        elif key in _OPTIONAL_SECTIONS:
            states[key] = "Optional"
        else:
            states[key] = "Ready"
    return states


def _section_findings(section: str, guild: Optional[discord.Guild]) -> list:
    findings = _configuration_findings(guild)
    if section == "overview":
        return findings
    return [
        finding for finding in findings
        if _FINDING_SECTIONS.get(str(finding.section).casefold(), "overview") == section
    ]


class SettingsSectionSelect(discord.ui.Select):
    def __init__(self, view: "SettingsHubView") -> None:
        self.hub = view
        states = settings_section_states(view.guild)
        options = [
            discord.SelectOption(
                label=label,
                value=key,
                description=truncate_text(f"{states[key]} — {description}", 100),
                default=key == view.section,
            )
            for key, label, description in SETTINGS_SECTIONS
        ]
        super().__init__(placeholder="Choose a settings section...", options=options, min_values=1, max_values=1)

    async def callback(self, interaction: discord.Interaction) -> None:
        next_section = self.values[0]
        history = [*self.hub.history]
        if next_section != self.hub.section:
            history.append(self.hub.section)
        await interaction.response.edit_message(
            view=SettingsHubView(
                interaction.guild,
                requester_id=self.hub.requester_id,
                section=next_section,
                history=history[-8:],
            )
        )


class SettingsNavigation(discord.ui.ActionRow):
    def __init__(self, hub: "SettingsHubView") -> None:
        super().__init__()
        self.hub = hub
        self.add_item(SettingsBackButton(hub))
        self.add_item(SettingsHomeButton(hub))
        self.add_item(SettingsEditorButton(hub))


class SettingsBackButton(discord.ui.Button):
    def __init__(self, hub: "SettingsHubView") -> None:
        super().__init__(label="Back", style=discord.ButtonStyle.secondary, disabled=not hub.history)
        self.hub = hub

    async def callback(self, interaction: discord.Interaction) -> None:
        history = [*self.hub.history]
        section = history.pop() if history else "overview"
        await interaction.response.edit_message(
            view=SettingsHubView(interaction.guild, requester_id=self.hub.requester_id, section=section, history=history)
        )


class SettingsHomeButton(discord.ui.Button):
    def __init__(self, hub: "SettingsHubView") -> None:
        super().__init__(label="Home", style=discord.ButtonStyle.secondary, disabled=hub.section == "overview")
        self.hub = hub

    async def callback(self, interaction: discord.Interaction) -> None:
        await interaction.response.edit_message(
            view=SettingsHubView(interaction.guild, requester_id=self.hub.requester_id, section="overview")
        )


class SettingsEditorButton(discord.ui.Button):
    def __init__(self, hub: "SettingsHubView") -> None:
        super().__init__(label="Open Editor", style=discord.ButtonStyle.primary)
        self.hub = hub

    async def callback(self, interaction: discord.Interaction) -> None:
        if self.hub.section == "branding" and not _can_use_spec(interaction, get_action_spec("branding server")):
            await respond_with_error(interaction, "Only the configured owner role can edit bot branding.", scope=SCOPE_SYSTEM)
            return
        await interaction.response.edit_message(
            view=SettingsEditorView(
                interaction.guild,
                requester_id=self.hub.requester_id,
                section=self.hub.section,
                history=self.hub.history,
            )
        )


_ROLE_FIELDS = (
    ("role_owner", "Owner Role"),
    ("role_admin", "Admin Role"),
    ("role_mod", "Mod Role"),
    ("role_community_manager", "Community Manager"),
    ("role_anchor", "Custom Role Anchor"),
)
_CHANNEL_FIELDS = (
    ("general_log_channel_id", "General Bot Log"),
    ("punishment_log_channel_id", "Punishment Log"),
    ("appeal_channel_id", "Appeal Log"),
    ("automod_log_channel_id", "AutoMod Log"),
    ("automod_report_channel_id", "AutoMod Reports"),
    ("category_archive", "Archive Category"),
    ("modmail_inbox_channel", "Modmail Inbox"),
    ("modmail_action_log_channel", "Modmail Action Log"),
    ("modmail_panel_channel", "Modmail Panel"),
)


class SettingsEditorReturnButton(discord.ui.Button):
    def __init__(self, editor: "SettingsEditorView", *, home: bool = False) -> None:
        super().__init__(label="Home" if home else "Back", style=discord.ButtonStyle.secondary)
        self.editor = editor
        self.home = home

    async def callback(self, interaction: discord.Interaction) -> None:
        section = "overview" if self.home else self.editor.section
        await interaction.response.edit_message(
            view=SettingsHubView(
                interaction.guild,
                requester_id=self.editor.requester_id,
                section=section,
                history=self.editor.history,
            )
        )


class SettingsFieldSelect(discord.ui.Select):
    def __init__(self, editor: "SettingsEditorView", category: str) -> None:
        self.editor = editor
        self.category = category
        fields = _ROLE_FIELDS if category == "roles" else _CHANNEL_FIELDS
        super().__init__(
            placeholder=f"Choose a {category[:-1]} setting...",
            options=[discord.SelectOption(label=label, value=key) for key, label in fields],
        )

    async def callback(self, interaction: discord.Interaction) -> None:
        key = self.values[0]
        fields = dict(_ROLE_FIELDS if self.category == "roles" else _CHANNEL_FIELDS)
        await interaction.response.edit_message(
            view=SettingsValueEditorView(
                interaction.guild,
                requester_id=self.editor.requester_id,
                section=self.editor.section,
                history=self.editor.history,
                field_key=key,
                field_label=fields[key],
                role_field=self.category == "roles",
            )
        )


class SettingsRoleValueSelect(discord.ui.RoleSelect):
    def __init__(self, editor: "SettingsValueEditorView") -> None:
        super().__init__(placeholder=f"Select {editor.field_label}...", min_values=1, max_values=1)
        self.editor = editor

    async def callback(self, interaction: discord.Interaction) -> None:
        await InteractionResponder(interaction).defer(ephemeral=True)
        await bot.data_manager.set_config_values(**{self.editor.field_key: self.values[0].id})
        await interaction.edit_original_response(
            view=SettingsEditorView(
                interaction.guild,
                requester_id=self.editor.requester_id,
                section=self.editor.section,
                history=self.editor.history,
            )
        )


class SettingsChannelValueSelect(discord.ui.ChannelSelect):
    def __init__(self, editor: "SettingsValueEditorView") -> None:
        channel_types = [discord.ChannelType.category] if "category" in editor.field_key else [discord.ChannelType.text]
        super().__init__(
            placeholder=f"Select {editor.field_label}...",
            min_values=1,
            max_values=1,
            channel_types=channel_types,
        )
        self.editor = editor

    async def callback(self, interaction: discord.Interaction) -> None:
        await InteractionResponder(interaction).defer(ephemeral=True)
        selected = self.values[0]
        channel = interaction.guild.get_channel(selected.id) or await interaction.guild.fetch_channel(selected.id)
        values = {self.editor.field_key: channel.id}
        if self.editor.field_key == "general_log_channel_id":
            values["log_channel_id"] = channel.id
        await bot.data_manager.set_config_values(**values)
        await interaction.edit_original_response(
            view=SettingsEditorView(
                interaction.guild,
                requester_id=self.editor.requester_id,
                section=self.editor.section,
                history=self.editor.history,
            )
        )


class SettingsAutoModSelect(discord.ui.Select):
    def __init__(self, editor: "SettingsEditorView") -> None:
        self.editor = editor
        options = [
            discord.SelectOption(label="Toggle Bot Responses", value="native:enabled"),
            discord.SelectOption(label="Toggle Warning DMs", value="native:warning_dm_enabled"),
            discord.SelectOption(label="Toggle Report Button", value="native:report_button_enabled"),
            discord.SelectOption(label="Toggle Scam Filter", value="image:enabled"),
            discord.SelectOption(label="Toggle Scam Deletion", value="image:delete_message"),
            discord.SelectOption(label="Toggle Scam Logs", value="image:log_detections"),
            discord.SelectOption(label="Toggle Scam Punishment", value="image:punish"),
        ]
        super().__init__(placeholder="Choose an AutoMod setting to toggle...", options=options)

    async def callback(self, interaction: discord.Interaction) -> None:
        from .automod import get_image_filter_settings, store_image_filter_settings, store_native_automod_settings
        from core.services import get_native_automod_settings

        await InteractionResponder(interaction).defer(ephemeral=True)
        family, key = self.values[0].split(":", 1)
        if family == "native":
            settings = get_native_automod_settings(bot.data_manager.config)
            settings[key] = not bool(settings.get(key, True))
            await store_native_automod_settings(settings)
        else:
            settings = get_image_filter_settings()
            settings[key] = not bool(settings.get(key, False))
            await store_image_filter_settings(settings)
        await interaction.edit_original_response(
            view=SettingsEditorView(
                interaction.guild,
                requester_id=self.editor.requester_id,
                section="automod",
                history=self.editor.history,
            )
        )


class SettingsSecurityUserSelect(discord.ui.UserSelect):
    def __init__(self, editor: "SettingsEditorView") -> None:
        super().__init__(placeholder="Toggle anti-nuke immunity for a member...", min_values=1, max_values=1)
        self.editor = editor

    async def callback(self, interaction: discord.Interaction) -> None:
        await InteractionResponder(interaction).defer(ephemeral=True)
        user_id = str(self.values[0].id)

        def toggle(config):
            immunity = config.setdefault("immunity_list", [])
            if user_id in immunity:
                immunity.remove(user_id)
            else:
                immunity.append(user_id)

        await bot.data_manager.mutate_config(toggle)
        await interaction.edit_original_response(
            view=SettingsEditorView(
                interaction.guild,
                requester_id=self.editor.requester_id,
                section="security",
                history=self.editor.history,
            )
        )


class SettingsAccessRoleSelect(discord.ui.RoleSelect):
    def __init__(self, editor: "SettingsEditorView") -> None:
        super().__init__(placeholder="Toggle a moderation access role...", min_values=1, max_values=1)
        self.editor = editor

    async def callback(self, interaction: discord.Interaction) -> None:
        if not _can_use_spec(interaction, get_action_spec("access")):
            await respond_with_error(
                interaction,
                "Only the configured owner role can change moderation access.",
                scope=SCOPE_SYSTEM,
            )
            return
        await InteractionResponder(interaction).defer(ephemeral=True)
        role_id = self.values[0].id

        def toggle(config):
            roles = config.setdefault("mod_roles", [])
            if role_id in roles:
                roles.remove(role_id)
                action["value"] = "Removed from"
            else:
                roles.append(role_id)
                action["value"] = "Added to"

        action = {}
        await bot.data_manager.mutate_config(toggle)
        log_embed = make_embed(
            "Moderator Access Updated",
            "> The list of roles with moderation access was changed.",
            kind="info",
            scope=SCOPE_SYSTEM,
            guild=interaction.guild,
        )
        log_embed.add_field(name="Actor", value=format_user_ref(interaction.user), inline=True)
        log_embed.add_field(name="Role", value=f"<@&{role_id}> (`{role_id}`)", inline=True)
        log_embed.add_field(name="Action", value=action["value"], inline=True)
        await send_log(interaction.guild, log_embed)
        await interaction.edit_original_response(
            view=SettingsEditorView(
                interaction.guild,
                requester_id=self.editor.requester_id,
                section="security",
                history=self.editor.history,
            )
        )


class SettingsValueEditorView(discord.ui.LayoutView):
    def __init__(
        self,
        guild: Optional[discord.Guild],
        *,
        requester_id: int,
        section: str,
        history: list[str],
        field_key: str,
        field_label: str,
        role_field: bool,
    ) -> None:
        super().__init__(timeout=900)
        self.requester_id = requester_id
        self.section = section
        self.history = list(history)
        self.field_key = field_key
        self.field_label = field_label
        container = panel_container(f"Configure {field_label}", f"> Choose the new **{field_label}** below.", guild=guild)
        row = discord.ui.ActionRow()
        row.add_item(SettingsRoleValueSelect(self) if role_field else SettingsChannelValueSelect(self))
        container.add_item(row)
        nav = discord.ui.ActionRow()
        nav.add_item(SettingsEditorReturnButton(self))
        nav.add_item(SettingsEditorReturnButton(self, home=True))
        container.add_item(nav)
        self.add_item(container)

    async def interaction_check(self, interaction: discord.Interaction) -> bool:
        if interaction.user.id == self.requester_id:
            return True
        await interaction.response.send_message(
            "This private settings session belongs to another user.",
            ephemeral=True,
        )
        return False


class SettingsEditorView(discord.ui.LayoutView):
    def __init__(self, guild: Optional[discord.Guild], *, requester_id: int, section: str, history: list[str]) -> None:
        super().__init__(timeout=900)
        self.requester_id = requester_id
        self.section = section
        self.history = list(history)
        title = f"{_SECTION_LABELS.get(section, 'Settings')} Editor"
        container = panel_container(title, "> Changes save immediately and this panel stays in one message.", guild=guild)

        if section in {"roles", "channels", "modmail"}:
            row = discord.ui.ActionRow()
            row.add_item(SettingsFieldSelect(self, "roles" if section == "roles" else "channels"))
            container.add_item(row)
        elif section == "moderation":
            from .case_panel import RulesDashboardButtons
            container.add_item(discord.ui.TextDisplay(f"**Configured rules** · {len(bot.data_manager.config.get('punishment_rules', {}))}"))
            container.add_item(RulesDashboardButtons())
        elif section == "automod":
            from .automod import get_image_filter_settings
            from core.services import get_native_automod_settings
            native = get_native_automod_settings(bot.data_manager.config)
            images = get_image_filter_settings()
            container.add_item(discord.ui.TextDisplay(
                f"**Responses:** {'On' if native.get('enabled') else 'Off'} · "
                f"**Warning DMs:** {'On' if native.get('warning_dm_enabled') else 'Off'} · "
                f"**Scam filter:** {'On' if images.get('enabled') else 'Off'}"
            ))
            row = discord.ui.ActionRow()
            row.add_item(SettingsAutoModSelect(self))
            container.add_item(row)
        elif section == "custom_roles":
            from .roles import RoleSettingsActionSelect
            row = discord.ui.ActionRow()
            row.add_item(RoleSettingsActionSelect())
            container.add_item(row)
        elif section == "security":
            row = discord.ui.ActionRow()
            row.add_item(SettingsSecurityUserSelect(self))
            container.add_item(row)
            access_row = discord.ui.ActionRow()
            access_row.add_item(SettingsAccessRoleSelect(self))
            container.add_item(access_row)
        elif section == "branding":
            from .admin import GlobalBrandingActionSelect, ServerBrandingActionSelect
            global_row = discord.ui.ActionRow()
            global_row.add_item(GlobalBrandingActionSelect())
            container.add_item(global_row)
            server_row = discord.ui.ActionRow()
            server_row.add_item(ServerBrandingActionSelect(guild))
            container.add_item(server_row)
        else:
            container.add_item(discord.ui.TextDisplay("Use the section selector to choose a focused editor."))

        container.add_item(discord.ui.Separator())
        nav = discord.ui.ActionRow()
        nav.add_item(SettingsEditorReturnButton(self))
        nav.add_item(SettingsEditorReturnButton(self, home=True))
        container.add_item(nav)
        self.add_item(container)

    async def interaction_check(self, interaction: discord.Interaction) -> bool:
        if interaction.user.id == self.requester_id:
            return True
        await interaction.response.send_message("This private settings session belongs to another user.", ephemeral=True)
        return False


class SettingsDataActions(discord.ui.ActionRow):
    def __init__(self, hub: "SettingsHubView") -> None:
        super().__init__()
        self.add_item(SettingsExportButton())
        self.add_item(SettingsRollbackButton(hub.requester_id))


class SettingsSectionActions(discord.ui.ActionRow):
    def __init__(self, section: str) -> None:
        super().__init__()
        if section == "modmail":
            self.add_item(SettingsModmailPanelButton())


class SettingsModmailPanelButton(discord.ui.Button):
    def __init__(self) -> None:
        super().__init__(label="Send Support Panel", style=discord.ButtonStyle.secondary)

    async def callback(self, interaction: discord.Interaction) -> None:
        from .config import send_configured_modmail_panel
        await send_configured_modmail_panel(interaction)


class SettingsExportButton(discord.ui.Button):
    def __init__(self) -> None:
        super().__init__(label="Download Backup", style=discord.ButtonStyle.secondary)

    async def callback(self, interaction: discord.Interaction) -> None:
        payload = export_config_payload(bot.data_manager.config)
        buffer = io.BytesIO(json.dumps(payload, indent=2, ensure_ascii=False).encode("utf-8"))
        await interaction.response.send_message(
            embed=make_confirmation_embed("Settings Backup Ready", "> Persistent settings were exported.", scope=SCOPE_SYSTEM, guild=interaction.guild),
            file=discord.File(buffer, filename="mbx-settings.json"),
            ephemeral=True,
        )


class SettingsRollbackButton(discord.ui.Button):
    def __init__(self, requester_id: int) -> None:
        super().__init__(label="Rollback Latest", style=discord.ButtonStyle.danger)
        self.requester_id = requester_id

    async def callback(self, interaction: discord.Interaction) -> None:
        backups = bot.data_manager.list_config_backups()
        if not backups:
            await respond_with_error(interaction, "No settings backup is available yet.", scope=SCOPE_SYSTEM)
            return
        await interaction.response.send_message(
            embed=make_embed("Confirm Settings Rollback", "> Restore the newest saved settings backup? The current settings will be backed up first.", kind="warning", scope=SCOPE_SYSTEM, guild=interaction.guild),
            view=ConfigRollbackConfirmView(self.requester_id, backups[0]),
            ephemeral=True,
        )


class SettingsHubView(discord.ui.LayoutView):
    def __init__(
        self,
        guild: Optional[discord.Guild],
        *,
        requester_id: int,
        section: str = "overview",
        history: Optional[list[str]] = None,
    ) -> None:
        super().__init__(timeout=900)
        self.guild = guild
        self.requester_id = requester_id
        self.section = section if section in _SECTION_LABELS else "overview"
        self.history = list(history or [])
        states = settings_section_states(guild)
        findings = _section_findings(self.section, guild)

        title = "Server Settings" if self.section == "overview" else _SECTION_LABELS[self.section]
        lines = [f"> Status: **{states[self.section]}**"]
        if self.section == "overview":
            lines.extend(
                f"**{label}** · {states[key]}" for key, label, _description in SETTINGS_SECTIONS[1:]
            )
            lines.extend(("", "-# Import a backup with `/settings import_file:<attachment>`."))
        else:
            description = next(item[2] for item in SETTINGS_SECTIONS if item[0] == self.section)
            lines.extend((f"> {description}", ""))
            if findings:
                lines.append("**Validation findings**")
                lines.extend(f"- {finding.message}" for finding in findings[:8])
                lines.append("-# Use Open Editor below to correct these settings.")
            else:
                lines.append("No validation problems were found in this section.")

        container = panel_container(title, "\n".join(lines), guild=guild)
        container.add_item(discord.ui.Separator())
        selection = discord.ui.ActionRow()
        selection.add_item(SettingsSectionSelect(self))
        container.add_item(selection)
        container.add_item(SettingsNavigation(self))
        if self.section == "overview":
            container.add_item(SettingsDataActions(self))
        elif self.section == "modmail":
            container.add_item(SettingsSectionActions(self.section))
        self.add_item(container)

    async def interaction_check(self, interaction: discord.Interaction) -> bool:
        if interaction.user.id == self.requester_id:
            return True
        await interaction.response.send_message("This private settings session belongs to another user.", ephemeral=True)
        return False


def _preview_value(key: str, value) -> str:
    lowered = key.casefold()
    if any(part in lowered for part in ("token", "secret", "password", "credential", "webhook")):
        return "[redacted]"
    if isinstance(value, dict):
        return f"object ({len(value)} entries)"
    if isinstance(value, list):
        return f"list ({len(value)} entries)"
    return truncate_text(repr(value), 80)


def config_change_preview(current: dict, merged: dict) -> tuple[list[str], set[str]]:
    changed = {key for key in set(current) | set(merged) if current.get(key) != merged.get(key)}
    lines = [
        f"- `{key}`: {_preview_value(key, current.get(key))} → {_preview_value(key, merged.get(key))}"
        for key in sorted(changed)
    ]
    return lines, changed


class ConfigImportConfirmView(discord.ui.View):
    def __init__(self, requester_id: int, merged: dict, changed_keys: set[str]) -> None:
        super().__init__(timeout=300)
        self.requester_id = requester_id
        self.merged = copy.deepcopy(merged)
        self.changed_keys = set(changed_keys)

    async def interaction_check(self, interaction: discord.Interaction) -> bool:
        if interaction.user.id == self.requester_id:
            return True
        await interaction.response.send_message("This import confirmation belongs to another user.", ephemeral=True)
        return False

    @discord.ui.button(label="Import Settings", style=discord.ButtonStyle.danger)
    async def confirm(self, interaction: discord.Interaction, button: discord.ui.Button) -> None:
        await interaction.response.defer(ephemeral=True, thinking=True)
        await bot.data_manager.create_config_backup()
        await bot.data_manager.replace_config(self.merged)
        self.stop()
        await interaction.edit_original_response(
            embed=make_confirmation_embed("Settings Imported", f"> Imported **{len(self.changed_keys)}** changed setting(s). A rollback backup was retained.", scope=SCOPE_SYSTEM, guild=interaction.guild),
            view=None,
        )

    @discord.ui.button(label="Cancel", style=discord.ButtonStyle.secondary)
    async def cancel(self, interaction: discord.Interaction, button: discord.ui.Button) -> None:
        self.stop()
        await interaction.response.edit_message(
            embed=make_embed("Import Cancelled", "> No settings were changed.", kind="muted", scope=SCOPE_SYSTEM, guild=interaction.guild),
            view=None,
        )


class ConfigRollbackConfirmView(discord.ui.View):
    def __init__(self, requester_id: int, backup_path: Path) -> None:
        super().__init__(timeout=180)
        self.requester_id = requester_id
        self.backup_path = backup_path

    async def interaction_check(self, interaction: discord.Interaction) -> bool:
        if interaction.user.id == self.requester_id:
            return True
        await interaction.response.send_message("This rollback confirmation belongs to another user.", ephemeral=True)
        return False

    @discord.ui.button(label="Restore Backup", style=discord.ButtonStyle.danger)
    async def confirm(self, interaction: discord.Interaction, button: discord.ui.Button) -> None:
        await interaction.response.defer(ephemeral=True, thinking=True)
        await bot.data_manager.create_config_backup()
        restored = await bot.data_manager.rollback_config_backup(self.backup_path)
        self.stop()
        await interaction.edit_original_response(
            embed=make_confirmation_embed("Settings Restored", f"> Restored `{restored.name}`. The replaced settings were backed up.", scope=SCOPE_SYSTEM, guild=interaction.guild),
            view=None,
        )


class HelpCategorySelect(discord.ui.Select):
    def __init__(self, view: "HelpCatalogView") -> None:
        self.catalog = view
        categories = sorted({spec.category for spec in view.all_specs})
        options = [discord.SelectOption(label="All Commands", value="all", default=view.category == "all")]
        options.extend(discord.SelectOption(label=category, value=category, default=view.category == category) for category in categories)
        super().__init__(placeholder="Filter commands by category...", options=options[:25], min_values=1, max_values=1)

    async def callback(self, interaction: discord.Interaction) -> None:
        await interaction.response.edit_message(
            view=HelpCatalogView(self.catalog.all_specs, requester_id=self.catalog.requester_id, category=self.values[0], page=0)
        )


class HelpNavigation(discord.ui.ActionRow):
    def __init__(self, view: "HelpCatalogView", pages: int) -> None:
        super().__init__()
        self.add_item(HelpPageButton(view, -1, "Previous", view.page <= 0))
        self.add_item(HelpPageButton(view, 1, "Next", view.page >= pages - 1))


class HelpPageButton(discord.ui.Button):
    def __init__(self, catalog: "HelpCatalogView", delta: int, label: str, disabled: bool) -> None:
        super().__init__(label=label, style=discord.ButtonStyle.secondary, disabled=disabled)
        self.catalog = catalog
        self.delta = delta

    async def callback(self, interaction: discord.Interaction) -> None:
        await interaction.response.edit_message(
            view=HelpCatalogView(
                self.catalog.all_specs,
                requester_id=self.catalog.requester_id,
                category=self.catalog.category,
                page=self.catalog.page + self.delta,
            )
        )


class HelpCatalogView(discord.ui.LayoutView):
    PAGE_SIZE = 8

    def __init__(self, specs: list[ActionSpec], *, requester_id: int, category: str = "all", page: int = 0) -> None:
        super().__init__(timeout=600)
        self.all_specs = list(specs)
        self.requester_id = requester_id
        self.category = category
        filtered = self.all_specs if category == "all" else [spec for spec in self.all_specs if spec.category == category]
        pages = max(1, (len(filtered) + self.PAGE_SIZE - 1) // self.PAGE_SIZE)
        self.page = max(0, min(page, pages - 1))
        visible = filtered[self.page * self.PAGE_SIZE:(self.page + 1) * self.PAGE_SIZE]
        lines = []
        for spec in visible:
            permission = ", ".join(spec.caller_permissions) if spec.caller_permissions else "Everyone"
            lines.append(f"**/{spec.command_name}**\n{spec.help_text}\n-# Access: {permission}")
        if not lines:
            lines.append("No commands are available in this category.")
        lines.append(f"-# Page {self.page + 1}/{pages} · {len(filtered)} commands available to you")
        container = panel_container("Command Help", "\n\n".join(lines), guild=None)
        select_row = discord.ui.ActionRow()
        select_row.add_item(HelpCategorySelect(self))
        container.add_item(discord.ui.Separator())
        container.add_item(select_row)
        container.add_item(HelpNavigation(self, pages))
        self.add_item(container)

    async def interaction_check(self, interaction: discord.Interaction) -> bool:
        if interaction.user.id == self.requester_id:
            return True
        await interaction.response.send_message("This private help session belongs to another user.", ephemeral=True)
        return False


def build_action_detail(spec: ActionSpec, guild: Optional[discord.Guild]) -> discord.Embed:
    embed = make_embed(f"/{spec.command_name}", f"> {spec.help_text}", kind="info", scope=SCOPE_SYSTEM, guild=guild)
    embed.add_field(name="Category", value=spec.category, inline=True)
    embed.add_field(name="Risk", value=spec.risk_level.value.replace("_", " ").title(), inline=True)
    embed.add_field(name="Acknowledgement", value=spec.acknowledgement_policy.value.title(), inline=True)
    embed.add_field(name="Your Access", value=", ".join(spec.caller_permissions) or "Everyone", inline=False)
    embed.add_field(name="Bot Permissions", value=", ".join(spec.bot_permissions) or "No special permissions", inline=False)
    if spec.examples:
        embed.add_field(name="Examples", value="\n".join(f"`{example}`" for example in spec.examples), inline=False)
    if spec.related_actions:
        embed.add_field(name="Related", value=" ".join(f"`/{name}`" for name in spec.related_actions), inline=False)
    return embed


async def send_settings_hub(interaction: discord.Interaction, section: str = "overview") -> None:
    await InteractionResponder(interaction).send(
        view=SettingsHubView(interaction.guild, requester_id=interaction.user.id, section=section),
        ephemeral=True,
    )


async def send_help_catalog(interaction: discord.Interaction, query: Optional[str] = None) -> None:
    normalized = str(query or "").strip()
    exact = get_action_spec(normalized) if normalized else None
    if exact is not None and _can_use_spec(interaction, exact):
        await interaction.response.send_message(embed=build_action_detail(exact, interaction.guild), ephemeral=True)
        return
    specs = available_actions(interaction, normalized)
    if normalized and not specs:
        await respond_with_error(interaction, "No command matching that search is available to you.", title="Command Not Found", scope=SCOPE_SYSTEM)
        return
    await interaction.response.send_message(
        view=HelpCatalogView(specs, requester_id=interaction.user.id), ephemeral=True
    )


async def _help_autocomplete(interaction: discord.Interaction, current: str):
    return [
        app_commands.Choice(name=f"/{spec.command_name} — {truncate_text(spec.help_text, 70)}", value=spec.command_name)
        for spec in available_actions(interaction, current)[:25]
    ]


@tree.command(name="settings", description="Open the unified server settings hub.")
@app_commands.describe(import_file="Optional JSON settings backup to validate and preview.")
async def settings_cmd(interaction: discord.Interaction, import_file: Optional[discord.Attachment] = None):
    if not get_feature_flag(bot.data_manager.config, "config_panel", True):
        await respond_with_error(interaction, "The bot settings panel is currently turned off.", scope=SCOPE_SYSTEM)
        return
    if import_file is None:
        await send_settings_hub(interaction)
        return
    if import_file.size > _IMPORT_MAX_BYTES:
        await respond_with_error(interaction, "The settings attachment must be 256 KiB or smaller.", scope=SCOPE_SYSTEM)
        return
    if not import_file.filename.casefold().endswith(".json"):
        await respond_with_error(interaction, "Attach a `.json` settings backup.", scope=SCOPE_SYSTEM)
        return
    await InteractionResponder(interaction).defer(ephemeral=True, thinking=True)
    try:
        raw = await import_file.read()
        payload = json.loads(raw.decode("utf-8"))
        errors = validate_config_import_payload(bot.data_manager.config, payload)
        if errors:
            raise ConfigImportError(" ".join(errors[:8]))
        merged, _warnings = import_config_payload(bot.data_manager.config, payload)
    except (UnicodeDecodeError, json.JSONDecodeError, ConfigImportError, ValueError) as exc:
        await interaction.followup.send(
            embed=make_embed("Invalid Settings Backup", f"> {truncate_text(str(exc), 1500)}", kind="error", scope=SCOPE_SYSTEM, guild=interaction.guild),
            ephemeral=True,
        )
        return
    preview, changed = config_change_preview(bot.data_manager.config, merged)
    if not changed:
        await interaction.followup.send(
            embed=make_embed("No Settings Changed", "> This backup matches the current persistent settings.", kind="info", scope=SCOPE_SYSTEM, guild=interaction.guild),
            ephemeral=True,
        )
        return
    description = "> Review the redacted change summary, then confirm.\n\n" + "\n".join(preview[:18])
    if len(preview) > 18:
        description += f"\n- …and {len(preview) - 18} more setting(s)"
    await interaction.followup.send(
        embed=make_embed("Review Settings Import", truncate_text(description, 4000), kind="warning", scope=SCOPE_SYSTEM, guild=interaction.guild),
        view=ConfigImportConfirmView(interaction.user.id, merged, changed),
        ephemeral=True,
    )


@tree.command(name="help", description="Search the command catalog and usage guidance.")
@app_commands.describe(query="Command name or topic to search for.")
@app_commands.autocomplete(query=_help_autocomplete)
async def help_cmd(interaction: discord.Interaction, query: Optional[str] = None):
    await send_help_catalog(interaction, query)


class ControlPlaneCog(commands.Cog):
    pass


async def setup(bot_instance: commands.Bot) -> None:
    await bot_instance.add_cog(ControlPlaneCog())
    bot_instance.tree.add_command(settings_cmd)
    bot_instance.tree.add_command(help_cmd)
