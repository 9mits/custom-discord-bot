import os
from types import SimpleNamespace
import unittest
from unittest.mock import AsyncMock, Mock, call, patch

import discord

import cogs.event_leaderboard as event_module
from cogs.event_leaderboard import (
    EVENT_CONTROL_BRAND_NAME,
    EVENT_GUILD_ID,
    EventControlGroup,
    discord_timestamp_lines,
    endtimestamp_command,
    estimate_event_end_unix,
    event_control_enabled,
    event_group,
    event_progress_seconds,
    is_event_owner,
)


class EventProgressTests(unittest.TestCase):
    def test_progress_uses_runtime_after_baseline_is_applied(self):
        cfg = {"baseline_seconds": 3600, "baseline_token": 4}
        runtime = {"vc_active_seconds": 1800, "applied_baseline_token": 4}

        self.assertEqual(event_progress_seconds(cfg, runtime), 5400)

    def test_progress_ignores_stale_runtime_until_new_baseline_is_applied(self):
        cfg = {"baseline_seconds": 7200, "baseline_token": 5}
        runtime = {"vc_active_seconds": 1800, "applied_baseline_token": 4}

        self.assertEqual(event_progress_seconds(cfg, runtime), 7200)

    def test_progress_includes_live_unflushed_interval(self):
        cfg = {"baseline_seconds": 3600, "baseline_token": 5}
        runtime = {"vc_active_seconds": 10, "applied_baseline_token": 5}
        cog = SimpleNamespace(
            _loaded=True,
            _applied_baseline_token=5,
            _vc_active_seconds=120,
            _vc_active_since=1000.0,
        )

        self.assertEqual(event_progress_seconds(cfg, runtime, cog=cog, now=1030.0), 3750)

    def test_end_estimate_counts_only_remaining_progress(self):
        self.assertEqual(
            estimate_event_end_unix(3600, 2, now_unix=1_700_000_000),
            1_700_003_600,
        )
        self.assertEqual(
            estimate_event_end_unix(7200, 2, now_unix=1_700_000_000),
            1_700_000_000,
        )

    def test_all_discord_timestamp_formats_are_returned(self):
        lines = discord_timestamp_lines(1_700_000_000)

        self.assertEqual(len(lines), 7)
        for style in ("t", "T", "d", "D", "f", "F", "R"):
            self.assertTrue(any(f"<t:1700000000:{style}>" in line for line in lines))


class EventControlTests(unittest.IsolatedAsyncioTestCase):
    async def test_owner_check_requires_event_guild_and_owner_capability(self):
        interaction = SimpleNamespace(guild_id=EVENT_GUILD_ID)
        with patch.object(event_module, "has_permission_capability", return_value=True) as capability:
            self.assertTrue(is_event_owner(interaction))
            capability.assert_called_once_with(interaction, "owner_panel")

        interaction.guild_id = EVENT_GUILD_ID + 1
        with patch.object(event_module, "has_permission_capability") as capability:
            self.assertFalse(is_event_owner(interaction))
            capability.assert_not_called()

    async def test_event_commands_are_guild_scoped_and_admin_hidden_by_default(self):
        self.assertIsInstance(event_group, EventControlGroup)
        self.assertEqual(event_group._guild_ids, [EVENT_GUILD_ID])
        self.assertEqual(endtimestamp_command._guild_ids, [EVENT_GUILD_ID])
        self.assertTrue(event_group.default_permissions.administrator)
        self.assertTrue(endtimestamp_command.default_permissions.administrator)

    async def test_test_mode_no_longer_registers_event_controls(self):
        fake_bot = SimpleNamespace(
            tree=SimpleNamespace(add_command=Mock()),
            add_cog=AsyncMock(),
        )
        with patch.dict(os.environ, {"TEST_MODE": "1"}, clear=True):
            await event_module.setup(fake_bot)

        fake_bot.tree.add_command.assert_not_called()
        fake_bot.add_cog.assert_not_awaited()

    async def test_mysterious_display_instance_registers_controls_and_tracking(self):
        fake_bot = SimpleNamespace(
            tree=SimpleNamespace(add_command=Mock()),
            add_cog=AsyncMock(),
        )
        fake_cog = SimpleNamespace(refresh_loop=SimpleNamespace(start=Mock()))
        with patch.dict(os.environ, {"EVENT_DISPLAY": "1"}, clear=True), patch.object(
            event_module,
            "BRAND_NAME",
            EVENT_CONTROL_BRAND_NAME,
        ), patch.object(event_module, "EventLeaderboardCog", return_value=fake_cog):
            self.assertTrue(event_control_enabled())
            await event_module.setup(fake_bot)

        fake_bot.tree.add_command.assert_has_calls([
            call(event_group),
            call(endtimestamp_command),
        ])
        fake_bot.add_cog.assert_awaited_once_with(fake_cog)
        fake_cog.refresh_loop.start.assert_called_once()

    async def test_other_display_brand_tracks_but_does_not_register_controls(self):
        fake_bot = SimpleNamespace(
            tree=SimpleNamespace(add_command=Mock()),
            add_cog=AsyncMock(),
        )
        fake_cog = SimpleNamespace(refresh_loop=SimpleNamespace(start=Mock()))
        with patch.dict(os.environ, {"EVENT_DISPLAY": "1"}, clear=True), patch.object(
            event_module,
            "BRAND_NAME",
            "Takopi Helper",
        ), patch.object(event_module, "EventLeaderboardCog", return_value=fake_cog):
            self.assertFalse(event_control_enabled())
            await event_module.setup(fake_bot)

        fake_bot.tree.add_command.assert_not_called()
        fake_bot.add_cog.assert_awaited_once_with(fake_cog)

    async def test_endtimestamp_command_returns_every_format(self):
        interaction = SimpleNamespace(
            guild=SimpleNamespace(icon=None),
            client=SimpleNamespace(get_cog=Mock(return_value=None)),
            response=SimpleNamespace(send_message=AsyncMock()),
        )
        cfg = {
            "active": True,
            "goal_hours": 2,
            "baseline_seconds": 3600,
            "baseline_token": 2,
        }
        runtime = {
            "vc_active_seconds": 0,
            "applied_baseline_token": 2,
        }
        with patch.object(event_module, "load_config", return_value=cfg), patch.object(
            event_module,
            "load_runtime",
            return_value=runtime,
        ), patch.object(event_module.time, "time", return_value=1_700_000_000), patch.object(
            event_module,
            "make_embed",
            side_effect=lambda title, description=None, **kwargs: discord.Embed(
                title=title,
                description=description,
            ),
        ):
            await endtimestamp_command.callback(interaction)

        sent = interaction.response.send_message.await_args.kwargs
        self.assertTrue(sent["ephemeral"])
        self.assertEqual(sent["embed"].title, "Projected Event End")
        self.assertIn("`1700003600`", sent["embed"].description)
        for style in ("t", "T", "d", "D", "f", "F", "R"):
            self.assertIn(f"<t:1700003600:{style}>", sent["embed"].description)


if __name__ == "__main__":
    unittest.main()
