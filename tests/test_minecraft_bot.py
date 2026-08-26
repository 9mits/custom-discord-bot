import asyncio
import inspect
import os
import time
import unittest
from datetime import datetime, timezone
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock, patch

import discord

from minecraft_bot.bot import MinecraftAccessBot, RateLimiter
from minecraft_bot.config import MinecraftConfig
from minecraft_bot.models import (
    AccessStatus,
    Edition,
    InvalidTransition,
    MinecraftAccess,
)
from minecraft_bot.presentation import (
    BRAND_NAME,
    ICON_PATH,
    FOOTER_ICON_URL,
    FOOTER_PATH,
    LOGO_PATH,
    RULES_ATTACHMENT_URI,
    ABOUT_PATH,
    APPLY_PATH,
    RULES_PATH,
    THEME_COLOUR,
    VERIFY_ATTACHMENT_URI,
    VERIFY_PATH,
    application_log_embed,
    info_embed,
    verification_embed,
    live_status_embed,
    minecraft_head_url,
    player_activity_embed,
    verification_log_embed,
)
from minecraft_bot.settings import MinecraftSettings
from minecraft_bot.setup import MinecraftSetupView
from minecraft_bot.ui import (
    application_card_view,
    VerifyButton,
    CancelPendingConfirmationView,
    MinecraftControlView,
    MinecraftApplicationModal,
    LiveApplicationView,
    RulesAgreementView,
)


class MinecraftBotPolicyTests(unittest.TestCase):
    def setUp(self):
        self.bot = object.__new__(MinecraftAccessBot)
        self.bot.settings = SimpleNamespace(mod_role_id=77)

    def test_configured_moderator_and_administrator_are_authorized(self):
        moderator = SimpleNamespace(
            roles=[SimpleNamespace(id=77)],
            guild_permissions=SimpleNamespace(administrator=False),
        )
        administrator = SimpleNamespace(
            roles=[],
            guild_permissions=SimpleNamespace(administrator=True),
        )
        self.assertTrue(self.bot.is_moderator(moderator))
        self.assertTrue(self.bot.is_moderator(administrator))

    def test_unauthorized_member_is_rejected(self):
        member = SimpleNamespace(
            roles=[SimpleNamespace(id=12)],
            guild_permissions=SimpleNamespace(administrator=False),
        )
        self.assertFalse(self.bot.is_moderator(member))

    def test_setup_hook_does_not_import_removed_application_controls(self):
        source = inspect.getsource(MinecraftAccessBot.setup_hook)
        self.assertNotIn("ContinueApplicationButton", source)
        self.assertNotIn("ApplyButton", source)

    def test_rate_limiter_is_bounded(self):
        limiter = RateLimiter(10, max_entries=2)
        self.assertTrue(limiter.claim(1, now=0))
        self.assertFalse(limiter.claim(1, now=1))
        self.assertTrue(limiter.claim(2, now=1))
        self.assertTrue(limiter.claim(3, now=20))
        self.assertLessEqual(len(limiter._entries), 2)

    def test_staff_commands_have_moderator_default_permissions(self):
        member_group, staff_group, admin_group = self.bot._build_command_groups()

        # Discord only honours default permissions on top-level commands, so the
        # gate has to sit on the groups themselves.
        self.assertIsNone(member_group.default_permissions)
        self.assertTrue(staff_group.default_permissions.manage_messages)
        self.assertTrue(admin_group.default_permissions.administrator)

        member_commands = {command.name: command for command in member_group.commands}
        staff_commands = {command.name: command for command in staff_group.commands}
        admin_commands = {command.name: command for command in admin_group.commands}

        for name in ("account", "server", "help", "cancel", "clan"):
            self.assertIn(name, member_commands)
        for name in (
            "panel", "status", "lookup", "revoke", "unlink", "retry", "applications",
            "audit", "cancel", "stats", "commandlog", "tools",
            "kick", "mute", "ban", "tempban", "unban", "heal", "broadcast", "update",
        ):
            self.assertIn(name, staff_commands)
        for name in (
            "setup", "information", "leaderboard", "log-channel", "chat-channel", "cleanheads",
        ):
            self.assertIn(name, admin_commands)
        self.assertNotIn("whois", staff_commands)
        self.assertNotIn("moderate", staff_commands)

        clan_commands = {command.name for command in member_commands["clan"].commands}
        self.assertLessEqual(
            {"view", "invite", "kick", "promote", "demote", "transfer",
             "rename", "color", "disband", "leave"},
            clan_commands,
        )

        lookup_parameters = {parameter.name: parameter for parameter in staff_commands["lookup"].parameters}
        self.assertEqual(set(lookup_parameters), {"user", "username"})
        self.assertFalse(lookup_parameters["user"].required)
        self.assertFalse(lookup_parameters["username"].required)

        tempban_parameters = {parameter.name: parameter for parameter in staff_commands["tempban"].parameters}
        self.assertTrue(tempban_parameters["duration"].required)
        self.assertFalse(tempban_parameters["reason"].required)
        broadcast_parameters = {parameter.name for parameter in staff_commands["broadcast"].parameters}
        self.assertEqual(broadcast_parameters, {"message"})
        self.assertEqual(
            {parameter.name for parameter in staff_commands["update"].parameters},
            set(),
        )

    def test_verification_panel_command_only_exists_in_minecraft_test_mode(self):
        production_groups = self.bot._build_command_groups()
        self.assertEqual(
            {group.name for group in production_groups},
            {"minecraft", "mcstaff", "mcadmin"},
        )

        self.bot.config = SimpleNamespace(test_mode=True)
        test_groups = self.bot._build_command_groups()
        staging = next(group for group in test_groups if group.name == "mctest")

        self.assertTrue(staging.default_permissions.administrator)
        self.assertEqual(
            {command.name for command in staging.commands},
            {"verification-panel"},
        )


    def test_minecraft_presentation_uses_orange_brand_system(self):
        embed = info_embed("Status", "Operational", success=True)

        self.assertEqual(THEME_COLOUR.value, discord.Colour.from_rgb(255, 153, 0).value)
        self.assertEqual(embed.colour.value, THEME_COLOUR.value)
        self.assertEqual(embed.footer.text, BRAND_NAME)
        self.assertEqual(embed.footer.icon_url, FOOTER_ICON_URL)
        self.assertIsNone(embed.thumbnail.url)
        self.assertIsNotNone(embed.timestamp)
        self.assertTrue(LOGO_PATH.is_file())
        self.assertTrue(ICON_PATH.is_file())
        self.assertTrue(FOOTER_PATH.is_file())
        self.assertTrue(RULES_PATH.is_file())
        self.assertTrue(VERIFY_PATH.is_file())
        self.assertTrue(ABOUT_PATH.is_file(), "the Before You Join mark must ship with the repo")
        self.assertTrue(APPLY_PATH.is_file(), "the Apply mark must ship with the repo")

    def test_minecraft_brand_assets_stay_lightweight(self):
        self.assertLess(LOGO_PATH.stat().st_size, 500_000)
        self.assertLess(ICON_PATH.stat().st_size, 100_000)
        self.assertLess(FOOTER_PATH.stat().st_size, 25_000)
        self.assertLess(RULES_PATH.stat().st_size, 1_000_000)
        self.assertLess(VERIFY_PATH.stat().st_size, 1_000_000)
        self.assertLess(ABOUT_PATH.stat().st_size, 1_000_000)
        self.assertLess(APPLY_PATH.stat().st_size, 1_000_000)

    def test_verification_instructions_are_copyable_and_edition_specific(self):
        application = MinecraftAccess(
            id=42,
            guild_id="1",
            discord_user_id="123456789012345678",
            edition=Edition.BEDROCK,
            claimed_username="PlayerOne",
            normalized_username="playerone",
            status=AccessStatus.PENDING_VERIFICATION,
            verification_expires_at=2_000_000_000,
            created_at=1_999_999_400,
            updated_at=1_999_999_400,
        )
        settings = SimpleNamespace(
            java_address="java.example:25565",
            bedrock_address="bedrock.example",
            bedrock_port=19132,
        )

        embed = verification_embed(application, settings)

        self.assertIn("```text\nbedrock.example\n```", embed.description)
        self.assertIn("```text\n19132\n```", embed.description)
        self.assertIn("java.example", embed.description)
        self.assertIn("/minecraft cancel", embed.description)
        self.assertNotIn("#42", embed.description)
        self.assertEqual(embed.image.url, VERIFY_ATTACHMENT_URI)

        live_embed = live_status_embed(application, settings)
        self.assertEqual(live_embed.image.url, VERIFY_ATTACHMENT_URI)

    def test_minecraft_skin_head_requires_a_verified_uuid(self):
        application = MinecraftAccess(
            id=8,
            guild_id="1",
            discord_user_id="123456789012345678",
            edition=Edition.BEDROCK,
            claimed_username="Bedrock Player",
            normalized_username="bedrock player",
            status=AccessStatus.PENDING_VERIFICATION,
            verification_expires_at=2_000_000_000,
            created_at=1_999_999_400,
            updated_at=1_999_999_400,
        )

        self.assertIsNone(minecraft_head_url(application))
        
    def test_every_minecraft_player_log_has_a_skin_thumbnail(self):
        verified = MinecraftAccess(
            id=9,
            guild_id="1",
            discord_user_id="123456789012345678",
            edition=Edition.JAVA,
            claimed_username="ClaimedName",
            normalized_username="claimedname",
            status=AccessStatus.VERIFIED,
            verification_expires_at=2_000_000_000,
            created_at=1_999_999_400,
            updated_at=1_999_999_400,
            verified_username="VerifiedName",
            minecraft_uuid="12345678-1234-1234-1234-123456789abc",
        )
        expected = "https://mc-heads.net/head/12345678-1234-1234-1234-123456789abc/128.png"

        self.assertEqual(application_log_embed(verified).thumbnail.url, expected)
        self.assertEqual(verification_log_embed(verified).thumbnail.url, expected)
        self.assertEqual(
            player_activity_embed(
                joined=True,
                username="VerifiedName",
                minecraft_uuid=verified.minecraft_uuid,
                edition="JAVA",
            ).thumbnail.url,
            expected,
        )

    def test_pre_verification_logs_resolve_java_and_bedrock_names(self):
        java_application = MinecraftAccess(
            id=10,
            guild_id="1",
            discord_user_id="123456789012345678",
            edition=Edition.JAVA,
            claimed_username="JavaPlayer",
            normalized_username="javaplayer",
            status=AccessStatus.PENDING_VERIFICATION,
            verification_expires_at=2_000_000_000,
            created_at=1_999_999_400,
            updated_at=1_999_999_400,
        )
        bedrock_application = MinecraftAccess(
            id=11,
            guild_id="1",
            discord_user_id="123456789012345679",
            edition=Edition.BEDROCK,
            claimed_username="Bedrock Player",
            normalized_username="bedrock player",
            status=AccessStatus.PENDING_VERIFICATION,
            verification_expires_at=2_000_000_000,
            created_at=1_999_999_400,
            updated_at=1_999_999_400,
        )

        self.assertEqual(
            application_log_embed(java_application).thumbnail.url,
            "https://mc-heads.net/head/JavaPlayer/128.png",
        )
        self.assertEqual(
            application_log_embed(bedrock_application).thumbnail.url,
            "https://api.mcheads.org/head/.Bedrock%20Player/128",
        )

    def test_setup_dashboard_uses_components_v2(self):
        bot = SimpleNamespace(
            settings=MinecraftSettings(),
            bridge=SimpleNamespace(connected=False),
            is_administrator=lambda _member: True,
        )
        view = MinecraftSetupView(bot, 123, None)
        payload = view.to_components()

        self.assertEqual(payload[0]["type"], 17)
        custom_ids = {
            component["custom_id"]
            for child in payload[0]["components"]
            for component in child.get("components", [])
            if "custom_id" in component
        }
        self.assertIn("minecraft:setup:application_channel_id", custom_ids)
        self.assertIn("minecraft:setup:activity_log_channel_id", custom_ids)
        self.assertIn("minecraft:setup:critical_log_channel_id", custom_ids)
        self.assertIn("minecraft:setup:member_role_id", custom_ids)
        self.assertIn("minecraft:setup:action:post", custom_ids)

    def test_moderator_control_panel_is_compact_and_actionable(self):
        bot = SimpleNamespace(
            is_moderator=lambda _member: True,
            is_administrator=lambda _member: False,
        )

        view = MinecraftControlView(bot, 123)

        self.assertEqual(len(view.children), 1)
        self.assertEqual(view.children[0].custom_id, "minecraft:control:tools")
        self.assertEqual(
            {option.value for option in view.children[0].options},
            {"overview", "diagnostics", "applications", "commandlog", "username"},
        )

    def test_administrator_control_panel_includes_setup(self):
        bot = SimpleNamespace(
            is_moderator=lambda _member: True,
            is_administrator=lambda _member: True,
        )

        view = MinecraftControlView(bot, 123, include_setup=True)

        self.assertEqual(len(view.children), 1)
        self.assertIn("setup", {option.value for option in view.children[0].options})

    def test_control_dropdown_reuses_the_compact_panel(self):
        replacement = object()
        bot = SimpleNamespace(
            is_moderator=lambda _member: True,
            is_administrator=lambda _member: False,
            build_control_overview=AsyncMock(),
            build_diagnostics_embed=AsyncMock(return_value=info_embed("Diagnostics", "Clear")),
            build_applications_embed=AsyncMock(),
            build_command_log_embed=AsyncMock(),
            control_view=lambda _interaction: replacement,
        )
        view = MinecraftControlView(bot, 123)
        menu = view.children[0]
        menu._values = ["diagnostics"]
        interaction = SimpleNamespace(
            user=SimpleNamespace(id=123),
            guild=SimpleNamespace(id=10),
            response=SimpleNamespace(defer=AsyncMock()),
            edit_original_response=AsyncMock(),
        )

        asyncio.run(menu.callback(interaction))

        interaction.response.defer.assert_awaited_once_with(ephemeral=True)
        bot.build_diagnostics_embed.assert_awaited_once_with(interaction.guild)
        self.assertIs(interaction.edit_original_response.await_args.kwargs["view"], replacement)


class MinecraftDepartureRevocationTests(unittest.IsolatedAsyncioTestCase):
    async def test_ban_in_the_configured_guild_revokes_access(self):
        fake = SimpleNamespace(
            config=SimpleNamespace(guild_id=10),
            _revoke_departed_member=AsyncMock(),
        )
        await MinecraftAccessBot.on_member_ban(
            fake, SimpleNamespace(id=10), SimpleNamespace(id=55)
        )
        fake._revoke_departed_member.assert_awaited_once_with(55, "Discord server ban")

    async def test_ban_from_an_unrelated_guild_is_ignored(self):
        fake = SimpleNamespace(
            config=SimpleNamespace(guild_id=10),
            _revoke_departed_member=AsyncMock(),
        )
        await MinecraftAccessBot.on_member_ban(
            fake, SimpleNamespace(id=11), SimpleNamespace(id=55)
        )
        fake._revoke_departed_member.assert_not_awaited()

    async def test_confirmed_kick_revokes_but_an_ordinary_leave_does_not(self):
        async def audit_entries(*entries):
            for entry in entries:
                yield entry

        kick = SimpleNamespace(
            target=SimpleNamespace(id=55),
            created_at=datetime.now(timezone.utc),
        )
        fake = SimpleNamespace(
            config=SimpleNamespace(guild_id=10),
            _revoke_departed_member=AsyncMock(),
        )
        kicked_guild = SimpleNamespace(
            id=10, audit_logs=lambda **_kwargs: audit_entries(kick)
        )
        with patch("minecraft_bot.bot.asyncio.sleep", new=AsyncMock()):
            await MinecraftAccessBot.on_member_remove(
                fake, SimpleNamespace(id=55, guild=kicked_guild)
            )
        fake._revoke_departed_member.assert_awaited_once_with(55, "Discord server kick")

        fake._revoke_departed_member.reset_mock()
        left_guild = SimpleNamespace(
            id=10, audit_logs=lambda **_kwargs: audit_entries()
        )
        with patch("minecraft_bot.bot.asyncio.sleep", new=AsyncMock()):
            await MinecraftAccessBot.on_member_remove(
                fake, SimpleNamespace(id=55, guild=left_guild)
            )
        fake._revoke_departed_member.assert_not_awaited()


