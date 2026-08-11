import asyncio
import unittest

from core.heavy_jobs import (
    HeavyJob,
    HeavyJobKind,
    HeavyJobOverloaded,
    HeavyJobPriority,
    HeavyJobQueue,
    HeavyJobStopped,
    HeavyJobTimedOut,
    RecentMessageIndex,
)


def _job(operation, *, guild_id=1, priority=HeavyJobPriority.MODERATION, dedupe=None, timeout=None):
    kind = {
        HeavyJobPriority.SECURITY: HeavyJobKind.IMAGE_CLEANUP,
        HeavyJobPriority.MODERATION: HeavyJobKind.MODERATION_EVIDENCE,
        HeavyJobPriority.EXPORT: HeavyJobKind.EXPORT,
    }[priority]
    return HeavyJob(
        kind=kind,
        priority=priority,
        guild_id=guild_id,
        deduplication_key=dedupe,
        timeout=timeout,
        operation=operation,
    )


class HeavyJobQueueTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.queues = []

    async def asyncTearDown(self):
        for queue in self.queues:
            await queue.shutdown(timeout=0)

    def make_queue(self, **kwargs):
        queue = HeavyJobQueue(**kwargs)
        self.queues.append(queue)
        queue.start()
        return queue

    async def test_priority_order_is_security_then_moderation_then_export(self):
        queue = self.make_queue(workers=1)
        order = []

        def operation(label):
            async def run():
                order.append(label)
                return label
            return run

        export = queue.enqueue(_job(operation("export"), guild_id=1, priority=HeavyJobPriority.EXPORT))
        moderation = queue.enqueue(_job(operation("moderation"), guild_id=2))
        security = queue.enqueue(_job(operation("security"), guild_id=3, priority=HeavyJobPriority.SECURITY))

        self.assertEqual(await asyncio.gather(export, moderation, security), ["export", "moderation", "security"])
        self.assertEqual(order, ["security", "moderation", "export"])

    async def test_capacity_reserves_five_slots_for_security(self):
        queue = self.make_queue(workers=2, capacity=20, security_reserve=5)
        release = asyncio.Event()

        async def blocked():
            await release.wait()

        futures = [
            queue.enqueue(_job(blocked, guild_id=index + 1))
            for index in range(15)
        ]
        with self.assertRaises(HeavyJobOverloaded):
            queue.enqueue(_job(blocked, guild_id=100))

        futures.extend(
            queue.enqueue(_job(blocked, guild_id=200 + index, priority=HeavyJobPriority.SECURITY))
            for index in range(5)
        )
        self.assertEqual(queue.admitted_count, 20)
        with self.assertRaises(HeavyJobOverloaded):
            queue.enqueue(_job(blocked, guild_id=300, priority=HeavyJobPriority.SECURITY))

        release.set()
        await asyncio.gather(*futures)

    async def test_deduplicated_jobs_share_one_operation_and_future(self):
        queue = self.make_queue()
        calls = 0

        async def operation():
            nonlocal calls
            calls += 1
            await asyncio.sleep(0)
            return 42

        first = queue.enqueue(_job(operation, dedupe="image-cleanup:1:42"))
        second = queue.enqueue(_job(operation, dedupe="image-cleanup:1:42"))

        self.assertIs(first, second)
        self.assertEqual(await first, 42)
        self.assertEqual(calls, 1)

    async def test_only_one_job_per_guild_runs_at_a_time(self):
        queue = self.make_queue(workers=2)
        release_first = asyncio.Event()
        first_started = asyncio.Event()
        second_started = asyncio.Event()
        other_started = asyncio.Event()

        async def first_operation():
            first_started.set()
            await release_first.wait()
            return "first"

        async def second_operation():
            second_started.set()
            return "second"

        async def other_operation():
            other_started.set()
            return "other"

        first = queue.enqueue(_job(first_operation, guild_id=1))
        second = queue.enqueue(_job(second_operation, guild_id=1))
        other = queue.enqueue(_job(other_operation, guild_id=2))

        await asyncio.wait_for(first_started.wait(), timeout=1)
        await asyncio.wait_for(other_started.wait(), timeout=1)
        self.assertFalse(second_started.is_set())
        release_first.set()
        self.assertEqual(await asyncio.gather(first, second, other), ["first", "second", "other"])
        self.assertTrue(second_started.is_set())

    async def test_job_timeout_is_typed(self):
        queue = self.make_queue(workers=1)

        async def operation():
            await asyncio.Event().wait()

        future = queue.enqueue(_job(operation, timeout=0.01))
        with self.assertRaises(HeavyJobTimedOut):
            await future

    async def test_scan_concurrency_is_process_wide(self):
        queue = self.make_queue(scan_concurrency=4)
        active = 0
        maximum = 0

        async def operation(value):
            nonlocal active, maximum
            active += 1
            maximum = max(maximum, active)
            await asyncio.sleep(0.01)
            active -= 1
            return value * 2

        result = await queue.map_scans(range(20), operation)
        self.assertEqual(result, [value * 2 for value in range(20)])
        self.assertEqual(maximum, 4)

    async def test_shutdown_stops_admission_and_cancels_unfinished_jobs(self):
        queue = self.make_queue(workers=1)
        started = asyncio.Event()

        async def operation():
            started.set()
            await asyncio.Event().wait()

        active = queue.enqueue(_job(operation, guild_id=1))
        pending = queue.enqueue(_job(operation, guild_id=2))
        await started.wait()
        await queue.shutdown(timeout=0)

        self.assertTrue(active.cancelled())
        self.assertTrue(pending.cancelled())
        with self.assertRaises(HeavyJobStopped):
            queue.enqueue(_job(operation, guild_id=3))


