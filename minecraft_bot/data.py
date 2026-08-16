"""Transactional SQLite storage for Minecraft applications and bridge work."""

from __future__ import annotations

import asyncio
import json
import math
import re
import statistics
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable, Optional

import aiosqlite

from .models import (
    ACTIVE_APPLICATION_STATUSES,
    AccountEditionAlreadyLinked,
    ApplicationStatus,
    BridgeAction,
    DeliveryRecord,
    DuplicateActiveApplication,
    Edition,
    InvalidTransition,
    MinecraftApplication,
    OutboxRecord,
)


JAVA_USERNAME = re.compile(r"^[A-Za-z0-9_]{3,16}$")
BEDROCK_USERNAME = re.compile(r"^[A-Za-z0-9 _-]{1,16}$")
SCHEMA_VERSION = 6
COMMAND_LOG_RETENTION_DAYS = 30
COMMAND_LOG_RETENTION_ROWS = 20_000
#: How long a verified applicant has to finish the written form before the
#: application expires on its own.
ANSWERS_WINDOW_SECONDS = 3 * 24 * 3600

APPLICATIONS_TABLE_COLUMNS_SQL = """(
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    guild_id TEXT NOT NULL,
    discord_user_id TEXT NOT NULL,
    edition TEXT NOT NULL CHECK (edition IN ('JAVA', 'BEDROCK')),
    claimed_username TEXT NOT NULL,
    normalized_username TEXT NOT NULL,
    verified_username TEXT,
    minecraft_uuid TEXT,
    xuid TEXT,
    answers TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN (
        'PENDING_VERIFICATION', 'PENDING_APPLICATION', 'PENDING_REVIEW', 'APPROVAL_QUEUED',
        'APPROVED', 'DENIED', 'EXPIRED', 'CANCELLED', 'REVOKED'
    )),
    verification_expires_at INTEGER NOT NULL,
    verified_at INTEGER,
    reviewed_by TEXT,
    reviewed_at INTEGER,
    applicant_reason TEXT,
    internal_note TEXT,
    review_channel_id TEXT,
    review_message_id TEXT,
    auto_detect_edition INTEGER NOT NULL DEFAULT 0,
    status_channel_id TEXT,
    status_message_id TEXT,
    decision_channel_id TEXT,
    decision_message_id TEXT,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
)"""

APPLICATIONS_COLUMN_NAMES = (
    "id, guild_id, discord_user_id, edition, claimed_username, normalized_username, "
    "verified_username, minecraft_uuid, xuid, answers, status, verification_expires_at, "
    "verified_at, reviewed_by, reviewed_at, applicant_reason, internal_note, "
    "review_channel_id, review_message_id, auto_detect_edition, status_channel_id, "
    "status_message_id, decision_channel_id, decision_message_id, created_at, updated_at"
)

SCHEMA_SQL = f"""
CREATE TABLE IF NOT EXISTS minecraft_applications {APPLICATIONS_TABLE_COLUMNS_SQL};
CREATE INDEX IF NOT EXISTS idx_minecraft_applications_user
    ON minecraft_applications(guild_id, discord_user_id, id DESC);
CREATE INDEX IF NOT EXISTS idx_minecraft_applications_user_recent
    ON minecraft_applications(discord_user_id, id DESC);
CREATE INDEX IF NOT EXISTS idx_minecraft_applications_status_recent
    ON minecraft_applications(status, id DESC);
CREATE INDEX IF NOT EXISTS idx_minecraft_applications_username
    ON minecraft_applications(normalized_username, id DESC);
CREATE INDEX IF NOT EXISTS idx_minecraft_applications_verification
    ON minecraft_applications(status, verification_expires_at);
CREATE INDEX IF NOT EXISTS idx_minecraft_applications_review_message
    ON minecraft_applications(review_message_id);
CREATE INDEX IF NOT EXISTS idx_minecraft_applications_status_message
    ON minecraft_applications(status_message_id);
CREATE INDEX IF NOT EXISTS idx_minecraft_applications_decision_message
    ON minecraft_applications(decision_message_id);

CREATE TABLE IF NOT EXISTS minecraft_accounts (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    discord_user_id TEXT NOT NULL,
    edition TEXT NOT NULL CHECK (edition IN ('JAVA', 'BEDROCK')),
    minecraft_uuid TEXT NOT NULL,
    xuid TEXT,
    current_username TEXT NOT NULL,
    verified_at INTEGER NOT NULL,
    last_seen_at INTEGER NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    UNIQUE(edition, minecraft_uuid)
);
CREATE INDEX IF NOT EXISTS idx_minecraft_accounts_user
    ON minecraft_accounts(discord_user_id, id DESC);
CREATE INDEX IF NOT EXISTS idx_minecraft_accounts_username
    ON minecraft_accounts(current_username COLLATE NOCASE, id DESC);
CREATE UNIQUE INDEX IF NOT EXISTS idx_minecraft_accounts_xuid
    ON minecraft_accounts(xuid) WHERE xuid IS NOT NULL;

CREATE TABLE IF NOT EXISTS minecraft_bridge_outbox (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    idempotency_key TEXT NOT NULL UNIQUE,
    action TEXT NOT NULL CHECK (
        action IN ('APPROVE', 'REVOKE', 'KICK', 'SYNC_PENDING', 'REMOVE_PENDING', 'STATUS')
    ),
    application_id INTEGER,
    payload TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'PENDING' CHECK (
        status IN ('PENDING', 'SENT', 'PROCESSED', 'FAILED', 'CANCELLED')
    ),
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    created_at INTEGER NOT NULL,
    processed_at INTEGER,
    FOREIGN KEY(application_id) REFERENCES minecraft_applications(id)
);
CREATE INDEX IF NOT EXISTS idx_minecraft_outbox_status
    ON minecraft_bridge_outbox(status, id);

CREATE TABLE IF NOT EXISTS minecraft_audit_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    action TEXT NOT NULL,
    application_id INTEGER,
    actor_discord_id TEXT,
    target_discord_id TEXT,
    payload TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    FOREIGN KEY(application_id) REFERENCES minecraft_applications(id)
);
CREATE INDEX IF NOT EXISTS idx_minecraft_audit_application
    ON minecraft_audit_log(application_id, id DESC);

CREATE TABLE IF NOT EXISTS minecraft_config (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS minecraft_bridge_nonces (
    nonce TEXT PRIMARY KEY,
    expires_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_minecraft_bridge_nonces_expiry
    ON minecraft_bridge_nonces(expires_at);

CREATE TABLE IF NOT EXISTS minecraft_bridge_events (
    idempotency_key TEXT PRIMARY KEY,
    message_type TEXT NOT NULL,
    processed_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_minecraft_bridge_events_processed
    ON minecraft_bridge_events(processed_at);

CREATE TABLE IF NOT EXISTS minecraft_command_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    source TEXT NOT NULL,
    command TEXT NOT NULL,
    actor_discord_id TEXT NOT NULL,
    actor_label TEXT NOT NULL,
    target_discord_id TEXT,
    channel_id TEXT,
    outcome TEXT NOT NULL,
    risk TEXT NOT NULL,
    duration_ms INTEGER NOT NULL,
    correlation_id TEXT,
    detail TEXT,
    options TEXT NOT NULL,
    created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_minecraft_command_log_recent
    ON minecraft_command_log(id DESC);
CREATE INDEX IF NOT EXISTS idx_minecraft_command_log_actor
    ON minecraft_command_log(actor_discord_id, id DESC);
CREATE INDEX IF NOT EXISTS idx_minecraft_command_log_age
    ON minecraft_command_log(created_at);

CREATE TABLE IF NOT EXISTS minecraft_delivery_outbox (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    dedupe_key TEXT NOT NULL UNIQUE,
    kind TEXT NOT NULL CHECK (kind IN ('CHANNEL_EMBED', 'USER_EMBED', 'LIVE_CARD')),
    target_id TEXT NOT NULL,
    payload TEXT NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at INTEGER NOT NULL,
    last_error TEXT,
    created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_minecraft_delivery_due
    ON minecraft_delivery_outbox(next_attempt_at, id);
"""


def normalize_username(edition: Edition | str, username: str) -> tuple[str, str]:
    try:
        parsed_edition = Edition(str(edition).upper())
    except ValueError as exc:
        raise ValueError("Edition must be Java or Bedrock") from exc
    cleaned = " ".join(str(username).strip().split())
    if parsed_edition is Edition.JAVA:
        if not JAVA_USERNAME.fullmatch(cleaned):
            raise ValueError("Java usernames must contain 3-16 letters, numbers, or underscores")
        return cleaned, cleaned.casefold()
    if not BEDROCK_USERNAME.fullmatch(cleaned):
        raise ValueError("Bedrock gamertags must contain 1-16 letters, numbers, spaces, underscores, or hyphens")
    return cleaned, cleaned.casefold()


def _like_contains(value: str) -> str:
    escaped = value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    return f"%{escaped}%"


def validate_answers(answers: dict[str, str]) -> tuple[str, str]:
    why = str(answers.get("why", "")).strip()
    about = str(answers.get("about", "")).strip()
    if not 10 <= len(why) <= 500 or not 10 <= len(about) <= 1000:
        raise ValueError("Application answers must be between 10 and their displayed limits")
    return why, about


def _now() -> int:
    return int(time.time())


