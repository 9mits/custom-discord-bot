import tempfile
import unittest
from dataclasses import replace
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock

import aiohttp

from minecraft_bot.bridge import MinecraftBridgeServer
from minecraft_bot.config import MinecraftConfig
from minecraft_bot.data import MinecraftDataManager
from minecraft_bot.models import BridgeAction, OutboxRecord
from minecraft_bot.security import create_envelope, verify_envelope


class MinecraftBridgeIntegrationTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.secret = bytes(range(32))
        self.config = MinecraftConfig(
            discord_token="unused",
            guild_id=1,
            application_channel_id=2,
            review_channel_id=3,
            mod_role_id=4,
            member_role_id=5,
            bridge_secret=self.secret,
            server_id="mysterious-smp-x",
            java_address="localhost:25565",
            bedrock_address="localhost",
            bedrock_port=19132,
            bridge_path="/minecraft-bridge",
            bridge_host="127.0.0.1",
            bridge_port=0,
            data_dir=Path(self.directory.name),
            allow_insecure_localhost=True,
        )
        self.data = MinecraftDataManager(self.config.database_path)
        await self.data.open()
        self.verification_handler = AsyncMock()
        self.result_handler = AsyncMock()
        self.player_event_handler = AsyncMock()
        self.server = MinecraftBridgeServer(
            self.config,
            self.data,
            verification_handler=self.verification_handler,
            action_result_handler=self.result_handler,
            player_event_handler=self.player_event_handler,
        )
        await self.server.start()
        socket = self.server._site._server.sockets[0]
        self.port = socket.getsockname()[1]
        self.session = aiohttp.ClientSession()

    async def asyncTearDown(self):
        await self.session.close()
        await self.server.close()
        await self.data.close()
        self.directory.cleanup()

    async def test_signed_handshake_full_sync_and_verification(self):
        socket = await self.session.ws_connect(
            f"http://127.0.0.1:{self.port}/minecraft-bridge"
        )
        hello = create_envelope(
            self.secret,
            "HELLO",
            {"server_id": "mysterious-smp-x", "protocol_version": 1},
            idempotency_key="hello-1",
        )
        await socket.send_json(hello)

        hello_ack = await socket.receive_json()
        full_sync = await socket.receive_json()

        self.assertEqual(hello_ack["type"], "HELLO_ACK")
        self.assertEqual(full_sync["type"], "ACTION")
        self.assertEqual(full_sync["payload"]["action"], "SYNC_PENDING")
        self.assertTrue(full_sync["payload"]["full"])
        self.assertTrue(
            verify_envelope(self.secret, hello_ack, used_nonces=set())
        )

        verification = create_envelope(
            self.secret,
            "VERIFICATION",
            {
                "application_id": 7,
                "edition": "JAVA",
                "minecraft_uuid": "123e4567-e89b-12d3-a456-426614174000",
                "current_username": "TestPlayer",
                "xuid": None,
            },
            idempotency_key="verification-7",
        )
        await socket.send_json(verification)
        verification_ack = await socket.receive_json()

        self.assertEqual(verification_ack["type"], "VERIFICATION_ACK")
        self.verification_handler.assert_awaited_once()
        kwargs = self.verification_handler.await_args.kwargs
        self.assertEqual(kwargs["application_id"], 7)
        self.assertEqual(kwargs["event_idempotency_key"], "verification-7")
        await socket.close()

    async def test_replayed_nonce_closes_the_connection(self):
        socket = await self.session.ws_connect(
            f"http://127.0.0.1:{self.port}/minecraft-bridge"
        )
        hello = create_envelope(
            self.secret,
            "HELLO",
            {"server_id": "mysterious-smp-x", "protocol_version": 1},
            idempotency_key="hello-replay",
            nonce="replayed-nonce",
        )
        await socket.send_json(hello)
        await socket.receive_json()
        await socket.receive_json()

        await socket.send_json(hello)
        message = await socket.receive()

        self.assertIn(message.type, {aiohttp.WSMsgType.CLOSE, aiohttp.WSMsgType.CLOSED})

    async def test_player_activity_is_forwarded_and_acknowledged(self):
        socket = await self.session.ws_connect(
            f"http://127.0.0.1:{self.port}/minecraft-bridge"
        )
        await socket.send_json(
            create_envelope(
                self.secret,
                "HELLO",
                {"server_id": "mysterious-smp-x", "protocol_version": 1},
                idempotency_key="hello-player",
            )
        )
        await socket.receive_json()
        await socket.receive_json()
        await socket.send_json(
            create_envelope(
                self.secret,
                "PLAYER_JOIN",
                {
                    "edition": "JAVA",
                    "minecraft_uuid": "123e4567-e89b-12d3-a456-426614174000",
                    "current_username": "TestPlayer",
                    "xuid": None,
                },
                idempotency_key="player-join-1",
            )
        )

        acknowledgement = await socket.receive_json()

        self.assertEqual(acknowledgement["type"], "PLAYER_EVENT_ACK")
        self.player_event_handler.assert_awaited_once()
        self.assertTrue(self.player_event_handler.await_args.kwargs["joined"])
        self.assertEqual(
            self.player_event_handler.await_args.kwargs["event_idempotency_key"],
            "player-join-1",
        )
        await socket.close()

    async def test_plaintext_non_development_connection_is_rejected(self):
        secure_config = replace(self.config, allow_insecure_localhost=False)
        secure_server = MinecraftBridgeServer(
            secure_config,
            self.data,
            verification_handler=self.verification_handler,
            action_result_handler=self.result_handler,
            player_event_handler=self.player_event_handler,
        )
        await secure_server.start()
        port = secure_server._site._server.sockets[0].getsockname()[1]
        try:
            with self.assertRaises(aiohttp.WSServerHandshakeError) as raised:
                await self.session.ws_connect(f"http://127.0.0.1:{port}/minecraft-bridge")
            self.assertEqual(raised.exception.status, 426)
        finally:
            await secure_server.close()


class MinecraftBridgeDispatchTests(unittest.IsolatedAsyncioTestCase):
    async def test_outbox_delivery_batches_sent_status_updates(self):
        records = [
            OutboxRecord(
                id=index,
                idempotency_key=f"action-{index}",
                action=BridgeAction.STATUS,
                payload={},
                status="PENDING",
                attempts=0,
                last_error=None,
                application_id=None,
                created_at=1,
                processed_at=None,
            )
            for index in (1, 2)
        ]
        data = SimpleNamespace(
            get_outbox_batch=AsyncMock(return_value=records),
            mark_outbox_sent_batch=AsyncMock(),
        )
        server = MinecraftBridgeServer(
            SimpleNamespace(bridge_path="/bridge", bridge_secret=bytes(range(32))),
            data,
            verification_handler=AsyncMock(),
            action_result_handler=AsyncMock(),
            player_event_handler=AsyncMock(),
        )
        server._socket = SimpleNamespace(closed=False, send_json=AsyncMock())

        await server.dispatch_outbox()

        data.mark_outbox_sent_batch.assert_awaited_once_with([1, 2])
        self.assertEqual(server._socket.send_json.await_count, 2)


if __name__ == "__main__":
    unittest.main()
