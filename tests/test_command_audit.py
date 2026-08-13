import asyncio
import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

from core import command_audit, context
from core.context import set_bot
from core.actions import RiskLevel, get_action_spec
from core.command_audit import (
    OUTCOME_FAILED,
    OUTCOME_SUCCESS,
    REDACTED,
    RISK_DESTRUCTIVE,
    RISK_READ_ONLY,
    TIER_COMMAND,
    TIER_CRITICAL,
    CommandAuditRecord,
    build_prefix_record,
    build_record,
    flatten_interaction_options,
    get_settings,
    redact_options,
    route_for,
    should_record,
)
from core.services import validate_command_log_placement
from minecraft_bot.audit import COMMAND_RISK, risk_for


class EmbedRuntimeMixin:
    """Embed builders read the branding theme off the active bot runtime.

    The runtime is a module global, so the previous value is restored rather than
    cleared — other test modules rely on whatever was installed before.
    """

    def setUp(self):
        super().setUp()
        self._previous_bot = context._active_bot
        set_bot(SimpleNamespace(data_manager=SimpleNamespace(config={})))

    def tearDown(self):
        set_bot(self._previous_bot)
        super().tearDown()


def make_interaction(data, *, user_id=7, channel_id=99, guild_id=1):
    return SimpleNamespace(
        data=data,
        user=SimpleNamespace(id=user_id, name="mod", display_name="Mod", global_name=None),
        channel_id=channel_id,
        guild=SimpleNamespace(id=guild_id),
        command=None,
    )


class RiskConstantTests(unittest.TestCase):
    def test_plain_risk_strings_match_the_action_registry_enum(self):
        # command_audit restates these so it stays importable without core.actions.
        self.assertEqual(command_audit.RISK_READ_ONLY, RiskLevel.READ_ONLY.value)
        self.assertEqual(command_audit.RISK_CONFIGURATION, RiskLevel.CONFIGURATION.value)
        self.assertEqual(command_audit.RISK_MODERATE, RiskLevel.MODERATE.value)
        self.assertEqual(command_audit.RISK_DESTRUCTIVE, RiskLevel.DESTRUCTIVE.value)


class RedactionTests(unittest.TestCase):
    def test_secret_looking_option_names_never_reach_a_log(self):
        options = [("token", "abc123"), ("api_secret", "s"), ("webhook_url", "https://x"), ("reason", "spam")]

        self.assertEqual(
            redact_options(options),
            (("token", REDACTED), ("api_secret", REDACTED), ("webhook_url", REDACTED), ("reason", "spam")),
        )

    def test_long_values_are_truncated_to_a_loggable_length(self):
        (_, value), = redact_options([("reason", "x" * 500)])

        self.assertEqual(len(value), command_audit.OPTION_VALUE_LIMIT)
        self.assertTrue(value.endswith("..."))

    def test_redaction_can_be_disabled_for_operators_who_want_raw_values(self):
        self.assertEqual(redact_options([("token", "abc")], redact=False), (("token", "abc"),))


class OptionExtractionTests(unittest.TestCase):
    def test_subcommand_wrappers_are_unwrapped_to_their_leaf_options(self):
        data = {
            "name": "event",
            "options": [{
                "name": "goal",
                "type": 1,
                "options": [{"name": "hours", "type": 4, "value": 12}],
            }],
        }

        self.assertEqual(flatten_interaction_options(data), [("hours", 12)])

    def test_subcommand_groups_nest_two_levels_deep(self):
        data = {
            "name": "branding",
            "options": [{
                "name": "server",
                "type": 2,
                "options": [{
                    "name": "edit",
                    "type": 1,
                    "options": [{"name": "name", "type": 3, "value": "MBX"}],
                }],
            }],
        }

        self.assertEqual(flatten_interaction_options(data), [("name", "MBX")])

    def test_missing_or_malformed_payloads_yield_no_options(self):
        self.assertEqual(flatten_interaction_options(None), [])
        self.assertEqual(flatten_interaction_options({}), [])
        self.assertEqual(flatten_interaction_options({"options": "nope"}), [])


