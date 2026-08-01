import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock, patch

from cogs import analytics, config, event_leaderboard, export, moderation, testkit
from cogs.shared import resolve_channel_input, resolve_member_input, resolve_user_input


USER_ID = 123456789012345678
CHANNEL_ID = 987654321098765432


class IdInputResolverTests(unittest.IsolatedAsyncioTestCase):
    async def test_member_mention_prefers_guild_cache(self):
        member = object()
        guild = SimpleNamespace(
            get_member=Mock(return_value=member),
            fetch_member=AsyncMock(),
        )

        result = await resolve_member_input(guild, f"<@{USER_ID}>")

        self.assertIs(result, member)
        guild.get_member.assert_called_once_with(USER_ID)
        guild.fetch_member.assert_not_awaited()

    async def test_member_id_falls_back_to_guild_api(self):
        member = object()
        guild = SimpleNamespace(
            get_member=Mock(return_value=None),
            fetch_member=AsyncMock(return_value=member),
        )

        result = await resolve_member_input(guild, str(USER_ID))

        self.assertIs(result, member)
        guild.fetch_member.assert_awaited_once_with(USER_ID)

    async def test_user_id_can_resolve_outside_guild(self):
        user = object()
        with patch("cogs.shared.bot") as bot_mock:
            bot_mock.get_user.return_value = None
            bot_mock.fetch_user = AsyncMock(return_value=user)

            result = await resolve_user_input(None, str(USER_ID))

        self.assertIs(result, user)
        bot_mock.fetch_user.assert_awaited_once_with(USER_ID)

    async def test_channel_mention_prefers_guild_cache(self):
        channel = object()
        guild = SimpleNamespace(
            get_channel_or_thread=Mock(return_value=channel),
            fetch_channel=AsyncMock(),
        )

        result = await resolve_channel_input(guild, f"<#{CHANNEL_ID}>")

        self.assertIs(result, channel)
        guild.get_channel_or_thread.assert_called_once_with(CHANNEL_ID)
        guild.fetch_channel.assert_not_awaited()

    async def test_channel_id_falls_back_to_guild_api(self):
        channel = object()
        guild = SimpleNamespace(
            get_channel_or_thread=Mock(return_value=None),
            fetch_channel=AsyncMock(return_value=channel),
        )

        result = await resolve_channel_input(guild, str(CHANNEL_ID))

        self.assertIs(result, channel)
        guild.fetch_channel.assert_awaited_once_with(CHANNEL_ID)

    async def test_invalid_ids_do_not_query_discord(self):
        guild = SimpleNamespace(
            get_member=Mock(),
            fetch_member=AsyncMock(),
            get_channel_or_thread=Mock(),
            fetch_channel=AsyncMock(),
        )

        self.assertIsNone(await resolve_member_input(guild, "not-an-id"))
        self.assertIsNone(await resolve_channel_input(guild, "not-an-id"))
        guild.get_member.assert_not_called()
        guild.fetch_member.assert_not_awaited()
        guild.get_channel_or_thread.assert_not_called()
        guild.fetch_channel.assert_not_awaited()


class CommandIdOptionTests(unittest.TestCase):
    def assert_has_option(self, command, option_name):
        self.assertIn(option_name, {parameter.name for parameter in command.parameters})

    def test_user_targeting_commands_expose_raw_id_option(self):
        commands = (
            analytics.stats,
            export.export,
            moderation.punish,
            moderation.publicexecution,
            moderation.history,
            moderation.undo,
            moderation.case,
            testkit.test_simulate_punishment,
            testkit.test_user_history,
        )
        for command in commands:
            with self.subTest(command=command.name):
                self.assert_has_option(command, "userid")

    def test_channel_targeting_commands_expose_raw_id_option(self):
        commands = (
            config.modmail_panel_cmd,
            export.export,
            event_leaderboard.event_group.get_command("setup"),
            event_leaderboard.event_group.get_command("vc"),
        )
        for command in commands:
            with self.subTest(command=command.name):
                self.assert_has_option(command, "channelid")


if __name__ == "__main__":
    unittest.main()
