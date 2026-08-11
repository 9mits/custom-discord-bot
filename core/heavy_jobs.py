"""Bounded process-wide admission for history-heavy Discord work."""

from __future__ import annotations

import asyncio
import heapq
import logging
import time
import uuid
from collections import OrderedDict, defaultdict, deque
from contextlib import asynccontextmanager
from dataclasses import dataclass, field
from enum import Enum, IntEnum
from typing import Any, Awaitable, Callable, Deque, Dict, Iterable, List, Optional, Tuple

from core.errors import BotOperationError


logger = logging.getLogger("MGXBot")


class HeavyJobKind(str, Enum):
    IMAGE_CLEANUP = "image_cleanup"
    MODERATION_EVIDENCE = "moderation_evidence"
    EXPORT = "export"


class HeavyJobPriority(IntEnum):
    SECURITY = 0
    MODERATION = 10
    EXPORT = 20


DEFAULT_JOB_TIMEOUTS = {
    HeavyJobKind.IMAGE_CLEANUP: 300.0,
    HeavyJobKind.MODERATION_EVIDENCE: 60.0,
    HeavyJobKind.EXPORT: 180.0,
}


class HeavyJobError(BotOperationError):
    pass


class HeavyJobOverloaded(HeavyJobError):
    title = "System Busy"
    public_message = "The bot is handling other heavy work. Try again shortly."


class HeavyJobStopped(HeavyJobError):
    title = "System Unavailable"
    public_message = "The background work queue is not available yet."


class HeavyJobTimedOut(HeavyJobError):
    title = "Operation Timed Out"
    public_message = "The operation exceeded its safe time limit and was stopped."


@dataclass
class HeavyJob:
    kind: HeavyJobKind
    priority: HeavyJobPriority
    guild_id: int
    operation: Callable[[], Awaitable[Any]]
    deduplication_key: Optional[str] = None
    timeout: Optional[float] = None
    correlation_id: str = field(default_factory=lambda: uuid.uuid4().hex[:12])
    future: Optional[asyncio.Future] = field(default=None, init=False, repr=False)

    def resolved_timeout(self) -> float:
        return float(self.timeout or DEFAULT_JOB_TIMEOUTS[self.kind])


@dataclass(frozen=True)
class MessageReference:
    guild_id: int
    user_id: int
    channel_id: int
    message_id: int
    timestamp: int


class RecentMessageIndex:
    """A restart-local 24-hour index containing identifiers and timestamps only."""

    def __init__(
        self,
        *,
        retention_seconds: int = 86400,
        global_limit: int = 50_000,
        per_user_limit: int = 500,
        started_at: Optional[int] = None,
    ) -> None:
        self.retention_seconds = max(1, int(retention_seconds))
        self.global_limit = max(1, int(global_limit))
        self.per_user_limit = max(1, int(per_user_limit))
        self.started_at = int(time.time()) if started_at is None else int(started_at)
        self._messages: "OrderedDict[int, MessageReference]" = OrderedDict()
        self._by_user: Dict[Tuple[int, int], Deque[int]] = defaultdict(deque)
        self._incomplete_until: Dict[Tuple[int, int], int] = {}

    def __len__(self) -> int:
        return len(self._messages)

    def _drop(self, message_id: int, *, incomplete_at: Optional[int] = None) -> None:
        reference = self._messages.pop(int(message_id), None)
        if reference is None:
            return
        key = (reference.guild_id, reference.user_id)
        ids = self._by_user.get(key)
        if ids is not None:
            try:
                ids.remove(reference.message_id)
            except ValueError:
                pass
            if not ids:
                self._by_user.pop(key, None)
        if incomplete_at is not None:
            self._incomplete_until[key] = max(
                self._incomplete_until.get(key, 0),
                int(incomplete_at) + self.retention_seconds,
            )

    def prune(self, *, now_timestamp: Optional[int] = None) -> None:
        now_value = int(time.time()) if now_timestamp is None else int(now_timestamp)
        cutoff = now_value - self.retention_seconds
        while self._messages:
            message_id, reference = next(iter(self._messages.items()))
            if reference.timestamp >= cutoff:
                break
            self._drop(message_id)
        for key, incomplete_until in list(self._incomplete_until.items()):
            if incomplete_until <= now_value:
                self._incomplete_until.pop(key, None)

    def record(
        self,
        *,
        guild_id: int,
        user_id: int,
        channel_id: int,
        message_id: int,
        timestamp: int,
    ) -> None:
        reference = MessageReference(
            guild_id=int(guild_id),
            user_id=int(user_id),
            channel_id=int(channel_id),
            message_id=int(message_id),
            timestamp=int(timestamp),
        )
        self.prune(now_timestamp=reference.timestamp)
        self._drop(reference.message_id)
        key = (reference.guild_id, reference.user_id)
        self._messages[reference.message_id] = reference
        self._by_user[key].append(reference.message_id)

        while len(self._by_user[key]) > self.per_user_limit:
            self._drop(self._by_user[key][0], incomplete_at=reference.timestamp)
        while len(self._messages) > self.global_limit:
            oldest_id = next(iter(self._messages))
            self._drop(oldest_id, incomplete_at=reference.timestamp)

    def remove(self, message_id: int) -> None:
        self._drop(int(message_id))

    def get_user_references(
        self,
        guild_id: int,
        user_id: int,
        *,
        since_timestamp: int,
        now_timestamp: Optional[int] = None,
    ) -> Tuple[List[MessageReference], bool]:
        now_value = int(time.time()) if now_timestamp is None else int(now_timestamp)
        self.prune(now_timestamp=now_value)
        key = (int(guild_id), int(user_id))
        references = [
            self._messages[message_id]
            for message_id in self._by_user.get(key, ())
            if message_id in self._messages
            and self._messages[message_id].timestamp >= int(since_timestamp)
        ]
        references.sort(key=lambda reference: reference.timestamp, reverse=True)
        complete = (
            self.started_at <= int(since_timestamp)
            and self._incomplete_until.get(key, 0) <= now_value
        )
        return references, complete


