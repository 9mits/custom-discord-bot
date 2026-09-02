"""Environment configuration for the isolated Minecraft access bot."""

from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

from .settings import DEFAULT_BEDROCK_ADDRESS, DEFAULT_JAVA_ADDRESS
from urllib.parse import urlsplit


def _required(name: str) -> str:
    value = os.environ.get(name, "").strip()
    if not value:
        raise RuntimeError(f"{name} is required for the Minecraft access bot")
    return value


def _snowflake(name: str, *, required: bool = True) -> int:
    value = os.environ.get(name, "").strip()
    if not value and not required:
        return 0
    if not value:
        raise RuntimeError(f"{name} is required for the Minecraft access bot")
    try:
        parsed = int(value)
    except ValueError as exc:
        raise RuntimeError(f"{name} must be a Discord snowflake") from exc
    if parsed <= 0:
        raise RuntimeError(f"{name} must be a positive Discord snowflake")
    return parsed


def decode_bridge_secret(value: str) -> bytes:
    """Decode the required 32-byte key from its 64-character hex form."""
    try:
        decoded = bytes.fromhex(value)
    except ValueError as exc:
        raise RuntimeError("MINECRAFT_BRIDGE_SECRET must be 64 hexadecimal characters") from exc
    if len(decoded) != 32:
        raise RuntimeError("MINECRAFT_BRIDGE_SECRET must decode to exactly 32 bytes")
    return decoded


