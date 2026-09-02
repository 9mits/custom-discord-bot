import asyncio
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
        self.reverse_link_handler = AsyncMock()
        self.server = MinecraftBridgeServer(
            self.config,
            self.data,
            verification_handler=self.verification_handler,
            action_result_handler=self.result_handler,
            player_event_handler=self.player_event_handler,
            chat_message_handler=self.chat_message_handler,
            server_event_handler=self.server_event_handler,
            reverse_link_handler=self.reverse_link_handler,
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

    def _classifier(self):
        from types import SimpleNamespace
        from minecraft_bot.bridge import MinecraftBridgeServer
        bridge = MinecraftBridgeServer.__new__(MinecraftBridgeServer)
        bridge.config = SimpleNamespace(bridge_secret=b"s" * 32)
        return bridge

    def test_a_late_message_is_dropped_but_a_forged_one_closes_the_link(self):
        """One stale message must not cost the connection.

        Closing on any rejection turned a single late event into a permanent reconnect
        loop: the plugin re-flushed its outbox on every handshake, the same message
        arrived late again, and production dropped the bridge every ~36 seconds — 508
        times on one day. A signature that does not match is different, and so is a
        replayed nonce: nobody without the secret can produce the first, and the second
        is the replay attack the nonce exists to stop. Both stay hard closes.
        """
        import time as _time
        from minecraft_bot.security import create_envelope

        bridge = self._classifier()
        secret = b"s" * 32

        fresh = create_envelope(secret, "SERVER_EVENT", {"a": 1}, idempotency_key="k1")
        stale = dict(fresh)
        stale["timestamp"] = int(_time.time()) - 300
        severity, detail = bridge._classify_rejection(stale)
        self.assertEqual("stale", severity)
        self.assertIn("clock skew", detail)

        forged = create_envelope(secret, "SERVER_EVENT", {"a": 1}, idempotency_key="k2")
        forged["signature"] = "0" * 64
        severity, detail = bridge._classify_rejection(forged)
        self.assertEqual("hostile", severity)
        self.assertIn("signature did not match", detail)

        # A well-formed envelope that fails for neither reason is a replay, and the
        # nonce exists precisely to make that fatal.
        severity, detail = bridge._classify_rejection(
            create_envelope(secret, "SERVER_EVENT", {"a": 1}, idempotency_key="k3")
        )
        self.assertEqual("hostile", severity)
        self.assertIn("replay", detail)

    def test_a_rejection_that_is_not_an_object_is_treated_as_hostile(self):
        bridge = self._classifier()
        self.assertEqual("hostile", bridge._classify_rejection("not-json")[0])
        self.assertEqual("hostile", bridge._classify_rejection({"type": "X"})[0])

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
        self.assertEqual(kwargs["access_id"], 7)
        self.assertEqual(kwargs["event_idempotency_key"], "verification-7")
        await socket.close()

    async def _create_auto_pending(self):
        return await self.data.create_verification(
            guild_id=1,
            discord_user_id=42,
            edition=None,
            claimed_username="TestPlayer",
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

    async def _connected_socket(self, *, version, key):
        socket = await self.session.ws_connect(
            f"http://127.0.0.1:{self.port}/minecraft-bridge"
        )
        await socket.send_json(create_envelope(
            self.secret,
            "HELLO",
            {"server_id": "mysterious-smp-x", "protocol_version": version},
            idempotency_key=key,
        ))
        await socket.receive_json()
        await socket.receive_json()
        return socket

    async def test_a_player_with_no_linked_account_is_told_to_forget_the_name(self):
        # An empty username that is actually sent means "not linked". Without it the
        # plugin keeps a cached Discord name forever, which is what left a wiped
        # member's name showing beside their Minecraft one.
        socket = await self._connected_socket(version=7, key="hello-unlinked")

        await self.server.send_player_profile(
            minecraft_uuid="123e4567-e89b-12d3-a456-426614174000",
            level=0,
            extra_hearts=0,
            elite=False,
            discord_username="",
            link_known=True,
        )
        profile = await socket.receive_json()

        self.assertIn("discord_username", profile["payload"])
        self.assertEqual(profile["payload"]["discord_username"], "")
        await socket.close()

    async def test_an_unresolvable_member_leaves_the_cached_name_alone(self):
        # Omitted, not empty. Sending "" here would clear everyone's name the first
        # time a Discord lookup failed.
        socket = await self._connected_socket(version=7, key="hello-unknown-link")

        await self.server.send_player_profile(
            minecraft_uuid="123e4567-e89b-12d3-a456-426614174000",
            level=0,
            extra_hearts=0,
            elite=False,
            discord_username="",
            link_known=False,
        )
        profile = await socket.receive_json()

        self.assertNotIn("discord_username", profile["payload"])
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
                    "online_count": 4,
                    "occurred_at": 1_784_484_000,
                },
                idempotency_key="player-join-1",
            )
        )

        acknowledgement = await socket.receive_json()

        self.assertEqual(acknowledgement["type"], "PLAYER_EVENT_ACK")
        self.player_event_handler.assert_awaited_once()
        self.assertTrue(self.player_event_handler.await_args.kwargs["joined"])
        self.assertEqual(self.player_event_handler.await_args.kwargs["online_count"], 4)
        self.assertEqual(
            self.player_event_handler.await_args.kwargs["occurred_at"], 1_784_484_000
        )
        self.assertEqual(
            self.player_event_handler.await_args.kwargs["event_idempotency_key"],
            "player-join-1",
        )
        await socket.close()

    async def test_failed_player_activity_is_not_acknowledged(self):
        self.player_event_handler.side_effect = RuntimeError("database unavailable")
        self.server._send = AsyncMock()

        await self.server._handle_message({
            "type": "PLAYER_LEAVE",
            "payload": {
                "edition": "JAVA",
                "minecraft_uuid": "123e4567-e89b-12d3-a456-426614174000",
                "current_username": "TestPlayer",
                "xuid": None,
            },
            "idempotency_key": "player-leave-failed",
        })

        self.server._send.assert_not_awaited()

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

    async def test_failed_chat_relay_is_not_acknowledged(self):
        self.chat_message_handler.side_effect = RuntimeError("Discord unavailable")
        self.server._send = AsyncMock()

        await self.server._handle_message({
            "type": "MINECRAFT_CHAT",
            "payload": {
                "edition": "JAVA",
                "minecraft_uuid": "123e4567-e89b-12d3-a456-426614174000",
                "current_username": "TestPlayer",
                "message": "retry me",
            },
            "idempotency_key": "minecraft-chat-failed",
        })

        self.server._send.assert_not_awaited()

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

    async def test_failed_server_event_is_not_acknowledged(self):
        self.server_event_handler.side_effect = RuntimeError("activity log unavailable")
        self.server._send = AsyncMock()

        await self.server._handle_message({
            "type": "SERVER_EVENT",
            "payload": {"event": "crate_rare_win", "details": {}},
            "idempotency_key": "server-event-failed",
        })

        self.server._send.assert_not_awaited()

    async def test_disconnect_resolves_actions_waiting_for_paper(self):
        future = asyncio.get_running_loop().create_future()
        self.server._pending_results["clan-action"] = future

        self.server._fail_pending_results("The Minecraft bridge disconnected.")

        # Three values, matching what an ACTION_RESULT resolves with: callers unpack the
        # result, and a disconnect must not be the one path that hands back a short tuple.
        self.assertEqual(
            await future,
            (False, "The Minecraft bridge disconnected.", {}),
        )
        self.assertEqual(self.server._pending_results, {})

    async def test_message_from_superseded_socket_is_ignored(self):
        current_socket = object()
        stale_socket = object()
        self.server._socket = current_socket
        self.server._send = AsyncMock()
        try:
            await self.server._handle_message(
                {
                    "type": "HEARTBEAT",
                    "payload": {},
                    "idempotency_key": "stale-heartbeat",
                },
                source_socket=stale_socket,
            )
        finally:
            self.server._socket = None

        self.server._send.assert_not_awaited()

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

    async def test_protocol_v8_can_hold_the_server_closed(self):
        socket = await self._connected_socket(version=8, key="hello-v8-maintenance")
        self.assertTrue(self.server.supports_maintenance)

        sent = await self.server.send_maintenance(True)
        action = await socket.receive_json()

        self.assertTrue(sent)
        self.assertEqual(action["payload"]["action"], "SET_MAINTENANCE")
        self.assertTrue(action["payload"]["enabled"])
        await socket.close()

    async def test_an_older_plugin_is_not_offered_maintenance(self):
        # It would silently do nothing, leaving the server open while Discord
        # reported it closed — the one failure worth reporting to the operator.
        socket = await self._connected_socket(version=7, key="hello-v7-maintenance")

        self.assertFalse(self.server.supports_maintenance)
        self.assertFalse(await self.server.send_maintenance(True))
        await socket.close()

    async def test_protocol_v13_publishes_a_change_set_and_returns_what_moved(self):
        socket = await self._connected_socket(version=13, key="hello-v13-changeset")
        self.assertTrue(self.server.supports_config_changesets)

        publish = asyncio.create_task(self.server.run_config_changeset(
            actor_uuid="11111111-1111-1111-1111-111111111111",
            actor_label="mits",
            operation="publish",
            edits=[
                {"key": "crate.default.key-cost", "value": "3"},
                {"key": "crate.keys-per-hour", "reset": True},
            ],
        ))
        action = await socket.receive_json()
        self.assertEqual(action["payload"]["action"], "GAME_VARIABLE")
        self.assertEqual(action["payload"]["operation"], "publish")
        self.assertEqual(action["payload"]["actor_label"], "mits")
        self.assertEqual(
            action["payload"]["edits"],
            [
                {"key": "crate.default.key-cost", "value": "3"},
                {"key": "crate.keys-per-hour", "reset": True},
            ],
        )

        await socket.send_json(create_envelope(self.secret, "ACTION_RESULT", {
            "action_idempotency_key": action["idempotency_key"],
            "success": True,
            "message": "Published 2 change(s). No restart required.",
            "detail": {
                "publish_id": "abc",
                "changes": [
                    {"key": "crate.default.key-cost", "before": 1, "after": 3},
                    {"key": "crate.keys-per-hour", "before": 30, "after": 20},
                ],
            },
        }, idempotency_key="result-publish"))
        success, message, detail = await publish

        self.assertTrue(success)
        self.assertEqual(detail["publish_id"], "abc")
        self.assertEqual(detail["changes"][0]["before"], 1)
        self.assertEqual(detail["changes"][0]["after"], 3)
        await socket.close()

    async def test_a_rejected_change_set_returns_findings_per_key(self):
        socket = await self._connected_socket(version=13, key="hello-v13-findings")

        pending = asyncio.create_task(self.server.run_config_changeset(
            actor_uuid="11111111-1111-1111-1111-111111111111",
            actor_label="mits",
            operation="validate",
            edits=[{"key": "airdrop.rarity.common.weight", "value": "0"}],
        ))
        action = await socket.receive_json()
        await socket.send_json(create_envelope(self.secret, "ACTION_RESULT", {
            "action_idempotency_key": action["idempotency_key"],
            "success": False,
            "message": "Every weight in airdrop.rarity would be zero.",
            "detail": {"findings": [
                {"key": "airdrop.rarity.common.weight", "message": "Leave one above zero."}
            ]},
        }, idempotency_key="result-findings"))
        success, _message, detail = await pending

        self.assertFalse(success)
        self.assertEqual(detail["findings"][0]["key"], "airdrop.rarity.common.weight")
        await socket.close()

    async def test_an_older_plugin_is_not_offered_change_sets(self):
        # It would apply the edits one at a time, which is the behaviour the change set
        # exists to replace: a half-applied rebalance, and no way to undo it.
        socket = await self._connected_socket(version=12, key="hello-v12-changeset")

        self.assertFalse(self.server.supports_config_changesets)
        success, message, detail = await self.server.run_config_changeset(
            actor_uuid="11111111-1111-1111-1111-111111111111",
            actor_label="mits",
            operation="publish",
            edits=[{"key": "crate.default.key-cost", "value": "3"}],
        )
        self.assertFalse(success)
        self.assertIn("too old", message)
        self.assertEqual(detail, {})
        await socket.close()

    async def test_a_disconnect_mid_change_set_still_returns_three_values(self):
        # The caller unpacks three values. A short tuple here would raise inside the
        # request handler instead of reporting that the bridge went away.
        socket = await self._connected_socket(version=13, key="hello-v13-drop")
        pending = asyncio.create_task(self.server.run_config_changeset(
            actor_uuid="11111111-1111-1111-1111-111111111111",
            actor_label="mits",
            operation="publish",
            edits=[{"key": "crate.default.key-cost", "value": "3"}],
        ))
        await socket.receive_json()
        self.server._fail_pending_results("The Minecraft bridge disconnected.")

        success, message, detail = await pending
        self.assertFalse(success)
        self.assertEqual(message, "The Minecraft bridge disconnected.")
        self.assertEqual(detail, {})
        await socket.close()

    async def test_protocol_v10_forwards_minecraft_first_link_request_and_acks(self):
        self.server._peer_protocol_version = 10
        self.server._send = AsyncMock()
        await self.server._handle_message({
            "type": "LINK_REQUEST",
            "payload": {
                "request_id": "123e4567-e89b-12d3-a456-426614174999",
                "discord_username": "test.user",
                "edition": "JAVA",
                "minecraft_uuid": "123e4567-e89b-12d3-a456-426614174000",
                "current_username": "TestPlayer",
                "xuid": None,
            },
            "idempotency_key": "link-request:one",
        })

        self.reverse_link_handler.assert_awaited_once_with(
            request_id="123e4567-e89b-12d3-a456-426614174999",
            discord_username="test.user",
            edition=Edition.JAVA,
            minecraft_uuid="123e4567-e89b-12d3-a456-426614174000",
            current_username="TestPlayer",
            xuid=None,
        )
        self.assertEqual(self.server._send.await_args.args[0], "LINK_REQUEST_ACK")

    async def test_link_status_is_only_sent_to_protocol_v10(self):
        self.server._socket = SimpleNamespace(closed=False)
        self.server._send = AsyncMock()
        self.server._peer_protocol_version = 9
        self.assertFalse(await self.server.send_reverse_link_status(
            request_id="request", minecraft_uuid="uuid", status="DM_SENT", message="Check Discord"
        ))
        self.server._peer_protocol_version = 10
        self.assertTrue(await self.server.send_reverse_link_status(
            request_id="request", minecraft_uuid="uuid", status="DM_SENT", message="Check Discord"
        ))
        self.assertEqual(self.server._send.await_args.args[0], "ACTION")
        self.server._socket = None

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
                access_id=None,
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

    def test_forwarded_proto_from_a_remote_client_is_ignored(self):
        server = MinecraftBridgeServer(
            SimpleNamespace(
                bridge_path="/bridge",
                bridge_secret=bytes(range(32)),
                allow_insecure_localhost=False,
            ),
            SimpleNamespace(),
            verification_handler=AsyncMock(),
            action_result_handler=AsyncMock(),
            player_event_handler=AsyncMock(),
        )
        request = SimpleNamespace(
            secure=False,
            remote="203.0.113.10",
            headers={"X-Forwarded-Proto": "https"},
        )
        self.assertFalse(server._request_is_secure(request))

    def test_forwarded_proto_from_localhost_is_trusted(self):
        server = MinecraftBridgeServer(
            SimpleNamespace(
                bridge_path="/bridge",
                bridge_secret=bytes(range(32)),
                allow_insecure_localhost=False,
            ),
            SimpleNamespace(),
            verification_handler=AsyncMock(),
            action_result_handler=AsyncMock(),
            player_event_handler=AsyncMock(),
        )
        request = SimpleNamespace(
            secure=False,
            remote="127.0.0.1",
            headers={"X-Forwarded-Proto": "https"},
        )
        self.assertTrue(server._request_is_secure(request))


