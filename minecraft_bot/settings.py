"""Persistent Discord-facing configuration for the Minecraft bot."""

from __future__ import annotations

import re
from dataclasses import dataclass, replace
from typing import Any, Mapping


DEFAULT_JAVA_ADDRESS = "104.254.131.178:50548"
DEFAULT_BEDROCK_ADDRESS = "104.254.131.178"
DEFAULT_BEDROCK_PORT = 50549

SETTING_KEYS = (
    "application_channel_id",
    "review_channel_id",
    "mod_role_id",
    "member_role_id",
    "application_log_channel_id",
    "verification_log_channel_id",
    "player_log_channel_id",
    "command_log_channel_id",
    "critical_log_channel_id",
    "chat_channel_id",
    "java_address",
    "bedrock_address",
    "bedrock_port",
)

_HOST = re.compile(r"^(?:[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?|\[[0-9A-Fa-f:]+\])$")


def _positive_int(value: Any, default: int = 0) -> int:
    try:
        parsed = int(value)
    except (TypeError, ValueError):
        return default
    return parsed if parsed > 0 else default


def _normalize_host(value: Any, *, label: str) -> str:
    normalized = str(value or "").strip()
    if not normalized or len(normalized) > 253 or not _HOST.fullmatch(normalized):
        raise ValueError(f"{label} must be a valid IP address or hostname")
    return normalized


def normalize_java_address(value: Any) -> str:
    normalized = str(value or "").strip()
    if not normalized or len(normalized) > 260:
        raise ValueError("Java address is required")
    if normalized.startswith("["):
        closing = normalized.find("]")
        if closing < 0:
            raise ValueError("Java IPv6 addresses must be enclosed in brackets")
        host = normalized[: closing + 1]
        remainder = normalized[closing + 1 :]
    else:
        host, separator, port_text = normalized.rpartition(":")
        if not separator:
            raise ValueError("Java address must include a port, for example server.example:25565")
        remainder = f":{port_text}"
    if not normalized.startswith("["):
        _normalize_host(host, label="Java host")
    else:
        _normalize_host(host, label="Java host")
    if not remainder.startswith(":"):
        raise ValueError("Java address must include a port")
    try:
        port = int(remainder[1:])
    except ValueError as exc:
        raise ValueError("Java port must be a number") from exc
    if not 1 <= port <= 65535:
        raise ValueError("Java port must be between 1 and 65535")
    return f"{host}:{port}"


def normalize_bedrock_address(value: Any) -> str:
    return _normalize_host(value, label="Bedrock address")


def normalize_port(value: Any) -> int:
    try:
        port = int(str(value).strip())
    except (TypeError, ValueError) as exc:
        raise ValueError("Bedrock port must be a number") from exc
    if not 1 <= port <= 65535:
        raise ValueError("Bedrock port must be between 1 and 65535")
    return port


@dataclass(frozen=True)
class MinecraftSettings:
    application_channel_id: int = 0
    review_channel_id: int = 0
    mod_role_id: int = 0
    member_role_id: int = 0
    application_log_channel_id: int = 0
    verification_log_channel_id: int = 0
    player_log_channel_id: int = 0
    command_log_channel_id: int = 0
    critical_log_channel_id: int = 0
    chat_channel_id: int = 0
    java_address: str = DEFAULT_JAVA_ADDRESS
    bedrock_address: str = DEFAULT_BEDROCK_ADDRESS
    bedrock_port: int = DEFAULT_BEDROCK_PORT

    @classmethod
    def from_sources(cls, bootstrap_config, stored: Mapping[str, Any]) -> "MinecraftSettings":
        def stored_or(name: str, fallback: Any) -> Any:
            return stored[name] if name in stored else fallback

        java_fallback = getattr(bootstrap_config, "java_address", DEFAULT_JAVA_ADDRESS)
        bedrock_fallback = getattr(bootstrap_config, "bedrock_address", DEFAULT_BEDROCK_ADDRESS)
        port_fallback = getattr(bootstrap_config, "bedrock_port", DEFAULT_BEDROCK_PORT)
        try:
            java_address = normalize_java_address(stored_or("java_address", java_fallback))
        except ValueError:
            java_address = DEFAULT_JAVA_ADDRESS
        try:
            bedrock_address = normalize_bedrock_address(stored_or("bedrock_address", bedrock_fallback))
        except ValueError:
            bedrock_address = DEFAULT_BEDROCK_ADDRESS
        try:
            bedrock_port = normalize_port(stored_or("bedrock_port", port_fallback))
        except ValueError:
            bedrock_port = DEFAULT_BEDROCK_PORT
        return cls(
            application_channel_id=_positive_int(
                stored_or("application_channel_id", getattr(bootstrap_config, "application_channel_id", 0))
            ),
            review_channel_id=_positive_int(
                stored_or("review_channel_id", getattr(bootstrap_config, "review_channel_id", 0))
            ),
            mod_role_id=_positive_int(stored_or("mod_role_id", getattr(bootstrap_config, "mod_role_id", 0))),
            member_role_id=_positive_int(
                stored_or("member_role_id", getattr(bootstrap_config, "member_role_id", 0))
            ),
            application_log_channel_id=_positive_int(stored_or("application_log_channel_id", 0)),
            verification_log_channel_id=_positive_int(stored_or("verification_log_channel_id", 0)),
            player_log_channel_id=_positive_int(stored_or("player_log_channel_id", 0)),
            command_log_channel_id=_positive_int(stored_or("command_log_channel_id", 0)),
            critical_log_channel_id=_positive_int(stored_or("critical_log_channel_id", 0)),
            chat_channel_id=_positive_int(stored_or("chat_channel_id", 0)),
            java_address=java_address,
            bedrock_address=bedrock_address,
            bedrock_port=bedrock_port,
        )

    def with_updates(self, **updates: Any) -> "MinecraftSettings":
        unknown = set(updates) - set(SETTING_KEYS)
        if unknown:
            raise ValueError(f"Unsupported Minecraft setting(s): {', '.join(sorted(unknown))}")
        normalized = dict(updates)
        for key in (
            "application_channel_id",
            "review_channel_id",
            "mod_role_id",
            "member_role_id",
        ):
            if key in normalized:
                value = _positive_int(normalized[key])
                if not value:
                    raise ValueError(f"{key} must be a positive Discord ID")
                normalized[key] = value
        for key in (
            "application_log_channel_id",
            "verification_log_channel_id",
            "player_log_channel_id",
            "command_log_channel_id",
            "critical_log_channel_id",
            "chat_channel_id",
        ):
            if key in normalized:
                normalized[key] = _positive_int(normalized[key])
        if "java_address" in normalized:
            normalized["java_address"] = normalize_java_address(normalized["java_address"])
        if "bedrock_address" in normalized:
            normalized["bedrock_address"] = normalize_bedrock_address(normalized["bedrock_address"])
        if "bedrock_port" in normalized:
            normalized["bedrock_port"] = normalize_port(normalized["bedrock_port"])
        candidate = replace(self, **normalized)
        if (
            candidate.application_channel_id
            and candidate.application_channel_id == candidate.review_channel_id
        ):
            raise ValueError("Application and review channels must be different")
        if candidate.mod_role_id and candidate.mod_role_id == candidate.member_role_id:
            raise ValueError("Moderator and approved-member roles must be different")
        return candidate

    def persistent_values(self) -> dict[str, Any]:
        return {key: getattr(self, key) for key in SETTING_KEYS}
