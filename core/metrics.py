"""Bounded runtime metrics and Discord interaction instrumentation."""

from __future__ import annotations

import asyncio
import logging
import math
import re
import time
from collections import deque
from dataclasses import dataclass, field
from typing import Any, Awaitable, Callable, Deque, Dict, Mapping, Optional

import discord
from discord import InteractionType, app_commands

from . import command_audit
from .actions import AcknowledgementPolicy, authorize_interaction
from .errors import BotOperationError, classify_operation_error, respond_operation_error
from .responding import InteractionResponder


logger = logging.getLogger(__name__)


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


async def emit_command_audit(
    client,
    interaction,
    *,
    source: str,
    spec=None,
    command: Optional[str] = None,
    outcome: str = command_audit.OUTCOME_SUCCESS,
    duration_ms: int = 0,
    correlation_id: Optional[str] = None,
    detail: Optional[str] = None,
) -> None:
    """Build and emit one audit record. Never raises into the command path."""
    if not command_audit.has_sink():
        return
    try:
        data_manager = getattr(client, "data_manager", None)
        config = getattr(data_manager, "config", {}) or {}
        settings = command_audit.get_settings(config)
        if not settings.get("enabled", True):
            return
        record = command_audit.build_record(
            interaction,
            source=source,
            spec=spec,
            command=command,
            outcome=outcome,
            duration_ms=duration_ms,
            correlation_id=correlation_id,
            detail=detail,
            redact=bool(settings.get("redact", True)),
        )
        if not command_audit.should_record(record, config):
            return
        await command_audit.emit(record)
    except asyncio.CancelledError:
        raise
    except Exception:
        logger.exception("Failed to emit command audit record")


class MetricsCommandTree(app_commands.CommandTree):
    async def _call(self, interaction) -> None:
        metrics = getattr(self.client, "metrics", None)
        audited = interaction.type is not InteractionType.autocomplete
        started_at = time.perf_counter()
        audit_state: Dict[str, Any] = {
            "spec": None,
            "outcome": command_audit.OUTCOME_SUCCESS,
            "correlation_id": None,
            "detail": None,
        }

        def mark_failed(operation_error: Optional[BotOperationError], detail: Optional[str] = None) -> None:
            audit_state["outcome"] = command_audit.OUTCOME_FAILED
            if operation_error is not None:
                audit_state["correlation_id"] = operation_error.correlation_id
                audit_state["detail"] = detail or type(operation_error).__name__
            else:
                audit_state["detail"] = detail

        async def operation() -> None:
            try:
                data_manager = getattr(self.client, "data_manager", None)
                config = getattr(data_manager, "config", {})
                spec = authorize_interaction(interaction, config)
                audit_state["spec"] = spec
                if spec is not None and spec.acknowledgement_policy is AcknowledgementPolicy.DEFER:
                    await InteractionResponder(interaction, metrics=metrics).defer(ephemeral=True)
            except BotOperationError as error:
                interaction.command_failed = True
                mark_failed(error)
                await respond_operation_error(interaction, error)
                return
            except Exception as error:
                operation_error = classify_operation_error(error)
                interaction.command_failed = True
                mark_failed(operation_error)
                logger.exception(
                    "Interaction policy failed correlation_id=%s",
                    operation_error.correlation_id,
                )
                await respond_operation_error(interaction, operation_error)
                return
            try:
                await super(MetricsCommandTree, self)._call(interaction)
            except Exception as error:
                # classify_operation_error caches on the exception, so the id logged
                # here is the same one on_app_command_error shows the caller.
                mark_failed(classify_operation_error(error), type(error).__name__)
                raise
            if getattr(interaction, "command_failed", False):
                mark_failed(None, "command_failed")

        try:
            if metrics is None:
                await operation()
            else:
                await metrics.measure_interaction(interaction, operation)
        finally:
            if audited:
                await emit_command_audit(
                    self.client,
                    interaction,
                    source=(
                        command_audit.SOURCE_CONTEXT
                        if getattr(interaction, "command", None) is not None
                        and isinstance(getattr(interaction, "command", None), app_commands.ContextMenu)
                        else command_audit.SOURCE_SLASH
                    ),
                    spec=audit_state["spec"],
                    outcome=audit_state["outcome"],
                    duration_ms=int((time.perf_counter() - started_at) * 1000),
                    correlation_id=audit_state["correlation_id"],
                    detail=audit_state["detail"],
                )


_UI_NAME_SUFFIXES = ("Button", "Select", "Modal", "View", "Container")


