import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

import cogs.case_panel as case_panel
from cogs.case_panel import OpenCaseButton, build_case_link_view
from cogs.cases import build_undo_confirm_embed

CASE_OPEN_TEMPLATE = OpenCaseButton.__discord_ui_compiled_template__


def make_target(user_id: int = 7):
    return SimpleNamespace(
        id=user_id,
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


if __name__ == "__main__":
    unittest.main()
