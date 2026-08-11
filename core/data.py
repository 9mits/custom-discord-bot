"""
mbx_data.py — DataManager, AntiAbuseSystem, path constants, and low-level I/O helpers.
"""
from __future__ import annotations

import asyncio
import copy
import json
import logging
import os
import tempfile
import time
from collections import deque
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Sequence, Tuple

import aiosqlite

from core.constants import (
    DEFAULT_GUILD_ID,
    DEFAULT_MAX_UNREAD_PINGS,
    DEFAULT_ROLE_ADMIN,
    DEFAULT_ANCHOR_ROLE_ID,
    DEFAULT_ROLE_COMMUNITY_MANAGER,
    DEFAULT_ROLE_MOD,
    DEFAULT_ROLE_OWNER,
    DEFAULT_RULES,
    DEFAULT_SPAM_ROLE_ID,
    DEFAULT_ARCHIVE_CAT_ID,
    TOKEN_ENV_VARS,
)
from core.services import (
    DEFAULT_CANNED_REPLIES,
    DEFAULT_NATIVE_AUTOMOD_SETTINGS,
    DEFAULT_SCHEMA_VERSION,
    normalize_case_record,
    invalidate_native_automod_settings,
    run_schema_migrations,
)
from core.runtime import TTLMap

logger = logging.getLogger("MGXBot")

# ----------------- PATHS -----------------
# BOT_DATA_DIR can be set per-instance in .env to keep databases separate.
# Defaults to the classic "database/" folder so existing installs are unaffected.
BASE_DIR = Path(__file__).resolve().parent.parent
DB_DIR = Path(os.environ.get("BOT_DATA_DIR", str(BASE_DIR / "database")))
ROLES_FILE = DB_DIR / "roles.json"
CONFIG_FILE = DB_DIR / "config.json"
PUNISHMENTS_FILE = DB_DIR / "punishments.json"
MOD_STATS_FILE = DB_DIR / "mod_stats.json"
PINGS_FILE = DB_DIR / "pings.json"
LOCKDOWN_FILE = DB_DIR / "lockdown.json"
MODMAIL_FILE = DB_DIR / "modmail.json"
DB_FILE = DB_DIR / "bot.db"
# -----------------------------------------

_STORAGE_SCHEMA_VERSION = 2
_BACKUP_RETENTION = 5
_NATIVE_AUTOMOD_RETENTION_DAYS = 30
_NATIVE_AUTOMOD_PER_USER_LIMIT = 100

_CREATE_TABLES_SQL = """
CREATE TABLE IF NOT EXISTS config (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS punishments (
    case_id    INTEGER PRIMARY KEY,
    user_id    TEXT    NOT NULL,
    data       TEXT    NOT NULL,
    action_type TEXT,
    active      INTEGER NOT NULL DEFAULT 0,
    expires_at  INTEGER
);
CREATE INDEX IF NOT EXISTS idx_punishments_user ON punishments(user_id);
CREATE INDEX IF NOT EXISTS idx_punishments_user_case ON punishments(user_id, case_id DESC);

CREATE TABLE IF NOT EXISTS roles (
    user_id TEXT PRIMARY KEY,
    data    TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS mod_stats (
    user_id TEXT PRIMARY KEY,
    data    TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS pings (
    user_id TEXT PRIMARY KEY,
    data    TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS modmail (
    user_id TEXT PRIMARY KEY,
    data    TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS lockdown (
    channel_id TEXT PRIMARY KEY,
    data       TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS exports (
    export_id     INTEGER PRIMARY KEY AUTOINCREMENT,
    created_at    TEXT    NOT NULL,
    requester_id  TEXT    NOT NULL,
    title         TEXT    NOT NULL,
    filename      TEXT    NOT NULL,
    message_count INTEGER NOT NULL,
    content       BLOB    NOT NULL
);

CREATE TABLE IF NOT EXISTS storage_meta (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS native_automod_events (
    event_id        INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id         TEXT    NOT NULL,
    rule_id         TEXT    NOT NULL,
    rule_name       TEXT    NOT NULL,
    occurred_at     INTEGER NOT NULL,
    content         TEXT    NOT NULL,
    matched_keyword TEXT
);
CREATE INDEX IF NOT EXISTS idx_native_automod_events_lookup
    ON native_automod_events(user_id, rule_id, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_native_automod_events_age
    ON native_automod_events(occurred_at);

CREATE TABLE IF NOT EXISTS native_automod_steps (
    step_id          INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id          TEXT    NOT NULL,
    rule_id          TEXT    NOT NULL,
    rule_name        TEXT    NOT NULL,
    occurred_at      INTEGER NOT NULL,
    threshold_value  INTEGER NOT NULL,
    window_minutes   INTEGER NOT NULL,
    punishment_type  TEXT    NOT NULL,
    duration_minutes INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_native_automod_steps_lookup
    ON native_automod_steps(user_id, rule_id, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_native_automod_steps_age
    ON native_automod_steps(occurred_at);
"""


def read_json_file(path: Path, default: Any) -> Any:
    if path.exists():
        try:
            with path.open("r", encoding="utf-8") as file:
                return json.load(file)
        except Exception as exc:
            logger.warning("Failed to read %s: %s", path.name, exc)
    return default


def parse_iso_datetime(value: Optional[str]) -> Optional[datetime]:
    if not value or not isinstance(value, str):
        return None
    try:
        return datetime.fromisoformat(value)
    except ValueError:
        return None


def resolve_bot_token() -> str:
    bootstrap_config = read_json_file(CONFIG_FILE, {})
    env_var_order: List[str] = []

    configured_env_var = bootstrap_config.get("token_env_var")
    if isinstance(configured_env_var, str) and configured_env_var.strip():
        env_var_order.append(configured_env_var.strip())

    for env_var in TOKEN_ENV_VARS:
        if env_var not in env_var_order:
            env_var_order.append(env_var)

    for env_var in env_var_order:
        token = os.getenv(env_var)
        if token:
            return token.strip()

    raise RuntimeError(
        "Discord bot token is not configured. Set one of the supported environment variables "
        f"({', '.join(env_var_order)})."
    )


