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
        self.assertEqual(
            normalize_username(Edition.BEDROCK, ".FloodgatePrefix"),
            ("FloodgatePrefix", "floodgateprefix"),
        )
        self.assertEqual(
            normalize_username(Edition.JAVA, "Dr_Ravager"),
            ("Dr_Ravager", "dr_ravager"),
        )

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
        self.assertEqual(
            normalize_username(Edition.BEDROCK, ".Real Name"),
            ("Real Name", "real name"),
        )

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
        removal = next(
            record
            for record in await self.data.get_outbox_batch()
            if record.application_id == review.id and record.action is BridgeAction.REMOVE_PENDING
        )
        self.assertEqual(removal.payload, {"application_id": review.id})
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
        await self.data.set_decision_message(application.id, 700, 800)
        await self.data.close()
        self.data = MinecraftDataManager(Path(self.directory.name) / "minecraft.db")
        await self.data.open()

        restored = await self.data.get_application_by_status_message(600)
        self.assertEqual(restored.id, application.id)
        self.assertEqual(restored.status_channel_id, "500")
        self.assertEqual(restored.decision_channel_id, "700")
        self.assertEqual(restored.decision_message_id, "800")

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
            dedupe_key="card:42", kind="LIVE_CARD", target_id=42, payload={"application_id": 42}, now=1000
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
        return await self.data.create_application(
            guild_id=10,
            discord_user_id=user_id,
            edition=Edition.JAVA,
            claimed_username=username,
            now=now,
        )

    async def test_verification_first_flow_waits_for_the_written_form(self):
        application = await self.create_unanswered()
        self.assertEqual(application.answers, {})

        verified, changed = await self.data.record_verification(
            application_id=application.id,
            edition=Edition.JAVA,
            minecraft_uuid="123e4567-e89b-12d3-a456-426614174000",
            current_username="TestPlayer",
            xuid=None,
            event_idempotency_key="verify-first",
            now=1010,
        )

        self.assertTrue(changed)
        self.assertEqual(verified.status, ApplicationStatus.PENDING_APPLICATION)
        # The deadline was extended so the applicant has days, not minutes.
        self.assertGreater(verified.verification_expires_at, 1010 + 24 * 3600)
        # Still the active application, so Apply continues rather than restarting.
        active = await self.data.get_active_application_for_user(
            guild_id=10, discord_user_id=42, now=1011
        )
        self.assertEqual(active.id, application.id)

        submitted = await self.data.submit_answers(
            application.id,
            42,
            why="I want to build with this community.",
            about="I am a considerate builder who enjoys group projects.",
            now=1020,
        )
        self.assertEqual(submitted.status, ApplicationStatus.PENDING_REVIEW)
        self.assertEqual(
            submitted.answers["why"], "I want to build with this community."
        )

    async def test_submit_answers_rejects_other_members_and_wrong_states(self):
        application = await self.create_unanswered()
        with self.assertRaisesRegex(InvalidTransition, "not waiting"):
            await self.data.submit_answers(
                application.id,
                42,
                why="Ten characters long answer.",
                about="Another ten characters long answer.",
                now=1005,
            )
        await self.data.record_verification(
            application_id=application.id,
            edition=Edition.JAVA,
            minecraft_uuid="123e4567-e89b-12d3-a456-426614174000",
            current_username="TestPlayer",
            xuid=None,
            event_idempotency_key="verify-guard",
            now=1010,
        )
        with self.assertRaisesRegex(InvalidTransition, "another member"):
            await self.data.submit_answers(
                application.id,
                43,
                why="Ten characters long answer.",
                about="Another ten characters long answer.",
                now=1011,
            )

    async def test_unfinished_form_expires_and_does_not_block_reapplying(self):
        application = await self.create_unanswered()
        await self.data.record_verification(
            application_id=application.id,
            edition=Edition.JAVA,
            minecraft_uuid="123e4567-e89b-12d3-a456-426614174000",
            current_username="TestPlayer",
            xuid=None,
            event_idempotency_key="verify-expire",
            now=1010,
        )
        deadline = (await self.data.get_application(application.id)).verification_expires_at

        expired = await self.data.expire_pending(now=deadline + 1)
        self.assertEqual([item.id for item in expired], [application.id])
        self.assertEqual(
            (await self.data.get_application(application.id)).status,
            ApplicationStatus.EXPIRED,
        )
        with self.assertRaisesRegex(InvalidTransition, "expired"):
            await self.data.submit_answers(
                application.id,
                42,
                why="Ten characters long answer.",
                about="Another ten characters long answer.",
                now=deadline + 2,
            )
        # The same member can start over with the very same account.
        replacement = await self.data.create_application(
            guild_id=10,
            discord_user_id=42,
            edition=Edition.JAVA,
            claimed_username="TestPlayer",
            now=deadline + 10,
        )
        self.assertEqual(replacement.status, ApplicationStatus.PENDING_VERIFICATION)

    async def test_whitelist_directory_lists_approved_players(self):
        application = await self.create_pending()
        await self.data.record_verification(
            application_id=application.id,
            edition=Edition.JAVA,
            minecraft_uuid="123e4567-e89b-12d3-a456-426614174000",
            current_username="TestPlayer",
            xuid=None,
            event_idempotency_key="verify-whitelist",
            now=1010,
        )
        await self.data.queue_approval(application.id, 77, now=1020)
        self.assertEqual(await self.data.list_whitelisted(), [])
        await self.data.complete_outbox(
            f"application:{application.id}:approve:123e4567-e89b-12d3-a456-426614174000"
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
            application_id=application.id,
            edition=Edition.JAVA,
            minecraft_uuid="123e4567-e89b-12d3-a456-426614174000",
            current_username="TestPlayer",
            xuid=None,
            event_idempotency_key="verify-wipe",
            now=1010,
        )

        counts = await self.data.wipe_all_data(actor_id=9)

        self.assertEqual(counts["minecraft_applications"], 1)
        self.assertEqual(counts["minecraft_accounts"], 1)
        self.assertIsNone(await self.data.get_application(application.id))
        self.assertEqual(await self.data.list_accounts_for_user(42), [])
        self.assertEqual(await self.data.list_whitelisted(), [])
        # Settings survive the wipe.
        self.assertEqual(await self.data.get_config("application_channel_id"), 123)
        # Paper is told to drop the whitelist entry and forget the application.
        outbox = await self.data.get_outbox_batch()
        actions = {(record.action, record.application_id) for record in outbox}
        self.assertIn((BridgeAction.REVOKE, None), actions)
        self.assertIn((BridgeAction.REMOVE_PENDING, None), actions)
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
        application = await self.data.create_application(
            guild_id=10,
            discord_user_id=user_id,
            edition=Edition.JAVA,
            claimed_username=username,
            answers={
                "why": "I want to build with this community.",
                "about": "I am a considerate builder who enjoys group projects.",
            },
            now=now,
        )
        await self.data.record_verification(
            application_id=application.id,
            edition=Edition.JAVA,
            minecraft_uuid="123e4567-e89b-12d3-a456-426614174000",
            current_username=username,
            xuid=None,
            event_idempotency_key=f"verify-java-{user_id}",
            now=now + 10,
        )
        await self.data.queue_approval(application.id, 99, now=now + 20)
        approval = next(
            record
            for record in await self.data.get_outbox_batch()
            if record.action is BridgeAction.APPROVE
        )
        await self.data.complete_outbox(approval.idempotency_key)
        return application

    async def _verify_bedrock(self, *, user_id=42, gamertag="Test Gamer", now=2000):
        application = await self.data.create_application(
            guild_id=10,
            discord_user_id=user_id,
            edition=Edition.BEDROCK,
            claimed_username=gamertag,
            now=now,
        )
        verified, _changed = await self.data.record_verification(
            application_id=application.id,
            edition=Edition.BEDROCK,
            minecraft_uuid="223e4567-e89b-12d3-a456-426614174000",
            current_username=gamertag,
            xuid="2535400000000000",
            event_idempotency_key=f"verify-bedrock-{user_id}-{now}",
            now=now + 10,
        )
        return verified

    async def test_an_accepted_member_links_the_other_edition_without_a_second_review(self):
        await self._approve_java()

        verified = await self._verify_bedrock()

        # Straight past the written form and the review queue: the person was vetted
        # already, and verification is the only new fact.
        self.assertEqual(verified.status, ApplicationStatus.APPROVAL_QUEUED)
        self.assertIsNone(verified.reviewed_by)

        approvals = [
            record
            for record in await self.data.get_outbox_batch()
            if record.action is BridgeAction.APPROVE
            and record.application_id == verified.id
        ]
        self.assertEqual(len(approvals), 1)

        await self.data.complete_outbox(approvals[0].idempotency_key)
        final = await self.data.get_application(verified.id)
        self.assertEqual(final.status, ApplicationStatus.APPROVED)

        # Both editions are now linked, and both carry whitelist access.
        accounts = {row["edition"] for row in await self.data.list_accounts_for_user(42)}
        self.assertEqual({"JAVA", "BEDROCK"}, accounts)
        self.assertEqual(2, len(await self.data.list_whitelisted()))

    async def test_the_auto_approval_records_no_moderator(self):
        # Naming the bot as the reviewer would be a lie in the staff log; the audit
        # has to say plainly that nobody reviewed it.
        await self._approve_java()
        verified = await self._verify_bedrock()

        actions = [row["action"] for row in await self.data.audit_rows(verified.id)]
        self.assertIn("LINK_AUTO_APPROVED", actions)
        self.assertNotIn("APPROVAL_QUEUED", actions)

        entry = next(
            row
            for row in await self.data.audit_rows(verified.id)
            if row["action"] == "LINK_AUTO_APPROVED"
        )
        self.assertIsNone(entry["actor_discord_id"])
        self.assertEqual(entry["target_discord_id"], "42")

    async def test_a_first_application_still_waits_for_the_form(self):
        # The guard is "already holds approved access", not "used the link button" —
        # so this cannot become a way to skip review on a first application.
        application = await self.data.create_application(
            guild_id=10,
            discord_user_id=77,
            edition=Edition.JAVA,
            claimed_username="Newcomer",
            now=1000,
        )
        verified, _ = await self.data.record_verification(
            application_id=application.id,
            edition=Edition.JAVA,
            minecraft_uuid="323e4567-e89b-12d3-a456-426614174000",
            current_username="Newcomer",
            xuid=None,
            event_idempotency_key="verify-newcomer",
            now=1010,
        )

        self.assertEqual(verified.status, ApplicationStatus.PENDING_APPLICATION)
        self.assertEqual([], [
            record
            for record in await self.data.get_outbox_batch()
            if record.action is BridgeAction.APPROVE
        ])

    async def test_a_revoked_member_goes_through_the_full_application(self):
        approved = await self._approve_java(user_id=55, username="WasHere")
        await self.data.unlink_account(55, Edition.JAVA, 99, "Revoked for cause")
        revoke = next(
            record
            for record in await self.data.get_outbox_batch()
            if record.action is BridgeAction.REVOKE
        )
        await self.data.complete_outbox(revoke.idempotency_key)
        self.assertEqual(
            (await self.data.get_application(approved.id)).status,
            ApplicationStatus.REVOKED,
        )

        verified = await self._verify_bedrock(user_id=55, gamertag="Was Here", now=3000)

        # No approved row left, so they are a stranger again.
        self.assertEqual(verified.status, ApplicationStatus.PENDING_APPLICATION)

    async def test_the_one_per_edition_ceiling_is_unchanged(self):
        await self._approve_java()

        with self.assertRaises(AccountEditionAlreadyLinked):
            await self.data.create_application(
                guild_id=10,
                discord_user_id=42,
                edition=Edition.JAVA,
                claimed_username="SomeoneElse",
                now=5000,
            )


class MinecraftAccessIntegrityTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.data = MinecraftDataManager(Path(self.directory.name) / "minecraft.db")
        await self.data.open()

    async def asyncTearDown(self):
        await self.data.close()
        self.directory.cleanup()

    async def test_two_users_cannot_claim_the_same_username(self):
        await self.data.create_application(
            guild_id=10,
            discord_user_id=1,
            edition=Edition.JAVA,
            claimed_username="Steve",
            now=1000,
        )
        with self.assertRaises(ValueError):
            await self.data.create_application(
                guild_id=10,
                discord_user_id=2,
                edition=Edition.JAVA,
                claimed_username="Steve",
                now=1001,
            )

    async def test_unlink_cancels_a_pending_application(self):
        application = await self.data.create_application(
            guild_id=10,
            discord_user_id=42,
            edition=Edition.JAVA,
            claimed_username="TestPlayer",
            now=1000,
        )
        await self.data.record_verification(
            application_id=application.id,
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
        self.assertFalse(queued)
        self.assertEqual(affected[0].status, ApplicationStatus.CANCELLED)
        self.assertEqual(await self.data.list_accounts_for_user(42), [])

    async def test_deny_releases_the_minecraft_account(self):
        application = await self.data.create_application(
            guild_id=10,
            discord_user_id=42,
            edition=Edition.JAVA,
            claimed_username="TestPlayer",
            answers={
                "why": "I want to build with this community.",
                "about": "I am a considerate builder who enjoys group projects.",
            },
            now=1000,
        )
        await self.data.record_verification(
            application_id=application.id,
            edition=Edition.JAVA,
            minecraft_uuid="123e4567-e89b-12d3-a456-426614174000",
            current_username="TestPlayer",
            xuid=None,
            event_idempotency_key="verify-deny-release",
            now=1010,
        )
        await self.data.deny_application(
            application.id,
            99,
            internal_note="Not a fit",
            applicant_reason="Please reapply later",
            now=1020,
        )

        replacement = await self.data.create_application(
            guild_id=10,
            discord_user_id=42,
            edition=Edition.JAVA,
            claimed_username="OtherJava",
            now=1030,
        )
        self.assertEqual(replacement.claimed_username, "OtherJava")

    async def test_late_approve_does_not_resurrect_a_cancelled_outbox_row(self):
        application = await self.data.create_application(
            guild_id=10,
            discord_user_id=42,
            edition=Edition.JAVA,
            claimed_username="TestPlayer",
            answers={
                "why": "I want to build with this community.",
                "about": "I am a considerate builder who enjoys group projects.",
            },
            now=1000,
        )
        await self.data.record_verification(
            application_id=application.id,
            edition=Edition.JAVA,
            minecraft_uuid="123e4567-e89b-12d3-a456-426614174000",
            current_username="TestPlayer",
            xuid=None,
            event_idempotency_key="verify-cancel-approve",
            now=1010,
        )
        await self.data.queue_approval(application.id, 99, now=1020)
        approve = next(
            record
            for record in await self.data.get_outbox_batch()
            if record.action is BridgeAction.APPROVE
        )
        await self.data.cancel_application(application.id, 99)
        record, updated, newly = await self.data.complete_outbox(approve.idempotency_key)

        self.assertFalse(newly)
        self.assertEqual(updated.status, ApplicationStatus.CANCELLED)
        self.assertEqual(record.status, "CANCELLED")

    async def test_expired_verification_is_acknowledged_without_raising(self):
        application = await self.data.create_application(
            guild_id=10,
            discord_user_id=42,
            edition=Edition.JAVA,
            claimed_username="TestPlayer",
            verification_seconds=10,
            now=1000,
        )
        updated, changed = await self.data.record_verification(
            application_id=application.id,
            edition=Edition.JAVA,
            minecraft_uuid="123e4567-e89b-12d3-a456-426614174000",
            current_username="TestPlayer",
            xuid=None,
            event_idempotency_key="verify-expired",
            now=2000,
        )
        self.assertFalse(changed)
        self.assertEqual(updated.status, ApplicationStatus.EXPIRED)

    async def test_verification_rejects_a_malformed_uuid(self):
        application = await self.data.create_application(
            guild_id=10,
            discord_user_id=42,
            edition=Edition.JAVA,
            claimed_username="TestPlayer",
            now=1000,
        )
        with self.assertRaises(InvalidTransition):
            await self.data.record_verification(
                application_id=application.id,
                edition=Edition.JAVA,
                minecraft_uuid="not-a-uuid",
                current_username="TestPlayer",
                xuid=None,
                event_idempotency_key="verify-bad-uuid",
                now=1010,
            )

    async def test_username_search_treats_like_wildcards_as_literals(self):
        await self.data.create_application(
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
        application = await self.data.create_application(
            guild_id=10,
            discord_user_id=8,
            edition=None,
            claimed_username="Dr_Ravager",
            now=1000,
        )
        verified, changed = await self.data.record_verification(
            application_id=application.id,
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
        application = await self.data.create_application(
            guild_id=10,
            discord_user_id=9,
            edition=Edition.BEDROCK,
            claimed_username="FER GAMER3520",
            now=1000,
        )
        verified, changed = await self.data.record_verification(
            application_id=application.id,
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
        application = await self.data.create_application(
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
