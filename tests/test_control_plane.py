import asyncio
import json
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

import discord

import core.data as data_module
from core.actions import (
    AcknowledgementPolicy,
    RiskLevel,
    authorize_interaction,
    get_action_spec,
    search_actions,
    validate_registered_actions,
)
from core.errors import BotPermissionError, CallerPermissionError, InvalidConfigurationError
from core.bot import EXTENSIONS, create_bot
from core.context import set_bot
from core.data import DataManager
from core.services import (
    export_config_payload,
    has_capability,
    import_config_payload,
    validate_config_import_payload,
)
from cogs.admin import AntiNukeResolveButton
from cogs.automod import AutoModReportResponseSelect, AutoModWarningReportButton
from cogs.control_plane import (
    SettingsEditorButton,
    SettingsEditorView,
    SettingsHubView,
    SettingsSectionSelect,
    config_change_preview,
    settings_section_states,
)
from cogs.event_leaderboard import EventHistorySelect
from cogs.modmail import ModmailActionButton, ModmailControlView, export_modmail_transcript


class ActionRegistryTests(unittest.TestCase):
    def test_registry_covers_the_production_command_tree(self):
        async def runner():
            runtime = create_bot()
            try:
                for extension in EXTENSIONS:
                    await runtime.load_extension(extension)
                runtime._remove_disabled_application_commands()
                undocumented, unavailable = validate_registered_actions(runtime.tree.walk_commands())
                self.assertEqual(undocumented, set())
                self.assertEqual(unavailable, set())
            finally:
                await runtime.close()

        asyncio.run(runner())

    def test_action_metadata_drives_help_and_risk(self):
        spec = get_action_spec("/punish")
        self.assertEqual(spec.capability, "punishments.issue")
        self.assertEqual(spec.acknowledgement_policy, AcknowledgementPolicy.DEFER)
        self.assertEqual(spec.risk_level, RiskLevel.DESTRUCTIVE)
        self.assertIn(spec, search_actions("punishment"))

    def test_registry_enforces_feature_caller_and_bot_permissions(self):
        class FakeMember:
            def __init__(self, role_ids, *, administrator=False, permissions=None):
                self.id = 10
                self.roles = [SimpleNamespace(id=role_id) for role_id in role_ids]
                self.guild_permissions = permissions or SimpleNamespace(
                    administrator=administrator,
                    manage_channels=False,
                )

        guild = SimpleNamespace(
            owner_id=99,
            me=FakeMember([], permissions=SimpleNamespace(administrator=False, manage_channels=True)),
            get_member=lambda _user_id: None,
        )
        interaction = SimpleNamespace(
            command=SimpleNamespace(qualified_name="lock"),
            data={"name": "lock"},
            type=discord.InteractionType.application_command,
            user=FakeMember([7]),
            guild=guild,
            client=SimpleNamespace(user=SimpleNamespace(id=500)),
        )
        config = {"role_mod": 7, "feature_flags": {}}
        with patch("core.actions.discord.Member", FakeMember):
            self.assertEqual(authorize_interaction(interaction, config).command_name, "lock")
            interaction.user = FakeMember([8])
            with self.assertRaises(CallerPermissionError):
                authorize_interaction(interaction, config)
            interaction.user = FakeMember([7])
            guild.me.guild_permissions.manage_channels = False
            with self.assertRaises(BotPermissionError):
                authorize_interaction(interaction, config)

            interaction.command = SimpleNamespace(qualified_name="settings")
            interaction.data = {"name": "settings"}
            with self.assertRaises(InvalidConfigurationError):
                authorize_interaction(
                    interaction,
                    {"role_admin": 7, "feature_flags": {"config_panel": False}},
                )


class CapabilityMigrationTests(unittest.TestCase):
    def test_existing_extra_staff_roles_inherit_granular_capabilities(self):
        config = {"mod_roles": [555]}
        for capability in (
            "cases.read",
            "punishments.issue",
            "punishments.undo",
            "messages.export",
            "messages.purge",
            "channels.lock",
        ):
            self.assertTrue(has_capability([555], capability, config))

    def test_unconfigured_role_does_not_gain_staff_capabilities(self):
        self.assertFalse(has_capability([999], "messages.purge", {"mod_roles": [555]}))