@dataclass(frozen=True)
class MinecraftConfig:
    discord_token: str
    guild_id: int
    application_channel_id: int
    review_channel_id: int
    mod_role_id: int
    member_role_id: int
    bridge_secret: bytes
    server_id: str
    java_address: str
    bedrock_address: str
    bedrock_port: int
    bridge_path: str
    bridge_host: str
    bridge_port: int
    data_dir: Path
    allow_insecure_localhost: bool
    bridge_tls_cert_path: Optional[Path] = None
    bridge_tls_key_path: Optional[Path] = None
    dashboard_enabled: bool = False
    dashboard_host: str = "127.0.0.1"
    dashboard_port: int = 8090
    dashboard_public_url: str = "http://127.0.0.1:8090"
    dashboard_client_secret: str = ""
    #: Header token letting automation drive the settings API without a Discord session.
    #: Empty disables it entirely, which is the default and the safe state.
    dashboard_automation_token: str = ""
    #: The Minecraft account an automated change is attributed to. The plugin authorises
    #: against LuckPerms owner on a real account, so automation has to name one.
    dashboard_automation_uuid: str = ""

    @property
    def database_path(self) -> Path:
        return self.data_dir / "minecraft.db"

    @classmethod
    def from_env(cls) -> "MinecraftConfig":
        bridge_path = os.environ.get("MINECRAFT_BRIDGE_PATH", "/minecraft-bridge").strip()
        if not bridge_path.startswith("/") or "?" in bridge_path or "#" in bridge_path:
            raise RuntimeError("MINECRAFT_BRIDGE_PATH must be an absolute URL path")
        server_id = os.environ.get("MINECRAFT_SERVER_ID", "mysterious-smp-x").strip()
        if not server_id or len(server_id) > 64:
            raise RuntimeError("MINECRAFT_SERVER_ID must contain 1-64 characters")
        try:
            bridge_port = int(os.environ.get("MINECRAFT_BRIDGE_PORT", "8080"))
            bedrock_port = int(os.environ.get("MINECRAFT_BEDROCK_PORT", "50549"))
            dashboard_port = int(os.environ.get("MINECRAFT_DASHBOARD_PORT", "8090"))
        except ValueError as exc:
            raise RuntimeError("Minecraft port values must be integers") from exc
        if (not 1 <= bridge_port <= 65535 or not 1 <= bedrock_port <= 65535
                or not 1 <= dashboard_port <= 65535):
            raise RuntimeError("Minecraft port values must be between 1 and 65535")
        data_dir = Path(os.environ.get("MINECRAFT_DATA_DIR", "runtime/minecraft")).expanduser()
        if not data_dir.is_absolute():
            data_dir = Path.cwd() / data_dir
        tls_cert_text = os.environ.get("MINECRAFT_BRIDGE_TLS_CERT", "").strip()
        tls_key_text = os.environ.get("MINECRAFT_BRIDGE_TLS_KEY", "").strip()
        if bool(tls_cert_text) != bool(tls_key_text):
            raise RuntimeError(
                "MINECRAFT_BRIDGE_TLS_CERT and MINECRAFT_BRIDGE_TLS_KEY must be set together"
            )

        def optional_path(value: str) -> Optional[Path]:
            if not value:
                return None
            path = Path(value).expanduser()
            if not path.is_absolute():
                path = Path.cwd() / path
            return path.resolve()

        return cls(
            discord_token=_required("MINECRAFT_DISCORD_BOT_TOKEN"),
            guild_id=_snowflake("MINECRAFT_GUILD_ID"),
            application_channel_id=_snowflake("MINECRAFT_APPLICATION_CHANNEL_ID", required=False),
            review_channel_id=_snowflake("MINECRAFT_REVIEW_CHANNEL_ID", required=False),
            mod_role_id=_snowflake("MINECRAFT_MOD_ROLE_ID", required=False),
            member_role_id=_snowflake("MINECRAFT_MEMBER_ROLE_ID", required=False),
            bridge_secret=decode_bridge_secret(_required("MINECRAFT_BRIDGE_SECRET")),
            server_id=server_id,
            java_address=os.environ.get(
                "MINECRAFT_JAVA_ADDRESS", DEFAULT_JAVA_ADDRESS
            ).strip() or DEFAULT_JAVA_ADDRESS,
            bedrock_address=os.environ.get(
                "MINECRAFT_BEDROCK_ADDRESS", DEFAULT_BEDROCK_ADDRESS
            ).strip() or DEFAULT_BEDROCK_ADDRESS,
            bedrock_port=bedrock_port,
            bridge_path=bridge_path,
            bridge_host=os.environ.get("MINECRAFT_BRIDGE_HOST", "0.0.0.0").strip(),
            bridge_port=bridge_port,
            data_dir=data_dir.resolve(),
            allow_insecure_localhost=os.environ.get(
                "MINECRAFT_ALLOW_INSECURE_LOCALHOST", "0"
            ).strip().lower() in {"1", "true", "yes"},
            bridge_tls_cert_path=optional_path(tls_cert_text),
            bridge_tls_key_path=optional_path(tls_key_text),
            dashboard_enabled=os.environ.get("MINECRAFT_DASHBOARD_ENABLED", "0")
            .strip().lower() in {"1", "true", "yes"},
            dashboard_host=os.environ.get(
                "MINECRAFT_DASHBOARD_HOST", "127.0.0.1"
            ).strip() or "127.0.0.1",
            dashboard_port=dashboard_port,
            dashboard_public_url=os.environ.get(
                "MINECRAFT_DASHBOARD_PUBLIC_URL", "http://127.0.0.1:8090"
            ).strip().rstrip("/"),
            dashboard_automation_token=os.environ.get(
                "MINECRAFT_DASHBOARD_AUTOMATION_TOKEN", ""
            ).strip(),
            dashboard_automation_uuid=os.environ.get(
                "MINECRAFT_DASHBOARD_AUTOMATION_UUID", ""
            ).strip(),
            dashboard_client_secret=os.environ.get(
                "MINECRAFT_DASHBOARD_CLIENT_SECRET", ""
            ).strip(),
        )


def validate_bridge_url(url: str, *, allow_insecure_localhost: bool) -> None:
    parsed = urlsplit(url)
    if parsed.scheme == "wss" and parsed.hostname:
        return
    if (
        allow_insecure_localhost
        and parsed.scheme == "ws"
        and parsed.hostname in {"localhost", "127.0.0.1", "::1"}
    ):
        return
    raise ValueError("bridge-url must use wss://; ws:// is allowed only for explicit localhost development")
