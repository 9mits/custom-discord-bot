import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock, patch

from core import bot as bot_module
from core.bot import MGXBot


class TempbanExpiryTests(unittest.IsolatedAsyncioTestCase):
    def _fake_bot(self, *, unban_side_effect=None):
        guild = SimpleNamespace(unban=AsyncMock(side_effect=unban_side_effect))
        manager = SimpleNamespace(
            config={"guild_id": 123},
            get_due_tempbans=AsyncMock(return_value=[("42", {"case_id": 7})]),
            mark_punishment_inactive=AsyncMock(return_value=True),
        )
        return SimpleNamespace(
            data_manager=manager,
            get_guild=Mock(return_value=guild),
        ), guild, manager

    async def test_successful_unban_marks_case_inactive(self):
        fake, guild, manager = self._fake_bot()

        await MGXBot.check_tempbans.coro(fake)

        guild.unban.assert_awaited_once()
        manager.mark_punishment_inactive.assert_awaited_once_with(7)

    async def test_transient_failure_leaves_case_due_for_retry(self):
        fake, guild, manager = self._fake_bot(unban_side_effect=RuntimeError("network down"))

        with patch.object(bot_module.logger, "exception"):
            await MGXBot.check_tempbans.coro(fake)

        guild.unban.assert_awaited_once()
        manager.mark_punishment_inactive.assert_not_awaited()

    async def test_not_found_confirms_expiry(self):
        class FakeNotFound(Exception):
            pass

        fake, guild, manager = self._fake_bot(unban_side_effect=FakeNotFound())
        with patch.object(bot_module.discord, "NotFound", FakeNotFound):
            await MGXBot.check_tempbans.coro(fake)

        guild.unban.assert_awaited_once()
        manager.mark_punishment_inactive.assert_awaited_once_with(7)

    async def test_missing_guild_does_not_consume_due_case(self):
        fake, guild, manager = self._fake_bot()
        fake.get_guild.return_value = None

        await MGXBot.check_tempbans.coro(fake)

        manager.get_due_tempbans.assert_not_awaited()
        guild.unban.assert_not_awaited()
        manager.mark_punishment_inactive.assert_not_awaited()


if __name__ == "__main__":
    unittest.main()
