"""Outbound-only Paper bridge endpoint and durable outbox dispatcher."""

from __future__ import annotations

import asyncio
import logging
import secrets
import ssl
import time
from contextlib import suppress
from typing import Any, Awaitable, Callable, Optional

from aiohttp import WSMsgType, web

from .config import MinecraftConfig
from .data import MinecraftDataManager
from .models import BridgeAction, Edition, OutboxRecord
from .security import MAX_CLOCK_SKEW_SECONDS, create_envelope, verify_envelope


logger = logging.getLogger("MinecraftAccessBot.bridge")
AUTO_EDITION_PROTOCOL_VERSION = 2
PROFILE_SYNC_PROTOCOL_VERSION = 3
CHAT_SYNC_PROTOCOL_VERSION = 4
RANK_SYNC_PROTOCOL_VERSION = 5
CURRENT_PROTOCOL_VERSION = RANK_SYNC_PROTOCOL_VERSION

VerificationHandler = Callable[..., Awaitable[None]]
ActionResultHandler = Callable[[OutboxRecord, Optional[Any]], Awaitable[None]]
PlayerEventHandler = Callable[..., Awaitable[None]]
ChatMessageHandler = Callable[..., Awaitable[None]]


class MinecraftBridgeServer:
    def __init__(
        self,
        config: MinecraftConfig,
        data: MinecraftDataManager,
        *,
        verification_handler: VerificationHandler,
        action_result_handler: ActionResultHandler,
        player_event_handler: PlayerEventHandler,
        chat_message_handler: Optional[ChatMessageHandler] = None,
    ) -> None:
        self.config = config
        self.data = data
        self.verification_handler = verification_handler
        self.action_result_handler = action_result_handler
        self.player_event_handler = player_event_handler
        self.chat_message_handler = chat_message_handler
        self._app = web.Application(client_max_size=1024 * 1024)
        self._app.router.add_get(config.bridge_path, self._websocket_handler)
        self._runner: Optional[web.AppRunner] = None
        self._site: Optional[web.TCPSite] = None
        self._socket: Optional[web.WebSocketResponse] = None
        self._send_lock = asyncio.Lock()
        self._dispatch_lock = asyncio.Lock()
        self._connection_lock = asyncio.Lock()
        self._dispatcher_task: Optional[asyncio.Task] = None
        self._sent_this_connection: dict[str, float] = {}
        self._last_heartbeat_at: Optional[float] = None
        self._connected_at: Optional[float] = None
        self._peer_protocol_version = 1

    @property
    def connected(self) -> bool:
        return self._socket is not None and not self._socket.closed

    @property
    def last_heartbeat_at(self) -> Optional[float]:
        return self._last_heartbeat_at

    @property
    def connected_at(self) -> Optional[float]:
        return self._connected_at

    @property
    def supports_auto_edition(self) -> bool:
        return self.connected and self._peer_protocol_version >= AUTO_EDITION_PROTOCOL_VERSION

    @property
    def supports_profile_sync(self) -> bool:
        return self.connected and self._peer_protocol_version >= PROFILE_SYNC_PROTOCOL_VERSION

    @property
    def supports_chat_sync(self) -> bool:
        return self.connected and self._peer_protocol_version >= CHAT_SYNC_PROTOCOL_VERSION

    @property
    def supports_rank_sync(self) -> bool:
        return self.connected and self._peer_protocol_version >= RANK_SYNC_PROTOCOL_VERSION

    async def start(self) -> None:
        ssl_context = None
        if self.config.bridge_tls_cert_path and self.config.bridge_tls_key_path:
            ssl_context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            ssl_context.minimum_version = ssl.TLSVersion.TLSv1_2
            ssl_context.load_cert_chain(
                certfile=self.config.bridge_tls_cert_path,
                keyfile=self.config.bridge_tls_key_path,
            )
        self._runner = web.AppRunner(self._app, access_log=None)
        await self._runner.setup()
        self._site = web.TCPSite(
            self._runner,
            host=self.config.bridge_host,
            port=self.config.bridge_port,
            ssl_context=ssl_context,
        )
        await self._site.start()
        self._dispatcher_task = asyncio.create_task(
            self._dispatch_loop(),
            name="minecraft-bridge-outbox",
        )
        logger.info(
            "Minecraft bridge listening on %s://%s:%s%s",
            "wss" if ssl_context else "ws",
            self.config.bridge_host,
            self.config.bridge_port,
            self.config.bridge_path,
        )

    async def close(self) -> None:
        if self._dispatcher_task is not None:
            self._dispatcher_task.cancel()
            with suppress(asyncio.CancelledError):
                await self._dispatcher_task
            self._dispatcher_task = None
        socket = self._socket
        if socket is not None and not socket.closed:
            await socket.close(code=1001, message=b"Bot shutting down")
        self._socket = None
        if self._runner is not None:
            await self._runner.cleanup()
            self._runner = None
            self._site = None

    def _request_is_secure(self, request: web.Request) -> bool:
        forwarded = request.headers.get("X-Forwarded-Proto", "").split(",", 1)[0].strip().lower()
        if request.secure or forwarded == "https":
            return True
        remote = request.remote or ""
        return self.config.allow_insecure_localhost and remote in {"127.0.0.1", "::1", "localhost"}

    async def _websocket_handler(self, request: web.Request) -> web.StreamResponse:
        if not self._request_is_secure(request):
            logger.warning("Rejected insecure Minecraft bridge connection from %s", request.remote)
            raise web.HTTPUpgradeRequired(text="The Minecraft bridge requires WSS")

        socket = web.WebSocketResponse(
            heartbeat=20,
            receive_timeout=45,
            max_msg_size=1024 * 1024,
            autoclose=True,
            autoping=True,
        )
        await socket.prepare(request)
        try:
            first = await asyncio.wait_for(socket.receive(), timeout=10)
            if first.type is not WSMsgType.TEXT:
                await socket.close(code=1008, message=b"Signed HELLO required")
                return socket
            envelope = first.json()
            if not await self._verify_incoming(envelope) or envelope.get("type") != "HELLO":
                await socket.close(code=1008, message=b"Authentication failed")
                return socket
            payload = envelope.get("payload", {})
            if payload.get("server_id") != self.config.server_id:
                logger.warning("Rejected bridge with unexpected server ID")
                await socket.close(code=1008, message=b"Server ID mismatch")
                return socket
            try:
                peer_protocol_version = max(1, int(payload.get("protocol_version", 1)))
            except (TypeError, ValueError):
                await socket.close(code=1008, message=b"Invalid protocol version")
                return socket

            async with self._connection_lock:
                previous = self._socket
                if previous is not None and not previous.closed:
                    await previous.close(code=1008, message=b"Superseded connection")
                self._socket = socket
                self._sent_this_connection.clear()
                self._connected_at = time.time()
                self._last_heartbeat_at = time.time()
                self._peer_protocol_version = peer_protocol_version

            logger.info("Minecraft bridge connected for server %s", self.config.server_id)
            await self._send(
                "HELLO_ACK",
                {
                    "server_id": self.config.server_id,
                    "protocol_version": min(peer_protocol_version, CURRENT_PROTOCOL_VERSION),
                },
                idempotency_key=envelope["idempotency_key"],
            )
            await self.send_full_pending_sync()
            await self.dispatch_outbox()

            async for message in socket:
                if message.type is WSMsgType.TEXT:
                    try:
                        incoming = message.json()
                    except Exception:
                        await socket.close(code=1007, message=b"Invalid JSON")
                        break
                    if not await self._verify_incoming(incoming):
                        logger.warning("Rejected invalid signed bridge message")
                        await socket.close(code=1008, message=b"Invalid signature, timestamp, or nonce")
                        break
                    await self._handle_message(incoming)
                elif message.type in {WSMsgType.CLOSE, WSMsgType.CLOSED, WSMsgType.ERROR}:
                    break
        except asyncio.TimeoutError:
            await socket.close(code=1008, message=b"Handshake timeout")
        except Exception:
            logger.exception("Minecraft bridge connection failed")
            with suppress(Exception):
                await socket.close(code=1011, message=b"Bridge error")
        finally:
            async with self._connection_lock:
                if self._socket is socket:
                    self._socket = None
                    self._sent_this_connection.clear()
                    self._connected_at = None
                    self._peer_protocol_version = 1
            logger.info("Minecraft bridge disconnected")
        return socket

    async def _verify_incoming(self, envelope: dict[str, Any]) -> bool:
        one_message_nonce_set: set[str] = set()
        if not verify_envelope(
            self.config.bridge_secret,
            envelope,
            used_nonces=one_message_nonce_set,
        ):
            return False
        nonce = str(envelope["nonce"])
        timestamp = int(envelope["timestamp"])
        return await self.data.claim_nonce(
            nonce,
            expires_at=timestamp + MAX_CLOCK_SKEW_SECONDS + 1,
        )

    async def _handle_message(self, envelope: dict[str, Any]) -> None:
        message_type = envelope["type"]
        payload = envelope["payload"]
        if message_type == "HEARTBEAT":
            self._last_heartbeat_at = time.time()
            await self._send(
                "HEARTBEAT_ACK",
                {"server_id": self.config.server_id},
                idempotency_key=envelope["idempotency_key"],
            )
            return
        if message_type == "VERIFICATION":
            await self.verification_handler(
                application_id=int(payload["application_id"]),
                edition=Edition(str(payload["edition"]).upper()),
                minecraft_uuid=str(payload["minecraft_uuid"]),
                current_username=str(payload["current_username"]),
                xuid=str(payload["xuid"]) if payload.get("xuid") is not None else None,
                event_idempotency_key=envelope["idempotency_key"],
            )
            await self._send(
                "VERIFICATION_ACK",
                {"application_id": int(payload["application_id"])},
                idempotency_key=envelope["idempotency_key"],
            )
            return
        if message_type in {"PLAYER_JOIN", "PLAYER_LEAVE"}:
            await self.player_event_handler(
                joined=message_type == "PLAYER_JOIN",
                minecraft_uuid=str(payload["minecraft_uuid"]),
                current_username=str(payload["current_username"]),
                edition=str(payload["edition"]).upper(),
                xuid=str(payload["xuid"]) if payload.get("xuid") is not None else None,
                event_idempotency_key=envelope["idempotency_key"],
            )
            await self._send(
                "PLAYER_EVENT_ACK",
                {"event_idempotency_key": envelope["idempotency_key"]},
                idempotency_key=envelope["idempotency_key"],
            )
            return
        if message_type == "MINECRAFT_CHAT":
            if self.chat_message_handler is not None:
                await self.chat_message_handler(
                    minecraft_uuid=str(payload["minecraft_uuid"]),
                    current_username=str(payload["current_username"]),
                    edition=str(payload["edition"]).upper(),
                    message=str(payload["message"]),
                    event_idempotency_key=envelope["idempotency_key"],
                )
            await self._send(
                "MINECRAFT_CHAT_ACK",
                {"event_idempotency_key": envelope["idempotency_key"]},
                idempotency_key=envelope["idempotency_key"],
            )
            return
        if message_type == "ACTION_RESULT":
            action_key = str(payload.get("action_idempotency_key", ""))
            if not action_key:
                raise ValueError("Action result omitted its idempotency key")
            if bool(payload.get("success")):
                record, application, newly_processed = await self.data.complete_outbox(action_key)
                if record is not None and newly_processed:
                    await self.action_result_handler(record, application)
            else:
                record = await self.data.mark_outbox_failed(
                    action_key,
                    str(payload.get("error", "Paper rejected the action")),
                )
                if record is not None:
                    await self.action_result_handler(record, None)
            self._sent_this_connection.pop(action_key, None)
            return
        if message_type == "STATUS_RESULT":
            self._last_heartbeat_at = time.time()
            return
        raise ValueError(f"Unsupported bridge message type: {message_type}")

    async def _send(
        self,
        message_type: str,
        payload: dict[str, Any],
        *,
        idempotency_key: Optional[str] = None,
    ) -> None:
        socket = self._socket
        if socket is None or socket.closed:
            raise ConnectionError("Minecraft bridge is offline")
        envelope = create_envelope(
            self.config.bridge_secret,
            message_type,
            payload,
            idempotency_key=idempotency_key,
        )
        async with self._send_lock:
            await socket.send_json(envelope)

    async def send_full_pending_sync(self) -> None:
        pending = await self.data.list_pending_verifications()
        payload = {
            "action": BridgeAction.SYNC_PENDING.value,
            "full": True,
            "applications": [
                {
                    "application_id": application.id,
                    "edition": self._pending_edition(application),
                    "claimed_username": application.claimed_username,
                    "normalized_username": application.normalized_username,
                    "expires_at": application.verification_expires_at,
                }
                for application in pending
            ],
        }
        await self._send(
            "ACTION",
            payload,
            idempotency_key=f"pending-sync:{secrets.token_hex(16)}",
        )

    async def send_player_profile(
        self,
        *,
        minecraft_uuid: str,
        level: int,
        extra_hearts: int,
        elite: bool,
        discord_username: str = "",
        rank_group: str = "",
        rank_label: str = "",
        rank_colour: int = 0,
    ) -> bool:
        """Apply an online player's Discord-derived perks on protocol v3 Paper."""
        if not self.supports_profile_sync:
            return False
        payload = {
            "action": "SYNC_PROFILE",
            "minecraft_uuid": str(minecraft_uuid),
            "level": max(0, min(int(level), 50)),
            "extra_hearts": max(0, min(int(extra_hearts), 5)),
            "elite": bool(elite),
            "discord_username": str(discord_username).strip()[:32],
        }
        if self.supports_rank_sync:
            # Sent even when empty so the plugin can clear a rank the member lost.
            payload["rank_group"] = str(rank_group).strip()[:32]
            payload["rank_label"] = str(rank_label).strip()[:16]
            payload["rank_colour"] = max(0, min(int(rank_colour), 0xFFFFFF))
        try:
            await self._send(
                "ACTION",
                payload,
                idempotency_key=f"profile:{minecraft_uuid}:{secrets.token_hex(12)}",
            )
        except ConnectionError:
            return False
        return True

    async def send_discord_chat(
        self,
        *,
        discord_user_id: int,
        discord_username: str,
        minecraft_uuid: Optional[str],
        minecraft_username: Optional[str],
        message: str,
        attachment_url: Optional[str] = None,
        attachment_count: int = 0,
    ) -> bool:
        if not self.supports_chat_sync:
            return False
        payload: dict[str, Any] = {
            "action": "DISCORD_CHAT",
            "discord_user_id": str(discord_user_id),
            "discord_username": str(discord_username).strip()[:32],
            "message": str(message)[:2000],
            "attachment_count": max(0, min(int(attachment_count), 10)),
        }
        if minecraft_uuid:
            payload["minecraft_uuid"] = str(minecraft_uuid)
        if minecraft_username:
            payload["minecraft_username"] = str(minecraft_username).strip()[:32]
        if attachment_url:
            payload["attachment_url"] = str(attachment_url)[:500]
        try:
            await self._send(
                "ACTION",
                payload,
                idempotency_key=f"discord-chat:{discord_user_id}:{secrets.token_hex(12)}",
            )
        except ConnectionError:
            return False
        return True

    def _pending_edition(self, application) -> str:
        if (
            application.auto_detect_edition
            and self._peer_protocol_version >= AUTO_EDITION_PROTOCOL_VERSION
        ):
            return "AUTO"
        return application.edition.value

    async def _action_payload(self, record: OutboxRecord) -> dict[str, Any]:
        payload = dict(record.payload)
        if (
            record.action is BridgeAction.SYNC_PENDING
            and payload.get("edition") == "AUTO"
            and self._peer_protocol_version < AUTO_EDITION_PROTOCOL_VERSION
            and record.application_id is not None
        ):
            application = await self.data.get_application(record.application_id)
            if application is not None:
                payload["edition"] = application.edition.value
        return {"action": record.action.value, **payload}

    async def dispatch_outbox(self) -> None:
        if not self.connected:
            return
        async with self._dispatch_lock:
            sent_ids: list[int] = []
            sent_keys: list[str] = []
            for record in await self.data.get_outbox_batch(limit=50):
                last_sent = self._sent_this_connection.get(record.idempotency_key)
                if last_sent is not None and time.monotonic() - last_sent < 15:
                    continue
                await self._send(
                    "ACTION",
                    await self._action_payload(record),
                    idempotency_key=record.idempotency_key,
                )
                sent_ids.append(record.id)
                sent_keys.append(record.idempotency_key)
            await self.data.mark_outbox_sent_batch(sent_ids)
            sent_at = time.monotonic()
            self._sent_this_connection.update({key: sent_at for key in sent_keys})

    async def _dispatch_loop(self) -> None:
        while True:
            try:
                await asyncio.sleep(1)
                if self.connected:
                    await self.dispatch_outbox()
            except asyncio.CancelledError:
                raise
            except Exception:
                logger.exception("Minecraft outbox dispatch failed; it will retry")
                await asyncio.sleep(2)
