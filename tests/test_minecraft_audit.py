import asyncio
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock

from minecraft_bot.audit import (
    COMMAND_RISK,
    OUTCOME_DENIED,
    OUTCOME_FAILED,
    OUTCOME_SUCCESS,
    REDACTED,
    RISK_DESTRUCTIVE,
    RISK_READ_ONLY,
    CommandAuditRecord,
    build_command_log_embed,
    build_important_embed,
    build_record,
    command_name_for,
    component_label,
    component_risk,
    deliver,
    flatten_options,
    format_record,
    humanize_ui_name,
    redact_options,
    resolve_target_id,
    risk_for,
    schedule_delivery,
)
from minecraft_bot.data import MinecraftDataManager
from minecraft_bot.models import Edition
from minecraft_bot.settings import SETTING_KEYS, MinecraftSettings


def make_interaction(data, *, user_id=7, channel_id=99, guild_id=1):
    return SimpleNamespace(
        data=data,
        user=SimpleNamespace(id=user_id, name="mod", display_name="Mod", global_name=None),
        channel_id=channel_id,
        guild=SimpleNamespace(id=guild_id),
        command=None,
        id=0xABC,
    )


def make_record(**overrides):
    base = dict(source="command", command="minecraft status", user_id=7, user_label="Mod")
    base.update(overrides)
    return CommandAuditRecord(**base)


class RiskTests(unittest.TestCase):
    def test_access_changing_commands_are_destructive(self):
        for name in ("minecraft revoke", "minecraft unlink", "minecraft cancel"):
            with self.subTest(command=name):
                self.assertEqual(risk_for(name), RISK_DESTRUCTIVE)

    def test_lookups_stay_read_only(self):
        for name in ("minecraft status", "minecraft lookup", "minecraft panel", "minecraft stats"):
            with self.subTest(command=name):
                self.assertEqual(risk_for(name), RISK_READ_ONLY)

    def test_risk_lookup_is_case_insensitive(self):
        self.assertEqual(risk_for("Minecraft Revoke"), RISK_DESTRUCTIVE)

    def test_unknown_commands_default_to_read_only(self):
        self.assertEqual(risk_for("minecraft brand-new-thing"), RISK_READ_ONLY)

    def test_every_declared_risk_is_a_known_value(self):
        valid = {"read_only", "configuration", "moderate", "destructive"}
        self.assertTrue(set(COMMAND_RISK.values()) <= valid)


class RedactionTests(unittest.TestCase):
    def test_secret_looking_option_names_are_never_logged(self):
        options = [("token", "abc"), ("api_secret", "s"), ("webhook_url", "x"), ("reason", "spam")]

        self.assertEqual(
            redact_options(options),
            (("token", REDACTED), ("api_secret", REDACTED), ("webhook_url", REDACTED), ("reason", "spam")),
        )

    def test_long_values_are_truncated(self):
        (_, value), = redact_options([("reason", "x" * 500)])

        self.assertEqual(len(value), 200)
        self.assertTrue(value.endswith("..."))

    def test_empty_values_render_as_a_dash(self):
        self.assertEqual(redact_options([("reason", "")]), (("reason", "—"),))


class OptionExtractionTests(unittest.TestCase):
    def test_the_minecraft_subcommand_wrapper_is_unwrapped(self):
        data = {
            "name": "minecraft",
            "options": [{
                "name": "revoke",
                "type": 1,
                "options": [
                    {"name": "user", "type": 6, "value": "555"},
                    {"name": "reason", "type": 3, "value": "Griefing"},
                ],
            }],
        }

        self.assertEqual(
            flatten_options(data),
            [("user", "555"), ("reason", "Griefing")],
        )

    def test_malformed_payloads_yield_no_options(self):
        self.assertEqual(flatten_options(None), [])
        self.assertEqual(flatten_options({}), [])
        self.assertEqual(flatten_options({"options": "nope"}), [])

    def test_the_qualified_command_name_is_reconstructed_from_the_payload(self):
        interaction = make_interaction({
            "name": "minecraft",
            "options": [{"name": "revoke", "type": 1, "options": []}],
        })

        self.assertEqual(command_name_for(interaction), "minecraft revoke")

    def test_a_resolved_command_object_wins_over_the_payload(self):
        interaction = make_interaction({"name": "minecraft"})
        interaction.command = SimpleNamespace(qualified_name="minecraft unlink")

        self.assertEqual(command_name_for(interaction), "minecraft unlink")

    def test_an_unrecognisable_payload_falls_back_to_unknown(self):
        self.assertEqual(command_name_for(make_interaction({})), "unknown")


