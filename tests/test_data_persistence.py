import asyncio
import copy
import json
import os
import tempfile
import time
import unittest
from contextlib import ExitStack
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest.mock import AsyncMock, patch

import aiosqlite

from core import data
from core.data import DataManager


class DummyBot:
    def __init__(self):
        self.guilds = []


class GranularPersistenceTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        base = Path(self.temp_dir.name)
        self.patchers = ExitStack()
        self.patchers.enter_context(patch.object(data, "DB_DIR", base))
        self.patchers.enter_context(patch.object(data, "DB_FILE", base / "bot.db"))
        for attribute, filename in (
            ("CONFIG_FILE", "config.json"),
            ("ROLES_FILE", "roles.json"),
            ("PUNISHMENTS_FILE", "punishments.json"),
            ("MOD_STATS_FILE", "mod_stats.json"),
            ("PINGS_FILE", "pings.json"),
            ("MODMAIL_FILE", "modmail.json"),
            ("LOCKDOWN_FILE", "lockdown.json"),
        ):
            self.patchers.enter_context(patch.object(data, attribute, base / filename))
        self.manager = DataManager(DummyBot())
        await self.manager.load_all()

    async def asyncTearDown(self):
        await self.manager.close()
        self.patchers.close()
        self.temp_dir.cleanup()

    async def test_single_case_update_does_not_rewrite_other_cases(self):
        first = await self.manager.add_punishment("1", {"type": "warn", "reason": "First"})
        second = await self.manager.add_punishment("2", {"type": "ban", "duration_minutes": -1, "reason": "Second"})
        second_before = copy.deepcopy(second)

        statements = []
        await self.manager._db.set_trace_callback(statements.append)
        await self.manager.mutate_punishment(
            first["case_id"],
            lambda record: record.update(note="Updated"),
        )
        await self.manager._db.set_trace_callback(None)

        rows = await self.manager._db.execute_fetchall(
            "SELECT case_id, data FROM punishments ORDER BY case_id"
        )
        stored = {int(row["case_id"]): json.loads(row["data"]) for row in rows}
        self.assertEqual(stored[first["case_id"]]["note"], "Updated")
        self.assertEqual(stored[second["case_id"]], second_before)
        self.assertFalse(
            any(
                statement.strip().upper().startswith("DELETE FROM PUNISHMENTS")
                and " WHERE " not in statement.upper()
                for statement in statements
            )
        )

    async def test_failed_commit_rolls_back_and_can_be_retried(self):
        records = [{"role_id": 99, "name": "Test"}]
        original_commit = self.manager._db.commit
        with patch.object(
            self.manager._db,
            "commit",
            AsyncMock(side_effect=RuntimeError("commit failed")),
        ):
            with self.assertRaisesRegex(RuntimeError, "commit failed"):
                await self.manager.set_role_records("42", records)

        rows = await self.manager._db.execute_fetchall("SELECT user_id FROM roles")
        self.assertEqual(rows, [])
        self.assertNotIn("42", self.manager.roles)

        await original_commit()
        await self.manager.set_role_records("42", records)
        rows = await self.manager._db.execute_fetchall("SELECT user_id FROM roles")
        self.assertEqual([row["user_id"] for row in rows], ["42"])

    async def test_concurrent_entity_writes_are_serialized_without_loss(self):
        await asyncio.gather(
            self.manager.mutate_modmail_ticket("1", lambda _ticket: {"status": "open", "thread_id": 101}),
            self.manager.mutate_modmail_ticket("2", lambda _ticket: {"status": "open", "thread_id": 202}),
        )

        rows = await self.manager._db.execute_fetchall(
            "SELECT user_id FROM modmail ORDER BY user_id"
        )
        self.assertEqual([row["user_id"] for row in rows], ["1", "2"])
        self.assertEqual(self.manager.get_modmail_user_id(101), "1")
        self.assertEqual(self.manager.get_modmail_user_id(202), "2")

    async def test_later_failed_write_cannot_be_marked_clean_by_earlier_commit(self):
        first_commit_started = asyncio.Event()
        release_first_commit = asyncio.Event()
        original_commit = self.manager._db.commit
        commit_count = 0

        async def controlled_commit():
            nonlocal commit_count
            commit_count += 1
            if commit_count == 1:
                first_commit_started.set()
                await release_first_commit.wait()
                await original_commit()
                return
            raise RuntimeError("later commit failed")

        with patch.object(self.manager._db, "commit", side_effect=controlled_commit):
            first_write = asyncio.create_task(self.manager.set_role_records("1", [{"role_id": 101}]))
            await first_commit_started.wait()
            second_write = asyncio.create_task(self.manager.set_role_records("2", [{"role_id": 202}]))
            await asyncio.sleep(0)
            release_first_commit.set()
            await first_write
            with self.assertRaisesRegex(RuntimeError, "later commit failed"):
                await second_write

        self.assertEqual(self.manager.roles, {"1": [{"role_id": 101}]})
        await self.manager.set_role_records("2", [{"role_id": 202}])
        rows = await self.manager._db.execute_fetchall(
            "SELECT user_id FROM roles ORDER BY user_id"
        )
        self.assertEqual([row["user_id"] for row in rows], ["1", "2"])

    async def test_failed_config_commit_never_publishes_candidate_state(self):
        before = copy.deepcopy(self.manager.config)
        with patch.object(
            self.manager._db,
            "commit",
            AsyncMock(side_effect=RuntimeError("commit failed")),
        ):
            with self.assertRaisesRegex(RuntimeError, "commit failed"):
                await self.manager.set_config_values(theme_color=0x123456)

        self.assertEqual(self.manager.config, before)
        rows = await self.manager._db.execute_fetchall(
            "SELECT value FROM config WHERE key = 'theme_color'"
        )
        self.assertEqual(rows, [])

    async def test_single_punishment_write_commits_counter_stats_and_case_together(self):
        record = await self.manager.add_punishment(
            "42",
            {"type": "warn", "reason": "Atomic"},
            increment_total_issued=True,
        )
        rows = await self.manager._db.execute_fetchall(
            "SELECT key, value FROM config WHERE key IN ('case_counter', 'stats')"
        )
        stored = {row["key"]: json.loads(row["value"]) for row in rows}
        self.assertEqual(stored["case_counter"], record["case_id"])
        self.assertEqual(stored["stats"]["total_issued"], 1)
        self.assertEqual(self.manager.config["stats"]["total_issued"], 1)

    async def test_case_pagination_and_aggregates_use_database_queries(self):
        for index in range(45):
            await self.manager.add_punishment(
                str(index % 3),
                {"type": "warn" if index % 2 else "ban", "duration_minutes": -1},
            )

        first_page = await self.manager.list_cases_page(page=0, page_size=20)
        last_page = await self.manager.list_cases_page(page=2, page_size=20)
        total, counts = await self.manager.get_case_totals()

        self.assertEqual(len(first_page), 20)
        self.assertEqual(len(last_page), 5)
        self.assertGreater(first_page[0][1]["case_id"], first_page[-1][1]["case_id"])
        self.assertEqual(total, 45)
        self.assertEqual(counts["ban"], 23)
        self.assertEqual(counts["warn"], 22)

        user_case_ids = await self.manager.list_user_case_ids(0, limit=10)
        self.assertEqual(len(user_case_ids), 10)
        self.assertEqual(user_case_ids, sorted(user_case_ids, reverse=True))

    async def test_due_tempban_is_indexed_and_only_cleared_after_confirmation(self):
        issued_at = datetime.now(timezone.utc) - timedelta(hours=2)
        record = await self.manager.add_punishment(
            "42",
            {
                "type": "ban",
                "active": True,
                "duration_minutes": 30,
                "timestamp": issued_at.isoformat(),
            },
        )

        due = await self.manager.get_due_tempbans(now_timestamp=int(time.time()))
        self.assertEqual([(user_id, item["case_id"]) for user_id, item in due], [("42", record["case_id"])])

        self.assertTrue(await self.manager.mark_punishment_inactive(record["case_id"]))
        self.assertFalse(record["active"])
        self.assertEqual(await self.manager.get_due_tempbans(now_timestamp=int(time.time())), [])

    async def test_native_automod_history_is_migrated_bounded_and_pruned(self):
        now = int(time.time())
        self.manager.mod_stats["native_automod"] = {
            "7": {
                "events": [{
                    "timestamp": datetime.fromtimestamp(now - 60, timezone.utc).isoformat(),
                    "rule_id": 10,
                    "rule_name": "Links",
                    "content": "legacy",
                    "matched_keyword": "bad.example",
                }],
                "applied_steps": [],
            }
        }
        await self.manager._migrate_legacy_native_automod_history()
        self.assertEqual(
            await self.manager.count_recent_native_automod_hits(
                user_id=7,
                rule_id=10,
                rule_name="Links",
                window_minutes=60,
                now_timestamp=now,
            ),
            1,
        )
        step = {
            "threshold": 2,
            "window_minutes": 60,
            "punishment_type": "timeout",
            "duration_minutes": 30,
        }
        await self.manager.record_native_automod_step_application(
            user_id=7,
            rule_id=10,
            rule_name="Links",
            step=step,
            occurred_at=now,
        )
        self.assertTrue(
            await self.manager.has_recent_native_automod_step_application(
                user_id=7,
                rule_id=10,
                rule_name="Links",
                threshold=2,
                window_minutes=60,
                now_timestamp=now,
            )
        )

        for offset in range(105):
            await self.manager.record_native_automod_event(
                user_id=8,
                rule_id=10,
                rule_name="Links",
                content=str(offset),
                matched_keyword=None,
                occurred_at=now + offset,
            )
        async with self.manager._db.execute(
            "SELECT COUNT(*) AS total FROM native_automod_events WHERE user_id = '8'"
        ) as cursor:
            self.assertEqual((await cursor.fetchone())["total"], 100)

        await self.manager.record_native_automod_event(
            user_id=9,
            rule_id=10,
            rule_name="Links",
            content="expired",
            matched_keyword=None,
            occurred_at=now - 31 * 86400,
        )
        self.assertGreaterEqual(
            await self.manager.prune_native_automod_history(now_timestamp=now),
            1,
        )

    async def test_additive_migration_creates_backup_and_indexes_old_cases(self):
        await self.manager.close()
        db_path = Path(data.DB_FILE)
        db_path.unlink()
        old_db = await aiosqlite.connect(db_path)
        await old_db.execute(
            "CREATE TABLE punishments (case_id INTEGER PRIMARY KEY, user_id TEXT NOT NULL, data TEXT NOT NULL)"
        )
        record = {
            "case_id": 1,
            "type": "ban",
            "active": True,
            "duration_minutes": 60,
            "timestamp": (datetime.now(timezone.utc) - timedelta(hours=2)).isoformat(),
        }
        await old_db.execute(
            "INSERT INTO punishments(case_id, user_id, data) VALUES (1, '42', ?)",
            (json.dumps(record),),
        )
        await old_db.commit()
        await old_db.close()

        backup_dir = db_path.parent / "backups"
        backup_dir.mkdir()
        for index in range(6):
            old_backup = backup_dir / f"bot-v0-old-{index}.db"
            old_backup.write_bytes(b"old")
            os.utime(old_backup, (index + 1, index + 1))

        self.manager = DataManager(DummyBot())
        await self.manager.load_all()
        columns = await self.manager._db.execute_fetchall("PRAGMA table_info(punishments)")
        column_names = {row["name"] for row in columns}
        self.assertTrue({"action_type", "active", "expires_at"}.issubset(column_names))
        async with self.manager._db.execute(
            "SELECT action_type, active, expires_at FROM punishments WHERE case_id = 1"
        ) as cursor:
            migrated = await cursor.fetchone()
        self.assertEqual(migrated["action_type"], "ban")
        self.assertEqual(migrated["active"], 1)
        self.assertIsNotNone(migrated["expires_at"])
        due = await self.manager.get_due_tempbans(now_timestamp=int(time.time()))
        self.assertEqual([(user_id, item["case_id"]) for user_id, item in due], [("42", 1)])
        backups = list(backup_dir.glob("bot-v0-*.db"))
        self.assertEqual(len(backups), 5)
        migration_backup = next(backup for backup in backups if "old" not in backup.name)
        backup_db = await aiosqlite.connect(migration_backup)
        try:
            backup_columns = await backup_db.execute_fetchall("PRAGMA table_info(punishments)")
        finally:
            await backup_db.close()
        self.assertNotIn("action_type", {column[1] for column in backup_columns})


if __name__ == "__main__":
    unittest.main()
