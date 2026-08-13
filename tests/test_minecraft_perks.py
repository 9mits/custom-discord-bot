import unittest

from minecraft_bot.perks import (
    LEVEL_ROLE_MILESTONES,
    RANK_GROUPS,
    RANK_ROLES,
    profile_for_role_ids,
    rank_for_role_ids,
)


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


class MinecraftRankTests(unittest.TestCase):
    def test_no_rank_role_returns_none(self):
        self.assertIsNone(rank_for_role_ids([1, 2, 3]))

    def test_highest_priority_rank_wins(self):
        owner_id = RANK_ROLES[0][0]
        booster_id = RANK_ROLES[-1][0]

        rank = rank_for_role_ids([booster_id, owner_id])

        self.assertIsNotNone(rank)
        self.assertEqual(rank.group, "owner")

    def test_lower_rank_used_when_held_alone(self):
        booster_id = RANK_ROLES[-1][0]

        rank = rank_for_role_ids([booster_id])

        self.assertIsNotNone(rank)
        self.assertEqual(rank.group, "booster")
        self.assertEqual(rank.label, "BOOSTER")

    def test_rank_roles_are_unique_and_ordered_by_priority(self):
        role_ids = [role_id for role_id, _group, _label, _colour in RANK_ROLES]

        self.assertEqual(len(role_ids), len(set(role_ids)))
        self.assertEqual(len(RANK_GROUPS), len(set(RANK_GROUPS)))
        self.assertEqual(RANK_GROUPS[0], "owner")

    def test_rank_colours_are_valid_rgb(self):
        for _role_id, group, label, colour in RANK_ROLES:
            with self.subTest(group=group):
                self.assertTrue(0 <= colour <= 0xFFFFFF)
                self.assertTrue(label.isupper())

    def test_rank_does_not_disturb_level_perks(self):
        owner_id = RANK_ROLES[0][0]
        level_ten = LEVEL_ROLE_MILESTONES[1][0]

        profile = profile_for_role_ids([owner_id, level_ten])

        self.assertEqual(profile.level, 10)
        self.assertFalse(profile.elite)


if __name__ == "__main__":
    unittest.main()