class MinecraftApplyFlowTests(unittest.IsolatedAsyncioTestCase):
    async def test_discord_chat_channel_relays_plain_messages_and_attachment_link(self):
        bot = object.__new__(MinecraftAccessBot)
        bot.config = SimpleNamespace(guild_id=10)
        bot.settings = SimpleNamespace(chat_channel_id=20)
        bot.data = SimpleNamespace(list_accounts_for_user=AsyncMock(return_value=[{
            "minecraft_uuid": "123e4567-e89b-12d3-a456-426614174000",
            "current_username": "TestPlayer",
        }]))
        bot.bridge = SimpleNamespace(
            send_discord_chat=AsyncMock(return_value=True),
            supports_chat_sync=True,
        )
        bot.chat_rate_limit = RateLimiter(0)
        bot.process_commands = AsyncMock()
        message = SimpleNamespace(
            guild=SimpleNamespace(id=10),
            channel=SimpleNamespace(id=20),
            author=SimpleNamespace(id=99, name="hellomits", bot=False),
            webhook_id=None,
            content="hello from Discord",
            attachments=[SimpleNamespace(url="https://cdn.discordapp.com/attachments/1/2/image.png")],
            jump_url="https://discord.com/channels/10/20/30",
        )

        await bot.on_message(message)

        bot.bridge.send_discord_chat.assert_awaited_once_with(
            discord_user_id=99,
            discord_username="hellomits",
            minecraft_uuid="123e4567-e89b-12d3-a456-426614174000",
            minecraft_username="TestPlayer",
            message="hello from Discord",
            attachment_url="https://cdn.discordapp.com/attachments/1/2/image.png",
            attachment_count=1,
        )
        bot.process_commands.assert_awaited_once_with(message)

    async def test_minecraft_chat_webhook_omits_the_player_edition(self):
        channel = Mock(spec=discord.TextChannel)
        webhook = SimpleNamespace(send=AsyncMock())
        bot = object.__new__(MinecraftAccessBot)
        bot.config = SimpleNamespace(guild_id=10)
        bot.settings = SimpleNamespace(chat_channel_id=20)
        bot.data = SimpleNamespace(
            claim_bridge_event=AsyncMock(return_value=True),
            get_account_owner=AsyncMock(return_value=None),
        )
        bot._configured_channel = AsyncMock(return_value=channel)
        bot._chat_webhook = AsyncMock(return_value=webhook)

        await bot.handle_minecraft_chat(
            minecraft_uuid="123e4567-e89b-12d3-a456-426614174000",
            current_username="TestPlayer",
            edition="JAVA",
            message="hello from Minecraft",
            event_idempotency_key="minecraft-chat-1",
        )

        webhook.send.assert_awaited_once()
        embed = webhook.send.await_args.kwargs["embed"]
        self.assertEqual(embed.author.name, "Minecraft · TestPlayer")
        self.assertNotIn("Java", embed.author.name)

    async def test_discord_chat_relay_ignores_webhooks_to_prevent_loops(self):
        bot = object.__new__(MinecraftAccessBot)
        bot.config = SimpleNamespace(guild_id=10)
        bot.settings = SimpleNamespace(chat_channel_id=20)
        bot.data = SimpleNamespace(list_accounts_for_user=AsyncMock())
        bot.bridge = SimpleNamespace(send_discord_chat=AsyncMock())
        bot.process_commands = AsyncMock()
        message = SimpleNamespace(
            guild=SimpleNamespace(id=10),
            channel=SimpleNamespace(id=20),
            author=SimpleNamespace(id=99, name="relay", bot=False),
            webhook_id=123,
            content="loop",
            attachments=[],
        )

        await bot.on_message(message)

        bot.bridge.send_discord_chat.assert_not_awaited()
        bot.data.list_accounts_for_user.assert_not_awaited()

    async def test_modal_submission_replaces_the_original_ephemeral_rules_card(self):
        # A freshly created application is awaiting verification, which is what
        # decides whether the card carries the verify image.
        application = SimpleNamespace(id=42, status=AccessStatus.PENDING_VERIFICATION)
        original_message = SimpleNamespace(id=9001)
        bot = SimpleNamespace(
            data=SimpleNamespace(create_verification=AsyncMock(return_value=application)),
            settings=SimpleNamespace(),
            remember_application_message=Mock(),
            finish_application_submission=AsyncMock(),
        )

        def close_background_work(work, *, name):
            work.close()

        bot.spawn_background_task = Mock(side_effect=close_background_work)
        interaction = SimpleNamespace(
            client=bot,
            guild_id=10,
            user=SimpleNamespace(id=99),
            response=SimpleNamespace(defer=AsyncMock()),
            original_response=AsyncMock(return_value=original_message),
            edit_original_response=AsyncMock(),
        )
        modal = MinecraftApplicationModal(Edition.JAVA)
        modal.username._value = "PlayerOne"

        with patch("minecraft_bot.ui.live_status_embed", return_value=info_embed("Application Received", "> Saved.")):
            await modal.on_submit(interaction)

        interaction.response.defer.assert_awaited_once_with()
        interaction.edit_original_response.assert_awaited_once()
        kwargs = interaction.edit_original_response.await_args.kwargs
        self.assertEqual(kwargs["embed"].title, "Application Received")
        self.assertIsInstance(kwargs["view"], LiveApplicationView)
        self.assertEqual(kwargs["attachments"][0].filename, "mysterious_smp_x_verify.png")
        kwargs["attachments"][0].close()
        bot.remember_application_message.assert_called_once_with(42, original_message)
        create_kwargs = bot.data.create_verification.await_args.kwargs
        # Stage one carries no written answers; the form follows verification.
        self.assertNotIn("answers", create_kwargs)

    def _questions_interaction(self, application, *, fails=None):
        bot = SimpleNamespace(
            data=SimpleNamespace(
                submit_answers=AsyncMock(return_value=application, side_effect=fails)
            ),
            settings=SimpleNamespace(),
            finish_answers_submission=AsyncMock(),
            replace_application_card=AsyncMock(),
        )

        def close_background_work(work, *, name):
            work.close()

        bot.spawn_background_task = Mock(side_effect=close_background_work)
        return SimpleNamespace(
            client=bot,
            user=SimpleNamespace(id=99),
            response=SimpleNamespace(defer=AsyncMock()),
            edit_original_response=AsyncMock(),
            followup=SimpleNamespace(send=AsyncMock()),
            original_response=AsyncMock(return_value=SimpleNamespace(id=4242)),
        )

    def _answered(self, modal):
        modal.why._value = "I enjoy collaborative survival servers."
        modal.about._value = "I build farms and help other players."
        return modal

    async def test_submission_does_not_dm_a_pending_verification_card(self):
        bot = object.__new__(MinecraftAccessBot)
        bot.log_application_submission = AsyncMock()
        bot.update_live_card = AsyncMock()
        bot.bridge = SimpleNamespace(connected=False)
        application = SimpleNamespace(id=1, status=AccessStatus.PENDING_VERIFICATION)

        await bot.finish_application_submission(application)

        bot.log_application_submission.assert_awaited_once_with(application)
        bot.update_live_card.assert_not_awaited()

    async def test_the_card_is_updated_in_place_and_nothing_is_dmed(self):
        # A verification still in flight: the card is edited where it stands and
        # nothing is DMed, because there is no outcome to report yet.
        application = SimpleNamespace(
            id=1,
            discord_user_id="99",
            edition=Edition.JAVA,
            claimed_username="PlayerOne",
            verified_username="PlayerOne",
            auto_detect_edition=False,
            verification_expires_at=2_000_000_000,
            status=AccessStatus.PENDING_VERIFICATION,
            status_channel_id=None,
            status_message_id=None,
        )
        ephemeral = SimpleNamespace(edit=AsyncMock())
        sent_message = SimpleNamespace(channel=SimpleNamespace(id=50), id=60)
        user = SimpleNamespace(send=AsyncMock(return_value=sent_message))
        bot = object.__new__(MinecraftAccessBot)
        bot.settings = SimpleNamespace(
            java_address="play.example.net",
            bedrock_address="play.example.net",
            bedrock_port=19132,
        )
        bot._application_messages = {1: (ephemeral, float("inf"))}
        bot.get_user = Mock(return_value=user)
        bot.fetch_user = AsyncMock()
        bot.data = SimpleNamespace(
            get_access=AsyncMock(return_value=application),
            enqueue_delivery=AsyncMock(),
            set_status_message=AsyncMock(),
        )

        delivered = await bot.update_live_card(application)

        self.assertTrue(delivered)
        ephemeral.edit.assert_awaited_once()
        # The verify card carries its own illustration, so the edit re-sends it
        # rather than stripping the message back to text.
        self.assertEqual(len(ephemeral.edit.await_args.kwargs["attachments"]), 1)
        # The subject of this test: editing in place never DMs.
        user.send.assert_not_awaited()
        bot.data.set_status_message.assert_not_awaited()

    async def test_the_application_channel_is_published_as_three_messages(self):
        # One message carries one set of buttons, so the reading and the Apply
        # button have to be separate messages to sit apart.
        sent = [SimpleNamespace(id=101), SimpleNamespace(id=102), SimpleNamespace(id=103)]
        channel = SimpleNamespace(send=AsyncMock(side_effect=sent))
        bot = object.__new__(MinecraftAccessBot)
        bot.settings = SimpleNamespace(application_channel_id=20)
        bot._configured_channel = AsyncMock(return_value=channel)
        bot.data = SimpleNamespace(
            get_config=AsyncMock(return_value=None),
            set_configs=AsyncMock(),
        )

        result = await bot.post_application_panel()

        self.assertEqual(channel.send.await_count, 3)
        titles = [call.kwargs["embed"].title for call in channel.send.await_args_list]
        self.assertEqual(
            titles,
            [
                "Welcome to Mysterious SMP X",
                "Before You Join",
                "Join Mysterious SMP X",
            ],
        )
        # Apply is returned and tracked, because that is the message a press is
        # validated against.
        self.assertIs(result, sent[2])
        bot.data.set_configs.assert_awaited_once_with(
            {
                "application_banner_message_id": "",
                "application_welcome_message_id": "101",
                "application_guide_message_id": "102",
                "application_panel_message_id": "103",
            }
        )
        for call in channel.send.await_args_list:
            self.assertNotIn("file", call.kwargs)
            for file in call.kwargs["files"]:
                file.close()

    async def test_only_the_apply_message_carries_the_apply_button(self):
        sent = [SimpleNamespace(id=101), SimpleNamespace(id=102), SimpleNamespace(id=103)]
        channel = SimpleNamespace(send=AsyncMock(side_effect=sent))
        bot = object.__new__(MinecraftAccessBot)
        bot.settings = SimpleNamespace(application_channel_id=20)
        bot._configured_channel = AsyncMock(return_value=channel)
        bot.data = SimpleNamespace(
            get_config=AsyncMock(return_value=None),
            set_configs=AsyncMock(),
        )

        await bot.post_application_panel()

        welcome, guide, apply = channel.send.await_args_list
        # An attachment:// thumbnail only renders if its file rides along on the
        # same message, so each panel carries its own.
        self.assertEqual(
            [file.filename for file in guide.kwargs["files"]],
            ["mysterious_smp_x_about.png"],
        )
        self.assertEqual(
            [file.filename for file in apply.kwargs["files"]],
            ["mysterious_smp_x_apply.png"],
        )
        self.assertIsNone(welcome.kwargs["view"])
        self.assertEqual(
            [item.label for item in apply.kwargs["view"].children], ["Verify"]
        )
        # The guide carries the information panel's pages, minus the one that
        # needs an account the applicant does not have yet.
        from minecraft_bot.information import PAGES

        guide_ids = {item.custom_id for item in guide.kwargs["view"].children}
        self.assertEqual(guide_ids, {f"mgx_info:{page}" for page in PAGES})
        for call in channel.send.await_args_list:
            for file in call.kwargs["files"]:
                file.close()

    async def test_apply_reveals_cancel_only_for_pending_verification(self):
        application = MinecraftAccess(
            id=42,
            guild_id="10",
            discord_user_id="99",
            edition=Edition.JAVA,
            claimed_username="PlayerOne",
            normalized_username="playerone",
            status=AccessStatus.PENDING_VERIFICATION,
            verification_expires_at=2_000_000_000,
            created_at=1_999_999_400,
            updated_at=1_999_999_400,
        )
        response = SimpleNamespace(
            send_message=AsyncMock(),
            send_modal=AsyncMock(),
        )
        bot = SimpleNamespace(
            config=SimpleNamespace(guild_id=10),
            settings=SimpleNamespace(
                application_channel_id=20,
                java_address="java.example:25565",
                bedrock_address="bedrock.example",
                bedrock_port=19132,
            ),
            data=SimpleNamespace(
                get_config=AsyncMock(return_value="30"),
                get_active_access_for_user=AsyncMock(return_value=application),
            ),
            apply_rate_limit=SimpleNamespace(claim=lambda _user_id: True),
            replace_application_card=AsyncMock(),
        )
        interaction = SimpleNamespace(
            client=bot,
            guild_id=10,
            channel_id=20,
            message=SimpleNamespace(id=30),
            user=SimpleNamespace(id=99),
            response=response,
            original_response=AsyncMock(return_value=SimpleNamespace(id=99)),
        )

        await VerifyButton().callback(interaction)

        response.send_modal.assert_not_awaited()
        response.send_message.assert_awaited_once()
        kwargs = response.send_message.await_args.kwargs
        self.assertTrue(kwargs["ephemeral"])
        # Both controls, not a cancel-only view: reopening the card through Apply
        # used to silently drop Get Help.
        self.assertEqual(
            [item.label for item in kwargs["view"].children],
            ["Cancel Pending Verification", "Get Help"],
        )
        self.assertEqual(kwargs["embed"].image.url, VERIFY_ATTACHMENT_URI)
        self.assertEqual(kwargs["file"].filename, "mysterious_smp_x_verify.png")
        kwargs["file"].close()

    async def test_cancel_confirmation_edits_the_existing_ephemeral(self):
        response = SimpleNamespace(defer=AsyncMock())
        interaction = SimpleNamespace(
            client=SimpleNamespace(cancel_pending_verification=AsyncMock()),
            guild_id=10,
            user=SimpleNamespace(id=99),
            response=response,
            edit_original_response=AsyncMock(),
        )
        view = CancelPendingConfirmationView(99)

        await view.children[0].callback(interaction)

        response.defer.assert_awaited_once_with()
        interaction.edit_original_response.assert_awaited_once()
        kwargs = interaction.edit_original_response.await_args.kwargs
        self.assertIsNone(kwargs["view"])
        self.assertEqual(kwargs["attachments"], [])

    async def test_new_application_requires_rules_agreement(self):
        response = SimpleNamespace(send_message=AsyncMock(), send_modal=AsyncMock())
        bot = SimpleNamespace(
            config=SimpleNamespace(guild_id=10),
            settings=SimpleNamespace(application_channel_id=20),
            data=SimpleNamespace(
                get_config=AsyncMock(return_value="30"),
                get_active_access_for_user=AsyncMock(return_value=None),
            ),
            apply_rate_limit=SimpleNamespace(claim=lambda _user_id: True),
        )
        interaction = SimpleNamespace(
            client=bot,
            guild_id=10,
            channel_id=20,
            message=SimpleNamespace(id=30),
            user=SimpleNamespace(id=99),
            response=response,
        )

        await VerifyButton().callback(interaction)

        response.send_modal.assert_not_awaited()
        kwargs = response.send_message.await_args.kwargs
        self.assertIsInstance(kwargs["view"], RulesAgreementView)
        self.assertEqual(kwargs["view"].children[0].style, discord.ButtonStyle.success)
        self.assertEqual(kwargs["view"].children[1].style, discord.ButtonStyle.secondary)
        self.assertEqual(kwargs["embed"].image.url, RULES_ATTACHMENT_URI)
        self.assertEqual(kwargs["embed"].footer.icon_url, FOOTER_ICON_URL)
        self.assertEqual(kwargs["file"].filename, "mysterious_smp_x_rules.png")
        kwargs["file"].close()

    async def test_rules_agreement_opens_modal_and_disagreement_edits_message(self):
        view = RulesAgreementView(99)
        agree_response = SimpleNamespace(send_modal=AsyncMock())
        agree_interaction = SimpleNamespace(
            client=SimpleNamespace(bridge=SimpleNamespace(supports_auto_edition=True)),
            message=SimpleNamespace(id=1),
            user=SimpleNamespace(id=99),
            response=agree_response,
        )

        await view.children[0].callback(agree_interaction)

        agree_response.send_modal.assert_awaited_once()
        self.assertIsInstance(
            agree_response.send_modal.await_args.args[0],
            MinecraftApplicationModal,
        )

        disagree_response = SimpleNamespace(edit_message=AsyncMock())
        disagree_interaction = SimpleNamespace(
            user=SimpleNamespace(id=99),
            response=disagree_response,
        )
        await view.children[1].callback(disagree_interaction)

        kwargs = disagree_response.edit_message.await_args.kwargs
        self.assertIsNone(kwargs["view"])
        self.assertEqual(kwargs["attachments"], [])

    async def test_player_activity_is_deduplicated_before_logging(self):
        bot = object.__new__(MinecraftAccessBot)
        bot.config = SimpleNamespace(guild_id=1)
        bot.settings = SimpleNamespace(player_log_channel_id=55)
        bot.bridge = SimpleNamespace(send_player_profile=AsyncMock(return_value=True))
        bot.get_guild = Mock(return_value=None)
        bot.data = SimpleNamespace(
            claim_bridge_event=AsyncMock(side_effect=[True, False]),
            get_account_owner=AsyncMock(return_value="99"),
        )
        bot._send_configured_log = AsyncMock()
        payload = {
            "joined": True,
            "minecraft_uuid": "123e4567-e89b-12d3-a456-426614174000",
            "current_username": "PlayerOne",
            "edition": "JAVA",
            "xuid": None,
            "event_idempotency_key": "player-event-1",
        }

        await bot.handle_player_event(**payload)
        await bot.handle_player_event(**payload)

        bot._send_configured_log.assert_awaited_once()
        self.assertEqual(bot._send_configured_log.await_args.args[0], 55)
        bot.bridge.send_player_profile.assert_awaited_once_with(
            minecraft_uuid=payload["minecraft_uuid"],
            level=0,
            extra_hearts=0,
            elite=False,
            discord_username="",
            rank_group="",
            rank_label="",
            rank_colour=0,
            rank_weight=0,
            booster=False,
            rank_known=False,
            # The account is linked but the member could not be resolved, so the name
            # is unknown rather than absent: the plugin must keep what it cached.
            link_known=False,
        )

    async def test_an_unlinked_player_is_told_to_forget_their_cached_name(self):
        # No account row is knowledge, not a failed lookup. Without this the plugin
        # keeps showing a Discord name for somebody who is no longer linked at all.
        bot = object.__new__(MinecraftAccessBot)
        bot.config = SimpleNamespace(guild_id=1)
        bot.settings = SimpleNamespace(player_log_channel_id=55)
        bot._background_tasks = set()
        bot.bridge = SimpleNamespace(
            supports_profile_sync=True,
            send_player_profile=AsyncMock(return_value=True),
        )
        bot.data = SimpleNamespace(
            claim_bridge_event=AsyncMock(return_value=True),
            get_account_owner=AsyncMock(return_value=None),
        )
        bot._send_configured_log = AsyncMock()

        await bot.handle_player_event(
            joined=True,
            minecraft_uuid="123e4567-e89b-12d3-a456-426614174000",
            current_username="PlayerOne",
            edition="JAVA",
            xuid=None,
            event_idempotency_key="player-event-unlinked",
        )

        kwargs = bot.bridge.send_player_profile.await_args.kwargs
        self.assertTrue(kwargs["link_known"])
        self.assertEqual(kwargs["discord_username"], "")

    async def test_level_role_change_resyncs_all_linked_accounts(self):
        bot = object.__new__(MinecraftAccessBot)
        bot.bridge = SimpleNamespace(supports_profile_sync=True)
        bot.data = SimpleNamespace(
            list_accounts_for_user=AsyncMock(return_value=[
                {"minecraft_uuid": "123e4567-e89b-12d3-a456-426614174000"},
                {"minecraft_uuid": "123e4567-e89b-12d3-a456-426614174001"},
            ])
        )
        bot.sync_player_profile = AsyncMock(return_value=True)
        unchanged = SimpleNamespace(id=7)
        milestone = SimpleNamespace(id=1476839722172158018)
        before = SimpleNamespace(id=99, roles=[unchanged])
        after = SimpleNamespace(id=99, roles=[unchanged, milestone])

        await bot.on_member_update(before, after)

        self.assertEqual(bot.sync_player_profile.await_count, 2)
        bot.sync_player_profile.assert_any_await(
            "123e4567-e89b-12d3-a456-426614174000",
            99,
            member=after,
        )

    async def test_discord_role_hierarchy_decides_which_rank_wins(self):
        bot = object.__new__(MinecraftAccessBot)
        bot.bridge = SimpleNamespace(
            supports_profile_sync=True,
            send_player_profile=AsyncMock(return_value=True),
        )
        bot.data = SimpleNamespace(account_for_uuid=AsyncMock(return_value=None))
        # Deliberately listed lowest-first, the order discord.py yields.
        booster = SimpleNamespace(id=1476877246902960249, position=2)
        owner = SimpleNamespace(id=1476839722247786593, position=40)
        member = SimpleNamespace(id=99, roles=[booster, owner], name="mits")

        await bot.sync_player_profile(
            "123e4567-e89b-12d3-a456-426614174000",
            99,
            member=member,
        )

        kwargs = bot.bridge.send_player_profile.await_args.kwargs
        self.assertEqual(kwargs["rank_group"], "owner")
        self.assertEqual(kwargs["rank_label"], "OWNER")

    async def test_lower_discord_role_wins_when_ranked_above(self):
        bot = object.__new__(MinecraftAccessBot)
        bot.bridge = SimpleNamespace(
            supports_profile_sync=True,
            send_player_profile=AsyncMock(return_value=True),
        )
        bot.data = SimpleNamespace(account_for_uuid=AsyncMock(return_value=None))
        # Booster dragged above owner in Discord: hierarchy, not code order, decides.
        booster = SimpleNamespace(id=1476877246902960249, position=90)
        owner = SimpleNamespace(id=1476839722247786593, position=40)
        member = SimpleNamespace(id=99, roles=[owner, booster], name="mits")

        await bot.sync_player_profile(
            "123e4567-e89b-12d3-a456-426614174000",
            99,
            member=member,
        )

        self.assertEqual(
            bot.bridge.send_player_profile.await_args.kwargs["rank_group"],
            "booster",
        )


