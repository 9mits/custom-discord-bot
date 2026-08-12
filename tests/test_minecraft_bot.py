import unittest
from types import SimpleNamespace

from minecraft_bot.bot import MinecraftAccessBot, RateLimiter
from minecraft_bot.presentation import application_panel
from minecraft_bot.ui import MinecraftApplicationModal, ReviewView


class MinecraftBotPolicyTests(unittest.TestCase):
    def setUp(self):
        self.bot = object.__new__(MinecraftAccessBot)
        self.bot.config = SimpleNamespace(mod_role_id=77)

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
                "minecraft:review:history",
            },
        )
        self.assertEqual(modal.edition.options[0].value, "JAVA")
        self.assertEqual(modal.edition.options[1].value, "BEDROCK")


if __name__ == "__main__":
    unittest.main()