class TargetResolutionTests(unittest.TestCase):
    def test_a_resolved_user_identifies_the_target(self):
        interaction = make_interaction({
            "name": "minecraft",
            "resolved": {"users": {"555": {"username": "griefer"}}},
        })

        self.assertEqual(resolve_target_id(interaction, []), 555)

    def test_a_user_option_is_used_when_nothing_was_resolved(self):
        interaction = make_interaction({"name": "minecraft"})

        self.assertEqual(resolve_target_id(interaction, [("user", "555")]), 555)

    def test_no_target_is_reported_when_none_is_present(self):
        self.assertIsNone(resolve_target_id(make_interaction({}), [("reason", "x")]))


class RecordBuildingTests(unittest.TestCase):
    def test_a_revoke_captures_actor_target_and_arguments(self):
        interaction = make_interaction({
            "name": "minecraft",
            "options": [{
                "name": "revoke",
                "type": 1,
                "options": [{"name": "reason", "type": 3, "value": "Griefing"}],
            }],
            "resolved": {"users": {"555": {"username": "griefer"}}},
        })

        record = build_record(interaction)

        self.assertEqual(record.command, "minecraft revoke")
        self.assertEqual(record.risk, RISK_DESTRUCTIVE)
        self.assertEqual(record.user_id, 7)
        self.assertEqual(record.target_id, 555)
        self.assertEqual(record.options, (("reason", "Griefing"),))
        self.assertEqual(record.channel_id, 99)
        self.assertEqual(record.guild_id, 1)

    def test_component_records_do_not_capture_command_options(self):
        interaction = make_interaction({"custom_id": "x", "values": ["a"]})

        record = build_record(interaction, source="component", command="Review → Approve")

        self.assertEqual(record.options, ())
        self.assertEqual(record.command, "Review → Approve")

    def test_an_explicit_risk_overrides_the_lookup(self):
        record = build_record(make_interaction({}), command="Review → Deny", risk=RISK_DESTRUCTIVE)

        self.assertEqual(record.risk, RISK_DESTRUCTIVE)


class ImportanceTests(unittest.TestCase):
    def test_routine_commands_are_not_important(self):
        self.assertFalse(make_record().important)

    def test_destructive_commands_are_important(self):
        self.assertTrue(make_record(risk=RISK_DESTRUCTIVE).important)

    def test_a_failure_is_important_even_for_a_read_only_command(self):
        self.assertTrue(make_record(outcome=OUTCOME_FAILED).important)

    def test_a_denied_attempt_is_important(self):
        record = make_record(outcome=OUTCOME_DENIED)

        self.assertTrue(record.important)
        self.assertTrue(record.failed)


class ComponentLabellingTests(unittest.TestCase):
    def test_class_names_become_readable_labels(self):
        self.assertEqual(humanize_ui_name("ApproveApplicationButton"), "Approve Application")
        self.assertEqual(humanize_ui_name("ReviewView"), "Review")

    def test_a_button_label_is_preferred_over_its_class_name(self):
        view = type("ReviewView", (), {})()
        item = SimpleNamespace(label="Approve")

        self.assertEqual(component_label(view, item), "Review → Approve")

    def test_a_class_name_is_used_when_the_control_has_no_label(self):
        view = type("ReviewView", (), {})()
        item = type("DenyButton", (), {})()

        self.assertEqual(component_label(view, item), "Review → Deny")

    def test_access_changing_controls_are_tiered_as_destructive(self):
        for label in ("Review → Approve", "Review → Deny", "Panel → Revoke", "X → Unlink"):
            with self.subTest(label=label):
                self.assertEqual(component_risk(label), RISK_DESTRUCTIVE)

    def test_ordinary_controls_stay_read_only(self):
        self.assertEqual(component_risk("Setup → Application log"), RISK_READ_ONLY)


