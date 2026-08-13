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
    ICON_PATH,
    FOOTER_ICON_URL,
    LOGO_ATTACHMENT_URI,
    FOOTER_PATH,
    LOGO_PATH,
    RULES_ATTACHMENT_URI,
    RULES_PATH,
    THEME_COLOUR,
    VERIFY_ATTACHMENT_URI,
    VERIFY_PATH,
    application_embeds,
    application_panel,
    denial_embed,
    info_embed,
    review_embed,
    verification_embed,
    verified_embed,
    live_status_embed,
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

    def test_verified_dm_is_the_single_concise_submission_confirmation(self):
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

        embed = verified_embed(application)

        self.assertEqual(embed.title, "Account Verified — Application Sent")
        self.assertIn("account was verified and your application has been sent", embed.description)
        self.assertNotIn("another DM", embed.description)

    def test_review_embed_uses_applicant_avatar_and_claimed_identity(self):
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

        self.assertEqual(embed.thumbnail.url, "https://cdn.discordapp.com/avatar.png")
        self.assertEqual(embed.footer.icon_url, FOOTER_ICON_URL)
        self.assertTrue(any(field.name == "Claimed Username" for field in embed.fields))

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
        )
        message = SimpleNamespace(channel=SimpleNamespace(id=50), id=60)
        user = SimpleNamespace(send=AsyncMock(return_value=message))
        bot = object.__new__(MinecraftAccessBot)
        bot.get_user = lambda _user_id: user
        bot.fetch_user = AsyncMock()
        bot.data = SimpleNamespace(
            get_application=AsyncMock(return_value=application),
            set_status_message=AsyncMock(),
            enqueue_delivery=AsyncMock(),
        )

        delivered = await bot.update_live_card(application)

        self.assertTrue(delivered)
        user.send.assert_awaited_once()
        self.assertEqual(user.send.await_args.kwargs["embed"].title, "Account Verified — Application Sent")
        self.assertNotIn("view", user.send.await_args.kwargs)
        bot.data.set_status_message.assert_awaited_once_with(1, 50, 60)
        bot.data.enqueue_delivery.assert_not_awaited()

    async def test_finished_application_uses_a_fallback_dm_when_the_ephemeral_card_expired(self):
        application = SimpleNamespace(
            id=1,
            discord_user_id="99",
            status=ApplicationStatus.DENIED,
            status_channel_id=None,
            status_message_id=None,
            applicant_reason="Not this time",
        )
        sent_message = SimpleNamespace(channel=SimpleNamespace(id=50), id=60)
        user = SimpleNamespace(send=AsyncMock(return_value=sent_message))
        bot = object.__new__(MinecraftAccessBot)
        bot.get_user = Mock(return_value=user)
        bot.fetch_user = AsyncMock()
        bot.data = SimpleNamespace(
            get_application=AsyncMock(return_value=application),
            enqueue_delivery=AsyncMock(),
            set_status_message=AsyncMock(),
        )

        delivered = await bot.update_live_card(application)

        self.assertTrue(delivered)
        bot.get_user.assert_called_once_with(99)
        bot.fetch_user.assert_not_awaited()
        user.send.assert_awaited_once()
        self.assertEqual(user.send.await_args.kwargs["embed"].title, "Minecraft Application Denied")
        bot.data.enqueue_delivery.assert_not_awaited()

    async def test_existing_verified_message_has_controls_removed(self):
        application = SimpleNamespace(
            id=1,
            discord_user_id="99",
            edition=Edition.JAVA,
            verified_username="PlayerOne",
            status=ApplicationStatus.PENDING_REVIEW,
            status_channel_id="50",
            status_message_id="60",
        )
        message = SimpleNamespace(edit=AsyncMock())
        channel = SimpleNamespace(fetch_message=AsyncMock(return_value=message))
        bot = object.__new__(MinecraftAccessBot)
        bot._configured_channel = AsyncMock(return_value=channel)
        bot.data = SimpleNamespace(
            get_application=AsyncMock(return_value=application),
            enqueue_delivery=AsyncMock(),
        )

        delivered = await bot.update_live_card(application)

        self.assertTrue(delivered)
        message.edit.assert_awaited_once()
        self.assertIsNone(message.edit.await_args.kwargs["view"])
        bot.data.enqueue_delivery.assert_not_awaited()

    async def test_concurrent_verified_delivery_sends_only_one_dm(self):
        stored = SimpleNamespace(
            id=1,
            discord_user_id="99",
            edition=Edition.JAVA,
            verified_username="PlayerOne",
            status=ApplicationStatus.PENDING_REVIEW,
            status_channel_id=None,
            status_message_id=None,
        )
        sent_message = SimpleNamespace(channel=SimpleNamespace(id=50), id=60, edit=AsyncMock())
        user = SimpleNamespace(send=AsyncMock(return_value=sent_message))
        channel = SimpleNamespace(fetch_message=AsyncMock(return_value=sent_message))

        async def remember_message(_application_id, channel_id, message_id):
            stored.status_channel_id = str(channel_id)
            stored.status_message_id = str(message_id)

        bot = object.__new__(MinecraftAccessBot)
        bot._live_card_lock = asyncio.Lock()
        bot.get_user = lambda _user_id: user
        bot.fetch_user = AsyncMock()
        bot._configured_channel = AsyncMock(return_value=channel)
        bot.data = SimpleNamespace(
            get_application=AsyncMock(side_effect=lambda _application_id: stored),
            set_status_message=AsyncMock(side_effect=remember_message),
            enqueue_delivery=AsyncMock(),
        )

        results = await asyncio.gather(
            bot.update_live_card(stored),
            bot.update_live_card(stored),
        )

        self.assertEqual(results, [True, True])
        user.send.assert_awaited_once()
        sent_message.edit.assert_awaited_once()
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
        self.assertIs(
            agree_response.send_modal.await_args.args[0].source_message,
            agree_interaction.message,
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
