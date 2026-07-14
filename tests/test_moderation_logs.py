import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock, call, patch

import discord

import cogs.moderation as moderation
import cogs.roles as roles
from cogs.cases import build_punishment_execution_log_embed
from cogs.moderation import (
    PunishDetailsModal,
    capture_message_evidence,
    delete_evidence_message,
    punish,
    punish_message_context,
)
from cogs.shared import format_user_id_ref, format_user_ref
from core.services import normalize_case_record


class ModerationLogFormatTests(unittest.TestCase):
    def test_user_references_do_not_repeat_display_names(self):
        user = SimpleNamespace(id=42, mention="<@42>", display_name="mits")

        self.assertEqual(format_user_ref(user), "<@42> (`42`)")
        self.assertEqual(format_user_id_ref(42, fallback_name="mits"), "<@42> (`42`)")

    def test_case_status_metadata_is_removed_during_normalization(self):
        record = {
            "case_id": 12,
            "status": "open",
            "resolution_state": "pending",
            "type": "warn",
        }

        self.assertTrue(normalize_case_record(record))
        self.assertNotIn("status", record)
        self.assertNotIn("resolution_state", record)

    def test_punishment_log_has_message_evidence_without_status(self):
        record = {
            "case_id": 12,
            "action_id": "CASE-000012",
            "reason": "Spamming",
            "type": "timeout",
            "duration_minutes": 60,
            "timestamp": "2026-07-15T00:00:00+00:00",
            "note": "Staff context",
            "source_message": {
                "id": 99,
                "jump_url": "https://discord.com/channels/1/2/99",
                "content": "flagged text",
                "attachments": [{
                    "filename": "proof.png",
                    "url": "https://example.invalid/proof.png",
                    "content_type": "image/png",
                }],
                "deleted": True,
            },
        }

        embed = build_punishment_execution_log_embed(
            guild=None,
            case_label="Case #12",
            actor="<@1> (`1`)",
            target="<@2> (`2`)",
            record=record,
        )

        fields = {field.name: field.value for field in embed.fields}
        self.assertNotIn("Status", fields)
        self.assertEqual(fields["Message ID"], "[99](https://discord.com/channels/1/2/99)")
        self.assertIn("flagged text", fields["Flagged Message"])
        self.assertEqual(fields["Attachments"], "[proof.png](https://example.invalid/proof.png)")
        self.assertEqual(embed.image.url, "https://example.invalid/proof.png")


class MessagePunishmentTests(unittest.IsolatedAsyncioTestCase):
    async def test_capture_and_delete_message_evidence(self):
        attachment = SimpleNamespace(
            filename="proof.png",
            url="https://example.invalid/proof.png",
            content_type="image/png",
        )
        message = SimpleNamespace(
            id=99,
            channel=SimpleNamespace(id=2),
            jump_url="https://discord.com/channels/1/2/99",
            content="flagged text",
            attachments=[attachment],
            delete=AsyncMock(),
        )

        evidence = capture_message_evidence(message)
        await delete_evidence_message(message, evidence)

        message.delete.assert_awaited_once()
        self.assertTrue(evidence["deleted"])
        self.assertEqual(evidence["id"], 99)
        self.assertEqual(evidence["attachments"][0]["filename"], "proof.png")

    async def test_repeat_offense_label_is_not_inserted_into_staff_note(self):
        target = SimpleNamespace(id=2, display_name="target")
        moderator = SimpleNamespace(id=1)
        modal = PunishDetailsModal(
            target,
            moderator,
            "Spamming",
            {"base": 0, "escalated": 60},
        )
        modal.mod_note._value = "Staff context"
        modal.mod_message._value = ""
        modal.duration_override._value = ""
        interaction = SimpleNamespace(response=SimpleNamespace(defer=AsyncMock()))
        fake_bot = SimpleNamespace(data_manager=SimpleNamespace(punishments={"2": []}))

        with patch.object(moderation, "bot", fake_bot), patch.object(
            moderation,
            "calculate_offense_punishment",
            return_value=(60, True, "Repeat Offense (1 prior in 90d)"),
        ), patch.object(moderation, "execute_punishment", new=AsyncMock()) as execute:
            await modal.on_submit(interaction)

        self.assertEqual(execute.await_args.args[5], "Staff context")

    async def test_message_context_menu_is_registered_and_targets_author(self):
        self.assertIsNone(punish_message_context.default_permissions)
        self.assertIn("message_id", {parameter.name for parameter in punish.parameters})
        fake_bot = SimpleNamespace(add_cog=AsyncMock(), tree=SimpleNamespace(add_command=Mock()))
        await moderation.setup(fake_bot)
        self.assertIn(call(punish_message_context), fake_bot.tree.add_command.call_args_list)

        author = SimpleNamespace(id=2, bot=False)
        message = SimpleNamespace(author=author)
        interaction = SimpleNamespace()
        with patch.object(moderation, "is_staff", return_value=True), patch.object(
            moderation,
            "_resolve_message_author",
            new=AsyncMock(return_value=author),
        ), patch.object(moderation, "show_punish_menu", new=AsyncMock()) as show:
            await punish_message_context.callback(interaction, message)

        show.assert_awaited_once_with(interaction, author, evidence_message=message)

    async def test_punish_message_id_uses_the_message_author(self):
        guild = SimpleNamespace(id=1)
        author = SimpleNamespace(id=2, bot=False)
        message_id = 123456789012345678
        message = SimpleNamespace(id=message_id, guild=guild, author=author)
        channel = SimpleNamespace(fetch_message=AsyncMock(return_value=message))
        interaction = SimpleNamespace(guild=guild, channel=channel)

        with patch.object(
            moderation,
            "_resolve_message_author",
            new=AsyncMock(return_value=author),
        ), patch.object(moderation, "show_punish_menu", new=AsyncMock()) as show:
            await punish.callback(interaction, message_id=str(message_id))

        channel.fetch_message.assert_awaited_once_with(message_id)
        show.assert_awaited_once_with(interaction, author, evidence_message=message)

    async def test_punish_panel_stays_compact_and_shows_selected_message(self):
        target = SimpleNamespace(
            id=2,
            mention="<@2>",
            display_avatar=SimpleNamespace(url="https://example.invalid/avatar.png"),
        )
        message = SimpleNamespace(
            id=99,
            jump_url="https://discord.com/channels/1/2/99",
            content="flagged text",
            attachments=[],
        )
        fake_bot = SimpleNamespace(data_manager=SimpleNamespace(punishments={"2": []}))

        with patch.object(roles, "bot", fake_bot), patch.object(
            roles,
            "get_active_records_for_user",
            return_value=[],
        ), patch.object(
            roles,
            "make_embed",
            side_effect=lambda title, description=None, **kwargs: discord.Embed(
                title=title,
                description=description,
            ),
        ):
            embed = roles.build_punish_embed(target, evidence_message=message)

        fields = {field.name: field.value for field in embed.fields}
        self.assertEqual(set(fields), {"Target", "Prior Punishments", "Message ID", "Flagged Message"})
        self.assertEqual(fields["Target"], "<@2> (`2`)")


if __name__ == "__main__":
    unittest.main()
