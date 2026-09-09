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

    def test_restart_detaches_so_paper_outlives_the_shell_that_deployed_it(self):
        # A foreground Paper dies with the agent command that started it, and the
        # player meets "Connection refused" from a deploy that reported success.
        args = SimpleNamespace(memory="2G")
        seen = {}
        with mock.patch.object(testserver, "deploy", return_value=0), mock.patch.object(
            testserver, "running_server_pid", return_value=None
        ), mock.patch.object(
            testserver, "start", side_effect=lambda a: seen.update(detach=a.detach) or 0
        ):
            self.assertEqual(testserver.restart(args), 0)
        self.assertTrue(seen["detach"])

    def test_restart_foreground_is_still_available_on_request(self):
        args = SimpleNamespace(memory="2G", foreground=True)
        seen = {}
        with mock.patch.object(testserver, "deploy", return_value=0), mock.patch.object(
            testserver, "running_server_pid", return_value=None
        ), mock.patch.object(
            testserver,
            "start",
            side_effect=lambda a: seen.update(detach=getattr(a, "detach", False)) or 0,
        ):
            self.assertEqual(testserver.restart(args), 0)
        self.assertFalse(seen["detach"])


class DetachedPaperTests(unittest.TestCase):
    def test_a_detached_start_gets_its_own_session_and_no_inherited_pipes(self):
        with TemporaryDirectory() as holder:
            server = Path(holder)
            (server / "eula.txt").write_text("eula=true\n")
            (server / "logs").mkdir()
            with mock.patch.object(testserver, "SERVER", server), mock.patch.object(
                testserver, "PLUGINS", server / "plugins"
            ), mock.patch.object(
                testserver, "SERVER_PID", server / "server.pid"
            ), mock.patch.object(
                testserver, "LATEST_LOG", server / "logs" / "latest.log"
            ), mock.patch.object(
                testserver, "match_production_limits"
            ), mock.patch.object(testserver, "configure_grim"), mock.patch.object(
                testserver, "running_server_pid", return_value=None
            ), mock.patch.object(
                testserver, "server_java_binary", return_value=Path("/bin/true")
            ), mock.patch.object(
                testserver, "wait_for_done", return_value=True
            ), mock.patch.object(
                testserver, "subprocess"
            ) as sub:
                sub.DEVNULL = -3
                sub.Popen.return_value = SimpleNamespace(pid=4242)
                code = testserver.start(SimpleNamespace(memory="2G", detach=True))

        self.assertEqual(0, code)
        kwargs = sub.Popen.call_args.kwargs
        self.assertTrue(kwargs["start_new_session"])
        self.assertEqual(-3, kwargs["stdout"])
        self.assertEqual(-3, kwargs["stderr"])

    STALE = (
        '[00:00:00] Starting minecraft server version 1.21.11\n'
        '[00:00:10] Done (1.0s)! For help, type "help"\n'
    )

    def test_the_previous_run_s_done_line_is_not_mistaken_for_this_one(self):
        # Paper gzips latest.log at startup, so the last server's "Done" is still on
        # disk for a moment. Identity by inode looked right and is not: a new
        # latest.log can reuse the inode the rotated one just freed.
        with TemporaryDirectory() as holder:
            log = Path(holder) / "latest.log"
            log.write_text(self.STALE)
            alive = SimpleNamespace(poll=lambda: None, returncode=None)
            with mock.patch.object(testserver, "LATEST_LOG", log):
                self.assertFalse(
                    testserver.wait_for_done(alive, self.STALE, timeout=0.2)
                )
                log.unlink()
                log.write_text(
                    "[00:01:00] Starting minecraft server version 1.21.11\n"
                    '[00:01:30] Done (2.0s)! For help, type "help"\n'
                )
                self.assertTrue(
                    testserver.wait_for_done(alive, self.STALE, timeout=5.0)
                )

    def test_a_started_but_unfinished_run_is_not_reported_ready(self):
        booting = self.STALE + "[00:01:00] Starting minecraft server version 1.21.11\n"
        self.assertFalse(testserver.log_reports_ready(booting))
        self.assertTrue(
            testserver.log_reports_ready(booting + '[00:01:30] Done (3.0s)!\n')
        )

    def test_a_paper_that_dies_during_startup_is_reported_not_waited_out(self):
        dead = SimpleNamespace(poll=lambda: 1, returncode=1)
        self.assertFalse(testserver.wait_for_done(dead, None, timeout=30.0))


class WorldProtectionPluginTests(unittest.TestCase):
    """WorldGuard and WorldEdit, matching the builds GravelHost runs.

    Production has had both for a long time and the test server did not, which
    is the difference that lets a protection rule behave one way in a test and
    another way in the game.
    """

    def _source(self) -> str:
        return Path(testserver.__file__).read_text(encoding="utf-8")

    def test_both_jars_are_pinned_by_hash_rather_than_merely_downloaded(self):
        source = self._source()
        body = source[
            source.index("def ensure_world_protection") : source.index("def read_json")
        ]

        self.assertIn("fetch_verified(url, jar, digest)", body)
        self.assertEqual(64, len(testserver.WORLDGUARD_SHA256))
        self.assertEqual(64, len(testserver.WORLDEDIT_SHA256))
        # The version production runs, not merely the newest one published.
        self.assertIn("worldguard-bukkit-7.0.17.jar", testserver.WORLDGUARD_URL)
        self.assertIn("worldedit-bukkit-7.4.5.jar", testserver.WORLDEDIT_URL)

    def test_an_existing_server_picks_them_up_without_a_second_setup(self):
        source = self._source()

        # Once in setup and once in deploy, so a server built before these
        # existed installs them on its next restart rather than never.
        self.assertEqual(2, source.count("    ensure_world_protection()"))

    def test_paper_runs_on_the_jvm_production_runs_not_the_build_one(self):
        source = self._source()

        # WorldGuard ships Java 25 bytecode. Running Paper on the plugin's own
        # Java 21 toolchain is what made both protection plugins refuse to load
        # on the machine that exists to reproduce production.
        self.assertEqual(21, testserver.BUILD_JAVA)
        self.assertEqual(25, testserver.SERVER_JAVA)
        self.assertIn("str(server_java_binary())", source)
        # Gradle keeps the toolchain it was built against.
        self.assertIn("JAVA_HOME=str(java_home())", source)

    def test_a_missing_server_jvm_falls_back_rather_than_refusing_to_start(self):
        source = self._source()
        body = source[
            source.index("def server_java_binary") : source.index("def _write_atomic")
            if "def _write_atomic" in source
            else source.index("def server_java_binary") + 1200
        ]

        self.assertIn("return java_binary()", body)

    def test_a_missing_download_does_not_stop_the_server_starting(self):
        source = self._source()
        body = source[
            source.index("def ensure_world_protection") : source.index("def read_json")
        ]

        # The bridge depends on neither, so an unreachable CDN must cost a
        # warning rather than the ability to test anything at all.
        self.assertIn("continue", body)
        self.assertIn("will run without it", body)


if __name__ == "__main__":
    unittest.main()
