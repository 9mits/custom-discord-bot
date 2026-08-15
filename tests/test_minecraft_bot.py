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
    ApplicationQuestionsModal,
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
            "kick", "mute", "ban", "tempban", "unban", "heal", "broadcast",
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
        # A freshly created application is awaiting verification, which is what
        # decides whether the card carries the verify image.
        application = SimpleNamespace(id=42, status=ApplicationStatus.PENDING_VERIFICATION)
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
        create_kwargs = bot.data.create_application.await_args.kwargs
        # Stage one carries no written answers; the form follows verification.
        self.assertNotIn("answers", create_kwargs)

    async def test_questions_modal_submits_answers_and_finishes_the_application(self):
        application = SimpleNamespace(id=42, status=ApplicationStatus.PENDING_REVIEW)
        bot = SimpleNamespace(
            data=SimpleNamespace(submit_answers=AsyncMock(return_value=application)),
            settings=SimpleNamespace(),
            finish_answers_submission=Mock(return_value=asyncio.sleep(0)),
        )

        def close_background_work(work, *, name):
            work.close()

        bot.spawn_background_task = Mock(side_effect=close_background_work)
        interaction = SimpleNamespace(
            client=bot,
            user=SimpleNamespace(id=99),
            response=SimpleNamespace(defer=AsyncMock()),
            edit_original_response=AsyncMock(),
        )
        modal = ApplicationQuestionsModal(42)
        modal.why._value = "I enjoy collaborative survival servers."
        modal.about._value = "I build farms and help other players."

        with patch("minecraft_bot.ui.live_status_embed", return_value=info_embed("Application Sent", "> Done.")):
            await modal.on_submit(interaction)

        submit_kwargs = bot.data.submit_answers.await_args
        self.assertEqual(submit_kwargs.args, (42, 99))
        self.assertEqual(submit_kwargs.kwargs["why"], "I enjoy collaborative survival servers.")
        kwargs = interaction.edit_original_response.await_args.kwargs
        self.assertEqual(kwargs["embed"].title, "Application Sent")
        bot.spawn_background_task.assert_called_once()

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
            rank_known=False,
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

    def test_clan_podium_rows_carry_their_icon(self):
        from minecraft_bot.leaderboard import clan_key

        snapshot = {
            "clan": {
                "wealth": [
                    {"clan": "Wolves", "value": 9, "display": "9"},
                    {"clan": "Ravens", "value": 8, "display": "8"},
                    {"clan": "Foxes", "value": 7, "display": "7"},
                    {"clan": "Bears", "value": 6, "display": "6"},
                ]
            }
        }
        icons = {
            clan_key("Wolves"): "<:w:1>",
            clan_key("Ravens"): "<:r:2>",
            clan_key("Foxes"): "<:f:3>",
            clan_key("Bears"): "<:b:4>",
        }

        described = self.leaderboard.build_embed(
            snapshot, scope="clan", board="wealth", heads=icons
        ).description

        self.assertTrue(described.startswith("<:w:1> **#1 · Wolves**"))
        self.assertIn("<:r:2>", described)
        self.assertIn("<:f:3>", described)
        # Fourth place is off the podium, so it stays plain like the players do.
        self.assertNotIn("<:b:4>", described)

    def test_clan_thumbnail_is_the_leading_clans_icon(self):
        from minecraft_bot.leaderboard import CLAN_DEFAULT_ICON_URL

        with_icon = self.leaderboard.build_embed(
            {"clan": {"wealth": [{"clan": "Wolves", "icon": "https://example.com/w.png"}]}},
            scope="clan",
            board="wealth",
        )
        without = self.leaderboard.build_embed(
            {"clan": {"wealth": [{"clan": "Ravens"}]}}, scope="clan", board="wealth"
        )

        self.assertEqual(with_icon.thumbnail.url, "https://example.com/w.png")
        self.assertEqual(without.thumbnail.url, CLAN_DEFAULT_ICON_URL)

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

    def test_row_reads_clan_then_discord_then_minecraft(self):
        embed = self.leaderboard.build_embed(
            self.snapshot,
            scope="individual",
            board="wealth",
            linked={"u1": "12345"},
        )
        first = embed.description.splitlines()[0]

        self.assertLess(first.index("[LUCKY]"), first.index("<@12345>"))
        self.assertLess(first.index("<@12345>"), first.index("mits"))

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

    async def test_changing_a_clan_icon_remints_it(self):
        # A clan keeps its key when it swaps picture, so without comparing the
        # stored source the board would show the old image indefinitely.
        from minecraft_bot.leaderboard import clan_key

        store = self._store(
            {
                clan_key("Wolves"): {
                    "emoji_id": 77,
                    "markdown": "<:w:77>",
                    "source": "https://example.com/old.png",
                    "last_podium": "2026-08-14T00:00:00+00:00",
                }
            }
        )
        existing = SimpleNamespace(id=77, name="mgx_head_Wolves", delete=AsyncMock())
        guild = SimpleNamespace(
            id=1,
            emoji_limit=250,
            emojis=[],
            fetch_emojis=AsyncMock(return_value=[existing]),
            get_emoji=lambda _id: None,
        )
        snapshot = {
            "individual": {},
            "clan": {
                "wealth": [
                    {"clan": "Wolves", "icon": "https://example.com/new.png", "value": 9}
                ]
            },
        }

        await store.sync(guild, snapshot)

        store._create.assert_awaited_once()
        self.assertEqual(store._create.await_args.args[-1], "https://example.com/new.png")
        existing.delete.assert_awaited_once()

    async def test_an_unchanged_clan_icon_is_left_alone(self):
        from minecraft_bot.leaderboard import clan_key

        store = self._store(
            {
                clan_key("Wolves"): {
                    "emoji_id": 77,
                    "markdown": "<:w:77>",
                    "source": "https://example.com/wolf.png",
                    "last_podium": "2026-08-14T00:00:00+00:00",
                }
            }
        )
        existing = SimpleNamespace(id=77, name="mgx_head_Wolves", delete=AsyncMock())
        guild = SimpleNamespace(
            id=1,
            emoji_limit=250,
            emojis=[],
            fetch_emojis=AsyncMock(return_value=[existing]),
            get_emoji=lambda _id: None,
        )
        snapshot = {
            "individual": {},
            "clan": {
                "wealth": [
                    {"clan": "Wolves", "icon": "https://example.com/wolf.png", "value": 9}
                ]
            },
        }

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

        for uuid in ("rich", "killer", "walker"):
            with self.subTest(uuid=uuid):
                self.assertIn(uuid, podium)

    def test_clan_podiums_mint_their_icons(self):
        from minecraft_bot.leaderboard import CLAN_DEFAULT_ICON_URL, clan_key

        snapshot = {
            "individual": {},
            "clan": {
                "wealth": [
                    {"clan": "Wolves", "icon": "https://example.com/wolf.png", "value": 9},
                    {"clan": "Ravens", "value": 4},
                ]
            },
        }

        subjects = self._store()._podium_subjects(snapshot)

        self.assertEqual(
            subjects[clan_key("Wolves")], ("Wolves", "https://example.com/wolf.png")
        )
        # A clan that never set one still gets an emoji, so the podium stays even.
        self.assertEqual(subjects[clan_key("Ravens")], ("Ravens", CLAN_DEFAULT_ICON_URL))

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
        emitted = [item.custom_id for item in LeaderboardView().children]

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
            SimpleNamespace(status=ApplicationStatus.PENDING_VERIFICATION)
        )

        self.assertEqual([f.filename for f in files], [VERIFY_FILENAME])

    def test_later_stages_carry_no_attachment(self):
        from minecraft_bot.presentation import application_card_files

        for status in (
            ApplicationStatus.PENDING_REVIEW,
            ApplicationStatus.APPROVED,
            ApplicationStatus.DENIED,
        ):
            with self.subTest(status=status):
                self.assertEqual(application_card_files(SimpleNamespace(status=status)), [])


