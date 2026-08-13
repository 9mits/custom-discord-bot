import asyncio
import sqlite3
import tempfile
import time
import unittest
from pathlib import Path
from types import SimpleNamespace

from minecraft_bot.data import MinecraftDataManager, normalize_username
from minecraft_bot.models import (
    AccountEditionAlreadyLinked,
    ApplicationStatus,
    BridgeAction,
    DuplicateActiveApplication,
    Edition,
    InvalidTransition,
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
        return await self.data.create_application(
            guild_id=10,
            discord_user_id=user_id,
            edition=edition,
            claimed_username=username,
            answers={
                "why": "I want to build with this community.",
                "about": "I am a considerate builder who enjoys group projects.",
            },
            now=now,
        )

    def test_username_normalization(self):
        self.assertEqual(normalize_username(Edition.JAVA, "  Test_Player "), ("Test_Player", "test_player"))
        self.assertEqual(normalize_username(Edition.BEDROCK, " Real   Name "), ("Real Name", "real name"))
        with self.assertRaises(ValueError):
            normalize_username(Edition.JAVA, "has spaces")
        with self.assertRaises(ValueError):
            normalize_username(Edition.BEDROCK, ".FloodgatePrefix")

    async def test_duplicate_active_application_is_rejected(self):
        pending = await self.create_pending()
        active = await self.data.get_active_application_for_user(
            guild_id=10,
            discord_user_id=42,
            now=1010,
        )

        self.assertEqual(active.id, pending.id)
        with self.assertRaises(DuplicateActiveApplication):
            await self.create_pending(username="OtherPlayer")

    async def test_applicant_can_cancel_pending_verification_and_reapply(self):
        pending = await self.create_pending()

        cancelled = await self.data.cancel_pending_verification_for_user(
            guild_id=10,
            discord_user_id=42,
            now=1010,
        )
        self.assertIsNone(
            await self.data.get_active_application_for_user(
                guild_id=10,
                discord_user_id=42,
                now=1011,
            )
        )
        replacement = await self.create_pending(username="CorrectName", now=1020)

        self.assertEqual(cancelled.status, ApplicationStatus.CANCELLED)
        self.assertIsNone(cancelled.reviewed_by)
        self.assertEqual(replacement.status, ApplicationStatus.PENDING_VERIFICATION)
        outbox = await self.data.get_outbox_batch()
        self.assertEqual(
            [(record.application_id, record.action) for record in outbox],
            [
                (pending.id, BridgeAction.REMOVE_PENDING),
                (replacement.id, BridgeAction.SYNC_PENDING),
            ],
        )
        cancelled_sync = await self.data._connection().execute_fetchall(
            "SELECT status FROM minecraft_bridge_outbox WHERE idempotency_key=?",
            (f"application:{pending.id}:sync",),
        )
        self.assertEqual(cancelled_sync[0]["status"], "CANCELLED")
        audit = await self.data.audit_rows(pending.id)
        withdrawn = [row for row in audit if row["action"] == "APPLICATION_WITHDRAWN"]
        self.assertEqual(len(withdrawn), 1)
        self.assertEqual(withdrawn[0]["actor_discord_id"], "42")

    async def test_applicant_cannot_cancel_after_verification(self):
        application = await self.create_pending()
        await self.data.record_verification(
            application_id=application.id,
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

        self.assertEqual((await self.data.get_application(previous.id)).status, ApplicationStatus.EXPIRED)
        self.assertEqual(replacement.status, ApplicationStatus.PENDING_VERIFICATION)

    async def test_bridge_player_events_are_idempotent(self):
        first = await self.data.claim_bridge_event("player-event-1", "PLAYER_JOIN", now=1000)
        duplicate = await self.data.claim_bridge_event("player-event-1", "PLAYER_JOIN", now=1001)

        self.assertTrue(first)
        self.assertFalse(duplicate)

    async def test_java_verification_transitions_and_is_idempotent(self):
        application = await self.create_pending()
        verified, changed = await self.data.record_verification(
            application_id=application.id,
            edition=Edition.JAVA,
            minecraft_uuid="123e4567-e89b-12d3-a456-426614174000",
            current_username="testplayer",
            xuid=None,
            event_idempotency_key="verification-1",
            now=1010,
        )
        duplicate, duplicate_changed = await self.data.record_verification(
            application_id=application.id,
            edition=Edition.JAVA,
            minecraft_uuid="123e4567-e89b-12d3-a456-426614174000",
            current_username="testplayer",
            xuid=None,
            event_idempotency_key="verification-1",
            now=1011,
        )

        self.assertTrue(changed)
        self.assertFalse(duplicate_changed)
        self.assertEqual(verified.status, ApplicationStatus.PENDING_REVIEW)
        self.assertEqual(duplicate.status, ApplicationStatus.PENDING_REVIEW)
        accounts = await self.data.list_accounts_for_user(42)
        self.assertEqual(len(accounts), 1)
        self.assertEqual(accounts[0]["current_username"], "testplayer")
        sync_key = f"application:{application.id}:sync"
        await self.data.mark_outbox_failed(sync_key, "late failure")
        sync_rows = await self.data._connection().execute_fetchall(
            "SELECT status FROM minecraft_bridge_outbox WHERE idempotency_key=?",
            (sync_key,),
        )
        self.assertEqual(sync_rows[0]["status"], "CANCELLED")

    async def test_bedrock_verification_uses_real_name_and_xuid(self):
        application = await self.create_pending(
            edition=Edition.BEDROCK,
            username="Real Name",
        )
        verified, changed = await self.data.record_verification(
            application_id=application.id,
            edition=Edition.BEDROCK,
            minecraft_uuid="123e4567-e89b-12d3-a456-426614174001",
            current_username="real name",
            xuid="2533274900000001",
            event_idempotency_key="bedrock-verification",
            now=1010,
        )

        self.assertTrue(changed)
        self.assertEqual(verified.xuid, "2533274900000001")
        with self.assertRaises(ValueError):
            normalize_username(Edition.BEDROCK, ".Real Name")

    async def test_user_can_link_one_account_per_edition(self):
        java = await self.create_pending()
        await self.data.record_verification(
            application_id=java.id,
            edition=Edition.JAVA,
            minecraft_uuid="123e4567-e89b-12d3-a456-426614174000",
            current_username="TestPlayer",
            xuid=None,
            event_idempotency_key="java-account-limit",
            now=1010,
        )
        await self.data._connection().execute(
            "UPDATE minecraft_applications SET status=? WHERE id=?",
            (ApplicationStatus.DENIED.value, java.id),
        )
        await self.data._connection().commit()

        with self.assertRaises(AccountEditionAlreadyLinked):
            await self.create_pending(username="OtherJava", now=1020)

        bedrock = await self.create_pending(
            edition=Edition.BEDROCK,
            username="Bedrock User",
            now=1020,
        )
        self.assertEqual(bedrock.edition, Edition.BEDROCK)

    async def test_verification_rechecks_edition_limit_transactionally(self):
        application = await self.create_pending(username="OtherJava")
        await self.data._connection().execute(
            "INSERT INTO minecraft_accounts"
            "(discord_user_id, edition, minecraft_uuid, current_username, verified_at, "
            "last_seen_at, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            (
                "42",
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

        with self.assertRaisesRegex(InvalidTransition, "linked Java account"):
            await self.data.record_verification(
                application_id=application.id,
                edition=Edition.JAVA,
                minecraft_uuid="123e4567-e89b-12d3-a456-426614174098",
                current_username="OtherJava",
                xuid=None,
                event_idempotency_key="defensive-account-limit",
                now=1010,
            )

    async def test_moderator_can_unlink_unapproved_account_immediately(self):
        application = await self.create_pending()
        await self.data.record_verification(
            application_id=application.id,
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
        self.assertFalse(queued)
        self.assertEqual(affected[0].status, ApplicationStatus.CANCELLED)
        self.assertEqual(await self.data.list_accounts_for_user(42), [])
        audit = await self.data.audit_rows(application.id)
        self.assertEqual(audit[-1]["action"], "ACCOUNT_UNLINKED")
        self.assertEqual(audit[-1]["actor_discord_id"], "99")

    async def test_approved_account_unlinks_only_after_paper_confirmation(self):
        application = await self.create_pending()
        await self.data.record_verification(
            application_id=application.id,
            edition=Edition.JAVA,
            minecraft_uuid="123e4567-e89b-12d3-a456-426614174000",
            current_username="TestPlayer",
            xuid=None,
            event_idempotency_key="unlink-approved",
            now=1010,
        )
        await self.data.queue_approval(application.id, 99, now=1020)
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
        self.assertEqual(affected[0].status, ApplicationStatus.APPROVED)
        self.assertEqual(len(await self.data.list_accounts_for_user(42)), 1)
        unlink = next(
            record
            for record in await self.data.get_outbox_batch()
            if record.action is BridgeAction.REVOKE and record.payload.get("unlink_account")
        )
        _, revoked, changed = await self.data.complete_outbox(unlink.idempotency_key)

        self.assertTrue(changed)
        self.assertEqual(revoked.status, ApplicationStatus.REVOKED)
        self.assertEqual(await self.data.list_accounts_for_user(42), [])

    async def test_bedrock_verification_requires_floodgate_xuid(self):
        application = await self.create_pending(edition=Edition.BEDROCK, username="Real Name")
        with self.assertRaisesRegex(InvalidTransition, "XUID"):
            await self.data.record_verification(
                application_id=application.id,
                edition=Edition.BEDROCK,
                minecraft_uuid="123e4567-e89b-12d3-a456-426614174001",
                current_username="Real Name",
                xuid=None,
                event_idempotency_key="missing-xuid",
                now=1010,
            )

    async def test_unverified_application_cannot_be_approved(self):
        application = await self.create_pending()
        with self.assertRaises(InvalidTransition):
            await self.data.queue_approval(application.id, 99, now=1010)

    async def test_duplicate_approvals_have_one_atomic_winner(self):
        application = await self.create_pending()
        await self.data.record_verification(
            application_id=application.id,
            edition=Edition.JAVA,
            minecraft_uuid="123e4567-e89b-12d3-a456-426614174000",
            current_username="TestPlayer",
            xuid=None,
            event_idempotency_key="verified",
            now=1010,
        )

        results = await asyncio.gather(
            self.data.queue_approval(application.id, 91, now=1020),
            self.data.queue_approval(application.id, 92, now=1020),
            return_exceptions=True,
        )

        self.assertEqual(sum(not isinstance(result, Exception) for result in results), 1)
        self.assertEqual(sum(isinstance(result, InvalidTransition) for result in results), 1)
        outbox = await self.data.get_outbox_batch()
        self.assertEqual(sum(record.action is BridgeAction.APPROVE for record in outbox), 1)

    async def test_queued_approval_completes_idempotently_after_reconnect(self):
        application = await self.create_pending()
        await self.data.record_verification(
            application_id=application.id,
            edition=Edition.JAVA,
            minecraft_uuid="123e4567-e89b-12d3-a456-426614174000",
            current_username="TestPlayer",
            xuid=None,
            event_idempotency_key="verified",
            now=1010,
        )
        queued = await self.data.queue_approval(application.id, 91, now=1020)
        self.assertEqual(queued.status, ApplicationStatus.APPROVAL_QUEUED)
        action = next(
            record for record in await self.data.get_outbox_batch() if record.action is BridgeAction.APPROVE
        )

        _, approved, first_processed = await self.data.complete_outbox(action.idempotency_key)
        _, duplicate, second_processed = await self.data.complete_outbox(action.idempotency_key)

        self.assertEqual(approved.status, ApplicationStatus.APPROVED)
        self.assertEqual(duplicate.status, ApplicationStatus.APPROVED)
        self.assertTrue(first_processed)
        self.assertFalse(second_processed)
        audit = await self.data.audit_rows(application.id)
        self.assertEqual(sum(row["action"] == "APPLICATION_APPROVED" for row in audit), 1)

    async def test_denial_and_expiry_state_transitions(self):
        expiring = await self.create_pending(user_id=1, now=1000)
        expired = await self.data.expire_pending(now=1600)
        self.assertEqual([entry.id for entry in expired], [expiring.id])
        self.assertEqual((await self.data.get_application(expiring.id)).status, ApplicationStatus.EXPIRED)

        review = await self.create_pending(user_id=2, now=2000)
        await self.data.record_verification(
            application_id=review.id,
            edition=Edition.JAVA,
            minecraft_uuid="123e4567-e89b-12d3-a456-426614174002",
            current_username="TestPlayer",
            xuid=None,
            event_idempotency_key="review-verified",
            now=2010,
        )
        denied = await self.data.deny_application(
            review.id,
            99,
            internal_note="Internal context",
            applicant_reason="Public explanation",
            now=2020,
        )
        self.assertEqual(denied.status, ApplicationStatus.DENIED)
        self.assertEqual(denied.internal_note, "Internal context")
        with self.assertRaises(InvalidTransition):
            await self.data.deny_application(
                review.id,
                98,
                internal_note="Duplicate",
                applicant_reason="",
                now=2021,
            )

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
        application = await self.data.create_application(
            guild_id=10,
            discord_user_id=50,
            edition=None,
            claimed_username="Real Name",
            answers={"why": "I want to join the community.", "about": "I enjoy building with friendly groups."},
            now=1000,
        )
        self.assertTrue(application.auto_detect_edition)
        outbox = await self.data.get_outbox_batch()
        self.assertEqual(outbox[0].payload["edition"], "AUTO")

        verified, changed = await self.data.record_verification(
            application_id=application.id,
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

    async def test_live_card_reference_survives_database_restart(self):
        application = await self.create_pending()
        await self.data.set_status_message(application.id, 500, 600)
        await self.data.close()
        self.data = MinecraftDataManager(Path(self.directory.name) / "minecraft.db")
        await self.data.open()

        restored = await self.data.get_application_by_status_message(600)
        self.assertEqual(restored.id, application.id)
        self.assertEqual(restored.status_channel_id, "500")

    async def test_pending_verification_is_not_selected_for_dm_card_recovery(self):
        pending = await self.create_pending()
        self.assertNotIn(pending.id, [item.id for item in await self.data.list_live_card_applications()])

        verified, _changed = await self.data.record_verification(
            application_id=pending.id,
            edition=Edition.JAVA,
            minecraft_uuid="123e4567-e89b-12d3-a456-426614174066",
            current_username="TestPlayer",
            xuid=None,
            event_idempotency_key="live-card-after-verification",
            now=1010,
        )
        self.assertIn(verified.id, [item.id for item in await self.data.list_live_card_applications()])

    async def test_join_updates_renamed_account_by_uuid(self):
        application = await self.create_pending()
        await self.data.record_verification(
            application_id=application.id,
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
            dedupe_key="dm:42", kind="USER_EMBED", target_id=42, payload={"title": "Test"}, now=1000
        )
        await self.data.close()
        self.data = MinecraftDataManager(Path(self.directory.name) / "minecraft.db")
        await self.data.open()
        deliveries = await self.data.get_due_deliveries(now=1001)
        self.assertEqual(deliveries[0].dedupe_key, "dm:42")


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


if __name__ == "__main__":
    unittest.main()
