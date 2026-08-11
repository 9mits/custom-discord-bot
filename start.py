"""Production supervisor for the two private bot instances."""

from __future__ import annotations

import os
import signal
import subprocess
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Dict, Iterable, Optional


PRODUCTION_ENV_FILES = (".env.bot1", ".env.bot2")
RESTART_BACKOFF_CAP_SECONDS = 30.0
STABLE_RUNTIME_SECONDS = 300.0
SHUTDOWN_GRACE_SECONDS = 10.0


def load_env(path: Path, *, base_env: Optional[Dict[str, str]] = None) -> Dict[str, str]:
    env = dict(os.environ if base_env is None else base_env)
    with path.open("r", encoding="utf-8") as file:
        for raw_line in file:
            line = raw_line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            if line.startswith("export "):
                line = line[7:].lstrip()
            key, _, value = line.partition("=")
            value = value.strip()
            if len(value) >= 2 and value[0] == value[-1] and value[0] in {"'", '"'}:
                value = value[1:-1]
            env[key.strip()] = value
    return env


@dataclass(frozen=True)
class InstanceSpec:
    name: str
    env_path: Path
    data_dir: Path
    env: Dict[str, str] = field(repr=False, compare=False)


@dataclass
class InstanceState:
    spec: InstanceSpec
    process: Optional[subprocess.Popen] = None
    started_at: float = 0.0
    failures: int = 0
    restart_at: Optional[float] = None


def discover_instances(workdir: Path) -> list[InstanceSpec]:
    specs = []
    for filename in PRODUCTION_ENV_FILES:
        env_path = workdir / filename
        if not env_path.is_file():
            continue
        env = load_env(env_path)
        configured_dir = env.get("BOT_DATA_DIR", "database").strip() or "database"
        data_dir = Path(configured_dir)
        if not data_dir.is_absolute():
            data_dir = workdir / data_dir
        specs.append(InstanceSpec(
            name=filename.removeprefix(".env."),
            env_path=env_path.resolve(),
            data_dir=data_dir.resolve(),
            env=env,
        ))
    return specs


def validate_unique_data_dirs(specs: Iterable[InstanceSpec]) -> None:
    owners: Dict[Path, str] = {}
    for spec in specs:
        owner = owners.get(spec.data_dir)
        if owner is not None:
            raise RuntimeError(
                f"{owner} and {spec.name} resolve to the same BOT_DATA_DIR: {spec.data_dir}"
            )
        owners[spec.data_dir] = spec.name


class ProcessSupervisor:
    def __init__(
        self,
        specs: Iterable[InstanceSpec],
        *,
        workdir: Path,
        process_factory: Callable = subprocess.Popen,
        clock: Callable[[], float] = time.monotonic,
        sleeper: Callable[[float], None] = time.sleep,
    ) -> None:
        self.workdir = workdir.resolve()
        self.process_factory = process_factory
        self.clock = clock
        self.sleeper = sleeper
        self.states = {spec.name: InstanceState(spec=spec) for spec in specs}
        self.stopping = False

    def request_stop(self, *_args) -> None:
        self.stopping = True

    def _launch(self, state: InstanceState) -> None:
        state.process = self.process_factory(
            [sys.executable, "main.py"],
            env=state.spec.env,
            cwd=self.workdir,
        )
        state.started_at = self.clock()
        state.restart_at = None
        print(f"Started {state.spec.name} (pid={state.process.pid}).", flush=True)

    def start_all(self) -> None:
        for state in self.states.values():
            self._launch(state)

    def tick(self) -> None:
        now = self.clock()
        for state in self.states.values():
            process = state.process
            if process is not None:
                return_code = process.poll()
                if return_code is None:
                    continue
                runtime = max(0.0, now - state.started_at)
                state.process = None
                if runtime >= STABLE_RUNTIME_SECONDS:
                    state.failures = 0
                state.failures += 1
                delay = min(RESTART_BACKOFF_CAP_SECONDS, 2 ** max(0, state.failures - 1))
                state.restart_at = now + delay
                print(
                    f"{state.spec.name} exited with code {return_code}; restarting in {delay:.0f}s.",
                    flush=True,
                )
                continue
            if not self.stopping and state.restart_at is not None and now >= state.restart_at:
                self._launch(state)

    def shutdown(self) -> None:
        self.stopping = True
        processes = [state.process for state in self.states.values() if state.process is not None]
        for process in processes:
            if process.poll() is None:
                process.terminate()

        deadline = self.clock() + SHUTDOWN_GRACE_SECONDS
        while self.clock() < deadline and any(process.poll() is None for process in processes):
            self.sleeper(0.1)
        for process in processes:
            if process.poll() is None:
                process.kill()
            process.wait()

    def run(self) -> None:
        self.start_all()
        try:
            while not self.stopping:
                self.tick()
                self.sleeper(0.5)
        finally:
            self.shutdown()


def main() -> int:
    workdir = Path(__file__).resolve().parent
    specs = discover_instances(workdir)
    if not specs:
        print("No .env.bot1 or .env.bot2 files found — nothing to launch.")
        return 1
    try:
        validate_unique_data_dirs(specs)
    except RuntimeError as exc:
        print(f"Startup refused: {exc}")
        return 1

    supervisor = ProcessSupervisor(specs, workdir=workdir)
    for signal_name in (signal.SIGINT, signal.SIGTERM):
        signal.signal(signal_name, supervisor.request_stop)
    supervisor.run()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
