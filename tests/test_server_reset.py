import unittest
from contextlib import ExitStack
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

import discord

from cogs.server_reset import (
    MemberRemovalResult,
    ServerResetResult,
    _delete_other_integrations,
    _execute_server_destruction,
    _member_hierarchy_blockers,
    _role_hierarchy_blockers,
    build_destroy_summary,
    can_reset_server,
    destroy_server_confirmation_phrase,
    kick_all_confirmation_phrase,
    perform_kick_all_members,
    perform_server_reset,
    server_reset_confirmation_phrase,
)
from core.actions import RiskLevel, get_action_spec
from core.services import has_capability


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


class FakeMember:
    def __init__(self, member_id, name, *, top_position=1):
        self.id = member_id
        self.name = name
        self.top_role = SimpleNamespace(position=top_position)

    def __str__(self):
        return self.name


class FakeIntegration(FakeItem):
    def __init__(self, item_id, name, deletion_log, *, application_id, integration_type="discord"):
        super().__init__(item_id, name, deletion_log)
        self.application = SimpleNamespace(id=application_id)
        self.type = integration_type


class FakeGuild:
    def __init__(self):
        self.id = 42
        self.name = "Reset Me"
        self.features = ["COMMUNITY"]
        self.members = [object(), object()]
        self.edits = []
        self.deletion_log = []
        self.kick_log = []
        self.unbanned = []
        self.failed_kicks = set()
        self.owner_id = 10
        self.me = SimpleNamespace(id=999, top_role=SimpleNamespace(position=10))
        self.ban_entries = [
            SimpleNamespace(user=SimpleNamespace(id=401, name="banned-user")),
        ]
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

    async def edit_onboarding(self, **kwargs):
        self.edits.append({"onboarding": kwargs})

    async def edit_welcome_screen(self, **kwargs):
        self.edits.append({"welcome_screen": kwargs})

    async def fetch_roles(self):
        return self.roles

    async def fetch_channels(self):
        return self.channels

    async def unban(self, user, *, reason=None):
        self.unbanned.append(user.id)

    async def kick(self, member, *, reason=None):
        self.kick_log.append(member.id)
        if member.id in self.failed_kicks:
            raise discord.HTTPException(SimpleNamespace(status=500, reason="error"), "failed")

    async def bans(self, *, limit=None):
        for entry in self.ban_entries:
            yield entry

    def __getattr__(self, name):
        if name in self.resources:
            return AsyncMock(return_value=self.resources[name])
        raise AttributeError(name)