class MinecraftConfigurationTests(unittest.IsolatedAsyncioTestCase):
    async def test_test_panel_command_configures_and_posts_the_working_panel(self):
        bot = object.__new__(MinecraftAccessBot)
        bot.config = SimpleNamespace(test_mode=True)
        bot.require_administrator = AsyncMock(return_value=True)
        bot.update_settings = AsyncMock()
        bot.post_application_panel = AsyncMock(
            return_value=SimpleNamespace(jump_url="https://discord.test/panel")
        )
        interaction = SimpleNamespace(
            user=SimpleNamespace(id=42),
            response=SimpleNamespace(defer=AsyncMock()),
            edit_original_response=AsyncMock(),
        )
        channel = SimpleNamespace(id=99, mention="#verify-test")
        test_group = next(
            group for group in bot._build_command_groups() if group.name == "mctest"
        )
        command = next(
            command
            for command in test_group.commands
            if command.name == "verification-panel"
        )

        await command.callback(interaction, channel)

        interaction.response.defer.assert_awaited_once_with(
            ephemeral=True,
            thinking=True,
        )
        bot.update_settings.assert_awaited_once_with(
            actor_id=42,
            application_channel_id=99,
        )
        bot.post_application_panel.assert_awaited_once_with()
        embed = interaction.edit_original_response.await_args.kwargs["embed"]
        self.assertEqual(embed.title, "Test Verification Ready")

    def test_minimal_environment_is_enough_to_bootstrap(self):
        environment = {
            "MINECRAFT_DISCORD_BOT_TOKEN": "token",
            "MINECRAFT_GUILD_ID": "123456789",
            "MINECRAFT_BRIDGE_SECRET": "ab" * 32,
        }
        with patch.dict(os.environ, environment, clear=True):
            config = MinecraftConfig.from_env()

        self.assertEqual(config.application_channel_id, 0)
        self.assertEqual(config.review_channel_id, 0)
        self.assertEqual(config.mod_role_id, 0)
        self.assertEqual(config.member_role_id, 0)
        self.assertIsNone(config.bridge_tls_cert_path)
        self.assertIsNone(config.bridge_tls_key_path)
        self.assertFalse(config.test_mode)

    def test_minecraft_test_mode_is_explicit(self):
        environment = {
            "MINECRAFT_DISCORD_BOT_TOKEN": "token",
            "MINECRAFT_GUILD_ID": "123456789",
            "MINECRAFT_BRIDGE_SECRET": "ab" * 32,
            "MINECRAFT_TEST_MODE": "true",
        }
        with patch.dict(os.environ, environment, clear=True):
            config = MinecraftConfig.from_env()

        self.assertTrue(config.test_mode)

    def test_bridge_tls_certificate_and_key_must_be_configured_together(self):
        environment = {
            "MINECRAFT_DISCORD_BOT_TOKEN": "token",
            "MINECRAFT_GUILD_ID": "123456789",
            "MINECRAFT_BRIDGE_SECRET": "ab" * 32,
            "MINECRAFT_BRIDGE_TLS_CERT": "runtime/minecraft/bridge-cert.pem",
        }
        with patch.dict(os.environ, environment, clear=True):
            with self.assertRaisesRegex(RuntimeError, "must be set together"):
                MinecraftConfig.from_env()

    def test_bridge_tls_certificate_and_key_paths_are_resolved(self):
        environment = {
            "MINECRAFT_DISCORD_BOT_TOKEN": "token",
            "MINECRAFT_GUILD_ID": "123456789",
            "MINECRAFT_BRIDGE_SECRET": "ab" * 32,
            "MINECRAFT_BRIDGE_TLS_CERT": "runtime/minecraft/bridge-cert.pem",
            "MINECRAFT_BRIDGE_TLS_KEY": "runtime/minecraft/bridge-key.pem",
        }
        with patch.dict(os.environ, environment, clear=True):
            config = MinecraftConfig.from_env()

        self.assertEqual(
            config.bridge_tls_cert_path,
            (Path.cwd() / "runtime/minecraft/bridge-cert.pem").resolve(),
        )
        self.assertEqual(
            config.bridge_tls_key_path,
            (Path.cwd() / "runtime/minecraft/bridge-key.pem").resolve(),
        )

    def test_database_settings_override_legacy_environment_defaults(self):
        bootstrap = SimpleNamespace(
            application_channel_id=10,
            review_channel_id=20,
            mod_role_id=30,
            member_role_id=40,
            java_address="legacy.example:25565",
            bedrock_address="legacy.example",
            bedrock_port=19132,
        )
        settings = MinecraftSettings.from_sources(
            bootstrap,
            {"application_channel_id": 99, "java_address": "saved.example:25570"},
        )

        self.assertEqual(settings.application_channel_id, 99)
        self.assertEqual(settings.review_channel_id, 20)
        self.assertEqual(settings.application_log_channel_id, 0)
        self.assertEqual(settings.java_address, "saved.example:25570")

    def test_log_channels_are_persistent_and_can_be_disabled(self):
        settings = MinecraftSettings().with_updates(
            application_log_channel_id=101,
            verification_log_channel_id=102,
            player_log_channel_id=103,
        )
        disabled = settings.with_updates(player_log_channel_id=0)

        self.assertEqual(settings.application_log_channel_id, 101)
        self.assertEqual(settings.verification_log_channel_id, 102)
        self.assertEqual(settings.player_log_channel_id, 103)
        self.assertEqual(disabled.player_log_channel_id, 0)

    def test_invalid_or_overlapping_panel_settings_are_rejected(self):
        settings = MinecraftSettings(application_channel_id=10, mod_role_id=20)
        with self.assertRaisesRegex(ValueError, "channels must be different"):
            settings.with_updates(review_channel_id=10)
        with self.assertRaisesRegex(ValueError, "roles must be different"):
            settings.with_updates(member_role_id=20)
        with self.assertRaisesRegex(ValueError, "include a port"):
            settings.with_updates(java_address="server.example")

    async def test_runtime_settings_publish_only_after_database_commit(self):
        bot = object.__new__(MinecraftAccessBot)
        bot.settings = MinecraftSettings(application_channel_id=10)
        bot._settings_lock = asyncio.Lock()
        bot.data = SimpleNamespace(set_configs=AsyncMock(side_effect=RuntimeError("commit failed")))

        with self.assertRaisesRegex(RuntimeError, "commit failed"):
            await bot.update_settings(actor_id=123, application_channel_id=20)

        self.assertEqual(bot.settings.application_channel_id, 10)


if __name__ == "__main__":
    unittest.main()


class MinecraftLeaderboardTransportTests(unittest.IsolatedAsyncioTestCase):
    """Drives the bridge's real message handler, not a copy of its logic."""

    def _bridge(self):
        from minecraft_bot.bridge import MinecraftBridgeServer

        bridge = object.__new__(MinecraftBridgeServer)
        bridge.latest_leaderboard = {}
        bridge.leaderboard_handler = None
        return bridge

    @staticmethod
    def _envelope(payload):
        return {"type": "LEADERBOARD_SNAPSHOT", "payload": payload, "idempotency_key": "k"}

    async def test_snapshot_is_cached_for_the_leaderboard_message(self):
        bridge = self._bridge()
        snapshot = {"individual": {"wealth": [{"username": "mits", "value": 10}]}, "clan": {}}

        await bridge._handle_message(self._envelope(snapshot))

        self.assertEqual(bridge.latest_leaderboard, snapshot)

    async def test_newer_snapshot_replaces_the_older_one(self):
        bridge = self._bridge()

        await bridge._handle_message(self._envelope({"generated_at": 1}))
        await bridge._handle_message(self._envelope({"generated_at": 2}))

        self.assertEqual(bridge.latest_leaderboard["generated_at"], 2)

    async def test_handler_is_notified_when_present(self):
        bridge = self._bridge()
        bridge.leaderboard_handler = AsyncMock()
        snapshot = {"generated_at": 3}

        await bridge._handle_message(self._envelope(snapshot))

        bridge.leaderboard_handler.assert_awaited_once_with(snapshot)


class MinecraftLeaderboardRenderTests(unittest.TestCase):
    def setUp(self):
        from minecraft_bot import leaderboard

        self.leaderboard = leaderboard
        self.snapshot = {
            "generated_at": 1_700_000_000_000,
            "individual": {
                "wealth": [
                    {"minecraft_uuid": "u1", "username": "mits", "value": 900, "display": "900", "clan": "LUCKY"},
                    {"minecraft_uuid": "u2", "username": "kai", "value": 500, "display": "500"},
                    {"minecraft_uuid": "u3", "username": "sam", "value": 100, "display": "100"},
                    {"minecraft_uuid": "u4", "username": "noah", "value": 10, "display": "10"},
                    {"minecraft_uuid": "u5", "username": "ely", "value": 5, "display": "5"},
                    {"minecraft_uuid": "u6", "username": "zed", "value": 1, "display": "1"},
                ]
            },
            "clan": {"wealth": [{"clan": "LUCKY", "members": 4, "value": 900, "display": "900"}]},
        }

    def test_footer_is_the_brand_name_alone(self):
        embed = self.leaderboard.build_embed(self.snapshot, scope="individual", board="wealth")

        self.assertEqual(embed.footer.text, self.leaderboard.BRAND_NAME)

    def test_thumbnail_is_the_leader_of_this_board(self):
        # Each board is about whoever tops it, so the thumbnail follows the board
        # rather than showing the same brand mark five times.
        from minecraft_bot.presentation import head_url

        embed = self.leaderboard.build_embed(self.snapshot, scope="individual", board="wealth")
        leader = self.snapshot["individual"]["wealth"][0]

        self.assertEqual(
            embed.thumbnail.url,
            head_url(leader["minecraft_uuid"], leader.get("username", "")),
        )

    def test_clan_podium_rows_carry_no_icon(self):
        # Clans have no picture of their own; only players carry a head emoji, so a
        # clan board never prefixes its rows the way an individual board does —
        # even a stray registry entry must not leak onto one.
        snapshot = {
            "clan": {
                "wealth": [
                    {"clan": "Wolves", "value": 9, "display": "9"},
                    {"clan": "Ravens", "value": 8, "display": "8"},
                ]
            }
        }

        described = self.leaderboard.build_embed(
            snapshot, scope="clan", board="wealth", heads={"u1": "<:w:1>"}
        ).description

        self.assertIn("**#1**", described)
        self.assertIn("**[Wolves]**", described)
        self.assertNotIn("<:w:1>", described)

    def test_clan_thumbnail_is_always_the_brand_mark(self):
        # Clans have no picture of their own, so every clan board shows the same
        # brand mark regardless of the row — including a stray "icon" key an old
        # payload might still carry before every Paper server is redeployed.
        from minecraft_bot.presentation import MARK_ICON_URL

        with_stray_icon = self.leaderboard.build_embed(
            {"clan": {"wealth": [{"clan": "Wolves", "icon": "https://example.com/w.png"}]}},
            scope="clan",
            board="wealth",
        )
        without = self.leaderboard.build_embed(
            {"clan": {"wealth": [{"clan": "Ravens"}]}}, scope="clan", board="wealth"
        )

        self.assertEqual(with_stray_icon.thumbnail.url, MARK_ICON_URL)
        self.assertEqual(without.thumbnail.url, MARK_ICON_URL)

    def test_thumbnail_is_a_remote_url_not_an_attachment(self):
        # The dropdown replies are ephemeral and cannot carry a file, so an
        # attachment:// thumbnail silently rendered nothing on those boards.
        for scope in ("individual", "clan"):
            with self.subTest(scope=scope):
                embed = self.leaderboard.build_embed(
                    self.snapshot, scope=scope, board="wealth"
                )

                self.assertTrue(embed.thumbnail.url.startswith("https://"))

    def test_bedrock_heads_use_the_name_lookup(self):
        # Floodgate UUIDs are zero-prefixed and unresolvable by the Java head
        # service, so a Bedrock player on the podium previously got no head.
        from minecraft_bot.presentation import head_url

        bedrock = head_url("00000000-0000-0000-0009-01f9d1ebbeb2", ".Wv4mp")
        java = head_url("5ebdc316-b5d6-4f32-8afb-330642f6ff2a", "MinimumOrc")

        self.assertIn("Wv4mp", bedrock)
        self.assertNotIn("00000000", bedrock)
        self.assertIn("5ebdc316-b5d6-4f32-8afb-330642f6ff2a", java)

    def test_thumbnail_falls_back_to_the_brand_when_a_board_is_empty(self):
        from minecraft_bot.presentation import MARK_ICON_URL, MARK_PATH

        embed = self.leaderboard.build_embed(
            {"individual": {"wealth": []}}, scope="individual", board="wealth"
        )

        self.assertEqual(embed.thumbnail.url, MARK_ICON_URL)
        self.assertTrue(MARK_PATH.is_file(), "the mark asset must ship with the repo")

    def test_heads_are_shown_for_the_podium_only(self):
        heads = {"u1": "<:a:1>", "u2": "<:b:2>", "u3": "<:c:3>", "u4": "<:d:4>"}

        embed = self.leaderboard.build_embed(
            self.snapshot, scope="individual", board="wealth", heads=heads
        )

        for markdown in ("<:a:1>", "<:b:2>", "<:c:3>"):
            self.assertIn(markdown, embed.description)
        self.assertNotIn("<:d:4>", embed.description)

    def test_empty_board_reads_as_a_sentence_not_a_blank(self):
        embed = self.leaderboard.build_embed({}, scope="individual", board="kills")

        self.assertIn("No standings yet", embed.description)

    def test_clan_boards_exclude_per_player_only_types(self):
        self.assertEqual(tuple(self.leaderboard.boards_for("clan")), ("wealth", "kills"))
        self.assertEqual(tuple(self.leaderboard.boards_for("individual")), ("wealth", "kills"))

    def test_emoji_names_are_discord_safe(self):
        self.assertEqual(self.leaderboard._emoji_name("Not.A-Name!"), "mgx_head_NotAName")
        self.assertTrue(self.leaderboard._emoji_name("").endswith("player"))
        self.assertLessEqual(len(self.leaderboard._emoji_name("x" * 60)), 32)

    def test_at_most_ten_rows_are_shown(self):
        rows = [
            {"minecraft_uuid": f"u{n}", "username": f"p{n}", "value": 100 - n, "display": str(100 - n)}
            for n in range(12)
        ]
        embed = self.leaderboard.build_embed(
            {"individual": {"wealth": rows}}, scope="individual", board="wealth"
        )

        self.assertEqual(embed.description.count("#"), 10)
        self.assertNotIn("p10", embed.description)
        self.assertNotIn("p11", embed.description)

    def test_every_place_uses_the_same_number_formatting(self):
        embed = self.leaderboard.build_embed(self.snapshot, scope="individual", board="wealth")
        blocks = embed.description.split("\n\n")

        for block in blocks:
            self.assertRegex(block, r"\*\*#\d+\*\*")
            self.assertIn("`", block)

    def test_row_reads_clan_then_discord_then_minecraft(self):
        embed = self.leaderboard.build_embed(
            self.snapshot,
            scope="individual",
            board="wealth",
            linked={"u1": "12345"},
        )
        first = embed.description.split("\n\n")[0]

        self.assertLess(first.index("[LUCKY]"), first.index("<@12345>"))
        self.assertIn("mits", first)

    def test_linked_discord_account_is_mentioned_beside_the_username(self):
        embed = self.leaderboard.build_embed(
            self.snapshot,
            scope="individual",
            board="wealth",
            linked={"u1": "12345"},
        )
        first = embed.description.split("\n\n")[0]

        self.assertIn("mits", first)
        self.assertIn("<@12345>", first)

    def test_unlinked_players_show_no_mention(self):
        embed = self.leaderboard.build_embed(
            self.snapshot,
            scope="individual",
            board="wealth",
            linked={"u1": "12345"},
        )
        second = embed.description.split("\n\n")[1]

        self.assertIn("kai", second)
        self.assertNotIn("<@", second)


