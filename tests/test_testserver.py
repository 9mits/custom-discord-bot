import unittest
import uuid
from pathlib import Path
from tempfile import TemporaryDirectory
from types import SimpleNamespace
from unittest import mock

from scripts import testserver


class LocalBridgeConfigTests(unittest.TestCase):
    def test_existing_production_setting_is_forced_off_by_default(self):
        original = "allow-insecure-localhost: true\nverification-required: true\n"

        patched = testserver.local_bridge_config(original)

        self.assertIn("verification-required: false", patched)
        self.assertNotIn("verification-required: true", patched)
        self.assertEqual(patched, testserver.local_bridge_config(patched))

    def test_explicit_test_run_can_require_verification(self):
        original = "allow-insecure-localhost: true\nverification-required: false\n"

        patched = testserver.local_bridge_config(
            original,
            verification_required=True,
        )

        self.assertIn("verification-required: true", patched)
        self.assertNotIn("verification-required: false", patched)
        self.assertEqual(
            patched,
            testserver.local_bridge_config(patched, verification_required=True),
        )

    def test_missing_setting_is_added_to_existing_local_config(self):
        original = "allow-insecure-localhost: true\nreconnect-max-seconds: 60\n"

        patched = testserver.local_bridge_config(original)

        self.assertIn(
            "allow-insecure-localhost: true\nverification-required: false\n",
            patched,
        )


class JavaResourcePackConfigTests(unittest.TestCase):
    def test_pack_hash_updates_url_hash_and_cache_identity(self):
        original = """\
require-resource-pack=false
resource-pack=http://old.invalid/old.zip
resource-pack-sha1=old
resource-pack-id=00000000-0000-0000-0000-000000000000
motd=test
"""

        patched, pack_id = testserver.java_pack_properties(original, "a" * 40)

        self.assertIn("require-resource-pack=true", patched)
        self.assertIn(f"127.0.0.1:{testserver.PACK_SERVER_PORT}", patched)
        self.assertIn("?sha1=" + "a" * 40, patched)
        self.assertIn("resource-pack-sha1=" + "a" * 40, patched)
        self.assertIn("resource-pack-id=" + pack_id, patched)
        self.assertEqual(uuid.UUID(pack_id).version, 5)
        self.assertEqual((patched, pack_id), testserver.java_pack_properties(patched, "a" * 40))

    def test_changed_pack_hash_gets_a_different_cache_identity(self):
        _, first = testserver.java_pack_properties("motd=test\n", "a" * 40)
        _, second = testserver.java_pack_properties("motd=test\n", "b" * 40)

        self.assertNotEqual(first, second)


class GrimPrinterConfigTests(unittest.TestCase):
    def test_printer_checks_never_cancel_or_set_back_and_patch_is_idempotent(self):
        original = """\
AirLiquidPlace:
    cancelvl: 0

FabricatedPlace:
    setbackvl: 5

FarPlace:
    setbackvl: 8
    cancelvl: 5
"""

        patched = testserver.grim_printer_config(original)

        for check in testserver.GRIM_PRINTER_PLACE_CHECKS:
            self.assertIn(f"{check}:\n    cancelvl: -1\n    setbackvl: -1", patched)
        self.assertIn("PacketOrderE:\n    setbackvl: -1", patched)
        self.assertNotIn("PacketOrderE:\n    cancelvl:", patched)
        self.assertIn("FarPlace:\n    cancelvl: -1\n    setbackvl: -1", patched)
        self.assertEqual(patched, testserver.grim_printer_config(patched))

    def test_printer_checks_are_removed_from_the_kick_group(self):
        original = """\
Punishments:
  BadPackets:
    checks:
      - "BadPackets"
      - "PacketOrder"
  Misc:
    checks:
      - "Place"
      - "Break"
"""

        patched = testserver.grim_printer_punishments(original)

        for check in testserver.GRIM_PRINTER_PLACE_CHECKS:
            self.assertIn(f'- "!{check}"', patched)
            self.assertIn(f'      - "{check}"', patched)
        self.assertIn('- "!PacketOrderE"', patched)
        self.assertIn('      - "PacketOrderE"', patched)
        self.assertIn('- "Place"', patched)
        self.assertIn('- "Break"', patched)
        self.assertIn("  LitematicaPrinter:", patched)
        self.assertIn('      - "5:5 [alert]"', patched)
        self.assertIn('      - "1:1 [log]"', patched)
        self.assertNotIn("kick %player%", patched)
        self.assertEqual(patched, testserver.grim_printer_punishments(patched))

    def test_existing_printer_group_is_migrated(self):
        original = """\
Punishments:
  Misc:
    checks:
      - "Place"
  LitematicaPrinter:
    remove-violations-after: 300
    checks:
      - "AirLiquidPlace"
    commands:
      - "1:1 [log]"
  Combat:
    checks:
      - "Interact"
"""

        patched = testserver.grim_printer_punishments(original)

        for check in testserver.GRIM_PRINTER_CHECKS:
            self.assertIn(f'- "{check}"', patched)
        self.assertEqual(patched, testserver.grim_printer_punishments(patched))


