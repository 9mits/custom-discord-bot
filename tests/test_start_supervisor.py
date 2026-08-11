import tempfile
import unittest
from pathlib import Path

from start import (
    InstanceSpec,
    ProcessSupervisor,
    discover_instances,
    load_env,
    validate_unique_data_dirs,
)


class FakeProcess:
    next_pid = 100

    def __init__(self, command, *, env, cwd):
        self.command = command
        self.env = env
        self.cwd = cwd
        self.pid = FakeProcess.next_pid
        FakeProcess.next_pid += 1
        self.return_code = None
        self.terminated = False
        self.killed = False

    def poll(self):
        return self.return_code

    def terminate(self):
        self.terminated = True
        self.return_code = 0

    def kill(self):
        self.killed = True
        self.return_code = -9

    def wait(self):
        return self.return_code


class StartConfigurationTests(unittest.TestCase):
    def test_load_env_supports_quotes_and_export(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            env_path = Path(temp_dir) / ".env.bot1"
            env_path.write_text(
                "export BOT_DATA_DIR='database-one'\nDISCORD_BOT_TOKEN=token\n",
                encoding="utf-8",
            )
            env = load_env(env_path, base_env={})
            self.assertEqual(env["BOT_DATA_DIR"], "database-one")
            self.assertEqual(env["DISCORD_BOT_TOKEN"], "token")

    def test_discovery_launches_only_production_env_files(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            workdir = Path(temp_dir)
            (workdir / ".env.bot1").write_text("BOT_DATA_DIR=database-one\n", encoding="utf-8")
            (workdir / ".env.bot2").write_text("BOT_DATA_DIR=database-two\n", encoding="utf-8")
            (workdir / ".env.test").write_text("BOT_DATA_DIR=database-test\n", encoding="utf-8")

            specs = discover_instances(workdir)

            self.assertEqual([spec.name for spec in specs], ["bot1", "bot2"])
            self.assertNotIn("database-test", {spec.data_dir.name for spec in specs})

    def test_duplicate_resolved_data_directories_are_rejected(self):
        shared = Path("/tmp/shared-bot-data").resolve()
        specs = [
            InstanceSpec("bot1", Path(".env.bot1"), shared, {}),
            InstanceSpec("bot2", Path(".env.bot2"), shared, {}),
        ]
        with self.assertRaisesRegex(RuntimeError, "same BOT_DATA_DIR"):
            validate_unique_data_dirs(specs)


class ProcessSupervisorTests(unittest.TestCase):
    def setUp(self):
        self.now = 100.0
        self.created = []
        specs = [
            InstanceSpec("bot1", Path(".env.bot1"), Path("/tmp/bot-one"), {"A": "1"}),
            InstanceSpec("bot2", Path(".env.bot2"), Path("/tmp/bot-two"), {"A": "2"}),
        ]

        def factory(command, *, env, cwd):
            process = FakeProcess(command, env=env, cwd=cwd)
            self.created.append(process)
            return process

        self.supervisor = ProcessSupervisor(
            specs,
            workdir=Path.cwd(),
            process_factory=factory,
            clock=lambda: self.now,
            sleeper=lambda delay: None,
        )

    def test_dead_child_is_restarted_without_stopping_healthy_sibling(self):
        self.supervisor.start_all()
        failed, healthy = self.created
        failed.return_code = 1

        self.supervisor.tick()
        self.assertEqual(len(self.created), 2)
        self.assertIsNone(self.supervisor.states["bot1"].process)
        self.assertIs(self.supervisor.states["bot2"].process, healthy)

        self.now += 1
        self.supervisor.tick()
        self.assertEqual(len(self.created), 3)
        self.assertIs(self.supervisor.states["bot1"].process, self.created[2])
        self.assertIs(self.supervisor.states["bot2"].process, healthy)

    def test_restart_backoff_is_capped(self):
        self.supervisor.start_all()
        state = self.supervisor.states["bot1"]
        state.failures = 20
        state.process.return_code = 1

        self.supervisor.tick()

        self.assertEqual(state.restart_at, self.now + 30)

    def test_shutdown_terminates_every_live_child(self):
        self.supervisor.start_all()
        self.supervisor.shutdown()

        self.assertTrue(all(process.terminated for process in self.created))
        self.assertTrue(self.supervisor.stopping)


if __name__ == "__main__":
    unittest.main()