class MinecraftLeaderboardRestartTests(unittest.IsolatedAsyncioTestCase):
    """A bot restart empties in-memory standings; the posted board must survive it."""

    def _bot(self, *, snapshot, message_id):
        bot = object.__new__(MinecraftAccessBot)
        bot.bridge = SimpleNamespace(latest_leaderboard=snapshot)
        bot.data = SimpleNamespace(
            get_config=AsyncMock(side_effect=lambda key, default=None: {
                "leaderboard_channel_id": 999,
                "leaderboard_message_id": message_id,
            }.get(key, default)),
            set_config=AsyncMock(),
        )
        bot.get_channel = lambda _id: SimpleNamespace(guild=None)
        return bot

    async def test_empty_standings_do_not_blank_a_posted_board(self):
        bot = self._bot(snapshot={}, message_id=555)

        result = await bot._refresh_leaderboard_message()

        self.assertIsNone(result)
        bot.data.set_config.assert_not_awaited()


class MinecraftPodiumEmojiCapacityTests(unittest.IsolatedAsyncioTestCase):
    """A guild at Discord's emoji cap must not be asked for more, every refresh."""

    def _guild(self, *, limit, used):
        # Names avoid the head prefix so the orphan sweep leaves them alone.
        existing = [SimpleNamespace(id=n + 1, name=f"other{n}") for n in range(used)]
        return SimpleNamespace(
            id=1,
            emoji_limit=limit,
            emojis=existing,
            fetch_emojis=AsyncMock(return_value=existing),
            get_emoji=lambda _id: None,
        )

    def _store(self):
        from minecraft_bot.leaderboard import HeadEmojiStore

        bot = SimpleNamespace(
            data=SimpleNamespace(get_config=AsyncMock(return_value={}), set_config=AsyncMock())
        )
        store = HeadEmojiStore(bot)
        store._create = AsyncMock(return_value=None)
        return store

    @property
    def _snapshot(self):
        return {
            "individual": {
                "wealth": [
                    {"minecraft_uuid": f"u{n}", "username": f"p{n}", "value": 10 - n}
                    for n in range(3)
                ]
            }
        }

    async def test_a_full_guild_is_never_asked_to_create(self):
        store = self._store()

        await store.sync(self._guild(limit=250, used=250), self._snapshot)

        store._create.assert_not_awaited()

    async def test_partial_room_creates_only_what_fits(self):
        store = self._store()

        await store.sync(self._guild(limit=250, used=249), self._snapshot)

        self.assertEqual(store._create.await_count, 1)


class MinecraftPodiumDuplicateTests(unittest.IsolatedAsyncioTestCase):
    """The stale gateway cache used to make the bot recreate heads it already had."""

    def _store(self, registry):
        from minecraft_bot.leaderboard import HeadEmojiStore

        bot = SimpleNamespace(
            data=SimpleNamespace(
                get_config=AsyncMock(return_value=registry), set_config=AsyncMock()
            )
        )
        store = HeadEmojiStore(bot)
        store._create = AsyncMock(return_value=None)
        return store

    async def test_an_existing_head_is_not_created_again(self):
        registry = {"u0": {"emoji_id": 77, "markdown": "<:h:77>", "last_podium": "2026-08-14T00:00:00+00:00"}}
        store = self._store(registry)
        head = SimpleNamespace(id=77, name="mgx_head_p0", delete=AsyncMock())
        guild = SimpleNamespace(
            id=1,
            emoji_limit=250,
            emojis=[],
            fetch_emojis=AsyncMock(return_value=[head]),
            get_emoji=lambda _id: None,
        )
        snapshot = {"individual": {"wealth": [{"minecraft_uuid": "u0", "username": "p0", "value": 5}]}}

        await store.sync(guild, snapshot)

        store._create.assert_not_awaited()
        head.delete.assert_not_awaited()

    async def test_a_changed_source_remints_the_head(self):
        # A renamed Bedrock player keeps their uuid key, so without comparing the
        # stored source the board would show their old name's head indefinitely.
        store = self._store(
            {
                "u1": {
                    "emoji_id": 77,
                    "markdown": "<:w:77>",
                    "source": "https://example.com/old-name.png",
                    "last_podium": "2026-08-14T00:00:00+00:00",
                }
            }
        )
        existing = SimpleNamespace(id=77, name="mgx_head_oldname", delete=AsyncMock())
        guild = SimpleNamespace(
            id=1,
            emoji_limit=250,
            emojis=[],
            fetch_emojis=AsyncMock(return_value=[existing]),
            get_emoji=lambda _id: None,
        )
        snapshot = {
            "individual": {
                "wealth": [{"minecraft_uuid": "u1", "username": "newname", "value": 9}]
            }
        }

        with patch(
            "minecraft_bot.leaderboard.head_url",
            return_value="https://example.com/new-name.png",
        ):
            await store.sync(guild, snapshot)

        store._create.assert_awaited_once()
        self.assertEqual(store._create.await_args.args[-1], "https://example.com/new-name.png")
        existing.delete.assert_awaited_once()

    async def test_an_unchanged_source_is_left_alone(self):
        store = self._store(
            {
                "u1": {
                    "emoji_id": 77,
                    "markdown": "<:w:77>",
                    "source": "https://example.com/name.png",
                    "last_podium": "2026-08-14T00:00:00+00:00",
                }
            }
        )
        existing = SimpleNamespace(id=77, name="mgx_head_name", delete=AsyncMock())
        guild = SimpleNamespace(
            id=1,
            emoji_limit=250,
            emojis=[],
            fetch_emojis=AsyncMock(return_value=[existing]),
            get_emoji=lambda _id: None,
        )
        snapshot = {
            "individual": {
                "wealth": [{"minecraft_uuid": "u1", "username": "name", "value": 9}]
            }
        }

        with patch(
            "minecraft_bot.leaderboard.head_url",
            return_value="https://example.com/name.png",
        ):
            await store.sync(guild, snapshot)

        store._create.assert_not_awaited()
        existing.delete.assert_not_awaited()

    async def test_unowned_head_emojis_are_swept_up(self):
        store = self._store({})
        duplicate = SimpleNamespace(id=99, name="mgx_head_p0", delete=AsyncMock())
        unrelated = SimpleNamespace(id=100, name="party_blob", delete=AsyncMock())
        guild = SimpleNamespace(
            id=1,
            emoji_limit=250,
            emojis=[],
            fetch_emojis=AsyncMock(return_value=[duplicate, unrelated]),
            get_emoji=lambda _id: None,
        )

        await store.sync(guild, {"individual": {}})

        duplicate.delete.assert_awaited_once()
        unrelated.delete.assert_not_awaited()


class MinecraftHeadPurgeTests(unittest.IsolatedAsyncioTestCase):
    async def test_only_head_emojis_are_removed(self):
        from minecraft_bot.leaderboard import purge_head_emojis

        head = SimpleNamespace(name="mgx_head_mits", delete=AsyncMock())
        other = SimpleNamespace(name="party_blob", delete=AsyncMock())
        guild = SimpleNamespace(fetch_emojis=AsyncMock(return_value=[head, other]))

        removed, failed = await purge_head_emojis(guild)

        self.assertEqual((removed, failed), (1, 0))
        head.delete.assert_awaited_once()
        other.delete.assert_not_awaited()

    async def test_failures_are_counted_not_raised(self):
        import discord

        from minecraft_bot.leaderboard import purge_head_emojis

        stubborn = SimpleNamespace(
            name="mgx_head_kai",
            delete=AsyncMock(
                side_effect=discord.HTTPException(Mock(status=500), "nope")
            ),
        )
        guild = SimpleNamespace(fetch_emojis=AsyncMock(return_value=[stubborn]))

        removed, failed = await purge_head_emojis(guild)

        self.assertEqual((removed, failed), (0, 1))


class MinecraftPodiumScopeTests(unittest.TestCase):
    """Every individual board mints heads for its own top three."""

    def _store(self):
        from minecraft_bot.leaderboard import HeadEmojiStore

        return HeadEmojiStore(SimpleNamespace())

    def test_every_individual_board_mints_its_own_podium(self):
        # Topping Distance Walked rarely means topping Richest, so restricting
        # this to one board left the other four showing bare rows.
        from minecraft_bot.leaderboard import DEFAULT_TYPE

        snapshot = {
            "individual": {
                DEFAULT_TYPE: [
                    {"minecraft_uuid": "rich", "username": "rich", "value": 9},
                ],
                "kills": [
                    {"minecraft_uuid": "killer", "username": "killer", "value": 9},
                ],
                "blocks_walked": [
                    {"minecraft_uuid": "walker", "username": "walker", "value": 9},
                ],
            }
        }

        podium = self._store()._podium_players(snapshot)

        self.assertIn("rich", podium)
        self.assertIn("killer", podium)
        self.assertNotIn("walker", podium)

    def test_clan_rows_never_mint_podium_subjects(self):
        # Clans have no picture of their own, so nothing about a clan board should
        # ever reach the emoji-minting pipeline.
        snapshot = {
            "individual": {},
            "clan": {
                "wealth": [
                    {"clan": "Wolves", "value": 9},
                    {"clan": "Ravens", "value": 4},
                ]
            },
        }

        subjects = self._store()._podium_subjects(snapshot)

        self.assertEqual(subjects, {})

    def test_clan_rows_never_mint_player_heads(self):
        from minecraft_bot.leaderboard import DEFAULT_TYPE

        snapshot = {
            "individual": {DEFAULT_TYPE: []},
            "clan": {DEFAULT_TYPE: [{"clan": "Wolves", "value": 9}]},
        }

        self.assertEqual(self._store()._podium_players(snapshot), {})

    def test_no_more_than_three_are_minted(self):
        from minecraft_bot.leaderboard import DEFAULT_TYPE, PODIUM

        snapshot = {
            "individual": {
                DEFAULT_TYPE: [
                    {"minecraft_uuid": f"u{n}", "username": f"p{n}", "value": 10 - n}
                    for n in range(8)
                ]
            }
        }

        self.assertEqual(len(self._store()._podium_players(snapshot)), PODIUM)


class MinecraftBoardSelectRoutingTests(unittest.TestCase):
    """An unregistered DynamicItem never gets its callback, so Discord times out."""

    def test_template_matches_the_custom_ids_the_view_emits(self):
        from minecraft_bot.leaderboard import BoardSelect, LeaderboardView

        pattern = BoardSelect.__discord_ui_compiled_template__
        emitted = [
            item.custom_id
            for scope in ("individual", "clan")
            for item in LeaderboardView(scope).children
        ]

        self.assertEqual(len(emitted), 2)
        for custom_id in emitted:
            self.assertTrue(
                pattern.fullmatch(custom_id),
                f"{custom_id} would not route back to BoardSelect",
            )

    def test_scope_is_recovered_from_the_custom_id(self):
        from minecraft_bot.leaderboard import BoardSelect

        pattern = BoardSelect.__discord_ui_compiled_template__

        self.assertEqual(pattern.fullmatch("mgx_board:clan")["scope"], "clan")
        self.assertEqual(pattern.fullmatch("mgx_board:individual")["scope"], "individual")


class MinecraftRankSyncSafetyTests(unittest.IsolatedAsyncioTestCase):
    """An unknown Discord member must never be read as "this player has no rank"."""

    def _bridge(self):
        from minecraft_bot.bridge import MinecraftBridgeServer

        bridge = object.__new__(MinecraftBridgeServer)
        bridge._peer_protocol_version = 5
        # `connected` is a property derived from the socket.
        bridge._socket = SimpleNamespace(closed=False)
        bridge._send = AsyncMock()
        return bridge

    async def _payload(self, **kwargs):
        bridge = self._bridge()
        await bridge.send_player_profile(
            minecraft_uuid="123e4567-e89b-12d3-a456-426614174000",
            level=0,
            extra_hearts=0,
            elite=False,
            **kwargs,
        )
        return bridge._send.await_args.args[1]

    async def test_rank_is_omitted_when_the_member_is_unknown(self):
        payload = await self._payload(rank_group="", rank_known=False)

        self.assertNotIn("rank_group", payload)

    async def test_an_empty_rank_still_clears_when_the_member_is_known(self):
        payload = await self._payload(rank_group="", rank_known=True)

        self.assertEqual(payload["rank_group"], "")


class MinecraftApplicationCardImageTests(unittest.TestCase):
    """Editing with attachments=[] stripped the image the embed still pointed at."""

    def test_pending_verification_keeps_its_image(self):
        from minecraft_bot.presentation import (
            VERIFY_FILENAME,
            application_card_files,
        )

        files = application_card_files(
            SimpleNamespace(status=AccessStatus.PENDING_VERIFICATION)
        )

        self.assertEqual([f.filename for f in files], [VERIFY_FILENAME])

    def test_later_stages_carry_no_attachment(self):
        from minecraft_bot.presentation import application_card_files

        for status in (
            AccessStatus.VERIFIED,
            AccessStatus.VERIFIED,
            AccessStatus.REVOKED,
        ):
            with self.subTest(status=status):
                self.assertEqual(application_card_files(SimpleNamespace(status=status)), [])


class MinecraftApplicationPanelTests(unittest.TestCase):
    def test_welcome_embed_credits_the_partnership_in_bold(self):
        from minecraft_bot.presentation import application_welcome_embed

        description = application_welcome_embed().description

        self.assertIn(
            "**Mysterious Girlfriend X Discord, in partnership with "
            "r/MysteriousGirlfriendX.**",
            description,
        )
        self.assertNotIn("presents", description)

    def test_both_panels_pitch_the_server_the_same_way(self):
        # The same person reads the application panel before joining and the
        # information panel after, so two descriptions meant two answers.
        from minecraft_bot.presentation import (
            SERVER_TAGLINE_PARAGRAPHS,
            application_welcome_embed,
        )
        from minecraft_bot import information

        welcome = application_welcome_embed().description
        panel = information.overview_embed(0).description

        for paragraph in SERVER_TAGLINE_PARAGRAPHS:
            with self.subTest(paragraph=paragraph[:32]):
                self.assertIn(paragraph, welcome)
                self.assertIn(paragraph, panel)

    def test_welcome_showcases_what_the_server_offers(self):
        # Someone reading this is deciding whether the server suits them, which
        # they cannot tell from atmosphere alone.
        from minecraft_bot.presentation import SERVER_FEATURES, application_welcome_embed

        described = dict(SERVER_FEATURES)
        shown = {
            field.name: field
            for field in application_welcome_embed().fields
            if field.name in described
        }

        for feature in (
            "Economy",
            "Clans",
            "Levels",
            "Voice chat",
            "Leaderboards",
            "Crossplay",
        ):
            with self.subTest(feature=feature):
                self.assertIn(feature, shown)
                self.assertEqual(described[feature], shown[feature].value)
                # Columns, like the Contact Staff panel — one feature per field
                # with its own name, so the showcase needs no header.
                self.assertTrue(shown[feature].inline)

    def test_welcome_only_advertises_commands_that_exist(self):
        # /spawn was once documented on a server that had never installed it.
        # The front door is the worst place to promise a command that errors.
        import re

        from minecraft_bot import information
        from minecraft_bot.presentation import application_welcome_embed

        welcome = application_welcome_embed()
        advertised = set(re.findall(r"`(/[a-z]+)`", welcome.description or ""))
        for field in welcome.fields:
            advertised.update(re.findall(r"`(/[a-z]+)`", field.value))

        documented = []
        for _label, builder in information.PAGES.values():
            documented += [field.value for field in builder(0).fields]
        for sections in information.SECTIONS.values():
            for _label, builder in sections.values():
                documented += [field.value for field in builder(0).fields]
        catalogue = "\n".join(documented)

        # The showcase is allowed to name no commands at all — it sells the
        # server, it does not teach it. The guarantee is only that anything it
        # does name is a command the server actually has.
        for command in sorted(advertised):
            with self.subTest(command=command):
                self.assertIn(command, catalogue)

    def _rules_text(self, *, agreement=False):
        from minecraft_bot.presentation import rules_embed

        embed = rules_embed(agreement=agreement)
        parts = [embed.description or ""]
        for field in embed.fields:
            parts += [field.name, field.value]
        return "\n".join(parts)

    def _rule(self, heading):
        from minecraft_bot.presentation import rules_embed

        for field in rules_embed().fields:
            if field.name == heading:
                return field.value
        raise AssertionError(f"no rule named {heading!r}")

    def test_griefing_is_allowed_and_says_so_first(self):
        # The policy people most need to know before they build anything, and the
        # one most servers have the other way round.
        from minecraft_bot.presentation import rules_embed

        self.assertEqual(rules_embed().fields[0].name, "1. Griefing is allowed")

    def test_server_builds_are_the_stated_exception(self):
        rule = self._rule("3. Server builds are the exception")

        self.assertIn("server-coordinated", rule)
        # A build staff forgot to region is still off limits; the plugin refusing
        # the block is the usual signal, never the definition.
        self.assertIn("oversight rather than permission", rule)
        self.assertIn("WorldGuard", rule)

    def test_each_rule_is_its_own_field(self):
        # A heading in the description gets its own paragraph margin and floats
        # away from its rule. A field name is drawn tight above its value.
        from minecraft_bot.presentation import SERVER_RULES, rules_embed

        embed = rules_embed()
        headings = [field.name for field in embed.fields]

        self.assertEqual(headings[: len(SERVER_RULES)], [name for name, _ in SERVER_RULES])
        for field in embed.fields:
            with self.subTest(rule=field.name):
                self.assertFalse(field.inline)
                self.assertNotIn("**", field.name, "Discord bolds field names itself")
                self.assertLessEqual(len(field.value), 1024)

    def test_rules_fit_one_embed(self):
        from minecraft_bot.presentation import rules_embed

        embed = rules_embed(agreement=True)
        total = (
            len(embed.title)
            + len(embed.description or "")
            + sum(len(f.name) + len(f.value) for f in embed.fields)
        )

        self.assertLessEqual(len(embed.fields), 25)
        self.assertLessEqual(total, 6000)

    def test_rules_do_not_restate_the_mod_catalogue(self):
        # The permitted mods live on the information panel. Listing them here
        # too meant two places to update and two chances to disagree.
        described = self._rules_text()

        for mod in ("Sodium", "Litematica", "JourneyMap", "OptiFine"):
            with self.subTest(mod=mod):
                self.assertNotIn(mod, described)

    def test_agreement_variant_adds_the_undertaking(self):
        plain = self._rules_text()
        agreed = self._rules_text(agreement=True)

        self.assertNotIn("I Agree", plain)
        self.assertIn("Agreement", agreed)
        self.assertIn("I Agree", agreed)

    def test_rules_state_a_consequence(self):
        # Rules with no stated consequence read as suggestions.
        described = self._rules_text()

        self.assertIn("removal from the server", described)
        self.assertIn("Harassment", described)

    def test_in_game_conflict_is_walled_off_from_discord(self):
        # A rivalry that follows someone into Discord stops being a game, and
        # that is the moderation problem most likely to arrive with a crowd.
        rule = self._rule("7. Keep it in character")

        self.assertIn("What happens in Minecraft stays in Minecraft", rule)
        self.assertIn("Discord", rule)

    def test_rules_that_enumerate_use_bullets(self):
        # Conditions buried in a sentence get skimmed past. Where a rule draws a
        # line between two things, or lists several, it should show them.
        for heading, bullets in (
            ("1. Griefing is allowed", ("**Fair game** —", "**Off limits** —")),
            ("4. Keep PvP fair", ("**Allowed** —", "**Not allowed** —")),
            ("7. Keep it in character", ("**In character** —", "**Not** —")),
            ("9. Permitted mods and launchers", ("- Minimaps must", "- A launcher")),
        ):
            rule = self._rule(heading)
            for bullet in bullets:
                with self.subTest(rule=heading, bullet=bullet):
                    self.assertIn(bullet, rule)

    def test_rules_name_the_three_kinds_of_cheating(self):
        # A bare list of banned mods reads as exhaustive, so a player judges an
        # unfamiliar one against nothing. The categories are what they check.
        described = self._rules_text()

        for category in (
            "Shows what you could not see",
            "Plays for you",
            "Changes what your character can do",
        ):
            with self.subTest(category=category):
                self.assertIn(category, described)
        self.assertIn("Lunar Client", described)
        self.assertIn("player radar turned off", described)
        self.assertIn("Macros and auto-clickers are allowed", described)
        self.assertNotIn("auto-clickers, auto-walk", described)

    def test_rules_close_the_arguments_people_make(self):
        # Each of these answers a defence a player would otherwise offer, and
        # each was missing while the rules lived in one description.
        described = self._rules_text()

        for clause in (
            "looks abandoned",           # "it was abandoned, so it was not really griefing"
            "not an oversight",          # "the chest was unlocked"
            "being offline is not protection",
            "oversight rather than permission",  # "WorldGuard let me break it"
            "regardless of who started it",
            "the death you avoided",     # combat logging
            "judged on its effect",      # "I was not trying to run them off"
            "never excused as roleplay",
            "is not a defence",          # "I did not know"
            "ask before doing it",
        ):
            with self.subTest(clause=clause):
                self.assertIn(clause, described)


