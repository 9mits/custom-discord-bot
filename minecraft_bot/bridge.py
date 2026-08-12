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

VerificationHandler = Callable[..., Awaitable[None]]
ActionResultHandler = Callable[[OutboxRecord, Optional[Any]], Awaitable[None]]


class MinecraftBridgeServer:
    def __init__(
        self,
        config: MinecraftConfig,
        data: MinecraftDataManager,
        *,
        verification_handler: VerificationHandler,
        action_result_handler: ActionResultHandler,
    ) -> None:
        self.config = config
        self.data = data
        self.verification_handler = verification_handler
        self.action_result_handler = action_result_handler
        self._app = web.Application(client_max_size=1024 * 1024)
        self._app.router.add_get(config.bridge_path, self._websocket_handler)
        self._runner: Optional[web.AppRunner] = None
        self._site: Optional[web.TCPSite] = None
        self._socket: Optional[web.WebSocketResponse] = None
        self._send_lock = asyncio.Lock()
        self._connection_lock = asyncio.Lock()
        self._dispatcher_task: Optional[asyncio.Task] = None
        self._sent_this_connection: set[str] = set()
        self._last_heartbeat_at: Optional[float] = None
        self._connected_at: Optional[float] = None

    @property
    def connected(self) -> bool:
        return self._socket is not None and not self._socket.closed

    @property
    def last_heartbeat_at(self) -> Optional[float]:
        return self._last_heartbeat_at

    @property
    def connected_at(self) -> Optional[float]:
        return self._connected_at

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

            async with self._connection_lock:
                previous = self._socket
                if previous is not None and not previous.closed:
                    await previous.close(code=1008, message=b"Superseded connection")
                self._socket = socket
                self._sent_this_connection.clear()
                self._connected_at = time.time()
                self._last_heartbeat_at = time.time()

            logger.info("Minecraft bridge connected for server %s", self.config.server_id)
            await self._send(
                "HELLO_ACK",
                {"server_id": self.config.server_id, "protocol_version": 1},
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
            self._sent_this_connection.discard(action_key)
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
                    "edition": application.edition.value,
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

    async def dispatch_outbox(self) -> None:
        if not self.connected:
            return
        for record in await self.data.get_outbox_batch(limit=50):
            if record.idempotency_key in self._sent_this_connection:
                continue
            await self._send(
                "ACTION",
                {"action": record.action.value, **record.payload},
                idempotency_key=record.idempotency_key,
            )
            await self.data.mark_outbox_sent(record.id)
            self._sent_this_connection.add(record.idempotency_key)

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
