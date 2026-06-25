import os
import unittest
from types import SimpleNamespace
from unittest.mock import patch

from core.bot import MGXBot, fingerprint_payloads
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


if __name__ == "__main__":
    unittest.main()
