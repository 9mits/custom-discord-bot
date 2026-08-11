"""Stage-gate benchmark for constant-time punishment writes.

Runs entirely in a temporary directory and exits non-zero when p95 exceeds the
approved 25 ms target. It is intentionally separate from CI unit tests so host
load cannot make the deterministic suite flaky.
"""

import asyncio
import json
import statistics
import sys
import tempfile
import time
from contextlib import ExitStack
from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import patch

PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from core import data
from core.data import DataManager


CASE_COUNT = 50_000
SAMPLE_COUNT = 100
TARGET_P95_MS = 25.0


class DummyBot:
    guilds = []


async def benchmark() -> float:
    with tempfile.TemporaryDirectory() as temp_dir, ExitStack() as patches:
        base = Path(temp_dir)
        patches.enter_context(patch.object(data, "DB_DIR", base))
        patches.enter_context(patch.object(data, "DB_FILE", base / "bot.db"))
        for attribute, filename in (
            ("CONFIG_FILE", "config.json"),
            ("ROLES_FILE", "roles.json"),
            ("PUNISHMENTS_FILE", "punishments.json"),
            ("MOD_STATS_FILE", "mod_stats.json"),
            ("PINGS_FILE", "pings.json"),
            ("MODMAIL_FILE", "modmail.json"),
            ("LOCKDOWN_FILE", "lockdown.json"),
        ):
            patches.enter_context(patch.object(data, attribute, base / filename))

        manager = DataManager(DummyBot())
        await manager.load_all()
        timestamp = datetime.now(timezone.utc).isoformat()
        rows = []
        for case_id in range(1, CASE_COUNT + 1):
            user_id = str(case_id % 10_000)
            record = {
                "case_id": case_id,
                "type": "warn",
                "active": False,
                "duration_minutes": 0,
                "timestamp": timestamp,
                "reason": "Benchmark",
            }
            manager.punishments.setdefault(user_id, []).append(record)
            manager._index_case_record(user_id, record)
            rows.append((case_id, user_id, json.dumps(record), "warn", 0, None))

        async with manager._save_lock:
            await manager._upsert_punishment_rows(manager._db, rows)
            await manager._db.commit()

        target = manager.case_index[CASE_COUNT][1]
        durations = []
        for sample in range(SAMPLE_COUNT):
            target["note"] = f"sample-{sample}"
            started = time.perf_counter()
            await manager.save_punishments(case_ids=[CASE_COUNT])
            durations.append((time.perf_counter() - started) * 1000)

        await manager.close()
        return statistics.quantiles(durations, n=100, method="inclusive")[94]


def main() -> None:
    p95_ms = asyncio.run(benchmark())
    print(f"50,000-case single-row write p95: {p95_ms:.2f} ms")
    if p95_ms > TARGET_P95_MS:
        raise SystemExit(f"p95 exceeded {TARGET_P95_MS:.0f} ms target")


if __name__ == "__main__":
    main()