class MinecraftInformationPanelTests(unittest.TestCase):
    def setUp(self):
        from minecraft_bot import information

        self.information = information

    @staticmethod
    def embed_text(embed):
        """Description plus every field, since pages lay sections out as fields."""
        parts = [embed.description or ""]
        for field in embed.fields:
            parts.append(f"{field.name}\n{field.value}")
        return "\n".join(parts)

    def test_overview_carries_both_marks_it_references(self):
        # The logo runs wide beneath the panel; the question mark sits in the
        # corner, the same one the application panel uses for its own reading.
        # An attachment:// URI only resolves against the message its file was
        # uploaded with, so a missed file renders as blank space with no error.
        from minecraft_bot.presentation import ABOUT_ATTACHMENT_URI, LOGO_ATTACHMENT_URI

        embed = self.information.overview_embed(0)
        payload = self.information.message_payload(0)
        try:
            filenames = [file.filename for file in payload["attachments"]]
        finally:
            for file in payload["attachments"]:
                file.close()

        self.assertEqual(embed.image.url, LOGO_ATTACHMENT_URI)
        self.assertEqual(embed.thumbnail.url, ABOUT_ATTACHMENT_URI)
        for uri in (LOGO_ATTACHMENT_URI, ABOUT_ATTACHMENT_URI):
            with self.subTest(uri=uri):
                self.assertIn(uri.removeprefix("attachment://"), filenames)

    def test_every_page_follows_the_footer_rule(self):
        from minecraft_bot.presentation import BRAND_NAME

        for key, (_label, builder) in self.information.PAGES.items():
            with self.subTest(page=key):
                self.assertEqual(builder(0).footer.text, BRAND_NAME)
        self.assertEqual(self.information.overview_embed(0).footer.text, BRAND_NAME)

    def test_levels_page_mentions_every_milestone_role(self):
        from minecraft_bot.perks import LEVEL_ROLE_MILESTONES

        description = self.embed_text(self.information.levels_embed())

        # The mention already renders as "@Level 10", so restating the number
        # beside it was noise; the reward goes there instead.
        for role_id, _level in LEVEL_ROLE_MILESTONES:
            self.assertIn(f"<@&{role_id}>", description)
        self.assertIn(self.information.LEVELS_CHANNEL_URL, description)

    def test_levels_page_explains_how_levels_are_earned(self):
        description = self.embed_text(self.information.levels_embed())

        self.assertIn("Chatting", description)
        self.assertIn("voice", description)

    def test_milestone_ladder_shows_the_running_heart_total(self):
        # "+1 heart" beside every rung reads as though they do not accumulate.
        # The ladder shows what a member actually holds at each milestone.
        from minecraft_bot.perks import LEVEL_ROLE_MILESTONES, profile_for_role_ids

        described = self.embed_text(self.information.levels_embed())

        for index, (role_id, level) in enumerate(LEVEL_ROLE_MILESTONES):
            owned = [held for held, _m in LEVEL_ROLE_MILESTONES[: index + 1]]
            expected = profile_for_role_ids(owned).extra_hearts
            noun = "heart" if expected == 1 else "hearts"
            with self.subTest(level=level):
                self.assertIn(f"<@&{role_id}> — **{expected} extra {noun}**", described)

    def test_stacking_is_stated_on_both_perk_pages(self):
        # Members repeatedly misread these as alternatives rather than additive.
        for name, builder in (
            ("levels", self.information.levels_embed),
            ("boosting", self.information.boosting_embed),
        ):
            with self.subTest(page=name):
                described = self.embed_text(builder())

                self.assertIn("+25% damage", described)
                self.assertIn("6 extra hearts", described)
                self.assertIn("stack", described.lower())

    def test_clan_figures_match_the_plugin_that_enforces_them(self):
        import re
        from pathlib import Path

        bridge = (
            Path(__file__).resolve().parent.parent
            / "minecraft-bridge/src/main/java/bot/mgx/accessbridge"
        )
        store = (bridge / "ClanStore.java").read_text()
        service = (bridge / "ClanService.java").read_text()

        members = re.search(r"MAX_MEMBERS = (\d+);", store)
        self.assertIsNotNone(members, "MAX_MEMBERS vanished from ClanStore")
        self.assertEqual(self.information.CLAN_MAX_MEMBERS, int(members.group(1)))

        ttl = re.search(r"INVITE_TTL_MILLIS = (\d+) \* 60 \* 1000L;", store)
        self.assertIsNotNone(ttl, "INVITE_TTL_MILLIS shape changed in ClanStore")
        self.assertEqual(
            self.information.CLAN_INVITE_EXPIRY_MINUTES, int(ttl.group(1))
        )

        colours = re.search(r"THEME_COLORS = List\.of\(\s*(.*?)\s*\);", service, re.S)
        self.assertIsNotNone(colours, "THEME_COLORS vanished from ClanService")
        self.assertEqual(
            list(self.information.CLAN_THEME_COLOURS),
            re.findall(r'"([a-z]+)"', colours.group(1)),
        )

    def test_clan_ladder_matches_the_plugin_that_enforces_it(self):
        # ClanLevel.java is authoritative at runtime. Copy quoting a cost or a perk
        # the plugin will not honour sends players to bank the wrong materials.
        import re
        from pathlib import Path

        from minecraft_bot import clans

        source = (
            Path(__file__).resolve().parent.parent
            / "minecraft-bridge/src/main/java/bot/mgx/accessbridge/ClanLevel.java"
        ).read_text()

        match = re.search(r"int MAX_PUBLIC_LEVEL = (\d+);", source)
        self.assertIsNotNone(match, "MAX_PUBLIC_LEVEL vanished from ClanLevel")
        self.assertEqual(clans.MAX_PUBLIC_LEVEL, int(match.group(1)))
        self.assertNotIn("SECRET_LEVEL", source)

        perks = re.findall(
            r"(\d+), new Perks\((\d+), ([0-9.]+), ([0-9.]+), ([0-9.]+), ([0-9.]+), ([0-9.]+)\)",
            source,
        )
        self.assertEqual(clans.MAX_PUBLIC_LEVEL, len(perks), "the perk table changed shape")
        for level, hearts, strength, saturation, digging, resistance, speed in perks:
            with self.subTest(level=level):
                mirrored = clans.perks_for(int(level))
                self.assertEqual(int(hearts), mirrored.extra_hearts)
                self.assertEqual(round(float(strength) * 100), mirrored.strength)
                self.assertEqual(round(float(saturation) * 100), mirrored.saturation)
                self.assertEqual(round(float(digging) * 100), mirrored.digging_speed)
                self.assertEqual(round(float(resistance) * 100), mirrored.resistance)
                self.assertEqual(round(float(speed) * 100), mirrored.speed)

        costs_block = re.search(r"COSTS = Map\.of\((.*?)\n    \);", source, re.S)
        self.assertIsNotNone(costs_block, "COSTS vanished from ClanLevel")
        priced = {
            int(level): int(amount.replace("_", ""))
            for level, amount in re.findall(r"(\d+), new Cost\(([0-9_]+)L\)", costs_block.group(1))
        }
        self.assertEqual(set(clans.COSTS), set(priced), "the levels ClanLevel prices changed")
        for level, expected in priced.items():
            with self.subTest(level=level):
                self.assertEqual(expected, clans.cost_of(level))

        badges = re.search(r"BADGES = Map\.of\((.*?)\n    \);", source, re.S)
        self.assertIsNotNone(badges, "BADGES vanished from ClanLevel")
        drawn = {
            int(level): glyph
            for level, glyph in re.findall(r'\n\s+(\d+), "(.*?)"', badges.group(1))
        }
        self.assertEqual(clans.MAX_PUBLIC_LEVEL + 1, len(drawn), "the badge table changed shape")
        for level, glyph in drawn.items():
            with self.subTest(badge=level):
                self.assertEqual(glyph, clans.badge(level))

        starting = re.search(r"STARTING_MEMBER_SLOTS = (\d+);", source)
        self.assertIsNotNone(starting, "STARTING_MEMBER_SLOTS vanished from ClanLevel")
        self.assertEqual(clans.STARTING_MEMBER_SLOTS, int(starting.group(1)))

        tiers = re.findall(r"new MemberTier\((\d+), new Cost\(([0-9_]+)L\)\)", source)
        self.assertEqual(
            list(clans.MEMBER_TIERS),
            [(int(slots), int(amount.replace("_", ""))) for slots, amount in tiers],
            "the roster ladder drifted from the plugin that enforces it",
        )

    def test_there_is_no_secret_sixth_clan_level(self):
        from minecraft_bot import clans

        described = " ".join(self.embed_text(embed) for _name, embed in self._every_embed())

        self.assertNotIn("Dragon Egg", described)
        self.assertNotIn("Level 6", described)
        self.assertEqual(clans.PUBLIC_LEVELS, clans.visible_levels(0))
        self.assertEqual(clans.PUBLIC_LEVELS, clans.visible_levels(clans.MAX_PUBLIC_LEVEL))

    def test_clan_levels_page_publishes_no_prices_or_perk_figures(self):
        # The upgrade menu quotes the next level in game, at the moment it matters.
        # Printing the whole ladder here turned an ambition into a shopping list.
        from minecraft_bot import clans

        described = self.embed_text(self.information.clans_levels_embed())

        for level in clans.PUBLIC_LEVELS:
            with self.subTest(level=level):
                self.assertNotIn(f"Level {level}", described)
                self.assertNotIn(clans.described_cost(level), described)
        # No per-level perk figures either, which is the other half of the table.
        self.assertNotRegex(described, r"\+\d")
        # What is left still has to warn about the rules people resent finding late.
        self.assertRegex(described, r"(?i)leave the clan or get kicked")
        self.assertRegex(described, r"(?i)one way")
        self.assertRegex(described, r"(?i)disbanding the clan destroys the balance")

    def test_clan_roster_page_publishes_no_slot_prices(self):
        from minecraft_bot import clans

        embed = self.information.clans_members_embed()
        described = self.embed_text(embed)

        for slots, amount in clans.MEMBER_TIERS:
            with self.subTest(slots=slots):
                self.assertNotIn(f"**{slots}** — {amount}", described)
        self.assertNotIn("Slots priced in", described)
        # The limits are not prices: where a roster starts and where it stops are
        # what someone needs to plan around.
        self.assertIn(str(clans.STARTING_MEMBER_SLOTS), described)
        self.assertIn(str(clans.MAX_MEMBER_SLOTS), described)

    def test_no_clan_page_still_offers_a_way_to_take_donations_back(self):
        # Withdrawing is gone from the plugin. Copy that still advertises it would
        # send people looking for a command that no longer exists.
        described = " ".join(
            self.embed_text(embed)
            for name, embed in self._every_embed()
            if "clan" in name
        )

        self.assertNotIn("withdraw", described.lower())
        self.assertNotIn("/clans deposit", described)
        self.assertNotIn("/clans vault", described)

    def test_clan_pages_state_the_rules_people_get_wrong(self):
        described = " ".join(
            self.embed_text(embed)
            for name, embed in self._every_embed()
            if name.startswith("clans")
        )

        # Each of these is a rule the plugin enforces with an error message, so a
        # member who has not read it only discovers it by being refused.
        self.assertIn("cannot damage each other", described)
        self.assertIn(str(self.information.CLAN_MAX_MEMBERS), described)
        self.assertIn(str(self.information.CLAN_INVITE_EXPIRY_MINUTES), described)
        self.assertIn("online", described)
        self.assertRegex(described, r"(?i)leader cannot be kicked")
        self.assertRegex(described, r"(?i)only the leader can\s+remove")
        self.assertIn("as **staff**", described)

    def test_perk_figures_match_the_plugin_that_applies_them(self):
        # The bridge is authoritative at runtime; copy quoting a stale figure is
        # worse than copy omitting it. Parse the Java rather than trusting memory.
        import re
        from pathlib import Path

        from minecraft_bot import perks

        source = (
            Path(__file__).resolve().parent.parent
            / "minecraft-bridge/src/main/java/bot/mgx/accessbridge/PlayerPerkService.java"
        ).read_text()

        def constant(name):
            match = re.search(rf"{name} = ([0-9.]+)f?;", source)
            self.assertIsNotNone(match, f"{name} vanished from PlayerPerkService")
            return float(match.group(1))

        self.assertEqual(
            perks.ELITE_DAMAGE_PERCENT, round(constant("ELITE_DAMAGE_BONUS") * 100)
        )
        self.assertEqual(
            perks.BOOSTER_DAMAGE_PERCENT, round(constant("BOOSTER_DAMAGE_BONUS") * 100)
        )
        self.assertEqual(
            perks.BOOSTER_HUNGER_REDUCTION_PERCENT,
            round((1 - constant("BOOSTER_EXHAUSTION_MULTIPLIER")) * 100),
        )

    def test_members_are_never_offered_a_way_to_unlink(self):
        # Linking only ever adds. Unlinking is a staff action through /mcstaff
        # unlink, and a member-facing button would let someone shed an account to
        # dodge whatever is attached to it.
        from minecraft_bot.ui import AccountView

        labels = {str(item.label) for item in AccountView(123).children}
        for label in labels:
            self.assertNotIn("unlink", label.casefold())

        panel = {
            str(item.custom_id)
            for item in self.information.InformationView().children
        }
        for custom_id in panel:
            self.assertNotIn("unlink", custom_id.casefold())

        described = " ".join(self.embed_text(embed) for _name, embed in self._every_embed())
        self.assertNotIn("unlink", described.casefold())

    def test_in_game_perk_copy_quotes_no_figure_of_its_own(self):
        # /perks told players "+5% direct combat damage" for months while the plugin
        # applied 15%, because the copy restated the number by hand. Nothing that
        # players read may hard-code a percentage the code owns.
        import re
        from pathlib import Path

        bridge = (
            Path(__file__).resolve().parent.parent
            / "minecraft-bridge/src/main/java/bot/mgx/accessbridge"
        )
        for name in ("GuideService.java", "PlayerMenuService.java"):
            source = (bridge / name).read_text()
            with self.subTest(source=name):
                # A percentage in a player-facing string, rather than derived from
                # PlayerPerkService, is exactly the bug that got shipped.
                literal = re.findall(r'"[^"]*?\+\d+%[^"]*?"', source)
                self.assertEqual(
                    [],
                    literal,
                    f"{name} hard-codes a perk percentage; derive it from "
                    "PlayerPerkService instead",
                )
                if "ELITE_DAMAGE_BONUS" in source:
                    self.assertIn(
                        "PlayerPerkService.ELITE_DAMAGE_BONUS",
                        source,
                        f"{name} should read the bonus from the class that applies it",
                    )

    def test_boosting_page_states_the_stacked_totals(self):
        description = self.embed_text(self.information.boosting_embed())

        self.assertIn("+10% damage", description)
        self.assertIn("+25% damage", description)
        self.assertIn("6 extra hearts", description)

    def test_buttons_route_back_to_their_page(self):
        pattern = self.information.InformationButton.__discord_ui_compiled_template__
        link_pattern = self.information.LinkEditionButton.__discord_ui_compiled_template__
        emitted = [item.custom_id for item in self.information.InformationView().children]

        # Every page, plus the link action, and nothing that routes nowhere.
        pages = [custom_id for custom_id in emitted if pattern.fullmatch(custom_id)]
        links = [custom_id for custom_id in emitted if link_pattern.fullmatch(custom_id)]
        self.assertEqual(len(pages), len(self.information.PAGES))
        self.assertEqual(len(links), 1)
        self.assertEqual(len(emitted), len(pages) + len(links))
        for custom_id in pages:
            self.assertIn(pattern.fullmatch(custom_id)["page"], self.information.PAGES)

    def test_no_page_walks_an_accepted_member_through_applying(self):
        # Everyone reading this panel has already applied and been accepted, so
        # application troubleshooting belongs upstream, not here.
        for name, embed in self._every_embed():
            with self.subTest(page=name):
                described = self.embed_text(embed)

                self.assertNotIn("the application channel", described)
                self.assertNotIn("Continue Application", described)

    def test_overview_reads_as_a_post_acceptance_handbook(self):
        described = self.embed_text(self.information.overview_embed(0))

        self.assertIn("Java", described)
        self.assertIn("Bedrock", described)
        self.assertIn(self.information.SERVER_VERSION, described)
        self.assertNotIn("How to join", described)
        self.assertNotIn("Apply", described)

    def test_pages_do_not_claim_newer_clients_are_refused(self):
        # The client cap was removed on 2026-08-15: it could not be applied to Java
        # alone, because Geyser injects Bedrock players at the newest Java protocol,
        # so it was turning away every mobile player too. Copy still telling people
        # to downgrade their launcher would send them to fix a problem that is gone.
        builders = (
            ("overview", self.information.overview_embed),
            ("versions", self.information.PAGES["versions"][1]),
        )
        for page, builder in builders:
            with self.subTest(page=page):
                described = self.embed_text(builder(0))

                self.assertIn(self.information.JAVA_SUPPORTED_RANGE, described)
                self.assertNotRegex(described, r"(?i)turned away|refused|are blocked")

    def test_the_supported_range_has_no_upper_bound(self):
        # Anything of the form "up to <version>" is the shape the old cap took, and
        # would be wrong the moment Minecraft ships another release.
        from minecraft_bot import presentation

        self.assertNotRegex(presentation.JAVA_SUPPORTED_RANGE, r"(?i)up to")
        self.assertNotIn(presentation.SERVER_VERSION, presentation.JAVA_SUPPORTED_RANGE)

    def test_panel_is_titled_information(self):
        self.assertEqual(self.information.overview_embed(0).title, "Information")

    def test_connection_details_live_only_on_the_versions_page(self):
        # The front page is an overview, not a join guide; addresses and ports
        # belong behind the Server & Versions button with the rest of the setup.
        class _Settings:
            java_address = "play.example.net"
            bedrock_address = "bedrock.example.net"
            bedrock_port = 50549
            application_channel_id = 0

        settings = _Settings()
        overview = self.embed_text(self.information.overview_embed(settings))
        versions = self.embed_text(self.information.technical_embed(settings))

        for value in ("play.example.net", "bedrock.example.net", "50549"):
            with self.subTest(value=value):
                self.assertNotIn(value, overview)
                self.assertIn(value, versions)

    def _every_embed(self):
        """Every page and every category within a page, as (name, embed)."""
        for key, (_label, builder) in self.information.PAGES.items():
            yield key, builder(0)
        for page, sections in self.information.SECTIONS.items():
            for key, (_label, builder) in sections.items():
                yield f"{page}/{key}", builder(0)

    def test_commands_page_documents_the_everyday_essentials_commands(self):
        # These are the commands players reach for first; the panel shipped
        # without them once, which is worse than shipping no command list.
        described = " ".join(
            self.embed_text(embed)
            for name, embed in self._every_embed()
            if name.startswith("commands")
        )

        for command in (
            "/sethome",
            "/home",
            "/delhome",
            "/renamehome",
            "/back",
            "/warp",
            "/tpa",
            "/tpahere",
            "/tpaccept",
            "/tpdeny",
            "/tptoggle",
            "/msg",
            "/mail",
            "/ignore",
            "/afk",
            "/list",
            "/ping",
            "/shop",
            "/sell",
            "/ah",
            "/bal",
            "/pay",
            "/bounty",
        ):
            with self.subTest(command=command):
                self.assertIn(command, described)

    def test_panel_never_documents_staff_commands(self):
        # Members read this panel; staff tooling belongs behind its own
        # permission checks, not in a public directory of commands.
        for name, embed in self._every_embed():
            with self.subTest(page=name):
                described = self.embed_text(embed).lower()
                self.assertNotIn("/mcstaff", described)
                self.assertNotIn("staff only", described)
                self.assertNotIn("/co ", described)
                self.assertNotIn("/vanish", described)

    def test_pages_are_stated_rather_than_asked(self):
        # The panel reads as documentation, so headings are statements. A stray
        # question mark means a section slipped back into FAQ voice.
        for name, embed in self._every_embed():
            with self.subTest(page=name):
                for field in embed.fields:
                    self.assertNotIn("?", field.name)

    def test_notes_stay_inside_their_blockquote(self):
        # Prose placed after a blank line below quoted rows renders detached from
        # the field, reading as though it belongs to nothing. Notes go in the
        # quote; only a code block may follow, since those cannot be quoted.
        for name, embed in self._every_embed():
            for field in embed.fields:
                blocks = field.value.split("\n\n")
                quoted = any(line.startswith(">") for line in blocks[0].splitlines())
                for block in blocks[1:]:
                    if not quoted:
                        continue
                    with self.subTest(page=name, field=field.name):
                        self.assertTrue(
                            block.startswith(">") or "```" in block,
                            f"{name}/{field.name} strands {block[:40]!r}",
                        )

    def test_categories_stay_within_discord_limits(self):
        for name, embed in self._every_embed():
            with self.subTest(page=name):
                if embed.fields:
                    self.assertGreaterEqual(len(embed.fields), 2)
                else:
                    # A page may instead be a single document, as the rules are.
                    self.assertGreater(len(embed.description or ""), 200)
                for field in embed.fields:
                    self.assertFalse(field.inline)
                    self.assertLessEqual(len(field.value), 1024)

    def test_section_buttons_route_back_to_their_category(self):
        pattern = self.information.SectionButton.__discord_ui_compiled_template__
        for page, sections in self.information.SECTIONS.items():
            view = self.information.SectionView(page)
            emitted = [item.custom_id for item in view.children]

            self.assertEqual(len(emitted), len(sections))
            for custom_id in emitted:
                match = pattern.fullmatch(custom_id)
                self.assertIsNotNone(match, f"{custom_id} would not route back")
                self.assertEqual(match["page"], page)
                self.assertIn(match["section"], sections)

    def test_pages_with_categories_ship_the_buttons(self):
        # A page listing categories it does not attach buttons for would send
        # members hunting for controls that are not there.
        for page in self.information.SECTIONS:
            with self.subTest(page=page):
                self.assertIn(page, self.information.PAGES)
                self.assertTrue(self.information.SectionView(page).children)

    def test_pages_lay_sections_out_as_fields(self):
        # The panel's readability rests on fields, so a page collapsing back
        # into one long description should fail loudly.
        for key, (_label, builder) in self.information.PAGES.items():
            with self.subTest(page=key):
                embed = builder(0)
                self.assertGreaterEqual(len(embed.fields), 2)
                for field in embed.fields:
                    self.assertFalse(field.inline)
                    self.assertLessEqual(len(field.value), 1024)

    def test_rules_page_shows_the_same_rules_as_the_application(self):
        from minecraft_bot.presentation import rules_embed

        panel = self.information.PAGES["rules"][1](0)

        self.assertEqual(panel.description, rules_embed().description)
        # The agreement wording belongs to the application flow, not to a
        # reference page read by members who accepted months ago.
        self.assertNotIn("I Agree", panel.description)

    def test_pages_referencing_an_attachment_ship_the_file(self):
        # An ephemeral reply cannot borrow the panel's attachments, so a page
        # pointing at attachment:// must carry its own file or render broken.
        for key, (_label, builder) in self.information.PAGES.items():
            image = builder(0).image.url or ""
            with self.subTest(page=key):
                if image.startswith("attachment://"):
                    self.assertIn(key, self.information.PAGE_FILES)
                    handle = self.information.PAGE_FILES[key]()
                    self.assertEqual(image, f"attachment://{handle.filename}")
                    handle.close()
                else:
                    self.assertNotIn(key, self.information.PAGE_FILES)


    def test_panel_no_longer_says_presents(self):
        from minecraft_bot.presentation import application_welcome_embed

        self.assertNotIn("presents", self.embed_text(self.information.overview_embed(0)))
        self.assertNotIn("presents", application_welcome_embed().description)
        self.assertIn("in partnership with", application_welcome_embed().description)