class MinecraftBridgeProtocolCapTests(unittest.IsolatedAsyncioTestCase):
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
        self.server = MinecraftBridgeServer(
            self.config,
            self.data,
            verification_handler=AsyncMock(),
            action_result_handler=AsyncMock(),
            player_event_handler=AsyncMock(),
        )
        await self.server.start()
        self.port = self.server._site._server.sockets[0].getsockname()[1]
        self.session = aiohttp.ClientSession()

    async def asyncTearDown(self):
        await self.session.close()
        await self.server.close()
        await self.data.close()
        self.directory.cleanup()

    async def test_a_future_protocol_version_is_capped_at_current(self):
        from minecraft_bot.bridge import CURRENT_PROTOCOL_VERSION

        socket = await self.session.ws_connect(
            f"http://127.0.0.1:{self.port}/minecraft-bridge"
        )
        await socket.send_json(create_envelope(
            self.secret,
            "HELLO",
            {"server_id": "mysterious-smp-x", "protocol_version": 999},
            idempotency_key="hello-future",
        ))
        hello_ack = (await socket.receive_json())
        if hello_ack.get("type") != "HELLO_ACK":
            hello_ack = await socket.receive_json()
        self.assertEqual(hello_ack["payload"]["protocol_version"], CURRENT_PROTOCOL_VERSION)
        self.assertEqual(self.server._peer_protocol_version, CURRENT_PROTOCOL_VERSION)
        await socket.close()

if __name__ == "__main__":
    unittest.main()