class RecordBuildingTests(unittest.TestCase):
    def test_resolved_users_identify_the_target_of_an_action(self):
        interaction = make_interaction({
            "name": "purge",
            "options": [{"name": "amount", "type": 4, "value": 5}],
            "resolved": {"users": {"555": {"username": "offender"}}},
        })

        record = build_record(interaction, command="purge")

        self.assertEqual(record.target_id, 555)
        self.assertEqual(record.target_label, "offender")
        self.assertEqual(record.options, (("amount", "5"),))
        self.assertEqual(record.user_id, 7)
        self.assertEqual(record.channel_id, 99)
        self.assertEqual(record.guild_id, 1)

    def test_a_raw_user_id_option_is_used_when_nothing_was_resolved(self):
        interaction = make_interaction({
            "name": "punish",
            "options": [{"name": "userid", "type": 3, "value": "123456789012345678"}],
        })

        record = build_record(interaction, command="punish")

        self.assertEqual(record.target_id, 123456789012345678)

    def test_risk_is_taken_from_the_action_registry(self):
        interaction = make_interaction({"name": "purge"})

        record = build_record(interaction, command="purge", spec=get_action_spec("purge"))

        self.assertEqual(record.risk, RISK_DESTRUCTIVE)

    def test_an_unregistered_command_falls_back_to_read_only(self):
        record = build_record(make_interaction({"name": "nope"}), command="nope")

        self.assertEqual(record.risk, RISK_READ_ONLY)

    def test_prefix_records_capture_the_context_author_and_channel(self):
        ctx = SimpleNamespace(
            author=SimpleNamespace(id=11, name="owner", display_name="Owner", global_name=None),
            channel=SimpleNamespace(id=22),
            guild=SimpleNamespace(id=33),
        )

        record = build_prefix_record(ctx, command="sync", options=(("synced", 12),))

        self.assertEqual(record.source, command_audit.SOURCE_PREFIX)
        self.assertEqual((record.user_id, record.channel_id, record.guild_id), (11, 22, 33))
        self.assertEqual(record.options, (("synced", "12"),))


class RoutingTests(unittest.TestCase):
    def _record(self, **overrides):
        base = dict(source="slash", command="help", user_id=1, user_label="Mod")
        base.update(overrides)
        return CommandAuditRecord(**base)

    def test_routine_commands_only_reach_the_command_log(self):
        self.assertEqual(route_for(self._record()), [TIER_COMMAND])

    def test_destructive_commands_are_mirrored_to_the_critical_log(self):
        record = self._record(command="purge", risk=RISK_DESTRUCTIVE)

        self.assertEqual(route_for(record), [TIER_COMMAND, TIER_CRITICAL])

    def test_a_failure_is_important_even_for_a_read_only_command(self):
        record = self._record(outcome=OUTCOME_FAILED)

        self.assertIn(TIER_CRITICAL, route_for(record))

    def test_minecraft_access_commands_are_critical_without_an_action_spec(self):
        for command in ("minecraft revoke", "minecraft unlink", "minecraft cancel"):
            with self.subTest(command=command):
                self.assertIn(TIER_CRITICAL, route_for(self._record(command=command)))

    def test_operators_can_promote_extra_commands_to_the_critical_log(self):
        config = {"command_log_settings": {"critical_commands": ["Derole"]}}

        self.assertIn(TIER_CRITICAL, route_for(self._record(command="derole"), config))


class RecordFilterTests(unittest.TestCase):
    def _record(self, **overrides):
        base = dict(source="slash", command="help", user_id=1, user_label="Mod")
        base.update(overrides)
        return CommandAuditRecord(**base)

    def test_auditing_can_be_switched_off_entirely(self):
        self.assertFalse(should_record(self._record(), {"command_log_settings": {"enabled": False}}))

    def test_panel_clicks_can_be_excluded_without_losing_slash_commands(self):
        config = {"command_log_settings": {"log_components": False}}

        self.assertFalse(should_record(self._record(source=command_audit.SOURCE_COMPONENT), config))
        self.assertFalse(should_record(self._record(source=command_audit.SOURCE_MODAL), config))
        self.assertTrue(should_record(self._record(), config))

    def test_read_only_commands_can_be_excluded_but_their_failures_are_kept(self):
        config = {"command_log_settings": {"log_read_only": False}}

        self.assertFalse(should_record(self._record(), config))
        self.assertTrue(should_record(self._record(outcome=OUTCOME_FAILED), config))

    def test_defaults_log_everything(self):
        self.assertEqual(get_settings(None), command_audit.DEFAULT_SETTINGS)
        self.assertTrue(should_record(self._record(), {}))