class HeavyJobQueue:
    def __init__(
        self,
        *,
        workers: int = 2,
        capacity: int = 20,
        security_reserve: int = 5,
        scan_concurrency: int = 4,
        metrics=None,
    ) -> None:
        self.worker_count = max(1, int(workers))
        self.capacity = max(self.worker_count, int(capacity))
        self.security_reserve = max(0, min(int(security_reserve), self.capacity))
        self.scan_concurrency = max(1, int(scan_concurrency))
        self.metrics = metrics
        self._pending: List[Tuple[int, int, HeavyJob]] = []
        self._state_changed = asyncio.Event()
        self._all_done = asyncio.Event()
        self._all_done.set()
        self._scan_semaphore = asyncio.Semaphore(self.scan_concurrency)
        self._workers: List[asyncio.Task] = []
        self._sequence = 0
        self._admitted = 0
        self._non_security_admitted = 0
        self._accepting = False
        self._active_guilds = set()
        self._deduplicated_jobs: Dict[str, HeavyJob] = {}
        self._active_jobs: Dict[str, HeavyJob] = {}

    @property
    def accepting(self) -> bool:
        return self._accepting

    @property
    def depth(self) -> int:
        return len(self._pending)

    @property
    def active_count(self) -> int:
        return len(self._active_jobs)

    @property
    def admitted_count(self) -> int:
        return self._admitted

    def start(self) -> None:
        if self._workers:
            return
        self._accepting = True
        self._workers = [
            asyncio.create_task(self._worker(index), name=f"heavy-job-worker-{index}")
            for index in range(self.worker_count)
        ]

    def enqueue(self, job: HeavyJob) -> asyncio.Future:
        if not self._accepting:
            raise HeavyJobStopped("Heavy-work admission is stopped.")
        if job.deduplication_key:
            existing = self._deduplicated_jobs.get(job.deduplication_key)
            if existing is not None and existing.future is not None:
                return existing.future
        security_job = job.priority == HeavyJobPriority.SECURITY
        non_security_limit = self.capacity - self.security_reserve
        if self._admitted >= self.capacity or (
            not security_job and self._non_security_admitted >= non_security_limit
        ):
            raise HeavyJobOverloaded("The heavy-work queue is busy. Try again shortly.")

        loop = asyncio.get_running_loop()
        job.future = loop.create_future()
        job.future._heavy_job = job
        self._sequence += 1
        self._admitted += 1
        if not security_job:
            self._non_security_admitted += 1
        self._all_done.clear()
        if job.deduplication_key:
            self._deduplicated_jobs[job.deduplication_key] = job
        heapq.heappush(self._pending, (int(job.priority), self._sequence, job))
        if self.metrics is not None:
            self.metrics.record_queue_depth(self.depth)
        self._state_changed.set()
        return job.future

    def _release(self, job: HeavyJob) -> None:
        self._active_jobs.pop(job.correlation_id, None)
        self._active_guilds.discard(int(job.guild_id))
        if job.deduplication_key and self._deduplicated_jobs.get(job.deduplication_key) is job:
            self._deduplicated_jobs.pop(job.deduplication_key, None)
        self._admitted = max(0, self._admitted - 1)
        if job.priority != HeavyJobPriority.SECURITY:
            self._non_security_admitted = max(0, self._non_security_admitted - 1)
        if self._admitted == 0:
            self._all_done.set()
        if self.metrics is not None:
            self.metrics.record_queue_depth(self.depth)
        self._state_changed.set()

    async def _next_runnable_job(self) -> HeavyJob:
        while True:
            runnable = [
                (entry, index)
                for index, entry in enumerate(self._pending)
                if int(entry[2].guild_id) not in self._active_guilds
            ]
            if runnable:
                _, index = min(runnable, key=lambda item: item[0][:2])
                _, _, job = self._pending.pop(index)
                heapq.heapify(self._pending)
                self._active_guilds.add(int(job.guild_id))
                return job
            self._state_changed.clear()
            await self._state_changed.wait()

    async def _worker(self, worker_index: int) -> None:
        while True:
            job = await self._next_runnable_job()
            self._active_jobs[job.correlation_id] = job
            try:
                result = await asyncio.wait_for(job.operation(), timeout=job.resolved_timeout())
            except asyncio.TimeoutError:
                error = HeavyJobTimedOut(
                    f"{job.kind.value} job {job.correlation_id} exceeded {job.resolved_timeout():.0f}s."
                )
                if job.future is not None and not job.future.done():
                    job.future.set_exception(error)
                logger.warning("Heavy job timed out: %s (%s)", job.correlation_id, job.kind.value)
                if self.metrics is not None:
                    self.metrics.record_failure()
            except asyncio.CancelledError:
                if job.future is not None and not job.future.done():
                    job.future.cancel()
                raise
            except Exception as exc:
                if job.future is not None and not job.future.done():
                    job.future.set_exception(exc)
                logger.exception("Heavy job failed: %s (%s)", job.correlation_id, job.kind.value)
                if self.metrics is not None:
                    self.metrics.record_failure()
            else:
                if job.future is not None and not job.future.done():
                    job.future.set_result(result)
            finally:
                self._release(job)

    @asynccontextmanager
    async def scan_slot(self):
        async with self._scan_semaphore:
            yield

    async def map_scans(
        self,
        items: Iterable[Any],
        operation: Callable[[Any], Awaitable[Any]],
    ) -> List[Any]:
        values = list(items)
        if not values:
            return []
        results: List[Any] = [None] * len(values)
        next_index = 0

        async def worker() -> None:
            nonlocal next_index
            while next_index < len(values):
                index = next_index
                next_index += 1
                try:
                    async with self.scan_slot():
                        results[index] = await operation(values[index])
                except Exception as exc:
                    results[index] = exc

        await asyncio.gather(
            *(worker() for _ in range(min(self.scan_concurrency, len(values))))
        )
        return results

    async def shutdown(self, *, timeout: float = 10.0) -> None:
        self._accepting = False
        if not self._workers:
            return
        try:
            await asyncio.wait_for(self._all_done.wait(), timeout=max(0.0, float(timeout)))
        except asyncio.TimeoutError:
            correlations = [
                *self._active_jobs,
                *(entry[2].correlation_id for entry in self._pending),
            ]
            if correlations:
                logger.warning(
                    "Cancelling unfinished heavy jobs during shutdown: %s",
                    ", ".join(correlations),
                )
        finally:
            for worker in self._workers:
                worker.cancel()
            await asyncio.gather(*self._workers, return_exceptions=True)
            self._workers = []

            while self._pending:
                _, _, job = heapq.heappop(self._pending)
                if job.future is not None and not job.future.done():
                    job.future.cancel()
                self._release(job)