class ConfigurationImportTests(unittest.IsolatedAsyncioTestCase):
    def test_strict_attachment_validation_rejects_unknown_and_secret_keys(self):
        config = {"guild_id": 1, "feature_flags": {}}
        errors = validate_config_import_payload(
            config,
            {"guild_id": 2, "unknown_setting": True, "api_token": "secret"},
        )
        self.assertTrue(any("unknown_setting" in error for error in errors))
        self.assertTrue(any("api_token" in error for error in errors))

    def test_export_contains_only_portable_settings(self):
        payload = export_config_payload({
            "guild_id": 1,
            "case_counter": 99,
            "synced_command_fingerprint_1": "hash",
            "pending_antinuke_resolutions": {"token": {}},
            "webhook_secret": "secret",
        })
        self.assertEqual(payload["guild_id"], 1)
        self.assertNotIn("case_counter", payload)
        self.assertNotIn("synced_command_fingerprint_1", payload)
        self.assertNotIn("pending_antinuke_resolutions", payload)
        self.assertNotIn("webhook_secret", payload)

    def test_change_preview_redacts_secret_like_values(self):
        lines, changed = config_change_preview(
            {"guild_id": 1, "api_token": "before"},
            {"guild_id": 2, "api_token": "after"},
        )
        rendered = "\n".join(lines)
        self.assertEqual(changed, {"guild_id", "api_token"})
        self.assertIn("[redacted]", rendered)
        self.assertNotIn("before", rendered)
        self.assertNotIn("after", rendered)

    async def test_versioned_backup_and_rollback(self):
        with tempfile.TemporaryDirectory() as directory:
            fake_db = Path(directory) / "bot.db"
            manager = DataManager(SimpleNamespace(metrics=None))
            manager.config = {"schema_version": 3, "guild_id": 1}
            with patch.object(data_module, "DB_FILE", fake_db):
                backup = await manager.create_config_backup()
                self.assertTrue(backup.exists())
                manager.config = {"schema_version": 3, "guild_id": 2}
                async def replace_config(values):
                    manager.config = dict(values)

                manager.replace_config = AsyncMock(side_effect=replace_config)
                restored = await manager.rollback_config_backup(backup)
            self.assertEqual(restored, backup.resolve())
            self.assertEqual(manager.config["guild_id"], 1)
            manager.replace_config.assert_awaited_once()

    def test_legacy_import_helper_still_strips_secrets(self):
        merged, warnings = import_config_payload({}, {"bot_token": "x", "guild_id": 1})
        self.assertEqual(merged["guild_id"], 1)
        self.assertNotIn("bot_token", merged)
        self.assertTrue(warnings)