class SinkTests(unittest.TestCase):
    def tearDown(self):
        command_audit.set_sink(None)

    def test_emitting_without_a_sink_is_a_no_op(self):
        asyncio.run(command_audit.emit(CommandAuditRecord("slash", "help", 1, "Mod")))

    def test_a_failing_sink_never_propagates_into_the_command_path(self):
        async def broken(_record):
            raise RuntimeError("channel exploded")

        command_audit.set_sink(broken)
        # Must not raise: auditing can never take a command down with it.
        asyncio.run(command_audit.emit(CommandAuditRecord("slash", "help", 1, "Mod")))

    def test_records_reach_a_registered_sink(self):
        seen = []

        async def sink(record):
            seen.append(record)

        command_audit.set_sink(sink)
        asyncio.run(command_audit.emit(CommandAuditRecord("slash", "help", 1, "Mod")))

        self.assertEqual([record.command for record in seen], ["help"])


class StaffOnlyPlacementTests(unittest.TestCase):
    def _guild(self, *, everyone_can_read=False, channel_id=500):
        default_role = object()
        channel = SimpleNamespace(
            id=channel_id,
            overwrites_for=lambda _role: SimpleNamespace(read_messages=None if everyone_can_read else False),
        )
        return SimpleNamespace(
            default_role=default_role,
            get_channel=lambda cid: channel if cid == channel_id else None,
        )

    def test_pointing_the_command_log_at_a_member_facing_channel_is_an_error(self):
        config = {"command_log_channel_id": 200, "modmail_panel_channel": 200}

        findings = validate_command_log_placement(config, self._guild())

        self.assertEqual([finding.level for finding in findings], ["error"])
        self.assertIn("staff-only", findings[0].message)

    def test_the_appeal_channel_is_also_rejected(self):
        config = {"critical_log_channel_id": 300, "appeal_channel_id": 300}

        findings = validate_command_log_placement(config, self._guild())

        self.assertEqual([finding.level for finding in findings], ["error"])

    def test_a_publicly_readable_command_log_is_a_warning(self):
        config = {"command_log_channel_id": 500}

        findings = validate_command_log_placement(config, self._guild(everyone_can_read=True))

        self.assertEqual([finding.level for finding in findings], ["warning"])

    def test_a_staff_only_channel_produces_no_findings(self):
        config = {"command_log_channel_id": 500}

        self.assertEqual(validate_command_log_placement(config, self._guild()), [])

    def test_unset_channels_are_not_flagged(self):
        self.assertEqual(validate_command_log_placement({}, self._guild()), [])
        self.assertEqual(validate_command_log_placement({"command_log_channel_id": 0}, self._guild()), [])


class MinecraftRiskTests(unittest.TestCase):
    def test_access_changing_commands_are_destructive(self):
        for command in ("minecraft revoke", "minecraft unlink", "minecraft cancel"):
            with self.subTest(command=command):
                self.assertEqual(risk_for(command), RISK_DESTRUCTIVE)

    def test_lookups_stay_read_only(self):
        self.assertEqual(risk_for("minecraft lookup"), RISK_READ_ONLY)
        self.assertEqual(risk_for("minecraft status"), RISK_READ_ONLY)

    def test_risk_lookup_is_case_insensitive(self):
        self.assertEqual(risk_for("Minecraft Revoke"), RISK_DESTRUCTIVE)

    def test_every_declared_risk_is_a_known_value(self):
        valid = {
            command_audit.RISK_READ_ONLY,
            command_audit.RISK_CONFIGURATION,
            command_audit.RISK_MODERATE,
            command_audit.RISK_DESTRUCTIVE,
        }
        self.assertTrue(set(COMMAND_RISK.values()) <= valid)


