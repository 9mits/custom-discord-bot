import unittest

from minecraft_bot.perks import (
    DEVELOPER_ROLE_ID,
    OWNER_ROLE_ID,
    RANK_GROUPS,
    RANK_ROLES,
    rank_for_role_ids,
)


class MinecraftRankTests(unittest.TestCase):
    """Two ranks is the whole system: the ladder died with the old Discord."""

    def test_only_owner_and_developer_remain(self):
        self.assertEqual(("owner", "developer"), RANK_GROUPS)
        self.assertEqual(1550144602554634320, OWNER_ROLE_ID)
        self.assertEqual(1550144558296334366, DEVELOPER_ROLE_ID)

    def test_no_rank_role_returns_none(self):
        self.assertIsNone(rank_for_role_ids([1, 2, 3]))

    def test_first_mapped_role_in_caller_order_wins(self):
        # The bot passes roles highest-first, so Discord's own hierarchy decides the
        # tag. Owner sits above Developer there, which is why it is the one shown.
        self.assertEqual(
            rank_for_role_ids([OWNER_ROLE_ID, DEVELOPER_ROLE_ID]).group, "owner"
        )
        self.assertEqual(
            rank_for_role_ids([DEVELOPER_ROLE_ID, OWNER_ROLE_ID]).group, "developer"
        )

    def test_unmapped_roles_are_skipped(self):
        rank = rank_for_role_ids([1, 2, DEVELOPER_ROLE_ID])

        self.assertIsNotNone(rank)
        self.assertEqual(rank.group, "developer")
        self.assertEqual(rank.label, "DEVELOPER")

    def test_rank_roles_and_groups_are_unique(self):
        role_ids = [role_id for role_id, _group, _label, _colour in RANK_ROLES]

        self.assertEqual(len(role_ids), len(set(role_ids)))
        self.assertEqual(len(RANK_GROUPS), len(set(RANK_GROUPS)))

    def test_rank_colours_are_valid_rgb(self):
        for _role_id, group, label, colour in RANK_ROLES:
            with self.subTest(group=group):
                self.assertTrue(0 <= colour <= 0xFFFFFF)
                self.assertTrue(label.isupper())


if __name__ == "__main__":
    unittest.main()
