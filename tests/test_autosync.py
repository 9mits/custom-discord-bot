import os
import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock, patch

import discord

import cogs.admin as admin_module
from core.bot import MGXBot, command_payloads, fingerprint_payloads
from core.constants import TEST_GUILD_ID


class FingerprintPayloadsTests(unittest.TestCase):
    def test_same_commands_same_fingerprint(self):
        a = [{"name": "about", "description": "x"}, {"name": "stats", "description": "y"}]
        b = [{"name": "about", "description": "x"}, {"name": "stats", "description": "y"}]
        self.assertEqual(fingerprint_payloads(a), fingerprint_payloads(b))

    def test_order_independent(self):
        a = [{"name": "about"}, {"name": "stats"}, {"name": "directory"}]
        b = [{"name": "directory"}, {"name": "about"}, {"name": "stats"}]
        self.assertEqual(fingerprint_payloads(a), fingerprint_payloads(b))

    def test_added_command_changes_fingerprint(self):
        before = [{"name": "stats"}]
        after = [{"name": "stats"}, {"name": "about"}]
        self.assertNotEqual(fingerprint_payloads(before), fingerprint_payloads(after))

    def test_changed_description_changes_fingerprint(self):
        before = [{"name": "about", "description": "old"}]
        after = [{"name": "about", "description": "new"}]
        self.assertNotEqual(fingerprint_payloads(before), fingerprint_payloads(after))

    def test_empty_is_stable(self):
        self.assertEqual(fingerprint_payloads([]), fingerprint_payloads([]))


class CommandPayloadsTests(unittest.TestCase):
    def test_serializes_the_requested_guild_scope(self):
        guild = SimpleNamespace(id=123)
        command = SimpleNamespace(
            qualified_name="event",
            to_dict=Mock(return_value={"name": "event"}),
        )
        tree = SimpleNamespace(get_commands=Mock(return_value=[command]))

        self.assertEqual(command_payloads(tree, guild=guild), [{"name": "event"}])
        tree.get_commands.assert_called_once_with(guild=guild)
        command.to_dict.assert_called_once_with(tree)


class ManualSyncTests(unittest.IsolatedAsyncioTestCase):
    async def test_manual_sync_preserves_local_guild_commands(self):
        guild = SimpleNamespace(id=123, name="Event Server", owner_id=42)
        tree = SimpleNamespace(
            clear_commands=Mock(),
            copy_global_to=Mock(),
            sync=AsyncMock(return_value=[SimpleNamespace(name="event")]),
        )
        fake_bot = SimpleNamespace(
            data_manager=SimpleNamespace(config={}),
            tree=tree,
            _remove_disabled_application_commands=Mock(),
            _resolve_sync_targets=Mock(return_value=[123]),
        )
        ctx = SimpleNamespace(
            guild=guild,
            author=SimpleNamespace(
                id=42,
                roles=[],
                guild_permissions=SimpleNamespace(administrator=False),
            ),
            message=SimpleNamespace(delete=AsyncMock()),
            send=AsyncMock(),
        )

        with patch.object(admin_module, "bot", fake_bot), patch.object(
            admin_module,
            "delete_remote_commands",
            AsyncMock(return_value=[]),
        ):
            await admin_module.sync.callback(ctx)

        tree.clear_commands.assert_not_called()
        tree.copy_global_to.assert_called_once_with(guild=guild)
        tree.sync.assert_awaited_once_with(guild=guild)

    async def test_manual_sync_does_not_copy_globals_to_scoped_only_guild(self):
        guild = SimpleNamespace(id=222, name="Event Server", owner_id=42)
        tree = SimpleNamespace(
            copy_global_to=Mock(),
            sync=AsyncMock(return_value=[SimpleNamespace(name="event")]),
        )
        fake_bot = SimpleNamespace(
            data_manager=SimpleNamespace(config={}),
            tree=tree,
            _remove_disabled_application_commands=Mock(),
            _resolve_sync_targets=Mock(return_value=[111]),
        )
        ctx = SimpleNamespace(
            guild=guild,
            author=SimpleNamespace(
                id=42,
                roles=[],
                guild_permissions=SimpleNamespace(administrator=False),
            ),
            message=SimpleNamespace(delete=AsyncMock()),
            send=AsyncMock(),
        )

        with patch.object(admin_module, "bot", fake_bot), patch.object(
            admin_module,
            "delete_remote_commands",
            AsyncMock(return_value=[]),
        ):
            await admin_module.sync.callback(ctx)

        tree.copy_global_to.assert_not_called()
        tree.sync.assert_awaited_once_with(guild=guild)


def _fake_bot(guild_id, member_guild_ids, present_ids):
    """Minimal stand-in exposing what _resolve_sync_targets reads off `self`."""
    return SimpleNamespace(
        data_manager=SimpleNamespace(config={"guild_id": guild_id}),
        guilds=[SimpleNamespace(id=g) for g in member_guild_ids],
        get_guild=lambda gid: SimpleNamespace(id=gid) if gid in present_ids else None,
    )


class ResolveSyncTargetsTests(unittest.TestCase):
    def test_test_mode_targets_test_guild_only(self):
        fake = _fake_bot(555, [555, 999], {555, 999})
        with patch.dict(os.environ, {"TEST_MODE": "1"}):
            self.assertEqual(MGXBot._resolve_sync_targets(fake), [TEST_GUILD_ID])

    def test_production_uses_configured_guild_when_member(self):
        fake = _fake_bot(555, [555], {555})
        with patch.dict(os.environ, {}, clear=True):
            self.assertEqual(MGXBot._resolve_sync_targets(fake), [555])

    def test_production_falls_back_to_member_guilds(self):
        # Not a member of the configured guild (e.g. fresh instance pre-/setup).
        fake = _fake_bot(555, [777, 888], set())
        with patch.dict(os.environ, {}, clear=True):
            self.assertEqual(sorted(MGXBot._resolve_sync_targets(fake)), [777, 888])


class ScopedSyncTargetsTests(unittest.IsolatedAsyncioTestCase):
    async def test_finds_only_joined_guilds_with_scoped_commands(self):
        tree = SimpleNamespace(
            get_commands=Mock(side_effect=lambda *, guild: [object()] if guild.id == 222 else []),
        )
        fake = SimpleNamespace(
            guilds=[SimpleNamespace(id=111), SimpleNamespace(id=222)],
            tree=tree,
        )

        self.assertEqual(MGXBot._resolve_scoped_sync_targets(fake), [222])

    async def test_scoped_only_target_does_not_receive_global_commands(self):
        command = SimpleNamespace(
            qualified_name="event",
            to_dict=Mock(return_value={"name": "event"}),
        )
        tree = SimpleNamespace(
            get_commands=Mock(return_value=[command]),
            copy_global_to=Mock(),
            sync=AsyncMock(return_value=[command]),
        )
        data_manager = SimpleNamespace(
            config={},
            mark_config_dirty=Mock(),
            save_all=AsyncMock(),
        )
        fake = SimpleNamespace(
            tree=tree,
            data_manager=data_manager,
            _resolve_sync_targets=Mock(return_value=[111]),
            _resolve_scoped_sync_targets=Mock(return_value=[222]),
        )

        await MGXBot._auto_sync_commands(fake)

        tree.copy_global_to.assert_called_once()
        self.assertEqual(tree.copy_global_to.call_args.kwargs["guild"], discord.Object(id=111))
        self.assertEqual(
            {sync_call.kwargs["guild"].id for sync_call in tree.sync.await_args_list},
            {111, 222},
        )
        data_manager.save_all.assert_awaited_once()


if __name__ == "__main__":
    unittest.main()
