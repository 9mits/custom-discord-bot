import asyncio
import sqlite3
import tempfile
import time
import unittest
from pathlib import Path
from types import SimpleNamespace

from minecraft_bot.data import MinecraftDataManager, normalize_username
from minecraft_bot.models import (
    AccessStatus,
    BridgeAction,
    DuplicateActiveVerification,
    Edition,
    InvalidTransition,
    ReverseLinkStatus,
)


class MinecraftDataTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.data = MinecraftDataManager(Path(self.directory.name) / "minecraft.db")
        await self.data.open()

    async def asyncTearDown(self):
        await self.data.close()
        self.directory.cleanup()

    async def create_pending(self, *, user_id=42, edition=Edition.JAVA, username="TestPlayer", now=1000):
        return await self.data.create_verification(
            guild_id=10,
            discord_user_id=user_id,
            edition=edition,
            claimed_username=username,
            now=now,
        )

    def test_username_normalization(self):
        self.assertEqual(normalize_username(Edition.JAVA, "  Test_Player "), ("Test_Player", "test_player"))
        self.assertEqual(normalize_username(Edition.BEDROCK, " Real   Name "), ("Real Name", "real name"))
        with self.assertRaises(ValueError):
            normalize_username(Edition.JAVA, "has spaces")
        self.assertEqual(
            normalize_username(Edition.BEDROCK, ".FloodgatePrefix"),
            ("FloodgatePrefix", "floodgateprefix"),
        )

    async def test_notification_receipt_is_claimed_only_once(self):
        key = "application:17:decision-dm"

        self.assertTrue(await self.data.claim_notification(key, now=1000))
        self.assertFalse(await self.data.claim_notification(key, now=1001))

        rows = await self.data._connection().execute_fetchall(
            "SELECT sent_at FROM minecraft_notification_receipts WHERE dedupe_key=?",
            (key,),
        )
        self.assertEqual([row["sent_at"] for row in rows], [1000])

    async def test_reverse_link_persists_member_confirmation_lifecycle(self):
        request = await self.data.create_reverse_link(
            guild_id=10,
            discord_username="@Test.User",
            edition=Edition.JAVA,
            minecraft_uuid="123e4567-e89b-12d3-a456-426614174000",
            current_username="TestPlayer",
            xuid=None,
            request_id="request-one",
            now=1000,
        )
        self.assertEqual(request.status, ReverseLinkStatus.WAITING_FOR_MEMBER)
        self.assertEqual(request.normalized_discord_username, "test.user")

        attached = await self.data.attach_reverse_link_member("request-one", 42, now=1001)
        claimed = await self.data.claim_reverse_link("request-one", 42, now=1002)
        finished = await self.data.finish_reverse_link(
            "request-one", ReverseLinkStatus.APPROVED, now=1003
        )

        self.assertEqual(attached.discord_user_id, "42")
        self.assertEqual(claimed.status, ReverseLinkStatus.PROCESSING)
        self.assertEqual(finished.status, ReverseLinkStatus.APPROVED)
        with self.assertRaises(InvalidTransition):
            await self.data.claim_reverse_link("request-one", 42, now=1004)

    async def test_new_reverse_link_replaces_old_request_and_waiting_member_lookup_is_exact(self):
        common = dict(
            guild_id=10,
            discord_username="Test_User",
            edition=Edition.JAVA,
            minecraft_uuid="123e4567-e89b-12d3-a456-426614174000",
            current_username="TestPlayer",
            xuid=None,
            now=1000,
        )
        first = await self.data.create_reverse_link(request_id="request-old", **common)
        second = await self.data.create_reverse_link(request_id="request-new", **common)

        self.assertEqual(
            (await self.data.get_reverse_link(first.request_id)).status,
            ReverseLinkStatus.SUPERSEDED,
        )
        waiting = await self.data.waiting_reverse_links_for_username("test_user", now=1001)
        self.assertEqual([item.request_id for item in waiting], [second.request_id])
        self.assertEqual(await self.data.waiting_reverse_links_for_username("test.user", now=1001), [])

    async def test_reverse_link_expiry_is_explicit(self):
        request = await self.data.create_reverse_link(
            guild_id=10,
            discord_username="Test_User",
            edition=Edition.JAVA,
            minecraft_uuid="123e4567-e89b-12d3-a456-426614174000",
            current_username="TestPlayer",
            xuid=None,
            request_id="request-expire",
            expires_seconds=60,
            now=1000,
        )
        expired = await self.data.expire_reverse_links(now=1060)
        self.assertEqual([item.request_id for item in expired], [request.request_id])
        self.assertEqual(
            (await self.data.get_reverse_link(request.request_id)).status,
            ReverseLinkStatus.EXPIRED,
        )
        self.assertEqual(
            normalize_username(Edition.JAVA, "Dr_Ravager"),
            ("Dr_Ravager", "dr_ravager"),
        )

    async def test_duplicate_active_application_is_rejected(self):
        pending = await self.create_pending()
        active = await self.data.get_active_access_for_user(
            guild_id=10,
            discord_user_id=42,
            now=1010,
        )

        self.assertEqual(active.id, pending.id)
        with self.assertRaises(DuplicateActiveVerification):
            await self.create_pending(username="OtherPlayer")

    async def test_applicant_can_cancel_pending_verification_and_reapply(self):
        pending = await self.create_pending()

        cancelled = await self.data.cancel_pending_verification_for_user(
            guild_id=10,
            discord_user_id=42,
            now=1010,
        )
        self.assertIsNone(
            await self.data.get_active_access_for_user(
                guild_id=10,
                discord_user_id=42,
                now=1011,
            )
        )
        replacement = await self.create_pending(username="CorrectName", now=1020)

        self.assertEqual(cancelled.status, AccessStatus.CANCELLED)
        self.assertIsNone(cancelled.revoked_by)
        self.assertEqual(replacement.status, AccessStatus.PENDING_VERIFICATION)
        outbox = await self.data.get_outbox_batch()
        self.assertEqual(
            [(record.access_id, record.action) for record in outbox],
            [
                (pending.id, BridgeAction.REMOVE_PENDING),
                (replacement.id, BridgeAction.SYNC_PENDING),
            ],
        )
        cancelled_sync = await self.data._connection().execute_fetchall(
            "SELECT status FROM minecraft_bridge_outbox WHERE idempotency_key=?",
            (f"access:{pending.id}:sync",),
        )
        self.assertEqual(cancelled_sync[0]["status"], "CANCELLED")
        audit = await self.data.audit_rows(pending.id)
        withdrawn = [row for row in audit if row["action"] == "APPLICATION_WITHDRAWN"]
        self.assertEqual(len(withdrawn), 1)
        self.assertEqual(withdrawn[0]["actor_discord_id"], "42")

    async def test_applicant_cannot_cancel_after_verification(self):
        application = await self.create_pending()
        await self.data.record_verification(
            access_id=application.id,
            edition=Edition.JAVA,
            minecraft_uuid="123e4567-e89b-12d3-a456-426614174000",
            current_username="TestPlayer",
            xuid=None,
            event_idempotency_key="verified-before-cancel",
            now=1010,
        )

        with self.assertRaisesRegex(InvalidTransition, "pending verification"):
            await self.data.cancel_pending_verification_for_user(
                guild_id=10,
                discord_user_id=42,
                now=1020,
            )

    async def test_expired_verification_does_not_block_a_new_application(self):
        previous = await self.create_pending(now=1000)
        replacement = await self.create_pending(username="OtherPlayer", now=1600)

        self.assertEqual((await self.data.get_access(previous.id)).status, AccessStatus.EXPIRED)
        self.assertEqual(replacement.status, AccessStatus.PENDING_VERIFICATION)

    async def test_bridge_player_events_are_idempotent(self):
        first = await self.data.claim_bridge_event("player-event-1", "PLAYER_JOIN", now=1000)
        duplicate = await self.data.claim_bridge_event("player-event-1", "PLAYER_JOIN", now=1001)

        self.assertTrue(first)
        self.assertFalse(duplicate)

        await self.data.release_bridge_event("player-event-1", "PLAYER_JOIN")
        self.assertTrue(
            await self.data.claim_bridge_event("player-event-1", "PLAYER_JOIN", now=1002)
        )

    async def test_player_activity_metrics_track_peak_editions_and_busy_time(self):
        monday_jst = 1_784_484_000
        events = (
            ("join-java", True, "JAVA", 1, monday_jst),
            ("join-bedrock", True, "BEDROCK", 2, monday_jst + 60),
            ("leave-java", False, "JAVA", 1, monday_jst + 120),
        )
        for key, joined, edition, online, occurred_at in events:
            await self.data.record_player_activity(
                event_idempotency_key=key,
                minecraft_uuid=f"00000000-0000-0000-0000-{len(key):012d}",
                current_username=key,
                edition=edition,
                joined=joined,
                online_count=online,
                occurred_at=occurred_at,
            )

        metrics = await self.data.player_activity_metrics(days=90)

        self.assertEqual(metrics["current"], 1)
        self.assertEqual(metrics["peak"], 2)
        self.assertEqual(metrics["peak_at"], monday_jst + 60)
        self.assertEqual(metrics["joins"], 2)
        self.assertEqual(metrics["java_joins"], 1)
        self.assertEqual(metrics["bedrock_joins"], 1)
        self.assertTrue(metrics["busiest"])

    async def test_player_activity_events_are_idempotent(self):
        values = dict(
            event_idempotency_key="same-player-event",
            minecraft_uuid="123e4567-e89b-12d3-a456-426614174000",
            current_username="TestPlayer",
            edition="JAVA",
            joined=True,
            online_count=1,
            occurred_at=int(time.time()),
        )
        await self.data.record_player_activity(**values)
        await self.data.record_player_activity(**values)

        rows = await self.data._connection().execute_fetchall(
            "SELECT COUNT(*) AS count FROM minecraft_player_activity"
        )
        self.assertEqual(rows[0]["count"], 1)

    async def test_java_verification_transitions_and_is_idempotent(self):
        application = await self.create_pending()
        verified, changed = await self.data.record_verification(
            access_id=application.id,
            edition=Edition.JAVA,
            minecraft_uuid="123e4567-e89b-12d3-a456-426614174000",
            current_username="testplayer",
            xuid=None,
            event_idempotency_key="verification-1",
            now=1010,
        )
        duplicate, duplicate_changed = await self.data.record_verification(
            access_id=application.id,
            edition=Edition.JAVA,
            minecraft_uuid="123e4567-e89b-12d3-a456-426614174000",
            current_username="testplayer",
            xuid=None,
            event_idempotency_key="verification-1",
            now=1011,
        )

        self.assertTrue(changed)
        self.assertFalse(duplicate_changed)
        self.assertEqual(verified.status, AccessStatus.VERIFIED)
        self.assertEqual(duplicate.status, AccessStatus.VERIFIED)
        accounts = await self.data.list_accounts_for_user(42)
        self.assertEqual(len(accounts), 1)
        self.assertEqual(accounts[0]["current_username"], "testplayer")
        sync_key = f"access:{application.id}:sync"
        await self.data.mark_outbox_failed(sync_key, "late failure")
        sync_rows = await self.data._connection().execute_fetchall(
            "SELECT status FROM minecraft_bridge_outbox WHERE idempotency_key=?",
            (sync_key,),
        )
        self.assertEqual(sync_rows[0]["status"], "CANCELLED")
        outbox = await self.data.get_outbox_batch()
        approval = next(record for record in outbox if record.action is BridgeAction.APPROVE)
        self.assertEqual(
            approval.idempotency_key,
            (
                f"access:{application.id}:approve:"
                "123e4567-e89b-12d3-a456-426614174000:verification-1"
            ),
        )

    async def test_bedrock_verification_uses_real_name_and_xuid(self):
        application = await self.create_pending(
            edition=Edition.BEDROCK,
            username="Real Name",
        )
        verified, changed = await self.data.record_verification(
            access_id=application.id,
            edition=Edition.BEDROCK,
            minecraft_uuid="123e4567-e89b-12d3-a456-426614174001",
            current_username="real name",
            xuid="2533274900000001",
            event_idempotency_key="bedrock-verification",
            now=1010,
        )

        self.assertTrue(changed)
        self.assertEqual(verified.xuid, "2533274900000001")
        self.assertEqual(
            normalize_username(Edition.BEDROCK, ".Real Name"),
            ("Real Name", "real name"),
        )

    async def test_user_can_link_many_accounts_per_edition(self):
        java = await self.create_pending()
        await self.data.record_verification(
            access_id=java.id,
            edition=Edition.JAVA,
            minecraft_uuid="123e4567-e89b-12d3-a456-426614174000",
            current_username="TestPlayer",
            xuid=None,
            event_idempotency_key="java-account-limit",
            now=1010,
        )

        extra = await self.create_pending(username="OtherJava", now=1020)
        verified, _changed = await self.data.record_verification(
            access_id=extra.id,
            edition=Edition.JAVA,
            minecraft_uuid="123e4567-e89b-12d3-a456-426614174098",
            current_username="OtherJava",
            xuid=None,
            event_idempotency_key="second-java",
            now=1030,
        )
        self.assertEqual(verified.status, AccessStatus.VERIFIED)

        bedrock = await self.create_pending(
            edition=Edition.BEDROCK,
            username="Bedrock User",
            now=1040,
        )
        self.assertEqual(bedrock.edition, Edition.BEDROCK)

    async def test_verification_still_blocks_an_account_owned_by_someone_else(self):
        application = await self.create_pending(username="OtherJava")
        await self.data._connection().execute(
            "INSERT INTO minecraft_accounts"
            "(discord_user_id, edition, minecraft_uuid, current_username, verified_at, "
            "last_seen_at, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            (
                "99",
                Edition.JAVA.value,
                "123e4567-e89b-12d3-a456-426614174099",
                "OriginalJava",
                1000,
                1000,
                1000,
                1000,
            ),
        )
        await self.data._connection().commit()

        with self.assertRaisesRegex(InvalidTransition, "linked to another Discord member"):
            await self.data.record_verification(
                access_id=application.id,
                edition=Edition.JAVA,
                minecraft_uuid="123e4567-e89b-12d3-a456-426614174099",
                current_username="OtherJava",
                xuid=None,
                event_idempotency_key="defensive-account-limit",
                now=1010,
            )

    async def test_moderator_unlink_queues_the_whitelist_removal(self):
        application = await self.create_pending()
        await self.data.record_verification(
            access_id=application.id,
            edition=Edition.JAVA,
            minecraft_uuid="123e4567-e89b-12d3-a456-426614174000",
            current_username="TestPlayer",
            xuid=None,
            event_idempotency_key="unlink-unapproved",
            now=1010,
        )

        account, affected, queued = await self.data.unlink_account(
            42,
            Edition.JAVA,
            99,
            "Applicant requested a different account",
        )

        self.assertEqual(account["current_username"], "TestPlayer")
        # Verification granted access, so unlinking must pull the whitelist back.
        # The record stays VERIFIED until Paper confirms the revoke.
        self.assertTrue(queued)
        self.assertEqual(affected[0].status, AccessStatus.VERIFIED)
        # The link survives until Paper confirms the revoke, so a mid-flight
        # failure cannot strand a whitelisted account with no record of it.
        self.assertEqual(len(await self.data.list_accounts_for_user(42)), 1)
        audit = await self.data.audit_rows(application.id)
        # Queued, not done: Paper still has to confirm the whitelist removal.
        self.assertEqual(audit[-1]["action"], "ACCOUNT_UNLINK_QUEUED")
        self.assertEqual(audit[-1]["actor_discord_id"], "99")

    async def test_approved_account_unlinks_only_after_paper_confirmation(self):
        application = await self.create_pending()
        await self.data.record_verification(
            access_id=application.id,
            edition=Edition.JAVA,
            minecraft_uuid="123e4567-e89b-12d3-a456-426614174000",
            current_username="TestPlayer",
            xuid=None,
            event_idempotency_key="unlink-approved",
            now=1010,
        )
        approval = next(
            record
            for record in await self.data.get_outbox_batch()
            if record.action is BridgeAction.APPROVE
        )
        await self.data.complete_outbox(approval.idempotency_key)

        account, affected, queued = await self.data.unlink_account(
            42,
            Edition.JAVA,
            99,
            "Replacing linked account",
        )

        self.assertEqual(account["current_username"], "TestPlayer")
        self.assertTrue(queued)
        self.assertEqual(affected[0].status, AccessStatus.VERIFIED)
        self.assertEqual(len(await self.data.list_accounts_for_user(42)), 1)
        unlink = next(
            record
            for record in await self.data.get_outbox_batch()
            if record.action is BridgeAction.REVOKE and record.payload.get("unlink_account")
        )
        _, revoked, changed = await self.data.complete_outbox(unlink.idempotency_key)

        self.assertTrue(changed)
        self.assertEqual(revoked.status, AccessStatus.REVOKED)
        self.assertEqual(await self.data.list_accounts_for_user(42), [])

    async def test_bedrock_verification_requires_floodgate_xuid(self):
        application = await self.create_pending(edition=Edition.BEDROCK, username="Real Name")
        with self.assertRaisesRegex(InvalidTransition, "XUID"):
            await self.data.record_verification(
                access_id=application.id,
                edition=Edition.BEDROCK,
                minecraft_uuid="123e4567-e89b-12d3-a456-426614174001",
                current_username="Real Name",
                xuid=None,
                event_idempotency_key="missing-xuid",
                now=1010,
            )

    async def test_queued_approval_completes_idempotently_after_reconnect(self):
        application = await self.create_pending()
        await self.data.record_verification(
            access_id=application.id,
            edition=Edition.JAVA,
            minecraft_uuid="123e4567-e89b-12d3-a456-426614174000",
            current_username="TestPlayer",
            xuid=None,
            event_idempotency_key="verified",
            now=1010,
        )
        action = next(
            record for record in await self.data.get_outbox_batch() if record.action is BridgeAction.APPROVE
        )

        _, approved, first_processed = await self.data.complete_outbox(action.idempotency_key)
        _, duplicate, second_processed = await self.data.complete_outbox(action.idempotency_key)

        self.assertEqual(approved.status, AccessStatus.VERIFIED)
        self.assertEqual(duplicate.status, AccessStatus.VERIFIED)
        self.assertTrue(first_processed)
        self.assertFalse(second_processed)
        audit = await self.data.audit_rows(application.id)
        self.assertEqual(sum(row["action"] == "WHITELIST_CONFIRMED" for row in audit), 1)

    async def test_expiry_moves_a_pending_verification_out_of_the_way(self):
        expiring = await self.create_pending(user_id=1, now=1000)
        expired = await self.data.expire_pending(now=1600)
        self.assertEqual([entry.id for entry in expired], [expiring.id])
        self.assertEqual((await self.data.get_access(expiring.id)).status, AccessStatus.EXPIRED)

    async def test_verification_grants_access_without_a_staff_step(self):
        """The whole point of the change: verifying is the only gate."""
        record = await self.create_pending(user_id=2, now=2000)
        updated, changed = await self.data.record_verification(
            access_id=record.id,
            edition=Edition.JAVA,
            minecraft_uuid="123e4567-e89b-12d3-a456-426614174002",
            current_username="TestPlayer",
            xuid=None,
            event_idempotency_key="verified-grants",
            now=2010,
        )
        self.assertTrue(changed)
        self.assertEqual(updated.status, AccessStatus.VERIFIED)
        # And the whitelist is queued by the same transaction, with no moderator.
        approve = [
            row for row in await self.data.get_outbox_batch()
            if row.action is BridgeAction.APPROVE and row.access_id == record.id
        ]
        self.assertEqual(len(approve), 1)
        self.assertIsNone(updated.revoked_by)

    async def test_nonce_claim_is_atomic(self):
        claimed = await asyncio.gather(
            self.data.claim_nonce("same-nonce", expires_at=1100, now=1000),
            self.data.claim_nonce("same-nonce", expires_at=1100, now=1000),
        )
        self.assertEqual(sorted(claimed), [False, True])

    async def test_configuration_values_are_written_together_and_audited(self):
        await self.data.set_configs(
            {"application_channel_id": 100, "member_role_id": 200},
            actor_id=42,
        )

        values = await self.data.get_configs(
            ("application_channel_id", "member_role_id", "missing")
        )
        self.assertEqual(values, {"application_channel_id": 100, "member_role_id": 200})
        rows = await self.data._connection().execute_fetchall(
            "SELECT actor_discord_id, payload FROM minecraft_audit_log WHERE action='SETTINGS_UPDATED'"
        )
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]["actor_discord_id"], "42")
        self.assertIn("application_channel_id", rows[0]["payload"])

    async def test_edition_is_detected_from_verified_connection(self):
        application = await self.data.create_verification(
            guild_id=10,
            discord_user_id=50,
            edition=None,
            claimed_username="Real Name",
            now=1000,
        )
        self.assertTrue(application.auto_detect_edition)
        outbox = await self.data.get_outbox_batch()
        self.assertEqual(outbox[0].payload["edition"], "AUTO")

        verified, changed = await self.data.record_verification(
            access_id=application.id,
            edition=Edition.BEDROCK,
            minecraft_uuid="123e4567-e89b-12d3-a456-426614174088",
            current_username="Real Name",
            xuid="2533274900000088",
            event_idempotency_key="auto-bedrock",
            now=1010,
        )
        self.assertTrue(changed)
        self.assertEqual(verified.edition, Edition.BEDROCK)
        self.assertFalse(verified.auto_detect_edition)

    async def test_a_pending_verification_is_the_card_worth_recovering(self):
        pending = await self.create_pending()
        self.assertIn(pending.id, [item.id for item in await self.data.list_live_card_access()])

        verified, _changed = await self.data.record_verification(
            access_id=pending.id,
            edition=Edition.JAVA,
            minecraft_uuid="123e4567-e89b-12d3-a456-426614174066",
            current_username="TestPlayer",
            xuid=None,
            event_idempotency_key="live-card-after-verification",
            now=1010,
        )
        self.assertNotIn(
            verified.id, [item.id for item in await self.data.list_live_card_access()]
        )

    async def test_join_updates_renamed_account_by_uuid(self):
        application = await self.create_pending()
        await self.data.record_verification(
            access_id=application.id,
            edition=Edition.JAVA,
            minecraft_uuid="123e4567-e89b-12d3-a456-426614174077",
            current_username="TestPlayer",
            xuid=None,
            event_idempotency_key="rename-original",
            now=1010,
        )
        owner = await self.data.record_player_seen(
            Edition.JAVA,
            "123e4567-e89b-12d3-a456-426614174077",
            "NewPlayerName",
            now=2000,
        )
        accounts = await self.data.list_accounts_for_user(42)
        self.assertEqual(owner, "42")
        self.assertEqual(accounts[0]["current_username"], "NewPlayerName")
        self.assertEqual(accounts[0]["last_seen_at"], 2000)

    async def test_response_metrics_and_delivery_queue_are_persistent(self):
        current = int(time.time())
        for duration in (10, 20, 30, 100):
            await self.data.record_command_log(SimpleNamespace(
                source="command", command="minecraft account", user_id=42,
                user_label="member", target_id=None, channel_id=None,
                outcome="SUCCESS", risk="LOW", duration_ms=duration,
                correlation_id=None, detail=None, options=(), created_at=current,
            ))
        metrics = await self.data.response_time_metrics()
        self.assertEqual(metrics, {"samples": 4, "median_ms": 25, "p95_ms": 100})

        await self.data.enqueue_delivery(
            dedupe_key="card:42", kind="LIVE_CARD", target_id=42, payload={"access_id": 42}, now=1000
        )
        await self.data.close()
        self.data = MinecraftDataManager(Path(self.directory.name) / "minecraft.db")
        await self.data.open()
        deliveries = await self.data.get_due_deliveries(now=1001)
        self.assertEqual(deliveries[0].dedupe_key, "card:42")

    async def test_legacy_user_dm_deliveries_can_be_discarded(self):
        await self.data.enqueue_delivery(
            dedupe_key="dm:42", kind="USER_EMBED", target_id=42, payload={"title": "Old"}, now=1000
        )

        discarded = await self.data.discard_deliveries("USER_EMBED")

        self.assertEqual(discarded, 1)
        self.assertEqual(await self.data.get_due_deliveries(now=1001), [])

    async def create_unanswered(self, *, user_id=42, username="TestPlayer", now=1000):
        return await self.data.create_verification(
            guild_id=10,
            discord_user_id=user_id,
            edition=Edition.JAVA,
            claimed_username=username,
            now=now,
        )

    async def test_whitelist_directory_lists_approved_players(self):
        application = await self.create_pending()
        await self.data.record_verification(
            access_id=application.id,
            edition=Edition.JAVA,
            minecraft_uuid="123e4567-e89b-12d3-a456-426614174000",
            current_username="TestPlayer",
            xuid=None,
            event_idempotency_key="verify-whitelist",
            now=1010,
        )
        rows = await self.data.list_whitelisted()
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]["username"], "TestPlayer")
        self.assertEqual(rows[0]["edition"], "JAVA")
        self.assertEqual(rows[0]["discord_user_id"], "42")

    async def test_wipe_clears_everything_except_settings(self):
        await self.data.set_config("application_channel_id", 123)
        application = await self.create_pending()
        await self.data.record_verification(
            access_id=application.id,
            edition=Edition.JAVA,
            minecraft_uuid="123e4567-e89b-12d3-a456-426614174000",
            current_username="TestPlayer",
            xuid=None,
            event_idempotency_key="verify-wipe",
            now=1010,
        )

        counts = await self.data.wipe_all_data(actor_id=9)

        self.assertEqual(counts["minecraft_access"], 1)
        self.assertEqual(counts["minecraft_accounts"], 1)
        self.assertIsNone(await self.data.get_access(application.id))
        self.assertEqual(await self.data.list_accounts_for_user(42), [])
        self.assertEqual(await self.data.list_whitelisted(), [])
        # Settings survive the wipe.
        self.assertEqual(await self.data.get_config("application_channel_id"), 123)
        # Paper is told to drop the whitelist entry and forget the application.
        outbox = await self.data.get_outbox_batch()
        actions = {(record.action, record.access_id) for record in outbox}
        self.assertIn((BridgeAction.REVOKE, None), actions)
        revoke = next(r for r in outbox if r.action is BridgeAction.REVOKE)
        self.assertEqual(revoke.payload["minecraft_uuid"], "123e4567-e89b-12d3-a456-426614174000")