class DiscordControlPlaneTests(unittest.TestCase):
    def setUp(self):
        self.runtime = create_bot()
        set_bot(self.runtime)
        self.runtime.data_manager = SimpleNamespace(
            config={"feature_flags": {}},
            modmail={"42": {"status": "open", "assigned_moderator": None}},
            close=AsyncMock(),
        )

    def tearDown(self):
        asyncio.run(self.runtime.close())

    def test_settings_hub_is_components_v2_with_all_sections(self):
        guild = SimpleNamespace(id=1, icon=None, me=None, get_member=lambda _user_id: None)
        view = SettingsHubView(guild, requester_id=9)
        components = view.to_components()
        self.assertEqual(components[0]["type"], 17)
        rendered = json.dumps(components)
        for label in ("Overview", "Roles", "Channels", "Moderation", "AutoMod", "Modmail", "Custom Roles", "Security", "Branding"):
            self.assertIn(label, rendered)
        self.assertIn("Ready", settings_section_states(guild).values())

    def test_modmail_controls_use_ticket_specific_dynamic_ids(self):
        view = ModmailControlView("42")
        custom_ids = [child.item.custom_id for child in view.children]
        self.assertIn("mm_close:42", custom_ids)
        self.assertIn("mm_export:42", custom_ids)
        self.assertTrue(all(isinstance(child, ModmailActionButton) for child in view.children))

    def test_settings_navigation_edits_one_message(self):
        async def runner():
            guild = SimpleNamespace(id=1, icon=None, me=None, get_member=lambda _user_id: None)
            view = SettingsHubView(guild, requester_id=9)
            select = next(
                component
                for item in view.children[0].children
                if isinstance(item, discord.ui.ActionRow)
                for component in item.children
                if isinstance(component, SettingsSectionSelect)
            )
            select._values = ["roles"]
            interaction = SimpleNamespace(guild=guild, response=SimpleNamespace(edit_message=AsyncMock()))
            await select.callback(interaction)
            edited_view = interaction.response.edit_message.await_args.kwargs["view"]
            self.assertEqual(edited_view.section, "roles")
            self.assertEqual(edited_view.history, ["overview"])

        asyncio.run(runner())

    def test_settings_editor_replaces_the_hub_message(self):
        async def runner():
            guild = SimpleNamespace(id=1, icon=None, me=None, get_member=lambda _user_id: None)
            hub = SettingsHubView(guild, requester_id=9, section="roles")
            button = next(
                component
                for item in hub.children[0].children
                if isinstance(item, discord.ui.ActionRow)
                for component in item.children
                if isinstance(component, SettingsEditorButton)
            )
            interaction = SimpleNamespace(guild=guild, response=SimpleNamespace(edit_message=AsyncMock()))
            await button.callback(interaction)
            edited = interaction.response.edit_message.await_args.kwargs["view"]
            self.assertIsInstance(edited, SettingsEditorView)
            self.assertEqual(edited.section, "roles")
            self.assertEqual(interaction.response.edit_message.await_count, 1)

        asyncio.run(runner())

    def test_every_settings_section_has_same_message_navigation(self):
        async def runner():
            guild_member = SimpleNamespace(nick=None, guild_avatar=None, guild_banner=None)
            guild = SimpleNamespace(id=1, icon=None, me=guild_member, get_member=lambda _user_id: None)
            for section in (
                "overview", "roles", "channels", "moderation", "automod",
                "modmail", "custom_roles", "security", "branding",
            ):
                editor = SettingsEditorView(
                    guild,
                    requester_id=9,
                    section=section,
                    history=["overview"],
                )
                rendered = json.dumps(editor.to_components())
                self.assertIn("Back", rendered, section)
                self.assertIn("Home", rendered, section)

        asyncio.run(runner())

    def test_modmail_transcript_hides_staff_identity(self):
        class Thread:
            id = 123

            def history(self, **_kwargs):
                async def messages():
                    yield SimpleNamespace(
                        author=SimpleNamespace(id=42, display_name="Ticket User", display_avatar=SimpleNamespace(url="user.png")),
                        created_at=datetime.now(timezone.utc),
                        content="User message",
                        attachments=[],
                        edited_at=None,
                    )
                    yield SimpleNamespace(
                        author=SimpleNamespace(id=999999999999999, display_name="Private Moderator", display_avatar=SimpleNamespace(url="staff.png")),
                        created_at=datetime.now(timezone.utc),
                        content="Staff response from <@999999999999999>",
                        attachments=[],
                        edited_at=None,
                    )
                return messages()

        async def runner():
            file = await export_modmail_transcript(Thread(), "42")
            rendered = file.fp.getvalue().decode("utf-8")
            file.close()
            self.assertIn("Ticket User", rendered)
            self.assertIn("Staff Team", rendered)
            self.assertNotIn("Private Moderator", rendered)
            self.assertNotIn("staff.png", rendered)
            self.assertNotIn("<@999999999999999>", rendered)
            self.assertIn("@Staff Team", rendered)

        asyncio.run(runner())

    def test_dynamic_control_templates_rehydrate_after_restart(self):
        async def runner():
            match = ModmailActionButton.__discord_ui_compiled_template__.fullmatch("mm_claim:42")
            item = await ModmailActionButton.from_custom_id(
                SimpleNamespace(),
                discord.ui.Button(label="Claim Ticket", custom_id="mm_claim:42"),
                match,
            )
            self.assertEqual((item.action, item.user_id), ("claim", "42"))

            warning_match = AutoModWarningReportButton.__discord_ui_compiled_template__.fullmatch("amwarn:1:2:3")
            warning = await AutoModWarningReportButton.from_custom_id(
                SimpleNamespace(),
                discord.ui.Button(label="Report", custom_id="amwarn:1:2:3"),
                warning_match,
            )
            self.assertEqual((warning.guild_id, warning.rule_id, warning.created_at), (1, 2, 3))

            response_match = AutoModReportResponseSelect.__discord_ui_compiled_template__.fullmatch("amrr:1:4:2:3")
            response = await AutoModReportResponseSelect.from_custom_id(
                SimpleNamespace(),
                discord.ui.Select(custom_id="amrr:1:4:2:3", options=[discord.SelectOption(label="A", value="a")]),
                response_match,
            )
            self.assertEqual(response.warning_id, "AM-2-4-3")

            event_match = EventHistorySelect.__discord_ui_compiled_template__.fullmatch("event_history_select")
            with patch("cogs.event_leaderboard.load_config", return_value={"history": []}):
                event = await EventHistorySelect.from_custom_id(
                    SimpleNamespace(),
                    discord.ui.Select(custom_id="event_history_select", options=[discord.SelectOption(label="A", value="a")]),
                    event_match,
                )
            self.assertEqual(event.item.custom_id, "event_history_select")

            antinuke_match = AntiNukeResolveButton.__discord_ui_compiled_template__.fullmatch("antinuke:resolve:abcdefgh")
            antinuke = await AntiNukeResolveButton.from_custom_id(
                SimpleNamespace(),
                discord.ui.Button(label="Resolve", custom_id="antinuke:resolve:abcdefgh"),
                antinuke_match,
            )
            self.assertEqual(antinuke.resolution_id, "abcdefgh")

        asyncio.run(runner())


if __name__ == "__main__":
    unittest.main()
