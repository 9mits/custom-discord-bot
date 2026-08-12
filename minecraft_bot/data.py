"""Transactional SQLite storage for Minecraft applications and bridge work."""

from __future__ import annotations

import asyncio
import json
import re
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Optional

import aiosqlite

from .models import (
    ACTIVE_APPLICATION_STATUSES,
    ApplicationStatus,
    BridgeAction,
    DuplicateActiveApplication,
    Edition,
    InvalidTransition,
    MinecraftApplication,
    OutboxRecord,
)


JAVA_USERNAME = re.compile(r"^[A-Za-z0-9_]{3,16}$")
BEDROCK_USERNAME = re.compile(r"^[\w -]{1,16}$", re.UNICODE)
SCHEMA_VERSION = 1

SCHEMA_SQL = """
CREATE TABLE IF NOT EXISTS minecraft_applications (
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
        'PENDING_VERIFICATION', 'PENDING_REVIEW', 'APPROVAL_QUEUED', 'APPROVED',
        'DENIED', 'EXPIRED', 'CANCELLED', 'REVOKED'
    )),
    verification_expires_at INTEGER NOT NULL,
    verified_at INTEGER,
    reviewed_by TEXT,
    reviewed_at INTEGER,
    applicant_reason TEXT,
    internal_note TEXT,
    review_channel_id TEXT,
    review_message_id TEXT,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_minecraft_applications_user
    ON minecraft_applications(guild_id, discord_user_id, id DESC);
CREATE INDEX IF NOT EXISTS idx_minecraft_applications_verification
    ON minecraft_applications(status, verification_expires_at);
CREATE INDEX IF NOT EXISTS idx_minecraft_applications_review_message
    ON minecraft_applications(review_message_id);

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

    async def create_application(
        self,
        *,
        guild_id: int,
        discord_user_id: int,
        edition: Edition,
        claimed_username: str,
        answers: dict[str, str],
        verification_seconds: int = 600,
        now: Optional[int] = None,
    ) -> MinecraftApplication:
        current = _now() if now is None else int(now)
        claimed, normalized = normalize_username(edition, claimed_username)
        why = str(answers.get("why", "")).strip()
        about = str(answers.get("about", "")).strip()
        if not 10 <= len(why) <= 500 or not 10 <= len(about) <= 1000:
            raise ValueError("Application answers must be between 10 and their displayed limits")
        db = self._connection()
        async with self._write_lock:
            try:
                await self._begin(db)
                expired_rows = await db.execute_fetchall(
                    "SELECT id FROM minecraft_applications WHERE guild_id=? AND discord_user_id=? "
                    "AND status=? AND verification_expires_at<=?",
                    (
                        str(guild_id),
                        str(discord_user_id),
                        ApplicationStatus.PENDING_VERIFICATION.value,
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
                            ApplicationStatus.PENDING_VERIFICATION.value,
                        ),
                    )
                    await self._queue(
                        db,
                        BridgeAction.REMOVE_PENDING,
                        {"application_id": expired_id},
                        idempotency_key=f"application:{expired_id}:expire",
                        application_id=expired_id,
                        timestamp=current,
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
                    "status, verification_expires_at, created_at, updated_at) "
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    (
                        str(guild_id),
                        str(discord_user_id),
                        edition.value,
                        claimed,
                        normalized,
                        json.dumps({"why": why, "about": about}, separators=(",", ":")),
                        ApplicationStatus.PENDING_VERIFICATION.value,
                        current + verification_seconds,
                        current,
                        current,
                    ),
                )
                application_id = int(cursor.lastrowid)
                payload = {
                    "application_id": application_id,
                    "edition": edition.value,
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
                    payload={"edition": edition.value, "claimed_username": claimed},
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

    async def list_accounts_for_user(self, discord_user_id: int | str) -> list[dict[str, Any]]:
        rows = await self._connection().execute_fetchall(
            "SELECT * FROM minecraft_accounts WHERE discord_user_id=? ORDER BY id DESC",
            (str(discord_user_id),),
        )
        return [dict(row) for row in rows]

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
                    "SELECT * FROM minecraft_applications WHERE status=? AND verification_expires_at<=? "
                    "ORDER BY verification_expires_at LIMIT ?",
                    (ApplicationStatus.PENDING_VERIFICATION.value, current, max(1, min(limit, 500))),
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
                            ApplicationStatus.PENDING_VERIFICATION.value,
                        ),
                    )
                    await self._queue(
                        db,
                        BridgeAction.REMOVE_PENDING,
                        {"application_id": application.id},
                        idempotency_key=f"application:{application.id}:expire",
                        application_id=application.id,
                        timestamp=current,
                    )
                    await self._audit(
                        db,
                        "VERIFICATION_EXPIRED",
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
                    raise InvalidTransition("Verification window expired")
                if application.edition is not edition:
                    raise InvalidTransition("Verified edition does not match the application")
                if normalized_actual != application.normalized_username:
                    raise InvalidTransition("Verified username does not match the application")
                if edition is Edition.BEDROCK and not xuid:
                    raise InvalidTransition("Bedrock verification did not include a Floodgate XUID")

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

                await db.execute(
                    "UPDATE minecraft_applications SET verified_username=?, minecraft_uuid=?, xuid=?, "
                    "status=?, verified_at=?, updated_at=? WHERE id=? AND status=?",
                    (
                        current_username,
                        minecraft_uuid,
                        str(xuid) if xuid is not None else None,
                        ApplicationStatus.PENDING_REVIEW.value,
                        current,
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
                await self._queue(
                    db,
                    BridgeAction.REMOVE_PENDING,
                    {"application_id": application.id},
                    idempotency_key=f"application:{application.id}:verified",
                    application_id=application.id,
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
                    "SELECT discord_user_id FROM minecraft_applications WHERE id=?",
                    (int(application_id),),
                )
                await self._audit(
                    db,
                    "APPLICATION_DENIED",
                    application_id=application_id,
                    actor_id=moderator_id,
                    target_id=rows[0]["discord_user_id"] if rows else None,
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

    async def get_outbox_batch(self, *, limit: int = 50) -> list[OutboxRecord]:
        rows = await self._connection().execute_fetchall(
            "SELECT * FROM minecraft_bridge_outbox WHERE status IN ('PENDING', 'SENT') "
            "ORDER BY id LIMIT ?",
            (max(1, min(limit, 100)),),
        )
        return [self._outbox(row) for row in rows]

    async def mark_outbox_sent(self, record_id: int) -> None:
        async with self._write_lock:
            db = self._connection()
            await db.execute(
                "UPDATE minecraft_bridge_outbox SET status='SENT', attempts=attempts+1, last_error=NULL WHERE id=?",
                (int(record_id),),
            )
            await db.commit()

    async def mark_outbox_failed(self, idempotency_key: str, error: str) -> Optional[OutboxRecord]:
        async with self._write_lock:
            db = self._connection()
            await db.execute(
                "UPDATE minecraft_bridge_outbox SET status='FAILED', last_error=? WHERE idempotency_key=?",
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
                if record.status == "PROCESSED":
                    await db.rollback()
                else:
                    newly_processed = True
                    await db.execute(
                        "UPDATE minecraft_bridge_outbox SET status='PROCESSED', processed_at=?, last_error=NULL "
                        "WHERE idempotency_key=?",
                        (current, idempotency_key),
                    )
                    if application_id is not None and record.action is BridgeAction.APPROVE:
                        await db.execute(
                            "UPDATE minecraft_applications SET status=?, updated_at=? WHERE id=? AND status=?",
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
                            "UPDATE minecraft_applications SET status=?, updated_at=? WHERE id=? AND status=?",
                            (
                                ApplicationStatus.REVOKED.value,
                                current,
                                application_id,
                                ApplicationStatus.APPROVED.value,
                            ),
                        )
                        await self._audit(
                            db,
                            "ACCESS_REVOKED",
                            application_id=application_id,
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
        async with self._write_lock:
            db = self._connection()
            await db.execute(
                "INSERT INTO minecraft_config(key, value) VALUES (?, ?) "
                "ON CONFLICT(key) DO UPDATE SET value=excluded.value",
                (key, json.dumps(value, separators=(",", ":"))),
            )
            await db.commit()

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

    async def application_status_counts(self) -> dict[str, int]:
        rows = await self._connection().execute_fetchall(
            "SELECT status, COUNT(*) AS count FROM minecraft_applications GROUP BY status"
        )
        return {row["status"]: int(row["count"]) for row in rows}

    async def audit_rows(self, application_id: int) -> list[dict[str, Any]]:
        rows = await self._connection().execute_fetchall(
            "SELECT * FROM minecraft_audit_log WHERE application_id=? ORDER BY id",
            (int(application_id),),
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