class MinecraftMigrationBackupTests(unittest.IsolatedAsyncioTestCase):
    async def test_existing_unversioned_database_is_backed_up_before_schema_creation(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "minecraft.db"
            connection = sqlite3.connect(path)
            connection.execute("CREATE TABLE legacy_marker(value TEXT)")
            connection.execute("INSERT INTO legacy_marker(value) VALUES ('preserve-me')")
            connection.commit()
            connection.close()
            data = MinecraftDataManager(path)

            await data.open()
            await data.close()

            backups = list((path.parent / "backups").glob("minecraft-v0-*.db"))
            self.assertEqual(len(backups), 1)
            backup = sqlite3.connect(backups[0])
            try:
                self.assertEqual(backup.execute("SELECT value FROM legacy_marker").fetchone()[0], "preserve-me")
            finally:
                backup.close()


class SecondEditionLinkTests(unittest.IsolatedAsyncioTestCase):
    """Linking the other edition once a member is already accepted."""

    async def asyncSetUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.data = MinecraftDataManager(Path(self.directory.name) / "minecraft.db")
        await self.data.open()

    async def asyncTearDown(self):
        await self.data.close()
        self.directory.cleanup()

    async def _approve_java(self, *, user_id=42, username="TestPlayer", now=1000):
        """Takes a member all the way to approved Java access."""
        application = await self.data.create_verification(
            guild_id=10,
            discord_user_id=user_id,
            edition=Edition.JAVA,
            claimed_username=username,
            now=now,
        )
        await self.data.record_verification(
            access_id=application.id,
            edition=Edition.JAVA,
            minecraft_uuid="123e4567-e89b-12d3-a456-426614174000",
            current_username=username,
            xuid=None,
            event_idempotency_key=f"verify-java-{user_id}",
            now=now + 10,
        )
        approval = next(
            record
            for record in await self.data.get_outbox_batch()
            if record.action is BridgeAction.APPROVE
        )
        await self.data.complete_outbox(approval.idempotency_key)
        return application

    async def _verify_bedrock(self, *, user_id=42, gamertag="Test Gamer", now=2000):
        application = await self.data.create_verification(
            guild_id=10,
            discord_user_id=user_id,
            edition=Edition.BEDROCK,
            claimed_username=gamertag,
            now=now,
        )
        verified, _changed = await self.data.record_verification(
            access_id=application.id,
            edition=Edition.BEDROCK,
            minecraft_uuid="223e4567-e89b-12d3-a456-426614174000",
            current_username=gamertag,
            xuid="2535400000000000",
            event_idempotency_key=f"verify-bedrock-{user_id}-{now}",
            now=now + 10,
        )
        return verified

    async def test_a_verified_member_can_start_another_verification(self):
        await self._approve_java()

        extra = await self.data.create_verification(
            guild_id=10,
            discord_user_id=42,
            edition=Edition.JAVA,
            claimed_username="SomeoneElse",
            now=5000,
        )
        self.assertEqual(extra.status, AccessStatus.PENDING_VERIFICATION)


class MinecraftAccessIntegrityTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.data = MinecraftDataManager(Path(self.directory.name) / "minecraft.db")
        await self.data.open()

    async def asyncTearDown(self):
        await self.data.close()
        self.directory.cleanup()

    async def test_two_users_cannot_claim_the_same_username(self):
        await self.data.create_verification(
            guild_id=10,
            discord_user_id=1,
            edition=Edition.JAVA,
            claimed_username="Steve",
            now=1000,
        )
        with self.assertRaises(ValueError):
            await self.data.create_verification(
                guild_id=10,
                discord_user_id=2,
                edition=Edition.JAVA,
                claimed_username="Steve",
                now=1001,
            )

    async def test_unlink_cancels_a_pending_application(self):
        application = await self.data.create_verification(
            guild_id=10,
            discord_user_id=42,
            edition=Edition.JAVA,
            claimed_username="TestPlayer",
            now=1000,
        )
        await self.data.record_verification(
            access_id=application.id,
            edition=Edition.JAVA,
            minecraft_uuid="123e4567-e89b-12d3-a456-426614174000",
            current_username="TestPlayer",
            xuid=None,
            event_idempotency_key="verify-form",
            now=1010,
        )

        account, affected, queued = await self.data.unlink_account(
            42, Edition.JAVA, 99, "Wrong account"
        )

        self.assertEqual(account["current_username"], "TestPlayer")
        # Verification granted access, so unlinking must pull the whitelist back.
        # The record stays VERIFIED until Paper confirms the revoke.
        self.assertTrue(queued)
        self.assertEqual(affected[0].status, AccessStatus.VERIFIED)
        # The link survives until Paper confirms the revoke, so a mid-flight
        # failure cannot strand a whitelisted account with no record of it.
        self.assertEqual(len(await self.data.list_accounts_for_user(42)), 1)

    async def test_late_approve_does_not_resurrect_a_cancelled_outbox_row(self):
        application = await self.data.create_verification(
            guild_id=10,
            discord_user_id=42,
            edition=Edition.JAVA,
            claimed_username="TestPlayer",
            now=1000,
        )
        await self.data.record_verification(
            access_id=application.id,
            edition=Edition.JAVA,
            minecraft_uuid="123e4567-e89b-12d3-a456-426614174000",
            current_username="TestPlayer",
            xuid=None,
            event_idempotency_key="verify-cancel-approve",
            now=1010,
        )
        approve = next(
            record
            for record in await self.data.get_outbox_batch()
            if record.action is BridgeAction.APPROVE
        )
        await self.data.cancel_verification(application.id, 99)
        record, updated, newly = await self.data.complete_outbox(approve.idempotency_key)

        self.assertFalse(newly)
        self.assertEqual(updated.status, AccessStatus.CANCELLED)
        self.assertEqual(record.status, "CANCELLED")

    async def test_expired_verification_is_acknowledged_without_raising(self):
        application = await self.data.create_verification(
            guild_id=10,
            discord_user_id=42,
            edition=Edition.JAVA,
            claimed_username="TestPlayer",
            verification_seconds=10,
            now=1000,
        )
        updated, changed = await self.data.record_verification(
            access_id=application.id,
            edition=Edition.JAVA,
            minecraft_uuid="123e4567-e89b-12d3-a456-426614174000",
            current_username="TestPlayer",
            xuid=None,
            event_idempotency_key="verify-expired",
            now=2000,
        )
        self.assertFalse(changed)
        self.assertEqual(updated.status, AccessStatus.EXPIRED)

    async def test_verification_rejects_a_malformed_uuid(self):
        application = await self.data.create_verification(
            guild_id=10,
            discord_user_id=42,
            edition=Edition.JAVA,
            claimed_username="TestPlayer",
            now=1000,
        )
        with self.assertRaises(InvalidTransition):
            await self.data.record_verification(
                access_id=application.id,
                edition=Edition.JAVA,
                minecraft_uuid="not-a-uuid",
                current_username="TestPlayer",
                xuid=None,
                event_idempotency_key="verify-bad-uuid",
                now=1010,
            )

    async def test_username_search_treats_like_wildcards_as_literals(self):
        await self.data.create_verification(
            guild_id=10,
            discord_user_id=42,
            edition=Edition.JAVA,
            claimed_username="TestPlayer",
            now=1000,
        )
        self.assertEqual(await self.data.find_applications_by_username("%"), [])
        self.assertEqual(await self.data.find_applications_by_username("_"), [])
        self.assertEqual(len(await self.data.find_applications_by_username("TestPlayer")), 1)

    async def test_verification_accepts_a_floodgate_prefixed_name(self):
        application = await self.data.create_verification(
            guild_id=10,
            discord_user_id=8,
            edition=None,
            claimed_username="Dr_Ravager",
            now=1000,
        )
        verified, changed = await self.data.record_verification(
            access_id=application.id,
            edition=Edition.JAVA,
            minecraft_uuid="123e4567-e89b-12d3-a456-426614174099",
            current_username=".Dr_Ravager",
            xuid=None,
            event_idempotency_key="verify-prefixed",
            now=1010,
        )
        self.assertTrue(changed)
        self.assertEqual(verified.verified_username, "Dr_Ravager")

    async def test_bedrock_spaces_match_floodgate_underscores(self):
        application = await self.data.create_verification(
            guild_id=10,
            discord_user_id=9,
            edition=Edition.BEDROCK,
            claimed_username="FER GAMER3520",
            now=1000,
        )
        verified, changed = await self.data.record_verification(
            access_id=application.id,
            edition=Edition.BEDROCK,
            minecraft_uuid="00000000-0000-0000-0009-01f9d1ebbeb2",
            current_username=".FER_GAMER3520",
            xuid="2172480372402",
            event_idempotency_key="verify-bedrock-spaces",
            now=1010,
        )
        self.assertTrue(changed)
        self.assertEqual(verified.verified_username, "FER_GAMER3520")

    async def test_auto_detect_accepts_a_normal_java_name(self):
        application = await self.data.create_verification(
            guild_id=10,
            discord_user_id=7,
            edition=None,
            claimed_username="Dr_Ravager",
            now=1000,
        )
        self.assertEqual(application.edition, Edition.JAVA)
        self.assertEqual(application.claimed_username, "Dr_Ravager")


if __name__ == "__main__":
    unittest.main()
