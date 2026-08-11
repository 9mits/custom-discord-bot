"""Bounded runtime metrics and Discord interaction instrumentation."""

from __future__ import annotations

import asyncio
import math
import time
from collections import deque
from dataclasses import dataclass, field
from typing import Any, Awaitable, Callable, Deque, Dict, Mapping, Optional

import discord
from discord import InteractionType, app_commands


def _percentile(values: Deque[float], percentile: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, math.ceil(percentile * len(ordered)) - 1))
    return ordered[index]


@dataclass(frozen=True)
class LoopHealth:
    name: str
    status: str
    last_success_at: Optional[float]
    last_failure_at: Optional[float]
    failures: int
    last_error: Optional[str]


@dataclass(frozen=True)
class HealthSnapshot:
    acknowledgement_p50_ms: float
    acknowledgement_p95_ms: float
    completion_p50_ms: float
    completion_p95_ms: float
    database_p50_ms: float
    database_p95_ms: float
    event_loop_lag_p50_ms: float
    event_loop_lag_p95_ms: float
    queue_depth: int
    queue_depth_p95: float
    queue_active: int
    failures: int
    uptime_seconds: int
    loops: Dict[str, LoopHealth] = field(default_factory=dict)


class OperationMetrics:
    """Small in-memory rolling windows; no request content or user data is kept."""

    def __init__(self, *, sample_limit: int = 1000, interaction_limit: int = 2000) -> None:
        limit = max(20, int(sample_limit))
        self.acknowledgement_seconds: Deque[float] = deque(maxlen=limit)
        self.completion_seconds: Deque[float] = deque(maxlen=limit)
        self.database_seconds: Deque[float] = deque(maxlen=limit)
        self.event_loop_lag_seconds: Deque[float] = deque(maxlen=limit)
        self.queue_depths: Deque[float] = deque(maxlen=limit)
        self.failures = 0
        self._interactions: Dict[int, Dict[str, Any]] = {}
        self._interaction_order: Deque[int] = deque(maxlen=max(100, int(interaction_limit)))
        self._loops: Dict[str, Dict[str, Any]] = {}
        self._lag_task: Optional[asyncio.Task] = None

    def begin_interaction(self, interaction_id: int, *, started_at: Optional[float] = None) -> None:
        interaction_key = int(interaction_id)
        if interaction_key in self._interactions:
            return
        if len(self._interaction_order) == self._interaction_order.maxlen:
            expired = self._interaction_order.popleft()
            self._interactions.pop(expired, None)
        self._interaction_order.append(interaction_key)
        self._interactions[interaction_key] = {
            "started_at": time.perf_counter() if started_at is None else float(started_at),
            "acknowledged": False,
        }

    def record_acknowledgement(self, interaction_id: int, *, at: Optional[float] = None) -> None:
        state = self._interactions.get(int(interaction_id))
        if state is None:
            self.begin_interaction(interaction_id)
            state = self._interactions[int(interaction_id)]
        if state["acknowledged"]:
            return
        state["acknowledged"] = True
        finished = time.perf_counter() if at is None else float(at)
        self.acknowledgement_seconds.append(max(0.0, finished - state["started_at"]))

    def complete_interaction(self, interaction_id: int, *, failed: bool = False) -> None:
        interaction_key = int(interaction_id)
        state = self._interactions.pop(interaction_key, None)
        if state is None:
            return
        try:
            self._interaction_order.remove(interaction_key)
        except ValueError:
            pass
        self.completion_seconds.append(max(0.0, time.perf_counter() - state["started_at"]))
        if failed:
            self.failures += 1

    async def measure_interaction(
        self,
        interaction,
        operation: Callable[[], Awaitable[Any]],
    ) -> Any:
        if interaction.type is InteractionType.autocomplete:
            return await operation()
        self.begin_interaction(interaction.id)

        async def watch_acknowledgement() -> None:
            while not interaction.response.is_done():
                await asyncio.sleep(0.005)
            self.record_acknowledgement(interaction.id)

        watcher = asyncio.create_task(
            watch_acknowledgement(),
            name=f"interaction-ack-{interaction.id}",
        )
        failed = False
        try:
            return await operation()
        except Exception:
            failed = True
            raise
        finally:
            if interaction.response.is_done():
                self.record_acknowledgement(interaction.id)
            watcher.cancel()
            await asyncio.gather(watcher, return_exceptions=True)
            self.complete_interaction(
                interaction.id,
                failed=failed or bool(getattr(interaction, "command_failed", False)),
            )

    def record_database_write(self, duration_seconds: float) -> None:
        self.database_seconds.append(max(0.0, float(duration_seconds)))

    def record_event_loop_lag(self, duration_seconds: float) -> None:
        self.event_loop_lag_seconds.append(max(0.0, float(duration_seconds)))

    def record_queue_depth(self, depth: int) -> None:
        self.queue_depths.append(float(max(0, int(depth))))

    def record_failure(self) -> None:
        self.failures += 1

    def register_loop(self, name: str, *, expected_interval_seconds: float) -> None:
        self._loops.setdefault(name, {
            "expected_interval": max(1.0, float(expected_interval_seconds)),
            "last_success": None,
            "last_failure": None,
            "failures": 0,
            "last_error": None,
        })

    def record_loop_success(self, name: str) -> None:
        state = self._loops.setdefault(name, {
            "expected_interval": 60.0,
            "last_success": None,
            "last_failure": None,
            "failures": 0,
            "last_error": None,
        })
        state["last_success"] = time.time()

    def record_loop_failure(self, name: str, error: BaseException) -> None:
        state = self._loops.setdefault(name, {
            "expected_interval": 60.0,
            "last_success": None,
            "last_failure": None,
            "failures": 0,
            "last_error": None,
        })
        state["last_failure"] = time.time()
        state["failures"] += 1
        state["last_error"] = type(error).__name__
        self.failures += 1

    def start_event_loop_monitor(self, *, interval_seconds: float = 0.5) -> None:
        if self._lag_task is not None and not self._lag_task.done():
            return

        async def monitor() -> None:
            loop = asyncio.get_running_loop()
            interval = max(0.05, float(interval_seconds))
            expected = loop.time() + interval
            while True:
                await asyncio.sleep(interval)
                now = loop.time()
                self.record_event_loop_lag(max(0.0, now - expected))
                expected = now + interval

        self._lag_task = asyncio.create_task(monitor(), name="event-loop-lag-monitor")

    async def stop_event_loop_monitor(self) -> None:
        if self._lag_task is None:
            return
        self._lag_task.cancel()
        await asyncio.gather(self._lag_task, return_exceptions=True)
        self._lag_task = None

    def snapshot(
        self,
        *,
        queue=None,
        uptime_seconds: int = 0,
        loop_running: Optional[Mapping[str, bool]] = None,
    ) -> HealthSnapshot:
        now = time.time()
        loop_states: Dict[str, LoopHealth] = {}
        for name, state in self._loops.items():
            running = True if loop_running is None else bool(loop_running.get(name, False))
            last_success = state["last_success"]
            last_failure = state["last_failure"]
            if not running:
                status = "Stopped"
            elif last_failure is not None and (last_success is None or last_failure > last_success):
                status = "Degraded"
            elif last_success is None:
                status = "Starting"
            elif now - last_success > state["expected_interval"] * 2.5:
                status = "Degraded"
            else:
                status = "Healthy"
            loop_states[name] = LoopHealth(
                name=name,
                status=status,
                last_success_at=last_success,
                last_failure_at=last_failure,
                failures=int(state["failures"]),
                last_error=state["last_error"],
            )

        queue_depth = int(getattr(queue, "depth", 0) or 0)
        queue_active = int(getattr(queue, "active_count", 0) or 0)
        return HealthSnapshot(
            acknowledgement_p50_ms=_percentile(self.acknowledgement_seconds, 0.50) * 1000,
            acknowledgement_p95_ms=_percentile(self.acknowledgement_seconds, 0.95) * 1000,
            completion_p50_ms=_percentile(self.completion_seconds, 0.50) * 1000,
            completion_p95_ms=_percentile(self.completion_seconds, 0.95) * 1000,
            database_p50_ms=_percentile(self.database_seconds, 0.50) * 1000,
            database_p95_ms=_percentile(self.database_seconds, 0.95) * 1000,
            event_loop_lag_p50_ms=_percentile(self.event_loop_lag_seconds, 0.50) * 1000,
            event_loop_lag_p95_ms=_percentile(self.event_loop_lag_seconds, 0.95) * 1000,
            queue_depth=queue_depth,
            queue_depth_p95=_percentile(self.queue_depths, 0.95),
            queue_active=queue_active,
            failures=self.failures,
            uptime_seconds=max(0, int(uptime_seconds)),
            loops=loop_states,
        )


