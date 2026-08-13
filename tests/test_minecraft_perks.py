import unittest

from minecraft_bot.perks import LEVEL_ROLE_MILESTONES, profile_for_role_ids


class MinecraftPerkTests(unittest.TestCase):
    def test_no_milestone_role_has_no_perks(self):
        profile = profile_for_role_ids([1, 2, 3])

        self.assertEqual(profile.level, 0)
        self.assertEqual(profile.extra_hearts, 0)
        self.assertFalse(profile.elite)

    def test_milestone_hearts_stack_until_level_fifty(self):
        role_ids = [role_id for role_id, _level in LEVEL_ROLE_MILESTONES]

        profile = profile_for_role_ids(role_ids)

        self.assertEqual(profile.level, 50)
        self.assertEqual(profile.extra_hearts, 5)
        self.assertTrue(profile.elite)

    def test_highest_owned_role_controls_displayed_level(self):
        level_ten = LEVEL_ROLE_MILESTONES[1][0]
        level_forty = LEVEL_ROLE_MILESTONES[4][0]

        profile = profile_for_role_ids([level_ten, level_forty])

        self.assertEqual(profile.level, 40)
        self.assertEqual(profile.extra_hearts, 5)
        self.assertFalse(profile.elite)

    def test_level_fifty_role_does_not_add_a_heart(self):
        level_fifty = LEVEL_ROLE_MILESTONES[-1][0]

        profile = profile_for_role_ids([level_fifty])

        self.assertEqual(profile.level, 50)
        self.assertEqual(profile.extra_hearts, 5)
        self.assertTrue(profile.elite)


if __name__ == "__main__":
    unittest.main()