class MinecraftApplicationPanelTests(unittest.TestCase):
    def test_welcome_embed_credits_the_partnership_in_bold(self):
        from minecraft_bot.presentation import application_embeds

        description = application_embeds()[0].description

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
            application_embeds,
        )
        from minecraft_bot import information

        welcome = application_embeds()[0].description
        panel = information.overview_embed(0).description

        for paragraph in SERVER_TAGLINE_PARAGRAPHS:
            with self.subTest(paragraph=paragraph[:32]):
                self.assertIn(paragraph, welcome)
                self.assertIn(paragraph, panel)

    def test_welcome_showcases_what_the_server_offers(self):
        # Someone reading this is deciding whether the server suits them, which
        # they cannot tell from atmosphere alone.
        from minecraft_bot.presentation import SERVER_FEATURES, application_embeds

        described = dict(SERVER_FEATURES)
        shown = {
            field.name: field
            for field in application_embeds()[0].fields
            if field.name in described
        }

        for feature in (
            "Clans",
            "Levels",
            "Homes and travel",
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
        from minecraft_bot.presentation import application_embeds

        welcome = application_embeds()[0]
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

    def test_griefing_is_the_first_rule(self):
        from minecraft_bot.presentation import rules_embed

        self.assertEqual(rules_embed().fields[0].name, "1. Do not grief")

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
            ("2. Theft has limits", ("**Fair** —", "**Griefing** —")),
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

    def test_rules_close_the_arguments_people_make(self):
        # Each of these answers a defence a player would otherwise offer, and
        # each was missing while the rules lived in one description.
        described = self._rules_text()

        for clause in (
            "looks abandoned",           # "it was abandoned"
            "not an invitation",         # "the chest was unlocked"
            "being offline is not consent",
            "regardless of who started it",
            "the death you avoided",     # combat logging
            "judged by staff",           # "it was proportional"
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

    def test_overview_uses_the_logo_as_the_image_not_a_thumbnail(self):
        from minecraft_bot.presentation import LOGO_ATTACHMENT_URI

        embed = self.information.overview_embed(0)

        self.assertEqual(embed.image.url, LOGO_ATTACHMENT_URI)
        self.assertIsNone(embed.thumbnail.url)

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

    def test_boosting_page_states_the_stacked_totals(self):
        description = self.embed_text(self.information.boosting_embed())

        self.assertIn("+10% damage", description)
        self.assertIn("+25% damage", description)
        self.assertIn("6 extra hearts", description)

    def test_buttons_route_back_to_their_page(self):
        pattern = self.information.InformationButton.__discord_ui_compiled_template__
        emitted = [item.custom_id for item in self.information.InformationView().children]

        self.assertEqual(len(emitted), len(self.information.PAGES))
        for custom_id in emitted:
            match = pattern.fullmatch(custom_id)
            self.assertIsNotNone(match, f"{custom_id} would not route back")
            self.assertIn(match["page"], self.information.PAGES)

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

    def test_pages_warn_that_newer_clients_are_refused(self):
        # Clients above the server version bypass GrimAC, so they are blocked at
        # the door; players have to be told before they hit the kick.
        builders = (
            ("overview", self.information.overview_embed),
            ("versions", self.information.PAGES["versions"][1]),
        )
        for page, builder in builders:
            with self.subTest(page=page):
                described = self.embed_text(builder(0))

                self.assertIn(self.information.JAVA_SUPPORTED_RANGE, described)
                self.assertRegex(described, r"turned away|refused|blocked")

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
        from minecraft_bot.presentation import application_embeds

        self.assertNotIn("presents", self.embed_text(self.information.overview_embed(0)))
        self.assertNotIn("presents", application_embeds()[0].description)
        self.assertIn("in partnership with", application_embeds()[0].description)


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

    def test_a_member_may_only_leave(self):
        caps = self._caps("member-uuid")

        self.assertEqual([a for a, _ in caps.available_clan_actions()], ["leave"])
        self.assertFalse(caps.may("disband"))
        self.assertFalse(caps.may("invite"))

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