class RecentMessageIndexTests(unittest.TestCase):
    def test_index_keeps_only_identifiers_and_timestamps(self):
        index = RecentMessageIndex(started_at=0)
        index.record(guild_id=1, user_id=2, channel_id=3, message_id=4, timestamp=100)

        references, complete = index.get_user_references(
            1,
            2,
            since_timestamp=50,
            now_timestamp=100,
        )

        self.assertTrue(complete)
        self.assertEqual(
            (references[0].guild_id, references[0].user_id, references[0].channel_id,
             references[0].message_id, references[0].timestamp),
            (1, 2, 3, 4, 100),
        )

    def test_restart_or_eviction_requires_bounded_fallback_scan(self):
        restarted = RecentMessageIndex(started_at=200)
        _, complete = restarted.get_user_references(1, 2, since_timestamp=100, now_timestamp=250)
        self.assertFalse(complete)

        bounded = RecentMessageIndex(retention_seconds=100, per_user_limit=2, started_at=0)
        for message_id in range(1, 4):
            bounded.record(
                guild_id=1,
                user_id=2,
                channel_id=3,
                message_id=message_id,
                timestamp=100 + message_id,
            )
        references, complete = bounded.get_user_references(
            1,
            2,
            since_timestamp=0,
            now_timestamp=103,
        )
        self.assertEqual([reference.message_id for reference in references], [3, 2])
        self.assertFalse(complete)

    def test_global_and_per_user_limits_are_enforced(self):
        index = RecentMessageIndex(global_limit=3, per_user_limit=2, started_at=0)
        index.record(guild_id=1, user_id=10, channel_id=1, message_id=1, timestamp=100)
        index.record(guild_id=1, user_id=10, channel_id=1, message_id=2, timestamp=101)
        index.record(guild_id=1, user_id=10, channel_id=1, message_id=3, timestamp=102)
        index.record(guild_id=1, user_id=11, channel_id=1, message_id=4, timestamp=103)
        index.record(guild_id=1, user_id=12, channel_id=1, message_id=5, timestamp=104)

        self.assertEqual(len(index), 3)
        references, _ = index.get_user_references(1, 10, since_timestamp=0, now_timestamp=104)
        self.assertEqual([reference.message_id for reference in references], [3])

    def test_prune_removes_references_older_than_24_hours(self):
        index = RecentMessageIndex(retention_seconds=10, started_at=0)
        index.record(guild_id=1, user_id=2, channel_id=3, message_id=4, timestamp=100)
        index.prune(now_timestamp=111)
        self.assertEqual(len(index), 0)


if __name__ == "__main__":
    unittest.main()
