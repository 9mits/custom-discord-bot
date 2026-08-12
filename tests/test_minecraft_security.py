import hashlib
import hmac
import json
import unittest

from minecraft_bot.security import create_envelope, verify_envelope


class MinecraftBridgeSecurityTests(unittest.TestCase):
    def setUp(self):
        self.secret = bytes(range(32))

    def test_hmac_creation_and_verification(self):
        envelope = create_envelope(
            self.secret,
            "HELLO",
            {"server_id": "mysterious-smp-x"},
            idempotency_key="hello-1",
            now=1_700_000_000,
            nonce="nonce-1",
        )

        self.assertTrue(
            verify_envelope(
                self.secret,
                envelope,
                used_nonces=set(),
                now=1_700_000_010,
            )
        )
        unsigned = {key: value for key, value in envelope.items() if key != "signature"}
        canonical = json.dumps(
            unsigned,
            sort_keys=True,
            separators=(",", ":"),
            ensure_ascii=False,
        ).encode("utf-8")
        expected = hmac.new(self.secret, canonical, hashlib.sha256).hexdigest()
        self.assertEqual(envelope["signature"], expected)

    def test_expired_timestamp_is_rejected(self):
        envelope = create_envelope(
            self.secret,
            "HEARTBEAT",
            {},
            now=1_700_000_000,
            nonce="expired",
        )
        self.assertFalse(
            verify_envelope(
                self.secret,
                envelope,
                used_nonces=set(),
                now=1_700_000_031,
            )
        )

    def test_reused_nonce_is_rejected(self):
        envelope = create_envelope(
            self.secret,
            "HEARTBEAT",
            {},
            now=1_700_000_000,
            nonce="reused",
        )
        nonces = set()
        self.assertTrue(verify_envelope(self.secret, envelope, used_nonces=nonces, now=1_700_000_000))
        self.assertFalse(verify_envelope(self.secret, envelope, used_nonces=nonces, now=1_700_000_000))

    def test_tampered_payload_is_rejected(self):
        envelope = create_envelope(
            self.secret,
            "ACTION",
            {"action": "STATUS"},
            now=1_700_000_000,
        )
        envelope["payload"]["action"] = "APPROVE"
        self.assertFalse(verify_envelope(self.secret, envelope, used_nonces=set(), now=1_700_000_000))


if __name__ == "__main__":
    unittest.main()
