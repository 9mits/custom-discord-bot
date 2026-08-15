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
from minecraft_bot.models import BridgeAction, Edition, OutboxRecord
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
        self.chat_message_handler = AsyncMock()
        self.server_event_handler = AsyncMock()
        self.server = MinecraftBridgeServer(
            self.config,
            self.data,
            verification_handler=self.verification_handler,
            action_result_handler=self.result_handler,
            player_event_handler=self.player_event_handler,
            chat_message_handler=self.chat_message_handler,
            server_event_handler=self.server_event_handler,
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

    async def _create_auto_pending(self):
        return await self.data.create_application(
            guild_id=1,
            discord_user_id=42,
            edition=None,
            claimed_username="TestPlayer",
            answers={
                "why": "I want to build with this community.",
                "about": "I am a considerate builder who enjoys group projects.",
            },
        )

    async def test_protocol_v1_paper_receives_compatible_pending_edition(self):
        application = await self._create_auto_pending()
        self.assertEqual(application.edition, Edition.JAVA)
        socket = await self.session.ws_connect(
            f"http://127.0.0.1:{self.port}/minecraft-bridge"
        )
        await socket.send_json(create_envelope(
            self.secret,
            "HELLO",
            {"server_id": "mysterious-smp-x", "protocol_version": 1},
            idempotency_key="hello-v1-compatible",
        ))

        hello_ack = await socket.receive_json()
        full_sync = await socket.receive_json()
        queued_sync = await socket.receive_json()

        self.assertEqual(hello_ack["payload"]["protocol_version"], 1)
        self.assertEqual(full_sync["payload"]["applications"][0]["edition"], "JAVA")
        self.assertEqual(queued_sync["payload"]["edition"], "JAVA")
        await socket.close()

    async def test_protocol_v2_paper_receives_automatic_pending_edition(self):
        await self._create_auto_pending()
        socket = await self.session.ws_connect(
            f"http://127.0.0.1:{self.port}/minecraft-bridge"
        )
        await socket.send_json(create_envelope(
            self.secret,
            "HELLO",
            {"server_id": "mysterious-smp-x", "protocol_version": 2},
            idempotency_key="hello-v2-auto",
        ))

        hello_ack = await socket.receive_json()
        full_sync = await socket.receive_json()
        queued_sync = await socket.receive_json()

        self.assertEqual(hello_ack["payload"]["protocol_version"], 2)
        self.assertEqual(full_sync["payload"]["applications"][0]["edition"], "AUTO")
        self.assertEqual(queued_sync["payload"]["edition"], "AUTO")
        await socket.close()

    async def test_protocol_v3_can_receive_transient_player_profiles(self):
        socket = await self.session.ws_connect(
            f"http://127.0.0.1:{self.port}/minecraft-bridge"
        )
        await socket.send_json(create_envelope(
            self.secret,
            "HELLO",
            {"server_id": "mysterious-smp-x", "protocol_version": 3},
            idempotency_key="hello-v3-profile",
        ))

        hello_ack = await socket.receive_json()
        await socket.receive_json()
        sent = await self.server.send_player_profile(
            minecraft_uuid="123e4567-e89b-12d3-a456-426614174000",
            level=50,
            extra_hearts=5,
            elite=True,
            discord_username="hellomits",
        )
        profile = await socket.receive_json()

        self.assertTrue(sent)
        self.assertEqual(hello_ack["payload"]["protocol_version"], 3)
        self.assertEqual(profile["type"], "ACTION")
        self.assertEqual(profile["payload"]["action"], "SYNC_PROFILE")
        self.assertEqual(profile["payload"]["level"], 50)
        self.assertEqual(profile["payload"]["extra_hearts"], 5)
        self.assertTrue(profile["payload"]["elite"])
        self.assertEqual(profile["payload"]["discord_username"], "hellomits")
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

    async def test_protocol_v4_relays_chat_in_both_directions(self):
        socket = await self.session.ws_connect(
            f"http://127.0.0.1:{self.port}/minecraft-bridge"
        )
        await socket.send_json(create_envelope(
            self.secret,
            "HELLO",
            {"server_id": "mysterious-smp-x", "protocol_version": 4},
            idempotency_key="hello-v4-chat",
        ))
        hello_ack = await socket.receive_json()
        await socket.receive_json()

        await socket.send_json(create_envelope(
            self.secret,
            "MINECRAFT_CHAT",
            {
                "edition": "JAVA",
                "minecraft_uuid": "123e4567-e89b-12d3-a456-426614174000",
                "current_username": "TestPlayer",
                "message": "hello from Minecraft",
            },
            idempotency_key="minecraft-chat-1",
        ))
        acknowledgement = await socket.receive_json()

        sent = await self.server.send_discord_chat(
            discord_user_id=7,
            discord_username="hellomits",
            minecraft_uuid="123e4567-e89b-12d3-a456-426614174000",
            minecraft_username="TestPlayer",
            message="hello from Discord",
            attachment_url="https://discord.com/channels/1/2/3",
            attachment_count=1,
        )
        action = await socket.receive_json()

        self.assertEqual(hello_ack["payload"]["protocol_version"], 4)
        self.assertEqual(acknowledgement["type"], "MINECRAFT_CHAT_ACK")
        self.chat_message_handler.assert_awaited_once_with(
            minecraft_uuid="123e4567-e89b-12d3-a456-426614174000",
            current_username="TestPlayer",
            edition="JAVA",
            message="hello from Minecraft",
            event_idempotency_key="minecraft-chat-1",
        )
        self.assertTrue(sent)
        self.assertEqual(action["payload"]["action"], "DISCORD_CHAT")
        self.assertEqual(action["payload"]["discord_username"], "hellomits")
        self.assertEqual(action["payload"]["minecraft_username"], "TestPlayer")
        self.assertEqual(action["payload"]["attachment_count"], 1)
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

    async def test_protocol_v7_forwards_a_server_event_and_acknowledges_it(self):
        socket = await self.session.ws_connect(
            f"http://127.0.0.1:{self.port}/minecraft-bridge"
        )
        await socket.send_json(create_envelope(
            self.secret,
            "HELLO",
            {"server_id": "mysterious-smp-x", "protocol_version": 7},
            idempotency_key="hello-v7-events",
        ))
        await socket.receive_json()
        await socket.receive_json()
        self.assertTrue(self.server.supports_server_events)

        await socket.send_json(create_envelope(
            self.secret,
            "SERVER_EVENT",
            {
                "event": "clan_donate",
                "category": "clan",
                "actor_uuid": "123e4567-e89b-12d3-a456-426614174000",
                "actor_name": "TestPlayer",
                "summary": "Donated 4,096 to MGX",
                "details": {"clan": "MGX", "value": "4,096"},
                "occurred_at": 1750000000,
            },
            idempotency_key="server-event-1",
        ))

        acknowledgement = await socket.receive_json()

        self.assertEqual(acknowledgement["type"], "SERVER_EVENT_ACK")
        self.server_event_handler.assert_awaited_once()
        forwarded = self.server_event_handler.await_args.kwargs
        self.assertEqual(forwarded["event"], "clan_donate")
        self.assertEqual(forwarded["actor_name"], "TestPlayer")
        self.assertEqual(forwarded["details"]["clan"], "MGX")
        self.assertEqual(forwarded["event_idempotency_key"], "server-event-1")
        await socket.close()

    async def test_a_v6_plugin_is_not_offered_server_events(self):
        # An older plugin must keep working rather than being cut off, so the bot has
        # to notice it cannot report in-game actions instead of assuming it can.
        socket = await self.session.ws_connect(
            f"http://127.0.0.1:{self.port}/minecraft-bridge"
        )
        await socket.send_json(create_envelope(
            self.secret,
            "HELLO",
            {"server_id": "mysterious-smp-x", "protocol_version": 6},
            idempotency_key="hello-v6-events",
        ))
        await socket.receive_json()
        await socket.receive_json()

        self.assertFalse(self.server.supports_server_events)
        self.assertTrue(self.server.supports_whitelist_sync)
        await socket.close()

    def test_the_plugin_advertises_the_protocol_version_the_bot_expects(self):
        # The plugin gates SERVER_EVENT on its own constant. If the two drift, in-game
        # actions silently stop reaching the activity log with nothing logged about it.
        import re
        from pathlib import Path

        from minecraft_bot.bridge import CURRENT_PROTOCOL_VERSION

        source = (
            Path(__file__).resolve().parent.parent
            / "minecraft-bridge/src/main/java/bot/mgx/accessbridge/BridgeClient.java"
        ).read_text()
        declared = re.search(r"PROTOCOL_VERSION = (\d+);", source)

        self.assertIsNotNone(declared, "PROTOCOL_VERSION vanished from BridgeClient")
        self.assertEqual(CURRENT_PROTOCOL_VERSION, int(declared.group(1)))




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