class CommandLogDeliveryTests(EmbedRuntimeMixin, unittest.TestCase):
    def setUp(self):
        super().setUp()
        import cogs.command_log as command_log

        self.command_log = command_log

    def _record(self, **overrides):
        base = dict(source="slash", command="purge", user_id=1, user_label="Mod")
        base.update(overrides)
        return CommandAuditRecord(**base)

    def test_the_critical_log_falls_back_to_the_command_log_when_unset(self):
        config = {"command_log_channel_id": 10, "critical_log_channel_id": 0}

        self.assertEqual(self.command_log.get_critical_log_channel_ids(config), [10])

    def test_a_configured_critical_channel_is_preferred(self):
        config = {"command_log_channel_id": 10, "critical_log_channel_id": 20}

        self.assertEqual(self.command_log.get_critical_log_channel_ids(config), [20])

    def test_unset_channels_resolve_to_nothing(self):
        self.assertEqual(self.command_log.get_command_log_channel_ids({}), [])
        self.assertEqual(self.command_log.get_command_log_channel_ids({"command_log_channel_id": "oops"}), [])

    def test_a_batch_is_rendered_as_one_embed(self):
        records = [self._record(command=f"cmd{index}") for index in range(3)]

        embed = self.command_log.build_batch_embed(records, guild=None)

        self.assertIn("Command Log (3)", embed.title)
        for index in range(3):
            self.assertIn(f"cmd{index}", embed.description)

    def test_a_failed_record_surfaces_its_reference_for_cross_checking_logs(self):
        record = self._record(outcome=OUTCOME_FAILED, detail="InternalFailure", correlation_id="deadbeef")

        line = self.command_log.format_record_line(record)

        self.assertIn("FAILED", line)
        self.assertIn("deadbeef", line)

    def test_the_critical_embed_names_the_staff_member_and_target(self):
        record = self._record(target_id=555, risk=RISK_DESTRUCTIVE, channel_id=42)

        embed = self.command_log.build_critical_embed(record, guild=None)
        rendered = {field.name: field.value for field in embed.fields}

        self.assertIn("<@1>", rendered["Staff"])
        self.assertIn("<@555>", rendered["Target"])
        self.assertIn("<#42>", rendered["Channel"])

    def test_buffered_records_are_flushed_and_persisted_together(self):
        cog = self.command_log.CommandLogCog()
        data_manager = SimpleNamespace(
            config={"command_log_channel_id": 10, "guild_id": 5},
            record_command_audit=AsyncMock(return_value=2),
        )
        guild = SimpleNamespace(id=5, icon=None)

        async def runner():
            with patch.object(self.command_log, "bot", SimpleNamespace(
                data_manager=data_manager, get_guild=lambda _id: guild, guilds=[guild], metrics=None
            )), patch.object(self.command_log, "_send_log_to_channels", AsyncMock(return_value=True)) as send:
                await cog.handle_record(self._record(command="help", risk=RISK_READ_ONLY))
                await cog.handle_record(self._record(command="history", risk=RISK_READ_ONLY))
                await cog._flush()
                return send

        send = asyncio.run(runner())

        self.assertEqual(data_manager.record_command_audit.await_count, 1)
        self.assertEqual(len(data_manager.record_command_audit.await_args.args[0]), 2)
        # Two records, one message.
        self.assertEqual(send.await_count, 1)

    def test_a_destructive_record_is_sent_immediately_without_waiting_for_a_flush(self):
        cog = self.command_log.CommandLogCog()
        data_manager = SimpleNamespace(
            config={"command_log_channel_id": 10, "critical_log_channel_id": 20, "guild_id": 5},
            record_command_audit=AsyncMock(return_value=1),
        )
        guild = SimpleNamespace(id=5, icon=None)

        async def runner():
            with patch.object(self.command_log, "bot", SimpleNamespace(
                data_manager=data_manager, get_guild=lambda _id: guild, guilds=[guild], metrics=None
            )), patch.object(self.command_log, "_send_log_to_channels", AsyncMock(return_value=True)) as send:
                await cog.handle_record(self._record(risk=RISK_DESTRUCTIVE))
                return send

        send = asyncio.run(runner())

        self.assertEqual(send.await_count, 1)
        self.assertEqual(send.await_args.args[1], [20])
        self.assertEqual(send.await_args.kwargs["log_label"], "critical command log")

    def test_flushing_an_empty_buffer_does_nothing(self):
        cog = self.command_log.CommandLogCog()

        async def runner():
            with patch.object(self.command_log, "_send_log_to_channels", AsyncMock()) as send:
                await cog._flush()
                return send

        self.assertEqual(asyncio.run(runner()).await_count, 0)