class MaintenanceModeTests(unittest.IsolatedAsyncioTestCase):
    """A pre-launch hold: everything except actually playing carries on."""

    def _bot(self, *, enabled=False, delivered=True):
        bot = object.__new__(MinecraftAccessBot)
        bot.settings = SimpleNamespace(maintenance_mode=enabled)
        bot.bridge = SimpleNamespace(send_maintenance=AsyncMock(return_value=delivered))

        async def update(**updates):
            bot.settings = SimpleNamespace(**{**vars(bot.settings), **updates})
            return bot.settings

        bot.update_settings = update
        return bot

    async def test_closing_saves_the_setting_and_tells_paper(self):
        bot = self._bot()

        changed, delivered = await bot.set_maintenance_mode(True)

        self.assertTrue(changed)
        self.assertTrue(delivered)
        self.assertTrue(bot.settings.maintenance_mode)
        bot.bridge.send_maintenance.assert_awaited_once_with(True)

    async def test_setting_it_again_still_tells_paper(self):
        # Paper is the thing that enforces it, so a repeat is how an operator
        # recovers from a plugin that missed the first one.
        bot = self._bot(enabled=True)

        changed, delivered = await bot.set_maintenance_mode(True)

        self.assertFalse(changed)
        self.assertTrue(delivered)
        bot.bridge.send_maintenance.assert_awaited_once_with(True)

    async def test_an_undeliverable_change_is_still_saved(self):
        # The bridge restates it on connect, so the setting must survive being
        # made while Paper is down.
        bot = self._bot(delivered=False)

        changed, delivered = await bot.set_maintenance_mode(True)

        self.assertTrue(changed)
        self.assertFalse(delivered)
        self.assertTrue(bot.settings.maintenance_mode)

    def test_the_approval_reads_the_same_whether_or_not_the_server_is_held(self):
        # The hold used to have its own approval wording, which promised "you will
        # be notified here as soon as it opens" — and nothing ever sent that. A DM
        # cannot track a flag that may be lifted a minute later, so the server
        # states its own closure on the kick screen instead.
        from minecraft_bot.presentation import approval_embed

        addresses = dict(
            java_address="play.example.net",
            bedrock_address="bedrock.example.net",
            bedrock_port=19132,
        )
        closed = approval_embed(SimpleNamespace(maintenance_mode=True, **addresses))
        opened = approval_embed(SimpleNamespace(maintenance_mode=False, **addresses))

        self.assertEqual(closed.description, opened.description)
        self.assertIn("access is now active", opened.description)
        for address in ("play.example.net", "bedrock.example.net", "19132"):
            with self.subTest(address=address):
                self.assertIn(address, closed.description)

    def test_approval_points_to_the_actual_information_panel_channel_and_guide(self):
        from minecraft_bot.presentation import approval_embed

        embed = approval_embed(
            SimpleNamespace(
                java_address="play.example.net",
                bedrock_address="bedrock.example.net",
                bedrock_port=19132,
            ),
            987654321,
        )

        self.assertIn("<#987654321>", embed.description)
        self.assertIn("https://mysterioussmpx.blog/guide", embed.description)


class LinkEditionPromptTests(unittest.IsolatedAsyncioTestCase):
    """The information panel is the only surface an accepted member can reach.

    They lose the application channel on acceptance, so anything this card sends
    them elsewhere for is somewhere they cannot go.
    """

    def _bot(self, *, accounts=(), applications=(), approved=True):
        bot = object.__new__(MinecraftAccessBot)
        bot.config = SimpleNamespace(guild_id=1)
        bot.settings = SimpleNamespace(
            application_channel_id=4242,
            java_address="play.example.net",
            bedrock_address="bedrock.example.net",
            bedrock_port=19132,
        )
        bot.data = SimpleNamespace(
            list_accounts_for_user=AsyncMock(return_value=list(accounts)),
            list_access_for_user=AsyncMock(return_value=list(applications)),
            has_verified_access=AsyncMock(return_value=approved),
        )
        return bot

    async def test_verification_is_the_same_whether_or_not_they_already_play(self):
        unapproved, _view = await self._bot(approved=False).build_link_edition_prompt(99)
        accepted, _view = await self._bot(approved=True).build_link_edition_prompt(99)

        for embed in (unapproved, accepted):
            with self.subTest(title=embed.title):
                self.assertNotIn("staff review", embed.description)
                self.assertNotIn("application form", embed.description)
                self.assertIn("let straight in", embed.description)

    async def test_the_steps_are_the_same_three_either_way(self):
        for accounts in ([], [{"edition": "JAVA"}]):
            with self.subTest(accounts=accounts):
                embed, _view = await self._bot(accounts=accounts).build_link_edition_prompt(99)

                for step in ("**1.**", "**2.**", "**3.**"):
                    self.assertIn(step, embed.description)
                self.assertIn("let straight in", embed.description)

    async def test_a_member_with_nothing_linked_can_link_from_here(self):
        embed, view = await self._bot().build_link_edition_prompt(99)

        # Both editions offered, because nothing is linked to infer from.
        self.assertEqual(
            [item.label for item in view.children], ["Link Java", "Link Bedrock"]
        )
        # And no channel to go to, because they cannot open it.
        self.assertNotIn("<#4242>", embed.description)

    async def test_a_member_already_linked_is_still_offered_both_editions(self):
        bot = self._bot(accounts=[{"edition": "JAVA"}, {"edition": "BEDROCK"}])

        embed, view = await bot.build_link_edition_prompt(99)

        self.assertEqual(
            [item.label for item in view.children], ["Link Java", "Link Bedrock"]
        )
        self.assertEqual(embed.title, "Link Other Accounts")
        self.assertIn("bedrock.example.net", embed.description)
        self.assertIn("19132", embed.description)
        self.assertIn("play.example.net", embed.description)

    async def test_an_application_already_running_blocks_a_second_one(self):
        # Only a pending verification blocks another. Already-verified members
        # can keep linking more accounts.
        bot = self._bot(
            applications=[SimpleNamespace(status=AccessStatus.PENDING_VERIFICATION)],
        )
        embed, view = await bot.build_link_edition_prompt(99)
        self.assertIsNone(view)
        self.assertEqual(embed.title, "Verification Already Active")

        embed, view = await self._bot(
            applications=[SimpleNamespace(status=AccessStatus.VERIFIED)],
        ).build_link_edition_prompt(99)
        self.assertIsNotNone(view)
        self.assertEqual(embed.title, "Link Other Accounts")

class QuoteFormattingTests(unittest.TestCase):
    """The quote bar is what stops a page of headings running together."""

    def test_plain_lines_are_quoted(self):
        from minecraft_bot.presentation import quote_block

        self.assertEqual(quote_block("One\nTwo"), "> One\n> Two")

    def test_already_quoted_lines_are_left_alone(self):
        from minecraft_bot.presentation import quote_block

        self.assertEqual(quote_block("> One\nTwo"), "> One\n> Two")

    def test_blank_lines_are_quoted_so_the_block_stays_whole(self):
        # An unquoted blank line ends a Discord quote, splitting one section into
        # two and putting a bar round only half of it.
        from minecraft_bot.presentation import quote_block

        self.assertEqual(quote_block("One\n\nTwo"), "> One\n> \n> Two")

    def test_a_blank_line_keeps_the_space_after_its_bracket(self):
        # Discord starts a quote on "> ", bracket and space. A bare ">" is not
        # markup at all and is printed as a literal > in the middle of the block,
        # which is exactly how it looked on the live panel.
        from minecraft_bot.presentation import quote_block

        for line in quote_block("One\n\nTwo").splitlines():
            with self.subTest(line=line):
                self.assertTrue(line.startswith("> "))

    def test_a_value_holding_a_code_block_is_untouched(self):
        # Quoting a fence puts the markers inside the quote, and Discord then
        # prints them literally instead of drawing the block — which is how server
        # addresses would stop being copyable.
        from minecraft_bot.presentation import quote_block

        value = "Add the server\n```text\nplay.example.net\n```"

        self.assertEqual(quote_block(value), value)

    def test_every_information_page_is_quoted_wherever_it_safely_can_be(self):
        from types import SimpleNamespace

        from minecraft_bot import information

        settings = SimpleNamespace(
            java_address="play.example.net",
            bedrock_address="bedrock.example.net",
            bedrock_port=19132,
            application_channel_id=5,
        )
        embeds = [("overview", information.overview_embed(settings))]
        embeds += [(key, builder(settings)) for key, (_l, builder) in information.PAGES.items()]
        for page, sections in information.SECTIONS.items():
            embeds += [
                (f"{page}/{key}", builder(settings))
                for key, (_l, builder) in sections.items()
            ]

        for name, embed in embeds:
            with self.subTest(page=name):
                for line in (embed.description or "").splitlines():
                    if line.strip():
                        self.assertTrue(line.startswith(">"), f"{name} intro unquoted")
                for field in embed.fields:
                    if "```" in field.value:
                        continue  # addresses must stay copyable
                    if field.inline:
                        # A quote bar in a narrow column wraps the text to a couple
                        # of words a line; the bar is for full-width blocks.
                        continue
                    for line in field.value.splitlines():
                        if line.strip():
                            self.assertTrue(
                                line.startswith(">"),
                                f"{name} / {field.name} is unquoted",
                            )