class MinecraftDataManager:
    def __init__(self, database_path: Path) -> None:
        self.database_path = Path(database_path)
        self._db: Optional[aiosqlite.Connection] = None
        self._write_lock = asyncio.Lock()

    async def open(self) -> None:
        self.database_path.parent.mkdir(parents=True, exist_ok=True)
        existed = self.database_path.exists() and self.database_path.stat().st_size > 0
        db = await aiosqlite.connect(self.database_path)
        db.row_factory = aiosqlite.Row
        await db.execute("PRAGMA journal_mode=WAL")
        await db.execute("PRAGMA synchronous=NORMAL")
        await db.execute("PRAGMA foreign_keys=ON")
        try:
            async with db.execute("PRAGMA user_version") as cursor:
                row = await cursor.fetchone()
            current_version = int(row[0] if row else 0)
            if existed and current_version < SCHEMA_VERSION:
                await self._backup_database(db, current_version)
            if existed and current_version < 4:
                columns = {
                    row[1]
                    for row in await db.execute_fetchall("PRAGMA table_info(minecraft_applications)")
                }
                if columns:
                    for name, definition in (
                        ("auto_detect_edition", "INTEGER NOT NULL DEFAULT 0"),
                        ("status_channel_id", "TEXT"),
                        ("status_message_id", "TEXT"),
                    ):
                        if name not in columns:
                            await db.execute(
                                f"ALTER TABLE minecraft_applications ADD COLUMN {name} {definition}"
                            )
            if existed and current_version < 5:
                columns = {
                    row[1]
                    for row in await db.execute_fetchall("PRAGMA table_info(minecraft_applications)")
                }
                if columns:
                    for name in ("decision_channel_id", "decision_message_id"):
                        if name not in columns:
                            await db.execute(
                                f"ALTER TABLE minecraft_applications ADD COLUMN {name} TEXT"
                            )
            if existed and current_version < 6:
                # PENDING_APPLICATION joins the status CHECK, and SQLite cannot
                # alter a constraint in place — rebuild the table around it.
                await db.commit()
                await db.execute("PRAGMA foreign_keys=OFF")
                tables = {
                    row[0]
                    for row in await db.execute_fetchall(
                        "SELECT name FROM sqlite_master WHERE type='table' AND name='minecraft_applications'"
                    )
                }
                if tables:
                    await db.execute(
                        f"CREATE TABLE minecraft_applications_v6 {APPLICATIONS_TABLE_COLUMNS_SQL}"
                    )
                    await db.execute(
                        f"INSERT INTO minecraft_applications_v6 ({APPLICATIONS_COLUMN_NAMES}) "
                        f"SELECT {APPLICATIONS_COLUMN_NAMES} FROM minecraft_applications"
                    )
                    await db.execute("DROP TABLE minecraft_applications")
                    await db.execute(
                        "ALTER TABLE minecraft_applications_v6 RENAME TO minecraft_applications"
                    )
                await db.commit()
                await db.execute("PRAGMA foreign_keys=ON")
            await db.executescript(SCHEMA_SQL)
            await db.execute(f"PRAGMA user_version={SCHEMA_VERSION}")
            await db.commit()
        except Exception:
            await db.rollback()
            await db.close()
            raise
        self._db = db

    async def _backup_database(self, db: aiosqlite.Connection, version: int) -> Path:
        backup_dir = self.database_path.parent / "backups"
        backup_dir.mkdir(parents=True, exist_ok=True)
        stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
        backup_path = backup_dir / f"minecraft-v{version}-{stamp}.db"
        counter = 1
        while backup_path.exists():
            backup_path = backup_dir / f"minecraft-v{version}-{stamp}-{counter}.db"
            counter += 1
        target = await aiosqlite.connect(backup_path)
        try:
            await db.backup(target)
            await target.commit()
        finally:
            await target.close()
        backups = sorted(
            backup_dir.glob("minecraft-v*.db"),
            key=lambda candidate: candidate.stat().st_mtime,
            reverse=True,
        )
        for stale in backups[5:]:
            stale.unlink(missing_ok=True)
        return backup_path

    async def close(self) -> None:
        if self._db is not None:
            await self._db.close()
            self._db = None

    def _connection(self) -> aiosqlite.Connection:
        if self._db is None:
            raise RuntimeError("Minecraft database has not been opened")
        return self._db

    @staticmethod
    def _application(row: aiosqlite.Row | dict[str, Any]) -> MinecraftApplication:
        return MinecraftApplication(
            id=int(row["id"]),
            guild_id=str(row["guild_id"]),
            discord_user_id=str(row["discord_user_id"]),
            edition=Edition(row["edition"]),
            claimed_username=row["claimed_username"],
            normalized_username=row["normalized_username"],
            verified_username=row["verified_username"],
            minecraft_uuid=row["minecraft_uuid"],
            xuid=row["xuid"],
            answers=json.loads(row["answers"]),
            status=ApplicationStatus(row["status"]),
            verification_expires_at=int(row["verification_expires_at"]),
            verified_at=row["verified_at"],
            reviewed_by=row["reviewed_by"],
            reviewed_at=row["reviewed_at"],
            applicant_reason=row["applicant_reason"],
            internal_note=row["internal_note"],
            review_channel_id=row["review_channel_id"],
            review_message_id=row["review_message_id"],
            auto_detect_edition=bool(row["auto_detect_edition"]),
            status_channel_id=row["status_channel_id"],
            status_message_id=row["status_message_id"],
            decision_channel_id=row["decision_channel_id"],
            decision_message_id=row["decision_message_id"],
            created_at=int(row["created_at"]),
            updated_at=int(row["updated_at"]),
        )

    @staticmethod
    def _outbox(row: aiosqlite.Row) -> OutboxRecord:
        return OutboxRecord(
            id=int(row["id"]),
            idempotency_key=row["idempotency_key"],
            action=BridgeAction(row["action"]),
            payload=json.loads(row["payload"]),
            status=row["status"],
            attempts=int(row["attempts"]),
            last_error=row["last_error"],
            application_id=row["application_id"],
            created_at=int(row["created_at"]),
            processed_at=row["processed_at"],
        )

    async def _begin(self, db: aiosqlite.Connection) -> None:
        await db.execute("BEGIN IMMEDIATE")

    async def _audit(
        self,
        db: aiosqlite.Connection,
        action: str,
        *,
        application_id: Optional[int] = None,
        actor_id: Optional[int | str] = None,
        target_id: Optional[int | str] = None,
        payload: Optional[dict[str, Any]] = None,
        timestamp: Optional[int] = None,
    ) -> None:
        await db.execute(
            "INSERT INTO minecraft_audit_log"
            "(action, application_id, actor_discord_id, target_discord_id, payload, created_at) "
            "VALUES (?, ?, ?, ?, ?, ?)",
            (
                action,
                application_id,
                str(actor_id) if actor_id is not None else None,
                str(target_id) if target_id is not None else None,
                json.dumps(payload or {}, separators=(",", ":")),
                _now() if timestamp is None else timestamp,
            ),
        )

    async def _queue(
        self,
        db: aiosqlite.Connection,
        action: BridgeAction,
        payload: dict[str, Any],
        *,
        idempotency_key: str,
        application_id: Optional[int] = None,
        timestamp: Optional[int] = None,
    ) -> None:
        await db.execute(
            "INSERT OR IGNORE INTO minecraft_bridge_outbox"
            "(idempotency_key, action, application_id, payload, status, created_at) "
            "VALUES (?, ?, ?, ?, 'PENDING', ?)",
            (
                idempotency_key,
                action.value,
                application_id,
                json.dumps(payload, separators=(",", ":")),
                _now() if timestamp is None else timestamp,
            ),
        )

    async def _release_account_if_unused(
        self,
        db: aiosqlite.Connection,
        application: MinecraftApplication,
    ) -> None:
        if not application.minecraft_uuid:
            return
        still_needed = await db.execute_fetchall(
            "SELECT id FROM minecraft_applications WHERE minecraft_uuid=? AND id<>? "
            "AND status IN (?, ?) LIMIT 1",
            (
                application.minecraft_uuid,
                application.id,
                ApplicationStatus.APPROVED.value,
                ApplicationStatus.APPROVAL_QUEUED.value,
            ),
        )
        if still_needed:
            return
        await db.execute(
            "DELETE FROM minecraft_accounts WHERE edition=? AND minecraft_uuid=?",
            (application.edition.value, application.minecraft_uuid),
        )

    async def create_application(
        self,
        *,
        guild_id: int,
        discord_user_id: int,
        edition: Optional[Edition],
        claimed_username: str,
        answers: Optional[dict[str, str]] = None,
        verification_seconds: int = 600,
        now: Optional[int] = None,
    ) -> MinecraftApplication:
        current = _now() if now is None else int(now)
        auto_detect_edition = edition is None
        if auto_detect_edition:
            cleaned = " ".join(str(claimed_username).strip().split())
            if not BEDROCK_USERNAME.fullmatch(cleaned):
                raise ValueError(
                    "Minecraft names must contain 1-16 letters, numbers, spaces, underscores, or hyphens"
                )
            edition = Edition.JAVA if JAVA_USERNAME.fullmatch(cleaned) else Edition.BEDROCK
            claimed, normalized = cleaned, cleaned.casefold()
        else:
            claimed, normalized = normalize_username(edition, claimed_username)
        # Verification comes first now, so most applications start with no written
        # answers; they arrive through submit_answers after the account is verified.
        why, about = validate_answers(answers) if answers else ("", "")
        db = self._connection()
        async with self._write_lock:
            try:
                await self._begin(db)
                expired_rows = await db.execute_fetchall(
                    "SELECT id, status FROM minecraft_applications WHERE guild_id=? AND discord_user_id=? "
                    "AND status IN (?, ?) AND verification_expires_at<=?",
                    (
                        str(guild_id),
                        str(discord_user_id),
                        ApplicationStatus.PENDING_VERIFICATION.value,
                        ApplicationStatus.PENDING_APPLICATION.value,
                        current,
                    ),
                )
                for expired_row in expired_rows:
                    expired_id = int(expired_row["id"])
                    await db.execute(
                        "UPDATE minecraft_applications SET status=?, updated_at=? WHERE id=? AND status=?",
                        (
                            ApplicationStatus.EXPIRED.value,
                            current,
                            expired_id,
                            str(expired_row["status"]),
                        ),
                    )
                    if str(expired_row["status"]) == ApplicationStatus.PENDING_VERIFICATION.value:
                        await self._queue(
                            db,
                            BridgeAction.REMOVE_PENDING,
                            {"application_id": expired_id},
                            idempotency_key=f"application:{expired_id}:expire",
                            application_id=expired_id,
                            timestamp=current,
                        )
                if not auto_detect_edition:
                    linked_edition = await db.execute_fetchall(
                        "SELECT current_username FROM minecraft_accounts "
                        "WHERE discord_user_id=? AND edition=? LIMIT 1",
                        (str(discord_user_id), edition.value),
                    )
                    # Re-applying with the account already linked to this member is
                    # fine; only a different account for the same edition is blocked.
                    if (
                        linked_edition
                        and str(linked_edition[0]["current_username"]).casefold() != normalized
                    ):
                        raise AccountEditionAlreadyLinked(
                            f"Your Discord account already has a linked {edition.value.title()} account"
                        )
                else:
                    linked_editions = await db.execute_fetchall(
                        "SELECT edition FROM minecraft_accounts WHERE discord_user_id=?",
                        (str(discord_user_id),),
                    )
                    if len(linked_editions) >= 2:
                        raise AccountEditionAlreadyLinked(
                            "Your Discord account already has a linked Java and Bedrock account"
                        )
                owned_name = await db.execute_fetchall(
                    "SELECT discord_user_id FROM minecraft_accounts "
                    "WHERE lower(current_username)=? LIMIT 1",
                    (normalized,),
                )
                if owned_name and str(owned_name[0]["discord_user_id"]) != str(discord_user_id):
                    raise ValueError(
                        "That Minecraft name is already linked to another Discord account"
                    )
                claimed_name = await db.execute_fetchall(
                    "SELECT discord_user_id FROM minecraft_applications "
                    "WHERE normalized_username=? AND status IN "
                    f"({','.join('?' for _ in ACTIVE_APPLICATION_STATUSES)}) LIMIT 1",
                    (
                        normalized,
                        *(status.value for status in ACTIVE_APPLICATION_STATUSES),
                    ),
                )
                if claimed_name and str(claimed_name[0]["discord_user_id"]) != str(discord_user_id):
                    raise ValueError(
                        "That Minecraft name is already being used on another application"
                    )
                placeholders = ",".join("?" for _ in ACTIVE_APPLICATION_STATUSES)
                active = await db.execute_fetchall(
                    f"SELECT id FROM minecraft_applications WHERE guild_id=? AND discord_user_id=? "
                    f"AND status IN ({placeholders}) LIMIT 1",
                    (
                        str(guild_id),
                        str(discord_user_id),
                        *(status.value for status in ACTIVE_APPLICATION_STATUSES),
                    ),
                )
                if active:
                    raise DuplicateActiveApplication("An unfinished application already exists")
                cursor = await db.execute(
                    "INSERT INTO minecraft_applications"
                    "(guild_id, discord_user_id, edition, claimed_username, normalized_username, answers, "
                    "status, verification_expires_at, auto_detect_edition, created_at, updated_at) "
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    (
                        str(guild_id),
                        str(discord_user_id),
                        edition.value,
                        claimed,
                        normalized,
                        json.dumps(
                            {"why": why, "about": about} if why or about else {},
                            separators=(",", ":"),
                        ),
                        ApplicationStatus.PENDING_VERIFICATION.value,
                        current + verification_seconds,
                        int(auto_detect_edition),
                        current,
                        current,
                    ),
                )
                application_id = int(cursor.lastrowid)
                payload = {
                    "application_id": application_id,
                    "edition": "AUTO" if auto_detect_edition else edition.value,
                    "claimed_username": claimed,
                    "normalized_username": normalized,
                    "expires_at": current + verification_seconds,
                }
                await self._queue(
                    db,
                    BridgeAction.SYNC_PENDING,
                    payload,
                    idempotency_key=f"application:{application_id}:sync",
                    application_id=application_id,
                    timestamp=current,
                )
                await self._audit(
                    db,
                    "APPLICATION_CREATED",
                    application_id=application_id,
                    target_id=discord_user_id,
                    payload={"edition": "AUTO" if auto_detect_edition else edition.value, "claimed_username": claimed},
                    timestamp=current,
                )
                await db.commit()
            except Exception:
                await db.rollback()
                raise
        application = await self.get_application(application_id)
        if application is None:
            raise RuntimeError("Application insert did not persist")
        return application

    async def get_application(self, application_id: int) -> Optional[MinecraftApplication]:
        rows = await self._connection().execute_fetchall(
            "SELECT * FROM minecraft_applications WHERE id=?",
            (int(application_id),),
        )
        return self._application(rows[0]) if rows else None

    async def get_active_application_for_user(
        self,
        *,
        guild_id: int | str,
        discord_user_id: int | str,
        now: Optional[int] = None,
    ) -> Optional[MinecraftApplication]:
        current = _now() if now is None else int(now)
        rows = await self._connection().execute_fetchall(
            "SELECT * FROM minecraft_applications "
            "WHERE guild_id=? AND discord_user_id=? AND ("
            "(status IN (?, ?) AND verification_expires_at>?) OR status IN (?, ?)"
            ") ORDER BY id DESC LIMIT 1",
            (
                str(guild_id),
                str(discord_user_id),
                ApplicationStatus.PENDING_VERIFICATION.value,
                ApplicationStatus.PENDING_APPLICATION.value,
                current,
                ApplicationStatus.PENDING_REVIEW.value,
                ApplicationStatus.APPROVAL_QUEUED.value,
            ),
        )
        return self._application(rows[0]) if rows else None

    async def get_application_by_review_message(self, message_id: int) -> Optional[MinecraftApplication]:
        rows = await self._connection().execute_fetchall(
            "SELECT * FROM minecraft_applications WHERE review_message_id=?",
            (str(message_id),),
        )
        return self._application(rows[0]) if rows else None

    async def set_review_message(self, application_id: int, channel_id: int, message_id: int) -> None:
        async with self._write_lock:
            db = self._connection()
            await db.execute(
                "UPDATE minecraft_applications SET review_channel_id=?, review_message_id=?, updated_at=? WHERE id=?",
                (str(channel_id), str(message_id), _now(), int(application_id)),
            )
            await db.commit()

    async def list_applications_for_user(self, discord_user_id: int | str, *, limit: int = 25) -> list[MinecraftApplication]:
        rows = await self._connection().execute_fetchall(
            "SELECT * FROM minecraft_applications WHERE discord_user_id=? ORDER BY id DESC LIMIT ?",
            (str(discord_user_id), max(1, min(int(limit), 100))),
        )
        return [self._application(row) for row in rows]

    async def has_approved_application(self, discord_user_id: int | str) -> bool:
        """Whether this member holds access that staff granted.

        The same fact `record_verification` checks before linking a second edition
        outright, so anything that tells somebody they are already accepted can ask
        rather than infer it from having a linked account.
        """
        rows = await self._connection().execute_fetchall(
            "SELECT 1 FROM minecraft_applications WHERE discord_user_id=? AND status=? LIMIT 1",
            (str(discord_user_id), ApplicationStatus.APPROVED.value),
        )
        return bool(rows)

    async def list_applications(
        self,
        *,
        status: Optional[ApplicationStatus] = None,
        limit: int = 25,
    ) -> list[MinecraftApplication]:
        bounded_limit = max(1, min(int(limit), 100))
        if status is None:
            rows = await self._connection().execute_fetchall(
                "SELECT * FROM minecraft_applications ORDER BY id DESC LIMIT ?",
                (bounded_limit,),
            )
        else:
            rows = await self._connection().execute_fetchall(
                "SELECT * FROM minecraft_applications WHERE status=? ORDER BY id DESC LIMIT ?",
                (status.value, bounded_limit),
            )
        return [self._application(row) for row in rows]

    async def list_accounts_for_user(self, discord_user_id: int | str) -> list[dict[str, Any]]:
        rows = await self._connection().execute_fetchall(
            "SELECT * FROM minecraft_accounts WHERE discord_user_id=? ORDER BY id DESC",
            (str(discord_user_id),),
        )
        return [dict(row) for row in rows]

    async def get_account_owner(self, edition: Edition | str, minecraft_uuid: str) -> Optional[str]:
        try:
            parsed_edition = Edition(str(edition).upper())
        except ValueError:
            return None
        rows = await self._connection().execute_fetchall(
            "SELECT discord_user_id FROM minecraft_accounts WHERE edition=? AND minecraft_uuid=? LIMIT 1",
            (parsed_edition.value, str(minecraft_uuid)),
        )
        return str(rows[0]["discord_user_id"]) if rows else None

    async def owners_for_uuids(self, minecraft_uuids: Iterable[str]) -> dict[str, str]:
        """Linked Discord ids for many UUIDs in one query rather than one each."""
        unique = [str(value) for value in dict.fromkeys(minecraft_uuids) if value]
        if not unique:
            return {}
        placeholders = ",".join("?" for _ in unique)
        rows = await self._connection().execute_fetchall(
            "SELECT minecraft_uuid, discord_user_id FROM minecraft_accounts "
            f"WHERE minecraft_uuid IN ({placeholders})",
            tuple(unique),
        )
        return {str(row["minecraft_uuid"]): str(row["discord_user_id"]) for row in rows}

    async def list_pending_verifications(self) -> list[MinecraftApplication]:
        rows = await self._connection().execute_fetchall(
            "SELECT * FROM minecraft_applications WHERE status=? AND verification_expires_at>? ORDER BY id",
            (ApplicationStatus.PENDING_VERIFICATION.value, _now()),
        )
        return [self._application(row) for row in rows]

    async def list_missing_review_messages(self, *, limit: int = 20) -> list[MinecraftApplication]:
        rows = await self._connection().execute_fetchall(
            "SELECT * FROM minecraft_applications WHERE status=? AND review_message_id IS NULL "
            "ORDER BY id LIMIT ?",
            (ApplicationStatus.PENDING_REVIEW.value, max(1, min(int(limit), 100))),
        )
        return [self._application(row) for row in rows]

    async def expire_pending(self, *, now: Optional[int] = None, limit: int = 100) -> list[MinecraftApplication]:
        current = _now() if now is None else int(now)
        db = self._connection()
        expired_ids: list[int] = []
        async with self._write_lock:
            try:
                await self._begin(db)
                rows = await db.execute_fetchall(
                    "SELECT * FROM minecraft_applications WHERE status IN (?, ?) "
                    "AND verification_expires_at<=? ORDER BY verification_expires_at LIMIT ?",
                    (
                        ApplicationStatus.PENDING_VERIFICATION.value,
                        ApplicationStatus.PENDING_APPLICATION.value,
                        current,
                        max(1, min(limit, 500)),
                    ),
                )
                for row in rows:
                    application = self._application(row)
                    expired_ids.append(application.id)
                    await db.execute(
                        "UPDATE minecraft_applications SET status=?, updated_at=? WHERE id=? AND status=?",
                        (
                            ApplicationStatus.EXPIRED.value,
                            current,
                            application.id,
                            application.status.value,
                        ),
                    )
                    if application.status is ApplicationStatus.PENDING_VERIFICATION:
                        # Paper only tracks applications awaiting their first
                        # connection; verified ones were already removed there.
                        await self._queue(
                            db,
                            BridgeAction.REMOVE_PENDING,
                            {"application_id": application.id},
                            idempotency_key=f"application:{application.id}:expire",
                            application_id=application.id,
                            timestamp=current,
                        )
                    await self._release_account_if_unused(db, application)
                    await self._audit(
                        db,
                        "VERIFICATION_EXPIRED"
                        if application.status is ApplicationStatus.PENDING_VERIFICATION
                        else "APPLICATION_FORM_EXPIRED",
                        application_id=application.id,
                        target_id=application.discord_user_id,
                        timestamp=current,
                    )
                await db.commit()
            except Exception:
                await db.rollback()
                raise
        results = []
        for application_id in expired_ids:
            application = await self.get_application(application_id)
            if application is not None:
                results.append(application)
        return results

    async def record_verification(
        self,
        *,
        application_id: int,
        edition: Edition,
        minecraft_uuid: str,
        current_username: str,
        xuid: Optional[str],
        event_idempotency_key: str,
        now: Optional[int] = None,
    ) -> tuple[MinecraftApplication, bool]:
        current = _now() if now is None else int(now)
        try:
            uuid.UUID(str(minecraft_uuid))
        except ValueError as exc:
            raise InvalidTransition("Invalid Minecraft UUID") from exc
        _, normalized_actual = normalize_username(edition, current_username)
        db = self._connection()
        changed = False
        async with self._write_lock:
            try:
                await self._begin(db)
                rows = await db.execute_fetchall(
                    "SELECT * FROM minecraft_applications WHERE id=?",
                    (int(application_id),),
                )
                if not rows:
                    raise InvalidTransition("Application does not exist")
                application = self._application(rows[0])
                event_cursor = await db.execute(
                    "INSERT OR IGNORE INTO minecraft_bridge_events"
                    "(idempotency_key, message_type, processed_at) VALUES (?, 'VERIFICATION', ?)",
                    (event_idempotency_key, current),
                )
                if event_cursor.rowcount != 1:
                    await db.rollback()
                    return application, False
                if application.status is not ApplicationStatus.PENDING_VERIFICATION:
                    raise InvalidTransition("Application is not awaiting verification")
                if application.verification_expires_at <= current:
                    await db.execute(
                        "UPDATE minecraft_applications SET status=?, updated_at=? WHERE id=?",
                        (ApplicationStatus.EXPIRED.value, current, application.id),
                    )
                    await db.commit()
                    updated = await self.get_application(application_id)
                    return updated or application, False
                if not application.auto_detect_edition and application.edition is not edition:
                    raise InvalidTransition("Verified edition does not match the application")
                if normalized_actual != application.normalized_username:
                    raise InvalidTransition("Verified username does not match the application")
                if edition is Edition.BEDROCK and not xuid:
                    raise InvalidTransition("Bedrock verification did not include a Floodgate XUID")

                linked_edition = await db.execute_fetchall(
                    "SELECT minecraft_uuid FROM minecraft_accounts WHERE discord_user_id=? AND edition=? LIMIT 1",
                    (application.discord_user_id, edition.value),
                )
                if linked_edition and linked_edition[0]["minecraft_uuid"] != minecraft_uuid:
                    raise InvalidTransition(
                        f"Discord member already has a linked {edition.value.title()} account"
                    )

                existing = await db.execute_fetchall(
                    "SELECT discord_user_id FROM minecraft_accounts WHERE edition=? AND minecraft_uuid=?",
                    (edition.value, minecraft_uuid),
                )
                if existing and existing[0]["discord_user_id"] != application.discord_user_id:
                    raise InvalidTransition("Minecraft account is linked to another Discord member")
                if xuid:
                    linked_xuid = await db.execute_fetchall(
                        "SELECT discord_user_id FROM minecraft_accounts WHERE xuid=?",
                        (str(xuid),),
                    )
                    if linked_xuid and linked_xuid[0]["discord_user_id"] != application.discord_user_id:
                        raise InvalidTransition("Floodgate XUID is linked to another Discord member")

                # A member who already holds approved access is linking their other
                # edition. The written form and the review exist to vet the person,
                # which staff have already done; verification is the only new fact,
                # and it has just happened. Anyone without approved access — never
                # accepted, denied, or revoked — still gets the full application.
                already_approved = await db.execute_fetchall(
                    "SELECT 1 FROM minecraft_applications WHERE discord_user_id=? AND guild_id=? "
                    "AND status=? AND id<>? LIMIT 1",
                    (
                        application.discord_user_id,
                        application.guild_id,
                        ApplicationStatus.APPROVED.value,
                        application.id,
                    ),
                )
                auto_link = bool(already_approved)

                # Applications created without written answers (the normal flow now)
                # move to PENDING_APPLICATION and wait for the form; legacy records
                # that already carry answers go straight to staff review.
                if auto_link:
                    next_status = ApplicationStatus.APPROVAL_QUEUED
                else:
                    next_status = (
                        ApplicationStatus.PENDING_REVIEW
                        if application.answers
                        else ApplicationStatus.PENDING_APPLICATION
                    )
                next_deadline = (
                    application.verification_expires_at
                    if application.answers or auto_link
                    else current + ANSWERS_WINDOW_SECONDS
                )
                await db.execute(
                    "UPDATE minecraft_applications SET edition=?, auto_detect_edition=0, "
                    "verified_username=?, minecraft_uuid=?, xuid=?, status=?, verified_at=?, "
                    "verification_expires_at=?, updated_at=? "
                    "WHERE id=? AND status=?",
                    (
                        edition.value,
                        current_username,
                        minecraft_uuid,
                        str(xuid) if xuid is not None else None,
                        next_status.value,
                        current,
                        next_deadline,
                        current,
                        application.id,
                        ApplicationStatus.PENDING_VERIFICATION.value,
                    ),
                )
                await db.execute(
                    "INSERT INTO minecraft_accounts"
                    "(discord_user_id, edition, minecraft_uuid, xuid, current_username, verified_at, "
                    "last_seen_at, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) "
                    "ON CONFLICT(edition, minecraft_uuid) DO UPDATE SET "
                    "current_username=excluded.current_username, xuid=excluded.xuid, "
                    "last_seen_at=excluded.last_seen_at, updated_at=excluded.updated_at",
                    (
                        application.discord_user_id,
                        edition.value,
                        minecraft_uuid,
                        str(xuid) if xuid is not None else None,
                        current_username,
                        current,
                        current,
                        current,
                        current,
                    ),
                )
                await db.execute(
                    "UPDATE minecraft_bridge_outbox SET status='CANCELLED', processed_at=? "
                    "WHERE application_id=? AND action=? AND status IN ('PENDING', 'SENT', 'FAILED')",
                    (current, application.id, BridgeAction.SYNC_PENDING.value),
                )
                await self._queue(
                    db,
                    BridgeAction.REMOVE_PENDING,
                    {"application_id": application.id},
                    idempotency_key=f"application:{application.id}:verified",
                    application_id=application.id,
                    timestamp=current,
                )
                if auto_link:
                    # The same queue a staff approval uses, so whitelisting, retries
                    # and the outbox behave identically. reviewed_by stays null:
                    # recording the bot as the moderator would be a lie in the log.
                    await self._queue(
                        db,
                        BridgeAction.APPROVE,
                        {
                            "application_id": application.id,
                            "edition": edition.value,
                            "minecraft_uuid": minecraft_uuid,
                            "verified_username": current_username,
                        },
                        idempotency_key=f"application:{application.id}:approve:{minecraft_uuid}",
                        application_id=application.id,
                        timestamp=current,
                    )
                    await self._audit(
                        db,
                        "LINK_AUTO_APPROVED",
                        application_id=application.id,
                        target_id=application.discord_user_id,
                        payload={"edition": edition.value, "username": current_username},
                        timestamp=current,
                    )
                await self._audit(
                    db,
                    "VERIFICATION_ACCEPTED",
                    application_id=application.id,
                    target_id=application.discord_user_id,
                    payload={
                        "idempotency_key": event_idempotency_key,
                        "edition": edition.value,
                        "minecraft_uuid": minecraft_uuid,
                        "xuid": str(xuid) if xuid is not None else None,
                        "current_username": current_username,
                    },
                    timestamp=current,
                )
                await db.commit()
                changed = True
            except Exception:
                await db.rollback()
                raise
        updated = await self.get_application(application_id)
        if updated is None:
            raise RuntimeError("Verified application disappeared")
        return updated, changed

    async def submit_answers(
        self,
        application_id: int,
        discord_user_id: int | str,
        *,
        why: str,
        about: str,
        now: Optional[int] = None,
    ) -> MinecraftApplication:
        """Attaches the written form to a verified application and sends it to review."""
        current = _now() if now is None else int(now)
        cleaned_why, cleaned_about = validate_answers({"why": why, "about": about})
        db = self._connection()
        async with self._write_lock:
            try:
                await self._begin(db)
                rows = await db.execute_fetchall(
                    "SELECT * FROM minecraft_applications WHERE id=?",
                    (int(application_id),),
                )
                if not rows:
                    raise InvalidTransition("Application does not exist")
                application = self._application(rows[0])
                if str(application.discord_user_id) != str(discord_user_id):
                    raise InvalidTransition("This application belongs to another member")
                if application.status is ApplicationStatus.EXPIRED:
                    raise InvalidTransition("The application expired before the form was submitted")
                if application.status is not ApplicationStatus.PENDING_APPLICATION:
                    raise InvalidTransition("Application is not waiting for the written form")
                if application.verification_expires_at <= current:
                    await db.execute(
                        "UPDATE minecraft_applications SET status=?, updated_at=? WHERE id=?",
                        (ApplicationStatus.EXPIRED.value, current, application.id),
                    )
                    await db.commit()
                    raise InvalidTransition("The application expired before the form was submitted")
                await db.execute(
                    "UPDATE minecraft_applications SET answers=?, status=?, updated_at=? "
                    "WHERE id=? AND status=?",
                    (
                        json.dumps(
                            {"why": cleaned_why, "about": cleaned_about},
                            separators=(",", ":"),
                        ),
                        ApplicationStatus.PENDING_REVIEW.value,
                        current,
                        application.id,
                        ApplicationStatus.PENDING_APPLICATION.value,
                    ),
                )
                await self._audit(
                    db,
                    "APPLICATION_SUBMITTED",
                    application_id=application.id,
                    actor_id=discord_user_id,
                    target_id=application.discord_user_id,
                    timestamp=current,
                )
                await db.commit()
            except Exception:
                await db.rollback()
                raise
        updated = await self.get_application(application_id)
        if updated is None:
            raise RuntimeError("Submitted application disappeared")
        return updated

    async def list_whitelisted(self, *, limit: int = 200) -> list[dict[str, Any]]:
        """Every account with active whitelist access, for the public directory."""
        rows = await self._connection().execute_fetchall(
            "SELECT edition, verified_username, claimed_username, discord_user_id, "
            "minecraft_uuid, reviewed_at FROM minecraft_applications WHERE status=? "
            "ORDER BY LOWER(COALESCE(verified_username, claimed_username)) LIMIT ?",
            (ApplicationStatus.APPROVED.value, max(1, min(int(limit), 500))),
        )
        return [
            {
                "edition": str(row["edition"]),
                "username": str(row["verified_username"] or row["claimed_username"]),
                "discord_user_id": str(row["discord_user_id"]),
                "minecraft_uuid": str(row["minecraft_uuid"] or ""),
                "approved_at": row["reviewed_at"],
            }
            for row in rows
        ]

    async def wipe_all_data(self, actor_id: int | str) -> dict[str, int]:
        """Removes every application, account, and queue record, keeping settings.

        Queues the Minecraft-side cleanup before anything else can run: one REVOKE
        per linked account so Paper removes the whitelist entries, and one
        REMOVE_PENDING per verified-but-undecided application so Paper forgets it.
        """
        current = _now()
        db = self._connection()
        async with self._write_lock:
            try:
                await self._begin(db)
                accounts = await db.execute_fetchall(
                    "SELECT edition, minecraft_uuid FROM minecraft_accounts",
                )
                verified_active = await db.execute_fetchall(
                    "SELECT id FROM minecraft_applications WHERE status IN (?, ?, ?)",
                    (
                        ApplicationStatus.PENDING_APPLICATION.value,
                        ApplicationStatus.PENDING_REVIEW.value,
                        ApplicationStatus.APPROVAL_QUEUED.value,
                    ),
                )
                counts: dict[str, int] = {}
                for table in (
                    "minecraft_bridge_outbox",
                    "minecraft_audit_log",
                    "minecraft_delivery_outbox",
                    "minecraft_command_log",
                    "minecraft_bridge_events",
                    "minecraft_bridge_nonces",
                    "minecraft_accounts",
                    "minecraft_applications",
                ):
                    cursor = await db.execute(f"DELETE FROM {table}")
                    counts[table] = max(0, cursor.rowcount)
                for row in accounts:
                    await self._queue(
                        db,
                        BridgeAction.REVOKE,
                        {
                            "edition": str(row["edition"]),
                            "minecraft_uuid": str(row["minecraft_uuid"]),
                        },
                        idempotency_key=f"wipe:{current}:revoke:{row['minecraft_uuid']}",
                        application_id=None,
                        timestamp=current,
                    )
                for row in verified_active:
                    await self._queue(
                        db,
                        BridgeAction.REMOVE_PENDING,
                        {"application_id": int(row["id"])},
                        idempotency_key=f"wipe:{current}:remove:{row['id']}",
                        application_id=None,
                        timestamp=current,
                    )
                await self._audit(
                    db,
                    "DATA_WIPED",
                    actor_id=actor_id,
                    payload={"deleted": counts},
                    timestamp=current,
                )
                await db.commit()
            except Exception:
                await db.rollback()
                raise
        return counts

    async def set_status_message(self, application_id: int, channel_id: int, message_id: int) -> None:
        async with self._write_lock:
            db = self._connection()
            await db.execute(
                "UPDATE minecraft_applications SET status_channel_id=?, status_message_id=? WHERE id=?",
                (str(channel_id), str(message_id), int(application_id)),
            )
            await db.commit()

    async def set_decision_message(self, application_id: int, channel_id: int, message_id: int) -> None:
        async with self._write_lock:
            db = self._connection()
            await db.execute(
                "UPDATE minecraft_applications SET decision_channel_id=?, decision_message_id=? WHERE id=?",
                (str(channel_id), str(message_id), int(application_id)),
            )
            await db.commit()

    async def clear_status_message(self, application_id: int) -> None:
        async with self._write_lock:
            db = self._connection()
            await db.execute(
                "UPDATE minecraft_applications SET status_channel_id=NULL, status_message_id=NULL WHERE id=?",
                (int(application_id),),
            )
            await db.commit()

    async def get_application_by_status_message(self, message_id: int) -> Optional[MinecraftApplication]:
        rows = await self._connection().execute_fetchall(
            "SELECT * FROM minecraft_applications WHERE status_message_id=?",
            (str(message_id),),
        )
        return self._application(rows[0]) if rows else None

    async def list_live_card_applications(self, *, limit: int = 100) -> list[MinecraftApplication]:
        rows = await self._connection().execute_fetchall(
            "SELECT * FROM minecraft_applications WHERE status IN (?, ?) "
            "ORDER BY id DESC LIMIT ?",
            (
                ApplicationStatus.PENDING_APPLICATION.value,
                ApplicationStatus.PENDING_REVIEW.value,
                max(1, min(limit, 500)),
            ),
        )
        return [self._application(row) for row in rows]

    async def list_existing_live_cards(self, *, limit: int = 100) -> list[MinecraftApplication]:
        rows = await self._connection().execute_fetchall(
            "SELECT * FROM minecraft_applications WHERE status_message_id IS NOT NULL "
            "ORDER BY id DESC LIMIT ?",
            (max(1, min(limit, 500)),),
        )
        return [self._application(row) for row in rows]

    async def record_player_seen(
        self,
        edition: Edition | str,
        minecraft_uuid: str,
        current_username: str,
        xuid: Optional[str] = None,
        *,
        now: Optional[int] = None,
    ) -> Optional[str]:
        parsed = Edition(str(edition).upper())
        cleaned, _normalized = normalize_username(parsed, current_username)
        current = _now() if now is None else int(now)
        async with self._write_lock:
            db = self._connection()
            rows = await db.execute_fetchall(
                "SELECT discord_user_id FROM minecraft_accounts WHERE edition=? AND minecraft_uuid=?",
                (parsed.value, str(minecraft_uuid)),
            )
            if not rows:
                return None
            await db.execute(
                "UPDATE minecraft_accounts SET current_username=?, xuid=COALESCE(?, xuid), "
                "last_seen_at=?, updated_at=? WHERE edition=? AND minecraft_uuid=?",
                (cleaned, str(xuid) if xuid else None, current, current, parsed.value, str(minecraft_uuid)),
            )
            await db.commit()
            return str(rows[0]["discord_user_id"])

    async def queue_approval(self, application_id: int, moderator_id: int, *, now: Optional[int] = None) -> MinecraftApplication:
        current = _now() if now is None else int(now)
        db = self._connection()
        async with self._write_lock:
            try:
                await self._begin(db)
                rows = await db.execute_fetchall(
                    "SELECT * FROM minecraft_applications WHERE id=?",
                    (int(application_id),),
                )
                if not rows:
                    raise InvalidTransition("Application does not exist")
                application = self._application(rows[0])
                if application.status is not ApplicationStatus.PENDING_REVIEW:
                    raise InvalidTransition("Application has already been reviewed")
                if not application.minecraft_uuid or not application.verified_username:
                    raise InvalidTransition("Unverified applications cannot be approved")
                await db.execute(
                    "UPDATE minecraft_applications SET status=?, reviewed_by=?, reviewed_at=?, updated_at=? "
                    "WHERE id=? AND status=?",
                    (
                        ApplicationStatus.APPROVAL_QUEUED.value,
                        str(moderator_id),
                        current,
                        current,
                        application.id,
                        ApplicationStatus.PENDING_REVIEW.value,
                    ),
                )
                payload = {
                    "application_id": application.id,
                    "edition": application.edition.value,
                    "minecraft_uuid": application.minecraft_uuid,
                    "verified_username": application.verified_username,
                }
                await self._queue(
                    db,
                    BridgeAction.APPROVE,
                    payload,
                    idempotency_key=f"application:{application.id}:approve:{application.minecraft_uuid}",
                    application_id=application.id,
                    timestamp=current,
                )
                await self._audit(
                    db,
                    "APPROVAL_QUEUED",
                    application_id=application.id,
                    actor_id=moderator_id,
                    target_id=application.discord_user_id,
                    timestamp=current,
                )
                await db.commit()
            except Exception:
                await db.rollback()
                raise
        updated = await self.get_application(application_id)
        if updated is None:
            raise RuntimeError("Queued application disappeared")
        return updated

    async def deny_application(
        self,
        application_id: int,
        moderator_id: int,
        *,
        internal_note: str,
        applicant_reason: str,
        now: Optional[int] = None,
    ) -> MinecraftApplication:
        current = _now() if now is None else int(now)
        db = self._connection()
        async with self._write_lock:
            try:
                await self._begin(db)
                cursor = await db.execute(
                    "UPDATE minecraft_applications SET status=?, reviewed_by=?, reviewed_at=?, "
                    "internal_note=?, applicant_reason=?, updated_at=? WHERE id=? AND status=?",
                    (
                        ApplicationStatus.DENIED.value,
                        str(moderator_id),
                        current,
                        internal_note[:1000],
                        applicant_reason[:1000] or None,
                        current,
                        int(application_id),
                        ApplicationStatus.PENDING_REVIEW.value,
                    ),
                )
                if cursor.rowcount != 1:
                    raise InvalidTransition("Application has already been reviewed")
                rows = await db.execute_fetchall(
                    "SELECT * FROM minecraft_applications WHERE id=?",
                    (int(application_id),),
                )
                denied = self._application(rows[0]) if rows else None
                if denied is not None:
                    await self._release_account_if_unused(db, denied)
                await self._queue(
                    db,
                    BridgeAction.REMOVE_PENDING,
                    {"application_id": int(application_id)},
                    idempotency_key=f"application:{application_id}:deny-remove",
                    application_id=int(application_id),
                    timestamp=current,
                )
                await self._audit(
                    db,
                    "APPLICATION_DENIED",
                    application_id=application_id,
                    actor_id=moderator_id,
                    target_id=denied.discord_user_id if denied is not None else None,
                    payload={"has_public_reason": bool(applicant_reason)},
                    timestamp=current,
                )
                await db.commit()
            except Exception:
                await db.rollback()
                raise
        application = await self.get_application(application_id)
        if application is None:
            raise RuntimeError("Denied application disappeared")
        return application

    async def cancel_application(self, application_id: int, moderator_id: int) -> MinecraftApplication:
        current = _now()
        db = self._connection()
        placeholders = ",".join("?" for _ in ACTIVE_APPLICATION_STATUSES)
        async with self._write_lock:
            try:
                await self._begin(db)
                rows = await db.execute_fetchall(
                    "SELECT * FROM minecraft_applications WHERE id=?",
                    (int(application_id),),
                )
                if not rows:
                    raise InvalidTransition("Application does not exist")
                application = self._application(rows[0])
                cursor = await db.execute(
                    f"UPDATE minecraft_applications SET status=?, reviewed_by=?, reviewed_at=?, updated_at=? "
                    f"WHERE id=? AND status IN ({placeholders})",
                    (
                        ApplicationStatus.CANCELLED.value,
                        str(moderator_id),
                        current,
                        current,
                        application.id,
                        *(status.value for status in ACTIVE_APPLICATION_STATUSES),
                    ),
                )
                if cursor.rowcount != 1:
                    raise InvalidTransition("Application is no longer active")
                await db.execute(
                    "UPDATE minecraft_bridge_outbox SET status='CANCELLED', processed_at=? "
                    "WHERE application_id=? AND status IN ('PENDING', 'SENT', 'FAILED')",
                    (current, application.id),
                )
                await self._queue(
                    db,
                    BridgeAction.REMOVE_PENDING,
                    {"application_id": application.id},
                    idempotency_key=f"application:{application.id}:cancel",
                    application_id=application.id,
                    timestamp=current,
                )
                if application.status is ApplicationStatus.APPROVAL_QUEUED and application.minecraft_uuid:
                    await self._queue(
                        db,
                        BridgeAction.REVOKE,
                        {
                            "application_id": application.id,
                            "edition": application.edition.value,
                            "minecraft_uuid": application.minecraft_uuid,
                        },
                        idempotency_key=f"application:{application.id}:cancel-revoke",
                        application_id=application.id,
                        timestamp=current,
                    )
                else:
                    await self._release_account_if_unused(db, application)
                await self._audit(
                    db,
                    "APPLICATION_CANCELLED",
                    application_id=application.id,
                    actor_id=moderator_id,
                    target_id=application.discord_user_id,
                    timestamp=current,
                )
                await db.commit()
            except Exception:
                await db.rollback()
                raise
        updated = await self.get_application(application_id)
        if updated is None:
            raise RuntimeError("Cancelled application disappeared")
        return updated

    async def cancel_pending_verification_for_user(
        self,
        *,
        guild_id: int | str,
        discord_user_id: int | str,
        now: Optional[int] = None,
    ) -> MinecraftApplication:
        current = _now() if now is None else int(now)
        db = self._connection()
        application_id: Optional[int] = None
        async with self._write_lock:
            try:
                await self._begin(db)
                rows = await db.execute_fetchall(
                    "SELECT * FROM minecraft_applications "
                    "WHERE guild_id=? AND discord_user_id=? AND status=? "
                    "ORDER BY id DESC LIMIT 1",
                    (
                        str(guild_id),
                        str(discord_user_id),
                        ApplicationStatus.PENDING_VERIFICATION.value,
                    ),
                )
                if not rows:
                    raise InvalidTransition("You do not have a pending verification to cancel")
                application = self._application(rows[0])
                application_id = application.id
                cursor = await db.execute(
                    "UPDATE minecraft_applications SET status=?, updated_at=? "
                    "WHERE id=? AND status=?",
                    (
                        ApplicationStatus.CANCELLED.value,
                        current,
                        application.id,
                        ApplicationStatus.PENDING_VERIFICATION.value,
                    ),
                )
                if cursor.rowcount != 1:
                    raise InvalidTransition("The verification is no longer pending")
                await db.execute(
                    "UPDATE minecraft_bridge_outbox SET status='CANCELLED', processed_at=? "
                    "WHERE application_id=? AND status IN ('PENDING', 'SENT', 'FAILED')",
                    (current, application.id),
                )
                await self._queue(
                    db,
                    BridgeAction.REMOVE_PENDING,
                    {"application_id": application.id},
                    idempotency_key=f"application:{application.id}:withdraw",
                    application_id=application.id,
                    timestamp=current,
                )
                await self._audit(
                    db,
                    "APPLICATION_WITHDRAWN",
                    application_id=application.id,
                    actor_id=discord_user_id,
                    target_id=discord_user_id,
                    timestamp=current,
                )
                await db.commit()
            except Exception:
                await db.rollback()
                raise
        updated = await self.get_application(application_id)
        if updated is None:
            raise RuntimeError("Cancelled application disappeared")
        return updated

    async def queue_revocations(self, discord_user_id: int, moderator_id: int, reason: str) -> list[MinecraftApplication]:
        current = _now()
        db = self._connection()
        queued: list[int] = []
        async with self._write_lock:
            try:
                await self._begin(db)
                rows = await db.execute_fetchall(
                    "SELECT * FROM minecraft_applications WHERE discord_user_id=? AND status=?",
                    (str(discord_user_id), ApplicationStatus.APPROVED.value),
                )
                for row in rows:
                    application = self._application(row)
                    if not application.minecraft_uuid:
                        continue
                    queued.append(application.id)
                    await self._queue(
                        db,
                        BridgeAction.REVOKE,
                        {
                            "application_id": application.id,
                            "edition": application.edition.value,
                            "minecraft_uuid": application.minecraft_uuid,
                            "reason": reason[:500],
                        },
                        idempotency_key=f"application:{application.id}:revoke",
                        application_id=application.id,
                        timestamp=current,
                    )
                    await self._audit(
                        db,
                        "REVOCATION_QUEUED",
                        application_id=application.id,
                        actor_id=moderator_id,
                        target_id=discord_user_id,
                        payload={"reason": reason[:500]},
                        timestamp=current,
                    )
                await db.commit()
            except Exception:
                await db.rollback()
                raise
        results = []
        for application_id in queued:
            application = await self.get_application(application_id)
            if application is not None:
                results.append(application)
        return results

    async def unlink_account(
        self,
        discord_user_id: int | str,
        edition: Edition,
        moderator_id: int | str,
        reason: str,
    ) -> tuple[Optional[dict[str, Any]], list[MinecraftApplication], bool]:
        current = _now()
        db = self._connection()
        account: Optional[dict[str, Any]] = None
        affected_ids: list[int] = []
        revocation_queued = False
        async with self._write_lock:
            try:
                await self._begin(db)
                account_rows = await db.execute_fetchall(
                    "SELECT * FROM minecraft_accounts WHERE discord_user_id=? AND edition=? LIMIT 1",
                    (str(discord_user_id), edition.value),
                )
                if not account_rows:
                    await db.rollback()
                    return None, [], False
                account = dict(account_rows[0])
                applications = await db.execute_fetchall(
                    "SELECT * FROM minecraft_applications WHERE discord_user_id=? AND edition=? "
                    "AND minecraft_uuid=? AND status IN (?, ?) ORDER BY id",
                    (
                        str(discord_user_id),
                        edition.value,
                        account["minecraft_uuid"],
                        ApplicationStatus.APPROVAL_QUEUED.value,
                        ApplicationStatus.APPROVED.value,
                    ),
                )
                if applications:
                    revocation_queued = True
                    for row in applications:
                        application = self._application(row)
                        affected_ids.append(application.id)
                        await db.execute(
                            "UPDATE minecraft_bridge_outbox SET status='CANCELLED', processed_at=? "
                            "WHERE application_id=? AND action=? AND status IN ('PENDING', 'SENT', 'FAILED')",
                            (current, application.id, BridgeAction.APPROVE.value),
                        )
                        await self._queue(
                            db,
                            BridgeAction.REVOKE,
                            {
                                "application_id": application.id,
                                "edition": edition.value,
                                "minecraft_uuid": account["minecraft_uuid"],
                                "reason": str(reason)[:500],
                                "unlink_account": True,
                            },
                            idempotency_key=f"application:{application.id}:unlink",
                            application_id=application.id,
                            timestamp=current,
                        )
                        await self._audit(
                            db,
                            "ACCOUNT_UNLINK_QUEUED",
                            application_id=application.id,
                            actor_id=moderator_id,
                            target_id=discord_user_id,
                            payload={"edition": edition.value, "reason": str(reason)[:500]},
                            timestamp=current,
                        )
                else:
                    pending_rows = await db.execute_fetchall(
                        "SELECT id FROM minecraft_applications WHERE discord_user_id=? AND edition=? "
                        "AND minecraft_uuid=? AND status IN (?, ?)",
                        (
                            str(discord_user_id),
                            edition.value,
                            account["minecraft_uuid"],
                            ApplicationStatus.PENDING_REVIEW.value,
                            ApplicationStatus.PENDING_APPLICATION.value,
                        ),
                    )
                    affected_ids = [int(row["id"]) for row in pending_rows]
                    if affected_ids:
                        placeholders = ",".join("?" for _ in affected_ids)
                        await db.execute(
                            f"UPDATE minecraft_applications SET status=?, updated_at=? "
                            f"WHERE id IN ({placeholders})",
                            (ApplicationStatus.CANCELLED.value, current, *affected_ids),
                        )
                        for pending_id in affected_ids:
                            await self._queue(
                                db,
                                BridgeAction.REMOVE_PENDING,
                                {"application_id": pending_id},
                                idempotency_key=f"application:{pending_id}:unlink-remove",
                                application_id=pending_id,
                                timestamp=current,
                            )
                    await db.execute(
                        "DELETE FROM minecraft_accounts WHERE id=?",
                        (int(account["id"]),),
                    )
                    await self._audit(
                        db,
                        "ACCOUNT_UNLINKED",
                        application_id=affected_ids[-1] if affected_ids else None,
                        actor_id=moderator_id,
                        target_id=discord_user_id,
                        payload={
                            "edition": edition.value,
                            "minecraft_uuid": account["minecraft_uuid"],
                            "reason": str(reason)[:500],
                        },
                        timestamp=current,
                    )
                await db.commit()
            except Exception:
                await db.rollback()
                raise
        affected = []
        for application_id in affected_ids:
            application = await self.get_application(application_id)
            if application is not None:
                affected.append(application)
        return account, affected, revocation_queued

    async def get_outbox_batch(self, *, limit: int = 50) -> list[OutboxRecord]:
        rows = await self._connection().execute_fetchall(
            "SELECT * FROM minecraft_bridge_outbox WHERE status IN ('PENDING', 'SENT') "
            "ORDER BY id LIMIT ?",
            (max(1, min(limit, 100)),),
        )
        return [self._outbox(row) for row in rows]

    async def mark_outbox_sent(self, record_id: int) -> None:
        await self.mark_outbox_sent_batch([record_id])

    async def mark_outbox_sent_batch(self, record_ids: list[int]) -> None:
        normalized = tuple(dict.fromkeys(int(record_id) for record_id in record_ids))
        if not normalized:
            return
        async with self._write_lock:
            db = self._connection()
            try:
                await self._begin(db)
                await db.executemany(
                    "UPDATE minecraft_bridge_outbox SET status='SENT', attempts=attempts+1, "
                    "last_error=NULL WHERE id=?",
                    [(record_id,) for record_id in normalized],
                )
                await db.commit()
            except Exception:
                await db.rollback()
                raise

    async def mark_outbox_failed(self, idempotency_key: str, error: str) -> Optional[OutboxRecord]:
        async with self._write_lock:
            db = self._connection()
            await db.execute(
                "UPDATE minecraft_bridge_outbox SET status='FAILED', last_error=? WHERE idempotency_key=? "
                "AND status IN ('PENDING', 'SENT')",
                (str(error)[:1000], idempotency_key),
            )
            await db.commit()
        rows = await self._connection().execute_fetchall(
            "SELECT * FROM minecraft_bridge_outbox WHERE idempotency_key=?",
            (idempotency_key,),
        )
        return self._outbox(rows[0]) if rows else None

    async def complete_outbox(
        self,
        idempotency_key: str,
    ) -> tuple[Optional[OutboxRecord], Optional[MinecraftApplication], bool]:
        current = _now()
        db = self._connection()
        record: Optional[OutboxRecord] = None
        application_id: Optional[int] = None
        newly_processed = False
        async with self._write_lock:
            try:
                await self._begin(db)
                rows = await db.execute_fetchall(
                    "SELECT * FROM minecraft_bridge_outbox WHERE idempotency_key=?",
                    (idempotency_key,),
                )
                if not rows:
                    await db.rollback()
                    return None, None, False
                record = self._outbox(rows[0])
                application_id = record.application_id
                if record.status in {"PROCESSED", "CANCELLED"}:
                    await db.rollback()
                else:
                    newly_processed = True
                    await db.execute(
                        "UPDATE minecraft_bridge_outbox SET status='PROCESSED', processed_at=?, last_error=NULL "
                        "WHERE idempotency_key=?",
                        (current, idempotency_key),
                    )
                    if application_id is not None and record.action is BridgeAction.APPROVE:
                        account_still_linked = await db.execute_fetchall(
                            "SELECT 1 FROM minecraft_accounts WHERE minecraft_uuid=? LIMIT 1",
                            (str(record.payload.get("minecraft_uuid") or ""),),
                        )
                        if account_still_linked:
                            await db.execute(
                                "UPDATE minecraft_applications SET status=?, updated_at=? "
                                "WHERE id=? AND status=?",
                                (
                                    ApplicationStatus.APPROVED.value,
                                    current,
                                    application_id,
                                    ApplicationStatus.APPROVAL_QUEUED.value,
                                ),
                            )
                            await self._audit(
                                db,
                                "APPLICATION_APPROVED",
                                application_id=application_id,
                                timestamp=current,
                            )
                    elif application_id is not None and record.action is BridgeAction.REVOKE:
                        await db.execute(
                            "UPDATE minecraft_applications SET status=?, updated_at=? "
                            "WHERE id=? AND status IN (?, ?)",
                            (
                                ApplicationStatus.REVOKED.value,
                                current,
                                application_id,
                                ApplicationStatus.APPROVED.value,
                                ApplicationStatus.APPROVAL_QUEUED.value,
                            ),
                        )
                        await self._audit(
                            db,
                            "ACCESS_REVOKED",
                            application_id=application_id,
                            timestamp=current,
                        )
                        if record.payload.get("unlink_account"):
                            application_rows = await db.execute_fetchall(
                                "SELECT discord_user_id FROM minecraft_applications WHERE id=?",
                                (application_id,),
                            )
                            if application_rows:
                                owner_id = str(application_rows[0]["discord_user_id"])
                                await db.execute(
                                    "DELETE FROM minecraft_accounts WHERE discord_user_id=? AND edition=? "
                                    "AND minecraft_uuid=?",
                                    (
                                        owner_id,
                                        str(record.payload.get("edition", "")),
                                        str(record.payload.get("minecraft_uuid", "")),
                                    ),
                                )
                                await self._audit(
                                    db,
                                    "ACCOUNT_UNLINKED",
                                    application_id=application_id,
                                    target_id=owner_id,
                                    payload={
                                        "edition": record.payload.get("edition"),
                                        "minecraft_uuid": record.payload.get("minecraft_uuid"),
                                    },
                                    timestamp=current,
                                )
                    await db.commit()
            except Exception:
                await db.rollback()
                raise
        application = await self.get_application(application_id) if application_id is not None else None
        return record, application, newly_processed

    async def retry_application(self, application_id: int) -> int:
        async with self._write_lock:
            db = self._connection()
            cursor = await db.execute(
                "UPDATE minecraft_bridge_outbox SET status='PENDING', last_error=NULL, processed_at=NULL "
                "WHERE application_id=? AND status='FAILED'",
                (int(application_id),),
            )
            await db.commit()
            return max(0, cursor.rowcount)

    async def outbox_counts(self) -> dict[str, int]:
        rows = await self._connection().execute_fetchall(
            "SELECT status, COUNT(*) AS count FROM minecraft_bridge_outbox GROUP BY status"
        )
        return {row["status"]: int(row["count"]) for row in rows}

    async def set_config(self, key: str, value: Any) -> None:
        await self.set_configs({key: value})

    async def set_configs(
        self,
        values: dict[str, Any],
        *,
        actor_id: Optional[int | str] = None,
    ) -> None:
        if not values:
            return
        async with self._write_lock:
            db = self._connection()
            try:
                await self._begin(db)
                await db.executemany(
                    "INSERT INTO minecraft_config(key, value) VALUES (?, ?) "
                    "ON CONFLICT(key) DO UPDATE SET value=excluded.value",
                    [
                        (str(key), json.dumps(value, separators=(",", ":")))
                        for key, value in values.items()
                    ],
                )
                if actor_id is not None:
                    await self._audit(
                        db,
                        "SETTINGS_UPDATED",
                        actor_id=actor_id,
                        payload={"keys": sorted(str(key) for key in values)},
                    )
                await db.commit()
            except Exception:
                await db.rollback()
                raise

    async def get_config(self, key: str, default: Any = None) -> Any:
        rows = await self._connection().execute_fetchall(
            "SELECT value FROM minecraft_config WHERE key=?",
            (key,),
        )
        if not rows:
            return default
        try:
            return json.loads(rows[0]["value"])
        except (TypeError, json.JSONDecodeError):
            return default

    async def get_configs(self, keys: tuple[str, ...] | list[str]) -> dict[str, Any]:
        normalized = tuple(dict.fromkeys(str(key) for key in keys))
        if not normalized:
            return {}
        placeholders = ",".join("?" for _ in normalized)
        rows = await self._connection().execute_fetchall(
            f"SELECT key, value FROM minecraft_config WHERE key IN ({placeholders})",
            normalized,
        )
        values: dict[str, Any] = {}
        for row in rows:
            try:
                values[str(row["key"])] = json.loads(row["value"])
            except (TypeError, json.JSONDecodeError):
                continue
        return values

    async def claim_nonce(self, nonce: str, *, expires_at: int, now: Optional[int] = None) -> bool:
        current = _now() if now is None else int(now)
        async with self._write_lock:
            db = self._connection()
            try:
                await self._begin(db)
                await db.execute("DELETE FROM minecraft_bridge_nonces WHERE expires_at<=?", (current,))
                cursor = await db.execute(
                    "INSERT OR IGNORE INTO minecraft_bridge_nonces(nonce, expires_at) VALUES (?, ?)",
                    (nonce, int(expires_at)),
                )
                await db.commit()
                return cursor.rowcount == 1
            except Exception:
                await db.rollback()
                raise

    async def claim_bridge_event(
        self,
        idempotency_key: str,
        message_type: str,
        *,
        now: Optional[int] = None,
    ) -> bool:
        current = _now() if now is None else int(now)
        async with self._write_lock:
            db = self._connection()
            try:
                await db.execute(
                    "DELETE FROM minecraft_bridge_events WHERE processed_at<=?",
                    (current - 30 * 24 * 60 * 60,),
                )
                cursor = await db.execute(
                    "INSERT OR IGNORE INTO minecraft_bridge_events"
                    "(idempotency_key, message_type, processed_at) VALUES (?, ?, ?)",
                    (str(idempotency_key), str(message_type)[:64], current),
                )
                await db.commit()
                return cursor.rowcount == 1
            except Exception:
                await db.rollback()
                raise

    async def application_status_counts(self) -> dict[str, int]:
        rows = await self._connection().execute_fetchall(
            "SELECT status, COUNT(*) AS count FROM minecraft_applications GROUP BY status"
        )
        return {row["status"]: int(row["count"]) for row in rows}

    async def response_time_metrics(self, *, hours: int = 24) -> dict[str, int]:
        rows = await self._connection().execute_fetchall(
            "SELECT duration_ms FROM minecraft_command_log "
            "WHERE outcome='SUCCESS' AND created_at>=? ORDER BY duration_ms",
            (_now() - max(1, int(hours)) * 3600,),
        )
        values = [max(0, int(row["duration_ms"])) for row in rows]
        if not values:
            return {"samples": 0, "median_ms": 0, "p95_ms": 0}
        return {
            "samples": len(values),
            "median_ms": int(statistics.median(values)),
            "p95_ms": values[max(0, math.ceil(len(values) * 0.95) - 1)],
        }

    async def enqueue_delivery(
        self,
        *,
        dedupe_key: str,
        kind: str,
        target_id: int | str,
        payload: dict[str, Any],
        now: Optional[int] = None,
    ) -> None:
        current = _now() if now is None else int(now)
        async with self._write_lock:
            db = self._connection()
            await db.execute(
                "INSERT INTO minecraft_delivery_outbox"
                "(dedupe_key, kind, target_id, payload, next_attempt_at, created_at) "
                "VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT(dedupe_key) DO UPDATE SET "
                "payload=excluded.payload, next_attempt_at=MIN(next_attempt_at, excluded.next_attempt_at)",
                (
                    str(dedupe_key),
                    str(kind),
                    str(target_id),
                    json.dumps(payload, separators=(",", ":")),
                    current,
                    current,
                ),
            )
            await db.commit()

    async def get_due_deliveries(self, *, limit: int = 25, now: Optional[int] = None) -> list[DeliveryRecord]:
        current = _now() if now is None else int(now)
        rows = await self._connection().execute_fetchall(
            "SELECT * FROM minecraft_delivery_outbox WHERE next_attempt_at<=? ORDER BY id LIMIT ?",
            (current, max(1, min(int(limit), 100))),
        )
        return [
            DeliveryRecord(
                id=int(row["id"]),
                dedupe_key=str(row["dedupe_key"]),
                kind=str(row["kind"]),
                target_id=str(row["target_id"]),
                payload=json.loads(row["payload"]),
                attempts=int(row["attempts"]),
                next_attempt_at=int(row["next_attempt_at"]),
                last_error=row["last_error"],
            )
            for row in rows
        ]

    async def complete_delivery(self, delivery_id: int) -> None:
        async with self._write_lock:
            db = self._connection()
            await db.execute("DELETE FROM minecraft_delivery_outbox WHERE id=?", (int(delivery_id),))
            await db.commit()

    async def discard_deliveries(self, kind: str) -> int:
        async with self._write_lock:
            db = self._connection()
            cursor = await db.execute(
                "DELETE FROM minecraft_delivery_outbox WHERE kind=?",
                (str(kind),),
            )
            await db.commit()
            return cursor.rowcount

    async def fail_delivery(self, delivery_id: int, error: str, attempts: int) -> None:
        delay = min(3600, 5 * (2 ** min(max(0, int(attempts)), 9)))
        async with self._write_lock:
            db = self._connection()
            await db.execute(
                "UPDATE minecraft_delivery_outbox SET attempts=attempts+1, next_attempt_at=?, last_error=? "
                "WHERE id=?",
                (_now() + delay, str(error)[:500], int(delivery_id)),
            )
            await db.commit()

    async def delivery_counts(self) -> dict[str, int]:
        rows = await self._connection().execute_fetchall(
            "SELECT kind, COUNT(*) AS count FROM minecraft_delivery_outbox GROUP BY kind"
        )
        return {str(row["kind"]): int(row["count"]) for row in rows}

    async def audit_rows(self, application_id: int) -> list[dict[str, Any]]:
        rows = await self._connection().execute_fetchall(
            "SELECT * FROM minecraft_audit_log WHERE application_id=? ORDER BY id",
            (int(application_id),),
        )
        return [dict(row) for row in rows]

    # ------------------------------------------------------------------
    # Command audit trail (every invocation, not just application lifecycle)
    # ------------------------------------------------------------------

    async def record_command_log(self, record: Any) -> int:
        """Persist one CommandAuditRecord. Returns its new row id."""
        async with self._write_lock:
            db = self._connection()
            try:
                await self._begin(db)
                cursor = await db.execute(
                    "INSERT INTO minecraft_command_log ("
                    "source, command, actor_discord_id, actor_label, target_discord_id, "
                    "channel_id, outcome, risk, duration_ms, correlation_id, detail, options, created_at"
                    ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    (
                        record.source,
                        record.command,
                        str(record.user_id),
                        record.user_label,
                        str(record.target_id) if record.target_id else None,
                        str(record.channel_id) if record.channel_id else None,
                        record.outcome,
                        record.risk,
                        int(record.duration_ms),
                        record.correlation_id,
                        record.detail,
                        json.dumps(list(record.options)),
                        int(record.created_at),
                    ),
                )
                row_id = cursor.lastrowid
                await db.commit()
            except Exception:
                await db.rollback()
                raise
        return int(row_id)

    async def list_command_log(
        self,
        *,
        actor_id: Optional[int | str] = None,
        command: Optional[str] = None,
        limit: int = 20,
    ) -> list[dict[str, Any]]:
        clauses: list[str] = []
        params: list[Any] = []
        if actor_id:
            clauses.append("actor_discord_id=?")
            params.append(str(actor_id))
        if command:
            clauses.append("command LIKE ? ESCAPE '\\'")
            params.append(_like_contains(str(command)))
        where = f"WHERE {' AND '.join(clauses)}" if clauses else ""
        params.append(max(1, min(50, int(limit))))
        rows = await self._connection().execute_fetchall(
            f"SELECT * FROM minecraft_command_log {where} ORDER BY id DESC LIMIT ?",
            tuple(params),
        )
        return [dict(row) for row in rows]

    async def count_command_log(
        self,
        *,
        actor_id: Optional[int | str] = None,
        command: Optional[str] = None,
    ) -> int:
        clauses: list[str] = []
        params: list[Any] = []
        if actor_id:
            clauses.append("actor_discord_id=?")
            params.append(str(actor_id))
        if command:
            clauses.append("command LIKE ? ESCAPE '\\'")
            params.append(_like_contains(str(command)))
        where = f"WHERE {' AND '.join(clauses)}" if clauses else ""
        rows = await self._connection().execute_fetchall(
            f"SELECT COUNT(*) AS count FROM minecraft_command_log {where}", tuple(params)
        )
        return int(rows[0]["count"]) if rows else 0

    async def prune_command_log(self, *, now: Optional[int] = None) -> int:
        """Age out old rows and cap total size so the trail cannot grow unbounded."""
        cutoff = (_now() if now is None else int(now)) - COMMAND_LOG_RETENTION_DAYS * 86400
        deleted = 0
        async with self._write_lock:
            db = self._connection()
            try:
                await self._begin(db)
                cursor = await db.execute(
                    "DELETE FROM minecraft_command_log WHERE created_at < ?", (cutoff,)
                )
                deleted += max(0, int(cursor.rowcount))
                cursor = await db.execute(
                    "DELETE FROM minecraft_command_log WHERE id NOT IN "
                    "(SELECT id FROM minecraft_command_log ORDER BY id DESC LIMIT ?)",
                    (COMMAND_LOG_RETENTION_ROWS,),
                )
                deleted += max(0, int(cursor.rowcount))
                await db.commit()
            except Exception:
                await db.rollback()
                raise
        return deleted

    async def find_applications_by_username(
        self,
        username: str,
        *,
        limit: int = 10,
    ) -> list[MinecraftApplication]:
        """Reverse lookup: which applications claimed this Minecraft username."""
        needle = " ".join(str(username or "").strip().split()).casefold()
        if not needle:
            return []
        rows = await self._connection().execute_fetchall(
            "SELECT * FROM minecraft_applications WHERE normalized_username LIKE ? ESCAPE '\\' "
            "ORDER BY id DESC LIMIT ?",
            (_like_contains(needle), max(1, min(25, int(limit)))),
        )
        return [self._application(row) for row in rows]

    async def find_accounts_by_username(
        self,
        username: str,
        *,
        limit: int = 10,
    ) -> list[dict[str, Any]]:
        """Find currently linked accounts by their latest known username."""
        needle = " ".join(str(username or "").strip().split()).casefold()
        if not needle:
            return []
        rows = await self._connection().execute_fetchall(
            "SELECT * FROM minecraft_accounts WHERE current_username LIKE ? ESCAPE '\\' COLLATE NOCASE "
            "ORDER BY id DESC LIMIT ?",
            (_like_contains(needle), max(1, min(25, int(limit)))),
        )
        return [dict(row) for row in rows]

    async def write_audit(
        self,
        action: str,
        *,
        application_id: Optional[int] = None,
        actor_id: Optional[int | str] = None,
        target_id: Optional[int | str] = None,
        payload: Optional[dict[str, Any]] = None,
    ) -> None:
        async with self._write_lock:
            db = self._connection()
            try:
                await self._begin(db)
                await self._audit(
                    db,
                    action,
                    application_id=application_id,
                    actor_id=actor_id,
                    target_id=target_id,
                    payload=payload,
                )
                await db.commit()
            except Exception:
                await db.rollback()
                raise