class RenderingTests(unittest.TestCase):
    def test_a_line_names_the_actor_target_and_channel(self):
        line = format_record(make_record(command="minecraft revoke", target_id=555, channel_id=42))

        self.assertIn("<@7>", line)
        self.assertIn("<@555>", line)
        self.assertIn("<#42>", line)
        self.assertIn("minecraft revoke", line)

    def test_a_failure_surfaces_its_reference(self):
        line = format_record(make_record(
            outcome=OUTCOME_FAILED, detail="RuntimeError", correlation_id="mc-abc"
        ))

        self.assertIn("FAILED", line)
        self.assertIn("mc-abc", line)

    def test_the_important_embed_names_the_actor_and_target(self):
        embed = build_important_embed(make_record(
            command="minecraft revoke", risk=RISK_DESTRUCTIVE, target_id=555, channel_id=42
        ))
        rendered = {field.name: field.value for field in embed.fields}

        self.assertIn("<@7>", rendered["Actor"])
        self.assertIn("<@555>", rendered["Target"])
        self.assertIn("<#42>", rendered["Channel"])
        self.assertEqual(rendered["Category"], "Destructive")

    def test_an_empty_command_log_explains_itself(self):
        embed = build_command_log_embed([], total=0)

        self.assertIn("No command invocations", embed.description)

    def test_command_log_rows_render_with_actor_and_outcome(self):
        rows = [{
            "command": "minecraft revoke",
            "actor_discord_id": "7",
            "target_discord_id": "555",
            "outcome": OUTCOME_SUCCESS,
            "created_at": 1_700_000_000,
        }]

        embed = build_command_log_embed(rows, total=1)

        self.assertIn("minecraft revoke", embed.description)
        self.assertIn("<@7>", embed.description)
        self.assertIn("<@555>", embed.description)


class DeliveryRoutingTests(unittest.TestCase):
    def _client(self, *, command_channel=0, important_channel=0):
        return SimpleNamespace(
            settings=SimpleNamespace(
                command_log_channel_id=command_channel,
                critical_log_channel_id=important_channel,
            ),
            data=SimpleNamespace(record_command_log=AsyncMock(return_value=1)),
            _send_configured_log=AsyncMock(),
        )

    def test_a_routine_command_only_reaches_the_command_log(self):
        client = self._client(command_channel=10, important_channel=20)

        asyncio.run(deliver(client, make_record()))

        self.assertEqual([call.args[0] for call in client._send_configured_log.await_args_list], [10])

    def test_a_destructive_command_only_reaches_the_important_channel(self):
        client = self._client(command_channel=10, important_channel=20)

        asyncio.run(deliver(client, make_record(command="minecraft revoke", risk=RISK_DESTRUCTIVE)))

        self.assertEqual([call.args[0] for call in client._send_configured_log.await_args_list], [20])

    def test_important_records_fall_back_to_the_command_log(self):
        client = self._client(command_channel=10, important_channel=0)

        asyncio.run(deliver(client, make_record(risk=RISK_DESTRUCTIVE)))

        # One send, not two: the fallback must not duplicate into the same channel.
        self.assertEqual([call.args[0] for call in client._send_configured_log.await_args_list], [10])

    def test_nothing_is_sent_when_no_channel_is_configured(self):
        client = self._client()

        asyncio.run(deliver(client, make_record(risk=RISK_DESTRUCTIVE)))

        self.assertEqual(client._send_configured_log.await_count, 0)

    def test_records_are_persisted_even_with_no_channel_configured(self):
        client = self._client()

        asyncio.run(deliver(client, make_record()))

        self.assertEqual(client.data.record_command_log.await_count, 1)

    def test_live_logs_use_a_linked_players_minecraft_skin(self):
        client = self._client(command_channel=10)
        client.data.list_accounts_for_user = AsyncMock(return_value=[{
            "minecraft_uuid": "123e4567-e89b-12d3-a456-426614174000",
        }])

        asyncio.run(deliver(client, make_record()))

        embed = client._send_configured_log.await_args.args[1]
        self.assertIn("123e4567-e89b-12d3-a456-426614174000", embed.thumbnail.url)

    def test_a_channel_failure_never_propagates_into_the_command_path(self):
        client = self._client(command_channel=10)
        client._send_configured_log = AsyncMock(side_effect=RuntimeError("channel exploded"))

        # Must not raise: auditing can never take a command down with it.
        asyncio.run(deliver(client, make_record()))

    def test_a_persistence_failure_never_propagates_either(self):
        client = self._client(command_channel=10)
        client.data.record_command_log = AsyncMock(side_effect=RuntimeError("db down"))

        asyncio.run(deliver(client, make_record()))

        self.assertEqual(client._send_configured_log.await_count, 1)