class ServerResetPolicyTests(unittest.TestCase):
    def test_confirmation_phrase_is_bound_to_the_guild(self):
        self.assertEqual(server_reset_confirmation_phrase(42), "DELETE EVERYTHING 42")
        self.assertEqual(kick_all_confirmation_phrase(42), "KICK EVERYONE 42")
        self.assertEqual(destroy_server_confirmation_phrase(42), "DESTROY SERVER 42")

    def test_action_is_admin_only_destructive_and_requires_bot_administrator(self):
        spec = get_action_spec("reset-server")
        self.assertIsNotNone(spec)
        self.assertEqual(spec.capability, "server.reset")
        self.assertEqual(spec.bot_permissions, ("administrator",))
        self.assertIs(spec.risk_level, RiskLevel.DESTRUCTIVE)
        for name in ("kick-all-members", "destroy-server"):
            extra = get_action_spec(name)
            self.assertIsNotNone(extra)
            self.assertEqual(extra.capability, "server.reset")
            self.assertEqual(extra.bot_permissions, ("administrator",))
            self.assertIs(extra.risk_level, RiskLevel.DESTRUCTIVE)

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
    async def test_removes_content_and_bans_without_touching_members(self):
        guild = FakeGuild()
        original_members = list(guild.members)

        result = await perform_server_reset(
            guild,
            reason="test reset",
            final_channel_ids={101},
        )

        self.assertEqual(guild.members, original_members)
        self.assertEqual(guild.kick_log, [])
        self.assertFalse(hasattr(guild, "ban"))
        self.assertEqual(result.failure_count, 0)
        self.assertEqual(result.sections["Roles"].deleted, 2)
        self.assertEqual(result.sections["Channels"].deleted, 3)
        self.assertEqual(result.sections["Bans"].deleted, 1)
        self.assertEqual(guild.unbanned, [401])
        self.assertIn("@everyone (1)", result.protected_roles)
        self.assertIn("managed (3)", result.protected_roles)
        self.assertIn("above bot (4)", result.protected_roles)
        self.assertEqual(result.hierarchy_blocked_roles, ["above bot (4)"])
        self.assertLess(guild.deletion_log.index(103), guild.deletion_log.index(101))
        self.assertEqual(guild.deletion_log[-1], 101)
        self.assertEqual(len(guild.edits), 5)
        self.assertFalse(guild.edits[0]["onboarding"]["enabled"])
        self.assertFalse(guild.edits[1]["welcome_screen"]["enabled"])
        self.assertTrue(guild.edits[2]["community"] is False)

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

    async def test_kick_all_preserves_owner_and_bot_and_kicks_requester_last(self):
        guild = FakeGuild()
        owner = FakeMember(10, "owner", top_position=100)
        bot_member = FakeMember(999, "cleanup-bot", top_position=10)
        ordinary = FakeMember(20, "ordinary")
        requester = FakeMember(30, "requester", top_position=5)

        result = await perform_kick_all_members(
            guild,
            [owner, bot_member, requester, ordinary],
            requester_id=requester.id,
            reason="test kick all",
        )

        self.assertEqual(guild.kick_log, [ordinary.id, requester.id])
        self.assertEqual(result.kicked, 2)
        self.assertEqual(result.attempted, 2)
        self.assertTrue(result.requester_kicked)
        self.assertEqual(result.failure_count, 0)

    async def test_kick_command_preserves_requester(self):
        guild = FakeGuild()
        owner = FakeMember(10, "owner", top_position=100)
        bot_member = FakeMember(999, "cleanup-bot", top_position=10)
        ordinary = FakeMember(20, "ordinary")
        requester = FakeMember(30, "requester", top_position=5)

        result = await perform_kick_all_members(
            guild,
            [owner, bot_member, requester, ordinary],
            requester_id=requester.id,
            reason="test kick command",
            keep_requester=True,
        )

        self.assertEqual(guild.kick_log, [ordinary.id])
        self.assertEqual(result.kicked, 1)
        self.assertEqual(result.attempted, 1)
        self.assertFalse(result.requester_kicked)
        self.assertEqual(result.failure_count, 0)

    async def test_kick_failure_preserves_requester_for_recovery(self):
        guild = FakeGuild()
        ordinary = FakeMember(20, "ordinary")
        requester = FakeMember(30, "requester", top_position=5)
        guild.failed_kicks.add(ordinary.id)

        result = await perform_kick_all_members(
            guild,
            [ordinary, requester],
            requester_id=requester.id,
            reason="test kick all",
        )

        self.assertEqual(guild.kick_log, [ordinary.id])
        self.assertEqual(result.failure_count, 1)
        self.assertFalse(result.requester_kicked)

    async def test_partial_destruction_skips_known_hierarchy_blockers(self):
        guild = FakeGuild()
        blocked = FakeMember(20, "blocked", top_position=11)
        ordinary = FakeMember(21, "ordinary")
        requester = FakeMember(30, "requester", top_position=5)

        result = await perform_kick_all_members(
            guild,
            [blocked, ordinary, requester],
            requester_id=requester.id,
            reason="test partial destruction",
            skip_member_ids={blocked.id},
        )

        self.assertEqual(guild.kick_log, [ordinary.id, requester.id])
        self.assertEqual(result.skipped, ["blocked (20)"])
        self.assertEqual(result.failure_count, 0)
        self.assertTrue(result.requester_kicked)

    async def test_partial_destruction_can_skip_blocked_requester(self):
        guild = FakeGuild()
        ordinary = FakeMember(21, "ordinary")
        requester = FakeMember(30, "requester", top_position=11)

        result = await perform_kick_all_members(
            guild,
            [ordinary, requester],
            requester_id=requester.id,
            reason="test partial destruction",
            skip_member_ids={requester.id},
        )

        self.assertEqual(guild.kick_log, [ordinary.id])
        self.assertEqual(result.skipped, ["requester (30)"])
        self.assertEqual(result.failure_count, 0)
        self.assertFalse(result.requester_kicked)

    async def test_hierarchy_preflight_finds_only_real_blockers(self):
        guild = FakeGuild()
        equal_role = FakeRole(6, "equal", guild.deletion_log, position=10)
        roles = guild.roles + [equal_role]

        self.assertEqual(
            [role.name for role in _role_hierarchy_blockers(guild, roles)],
            ["above bot", "equal"],
        )

        owner = FakeMember(10, "owner", top_position=100)
        bot_member = FakeMember(999, "cleanup-bot", top_position=10)
        low_member = FakeMember(20, "low", top_position=9)
        equal_member = FakeMember(21, "equal", top_position=10)
        high_member = FakeMember(22, "high", top_position=11)
        self.assertEqual(
            [
                member.name
                for member in _member_hierarchy_blockers(
                    guild,
                    [owner, bot_member, low_member, equal_member, high_member],
                )
            ],
            ["equal", "high"],
        )

    async def test_destroy_leaves_bot_integrations_for_member_kicks_and_removes_external_ones(self):
        guild = FakeGuild()
        cleanup = FakeIntegration(501, "cleanup", guild.deletion_log, application_id=999)
        other_bot = FakeIntegration(502, "other-bot", guild.deletion_log, application_id=123)
        external = FakeIntegration(
            503,
            "external",
            guild.deletion_log,
            application_id=456,
            integration_type="twitch",
        )
        guild.resources["integrations"] = [cleanup, other_bot, external]
        result = ServerResetResult()

        await _delete_other_integrations(
            guild,
            result,
            current_bot_id=999,
            reason="test integrations",
        )

        self.assertEqual(result.failure_count, 0)
        self.assertEqual(result.sections["Integrations"].deleted, 1)
        self.assertNotIn(cleanup.id, guild.deletion_log)
        self.assertNotIn(other_bot.id, guild.deletion_log)
        self.assertIn(external.id, guild.deletion_log)

    async def test_destroy_summary_reports_intentional_permission_limits(self):
        reset_result = ServerResetResult(hierarchy_blocked_roles=["higher-role (4)"])
        member_result = MemberRemovalResult(skipped=["higher-member (20)"])

        with patch(
            "cogs.server_reset.make_embed",
            side_effect=lambda title, description, **kwargs: discord.Embed(
                title=title,
                description=description,
            ),
        ):
            embed = build_destroy_summary("Reset Me", reset_result, member_result)

        self.assertEqual(embed.title, "Server Destruction Finished With Permission Limits")
        self.assertEqual(
            [field.name for field in embed.fields if "Permission Limits" in field.name],
            ["Roles Left By Permission Limits", "Members Left By Permission Limits"],
        )

    async def test_destroy_continues_to_member_kicks_after_content_failure(self):
        guild = SimpleNamespace(
            id=42,
            name="Reset Me",
            icon=None,
            me=SimpleNamespace(id=999),
        )
        requester = FakeMember(30, "requester")
        requester.send = AsyncMock()
        interaction = SimpleNamespace(
            guild=guild,
            user=requester,
            channel=SimpleNamespace(id=101, parent_id=None),
            edit_original_response=AsyncMock(),
        )
        reset_result = ServerResetResult()
        reset_result.section("Channels").failures.append("one channel failed")
        kick_result = MemberRemovalResult(kicked=1, attempted=1)

        with ExitStack() as stack:
            def stub(name, **kwargs):
                return stack.enter_context(patch(f"cogs.server_reset.{name}", **kwargs))

            # make_embed reads the live theme colour off the bot runtime, which no
            # unit test starts.
            stack.enter_context(
                patch("cogs.shared.get_theme_color", return_value=discord.Color.red())
            )
            server_reset = stub("perform_server_reset", new=AsyncMock(return_value=reset_result))
            stub("_scrub_guild_identity", new=AsyncMock())
            stub("_delete_other_integrations", new=AsyncMock())
            stub("_fetch_all_members", new=AsyncMock(return_value=[requester]))
            kick_members = stub(
                "perform_kick_all_members", new=AsyncMock(return_value=kick_result)
            )
            stub("_send_dm", new=AsyncMock())
            recovery = stub("_create_recovery_access", new=AsyncMock())

            await _execute_server_destruction(interaction, guild_name="Reset Me")

        server_reset.assert_awaited_once()
        kick_members.assert_awaited_once()
        self.assertTrue(kick_members.await_args.kwargs["keep_requester"])
        recovery.assert_awaited_once()


if __name__ == "__main__":
    unittest.main()
