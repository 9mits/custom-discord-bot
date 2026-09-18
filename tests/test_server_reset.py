import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock

import discord

from core.actions import RiskLevel, get_action_spec
from core.services import has_capability
from cogs.server_reset import (
    can_reset_server,
    perform_server_reset,
    server_reset_confirmation_phrase,
)


class FakeItem:
    def __init__(self, item_id, name, deletion_log, *, fail=False):
        self.id = item_id
        self.name = name
        self.deletion_log = deletion_log
        self.fail = fail

    async def delete(self, *, reason=None):
        self.deletion_log.append(self.id)
        if self.fail:
            raise discord.HTTPException(SimpleNamespace(status=500, reason="error"), "failed")


class FakeTemplate(FakeItem):
    async def delete(self):
        await super().delete()


class FakeRole(FakeItem):
    def __init__(self, item_id, name, deletion_log, *, position, default=False, managed=False):
        super().__init__(item_id, name, deletion_log)
        self.position = position
        self._default = default
        self.managed = managed

    def is_default(self):
        return self._default


class FakeChannel(FakeItem):
    def __init__(self, item_id, name, deletion_log, *, position, channel_type):
        super().__init__(item_id, name, deletion_log)
        self.position = position
        self.type = channel_type


class FakeGuild:
    def __init__(self):
        self.id = 42
        self.name = "Reset Me"
        self.features = ["COMMUNITY"]
        self.members = [object(), object()]
        self.edits = []
        self.deletion_log = []
        self.me = SimpleNamespace(top_role=SimpleNamespace(position=10))
        self.roles = [
            FakeRole(1, "@everyone", self.deletion_log, position=0, default=True),
            FakeRole(2, "member", self.deletion_log, position=2),
            FakeRole(3, "managed", self.deletion_log, position=3, managed=True),
            FakeRole(4, "above bot", self.deletion_log, position=11),
            FakeRole(5, "staff", self.deletion_log, position=8),
        ]
        self.channels = [
            FakeChannel(101, "current", self.deletion_log, position=1, channel_type=discord.ChannelType.text),
            FakeChannel(102, "other", self.deletion_log, position=2, channel_type=discord.ChannelType.voice),
            FakeChannel(103, "category", self.deletion_log, position=0, channel_type=discord.ChannelType.category),
        ]
        self.resources = {
            "fetch_automod_rules": [FakeItem(201, "automod", self.deletion_log)],
            "fetch_scheduled_events": [FakeItem(202, "event", self.deletion_log)],
            "fetch_soundboard_sounds": [FakeItem(203, "sound", self.deletion_log)],
            "webhooks": [FakeItem(204, "webhook", self.deletion_log)],
            "invites": [FakeItem(205, "invite", self.deletion_log)],
            "templates": [FakeTemplate(206, "template", self.deletion_log)],
            "fetch_emojis": [FakeItem(207, "emoji", self.deletion_log)],
            "fetch_stickers": [FakeItem(208, "sticker", self.deletion_log)],
        }

    async def edit(self, **kwargs):
        self.edits.append(kwargs)
        return self

    async def fetch_roles(self):
        return self.roles

    async def fetch_channels(self):
        return self.channels

    def __getattr__(self, name):
        if name in self.resources:
            return AsyncMock(return_value=self.resources[name])
        raise AttributeError(name)


class ServerResetPolicyTests(unittest.TestCase):
    def test_confirmation_phrase_is_bound_to_the_guild(self):
        self.assertEqual(server_reset_confirmation_phrase(42), "DELETE EVERYTHING 42")

    def test_action_is_admin_only_destructive_and_requires_bot_administrator(self):
        spec = get_action_spec("reset-server")
        self.assertIsNotNone(spec)
        self.assertEqual(spec.capability, "server.reset")
        self.assertEqual(spec.bot_permissions, ("administrator",))
        self.assertIs(spec.risk_level, RiskLevel.DESTRUCTIVE)

    def test_administrator_can_run_reset_but_ordinary_member_cannot(self):
        self.assertFalse(has_capability(
            [],
            "server.reset",
            {},
            administrator=False,
            user_id=10,
            guild_owner_id=99,
        ))
        self.assertTrue(has_capability(
            [],
            "server.reset",
            {},
            administrator=True,
            user_id=10,
            guild_owner_id=99,
        ))

    def test_confirmation_rechecks_current_administrator_permission(self):
        interaction = SimpleNamespace(
            guild=SimpleNamespace(id=42),
            user=SimpleNamespace(guild_permissions=SimpleNamespace(administrator=True)),
        )
        self.assertTrue(can_reset_server(interaction))
        interaction.user.guild_permissions.administrator = False
        self.assertFalse(can_reset_server(interaction))


class ServerResetExecutionTests(unittest.IsolatedAsyncioTestCase):
    async def test_removes_content_without_touching_members_or_bans(self):
        guild = FakeGuild()
        original_members = list(guild.members)

        result = await perform_server_reset(
            guild,
            reason="test reset",
            final_channel_ids={101},
        )

        self.assertEqual(guild.members, original_members)
        self.assertFalse(hasattr(guild, "kick"))
        self.assertFalse(hasattr(guild, "ban"))
        self.assertEqual(result.failure_count, 0)
        self.assertEqual(result.sections["Roles"].deleted, 2)
        self.assertEqual(result.sections["Channels"].deleted, 3)
        self.assertIn("@everyone (1)", result.protected_roles)
        self.assertIn("managed (3)", result.protected_roles)
        self.assertIn("above bot (4)", result.protected_roles)
        self.assertLess(guild.deletion_log.index(103), guild.deletion_log.index(101))
        self.assertEqual(guild.deletion_log[-1], 101)
        self.assertEqual(len(guild.edits), 3)
        self.assertTrue(guild.edits[0]["community"] is False)

    async def test_one_failed_deletion_does_not_stop_the_reset(self):
        guild = FakeGuild()
        guild.resources["fetch_emojis"] = [
            FakeItem(301, "broken", guild.deletion_log, fail=True),
            FakeItem(302, "working", guild.deletion_log),
        ]

        result = await perform_server_reset(guild, reason="test reset")

        self.assertEqual(result.failure_count, 1)
        self.assertEqual(result.sections["Emojis"].attempted, 2)
        self.assertEqual(result.sections["Emojis"].deleted, 1)
        self.assertIn(302, guild.deletion_log)


if __name__ == "__main__":
    unittest.main()