class ApplyButtonExistingApplicationTests(unittest.IsolatedAsyncioTestCase):
    """Pressing Apply with an application already running reprints its card.

    This path used to build its own controls, which is how a Get Help button kept
    appearing on the submitted card after being removed everywhere else. Every
    surface asks application_card_view now.
    """

    async def _card(self, status):
        bot = SimpleNamespace(
            config=SimpleNamespace(guild_id=10),
            settings=SimpleNamespace(
                application_channel_id=20,
                java_address="j",
                bedrock_address="b",
                bedrock_port=1,
                maintenance_mode=False,
            ),
            data=SimpleNamespace(
                get_active_access_for_user=AsyncMock(
                    return_value=SimpleNamespace(
                        id=1,
                        status=status,
                        edition=Edition.JAVA,
                        claimed_username="PlayerOne",
                        verified_username="PlayerOne",
                        verification_expires_at=2_000_000_000,
                        auto_detect_edition=False,
                        verified_at=None,
                    )
                ),
                get_config=AsyncMock(return_value="30"),
            ),
            replace_application_card=AsyncMock(),
        )
        response = SimpleNamespace(send_message=AsyncMock(), send_modal=AsyncMock())
        interaction = SimpleNamespace(
            client=bot,
            guild_id=10,
            channel_id=20,
            message=SimpleNamespace(id=30),
            user=SimpleNamespace(id=99),
            response=response,
            original_response=AsyncMock(return_value=SimpleNamespace(id=99)),
        )

        await VerifyButton().callback(interaction)

        return response.send_message.await_args.kwargs

    async def test_a_pending_verification_offers_cancelling_and_help(self):
        kwargs = await self._card(AccessStatus.PENDING_VERIFICATION)

        self.assertEqual(
            [item.label for item in kwargs["view"].children],
            ["Cancel Pending Verification", "Get Help"],
        )


class ApplicantVoiceTests(unittest.TestCase):
    """The applicant-facing copy reads as a server writing to a member.

    Conversational asides — "that is it", "nothing is lost", "whenever you like" —
    are how it drifted before, so the ones already removed are pinned out.
    """

    CHATTY = (
        "that is it",
        "nothing is lost",
        "whenever you like",
        "you are in",
        "that is expected",
        "no form, no waiting",
        "give it a moment",
    )

    def _every_applicant_embed(self):
        from types import SimpleNamespace

        from minecraft_bot.presentation import (
            approval_embed,
            live_status_embed,
        )

        settings = SimpleNamespace(
            java_address="play.example.net",
            bedrock_address="bedrock.example.net",
            bedrock_port=19132,
            maintenance_mode=False,
        )
        held = SimpleNamespace(**{**vars(settings), "maintenance_mode": True})
        for status in AccessStatus:
            application = MinecraftAccess(
                id=1,
                guild_id="1",
                discord_user_id="9",
                edition=Edition.JAVA,
                claimed_username="PlayerOne",
                normalized_username="playerone",
                status=status,
                verification_expires_at=2_000_000_000,
                verified_username="PlayerOne",
                created_at=1,
                updated_at=1,
            )
            yield f"card:{status.value}", live_status_embed(application, settings)
            yield f"card-held:{status.value}", live_status_embed(application, held)
        yield "dm:approved", approval_embed(settings)
        yield "dm:approved-held", approval_embed(held)

    def _every_public_panel_embed(self):
        from minecraft_bot.presentation import (
            application_apply_embed,
            application_guide_embed,
            application_welcome_embed,
        )

        yield "welcome", application_welcome_embed()
        yield "guide", application_guide_embed()
        yield "join", application_apply_embed()

    def test_no_applicant_embed_slips_back_into_chattiness(self):
        for name, embed in self._every_applicant_embed():
            described = f"{embed.title} {embed.description}".casefold()
            for phrase in self.CHATTY:
                with self.subTest(embed=name, phrase=phrase):
                    self.assertNotIn(phrase, described)

    def test_the_public_panel_describes_verification_not_an_application(self):
        leftover = (
            "press **apply**",
            "staff review",
            "application form",
            "disconnected automatically",
            "before you apply",
            "apply to mysterious",
        )
        for name, embed in self._every_public_panel_embed():
            described = f"{embed.title} {embed.description}".casefold()
            described += " " + " ".join(
                f"{field.name} {field.value}".casefold() for field in embed.fields
            )
            for phrase in leftover:
                with self.subTest(embed=name, phrase=phrase):
                    self.assertNotIn(phrase, described)
            for phrase in self.CHATTY:
                with self.subTest(embed=name, chatty=phrase):
                    self.assertNotIn(phrase, described)

    def test_every_applicant_embed_leads_with_a_quote(self):
        for name, embed in self._every_applicant_embed():
            with self.subTest(embed=name):
                self.assertTrue(
                    embed.description.startswith(">"),
                    f"{name} does not open with a quoted line",
                )


class ApplicationCardCopyTests(unittest.TestCase):
    """The card answers one question: where am I, and what happens next."""

    def _application(self, status, **overrides):
        fields = dict(
            id=29,
            guild_id="1",
            discord_user_id="9",
            edition=Edition.JAVA,
            claimed_username="7saori",
            normalized_username="7saori",
            status=status,
            verification_expires_at=2_000_000_000,
            verified_username="7saori",
            created_at=1,
            updated_at=1,
        )
        fields.update(overrides)
        return MinecraftAccess(**fields)

    def _settings(self, **overrides):
        base = dict(
            java_address="play.example.net",
            bedrock_address="bedrock.example.net",
            bedrock_port=19132,
            maintenance_mode=False,
        )
        base.update(overrides)
        return SimpleNamespace(**base)

    def _text(self, embed):
        return " ".join(
            [embed.title or "", embed.description or ""]
            + [f"{field.name} {field.value}" for field in embed.fields]
        )

    def test_the_application_number_is_never_shown_to_the_applicant(self):
        # It identifies the record to staff, not to the person waiting on it.
        for status in AccessStatus:
            with self.subTest(status=status):
                embed = live_status_embed(self._application(status), self._settings())
                self.assertNotIn("#29", self._text(embed))

    def test_status_and_account_are_not_fields_of_their_own(self):
        for status in AccessStatus:
            with self.subTest(status=status):
                embed = live_status_embed(self._application(status), self._settings())
                names = {field.name for field in embed.fields}
                self.assertNotIn("Status", names)
                self.assertNotIn("Account", names)

    def test_the_title_says_where_they_are(self):
        expected = {
            AccessStatus.PENDING_VERIFICATION: "Verify Your Account",
            AccessStatus.VERIFIED: "Access Active",
        }
        for status, title in expected.items():
            with self.subTest(status=status):
                embed = live_status_embed(self._application(status), self._settings())
                self.assertEqual(embed.title, title)

    def test_the_two_screens_with_something_to_do_still_carry_it(self):
        # A deadline where one can run out, and an address where they must connect.
        # Trimming those away would make the card tidy and useless.
        verify = live_status_embed(
            self._application(AccessStatus.PENDING_VERIFICATION), self._settings()
        )
        approved = live_status_embed(
            self._application(AccessStatus.VERIFIED), self._settings()
        )

        self.assertIn("play.example.net", self._text(verify))
        self.assertIn("bedrock.example.net", self._text(verify))
        self.assertIn("19132", self._text(verify))
        self.assertIn("`7saori`", verify.description)
        self.assertIn("play.example.net", self._text(approved))
        self.assertIn("bedrock.example.net", self._text(approved))
        self.assertIn("19132", self._text(approved))

    def test_a_deadline_reads_as_part_of_the_sentence(self):
        # Discord renders it as "in 3 days", which sits naturally after a verb and
        # badly under a heading. Both timed screens say it inline and neither
        # carries a field for it.
        embed = live_status_embed(
            self._application(AccessStatus.PENDING_VERIFICATION), self._settings()
        )

        self.assertRegex(embed.description, r"expires <t:\d+:R>\.")
        self.assertNotIn("Finish by", {field.name for field in embed.fields})

    def test_the_card_does_not_branch_on_the_maintenance_hold(self):
        # A card is edited in place for as long as its token lives and then frozen,
        # so a hold it mentioned would be reported as current long after it was
        # lifted. The server says so itself, on the kick screen, when it is true.
        held = live_status_embed(
            self._application(AccessStatus.VERIFIED),
            self._settings(maintenance_mode=True),
        )
        open_server = live_status_embed(
            self._application(AccessStatus.VERIFIED),
            self._settings(maintenance_mode=False),
        )

        self.assertEqual(self._text(held), self._text(open_server))
        self.assertIn("play.example.net", self._text(held))

    def test_every_status_renders(self):
        for status in AccessStatus:
            with self.subTest(status=status):
                embed = live_status_embed(self._application(status), self._settings())
                self.assertTrue(embed.title)
                self.assertTrue(embed.description.startswith(">"))


class WelcomePanelTests(unittest.TestCase):
    def test_the_tagline_is_quoted_and_stays_one_block(self):
        # Both paragraphs under one bar. The blank line between them has to be
        # quoted too, or Discord ends the quote and only the first is inside it.
        from minecraft_bot.presentation import (
            SERVER_TAGLINE_PARAGRAPHS,
            application_welcome_embed,
        )

        lines = application_welcome_embed().description.splitlines()
        quoted = [line for line in lines if line.startswith(">")]

        for paragraph in SERVER_TAGLINE_PARAGRAPHS:
            with self.subTest(paragraph=paragraph[:30]):
                self.assertIn(f"> {paragraph}", quoted)
        self.assertEqual(len(quoted), len(SERVER_TAGLINE_PARAGRAPHS) * 2 - 1)

    def test_the_partnership_line_reads_as_a_header_above_the_quote(self):
        from minecraft_bot.presentation import application_welcome_embed

        first = application_welcome_embed().description.splitlines()[0]

        self.assertFalse(first.startswith(">"))
        self.assertIn("in partnership with", first)


class ApplicationGuideButtonTests(unittest.TestCase):
    """Before You Join shows the information panel's own pages.

    One set of pages behind both surfaces, so a change to a page reaches
    applicants and members alike instead of drifting between two copies.
    """

    def test_every_information_page_is_offered(self):
        from minecraft_bot.information import PAGES
        from minecraft_bot.presentation import application_guide_view

        offered = {
            item.custom_id.split(":", 1)[1]
            for item in application_guide_view().children
        }

        self.assertEqual(offered, set(PAGES))

    def test_linking_an_edition_is_not_offered_to_applicants(self):
        # It needs an account they do not have yet.
        from minecraft_bot.presentation import application_guide_view

        for item in application_guide_view().children:
            with self.subTest(button=item.custom_id):
                self.assertFalse(item.custom_id.startswith("mgx_info_link:"))

    def test_the_buttons_survive_a_restart(self):
        from minecraft_bot.presentation import application_guide_view

        self.assertTrue(application_guide_view().is_persistent())

    def test_the_guide_does_not_restate_the_welcome_features(self):
        from minecraft_bot.presentation import application_guide_embed

        names = {field.name for field in application_guide_embed().fields}
        described = " ".join(field.value.casefold() for field in application_guide_embed().fields)

        self.assertEqual(names, {"Can I play on my version?", "Nothing is safe"})
        for leftover in ("shop", "auction", "treasury", "leaderboard", "richest"):
            with self.subTest(leftover=leftover):
                self.assertNotIn(leftover, described)


class ApplicationCardReplacementTests(unittest.IsolatedAsyncioTestCase):
    """One application, one card.

    Submitting the written form produces a fresh reply while the card it was opened
    from is still on screen. Keeping both left two cards for one application, each
    being edited by the refresh that follows.
    """

    def _bot(self):
        bot = object.__new__(MinecraftAccessBot)
        bot._application_messages = {}
        return bot

    async def test_the_previous_card_is_deleted_and_the_new_one_remembered(self):
        bot = self._bot()
        old = SimpleNamespace(id=1, delete=AsyncMock())
        new = SimpleNamespace(id=2, delete=AsyncMock())
        bot.remember_application_message(7, old)

        await bot.replace_application_card(7, new)

        old.delete.assert_awaited_once()
        new.delete.assert_not_awaited()
        self.assertIs(bot._application_messages[7][0], new)

    async def test_replacing_a_card_with_itself_does_not_delete_it(self):
        bot = self._bot()
        card = SimpleNamespace(id=1, delete=AsyncMock())
        bot.remember_application_message(7, card)

        await bot.replace_application_card(7, card)

        card.delete.assert_not_awaited()
        self.assertIs(bot._application_messages[7][0], card)

    async def test_a_first_card_has_nothing_to_replace(self):
        bot = self._bot()
        card = SimpleNamespace(id=1, delete=AsyncMock())

        await bot.replace_application_card(7, card)

        card.delete.assert_not_awaited()
        self.assertIs(bot._application_messages[7][0], card)

    async def test_an_expired_ephemeral_that_cannot_be_deleted_is_left_alone(self):
        # Ephemeral replies die with their interaction token. Failing to delete one
        # must not lose the new card that replaced it.
        bot = self._bot()
        old = SimpleNamespace(
            id=1, delete=AsyncMock(side_effect=discord.NotFound(Mock(status=404), "gone"))
        )
        new = SimpleNamespace(id=2, delete=AsyncMock())
        bot.remember_application_message(7, old)

        await bot.replace_application_card(7, new)

        self.assertIs(bot._application_messages[7][0], new)


class LiveCardCancelButtonTests(unittest.IsolatedAsyncioTestCase):
    """The cancel button on the verification card.

    Persistent, so it acts on whoever presses it rather than on a member id
    captured when the card was drawn — a captured id would be wrong after a
    restart, and the card outlives the interaction that made it.
    """

    def _interaction(self, *, fails=False):
        cancel = AsyncMock(
            side_effect=InvalidTransition("No pending verification") if fails else None
        )
        return SimpleNamespace(
            client=SimpleNamespace(
                cancel_pending_verification=cancel,
                config=SimpleNamespace(guild_id=10),
            ),
            guild_id=10,
            user=SimpleNamespace(id=99),
            response=SimpleNamespace(defer=AsyncMock()),
            edit_original_response=AsyncMock(),
            followup=SimpleNamespace(send=AsyncMock()),
        )

    def _button(self):
        return next(
            item
            for item in LiveApplicationView().children
            if item.custom_id == "minecraft:live:cancel"
        )

    async def test_it_cancels_the_verification_of_whoever_pressed_it(self):
        interaction = self._interaction()

        await self._button().callback(interaction)

        interaction.client.cancel_pending_verification.assert_awaited_once_with(
            guild_id=10, discord_user_id=99
        )
        embed = interaction.edit_original_response.await_args.kwargs["embed"]
        self.assertEqual(embed.title, "Verification Cancelled")

    async def test_the_cancelled_card_keeps_no_controls_or_screenshot(self):
        # Cancelling drops the card out of the refresh table, so whatever is left on
        # screen here is final: a live Cancel button on it would go nowhere.
        interaction = self._interaction()

        await self._button().callback(interaction)

        kwargs = interaction.edit_original_response.await_args.kwargs
        self.assertIsNone(kwargs["view"])
        self.assertEqual(kwargs["attachments"], [])

    async def test_nothing_to_cancel_is_reported_rather_than_raised(self):
        interaction = self._interaction(fails=True)

        await self._button().callback(interaction)

        # Nothing was cancelled, so the card still describes something real and is
        # left alone; the refusal arrives beside it.
        interaction.edit_original_response.assert_not_awaited()
        kwargs = interaction.followup.send.await_args.kwargs
        self.assertEqual(kwargs["embed"].title, "Nothing to Cancel")
        self.assertTrue(kwargs["ephemeral"])

    async def test_the_outcome_replaces_the_card_instead_of_answering_beside_it(self):
        # A thinking reply would leave two messages: the dead verification card and
        # a separate note saying it had been cancelled.
        interaction = self._interaction()

        await self._button().callback(interaction)

        interaction.response.defer.assert_awaited_once_with()
        interaction.followup.send.assert_not_awaited()


class ApplicationCardViewTests(unittest.IsolatedAsyncioTestCase):
    """The card is drawn by the modal and redrawn by a background refresh.

    They have to agree. When they did not, Get Help was drawn and then stripped a
    moment later, which looked like the button flickering and disappearing.
    """

    def labels(self, view):
        if view is None:
            return []
        # A DynamicItem wraps the real button, so the label is one level down.
        return [
            getattr(child, "label", None) or getattr(getattr(child, "item", None), "label", None)
            for child in view.children
        ]

    def test_help_is_offered_on_the_step_somebody_can_get_stuck_on(self):
        # Verification is where a mistyped username or a connection that will not
        # go through leaves somebody unable to continue.
        self.assertIn(
            "Get Help",
            self.labels(application_card_view(AccessStatus.PENDING_VERIFICATION)),
        )

    def test_help_is_not_offered_once_there_is_nothing_left_to_do(self):
        # Waiting on staff is not a problem to be helped with, and a Get Help
        # button there only invites a ticket that says "waiting".
        for status in (
            AccessStatus.VERIFIED,
            AccessStatus.VERIFIED,
        ):
            with self.subTest(status=status):
                self.assertNotIn("Get Help", self.labels(application_card_view(status)))

    def test_finished_applications_carry_no_controls(self):
        for status in (
            AccessStatus.VERIFIED,
            AccessStatus.REVOKED,
            AccessStatus.EXPIRED,
            AccessStatus.CANCELLED,
            AccessStatus.REVOKED,
        ):
            with self.subTest(status=status):
                self.assertIsNone(application_card_view(status))

    def test_every_status_is_handled(self):
        for status in AccessStatus:
            with self.subTest(status=status):
                application_card_view(status)

    def test_card_views_persist_so_a_refresh_can_reattach_them(self):
        # update_live_card re-attaches these long after the interaction that made the
        # card. A view with a timeout would be dead by then.
        for status in AccessStatus:
            view = application_card_view(status)
            if view is not None:
                with self.subTest(status=status):
                    self.assertIsNone(view.timeout, f"{status} view must be persistent")

    async def test_refreshing_a_card_keeps_its_controls(self):
        # The original regression: the modal draws the card, the background refresh
        # redraws it, and the controls have to survive the second draw.
        bot = object.__new__(MinecraftAccessBot)
        bot._application_messages = {}
        bot._live_card_lock = asyncio.Lock()
        application = SimpleNamespace(
            id=7,
            status=AccessStatus.PENDING_VERIFICATION,
            discord_user_id="99",
            verified_username="PlayerOne",
            claimed_username="PlayerOne",
            edition=Edition.JAVA,
        )
        message = SimpleNamespace(edit=AsyncMock())
        bot._application_messages[7] = (message, time.monotonic() + 600)
        bot._application_card_embed = lambda _application: discord.Embed(title="card")

        await bot.update_live_card(application, create_if_missing=False)

        message.edit.assert_awaited_once()
        self.assertIn("Get Help", self.labels(message.edit.await_args.kwargs["view"]))


