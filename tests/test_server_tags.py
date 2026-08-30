import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock

import cogs.server_tags as server_tags
from cogs.server_tags import (
    accepted_guild_ids,
    parse_guild_ids,
    tag_matches,
    wearer_of,
)

HOME = 111
LINKED = 222
OTHER = 999


def member(primary_guild_id, identity_enabled=True, *, is_bot=False):
    return SimpleNamespace(
        bot=is_bot,
        primary_guild=SimpleNamespace(
            id=primary_guild_id, identity_enabled=identity_enabled
        ),
    )


class ParseGuildIdsTests(unittest.TestCase):
    def test_reads_ids_defensively(self):
        self.assertEqual([1, 2], parse_guild_ids([1, "2"]))
        self.assertEqual([], parse_guild_ids(None))
        self.assertEqual([], parse_guild_ids("123"))
        self.assertEqual([5], parse_guild_ids([5, 5, "nope", None, -3, 0]))


class AcceptedGuildIdsTests(unittest.TestCase):
    def test_unset_means_this_server_only(self):
        self.assertEqual({HOME}, accepted_guild_ids([], HOME))
        self.assertEqual({HOME}, accepted_guild_ids(None, HOME))

    def test_linking_replaces_the_default_with_the_stored_set(self):
        self.assertEqual({HOME, LINKED}, accepted_guild_ids([HOME, LINKED], HOME))


class TagMatchesTests(unittest.TestCase):
    def test_wearing_an_accepted_tag_earns_the_role(self):
        self.assertTrue(tag_matches(HOME, True, {HOME}))
        self.assertTrue(tag_matches(LINKED, True, {HOME, LINKED}))

    def test_an_unlinked_or_absent_tag_does_not(self):
        self.assertFalse(tag_matches(OTHER, True, {HOME, LINKED}))
        self.assertFalse(tag_matches(None, True, {HOME}))

    def test_a_hidden_tag_does_not_count(self):
        self.assertFalse(tag_matches(HOME, False, {HOME}))

    def test_an_unreaffirmed_tag_still_counts(self):
        # identity_enabled is three-valued: None means the user has a primary guild but
        # has not reaffirmed it, and the tag can still show. Dropping the role on that
        # would flap it every time Discord changed the badge.
        self.assertTrue(tag_matches(HOME, None, {HOME}))


class WearerOfTests(unittest.TestCase):
    def test_reads_the_members_primary_guild(self):
        self.assertTrue(wearer_of(member(HOME), {HOME}))
        self.assertFalse(wearer_of(member(OTHER), {HOME}))

    def test_a_member_without_a_primary_guild_is_not_a_wearer(self):
        self.assertFalse(wearer_of(SimpleNamespace(primary_guild=None), {HOME}))


class SyncMemberTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.cog = server_tags.ServerTagCog(Mock())
        self.role = Mock()
        self.cog._role = Mock(return_value=self.role)
        self.cog._assignable = Mock(return_value=True)
        self.cog._accepted = Mock(return_value={HOME})

    def _member(self, primary_guild_id, held):
        target = member(primary_guild_id)
        target.guild = SimpleNamespace(id=HOME)
        target.roles = [self.role] if held else []
        target.add_roles = AsyncMock()
        target.remove_roles = AsyncMock()
        return target

    async def test_grants_the_role_to_a_new_wearer(self):
        target = self._member(HOME, held=False)
        self.assertTrue(await self.cog.sync_member(target))
        target.add_roles.assert_awaited_once()
        target.remove_roles.assert_not_awaited()

    async def test_takes_the_role_back_when_the_tag_goes(self):
        target = self._member(OTHER, held=True)
        self.assertFalse(await self.cog.sync_member(target))
        target.remove_roles.assert_awaited_once()
        target.add_roles.assert_not_awaited()

    async def test_does_nothing_when_already_correct(self):
        target = self._member(HOME, held=True)
        self.assertIsNone(await self.cog.sync_member(target))
        target.add_roles.assert_not_awaited()
        target.remove_roles.assert_not_awaited()

    async def test_a_linked_servers_tag_earns_the_role_here(self):
        self.cog._accepted = Mock(return_value={HOME, LINKED})
        target = self._member(LINKED, held=False)
        self.assertTrue(await self.cog.sync_member(target))
        target.add_roles.assert_awaited_once()

    async def test_bots_are_skipped(self):
        target = self._member(HOME, held=False)
        target.bot = True
        self.assertIsNone(await self.cog.sync_member(target))
        target.add_roles.assert_not_awaited()

    async def test_a_role_above_the_bot_is_left_alone(self):
        self.cog._assignable = Mock(return_value=False)
        target = self._member(HOME, held=False)
        self.assertIsNone(await self.cog.sync_member(target))
        target.add_roles.assert_not_awaited()


if __name__ == "__main__":
    unittest.main()
