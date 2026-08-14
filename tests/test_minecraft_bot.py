import asyncio
import os
import unittest
from datetime import datetime, timezone
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock, patch

import discord

from minecraft_bot.bot import MinecraftAccessBot, RateLimiter
from minecraft_bot.config import MinecraftConfig
from minecraft_bot.models import ApplicationStatus, Edition, MinecraftApplication
from minecraft_bot.presentation import (
    BRAND_NAME,
    ERROR_COLOUR,
    ICON_ATTACHMENT_URI,
    ICON_PATH,
    FOOTER_ICON_URL,
    LOGO_ATTACHMENT_URI,
    FOOTER_PATH,
    LOGO_PATH,
    RULES_ATTACHMENT_URI,
    RULES_PATH,
    SUCCESS_COLOUR,
    THEME_COLOUR,
    VERIFY_ATTACHMENT_URI,
    VERIFY_PATH,
    application_embeds,
    application_dm_embed,
    application_log_embed,
    application_panel,
    application_panel_files,
    decision_log_embed,
    denial_embed,
    info_embed,
    review_embed,
    verification_embed,
    live_status_embed,
    minecraft_head_url,
    player_activity_embed,
    verification_log_embed,
)
from minecraft_bot.settings import MinecraftSettings
from minecraft_bot.setup import MinecraftSetupView
from minecraft_bot.ui import (
    AccountView,
    ApplyButton,
    CancelPendingConfirmationView,
    MinecraftControlView,
    MinecraftApplicationModal,
    LiveApplicationView,
    ReviewView,
    RulesButton,
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

    def test_rate_limiter_is_bounded(self):
        limiter = RateLimiter(10, max_entries=2)
        self.assertTrue(limiter.claim(1, now=0))
        self.assertFalse(limiter.claim(1, now=1))
        self.assertTrue(limiter.claim(2, now=1))
        self.assertTrue(limiter.claim(3, now=20))
        self.assertLessEqual(len(limiter._entries), 2)

    def test_staff_commands_have_moderator_default_permissions(self):
        group = self.bot._build_command_group()
        commands = {command.name: command for command in group.commands}

        for name in ("panel", "status", "lookup", "revoke", "unlink", "retry", "applications", "audit"):
            self.assertTrue(commands[name].default_permissions.manage_messages, name)
        self.assertNotIn("whois", commands)
        lookup_parameters = {parameter.name: parameter for parameter in commands["lookup"].parameters}
        self.assertEqual(set(lookup_parameters), {"user", "username"})
        self.assertFalse(lookup_parameters["user"].required)
        self.assertFalse(lookup_parameters["username"].required)
        self.assertTrue(commands["setup"].default_permissions.administrator)
        self.assertTrue(commands["log-channel"].default_permissions.administrator)
        self.assertTrue(commands["chat-channel"].default_permissions.administrator)
        self.assertIsNone(commands["cancel"].default_permissions)

    def test_application_and_review_components_are_persistent(self):
        panel = application_panel()
        review = ReviewView()
        modal = MinecraftApplicationModal()

        self.assertTrue(panel.is_persistent())
        self.assertTrue(review.is_persistent())
        self.assertEqual(
            {item.custom_id for item in review.children},
            {
                "minecraft:review:approve",
                "minecraft:review:deny",
            },
        )
        self.assertIsNone(modal.edition)
        compatibility_modal = MinecraftApplicationModal(require_edition=True)
        self.assertEqual(
            [option.value for option in compatibility_modal.edition.options],
            ["JAVA", "BEDROCK"],
        )
        live = LiveApplicationView()
        self.assertTrue(live.is_persistent())
        self.assertEqual(
            {item.custom_id for item in live.children},
            {"minecraft:live:help"},
        )
        self.assertEqual(
            {item.label for item in AccountView(123).children},
            {"Refresh", "Cancel Verification"},
        )
        panel_custom_ids = {
            component["custom_id"]
            for child in panel.to_components()
            for component in child.get("components", [])
            if "custom_id" in component
        }
        self.assertEqual(
            panel_custom_ids,
            {"minecraft:application:apply", "minecraft:application:rules"},
        )
        embeds = application_embeds()
        self.assertEqual(len(embeds), 2)
        self.assertEqual(embeds[0].title, "Welcome to Mysterious SMP X")
        self.assertEqual(embeds[1].title, "Apply to Mysterious SMP X")
        self.assertEqual(embeds[0].image.url, LOGO_ATTACHMENT_URI)
        self.assertEqual(embeds[0].footer.text, BRAND_NAME)
        self.assertIsNone(embeds[1].image.url)
        self.assertEqual(embeds[1].footer.icon_url, FOOTER_ICON_URL)
        files = application_panel_files()
        self.assertEqual([item.filename for item in files], ["mysterious_smp_x_logo.png"])
        for item in files:
            item.close()

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

    def test_minecraft_brand_assets_stay_lightweight(self):
        self.assertLess(LOGO_PATH.stat().st_size, 500_000)
        self.assertLess(ICON_PATH.stat().st_size, 100_000)
        self.assertLess(FOOTER_PATH.stat().st_size, 25_000)
        self.assertLess(RULES_PATH.stat().st_size, 1_000_000)
        self.assertLess(VERIFY_PATH.stat().st_size, 1_000_000)

    def test_verification_instructions_are_copyable_and_edition_specific(self):
        application = MinecraftApplication(
            id=42,
            guild_id="1",
            discord_user_id="123456789012345678",
            edition=Edition.BEDROCK,
            claimed_username="PlayerOne",
            normalized_username="playerone",
            answers={"why": "Build things", "about": "Helpful player"},
            status=ApplicationStatus.PENDING_VERIFICATION,
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
        self.assertNotIn("java.example", embed.description)
        self.assertIn("/minecraft cancel", embed.description)
        self.assertNotIn("#42", embed.description)
        self.assertEqual(embed.image.url, VERIFY_ATTACHMENT_URI)

        live_embed = live_status_embed(application, settings)
        self.assertEqual(live_embed.image.url, VERIFY_ATTACHMENT_URI)

    def test_applicant_decision_embed_hides_internal_application_id(self):
        application = MinecraftApplication(
            id=42,
            guild_id="1",
            discord_user_id="123456789012345678",
            edition=Edition.JAVA,
            claimed_username="PlayerOne",
            normalized_username="playerone",
            answers={"why": "Build things", "about": "Helpful player"},
            status=ApplicationStatus.DENIED,
            verification_expires_at=2_000_000_000,
            applicant_reason="The application needs more detail.",
            created_at=1_999_999_400,
            updated_at=1_999_999_400,
        )

        embed = denial_embed(application)

        self.assertNotIn("#42", embed.description)

    def test_verified_dm_is_the_concise_submission_confirmation(self):
        application = MinecraftApplication(
            id=42,
            guild_id="1",
            discord_user_id="123456789012345678",
            edition=Edition.JAVA,
            claimed_username="PlayerOne",
            normalized_username="playerone",
            answers={"why": "Build things", "about": "Helpful player"},
            status=ApplicationStatus.PENDING_REVIEW,
            verification_expires_at=2_000_000_000,
            verified_username="PlayerOne",
            created_at=1_999_999_400,
            updated_at=1_999_999_400,
        )

        embed = application_dm_embed(application, SimpleNamespace(), "verification")

        self.assertEqual(embed.title, "Account Verified — Application Sent")
        self.assertIn("account was verified and your application has been sent", embed.description)
        self.assertNotIn("another DM", embed.description)
        self.assertEqual(embed.colour.value, THEME_COLOUR.value)
        self.assertEqual(embed.thumbnail.url, ICON_ATTACHMENT_URI)

    def test_review_embed_uses_minecraft_skin_head_and_claimed_identity(self):
        application = MinecraftApplication(
            id=7,
            guild_id="1",
            discord_user_id="123456789012345678",
            edition=Edition.JAVA,
            claimed_username="ClaimedName",
            normalized_username="claimedname",
            answers={"why": "I enjoy SMPs", "about": "I like building"},
            status=ApplicationStatus.PENDING_REVIEW,
            verification_expires_at=2_000_000_000,
            created_at=int(datetime(2026, 1, 1, tzinfo=timezone.utc).timestamp()),
            updated_at=1_999_999_400,
            verified_username="VerifiedName",
            minecraft_uuid="00000000-0000-0000-0000-000000000000",
        )

        user = SimpleNamespace(
            name="Applicant",
            display_avatar=SimpleNamespace(url="https://cdn.discordapp.com/avatar.png"),
        )
        embed = review_embed(application, user=user)

        self.assertEqual(
            embed.thumbnail.url,
            "https://mc-heads.net/head/00000000-0000-0000-0000-000000000000/128.png",
        )
        self.assertEqual(embed.footer.icon_url, FOOTER_ICON_URL)
        self.assertTrue(any(field.name == "Claimed Username" for field in embed.fields))

    def test_minecraft_skin_head_requires_a_verified_uuid(self):
        application = MinecraftApplication(
            id=8,
            guild_id="1",
            discord_user_id="123456789012345678",
            edition=Edition.BEDROCK,
            claimed_username="Bedrock Player",
            normalized_username="bedrock player",
            answers={"why": "Play", "about": "Build"},
            status=ApplicationStatus.PENDING_VERIFICATION,
            verification_expires_at=2_000_000_000,
            created_at=1_999_999_400,
            updated_at=1_999_999_400,
        )

        self.assertIsNone(minecraft_head_url(application))
        self.assertIsNone(review_embed(application).thumbnail.url)

    def test_every_minecraft_player_log_has_a_skin_thumbnail(self):
        verified = MinecraftApplication(
            id=9,
            guild_id="1",
            discord_user_id="123456789012345678",
            edition=Edition.JAVA,
            claimed_username="ClaimedName",
            normalized_username="claimedname",
            answers={"why": "Play", "about": "Build"},
            status=ApplicationStatus.PENDING_REVIEW,
            verification_expires_at=2_000_000_000,
            created_at=1_999_999_400,
            updated_at=1_999_999_400,
            verified_username="VerifiedName",
            minecraft_uuid="12345678-1234-1234-1234-123456789abc",
        )
        expected = "https://mc-heads.net/head/12345678-1234-1234-1234-123456789abc/128.png"

        self.assertEqual(application_log_embed(verified).thumbnail.url, expected)
        self.assertEqual(verification_log_embed(verified).thumbnail.url, expected)
        self.assertEqual(decision_log_embed(verified).thumbnail.url, expected)
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
        java_application = MinecraftApplication(
            id=10,
            guild_id="1",
            discord_user_id="123456789012345678",
            edition=Edition.JAVA,
            claimed_username="JavaPlayer",
            normalized_username="javaplayer",
            answers={"why": "Play", "about": "Build"},
            status=ApplicationStatus.PENDING_VERIFICATION,
            verification_expires_at=2_000_000_000,
            created_at=1_999_999_400,
            updated_at=1_999_999_400,
        )
        bedrock_application = MinecraftApplication(
            id=11,
            guild_id="1",
            discord_user_id="123456789012345679",
            edition=Edition.BEDROCK,
            claimed_username="Bedrock Player",
            normalized_username="bedrock player",
            answers={"why": "Play", "about": "Build"},
            status=ApplicationStatus.PENDING_VERIFICATION,
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


class MinecraftApplyFlowTests(unittest.IsolatedAsyncioTestCase):
    async def test_discord_chat_channel_relays_plain_messages_and_attachment_link(self):
        bot = object.__new__(MinecraftAccessBot)
        bot.config = SimpleNamespace(guild_id=10)
        bot.settings = SimpleNamespace(chat_channel_id=20)
        bot.data = SimpleNamespace(list_accounts_for_user=AsyncMock(return_value=[{
            "minecraft_uuid": "123e4567-e89b-12d3-a456-426614174000",
            "current_username": "TestPlayer",
        }]))
        bot.bridge = SimpleNamespace(send_discord_chat=AsyncMock(return_value=True))
        bot.process_commands = AsyncMock()
        message = SimpleNamespace(
            guild=SimpleNamespace(id=10),
            channel=SimpleNamespace(id=20),
            author=SimpleNamespace(id=99, name="hellomits", bot=False),
            webhook_id=None,
            content="hello from Discord",
            attachments=[SimpleNamespace(filename="image.png")],
            jump_url="https://discord.com/channels/10/20/30",
        )

        await bot.on_message(message)

        bot.bridge.send_discord_chat.assert_awaited_once_with(
            discord_user_id=99,
            discord_username="hellomits",
            minecraft_uuid="123e4567-e89b-12d3-a456-426614174000",
            minecraft_username="TestPlayer",
            message="hello from Discord",
            attachment_url=message.jump_url,
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
        application = SimpleNamespace(id=42)
        original_message = SimpleNamespace(id=9001)
        bot = SimpleNamespace(
            data=SimpleNamespace(create_application=AsyncMock(return_value=application)),
            settings=SimpleNamespace(),
            remember_application_message=Mock(),
            finish_application_submission=Mock(return_value=asyncio.sleep(0)),
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
        modal.why._value = "I enjoy collaborative survival servers."
        modal.about._value = "I build farms and help other players."

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

    async def test_rules_button_attaches_the_rules_image(self):
        response = SimpleNamespace(send_message=AsyncMock())
        bot = SimpleNamespace(
            config=SimpleNamespace(guild_id=10),
            settings=SimpleNamespace(application_channel_id=20),
            data=SimpleNamespace(get_config=AsyncMock(return_value="30")),
        )
        interaction = SimpleNamespace(
            client=bot,
            guild_id=10,
            channel_id=20,
            message=SimpleNamespace(id=30),
            response=response,
        )

        await RulesButton().callback(interaction)

        kwargs = response.send_message.await_args.kwargs
        self.assertEqual(kwargs["embed"].image.url, RULES_ATTACHMENT_URI)
        self.assertEqual(kwargs["file"].filename, "mysterious_smp_x_rules.png")
        kwargs["file"].close()

    async def test_submission_does_not_dm_a_pending_verification_card(self):
        bot = object.__new__(MinecraftAccessBot)
        bot.log_application_submission = AsyncMock()
        bot.update_live_card = AsyncMock()
        bot.bridge = SimpleNamespace(connected=False)
        application = SimpleNamespace(id=1, status=ApplicationStatus.PENDING_VERIFICATION)

        await bot.finish_application_submission(application)

        bot.log_application_submission.assert_awaited_once_with(application)
        bot.update_live_card.assert_not_awaited()

    async def test_verified_application_creates_one_concise_tracked_dm(self):
        application = SimpleNamespace(
            id=1,
            discord_user_id="99",
            edition=Edition.JAVA,
            verified_username="PlayerOne",
            status=ApplicationStatus.PENDING_REVIEW,
            status_channel_id=None,
            status_message_id=None,
            decision_channel_id=None,
            decision_message_id=None,
        )
        message = SimpleNamespace(channel=SimpleNamespace(id=50), id=60)
        user = SimpleNamespace(send=AsyncMock(return_value=message))
        bot = object.__new__(MinecraftAccessBot)
        bot.settings = SimpleNamespace()
        bot.get_user = lambda _user_id: user
        bot.fetch_user = AsyncMock()
        bot.data = SimpleNamespace(
            get_application=AsyncMock(return_value=application),
            set_status_message=AsyncMock(),
            set_decision_message=AsyncMock(),
            enqueue_delivery=AsyncMock(),
        )

        delivered = await bot.update_live_card(application)

        self.assertTrue(delivered)
        user.send.assert_awaited_once()
        kwargs = user.send.await_args.kwargs
        self.assertEqual(kwargs["embed"].title, "Account Verified — Application Sent")
        self.assertEqual(kwargs["embed"].colour.value, THEME_COLOUR.value)
        self.assertEqual(kwargs["embed"].thumbnail.url, ICON_ATTACHMENT_URI)
        self.assertEqual(kwargs["file"].filename, "mysterious_smp_x_icon.png")
        self.assertNotIn("view", kwargs)
        bot.data.set_status_message.assert_awaited_once_with(1, 50, 60)
        bot.data.set_decision_message.assert_not_awaited()
        bot.data.enqueue_delivery.assert_not_awaited()

    async def test_finished_application_sends_missing_confirmation_and_denial_dms(self):
        application = SimpleNamespace(
            id=1,
            discord_user_id="99",
            edition=Edition.JAVA,
            verified_username="PlayerOne",
            status=ApplicationStatus.DENIED,
            status_channel_id=None,
            status_message_id=None,
            decision_channel_id=None,
            decision_message_id=None,
            applicant_reason="Not this time",
        )
        confirmation = SimpleNamespace(channel=SimpleNamespace(id=50), id=60)
        decision = SimpleNamespace(channel=SimpleNamespace(id=50), id=61)
        user = SimpleNamespace(send=AsyncMock(side_effect=[confirmation, decision]))
        bot = object.__new__(MinecraftAccessBot)
        bot.settings = SimpleNamespace()
        bot.get_user = Mock(return_value=user)
        bot.fetch_user = AsyncMock()
        bot.data = SimpleNamespace(
            get_application=AsyncMock(return_value=application),
            enqueue_delivery=AsyncMock(),
            set_status_message=AsyncMock(),
            set_decision_message=AsyncMock(),
        )

        delivered = await bot.update_live_card(application)

        self.assertTrue(delivered)
        self.assertEqual(bot.get_user.call_count, 2)
        bot.fetch_user.assert_not_awaited()
        self.assertEqual(user.send.await_count, 2)
        confirmation_kwargs = user.send.await_args_list[0].kwargs
        decision_kwargs = user.send.await_args_list[1].kwargs
        self.assertEqual(confirmation_kwargs["embed"].title, "Account Verified — Application Sent")
        self.assertEqual(decision_kwargs["embed"].title, "Minecraft Application Denied")
        self.assertEqual(decision_kwargs["embed"].colour.value, ERROR_COLOUR.value)
        for kwargs in (confirmation_kwargs, decision_kwargs):
            self.assertEqual(kwargs["embed"].thumbnail.url, ICON_ATTACHMENT_URI)
            self.assertEqual(kwargs["file"].filename, "mysterious_smp_x_icon.png")
        bot.data.set_status_message.assert_awaited_once_with(1, 50, 60)
        bot.data.set_decision_message.assert_awaited_once_with(1, 50, 61)
        bot.data.enqueue_delivery.assert_not_awaited()

    async def test_approved_application_sends_a_separate_green_decision_dm(self):
        application = SimpleNamespace(
            id=1,
            discord_user_id="99",
            edition=Edition.JAVA,
            verified_username="PlayerOne",
            status=ApplicationStatus.APPROVED,
            status_channel_id="50",
            status_message_id="60",
            decision_channel_id=None,
            decision_message_id=None,
        )
        decision = SimpleNamespace(channel=SimpleNamespace(id=50), id=61)
        user = SimpleNamespace(send=AsyncMock(return_value=decision))
        bot = object.__new__(MinecraftAccessBot)
        bot.settings = SimpleNamespace(
            java_address="play.example.test",
            bedrock_address="bedrock.example.test",
            bedrock_port=19132,
        )
        bot.get_user = Mock(return_value=user)
        bot.fetch_user = AsyncMock()
        bot.data = SimpleNamespace(
            get_application=AsyncMock(return_value=application),
            enqueue_delivery=AsyncMock(),
            set_status_message=AsyncMock(),
            set_decision_message=AsyncMock(),
        )

        delivered = await bot.update_live_card(application)

        self.assertTrue(delivered)
        user.send.assert_awaited_once()
        kwargs = user.send.await_args.kwargs
        self.assertEqual(kwargs["embed"].title, "Minecraft Application Approved")
        self.assertEqual(kwargs["embed"].colour.value, SUCCESS_COLOUR.value)
        self.assertEqual(kwargs["embed"].thumbnail.url, ICON_ATTACHMENT_URI)
        self.assertEqual(kwargs["file"].filename, "mysterious_smp_x_icon.png")
        bot.data.set_status_message.assert_not_awaited()
        bot.data.set_decision_message.assert_awaited_once_with(1, 50, 61)

    async def test_existing_verification_dm_is_not_edited_or_duplicated(self):
        application = SimpleNamespace(
            id=1,
            discord_user_id="99",
            edition=Edition.JAVA,
            verified_username="PlayerOne",
            status=ApplicationStatus.PENDING_REVIEW,
            status_channel_id="50",
            status_message_id="60",
            decision_channel_id=None,
            decision_message_id=None,
        )
        bot = object.__new__(MinecraftAccessBot)
        bot.get_user = Mock()
        bot.data = SimpleNamespace(
            get_application=AsyncMock(return_value=application),
            enqueue_delivery=AsyncMock(),
        )

        delivered = await bot.update_live_card(application)

        self.assertTrue(delivered)
        bot.get_user.assert_not_called()
        bot.data.enqueue_delivery.assert_not_awaited()

    async def test_ephemeral_card_update_does_not_suppress_verification_dm(self):
        application = SimpleNamespace(
            id=1,
            discord_user_id="99",
            edition=Edition.JAVA,
            verified_username="PlayerOne",
            status=ApplicationStatus.PENDING_REVIEW,
            status_channel_id=None,
            status_message_id=None,
            decision_channel_id=None,
            decision_message_id=None,
        )
        ephemeral = SimpleNamespace(edit=AsyncMock())
        sent_message = SimpleNamespace(channel=SimpleNamespace(id=50), id=60)
        user = SimpleNamespace(send=AsyncMock(return_value=sent_message))
        bot = object.__new__(MinecraftAccessBot)
        bot.settings = SimpleNamespace()
        bot._application_messages = {1: (ephemeral, float("inf"))}
        bot.get_user = Mock(return_value=user)
        bot.fetch_user = AsyncMock()
        bot.data = SimpleNamespace(
            get_application=AsyncMock(return_value=application),
            enqueue_delivery=AsyncMock(),
            set_status_message=AsyncMock(),
            set_decision_message=AsyncMock(),
        )

        delivered = await bot.update_live_card(application)

        self.assertTrue(delivered)
        ephemeral.edit.assert_awaited_once()
        self.assertEqual(ephemeral.edit.await_args.kwargs["attachments"], [])
        user.send.assert_awaited_once()
        bot.data.set_status_message.assert_awaited_once_with(1, 50, 60)

    async def test_concurrent_verified_delivery_sends_only_one_dm(self):
        stored = SimpleNamespace(
            id=1,
            discord_user_id="99",
            edition=Edition.JAVA,
            verified_username="PlayerOne",
            status=ApplicationStatus.PENDING_REVIEW,
            status_channel_id=None,
            status_message_id=None,
            decision_channel_id=None,
            decision_message_id=None,
        )
        sent_message = SimpleNamespace(channel=SimpleNamespace(id=50), id=60)
        user = SimpleNamespace(send=AsyncMock(return_value=sent_message))

        async def remember_message(_application_id, channel_id, message_id):
            stored.status_channel_id = str(channel_id)
            stored.status_message_id = str(message_id)

        bot = object.__new__(MinecraftAccessBot)
        bot.settings = SimpleNamespace()
        bot._live_card_lock = asyncio.Lock()
        bot.get_user = lambda _user_id: user
        bot.fetch_user = AsyncMock()
        bot.data = SimpleNamespace(
            get_application=AsyncMock(side_effect=lambda _application_id: stored),
            set_status_message=AsyncMock(side_effect=remember_message),
            set_decision_message=AsyncMock(),
            enqueue_delivery=AsyncMock(),
        )

        results = await asyncio.gather(
            bot.update_live_card(stored),
            bot.update_live_card(stored),
        )

        self.assertEqual(results, [True, True])
        user.send.assert_awaited_once()
        bot.data.set_status_message.assert_awaited_once_with(1, 50, 60)

    async def test_application_panel_has_no_public_banner_message(self):
        panel = SimpleNamespace(id=101)
        channel = SimpleNamespace(send=AsyncMock(return_value=panel))
        bot = object.__new__(MinecraftAccessBot)
        bot.settings = SimpleNamespace(application_channel_id=20)
        bot._configured_channel = AsyncMock(return_value=channel)
        bot.data = SimpleNamespace(
            get_config=AsyncMock(return_value=None),
            set_configs=AsyncMock(),
        )

        result = await bot.post_application_panel()

        self.assertIs(result, panel)
        channel.send.assert_awaited_once()
        call = channel.send.await_args
        self.assertNotIn("file", call.kwargs)
        self.assertEqual(len(call.kwargs["embeds"]), 2)
        bot.data.set_configs.assert_awaited_once_with(
            {
                "application_banner_message_id": "",
                "application_panel_message_id": "101",
            }
        )
        for file in call.kwargs["files"]:
            file.close()

    async def test_apply_reveals_cancel_only_for_pending_verification(self):
        application = MinecraftApplication(
            id=42,
            guild_id="10",
            discord_user_id="99",
            edition=Edition.JAVA,
            claimed_username="PlayerOne",
            normalized_username="playerone",
            answers={"why": "Build things", "about": "Helpful player"},
            status=ApplicationStatus.PENDING_VERIFICATION,
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
                get_active_application_for_user=AsyncMock(return_value=application),
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

        await ApplyButton().callback(interaction)

        response.send_modal.assert_not_awaited()
        response.send_message.assert_awaited_once()
        kwargs = response.send_message.await_args.kwargs
        self.assertTrue(kwargs["ephemeral"])
        self.assertIsInstance(kwargs["view"], CancelPendingConfirmationView)
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
                get_active_application_for_user=AsyncMock(return_value=None),
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

        await ApplyButton().callback(interaction)

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
        await asyncio.gather(*bot._background_tasks)

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
        )

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

    def test_thumbnail_is_the_attached_x_mark(self):
        from minecraft_bot.presentation import MARK_ATTACHMENT_URI, MARK_PATH

        embed = self.leaderboard.build_embed(self.snapshot, scope="individual", board="wealth")

        self.assertEqual(embed.thumbnail.url, MARK_ATTACHMENT_URI)
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
        self.assertNotIn("blocks_mined", tuple(self.leaderboard.boards_for("clan")))
        self.assertIn("blocks_mined", tuple(self.leaderboard.boards_for("individual")))

    def test_emoji_names_are_discord_safe(self):
        self.assertEqual(self.leaderboard._emoji_name("Not.A-Name!"), "mgx_head_NotAName")
        self.assertTrue(self.leaderboard._emoji_name("").endswith("player"))
        self.assertLessEqual(len(self.leaderboard._emoji_name("x" * 60)), 32)

    def test_only_five_rows_are_shown(self):
        embed = self.leaderboard.build_embed(self.snapshot, scope="individual", board="wealth")

        self.assertEqual(len(embed.description.splitlines()), 5)
        self.assertNotIn("zed", embed.description)

    def test_podium_is_bold_and_the_rest_are_not(self):
        embed = self.leaderboard.build_embed(self.snapshot, scope="individual", board="wealth")
        lines = embed.description.splitlines()

        for line in lines[:3]:
            self.assertIn("**", line)
        for line in lines[3:]:
            self.assertNotIn("**", line)

    def test_linked_discord_account_is_mentioned_beside_the_username(self):
        embed = self.leaderboard.build_embed(
            self.snapshot,
            scope="individual",
            board="wealth",
            linked={"u1": "12345"},
        )
        first = embed.description.splitlines()[0]

        self.assertIn("mits", first)
        self.assertIn("<@12345>", first)

    def test_unlinked_players_show_no_mention(self):
        embed = self.leaderboard.build_embed(
            self.snapshot,
            scope="individual",
            board="wealth",
            linked={"u1": "12345"},
        )
        second = embed.description.splitlines()[1]

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