class MetricsCommandTree(app_commands.CommandTree):
    async def _call(self, interaction) -> None:
        metrics = getattr(self.client, "metrics", None)
        if metrics is None:
            await super()._call(interaction)
            return

        async def operation() -> None:
            await super(MetricsCommandTree, self)._call(interaction)

        await metrics.measure_interaction(interaction, operation)


def install_component_metrics() -> None:
    """Instrument discord.py's central View and Modal dispatch points once."""
    if not getattr(discord.ui.View, "_mgx_metrics_wrapped", False):
        original_view_task = discord.ui.View._scheduled_task

        async def measured_view_task(view, item, interaction):
            metrics = getattr(interaction.client, "metrics", None)
            if metrics is None:
                return await original_view_task(view, item, interaction)

            async def operation():
                return await original_view_task(view, item, interaction)

            return await metrics.measure_interaction(interaction, operation)

        discord.ui.View._scheduled_task = measured_view_task
        discord.ui.View._mgx_metrics_wrapped = True

    if not getattr(discord.ui.Modal, "_mgx_metrics_wrapped", False):
        original_modal_task = discord.ui.Modal._scheduled_task

        async def measured_modal_task(modal, interaction, components, resolved):
            metrics = getattr(interaction.client, "metrics", None)
            if metrics is None:
                return await original_modal_task(modal, interaction, components, resolved)

            async def operation():
                return await original_modal_task(modal, interaction, components, resolved)

            return await metrics.measure_interaction(interaction, operation)

        discord.ui.Modal._scheduled_task = measured_modal_task
        discord.ui.Modal._mgx_metrics_wrapped = True
