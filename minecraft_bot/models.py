"""Typed records shared by the Minecraft bot runtime."""

from __future__ import annotations

from dataclasses import dataclass
try:
    from enum import StrEnum
except ImportError:  # Python 3.10 and the repository's legacy local venv
    from enum import Enum

    class StrEnum(str, Enum):
        def __str__(self) -> str:
            return self.value
from typing import Any, Optional


class Edition(StrEnum):
    JAVA = "JAVA"
    BEDROCK = "BEDROCK"


class AccessStatus(StrEnum):
    """Access is a two-state affair now: you are verified, or you are not.

    The old application ladder (PENDING_APPLICATION, PENDING_REVIEW,
    APPROVAL_QUEUED, DENIED) is gone with the review queue that gave it meaning.
    """

    PENDING_VERIFICATION = "PENDING_VERIFICATION"
    VERIFIED = "VERIFIED"
    EXPIRED = "EXPIRED"
    CANCELLED = "CANCELLED"
    REVOKED = "REVOKED"


class BridgeAction(StrEnum):
    APPROVE = "APPROVE"
    REVOKE = "REVOKE"
    KICK = "KICK"
    SYNC_PENDING = "SYNC_PENDING"
    REMOVE_PENDING = "REMOVE_PENDING"
    STATUS = "STATUS"


#: Statuses that still occupy a claimed username. A pending verification
#: reserves the name so two members cannot race for it; verified keeps it
#: owned. Members may hold many verified accounts at once.
ACTIVE_STATUSES = (
    AccessStatus.PENDING_VERIFICATION,
    AccessStatus.VERIFIED,
)


@dataclass(frozen=True)
class MinecraftAccess:
    id: int
    guild_id: str
    discord_user_id: str
    edition: Edition
    claimed_username: str
    normalized_username: str
    status: AccessStatus
    verification_expires_at: int
    created_at: int
    updated_at: int
    verified_username: Optional[str] = None
    minecraft_uuid: Optional[str] = None
    xuid: Optional[str] = None
    verified_at: Optional[int] = None
    revoked_by: Optional[str] = None
    revoked_at: Optional[int] = None
    internal_note: Optional[str] = None
    auto_detect_edition: bool = False
    status_channel_id: Optional[str] = None
    status_message_id: Optional[str] = None


@dataclass(frozen=True)
class OutboxRecord:
    id: int
    idempotency_key: str
    action: BridgeAction
    payload: dict[str, Any]
    status: str
    attempts: int
    last_error: Optional[str]
    access_id: Optional[int]
    created_at: int
    processed_at: Optional[int]


@dataclass(frozen=True)
class DeliveryRecord:
    id: int
    dedupe_key: str
    kind: str
    target_id: str
    payload: dict[str, Any]
    attempts: int
    next_attempt_at: int
    last_error: Optional[str]


class InvalidTransition(RuntimeError):
    """The record is no longer in the state required by an operation."""


class DuplicateActiveVerification(RuntimeError):
    """A Discord member already has a pending verification in flight."""


class AccountEditionAlreadyLinked(RuntimeError):
    """Kept for older callers. Linking is no longer capped per edition."""
