import asyncio
from types import SimpleNamespace
import unittest
from unittest.mock import AsyncMock, Mock, patch

from core.bot import MGXBot
from core.data import DataManager
from core.errors import InternalFailure
from core.metrics import OperationMetrics
from core.responding import InteractionResponder
from core.runtime import AsyncTTLCache, TTLMap, TTLSet
from core.services import get_native_automod_settings, invalidate_native_automod_settings


class OperationMetricsTests(unittest.IsolatedAsyncioTestCase):
    async def test_database_commit_duration_is_recorded(self):
        metrics = OperationMetrics()
        manager = DataManager(SimpleNamespace(metrics=metrics))
        database = SimpleNamespace(commit=AsyncMock())

        await manager._commit(database)

        database.commit.assert_awaited_once()
        self.assertEqual(len(metrics.database_seconds), 1)

    async def test_interaction_records_acknowledgement_before_completion(self):
        metrics = OperationMetrics()
        events = []
        response = SimpleNamespace(
            is_done=Mock(return_value=False),
            defer=AsyncMock(),
        )
        interaction = SimpleNamespace(
            id=123,
            client=SimpleNamespace(metrics=metrics),
            response=response,
            followup=SimpleNamespace(send=AsyncMock()),
        )
        responder = InteractionResponder(interaction)
        metrics.begin_interaction(interaction.id)

        async def defer(*, ephemeral, thinking):
            events.append("acknowledge")
            response.is_done.return_value = True

        response.defer.side_effect = defer
        await responder.defer(ephemeral=True)
        events.append("database")
        metrics.complete_interaction(interaction.id)

        self.assertEqual(events, ["acknowledge", "database"])
        self.assertEqual(len(metrics.acknowledgement_seconds), 1)
        self.assertEqual(len(metrics.completion_seconds), 1)

    async def test_responder_prevents_a_second_initial_response(self):
        done = False

        def is_done():
            return done

        async def defer(*, ephemeral, thinking):
            nonlocal done
            done = True

        interaction = SimpleNamespace(
            id=1,
            client=SimpleNamespace(metrics=OperationMetrics()),
            response=SimpleNamespace(is_done=is_done, defer=defer),
        )
        responder = InteractionResponder(interaction)

        self.assertTrue(await responder.defer())
        self.assertFalse(await responder.defer())

    async def test_failed_loop_recovers_on_next_iteration(self):
        metrics = OperationMetrics()
        metrics.register_loop("test loop", expected_interval_seconds=60)
        runtime = SimpleNamespace(metrics=metrics)

        async def failing():
            raise RuntimeError("temporary")

        async def succeeding():
            return None

        with patch("core.bot.logger.exception"), patch("core.runtime.asyncio.sleep", new=AsyncMock()):
            await MGXBot._run_background_loop(runtime, "test loop", failing)
        await MGXBot._run_background_loop(runtime, "test loop", succeeding)
        snapshot = metrics.snapshot(loop_running={"test loop": True})

        self.assertEqual(snapshot.loops["test loop"].status, "Healthy")
        self.assertEqual(snapshot.loops["test loop"].failures, 1)

    async def test_background_loop_retries_with_bounded_backoff(self):
        metrics = OperationMetrics()
        metrics.register_loop("retry loop", expected_interval_seconds=60)
        runtime = SimpleNamespace(metrics=metrics)
        attempts = 0

        async def transient_failure():
            nonlocal attempts
            attempts += 1
            if attempts < 3:
                raise RuntimeError("temporary")

        with patch("core.runtime.asyncio.sleep", new=AsyncMock()) as sleep:
            await MGXBot._run_background_loop(runtime, "retry loop", transient_failure)

        self.assertEqual(attempts, 3)
        self.assertEqual([entry.args[0] for entry in sleep.await_args_list], [1.0, 2.0])
        snapshot = metrics.snapshot(loop_running={"retry loop": True})
        self.assertEqual(snapshot.loops["retry loop"].status, "Healthy")


class MetricSnapshotTests(unittest.TestCase):
    def test_snapshot_reports_percentiles_queue_and_stopped_loops(self):
        metrics = OperationMetrics()
        metrics.acknowledgement_seconds.extend([0.01, 0.02, 0.03, 0.20])
        metrics.completion_seconds.extend([0.1, 0.2])
        metrics.database_seconds.extend([0.005, 0.025])
        metrics.event_loop_lag_seconds.extend([0.001, 0.1])
        metrics.queue_depths.extend([0, 2, 5])
        metrics.register_loop("worker", expected_interval_seconds=60)
        queue = SimpleNamespace(depth=3, active_count=2)

        snapshot = metrics.snapshot(
            queue=queue,
            uptime_seconds=90,
            loop_running={"worker": False},
        )

        self.assertEqual(snapshot.acknowledgement_p50_ms, 20)
        self.assertEqual(snapshot.acknowledgement_p95_ms, 200)
        self.assertEqual(snapshot.database_p95_ms, 25)
        self.assertEqual(snapshot.event_loop_lag_p95_ms, 100)
        self.assertEqual(snapshot.queue_depth, 3)
        self.assertEqual(snapshot.queue_active, 2)
        self.assertEqual(snapshot.loops["worker"].status, "Stopped")


class BoundedRuntimeContainerTests(unittest.IsolatedAsyncioTestCase):
    def test_ttl_map_and_set_evict_oldest_entries(self):
        cache = TTLMap(max_size=2, ttl_seconds=60)
        cache[1] = "one"
        cache[2] = "two"
        cache[3] = "three"
        self.assertNotIn(1, cache)
        self.assertEqual(cache[3], "three")

        values = TTLSet(max_size=2, ttl_seconds=60)
        values.add("a")
        values.add("b")
        values.add("c")
        self.assertNotIn("a", values)
        self.assertIn("c", values)

    async def test_async_cache_deduplicates_inflight_loads(self):
        cache = AsyncTTLCache(max_size=10, ttl_seconds=60)
        calls = 0

        async def factory():
            nonlocal calls
            calls += 1
            await asyncio.sleep(0)
            return "rule"

        first, second = await asyncio.gather(
            cache.get_or_create((1, 2), factory),
            cache.get_or_create((1, 2), factory),
        )
        self.assertEqual((first, second), ("rule", "rule"))
        self.assertEqual(calls, 1)


class NativeAutoModSettingsCacheTests(unittest.TestCase):
    def test_settings_cache_requires_explicit_write_invalidation(self):
        config = {"native_automod": {"enabled": True}}
        self.assertTrue(get_native_automod_settings(config)["enabled"])
        config["native_automod"]["enabled"] = False
        self.assertTrue(get_native_automod_settings(config)["enabled"])

        invalidate_native_automod_settings(config)
        self.assertFalse(get_native_automod_settings(config)["enabled"])


class TypedErrorTests(unittest.TestCase):
    def test_internal_error_has_safe_message_and_correlation_id(self):
        error = InternalFailure("database credentials should stay private")
        self.assertEqual(
            error.public_message,
            "The bot hit an unexpected error while processing this action.",
        )
        self.assertRegex(error.correlation_id, r"^[a-f0-9]{12}$")
        self.assertNotIn("credentials", error.public_message)


if __name__ == "__main__":
    unittest.main()