class GeyserRefreshTests(unittest.TestCase):
    def test_refresh_uses_official_digest_and_records_installed_build(self):
        payload = b"current official Geyser build"
        digest = testserver.hashlib.sha256(payload).hexdigest()
        metadata = {
            "version": "2.11.2",
            "build": 1233,
            "downloads": {"spigot": {"sha256": digest}},
        }

        with TemporaryDirectory() as directory:
            root = Path(directory)
            plugins = root / "plugins"
            plugins.mkdir()

            def install(url, destination, expected):
                self.assertEqual(url, testserver.GEYSER_API.format(p="geyser"))
                self.assertEqual(expected, digest)
                destination.write_bytes(payload)

            with mock.patch.object(testserver, "REPO", root), mock.patch.object(
                testserver, "PLUGINS", plugins
            ), mock.patch.object(testserver, "read_json", return_value=metadata), mock.patch.object(
                testserver, "fetch_verified", side_effect=install
            ):
                installed = testserver.refresh_geyser()

        self.assertEqual(installed["version"], "2.11.2")
        self.assertEqual(installed["build"], 1233)
        self.assertEqual(installed["path"], "plugins/geyser.jar")
        self.assertEqual(installed["bytes"], len(payload))
        self.assertEqual(installed["sha256"], digest)

    def test_refresh_refuses_a_build_without_bedrock_26_45_support(self):
        metadata = {
            "version": "2.11.1",
            "build": testserver.MINIMUM_GEYSER_BUILD - 1,
            "downloads": {"spigot": {"sha256": "0" * 64}},
        }
        with mock.patch.object(testserver, "read_json", return_value=metadata), mock.patch.object(
            testserver, "fetch_verified"
        ) as fetch:
            with self.assertRaisesRegex(RuntimeError, "Bedrock 26.45"):
                testserver.refresh_geyser()
        fetch.assert_not_called()


class TestServerRestartTests(unittest.TestCase):
    def test_restart_deploys_stops_running_paper_and_starts(self):
        args = SimpleNamespace(memory="2G")
        calls = []
        with mock.patch.object(
            testserver, "deploy", side_effect=lambda _: calls.append("deploy") or 0
        ), mock.patch.object(testserver, "running_server_pid", return_value=123), mock.patch.object(
            testserver, "stop_server", side_effect=lambda _: calls.append("stop") or True
        ), mock.patch.object(
            testserver, "start", side_effect=lambda _: calls.append("start") or 0
        ):
            self.assertEqual(testserver.restart(args), 0)
        self.assertEqual(calls, ["deploy", "stop", "start"])

    def test_restart_keeps_current_server_when_deploy_fails(self):
        args = SimpleNamespace(memory="2G")
        with mock.patch.object(testserver, "deploy", return_value=7), mock.patch.object(
            testserver, "running_server_pid"
        ) as running, mock.patch.object(testserver, "stop_server") as stop, mock.patch.object(
            testserver, "start"
        ) as start:
            self.assertEqual(testserver.restart(args), 7)
        running.assert_not_called()
        stop.assert_not_called()
        start.assert_not_called()


if __name__ == "__main__":
    unittest.main()
