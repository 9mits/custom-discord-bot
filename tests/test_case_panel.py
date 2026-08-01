import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

import discord

import cogs.case_panel as case_panel
from cogs.case_panel import CasePanelView, OpenCaseButton, build_case_link_view
from cogs.cases import build_case_detail_embed, build_undo_confirm_embed

CASE_OPEN_TEMPLATE = OpenCaseButton.__discord_ui_compiled_template__


def make_target(user_id: int = 7):
    return SimpleNamespace(
        id=user_id,
        mention=f"<@{user_id}>",
        display_name="Target",
        display_avatar=SimpleNamespace(url="https://example.com/avatar.png"),
    )


def make_record(case_id: int = 7):
    return {
        "case_id": case_id,
        "reason": "Spamming",
        "type": "warn",
        "duration_minutes": 0,
        "timestamp": "2026-07-01T00:00:00+00:00",
        "moderator": 42,
    }


class OpenCaseButtonTests(unittest.TestCase):
    def test_custom_id_matches_template(self):
        button = OpenCaseButton(45)
        self.assertEqual(button.item.custom_id, "case:open:45")
        match = CASE_OPEN_TEMPLATE.fullmatch(button.item.custom_id)
        self.assertIsNotNone(match)
        self.assertEqual(match["case_id"], "45")

    def test_template_rejects_foreign_custom_ids(self):
        for custom_id in ("case:open:", "case:open:abc", "mm_close", "revoke_punishment_btn"):
            self.assertIsNone(CASE_OPEN_TEMPLATE.fullmatch(custom_id))


class OpenCaseButtonAsyncTests(unittest.IsolatedAsyncioTestCase):
    async def test_build_case_link_view_is_persistent(self):
        view = build_case_link_view(99)
        self.assertIsNone(view.timeout)
        self.assertEqual(len(view.children), 1)
        self.assertEqual(view.children[0].custom_id, "case:open:99")

    async def test_from_custom_id_roundtrip(self):
        match = CASE_OPEN_TEMPLATE.fullmatch("case:open:123")
        item = await OpenCaseButton.from_custom_id(SimpleNamespace(), None, match)
        self.assertEqual(item.case_id, 123)

    async def test_revoke_undo_button_roundtrip(self):
        from cogs.moderation import RevokeUndoButton
        template = RevokeUndoButton.__discord_ui_compiled_template__
        match = template.fullmatch("case:revoke_undo:77")
        self.assertIsNotNone(match)
        item = await RevokeUndoButton.from_custom_id(SimpleNamespace(), None, match)
        self.assertEqual(item.case_id, 77)
        self.assertEqual(item.item.custom_id, "case:revoke_undo:77")

    async def test_callback_rejects_non_staff(self):
        button = OpenCaseButton(5)
        interaction = SimpleNamespace(user=SimpleNamespace(id=1))
        with patch.object(case_panel, "is_staff", return_value=False), \
                patch.object(case_panel, "respond_with_error", new=AsyncMock()) as denied, \
                patch.object(case_panel, "show_case_panel", new=AsyncMock()) as opened:
            await button.callback(interaction)
        denied.assert_awaited_once()
        opened.assert_not_awaited()

    async def test_callback_opens_panel_for_staff(self):
        button = OpenCaseButton(5)
        interaction = SimpleNamespace(user=SimpleNamespace(id=1))
        with patch.object(case_panel, "is_staff", return_value=True), \
                patch.object(case_panel, "show_case_panel", new=AsyncMock()) as opened:
            await button.callback(interaction)
        opened.assert_awaited_once_with(interaction, case_id=5)


class UndoConfirmEmbedTests(unittest.TestCase):
    def test_contains_reason_and_case_label(self):
        embed = build_undo_confirm_embed(make_target(), make_record(7), "Appeal accepted by staff.", guild=None)
        self.assertIn("Case #7", embed.title)
        field_names = [field.name for field in embed.fields]
        self.assertIn("Undo Reason", field_names)
        self.assertIn("Case Details", field_names)
        reason_field = next(field for field in embed.fields if field.name == "Undo Reason")
        self.assertIn("Appeal accepted by staff.", reason_field.value)


class CaseDetailEmbedTests(unittest.TestCase):
    def test_case_panel_only_has_repeat_and_undo_actions(self):
        view = CasePanelView("7", [12], target_user=make_target())
        self.assertEqual([item.label for item in view.children], ["Punish Again", "Undo Case"])

    def test_case_panel_only_shows_practical_punishment_details(self):
        record = {
            **make_record(12),
            "action_id": "CASE-000012",
            "evidence_links": ["https://example.com/old-evidence"],
            "internal_notes": [{"author_id": 42, "note": "Old note", "created_at": "2026-07-01T00:00:00+00:00"}],
            "linked_cases": [3],
            "tags": ["old-tag"],
        }

        with patch("cogs.shared.get_theme_color", return_value=discord.Color.blurple()):
            embed = build_case_detail_embed(None, "7", record, target_user=make_target())

        self.assertEqual(embed.title, "Case #12")
        self.assertEqual(embed.thumbnail.url, "https://example.com/avatar.png")
        field_names = {field.name for field in embed.fields}
        self.assertEqual(
            field_names,
            {"Target", "Server Status", "Punishment", "Reason", "Issued", "Moderator"},
        )
        self.assertTrue(
            {"Evidence", "Notes", "Tags", "Linked Cases", "Action ID"}.isdisjoint(field_names)
        )


if __name__ == "__main__":
    unittest.main()
