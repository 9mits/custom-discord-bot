import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from minecraft_bot.support import (
    claim_support_request,
    enqueue_support_request,
    list_support_requests,
    read_support_request,
)


class MinecraftSupportQueueTests(unittest.TestCase):
    def test_request_is_written_atomically_and_claimed_once(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(
            "os.environ", {"MINECRAFT_SUPPORT_QUEUE_DIR": directory}
        ):
            request_id = enqueue_support_request(
                guild_id=10,
                discord_user_id=42,
                access_id=7,
                status="PENDING_VERIFICATION",
                username="TestPlayer",
            )
            queued = list_support_requests()
            self.assertEqual([path.name for path in queued], [f"{request_id}.json"])
            self.assertFalse(list(Path(directory).glob("*.tmp")))
            self.assertEqual(read_support_request(queued[0])["guild_id"], "10")

            claimed = claim_support_request(queued[0])
            self.assertIsNotNone(claimed)
            path, payload = claimed
            self.assertEqual(payload["access_id"], 7)
            self.assertEqual(json.loads(path.read_text())["discord_user_id"], "42")
            self.assertIsNone(claim_support_request(queued[0]))


if __name__ == "__main__":
    unittest.main()