class MinecraftCapabilityTests(unittest.TestCase):
    """Discord must offer exactly what the server says a player may do."""

    def setUp(self):
        from minecraft_bot import capabilities

        self.capabilities = capabilities
        self.snapshot = {
            "players": {
                "leader-uuid": {
                    "clan": "LUCKY",
                    "clan_role": "leader",
                    "clan_members": 4,
                    "staff_tools": [],
                },
                "member-uuid": {
                    "clan": "LUCKY",
                    "clan_role": "member",
                    "clan_members": 4,
                    "staff_tools": [],
                },
                "mod-uuid": {"staff_tools": ["inspect", "kick"]},
            }
        }

    def _caps(self, uuid):
        return self.capabilities.capabilities_for(self.snapshot, uuid)

    def test_a_leader_may_manage_the_clan(self):
        caps = self._caps("leader-uuid")

        self.assertTrue(caps.may("disband"))
        self.assertTrue(caps.may("promote"))
        self.assertTrue(caps.may("invite"))
        self.assertFalse(caps.may("leave"))

    def test_a_member_may_only_leave(self):
        caps = self._caps("member-uuid")

        self.assertEqual([a for a, _ in caps.available_clan_actions()], ["leave", "donate"])
        self.assertFalse(caps.may("disband"))
        self.assertFalse(caps.may("invite"))
        self.assertFalse(caps.may("upgrade"))

    def test_staff_tools_are_limited_to_those_held(self):
        caps = self._caps("mod-uuid")

        offered = [key for key, _label in caps.available_staff_tools()]
        self.assertEqual(offered, ["inspect", "kick"])
        self.assertNotIn("ban", offered)

    def test_an_unknown_player_gets_nothing(self):
        caps = self._caps("stranger")

        self.assertFalse(caps.in_clan)
        self.assertFalse(caps.is_staff)
        self.assertEqual(caps.available_clan_actions(), [])
        self.assertEqual(caps.available_staff_tools(), [])

    def test_a_malformed_entry_is_treated_as_no_privileges(self):
        caps = self.capabilities.capabilities_for({"players": {"x": "nonsense"}}, "x")

        self.assertFalse(caps.in_clan)
        self.assertFalse(caps.is_staff)

    def test_every_clan_action_names_roles_that_exist(self):
        for action, (_label, roles) in self.capabilities.CLAN_ACTIONS.items():
            with self.subTest(action=action):
                self.assertTrue(set(roles) <= {"leader", "staff", "member"})


class MinecraftClanActionTests(unittest.IsolatedAsyncioTestCase):
    """Discord may only ask for actions the server says the player's role allows."""

    def _bot(self, *, role, connected=True):
        bot = object.__new__(MinecraftAccessBot)
        bot.data = SimpleNamespace(
            list_accounts_for_user=AsyncMock(return_value=[{"minecraft_uuid": "u1"}])
        )
        bot.bridge = SimpleNamespace(
            connected=connected,
            latest_capabilities={
                "players": {"u1": {"clan": "LUCKY", "clan_role": role, "staff_tools": []}}
            },
            run_clan_action=AsyncMock(return_value=(True, "The clan was disbanded.")),
        )
        return bot

    async def test_a_leader_may_disband(self):
        bot = self._bot(role="leader")

        embed = await bot.run_clan_action(1, "disband", "")

        bot.bridge.run_clan_action.assert_awaited_once()
        self.assertEqual(embed.title, "Clan Updated")

    async def test_a_refusal_from_the_server_is_shown_as_such(self):
        bot = self._bot(role="leader")
        bot.bridge.run_clan_action = AsyncMock(return_value=(False, "No clan has that name."))

        embed = await bot.run_clan_action(1, "disband", "")

        self.assertEqual(embed.title, "Clan Action Declined")
        self.assertIn("No clan has that name.", embed.description)

    async def test_a_member_may_not_disband(self):
        bot = self._bot(role="member")

        embed = await bot.run_clan_action(1, "disband", "")

        bot.bridge.run_clan_action.assert_not_awaited()
        self.assertEqual(embed.title, "Not Allowed")

    async def test_actions_needing_a_target_refuse_without_one(self):
        bot = self._bot(role="leader")

        embed = await bot.run_clan_action(1, "kick", "")

        bot.bridge.run_clan_action.assert_not_awaited()
        self.assertEqual(embed.title, "Missing Detail")

    async def test_nothing_is_sent_while_the_server_is_offline(self):
        bot = self._bot(role="leader", connected=False)

        embed = await bot.run_clan_action(1, "disband", "")

        bot.bridge.run_clan_action.assert_not_awaited()
        self.assertEqual(embed.title, "Server Offline")

    async def test_an_unlinked_user_is_told_to_link(self):
        bot = self._bot(role="leader")
        bot.data.list_accounts_for_user = AsyncMock(return_value=[])

        embed = await bot.run_clan_action(1, "leave", "")

        bot.bridge.run_clan_action.assert_not_awaited()
        self.assertEqual(embed.title, "No Linked Account")


class MinecraftBridgeAwaitedResultTests(unittest.IsolatedAsyncioTestCase):
    """A clan or staff action needs the server's real answer, not just "it was sent"."""

    def _bridge(self):
        from minecraft_bot.bridge import MinecraftBridgeServer

        bridge = object.__new__(MinecraftBridgeServer)
        bridge._pending_results = {}
        bridge._sent_this_connection = {}
        bridge._socket = SimpleNamespace(closed=False)
        bridge._send_lock = asyncio.Lock()
        return bridge

    async def test_a_matching_action_result_resolves_the_waiter(self):
        bridge = self._bridge()
        bridge._send = AsyncMock(
            side_effect=lambda *a, **kw: setattr(bridge, "_sent_key", kw["idempotency_key"])
        )

        async def respond_shortly():
            await asyncio.sleep(0)
            await bridge._handle_message(
                {
                    "type": "ACTION_RESULT",
                    "payload": {
                        "action_idempotency_key": bridge._sent_key,
                        "success": True,
                        "error": "It worked.",
                    },
                    "idempotency_key": "irrelevant",
                }
            )

        result, respond = await asyncio.gather(
            bridge._send_awaiting_result("ACTION", {"action": "CLAN_ACTION"}),
            respond_shortly(),
        )

        self.assertEqual(result, (True, "It worked."))

    async def test_a_timeout_is_reported_rather_than_hanging(self):
        bridge = self._bridge()
        bridge._send = AsyncMock()

        result = await bridge._send_awaiting_result(
            "ACTION", {"action": "CLAN_ACTION"}, timeout=0.01
        )

        self.assertFalse(result[0])
        self.assertIn("did not respond", result[1])

    async def test_an_offline_bridge_refuses_immediately(self):
        bridge = self._bridge()
        bridge._send = AsyncMock(side_effect=ConnectionError("offline"))

        result = await bridge._send_awaiting_result("ACTION", {"action": "CLAN_ACTION"})

        self.assertEqual(result, (False, "The Minecraft bridge is offline."))
        self.assertEqual(bridge._pending_results, {})

    async def test_a_result_for_an_unknown_key_falls_through_without_raising(self):
        bridge = self._bridge()
        bridge.data = SimpleNamespace(
            complete_outbox=AsyncMock(return_value=(None, None, False))
        )

        # No KeyError, no exception — a stray or duplicate ACTION_RESULT is inert.
        await bridge._handle_message(
            {
                "type": "ACTION_RESULT",
                "payload": {"action_idempotency_key": "never-registered", "success": True},
                "idempotency_key": "irrelevant",
            }
        )


class MinecraftStaffActionTests(unittest.IsolatedAsyncioTestCase):
    """Discord may only ask for a tool the linked account's own LuckPerms permission grants."""

    def _bot(self, *, tools, connected=True):
        bot = object.__new__(MinecraftAccessBot)
        bot.data = SimpleNamespace(
            list_accounts_for_user=AsyncMock(return_value=[{"minecraft_uuid": "u1"}])
        )
        bot.bridge = SimpleNamespace(
            connected=connected,
            latest_capabilities={"players": {"u1": {"staff_tools": tools}}},
            run_staff_action=AsyncMock(return_value=(True, "mits was kicked.")),
        )
        return bot

    async def test_a_held_tool_is_sent(self):
        bot = self._bot(tools=["kick"])

        embed = await bot.run_staff_action(1, "kick", target="mits", reason="", duration="")

        bot.bridge.run_staff_action.assert_awaited_once_with(
            actor_uuid="u1", tool="kick", target="mits", reason="", duration=""
        )
        self.assertEqual(embed.title, "Action Completed")

    async def test_an_unheld_tool_is_refused_without_contacting_the_server(self):
        bot = self._bot(tools=[])

        embed = await bot.run_staff_action(1, "kick", target="mits", reason="", duration="")

        bot.bridge.run_staff_action.assert_not_awaited()
        self.assertEqual(embed.title, "Not Allowed")

    async def test_a_tool_with_no_remote_command_is_refused(self):
        bot = self._bot(tools=["inspect"])

        embed = await bot.run_staff_action(1, "inspect", target="", reason="", duration="")

        bot.bridge.run_staff_action.assert_not_awaited()
        self.assertEqual(embed.title, "Not Available")

    async def test_kick_requires_a_target(self):
        bot = self._bot(tools=["kick"])

        embed = await bot.run_staff_action(1, "kick", target="", reason="", duration="")

        bot.bridge.run_staff_action.assert_not_awaited()
        self.assertEqual(embed.title, "Missing Detail")

    async def test_tempban_requires_a_duration(self):
        bot = self._bot(tools=["tempban"])

        embed = await bot.run_staff_action(
            1, "tempban", target="mits", reason="", duration=""
        )

        bot.bridge.run_staff_action.assert_not_awaited()
        self.assertEqual(embed.title, "Missing Detail")

    async def test_broadcast_needs_a_reason_not_a_target(self):
        bot = self._bot(tools=["broadcast"])

        embed = await bot.run_staff_action(
            1, "broadcast", target="", reason="Event starting", duration=""
        )

        bot.bridge.run_staff_action.assert_awaited_once()
        self.assertEqual(embed.title, "Action Completed")

    async def test_update_needs_no_target_or_reason(self):
        bot = self._bot(tools=["update"])

        embed = await bot.run_staff_action(
            1, "update", target="", reason="", duration=""
        )

        bot.bridge.run_staff_action.assert_awaited_once()
        self.assertEqual(embed.title, "Action Completed")

    async def test_a_refusal_from_the_server_is_shown_as_such(self):
        bot = self._bot(tools=["kick"])
        bot.bridge.run_staff_action = AsyncMock(
            return_value=(False, "That player is not online.")
        )

        embed = await bot.run_staff_action(1, "kick", target="mits", reason="", duration="")

        self.assertEqual(embed.title, "Action Declined")
        self.assertIn("not online", embed.description)

    async def test_nothing_is_sent_while_the_server_is_offline(self):
        bot = self._bot(tools=["kick"], connected=False)

        embed = await bot.run_staff_action(1, "kick", target="mits", reason="", duration="")

        bot.bridge.run_staff_action.assert_not_awaited()
        self.assertEqual(embed.title, "Server Offline")


class MinecraftSetupDashboardOutcomeTests(unittest.IsolatedAsyncioTestCase):
    """Where the setup dashboard writes the outcome of one of its own buttons.

    Never onto the dashboard: it is a Components V2 message, and Discord refuses
    to put an embed on one. Doing so failed the interaction outright, and would
    have replaced the dashboard with a one-line confirmation had it worked.
    """

    def _bot(self):
        return SimpleNamespace(
            settings=MinecraftSettings(),
            bridge=SimpleNamespace(connected=True),
            is_administrator=lambda _member: True,
            update_settings=AsyncMock(),
            post_application_panel=AsyncMock(
                return_value=SimpleNamespace(
                    channel=SimpleNamespace(mention="#applications")
                )
            ),
        )

    def _interaction(self, bot):
        return SimpleNamespace(
            client=bot,
            guild=None,
            user=SimpleNamespace(id=123),
            response=SimpleNamespace(defer=AsyncMock(), send_message=AsyncMock()),
            edit_original_response=AsyncMock(),
            followup=SimpleNamespace(send=AsyncMock()),
        )

    async def test_saved_addresses_refresh_the_dashboard_and_confirm_beside_it(self):
        from minecraft_bot.setup import MinecraftAddressModal, MinecraftSetupView

        bot = self._bot()
        modal = MinecraftAddressModal(MinecraftSetupView(bot, 123, None))
        modal.java_address._value = "play.example.net:25565"
        modal.bedrock_address._value = "bedrock.example.net"
        modal.bedrock_port._value = "19132"
        interaction = self._interaction(bot)

        await modal.on_submit(interaction)

        bot.update_settings.assert_awaited_once()
        # A bare defer is a message update, so the edit lands on the dashboard.
        interaction.response.defer.assert_awaited_once_with()
        edit = interaction.edit_original_response.await_args.kwargs
        self.assertIsInstance(edit["view"], MinecraftSetupView)
        self.assertNotIn("embed", edit)
        confirmation = interaction.followup.send.await_args.kwargs
        self.assertEqual(confirmation["embed"].title, "Addresses Updated")
        self.assertTrue(confirmation["ephemeral"])

    async def test_posting_the_panel_answers_beside_the_dashboard(self):
        from minecraft_bot import setup as setup_module

        bot = self._bot()
        button = setup_module.MinecraftSetupAction(
            "post", "Post Application Panel", discord.ButtonStyle.success
        )
        button._view = setup_module.MinecraftSetupView(bot, 123, None)
        interaction = self._interaction(bot)

        with patch.object(setup_module, "configuration_findings", return_value=[]):
            await button.callback(interaction)

        bot.post_application_panel.assert_awaited_once()
        edit = interaction.edit_original_response.await_args.kwargs
        self.assertIsInstance(edit["view"], setup_module.MinecraftSetupView)
        self.assertNotIn("embed", edit)
        confirmation = interaction.followup.send.await_args.kwargs
        self.assertEqual(confirmation["embed"].title, "Application Panel Ready")
        self.assertTrue(confirmation["ephemeral"])

    async def test_a_panel_that_could_not_be_posted_leaves_the_dashboard_alone(self):
        from minecraft_bot import setup as setup_module

        bot = self._bot()
        bot.post_application_panel = AsyncMock(
            side_effect=RuntimeError("The application channel is not set.")
        )
        button = setup_module.MinecraftSetupAction(
            "post", "Post Application Panel", discord.ButtonStyle.success
        )
        button._view = setup_module.MinecraftSetupView(bot, 123, None)
        interaction = self._interaction(bot)

        with patch.object(setup_module, "configuration_findings", return_value=[]):
            await button.callback(interaction)

        interaction.edit_original_response.assert_not_awaited()
        self.assertEqual(
            interaction.followup.send.await_args.kwargs["embed"].title, "Panel Not Posted"
        )


class MinecraftAccessGuardTests(unittest.IsolatedAsyncioTestCase):
    def test_owner_is_only_the_guild_owner(self):
        bot = object.__new__(MinecraftAccessBot)
        bot.config = SimpleNamespace(guild_id=10)
        bot.get_guild = lambda _gid: SimpleNamespace(owner_id=1)
        owner = SimpleNamespace(id=1, roles=[SimpleNamespace(name="Member")])
        named = SimpleNamespace(id=2, roles=[SimpleNamespace(name="Owner")])

        self.assertTrue(bot.is_owner_member(owner))
        self.assertFalse(bot.is_owner_member(named))

    async def test_unlinked_discord_chat_is_not_relayed(self):
        bot = object.__new__(MinecraftAccessBot)
        bot.config = SimpleNamespace(guild_id=10)
        bot.settings = SimpleNamespace(chat_channel_id=20)
        bot.data = SimpleNamespace(list_accounts_for_user=AsyncMock(return_value=[]))
        bot.bridge = SimpleNamespace(
            send_discord_chat=AsyncMock(return_value=True),
            supports_chat_sync=True,
        )
        bot.chat_rate_limit = RateLimiter(0)
        bot.process_commands = AsyncMock()
        message = SimpleNamespace(
            guild=SimpleNamespace(id=10),
            channel=SimpleNamespace(id=20),
            author=SimpleNamespace(id=99, name="stranger", bot=False),
            webhook_id=None,
            content="hello",
            attachments=[],
        )

        await bot.on_message(message)

        bot.bridge.send_discord_chat.assert_not_awaited()
        bot.process_commands.assert_awaited_once_with(message)

    async def test_rejected_verification_does_not_spawn_work(self):
        bot = object.__new__(MinecraftAccessBot)
        bot.data = SimpleNamespace(
            record_verification=AsyncMock(side_effect=InvalidTransition("already linked"))
        )
        bot.spawn_background_task = Mock()

        with self.assertRaises(InvalidTransition):
            await bot.handle_bridge_verification(
                access_id=1,
                edition=Edition.JAVA,
                minecraft_uuid="123e4567-e89b-12d3-a456-426614174000",
                current_username="TestPlayer",
                xuid=None,
                event_idempotency_key="verify-1",
            )

        bot.spawn_background_task.assert_not_called()
