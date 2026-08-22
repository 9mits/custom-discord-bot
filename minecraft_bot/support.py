"""Durable cross-process handoff from Minecraft verification to modmail."""

from __future__ import annotations

import json
import os
import time
import uuid
from pathlib import Path
from typing import Any


def support_queue_path() -> Path:
    configured = os.getenv("MINECRAFT_SUPPORT_QUEUE_DIR")
    return Path(configured) if configured else Path("runtime/minecraft/support_requests")


def enqueue_support_request(
    *,
    guild_id: int | str,
    discord_user_id: int | str,
    access_id: int | None,
    status: str,
    username: str | None,
) -> str:
    queue = support_queue_path()
    queue.mkdir(parents=True, exist_ok=True)
    request_id = uuid.uuid4().hex
    payload = {
        "version": 1,
        "request_id": request_id,
        "guild_id": str(guild_id),
        "discord_user_id": str(discord_user_id),
        # Written artifact under "version": 1 — the key name is part of that shape.
        "application_id": int(access_id) if access_id is not None else None,
        "status": str(status),
        "username": str(username)[:16] if username else None,
        "created_at": int(time.time()),
    }
    temporary = queue / f".{request_id}.tmp"
    destination = queue / f"{request_id}.json"
    with temporary.open("x", encoding="utf-8") as handle:
        json.dump(payload, handle, separators=(",", ":"))
        handle.flush()
        os.fsync(handle.fileno())
    os.replace(temporary, destination)
    return request_id


def list_support_requests() -> list[Path]:
    queue = support_queue_path()
    if not queue.exists():
        return []
    current = time.time()
    for stale in queue.glob("*.processing"):
        try:
            if current - stale.stat().st_mtime > 120:
                stale.replace(stale.with_suffix(".json"))
        except OSError:
            pass
    return sorted(queue.glob("*.json"), key=lambda path: path.stat().st_mtime)


def read_support_request(path: Path) -> dict[str, Any] | None:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None


def claim_support_request(path: Path) -> tuple[Path, dict[str, Any]] | None:
    claimed = path.with_suffix(".processing")
    try:
        path.replace(claimed)
        return claimed, json.loads(claimed.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        try:
            claimed.replace(claimed.with_suffix(".invalid"))
        except OSError:
            pass
        return None
    except (FileNotFoundError, FileExistsError, OSError):
        return None


def release_support_request(path: Path) -> None:
    try:
        path.replace(path.with_suffix(".json"))
    except OSError:
        pass
