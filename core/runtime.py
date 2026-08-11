"""Bounded TTL containers used by long-lived gateway state."""

from __future__ import annotations

import asyncio
import time
from collections import OrderedDict
from collections.abc import MutableMapping, MutableSet
from typing import Any, Awaitable, Callable, Iterator, Optional, Sequence, TypeVar


T = TypeVar("T")


async def retry_with_backoff(
    operation: Callable[[], Awaitable[T]],
    *,
    delays: Sequence[float] = (1.0, 2.0),
) -> T:
    """Retry a background operation with bounded exponential-style delays."""
    for delay in (*delays, None):
        try:
            return await operation()
        except asyncio.CancelledError:
            raise
        except Exception:
            if delay is None:
                raise
            await asyncio.sleep(max(0.0, float(delay)))
    raise RuntimeError("retry loop ended without a result")


class TTLMap(MutableMapping):
    def __init__(self, *, max_size: int, ttl_seconds: float) -> None:
        self.max_size = max(1, int(max_size))
        self.ttl_seconds = max(1.0, float(ttl_seconds))
        self._values: "OrderedDict[Any, tuple[Any, float]]" = OrderedDict()

    def _prune(self) -> None:
        now = time.monotonic()
        for key, (_, expires_at) in list(self._values.items()):
            if expires_at > now:
                continue
            self._values.pop(key, None)

    def __getitem__(self, key):
        self._prune()
        value, _ = self._values[key]
        self._values.move_to_end(key)
        return value

    def __setitem__(self, key, value) -> None:
        self._prune()
        self._values[key] = (value, time.monotonic() + self.ttl_seconds)
        self._values.move_to_end(key)
        while len(self._values) > self.max_size:
            self._values.popitem(last=False)

    def __delitem__(self, key) -> None:
        del self._values[key]

    def __iter__(self) -> Iterator:
        self._prune()
        return iter(list(self._values))

    def __len__(self) -> int:
        self._prune()
        return len(self._values)

    def get(self, key, default=None):
        try:
            return self[key]
        except KeyError:
            return default

    def pop(self, key, default=...):
        self._prune()
        entry = self._values.pop(key, None)
        if entry is not None:
            return entry[0]
        if default is ...:
            raise KeyError(key)
        return default

    def items(self):
        self._prune()
        return [(key, value) for key, (value, _) in self._values.items()]

    def clear(self) -> None:
        self._values.clear()


class TTLSet(MutableSet):
    def __init__(self, *, max_size: int, ttl_seconds: float) -> None:
        self._map = TTLMap(max_size=max_size, ttl_seconds=ttl_seconds)

    def __contains__(self, value) -> bool:
        return self._map.get(value, None) is not None

    def __iter__(self) -> Iterator:
        return iter(self._map)

    def __len__(self) -> int:
        return len(self._map)

    def add(self, value) -> None:
        self._map[value] = True

    def discard(self, value) -> None:
        self._map.pop(value, None)

    def clear(self) -> None:
        self._map.clear()


class AsyncTTLCache:
    def __init__(self, *, max_size: int = 512, ttl_seconds: float = 300.0) -> None:
        self._values = TTLMap(max_size=max_size, ttl_seconds=ttl_seconds)
        self._inflight = {}

    async def get_or_create(self, key, factory):
        missing = object()
        cached = self._values.get(key, missing)
        if cached is not missing:
            return cached
        future = self._inflight.get(key)
        if future is None:
            import asyncio
            future = asyncio.create_task(factory())
            self._inflight[key] = future
        try:
            value = await future
        finally:
            if self._inflight.get(key) is future:
                self._inflight.pop(key, None)
        self._values[key] = value
        return value

    def invalidate(self, key: Optional[Any] = None) -> None:
        if key is None:
            self._values.clear()
            return
        self._values.pop(key, None)