class ScheduledDeliveryTests(unittest.IsolatedAsyncioTestCase):
    async def test_scheduled_audit_does_not_wait_for_channel_delivery(self):
        release = asyncio.Event()

        async def slow_log(_channel_id, _embed):
            await release.wait()

        client = SimpleNamespace(
            settings=SimpleNamespace(command_log_channel_id=10, critical_log_channel_id=0),
            data=SimpleNamespace(record_command_log=AsyncMock(return_value=1)),
            _send_configured_log=slow_log,
        )

        schedule_delivery(client, make_record())
        await asyncio.sleep(0)

        self.assertFalse(release.is_set())
        release.set()
        await asyncio.sleep(0)


class SettingsTests(unittest.TestCase):
    def test_the_new_channels_are_persisted_settings(self):
        self.assertIn("command_log_channel_id", SETTING_KEYS)
        self.assertIn("critical_log_channel_id", SETTING_KEYS)
        self.assertIn("chat_channel_id", SETTING_KEYS)

    def test_they_default_to_disabled(self):
        settings = MinecraftSettings()

        self.assertEqual(settings.command_log_channel_id, 0)
        self.assertEqual(settings.critical_log_channel_id, 0)
        self.assertEqual(settings.chat_channel_id, 0)

    def test_they_can_be_set_and_cleared(self):
        settings = MinecraftSettings().with_updates(command_log_channel_id=123)
        self.assertEqual(settings.command_log_channel_id, 123)

        cleared = settings.with_updates(command_log_channel_id=0)
        self.assertEqual(cleared.command_log_channel_id, 0)

    def test_they_round_trip_through_stored_values(self):
        stored = {"command_log_channel_id": 5, "critical_log_channel_id": 6}

        settings = MinecraftSettings.from_sources(SimpleNamespace(), stored)

        self.assertEqual(settings.command_log_channel_id, 5)
        self.assertEqual(settings.critical_log_channel_id, 6)
        self.assertEqual(settings.persistent_values()["critical_log_channel_id"], 6)


class CommandLogStorageTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self._tempdir = tempfile.TemporaryDirectory()
        self.manager = MinecraftDataManager(Path(self._tempdir.name) / "minecraft.db")
        await self.manager.open()

    async def asyncTearDown(self):
        await self.manager.close()
        self._tempdir.cleanup()

    async def test_records_round_trip_newest_first(self):
        await self.manager.record_command_log(make_record(command="minecraft status"))
        await self.manager.record_command_log(make_record(command="minecraft revoke", risk=RISK_DESTRUCTIVE))

        rows = await self.manager.list_command_log()

        self.assertEqual([row["command"] for row in rows], ["minecraft revoke", "minecraft status"])
        self.assertEqual(await self.manager.count_command_log(), 2)

    async def test_results_filter_by_actor_and_command(self):
        await self.manager.record_command_log(make_record(command="minecraft revoke", user_id=7))
        await self.manager.record_command_log(make_record(command="minecraft status", user_id=8))

        self.assertEqual(len(await self.manager.list_command_log(actor_id=7)), 1)
        self.assertEqual(await self.manager.count_command_log(actor_id=8), 1)
        self.assertEqual(len(await self.manager.list_command_log(command="revoke")), 1)

    async def test_options_are_stored_so_the_trail_survives_a_purged_channel(self):
        import json

        await self.manager.record_command_log(make_record(options=(("reason", "Griefing"),)))

        rows = await self.manager.list_command_log()
        self.assertEqual(json.loads(rows[0]["options"]), [["reason", "Griefing"]])

    async def test_the_target_and_outcome_are_preserved(self):
        await self.manager.record_command_log(make_record(
            target_id=555, outcome=OUTCOME_DENIED, detail="Not a moderator"
        ))

        row = (await self.manager.list_command_log())[0]

        self.assertEqual(row["target_discord_id"], "555")
        self.assertEqual(row["outcome"], OUTCOME_DENIED)
        self.assertEqual(row["detail"], "Not a moderator")

    async def test_old_rows_are_pruned_by_the_retention_sweep(self):
        import time

        await self.manager.record_command_log(make_record())
        # 31 days on, the row is past the 30-day window.
        deleted = await self.manager.prune_command_log(now=int(time.time()) + 31 * 86400)

        self.assertEqual(deleted, 1)
        self.assertEqual(await self.manager.count_command_log(), 0)

    async def test_recent_rows_survive_the_sweep(self):
        await self.manager.record_command_log(make_record())

        await self.manager.prune_command_log()

        self.assertEqual(await self.manager.count_command_log(), 1)


class UsernameLookupTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self._tempdir = tempfile.TemporaryDirectory()
        self.manager = MinecraftDataManager(Path(self._tempdir.name) / "minecraft.db")
        await self.manager.open()

    async def asyncTearDown(self):
        await self.manager.close()
        self._tempdir.cleanup()

    async def _create(self, username, user_id="1"):
        return await self.manager.create_application(
            guild_id="1",
            discord_user_id=user_id,
            edition=Edition.JAVA,
            claimed_username=username,
            answers={"why": "Build things here", "about": "Helpful player here"},
        )

    async def test_an_exact_username_is_found(self):
        await self._create("PlayerOne")

        matches = await self.manager.find_applications_by_username("PlayerOne")

        self.assertEqual([record.claimed_username for record in matches], ["PlayerOne"])

    async def test_lookup_is_case_insensitive(self):
        await self._create("PlayerOne")

        self.assertEqual(len(await self.manager.find_applications_by_username("playerone")), 1)

    async def test_a_partial_username_matches(self):
        await self._create("PlayerOne")

        self.assertEqual(len(await self.manager.find_applications_by_username("layer")), 1)

    async def test_an_unknown_username_returns_nothing(self):
        await self._create("PlayerOne")

        self.assertEqual(await self.manager.find_applications_by_username("Nobody"), [])

    async def test_a_blank_query_returns_nothing_rather_than_everything(self):
        await self._create("PlayerOne")

        self.assertEqual(await self.manager.find_applications_by_username("   "), [])

    async def test_linked_account_username_search_is_case_insensitive(self):
        application = await self._create("RenamedPlayer")
        await self.manager.record_verification(
            application_id=application.id,
            edition=Edition.JAVA,
            minecraft_uuid="123e4567-e89b-12d3-a456-426614174000",
            current_username="RenamedPlayer",
            xuid=None,
            event_idempotency_key="verify-account-search",
        )

        matches = await self.manager.find_accounts_by_username("renamed")

        self.assertEqual(matches[0]["current_username"], "RenamedPlayer")


if __name__ == "__main__":
    unittest.main()