class ErrorCorrelationTests(unittest.TestCase):
    def test_repeated_classification_keeps_one_correlation_id(self):
        from core.errors import classify_operation_error

        error = RuntimeError("boom")

        first = classify_operation_error(error)
        second = classify_operation_error(error)

        self.assertIs(first, second)
        self.assertEqual(first.correlation_id, second.correlation_id)

    def test_a_wrapped_invoke_error_shares_the_id_with_its_original(self):
        from discord import app_commands
        from core.errors import classify_operation_error

        original = RuntimeError("boom")
        wrapper = app_commands.CommandInvokeError(SimpleNamespace(name="purge"), original)

        self.assertEqual(
            classify_operation_error(wrapper).correlation_id,
            classify_operation_error(original).correlation_id,
        )


class AuditLogEmbedTests(EmbedRuntimeMixin, unittest.TestCase):
    def setUp(self):
        super().setUp()
        import cogs.command_log as command_log

        self.command_log = command_log

    def test_an_empty_result_explains_itself(self):
        embed = self.command_log.build_auditlog_embed([], guild=None, page=0, total=0)

        self.assertIn("No command invocations", embed.description)

    def test_rows_render_with_their_actor_and_target(self):
        rows = [{
            "command": "purge",
            "user_id": "7",
            "target_id": "555",
            "outcome": OUTCOME_SUCCESS,
            "occurred_at": 1_700_000_000,
        }]

        embed = self.command_log.build_auditlog_embed(rows, guild=None, page=0, total=1)

        self.assertIn("purge", embed.description)
        self.assertIn("<@7>", embed.description)
        self.assertIn("<@555>", embed.description)


class DataManagerAuditTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        import tempfile
        from pathlib import Path

        import core.data as data

        self._tempdir = tempfile.TemporaryDirectory()
        self._original_db_file = data.DB_FILE
        data.DB_FILE = Path(self._tempdir.name) / "bot.db"
        self.data = data
        self.manager = data.DataManager(SimpleNamespace(metrics=None))

    async def asyncTearDown(self):
        await self.manager.close()
        self.data.DB_FILE = self._original_db_file
        self._tempdir.cleanup()

    def _record(self, **overrides):
        base = dict(source="slash", command="purge", user_id=7, user_label="Mod")
        base.update(overrides)
        return CommandAuditRecord(**base)

    async def test_records_round_trip_through_the_database(self):
        written = await self.manager.record_command_audit([
            self._record(command="purge", target_id=555),
            self._record(command="help", user_id=8),
        ])
        self.assertEqual(written, 2)

        rows = await self.manager.list_command_audit()
        self.assertEqual([row["command"] for row in rows], ["help", "purge"])
        self.assertEqual(await self.manager.count_command_audit(), 2)

    async def test_results_can_be_filtered_by_actor_and_command(self):
        await self.manager.record_command_audit([
            self._record(command="purge", user_id=7),
            self._record(command="help", user_id=8),
        ])

        self.assertEqual(len(await self.manager.list_command_audit(user_id=7)), 1)
        self.assertEqual(await self.manager.count_command_audit(user_id=8), 1)
        self.assertEqual(len(await self.manager.list_command_audit(command="pur")), 1)

    async def test_options_are_stored_as_json_so_the_trail_survives_a_purged_channel(self):
        await self.manager.record_command_audit([self._record(options=(("amount", "5"),))])

        import json

        rows = await self.manager.list_command_audit()
        self.assertEqual(json.loads(rows[0]["options"]), [["amount", "5"]])

    async def test_writing_an_empty_batch_is_a_no_op(self):
        self.assertEqual(await self.manager.record_command_audit([]), 0)

    async def test_old_rows_are_pruned_by_the_retention_sweep(self):
        import time

        await self.manager.record_command_audit([self._record()])
        # 31 days on, the row is past the 30-day retention window.
        deleted = await self.manager.prune_command_audit(now_timestamp=int(time.time()) + 31 * 86400)

        self.assertEqual(deleted, 1)
        self.assertEqual(await self.manager.count_command_audit(), 0)


if __name__ == "__main__":
    unittest.main()
