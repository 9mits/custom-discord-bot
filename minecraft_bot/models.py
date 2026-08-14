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


class ApplicationStatus(StrEnum):
    PENDING_VERIFICATION = "PENDING_VERIFICATION"
    PENDING_APPLICATION = "PENDING_APPLICATION"
    PENDING_REVIEW = "PENDING_REVIEW"
    APPROVAL_QUEUED = "APPROVAL_QUEUED"
    APPROVED = "APPROVED"
    DENIED = "DENIED"
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


ACTIVE_APPLICATION_STATUSES = (
    ApplicationStatus.PENDING_VERIFICATION,
    ApplicationStatus.PENDING_APPLICATION,
    ApplicationStatus.PENDING_REVIEW,
    ApplicationStatus.APPROVAL_QUEUED,
)


@dataclass(frozen=True)
class MinecraftApplication:
    id: int
    guild_id: str
    discord_user_id: str
    edition: Edition
    claimed_username: str
    normalized_username: str
    answers: dict[str, str]
    status: ApplicationStatus
    verification_expires_at: int
    created_at: int
    updated_at: int
    verified_username: Optional[str] = None
    minecraft_uuid: Optional[str] = None
    xuid: Optional[str] = None
    verified_at: Optional[int] = None
    reviewed_by: Optional[str] = None
    reviewed_at: Optional[int] = None
    applicant_reason: Optional[str] = None
    internal_note: Optional[str] = None
    review_channel_id: Optional[str] = None
    review_message_id: Optional[str] = None
    auto_detect_edition: bool = False
    status_channel_id: Optional[str] = None
    status_message_id: Optional[str] = None
    decision_channel_id: Optional[str] = None
    decision_message_id: Optional[str] = None


@dataclass(frozen=True)
class OutboxRecord:
    id: int
    idempotency_key: str
    action: BridgeAction
    payload: dict[str, Any]
    status: str
    attempts: int
    last_error: Optional[str]
    application_id: Optional[int]
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


class DuplicateActiveApplication(RuntimeError):
    """A Discord member already has an unfinished application."""


class AccountEditionAlreadyLinked(RuntimeError):
    """A Discord member already owns the allowed account for this edition."""