def _humanize_ui_name(name: str) -> str:
    """`SettingsChannelValueSelect` -> `Settings Channel Value`."""
    trimmed = str(name or "Component")
    for suffix in _UI_NAME_SUFFIXES:
        if trimmed.endswith(suffix) and len(trimmed) > len(suffix):
            trimmed = trimmed[: -len(suffix)]
            break
    spaced = re.sub(r"(?<=[a-z0-9])(?=[A-Z])", " ", trimmed).strip()
    return spaced or str(name or "Component")


def _component_label(view, item) -> str:
    """A readable `View → control` label for a panel interaction."""
    control = getattr(item, "label", None) or getattr(item, "placeholder", None)
    if not control:
        control = _humanize_ui_name(type(item).__name__)
    return f"{_humanize_ui_name(type(view).__name__)} → {control}"


def install_component_metrics() -> None:
    """Instrument discord.py's central View and Modal dispatch points once."""
    if not getattr(discord.ui.View, "_mgx_metrics_wrapped", False):
        original_view_task = discord.ui.View._scheduled_task

        async def measured_view_task(view, item, interaction):
            metrics = getattr(interaction.client, "metrics", None)
            started_at = time.perf_counter()
            outcome = command_audit.OUTCOME_SUCCESS
            detail = None
            try:
                if metrics is None:
                    return await original_view_task(view, item, interaction)

                async def operation():
                    return await original_view_task(view, item, interaction)

                return await metrics.measure_interaction(interaction, operation)
            except Exception as error:
                outcome = command_audit.OUTCOME_FAILED
                detail = type(error).__name__
                raise
            finally:
                await emit_command_audit(
                    interaction.client,
                    interaction,
                    source=command_audit.SOURCE_COMPONENT,
                    command=_component_label(view, item),
                    outcome=outcome,
                    duration_ms=int((time.perf_counter() - started_at) * 1000),
                    detail=detail,
                )

        discord.ui.View._scheduled_task = measured_view_task
        discord.ui.View._mgx_metrics_wrapped = True

    if not getattr(discord.ui.Modal, "_mgx_metrics_wrapped", False):
        original_modal_task = discord.ui.Modal._scheduled_task

        async def measured_modal_task(modal, interaction, components, resolved):
            metrics = getattr(interaction.client, "metrics", None)
            started_at = time.perf_counter()
            outcome = command_audit.OUTCOME_SUCCESS
            detail = None
            try:
                if metrics is None:
                    return await original_modal_task(modal, interaction, components, resolved)

                async def operation():
                    return await original_modal_task(modal, interaction, components, resolved)

                return await metrics.measure_interaction(interaction, operation)
            except Exception as error:
                outcome = command_audit.OUTCOME_FAILED
                detail = type(error).__name__
                raise
            finally:
                await emit_command_audit(
                    interaction.client,
                    interaction,
                    source=command_audit.SOURCE_MODAL,
                    command=_humanize_ui_name(type(modal).__name__),
                    outcome=outcome,
                    duration_ms=int((time.perf_counter() - started_at) * 1000),
                    detail=detail,
                )

        discord.ui.Modal._scheduled_task = measured_modal_task
        discord.ui.Modal._mgx_metrics_wrapped = True

    if not getattr(discord.ui.View, "_mgx_errors_wrapped", False):
        original_view_error = discord.ui.View.on_error

        async def safe_view_error(view, interaction, error, item):
            operation_error = classify_operation_error(error)
            logger.error(
                "Component failed correlation_id=%s view=%s item=%s",
                operation_error.correlation_id,
                type(view).__name__,
                type(item).__name__,
                exc_info=(type(error), error, error.__traceback__),
            )
            try:
                await respond_operation_error(interaction, operation_error)
            except Exception:
                pass
            await original_view_error(view, interaction, error, item)

        discord.ui.View.on_error = safe_view_error
        discord.ui.View._mgx_errors_wrapped = True

    if not getattr(discord.ui.Modal, "_mgx_errors_wrapped", False):
        original_modal_error = discord.ui.Modal.on_error

        async def safe_modal_error(modal, interaction, error):
            operation_error = classify_operation_error(error)
            logger.error(
                "Modal failed correlation_id=%s modal=%s",
                operation_error.correlation_id,
                type(modal).__name__,
                exc_info=(type(error), error, error.__traceback__),
            )
            try:
                await respond_operation_error(interaction, operation_error)
            except Exception:
                pass
            await original_modal_error(modal, interaction, error)

        discord.ui.Modal.on_error = safe_modal_error
        discord.ui.Modal._mgx_errors_wrapped = True