# ----------------- Storage helpers -----------------
class DataManager:
    def __init__(self, bot):
        self.bot = bot
        self.config: dict = {}
        self.roles: dict = {}
        self.punishments: dict = {}
        self.case_index: Dict[int, Tuple[str, dict]] = {}
        self.mod_stats: dict = {}
        self.pings: dict = {}
        self.modmail: dict = {}
        self.modmail_threads: Dict[int, str] = {}
        self.modmail_user_threads: Dict[str, int] = {}
        self.lockdown: dict = {}

        self._dirty_config = False
        self._dirty_roles = False
        self._dirty_punishments = False
        self._dirty_stats = False
        self._dirty_pings = False
        self._dirty_modmail = False
        self._dirty_lockdown = False
        self._dirty_generations = {
            "config": 0,
            "roles": 0,
            "punishments": 0,
            "stats": 0,
            "pings": 0,
            "modmail": 0,
            "lockdown": 0,
        }
        self._save_lock = asyncio.Lock()
        self._db: Optional[aiosqlite.Connection] = None

    def _mark_section_dirty(self, section: str) -> int:
        self._dirty_generations[section] += 1
        setattr(self, f"_dirty_{section}", True)
        return self._dirty_generations[section]

    def _clear_section_if_current(self, section: str, generation: int) -> None:
        if self._dirty_generations[section] == generation:
            setattr(self, f"_dirty_{section}", False)

    async def _commit(self, db: aiosqlite.Connection) -> None:
        started = time.perf_counter()
        try:
            await db.commit()
        finally:
            metrics = getattr(self.bot, "metrics", None)
            if metrics is not None:
                metrics.record_database_write(time.perf_counter() - started)

    # ------------------------------------------------------------------
    # Internal: legacy JSON helpers (kept for migration and resolve_bot_token)
    # ------------------------------------------------------------------

    def _load_json(self, path, default):
        return read_json_file(Path(path), default)

    def _save_json_sync(self, path, data):
        path = Path(path)
        path.parent.mkdir(parents=True, exist_ok=True)
        temp_name = None
        try:
            with tempfile.NamedTemporaryFile(
                "w",
                encoding="utf-8",
                dir=path.parent,
                delete=False,
            ) as temp_file:
                json.dump(data, temp_file, indent=2, ensure_ascii=False)
                temp_file.write("\n")
                temp_name = temp_file.name
            os.replace(temp_name, path)
        finally:
            if temp_name and os.path.exists(temp_name):
                try:
                    os.remove(temp_name)
                except OSError:
                    pass

    async def _save_json(self, path, data):
        await asyncio.to_thread(self._save_json_sync, path, data)

    # ------------------------------------------------------------------
    # Internal: SQLite helpers
    # ------------------------------------------------------------------

    async def _open_db(self) -> aiosqlite.Connection:
        db_path = Path(DB_FILE)
        db_path.parent.mkdir(parents=True, exist_ok=True)
        existed = db_path.exists() and db_path.stat().st_size > 0
        db = await aiosqlite.connect(db_path)
        db.row_factory = aiosqlite.Row
        # WAL keeps reads non-blocking during writes; NORMAL avoids an fsync
        # on every commit (durable enough for a bot, much faster on writes).
        await db.execute("PRAGMA journal_mode=WAL")
        await db.execute("PRAGMA synchronous=NORMAL")
        await db.execute("PRAGMA temp_store=MEMORY")
        try:
            async with db.execute("PRAGMA user_version") as cursor:
                row = await cursor.fetchone()
            storage_version = int(row[0] if row else 0)
            if existed and storage_version < _STORAGE_SCHEMA_VERSION:
                await self._backup_database(db, storage_version)

            await db.executescript(_CREATE_TABLES_SQL)
            await self._migrate_schema_columns(db)
            await db.execute(f"PRAGMA user_version = {_STORAGE_SCHEMA_VERSION}")
            await self._commit(db)
        except Exception:
            await db.rollback()
            await db.close()
            raise
        return db

    async def _backup_database(self, db: aiosqlite.Connection, storage_version: int) -> Path:
        backup_dir = Path(DB_FILE).parent / "backups"
        backup_dir.mkdir(parents=True, exist_ok=True)
        stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
        backup_path = backup_dir / f"bot-v{storage_version}-{stamp}.db"
        suffix = 1
        while backup_path.exists():
            backup_path = backup_dir / f"bot-v{storage_version}-{stamp}-{suffix}.db"
            suffix += 1

        target = await aiosqlite.connect(backup_path)
        try:
            await db.backup(target)
            await target.commit()
        finally:
            await target.close()

        backups = sorted(
            backup_dir.glob("bot-v*.db"),
            key=lambda path: path.stat().st_mtime,
            reverse=True,
        )
        for stale in backups[_BACKUP_RETENTION:]:
            try:
                stale.unlink()
            except OSError as exc:
                logger.warning("Could not prune old database backup %s: %s", stale.name, exc)
        logger.info("Storage migration backup created at %s", backup_path)
        return backup_path

    async def _migrate_schema_columns(self, db: aiosqlite.Connection):
        """Apply additive, idempotent storage migrations.

        The roles table is keyed by user_id but the column was historically
        misnamed `role_id`; rename it in place if an old DB still has it.
        """
        async with db.execute("PRAGMA table_info(roles)") as cursor:
            columns = {row["name"] async for row in cursor}
        if "role_id" in columns and "user_id" not in columns:
            await db.execute("ALTER TABLE roles RENAME COLUMN role_id TO user_id")
            logger.info("Migration: renamed roles.role_id column to user_id")

        async with db.execute("PRAGMA table_info(punishments)") as cursor:
            punishment_columns = {row["name"] async for row in cursor}
        for column_name, definition in (
            ("action_type", "TEXT"),
            ("active", "INTEGER NOT NULL DEFAULT 0"),
            ("expires_at", "INTEGER"),
        ):
            if column_name not in punishment_columns:
                await db.execute(f"ALTER TABLE punishments ADD COLUMN {column_name} {definition}")
                logger.info("Migration: added punishments.%s", column_name)

        await db.execute(
            "CREATE INDEX IF NOT EXISTS idx_punishments_expiry ON punishments(active, expires_at)"
        )
        await self._backfill_punishment_columns(db)

    async def _backfill_punishment_columns(self, db: aiosqlite.Connection) -> None:
        rows = await db.execute_fetchall(
            "SELECT case_id, data FROM punishments WHERE action_type IS NULL"
        )
        updates = []
        for row in rows:
            try:
                record = json.loads(row["data"])
            except (TypeError, ValueError):
                continue
            action_type, active, expires_at = self._punishment_storage_fields(record)
            updates.append((action_type, active, expires_at, int(row["case_id"])))
        if updates:
            await db.executemany(
                "UPDATE punishments SET action_type = ?, active = ?, expires_at = ? WHERE case_id = ?",
                updates,
            )
            logger.info("Migration: indexed %d punishment records", len(updates))

    async def _db_conn(self) -> aiosqlite.Connection:
        if self._db is None:
            self._db = await self._open_db()
        return self._db

    @staticmethod
    def _epoch_seconds(value: Optional[str]) -> Optional[int]:
        parsed = parse_iso_datetime(value)
        if parsed is None:
            return None
        if parsed.tzinfo is None:
            parsed = parsed.replace(tzinfo=timezone.utc)
        return int(parsed.timestamp())

    def _punishment_storage_fields(self, record: dict) -> Tuple[str, int, Optional[int]]:
        action_type = str(record.get("type") or "warn").lower()
        active = 1 if bool(record.get("active", False)) else 0
        try:
            duration_minutes = int(record.get("duration_minutes", 0) or 0)
        except (TypeError, ValueError):
            duration_minutes = 0
        issued_at = self._epoch_seconds(record.get("timestamp"))
        expires_at = None
        if active and duration_minutes > 0 and issued_at is not None:
            expires_at = issued_at + duration_minutes * 60
        return action_type, active, expires_at

    # ------------------------------------------------------------------
    # Internal: migration from JSON files → SQLite
    # ------------------------------------------------------------------

    async def _migrate_json_to_db(self, db: aiosqlite.Connection):
        """Import legacy JSON files into SQLite once, then rename them to .bak."""

        # config.json → config table (one row per key)
        if CONFIG_FILE.exists():
            try:
                raw = read_json_file(CONFIG_FILE, {})
                if isinstance(raw, dict):
                    for k, v in raw.items():
                        await db.execute(
                            "INSERT OR IGNORE INTO config(key, value) VALUES (?, ?)",
                            (k, json.dumps(v)),
                        )
                    logger.info("Migration: imported config.json into SQLite")
                CONFIG_FILE.rename(CONFIG_FILE.with_suffix(".json.bak"))
            except Exception as exc:
                logger.warning("Migration: failed to import config.json: %s", exc)

        # punishments.json → punishments table
        if PUNISHMENTS_FILE.exists():
            try:
                raw = read_json_file(PUNISHMENTS_FILE, {})
                if isinstance(raw, dict):
                    for user_id, records in raw.items():
                        if not isinstance(records, list):
                            continue
                        for record in records:
                            if not isinstance(record, dict):
                                continue
                            case_id = record.get("case_id")
                            if not isinstance(case_id, int) or case_id <= 0:
                                continue
                            action_type, active, expires_at = self._punishment_storage_fields(record)
                            await db.execute(
                                "INSERT OR IGNORE INTO punishments"
                                "(case_id, user_id, data, action_type, active, expires_at) "
                                "VALUES (?, ?, ?, ?, ?, ?)",
                                (case_id, str(user_id), json.dumps(record), action_type, active, expires_at),
                            )
                    logger.info("Migration: imported punishments.json into SQLite")
                PUNISHMENTS_FILE.rename(PUNISHMENTS_FILE.with_suffix(".json.bak"))
            except Exception as exc:
                logger.warning("Migration: failed to import punishments.json: %s", exc)

        # roles.json → roles table
        if ROLES_FILE.exists():
            try:
                raw = read_json_file(ROLES_FILE, {})
                if isinstance(raw, dict):
                    for user_id, data in raw.items():
                        await db.execute(
                            "INSERT OR IGNORE INTO roles(user_id, data) VALUES (?, ?)",
                            (str(user_id), json.dumps(data)),
                        )
                    logger.info("Migration: imported roles.json into SQLite")
                ROLES_FILE.rename(ROLES_FILE.with_suffix(".json.bak"))
            except Exception as exc:
                logger.warning("Migration: failed to import roles.json: %s", exc)

        # mod_stats.json → mod_stats table
        if MOD_STATS_FILE.exists():
            try:
                raw = read_json_file(MOD_STATS_FILE, {})
                if isinstance(raw, dict):
                    for user_id, data in raw.items():
                        await db.execute(
                            "INSERT OR IGNORE INTO mod_stats(user_id, data) VALUES (?, ?)",
                            (str(user_id), json.dumps(data)),
                        )
                    logger.info("Migration: imported mod_stats.json into SQLite")
                MOD_STATS_FILE.rename(MOD_STATS_FILE.with_suffix(".json.bak"))
            except Exception as exc:
                logger.warning("Migration: failed to import mod_stats.json: %s", exc)

        # pings.json → pings table
        if PINGS_FILE.exists():
            try:
                raw = read_json_file(PINGS_FILE, {})
                if isinstance(raw, dict):
                    for user_id, data in raw.items():
                        await db.execute(
                            "INSERT OR IGNORE INTO pings(user_id, data) VALUES (?, ?)",
                            (str(user_id), json.dumps(data)),
                        )
                    logger.info("Migration: imported pings.json into SQLite")
                PINGS_FILE.rename(PINGS_FILE.with_suffix(".json.bak"))
            except Exception as exc:
                logger.warning("Migration: failed to import pings.json: %s", exc)

        # modmail.json → modmail table
        if MODMAIL_FILE.exists():
            try:
                raw = read_json_file(MODMAIL_FILE, {})
                if isinstance(raw, dict):
                    for user_id, data in raw.items():
                        await db.execute(
                            "INSERT OR IGNORE INTO modmail(user_id, data) VALUES (?, ?)",
                            (str(user_id), json.dumps(data)),
                        )
                    logger.info("Migration: imported modmail.json into SQLite")
                MODMAIL_FILE.rename(MODMAIL_FILE.with_suffix(".json.bak"))
            except Exception as exc:
                logger.warning("Migration: failed to import modmail.json: %s", exc)

        # lockdown.json → lockdown table
        if LOCKDOWN_FILE.exists():
            try:
                raw = read_json_file(LOCKDOWN_FILE, {})
                if isinstance(raw, dict):
                    for channel_id, data in raw.items():
                        await db.execute(
                            "INSERT OR IGNORE INTO lockdown(channel_id, data) VALUES (?, ?)",
                            (str(channel_id), json.dumps(data)),
                        )
                    logger.info("Migration: imported lockdown.json into SQLite")
                LOCKDOWN_FILE.rename(LOCKDOWN_FILE.with_suffix(".json.bak"))
            except Exception as exc:
                logger.warning("Migration: failed to import lockdown.json: %s", exc)

        await self._commit(db)

    # ------------------------------------------------------------------
    # Internal: in-memory helpers (unchanged from original)
    # ------------------------------------------------------------------

    def _normalize_positive_int(self, value: Any, default: int, *, minimum: int = 1, maximum: Optional[int] = None) -> int:
        try:
            normalized = int(value)
        except (TypeError, ValueError):
            normalized = default
        if maximum is not None:
            normalized = min(normalized, maximum)
        return max(minimum, normalized)

    def _parse_optional_int(self, value: Any) -> Optional[int]:
        try:
            return int(value)
        except (TypeError, ValueError):
            return None

    def _rebuild_modmail_index(self):
        self.modmail_threads = {}
        self.modmail_user_threads = {}
        for user_id, ticket in self.modmail.items():
            thread_id = self._parse_optional_int(ticket.get("thread_id"))
            if thread_id is not None:
                self.modmail_threads[thread_id] = user_id
                self.modmail_user_threads[user_id] = thread_id

    def _update_modmail_index(self, user_ids: Iterable[Any]) -> None:
        for raw_user_id in user_ids:
            user_id = str(raw_user_id)
            old_thread_id = self.modmail_user_threads.pop(user_id, None)
            if old_thread_id is not None:
                self.modmail_threads.pop(old_thread_id, None)
            ticket = self.modmail.get(user_id)
            if not isinstance(ticket, dict):
                continue
            thread_id = self._parse_optional_int(ticket.get("thread_id"))
            if thread_id is not None:
                self.modmail_threads[thread_id] = user_id
                self.modmail_user_threads[user_id] = thread_id

    def _rebuild_case_index(self):
        self.case_index = {}
        for user_id, records in self.punishments.items():
            if not isinstance(records, list):
                continue
            for record in records:
                if not isinstance(record, dict):
                    continue
                self._index_case_record(user_id, record)

    def _index_case_record(self, user_id: str, record: dict):
        case_id = record.get("case_id")
        if isinstance(case_id, int) and case_id > 0:
            self.case_index[case_id] = (user_id, record)

    def _ensure_dict(self, value: Any, path: Path) -> dict:
        if isinstance(value, dict):
            return value
        logger.warning("Expected %s to contain a JSON object. Resetting to defaults.", path.name)
        return {}

    def _ensure_list(self, value: Any, path: Path) -> list:
        if isinstance(value, list):
            return value
        logger.warning("Expected %s to contain a JSON array. Resetting to defaults.", path.name)
        return []

    # ------------------------------------------------------------------
    # Internal: load sections from SQLite into memory
    # ------------------------------------------------------------------

    async def _load_config_from_db(self, db: aiosqlite.Connection) -> dict:
        config = {}
        async with db.execute("SELECT key, value FROM config") as cursor:
            async for row in cursor:
                try:
                    config[row["key"]] = json.loads(row["value"])
                except Exception:
                    config[row["key"]] = row["value"]
        return config

    async def _load_punishments_from_db(self, db: aiosqlite.Connection) -> dict:
        punishments: dict = {}
        async with db.execute("SELECT user_id, data FROM punishments") as cursor:
            async for row in cursor:
                try:
                    record = json.loads(row["data"])
                except Exception:
                    continue
                uid = row["user_id"]
                punishments.setdefault(uid, []).append(record)
        return punishments

    async def _load_simple_dict_from_db(self, db: aiosqlite.Connection, table: str, key_col: str) -> dict:
        result = {}
        async with db.execute(f"SELECT {key_col}, data FROM {table}") as cursor:
            async for row in cursor:
                try:
                    result[row[key_col]] = json.loads(row["data"])
                except Exception:
                    continue
        return result

    # ------------------------------------------------------------------
    # Internal: row-level writes
    # ------------------------------------------------------------------

    async def _sync_mapping_rows(
        self,
        db: aiosqlite.Connection,
        *,
        table: str,
        key_column: str,
        values: dict,
        keys: Optional[Iterable[Any]] = None,
        replace: bool = False,
    ) -> None:
        allowed = {
            ("config", "key", "value"),
            ("roles", "user_id", "data"),
            ("mod_stats", "user_id", "data"),
            ("pings", "user_id", "data"),
            ("modmail", "user_id", "data"),
            ("lockdown", "channel_id", "data"),
        }
        value_column = "value" if table == "config" else "data"
        if (table, key_column, value_column) not in allowed:
            raise ValueError(f"Unsupported storage mapping: {table}.{key_column}")

        normalized_values = {str(key): value for key, value in values.items()}
        target_keys = set(normalized_values) if keys is None else {str(key) for key in keys}
        rows = [
            (key, json.dumps(normalized_values[key]))
            for key in target_keys
            if key in normalized_values
        ]
        if rows:
            await db.executemany(
                f"INSERT INTO {table}({key_column}, {value_column}) VALUES (?, ?) "
                f"ON CONFLICT({key_column}) DO UPDATE SET {value_column} = excluded.{value_column}",
                rows,
            )

        delete_keys = {key for key in target_keys if key not in normalized_values}
        if replace:
            existing = await db.execute_fetchall(f"SELECT {key_column} FROM {table}")
            delete_keys.update(str(row[key_column]) for row in existing if str(row[key_column]) not in normalized_values)
        if delete_keys:
            await db.executemany(
                f"DELETE FROM {table} WHERE {key_column} = ?",
                [(key,) for key in delete_keys],
            )

    def _punishment_rows(self, case_ids: Optional[Iterable[int]] = None) -> List[tuple]:
        selected = None if case_ids is None else {int(case_id) for case_id in case_ids}
        rows = []
        sources: Iterable[Tuple[str, Iterable[dict]]]
        if selected is not None:
            selected_records = []
            for case_id in selected:
                entry = self.case_index.get(case_id)
                if entry is not None:
                    selected_records.append((entry[0], (entry[1],)))
            sources = selected_records
        else:
            sources = self.punishments.items()

        for user_id, records in sources:
            if not isinstance(records, list):
                records = list(records)
            for record in records:
                if not isinstance(record, dict):
                    continue
                case_id = record.get("case_id")
                if not isinstance(case_id, int) or case_id <= 0:
                    continue
                if selected is not None and case_id not in selected:
                    continue
                action_type, active, expires_at = self._punishment_storage_fields(record)
                rows.append(
                    (
                        case_id,
                        str(user_id),
                        json.dumps(record),
                        action_type,
                        active,
                        expires_at,
                    )
                )
        return rows

    async def _upsert_punishment_rows(self, db: aiosqlite.Connection, rows: Sequence[tuple]) -> None:
        if not rows:
            return
        await db.executemany(
            "INSERT INTO punishments"
            "(case_id, user_id, data, action_type, active, expires_at) VALUES (?, ?, ?, ?, ?, ?) "
            "ON CONFLICT(case_id) DO UPDATE SET "
            "user_id = excluded.user_id, data = excluded.data, action_type = excluded.action_type, "
            "active = excluded.active, expires_at = excluded.expires_at",
            rows,
        )

    async def _replace_punishments_from_memory(self, db: aiosqlite.Connection) -> None:
        rows = self._punishment_rows()
        await self._upsert_punishment_rows(db, rows)
        memory_ids = {int(row[0]) for row in rows}
        existing = await db.execute_fetchall("SELECT case_id FROM punishments")
        stale_ids = [int(row["case_id"]) for row in existing if int(row["case_id"]) not in memory_ids]
        if stale_ids:
            await db.executemany(
                "DELETE FROM punishments WHERE case_id = ?",
                [(case_id,) for case_id in stale_ids],
            )

    # ------------------------------------------------------------------
    # Public: load / save
    # ------------------------------------------------------------------

    async def load_all(self):
        db = await self._open_db()
        self._db = db

        # Run JSON → SQLite migration for any legacy files that still exist
        any_legacy = any(
            p.exists()
            for p in (
                CONFIG_FILE, PUNISHMENTS_FILE, ROLES_FILE, MOD_STATS_FILE,
                PINGS_FILE, MODMAIL_FILE, LOCKDOWN_FILE,
            )
        )
        if any_legacy:
            await self._migrate_json_to_db(db)

        # ---- config ----
        self.config = await self._load_config_from_db(db)

        had_general_log_channel = "general_log_channel_id" in self.config
        legacy_log_channel_id = self.config.get("log_channel_id")

        defaults = {
            "min_boosts_for_role": 0, "whitelist": {}, "punishment_rules": DEFAULT_RULES,
            "mod_roles": [], "stats": {"total_issued": 0, "cases_cleared": 0},
            "locked_channels": {}, "archived_channels": {},
            "cr_whitelist_users": {}, "cr_whitelist_roles": {}, "cr_blacklist_users": [], "cr_blacklist_roles": [],
            "security": {"max_actions_per_min": 10},
            "native_automod": DEFAULT_NATIVE_AUTOMOD_SETTINGS,
            "immunity_list": [], "debug": {},
            "token_env_var": "DISCORD_BOT_TOKEN",
            "case_counter": 0,
            "schema_version": DEFAULT_SCHEMA_VERSION,
            "max_unread_pings_per_user": DEFAULT_MAX_UNREAD_PINGS,
            "feature_flags": {},
            "modmail_canned_replies": DEFAULT_CANNED_REPLIES,
            "modmail_sla_minutes": 60,
            "dm_modmail_panel_cooldown_minutes": 30,
            "guild_id": DEFAULT_GUILD_ID,
            "general_log_channel_id": 0,
            "punishment_log_channel_id": 0,
            "automod_log_channel_id": 0,
            "automod_report_channel_id": 0,
            "role_owner": DEFAULT_ROLE_OWNER,
            "role_admin": DEFAULT_ROLE_ADMIN,
            "role_mod": DEFAULT_ROLE_MOD,
            "role_community_manager": DEFAULT_ROLE_COMMUNITY_MANAGER,
            "role_anchor": DEFAULT_ANCHOR_ROLE_ID,
            "category_archive": DEFAULT_ARCHIVE_CAT_ID,
            "role_mention_spam_target": DEFAULT_SPAM_ROLE_ID,
        }
        for k, v in defaults.items():
            if k not in self.config:
                self.config[k] = copy.deepcopy(v)
                self._dirty_config = True

        if not had_general_log_channel and legacy_log_channel_id:
            self.config["general_log_channel_id"] = legacy_log_channel_id
            self._dirty_config = True

        # ---- other sections ----
        raw_roles = await self._load_simple_dict_from_db(db, "roles", "user_id")
        # Migrate single-dict entries to lists
        if any(not isinstance(value, list) for value in raw_roles.values()):
            self._dirty_roles = True
        self.roles = {
            uid: (v if isinstance(v, list) else [v])
            for uid, v in raw_roles.items()
        }
        self.punishments = await self._load_punishments_from_db(db)
        self._normalize_punishments()
        self.mod_stats = await self._load_simple_dict_from_db(db, "mod_stats", "user_id")
        await self._migrate_legacy_native_automod_history()
        await self.prune_native_automod_history()
        self.pings = await self._load_simple_dict_from_db(db, "pings", "user_id")
        self.modmail = await self._load_simple_dict_from_db(db, "modmail", "user_id")

        migrated, migration_notes = run_schema_migrations(self.config, self.punishments, self.modmail)
        if migrated:
            self._dirty_config = True
            self._dirty_punishments = True
            self._dirty_modmail = True
            for note in migration_notes:
                logger.info("Migration: %s", note)

        self.lockdown = await self._load_simple_dict_from_db(db, "lockdown", "channel_id")
        self._rebuild_case_index()
        self._rebuild_modmail_index()

        # Flush any defaults / migrations written during load
        if any(
            [
                self._dirty_config,
                self._dirty_roles,
                self._dirty_punishments,
                self._dirty_stats,
                self._dirty_pings,
                self._dirty_modmail,
                self._dirty_lockdown,
            ]
        ):
            await self.save_all(force=False)

    async def save_all(self, force: bool = False):
        """Synchronize migration/import state without whole-table deletes.

        Runtime callers should use the entity-level methods below. This method is
        retained for startup migrations and explicit recovery only.
        """
        async with self._save_lock:
            db = await self._db_conn()
            sections = {
                "config": self._dirty_config or force,
                "roles": self._dirty_roles or force,
                "punishments": self._dirty_punishments or force,
                "stats": self._dirty_stats or force,
                "pings": self._dirty_pings or force,
                "modmail": self._dirty_modmail or force,
                "lockdown": self._dirty_lockdown or force,
            }
            generations = dict(self._dirty_generations)
            if not any(sections.values()):
                return
            try:
                if sections["config"]:
                    await self._sync_mapping_rows(
                        db, table="config", key_column="key", values=self.config, replace=True
                    )
                if sections["roles"]:
                    await self._sync_mapping_rows(
                        db, table="roles", key_column="user_id", values=self.roles, replace=True
                    )
                if sections["punishments"]:
                    self._rebuild_case_index()
                    await self._replace_punishments_from_memory(db)
                if sections["stats"]:
                    await self._sync_mapping_rows(
                        db, table="mod_stats", key_column="user_id", values=self.mod_stats, replace=True
                    )
                if sections["pings"]:
                    await self._sync_mapping_rows(
                        db, table="pings", key_column="user_id", values=self.pings, replace=True
                    )
                if sections["modmail"]:
                    self._rebuild_modmail_index()
                    await self._sync_mapping_rows(
                        db, table="modmail", key_column="user_id", values=self.modmail, replace=True
                    )
                if sections["lockdown"]:
                    await self._sync_mapping_rows(
                        db, table="lockdown", key_column="channel_id", values=self.lockdown, replace=True
                    )
                await self._commit(db)
            except Exception:
                await db.rollback()
                raise
            for section, selected in sections.items():
                if selected:
                    self._clear_section_if_current(section, generations[section])
            if sections["config"]:
                invalidate_native_automod_settings(self.config)

    def mark_config_dirty(self):
        return self._mark_section_dirty("config")

    async def save_config(self, *keys: str):
        generation = self.mark_config_dirty()
        async with self._save_lock:
            db = await self._db_conn()
            try:
                await self._sync_mapping_rows(
                    db,
                    table="config",
                    key_column="key",
                    values=self.config,
                    keys=keys or None,
                )
                await self._commit(db)
            except Exception:
                await db.rollback()
                raise
            self._clear_section_if_current("config", generation)
            if not keys or "native_automod" in keys:
                invalidate_native_automod_settings(self.config)

    async def save_roles(self, user_ids: Optional[Iterable[Any]] = None, *, replace: bool = False):
        generation = self._mark_section_dirty("roles")
        async with self._save_lock:
            db = await self._db_conn()
            try:
                await self._sync_mapping_rows(
                    db,
                    table="roles",
                    key_column="user_id",
                    values=self.roles,
                    keys=user_ids,
                    replace=replace,
                )
                await self._commit(db)
            except Exception:
                await db.rollback()
                raise
            self._clear_section_if_current("roles", generation)

    async def save_punishments(
        self,
        case_ids: Optional[Iterable[int]] = None,
        *,
        deleted_case_ids: Optional[Iterable[int]] = None,
        replace: bool = False,
    ):
        generation = self._mark_section_dirty("punishments")
        normalized_case_ids = None if case_ids is None else {int(case_id) for case_id in case_ids}
        rows = self._punishment_rows(normalized_case_ids)
        async with self._save_lock:
            db = await self._db_conn()
            try:
                await self._upsert_punishment_rows(db, rows)
                if deleted_case_ids:
                    await db.executemany(
                        "DELETE FROM punishments WHERE case_id = ?",
                        [(int(case_id),) for case_id in deleted_case_ids],
                    )
                if replace:
                    await self._replace_punishments_from_memory(db)
                await self._commit(db)
            except Exception:
                await db.rollback()
                raise
            self._clear_section_if_current("punishments", generation)
        if replace or normalized_case_ids is None:
            self._rebuild_case_index()

    async def persist_punishment(self, case_id: int, *, config_keys: Iterable[str] = ("case_counter",)) -> None:
        punishment_generation = self._mark_section_dirty("punishments")
        config_generation = self._mark_section_dirty("config")
        rows = self._punishment_rows([case_id])
        if not rows:
            raise KeyError(f"Unknown punishment case {case_id}")
        async with self._save_lock:
            db = await self._db_conn()
            try:
                await self._upsert_punishment_rows(db, rows)
                await self._sync_mapping_rows(
                    db,
                    table="config",
                    key_column="key",
                    values=self.config,
                    keys=config_keys,
                )
                await self._commit(db)
            except Exception:
                await db.rollback()
                raise
            self._clear_section_if_current("punishments", punishment_generation)
            self._clear_section_if_current("config", config_generation)

    async def delete_punishments(self, case_ids: Iterable[int]) -> None:
        normalized = sorted({int(case_id) for case_id in case_ids})
        if not normalized:
            return
        async with self._save_lock:
            db = await self._db_conn()
            try:
                await db.executemany(
                    "DELETE FROM punishments WHERE case_id = ?",
                    [(case_id,) for case_id in normalized],
                )
                await self._commit(db)
            except Exception:
                await db.rollback()
                raise

    async def save_mod_stats(self, keys: Optional[Iterable[Any]] = None):
        generation = self._mark_section_dirty("stats")
        async with self._save_lock:
            db = await self._db_conn()
            try:
                await self._sync_mapping_rows(
                    db,
                    table="mod_stats",
                    key_column="user_id",
                    values=self.mod_stats,
                    keys=keys,
                )
                await self._commit(db)
            except Exception:
                await db.rollback()
                raise
            self._clear_section_if_current("stats", generation)

    async def save_pings(self, user_ids: Optional[Iterable[Any]] = None):
        generation = self._mark_section_dirty("pings")
        async with self._save_lock:
            db = await self._db_conn()
            try:
                await self._sync_mapping_rows(
                    db,
                    table="pings",
                    key_column="user_id",
                    values=self.pings,
                    keys=user_ids,
                )
                await self._commit(db)
            except Exception:
                await db.rollback()
                raise
            self._clear_section_if_current("pings", generation)

    async def save_lockdown(self, channel_ids: Optional[Iterable[Any]] = None, *, replace: bool = False):
        generation = self._mark_section_dirty("lockdown")
        async with self._save_lock:
            db = await self._db_conn()
            try:
                await self._sync_mapping_rows(
                    db,
                    table="lockdown",
                    key_column="channel_id",
                    values=self.lockdown,
                    keys=channel_ids,
                    replace=replace,
                )
                await self._commit(db)
            except Exception:
                await db.rollback()
                raise
            self._clear_section_if_current("lockdown", generation)

    async def add_punishment(self, uid, record, *, persist: bool = True):
        uid = str(uid)
        if uid not in self.punishments:
            self.punishments[uid] = []
        prepared = self.prepare_punishment_record(record)
        self.punishments[uid].append(prepared)
        self._index_case_record(uid, prepared)
        self._mark_section_dirty("punishments")
        if persist:
            try:
                await self.persist_punishment(prepared["case_id"])
            except Exception:
                self.punishments[uid].remove(prepared)
                if not self.punishments[uid]:
                    self.punishments.pop(uid, None)
                self.case_index.pop(prepared["case_id"], None)
                raise
        return prepared

    async def discard_pending_punishment(self, uid, case_id: int, *, persist: bool = True):
        normalized_uid = str(uid)
        records = self.punishments.get(normalized_uid, [])
        removed = None
        removed_index = None
        for index, record in enumerate(records):
            if isinstance(record, dict) and record.get("case_id") == case_id:
                removed = record
                removed_index = index
                break
        if removed is None:
            return None
        if persist:
            await self.delete_punishments([case_id])
        records.pop(removed_index)
        if not records:
            self.punishments.pop(normalized_uid, None)
        self.case_index.pop(case_id, None)
        return removed

    async def save_modmail(self, user_ids: Optional[Iterable[Any]] = None, *, replace: bool = False):
        generation = self._mark_section_dirty("modmail")
        normalized_user_ids = None if user_ids is None else {str(user_id) for user_id in user_ids}
        async with self._save_lock:
            db = await self._db_conn()
            try:
                await self._sync_mapping_rows(
                    db,
                    table="modmail",
                    key_column="user_id",
                    values=self.modmail,
                    keys=normalized_user_ids,
                    replace=replace,
                )
                await self._commit(db)
            except Exception:
                await db.rollback()
                raise
            self._clear_section_if_current("modmail", generation)
        if normalized_user_ids is None or replace:
            self._rebuild_modmail_index()
        else:
            self._update_modmail_index(normalized_user_ids)

    def get_modmail_user_id(self, thread_id: int) -> Optional[str]:
        return self.modmail_threads.get(thread_id)

    def get_case(self, case_id: int) -> Tuple[Optional[str], Optional[dict]]:
        normalized_case_id = self._parse_optional_int(case_id)
        if normalized_case_id is None:
            return None, None
        entry = self.case_index.get(normalized_case_id)
        if entry is not None:
            user_id, record = entry
            if record in self.punishments.get(user_id, []):
                return entry
            self.case_index.pop(normalized_case_id, None)
        self._rebuild_case_index()
        return self.case_index.get(normalized_case_id, (None, None))

    def get_user_cases(self, user_id: int) -> List[dict]:
        records = self.punishments.get(str(user_id), [])
        return sorted(
            [record for record in records if isinstance(record, dict)],
            key=lambda record: record.get("case_id", 0),
            reverse=True,
        )

    def get_all_cases(self) -> List[Tuple[str, dict]]:
        """Every case on record across all users as (user_id, record) pairs,
        sorted by case id descending (newest first)."""
        cases: List[Tuple[str, dict]] = []
        for user_id, records in self.punishments.items():
            for record in records:
                if isinstance(record, dict):
                    cases.append((user_id, record))
        cases.sort(key=lambda item: item[1].get("case_id", 0), reverse=True)
        return cases

    async def list_cases_page(self, *, page: int = 0, page_size: int = 20) -> List[Tuple[str, dict]]:
        page = max(0, int(page))
        page_size = max(1, min(100, int(page_size)))
        db = await self._db_conn()
        rows = await db.execute_fetchall(
            "SELECT user_id, data FROM punishments ORDER BY case_id DESC LIMIT ? OFFSET ?",
            (page_size, page * page_size),
        )
        cases = []
        for row in rows:
            try:
                record = json.loads(row["data"])
            except (TypeError, ValueError):
                continue
            cases.append((str(row["user_id"]), record))
        return cases

    async def list_user_case_ids(self, user_id: int, *, limit: int = 25) -> List[int]:
        db = await self._db_conn()
        rows = await db.execute_fetchall(
            "SELECT case_id FROM punishments WHERE user_id = ? "
            "ORDER BY case_id DESC LIMIT ?",
            (str(user_id), max(1, min(100, int(limit)))),
        )
        return [int(row["case_id"]) for row in rows]

    async def get_case_totals(self) -> Tuple[int, Dict[str, int]]:
        db = await self._db_conn()
        rows = await db.execute_fetchall(
            "SELECT COALESCE(action_type, 'unknown') AS action_type, COUNT(*) AS total "
            "FROM punishments GROUP BY COALESCE(action_type, 'unknown')"
        )
        counts = {str(row["action_type"]): int(row["total"]) for row in rows}
        return sum(counts.values()), counts

    async def get_due_tempbans(self, *, now_timestamp: Optional[int] = None, limit: int = 100) -> List[Tuple[str, dict]]:
        cutoff = int(time.time()) if now_timestamp is None else int(now_timestamp)
        db = await self._db_conn()
        rows = await db.execute_fetchall(
            "SELECT user_id, data FROM punishments "
            "WHERE action_type = 'ban' AND active = 1 AND expires_at IS NOT NULL AND expires_at <= ? "
            "ORDER BY expires_at ASC LIMIT ?",
            (cutoff, max(1, min(1000, int(limit)))),
        )
        due = []
        for row in rows:
            try:
                record = json.loads(row["data"])
            except (TypeError, ValueError):
                continue
            due.append((str(row["user_id"]), record))
        return due

    async def mark_punishment_inactive(self, case_id: int) -> bool:
        user_id, current = self.get_case(case_id)
        if not user_id or not current:
            return False
        updated = copy.deepcopy(current)
        updated["active"] = False
        action_type, active, expires_at = self._punishment_storage_fields(updated)
        async with self._save_lock:
            db = await self._db_conn()
            try:
                cursor = await db.execute(
                    "UPDATE punishments SET data = ?, action_type = ?, active = ?, expires_at = ? WHERE case_id = ?",
                    (json.dumps(updated), action_type, active, expires_at, int(case_id)),
                )
                await self._commit(db)
            except Exception:
                await db.rollback()
                raise
        if cursor.rowcount <= 0:
            return False
        current.clear()
        current.update(updated)
        return True

    async def _migrate_legacy_native_automod_history(self) -> None:
        store = self.mod_stats.get("native_automod")
        if not isinstance(store, dict):
            return
        db = await self._db_conn()
        async with db.execute(
            "SELECT value FROM storage_meta WHERE key = 'native_automod_rows_migrated'"
        ) as cursor:
            if await cursor.fetchone():
                return

        event_rows = []
        step_rows = []
        for user_id, bucket in store.items():
            if not isinstance(bucket, dict):
                continue
            for event in bucket.get("events", []):
                if not isinstance(event, dict):
                    continue
                occurred_at = self._epoch_seconds(event.get("timestamp"))
                if occurred_at is None:
                    continue
                event_rows.append(
                    (
                        str(user_id),
                        str(event.get("rule_id") or "0"),
                        str(event.get("rule_name") or "Unknown"),
                        occurred_at,
                        str(event.get("content") or "")[:500],
                        event.get("matched_keyword"),
                    )
                )
            for step in bucket.get("applied_steps", []):
                if not isinstance(step, dict):
                    continue
                occurred_at = self._epoch_seconds(step.get("timestamp"))
                if occurred_at is None:
                    continue
                step_rows.append(
                    (
                        str(user_id),
                        str(step.get("rule_id") or "0"),
                        str(step.get("rule_name") or "Unknown"),
                        occurred_at,
                        int(step.get("threshold", 1) or 1),
                        int(step.get("window_minutes", 1440) or 1440),
                        str(step.get("punishment_type") or "warn"),
                        int(step.get("duration_minutes", 0) or 0),
                    )
                )

        async with self._save_lock:
            try:
                if event_rows:
                    await db.executemany(
                        "INSERT INTO native_automod_events"
                        "(user_id, rule_id, rule_name, occurred_at, content, matched_keyword) "
                        "VALUES (?, ?, ?, ?, ?, ?)",
                        event_rows,
                    )
                if step_rows:
                    await db.executemany(
                        "INSERT INTO native_automod_steps"
                        "(user_id, rule_id, rule_name, occurred_at, threshold_value, window_minutes, "
                        "punishment_type, duration_minutes) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                        step_rows,
                    )
                await db.execute(
                    "INSERT INTO storage_meta(key, value) VALUES ('native_automod_rows_migrated', '1') "
                    "ON CONFLICT(key) DO UPDATE SET value = excluded.value"
                )
                await self._commit(db)
            except Exception:
                await db.rollback()
                raise
        if event_rows or step_rows:
            logger.info(
                "Migration: copied %d native AutoMod events and %d applied steps into indexed storage",
                len(event_rows),
                len(step_rows),
            )

    async def record_native_automod_event(
        self,
        *,
        user_id: int,
        rule_id: int,
        rule_name: str,
        content: str,
        matched_keyword: Optional[str],
        occurred_at: Optional[int] = None,
    ) -> None:
        timestamp = int(time.time()) if occurred_at is None else int(occurred_at)
        async with self._save_lock:
            db = await self._db_conn()
            try:
                await db.execute(
                    "INSERT INTO native_automod_events"
                    "(user_id, rule_id, rule_name, occurred_at, content, matched_keyword) "
                    "VALUES (?, ?, ?, ?, ?, ?)",
                    (
                        str(user_id),
                        str(rule_id),
                        str(rule_name),
                        timestamp,
                        str(content or "")[:500],
                        matched_keyword,
                    ),
                )
                await db.execute(
                    "DELETE FROM native_automod_events WHERE user_id = ? AND event_id NOT IN "
                    "(SELECT event_id FROM native_automod_events WHERE user_id = ? "
                    "ORDER BY occurred_at DESC, event_id DESC LIMIT ?)",
                    (str(user_id), str(user_id), _NATIVE_AUTOMOD_PER_USER_LIMIT),
                )
                await self._commit(db)
            except Exception:
                await db.rollback()
                raise

    async def count_recent_native_automod_hits(
        self,
        *,
        user_id: int,
        rule_id: int,
        rule_name: str,
        window_minutes: int,
        now_timestamp: Optional[int] = None,
    ) -> int:
        now_value = int(time.time()) if now_timestamp is None else int(now_timestamp)
        cutoff = now_value - max(1, int(window_minutes)) * 60
        db = await self._db_conn()
        async with db.execute(
            "SELECT COUNT(*) AS total FROM native_automod_events "
            "WHERE user_id = ? AND occurred_at >= ? AND (rule_id = ? OR rule_name = ?)",
            (str(user_id), cutoff, str(rule_id), str(rule_name)),
        ) as cursor:
            row = await cursor.fetchone()
        return int(row["total"] if row else 0)

    async def has_recent_native_automod_step_application(
        self,
        *,
        user_id: int,
        rule_id: int,
        rule_name: str,
        threshold: int,
        window_minutes: int,
        now_timestamp: Optional[int] = None,
    ) -> bool:
        now_value = int(time.time()) if now_timestamp is None else int(now_timestamp)
        cutoff = now_value - max(1, int(window_minutes)) * 60
        db = await self._db_conn()
        async with db.execute(
            "SELECT 1 FROM native_automod_steps WHERE user_id = ? AND occurred_at >= ? "
            "AND (rule_id = ? OR rule_name = ?) AND threshold_value = ? AND window_minutes = ? LIMIT 1",
            (
                str(user_id),
                cutoff,
                str(rule_id),
                str(rule_name),
                int(threshold),
                int(window_minutes),
            ),
        ) as cursor:
            return await cursor.fetchone() is not None

    async def record_native_automod_step_application(
        self,
        *,
        user_id: int,
        rule_id: int,
        rule_name: str,
        step: dict,
        occurred_at: Optional[int] = None,
    ) -> None:
        timestamp = int(time.time()) if occurred_at is None else int(occurred_at)
        async with self._save_lock:
            db = await self._db_conn()
            try:
                await db.execute(
                    "INSERT INTO native_automod_steps"
                    "(user_id, rule_id, rule_name, occurred_at, threshold_value, window_minutes, "
                    "punishment_type, duration_minutes) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    (
                        str(user_id),
                        str(rule_id),
                        str(rule_name),
                        timestamp,
                        int(step.get("threshold", 1) or 1),
                        int(step.get("window_minutes", 1440) or 1440),
                        str(step.get("punishment_type") or "warn"),
                        int(step.get("duration_minutes", 0) or 0),
                    ),
                )
                await db.execute(
                    "DELETE FROM native_automod_steps WHERE user_id = ? AND step_id NOT IN "
                    "(SELECT step_id FROM native_automod_steps WHERE user_id = ? "
                    "ORDER BY occurred_at DESC, step_id DESC LIMIT ?)",
                    (str(user_id), str(user_id), _NATIVE_AUTOMOD_PER_USER_LIMIT),
                )
                await self._commit(db)
            except Exception:
                await db.rollback()
                raise

    async def prune_native_automod_history(self, *, batch_size: int = 1000, now_timestamp: Optional[int] = None) -> int:
        now_value = int(time.time()) if now_timestamp is None else int(now_timestamp)
        cutoff = now_value - _NATIVE_AUTOMOD_RETENTION_DAYS * 86400
        limit = max(1, min(10000, int(batch_size)))
        deleted = 0
        async with self._save_lock:
            db = await self._db_conn()
            try:
                for table, id_column in (
                    ("native_automod_events", "event_id"),
                    ("native_automod_steps", "step_id"),
                ):
                    cursor = await db.execute(
                        f"DELETE FROM {table} WHERE {id_column} IN "
                        f"(SELECT {id_column} FROM {table} WHERE occurred_at < ? LIMIT ?)",
                        (cutoff, limit),
                    )
                    deleted += max(0, int(cursor.rowcount))
                await self._commit(db)
            except Exception:
                await db.rollback()
                raise
        return deleted

    # ------------------------------------------------------------------
    # Message exports (stored on-demand, not held in memory)
    # ------------------------------------------------------------------

    _EXPORT_RETENTION = 50

    async def save_export(self, *, requester_id: int, title: str, filename: str, message_count: int, content: bytes) -> int:
        """Persist an HTML export blob and return its new export id. Old exports
        beyond the retention cap are pruned so the DB doesn't grow unbounded."""
        from core.utils import now_iso
        async with self._save_lock:
            db = await self._db_conn()
            try:
                cursor = await db.execute(
                    "INSERT INTO exports (created_at, requester_id, title, filename, message_count, content) VALUES (?, ?, ?, ?, ?, ?)",
                    (now_iso(), str(requester_id), title, filename, int(message_count), content),
                )
                export_id = cursor.lastrowid
                await db.execute(
                    "DELETE FROM exports WHERE export_id NOT IN (SELECT export_id FROM exports ORDER BY export_id DESC LIMIT ?)",
                    (self._EXPORT_RETENTION,),
                )
                await self._commit(db)
            except Exception:
                await db.rollback()
                raise
        return export_id

    async def list_exports(self, limit: int = 25) -> List[dict]:
        """Return export metadata (no blob) newest-first."""
        db = await self._db_conn()
        rows = await db.execute_fetchall(
            "SELECT export_id, created_at, requester_id, title, filename, message_count FROM exports ORDER BY export_id DESC LIMIT ?",
            (int(limit),),
        )
        return [dict(row) for row in rows]

    async def get_export(self, export_id: int) -> Optional[dict]:
        """Return a single export including its blob content, or None."""
        db = await self._db_conn()
        async with db.execute(
            "SELECT export_id, created_at, requester_id, title, filename, message_count, content FROM exports WHERE export_id = ?",
            (int(export_id),),
        ) as cursor:
            row = await cursor.fetchone()
        return dict(row) if row else None

    async def close(self) -> None:
        if self._dirty_config:
            await self.save_config()
        if self._dirty_roles:
            await self.save_roles()
        if self._dirty_punishments:
            await self.save_punishments()
        if self._dirty_stats:
            await self.save_mod_stats()
        if self._dirty_pings:
            await self.save_pings()
        if self._dirty_modmail:
            await self.save_modmail()
        if self._dirty_lockdown:
            await self.save_lockdown(replace=True)
        if self._db is not None:
            await self._db.close()
            self._db = None

    def allocate_case_id(self) -> int:
        current = self._normalize_positive_int(self.config.get("case_counter", 0), 0, minimum=0)
        next_case_id = current + 1
        self.config["case_counter"] = next_case_id
        self._mark_section_dirty("config")
        return next_case_id

    def prepare_punishment_record(self, record: dict) -> dict:
        from core.utils import now_iso
        prepared = dict(record)
        case_id = prepared.get("case_id")
        if not isinstance(case_id, int) or case_id <= 0:
            prepared["case_id"] = self.allocate_case_id()
        if "timestamp" not in prepared:
            prepared["timestamp"] = now_iso()
        if "active" not in prepared:
            prepared["active"] = prepared.get("type") == "ban"
        normalize_case_record(prepared)
        return prepared

    def _normalize_punishments(self):
        if not isinstance(self.punishments, dict):
            self.punishments = {}
            self._dirty_punishments = True
            return

        highest_case_id = self._normalize_positive_int(self.config.get("case_counter", 0), 0, minimum=0)
        changed = False

        for uid, records in list(self.punishments.items()):
            if not isinstance(records, list):
                self.punishments[uid] = []
                changed = True
                continue

            normalized_records = []
            for record in records:
                if not isinstance(record, dict):
                    changed = True
                    continue

                case_id = record.get("case_id")
                if isinstance(case_id, int) and case_id > 0:
                    highest_case_id = max(highest_case_id, case_id)
                else:
                    highest_case_id += 1
                    record["case_id"] = highest_case_id
                    changed = True

                record_type = record.get("type")
                if record_type == "ban":
                    duration = record.get("duration_minutes", 0)
                    active = bool(record.get("active", duration == -1 or duration > 0))
                    if record.get("active") != active:
                        record["active"] = active
                        changed = True

                if normalize_case_record(record):
                    changed = True

                normalized_records.append(record)

            self.punishments[uid] = normalized_records

        if self.config.get("case_counter") != highest_case_id:
            self.config["case_counter"] = highest_case_id
            self._dirty_config = True

        self._rebuild_case_index()
        if changed:
            self._dirty_punishments = True


# ----------------- Security -----------------
class AntiAbuseSystem:
    def __init__(self):
        self._tracker = TTLMap(max_size=10_000, ttl_seconds=3600)
        self.cooldowns = TTLMap(max_size=10_000, ttl_seconds=3600)
        self.mention_spam_tracker = TTLMap(max_size=10_000, ttl_seconds=3600)

    def check_rate_limit(self, user_id: int, config: dict) -> bool:
        now = time.time()
        limit = config.get("security", {}).get("max_actions_per_min", 10)
        tracker = self._tracker.get(user_id)
        if tracker is None:
            tracker = deque(maxlen=15)
            self._tracker[user_id] = tracker
        while tracker and now - tracker[0] > 60:
            tracker.popleft()
        tracker.append(now)
        self._tracker[user_id] = tracker
        return len(tracker) > limit
