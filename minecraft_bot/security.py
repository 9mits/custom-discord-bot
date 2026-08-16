"""Signed bridge-envelope creation and verification."""

from __future__ import annotations

import hashlib
import hmac
import json
import secrets
import time
from typing import Any, MutableSet, Optional


MAX_CLOCK_SKEW_SECONDS = 30


def canonical_message(envelope: dict[str, Any]) -> bytes:
    unsigned = {key: value for key, value in envelope.items() if key != "signature"}
    return json.dumps(
        unsigned,
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=False,
    ).encode("utf-8")


def sign_envelope(secret: bytes, envelope: dict[str, Any]) -> str:
    return hmac.new(secret, canonical_message(envelope), hashlib.sha256).hexdigest()


def create_envelope(
    secret: bytes,
    message_type: str,
    payload: dict[str, Any],
    *,
    idempotency_key: Optional[str] = None,
    now: Optional[int] = None,
    nonce: Optional[str] = None,
) -> dict[str, Any]:
    envelope = {
        "type": message_type,
        "timestamp": int(time.time() if now is None else now),
        "nonce": nonce or secrets.token_hex(16),
        "idempotency_key": idempotency_key or secrets.token_hex(16),
        "payload": payload,
    }
    envelope["signature"] = sign_envelope(secret, envelope)
    return envelope


def verify_envelope(
    secret: bytes,
    envelope: dict[str, Any],
    *,
    used_nonces: MutableSet[str],
    now: Optional[int] = None,
    max_clock_skew: int = MAX_CLOCK_SKEW_SECONDS,
) -> bool:
    try:
        timestamp = int(envelope["timestamp"])
        nonce = str(envelope["nonce"])
        signature = str(envelope["signature"])
        message_type = envelope["type"]
        idempotency_key = envelope["idempotency_key"]
        payload = envelope["payload"]
    except (KeyError, TypeError, ValueError):
        return False
    if not nonce or len(nonce) > 128 or not isinstance(message_type, str):
        return False
    if not isinstance(idempotency_key, str) or not isinstance(payload, dict):
        return False
    current = int(time.time() if now is None else now)
    if abs(current - timestamp) > max_clock_skew or nonce in used_nonces:
        return False
    if not isinstance(signature, str) or len(signature) != 64:
        return False
    try:
        int(signature, 16)
    except ValueError:
        return False
    expected = sign_envelope(secret, envelope)
    try:
        if not hmac.compare_digest(expected, signature):
            return False
    except (TypeError, ValueError):
        return False
    used_nonces.add(nonce)
    return True
