#!/usr/bin/env python3
"""Local Paper server for trying plugin changes before they reach players.

Production is GravelHost, which has no API and only an SFTP deploy, so the only
way to see a `minecraft-bridge/` change in game used to be to ship it to the
live server. This runs the same Paper build locally instead.

    python scripts/testserver.py setup    # once: fetch Paper and the plugins
    python scripts/testserver.py deploy   # build and install without starting
    python scripts/testserver.py run      # build, install, start

The server lives in `runtime/testserver/`, which is git-ignored, so nothing here
can reach a commit. It is deliberately NOT a copy of production: offline mode and
no whitelist, so alt accounts can join to test the multiplayer events, and no
resource pack, so a slow GitHub cannot stall a test.

Stdlib only, matching panel.py.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import re
import secrets
import shutil
import subprocess
import sys
import tarfile
import urllib.request
import zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
SERVER = REPO / "runtime" / "testserver"
PLUGINS = SERVER / "plugins"
BRIDGE = REPO / "minecraft-bridge"
TEST_BUILD_MANIFEST = SERVER / "test-build.json"

#: Pinned to the build production runs, so a test reproduces production's Paper.
PAPER_VERSION = "1.21.11"
PAPER_BUILD = 132
PAPER_API = "https://fill.papermc.io/v3/projects/paper/versions/{v}/builds/{b}"

GEYSER_API = "https://download.geysermc.org/v2/projects/{p}/versions/latest/builds/latest/downloads/spigot"
LUCKPERMS_META = "https://metadata.luckperms.net/data/all"
VIAVERSION_API = (
    "https://api.modrinth.com/v2/project/viaversion/version"
    "?loaders=%5B%22paper%22%5D&game_versions=%5B%22{v}%22%5D"
)

#: Floodgate is a hard `depend:` in plugin.yml — without it the plugin will not
#: load at all. Geyser and LuckPerms are soft, but the bridge talks to both.
REQUIRED_PLUGINS = ("floodgate", "geyser")

JDK_HOME = Path.home() / ".mgx-jdk21"
JDK_URL = (
    "https://api.adoptium.net/v3/binary/latest/21/ga/mac/{arch}/jdk/hotspot/normal/eclipse"
)

SERVER_PROPERTIES = """\
# Written by scripts/testserver.py. Deliberately not a copy of production.
online-mode=false
white-list=false
enforce-whitelist=false
require-resource-pack=false
spawn-protection=0
max-players=20
view-distance=8
simulation-distance=6
server-port=25565
motd=MGX local test server
level-name=world
gamemode=survival
difficulty=normal
allow-flight=true
player-idle-timeout=0
sync-chunk-writes=false
"""

BRIDGE_CONFIG = """\
# Written by scripts/testserver.py.
# The bridge points at a local port that is probably not listening. That is
# fine: the URL only has to parse, or the plugin disables itself on enable.
# BridgeClient just retries in the background and everything that does not need
# Discord — the abuse events, AFK, shop, clans — works regardless.
server-id: "mgx-local-test"
bridge-url: "ws://127.0.0.1:8765/minecraft-bridge"
bridge-secret: "{secret}"
bridge-certificate-sha256: ""
allow-insecure-localhost: true
verification-expiry-seconds: 600
reconnect-max-seconds: 60
afk-timeout-seconds: 60
afk-invincible: true
abuse-radius: 64
auto-update-notice: true
blog-latest-url: "https://mysterioussmpx.blog/latest.json"
blog-poll-minutes: 5
scoreboard:
  footer: "local test"
  update-ticks: 100
leaderboard:
  refresh-ticks: 6000
world:
  max-view-distance: 0
  max-simulation-distance: 0
  border-radius: 5000
debug: true
"""


def log(message: str) -> None:
    print(f"[testserver] {message}", flush=True)


def fetch(url: str, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    log(f"downloading {destination.name}")
    request = urllib.request.Request(url, headers={"User-Agent": "mgx-testserver"})
    with urllib.request.urlopen(request, timeout=180) as response:
        destination.write_bytes(response.read())
    log(f"  {destination.name} ({destination.stat().st_size:,} bytes)")


def read_json(url: str) -> dict:
    request = urllib.request.Request(url, headers={"User-Agent": "mgx-testserver"})
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.loads(response.read())


def _home_of(root: Path) -> Path:
    """The directory holding bin/java.

    macOS JDKs ship as an app bundle, so the real home is nested under
    Contents/Home. Linux-style archives put it at the root. Gradle needs the
    one that actually contains bin/java, not the folder it came in.
    """
    nested = root / "Contents" / "Home"
    return nested if (nested / "bin" / "java").exists() else root


def java_home() -> Path:
    """A JDK that survives a reboot.

    The repo's Temurin copies live under /private/tmp, which macOS clears, so a
    test server that depended on those would break at the worst moment.
    """
    if JDK_HOME.exists():
        return _home_of(JDK_HOME)
    for candidate in sorted(Path("/private/tmp").glob("jdk-21*")):
        home = candidate / "Contents" / "Home"
        if (home / "bin" / "java").exists():
            log(f"copying {candidate.name} to {JDK_HOME} so a reboot cannot remove it")
            shutil.copytree(candidate, JDK_HOME)
            return _home_of(JDK_HOME)
    arch = "aarch64" if platform.machine() == "arm64" else "x64"
    archive = SERVER / "jdk.tar.gz"
    fetch(JDK_URL.format(arch=arch), archive)
    staging = SERVER / "jdk-staging"
    staging.mkdir(parents=True, exist_ok=True)
    with tarfile.open(archive) as tar:
        tar.extractall(staging)
    extracted = next(staging.iterdir())
    shutil.move(str(extracted), str(JDK_HOME))
    archive.unlink()
    shutil.rmtree(staging, ignore_errors=True)
    return _home_of(JDK_HOME)


def java_binary() -> Path:
    return java_home() / "bin" / "java"


def match_production_limits() -> None:
    """Raises the attribute caps in spigot.yml to what production allows.

    A default Paper install caps `attribute.maxHealth.max` at 1024, production
    runs 2048. A boss configured above the cap is silently clamped, so a health
    value that works live would quietly test as something else here.

    spigot.yml is generated by Paper on first boot, so on a brand new server
    this writes only the attribute block and lets Paper merge its own defaults
    in around it.
    """
    spigot = SERVER / "spigot.yml"
    wanted = 2048.0
    if not spigot.exists():
        spigot.write_text(
            "# Partial file: Paper fills in every key it does not find here.\n"
            "settings:\n"
            "  attribute:\n"
            "    maxAbsorption:\n"
            f"      max: {wanted}\n"
            "    maxHealth:\n"
            f"      max: {wanted}\n"
        )
        log("wrote spigot.yml with production's attribute caps")
        return
    text = spigot.read_text()
    patched = re.sub(
        r"(maxHealth:\s*\n\s*max:\s*)([0-9.]+)",
        lambda m: m.group(1) + str(wanted) if float(m.group(2)) < wanted else m.group(0),
        text,
    )
    if patched != text:
        spigot.write_text(patched)
        log(f"raised spigot.yml maxHealth cap to {wanted} to match production")


def config_problem(config: Path) -> str | None:
    """Mirrors BridgeConfig.load, which disables the plugin on a bad value.

    Without this the only symptom is `/mgxadmin` reporting the plugin is
    disabled, twenty minutes after the mistake was made.
    """
    text = config.read_text()

    def value(key: str) -> str:
        # Leading whitespace matters: scoreboard.footer is a nested key.
        found = re.search(rf'^\s*{re.escape(key)}:\s*"?([^"\n]*)"?\s*$', text, re.MULTILINE)
        return found.group(1).strip() if found else ""

    secret = value("bridge-secret")
    if len(secret) != 64 or not all(c in "0123456789abcdefABCDEF" for c in secret):
        return "bridge-secret must be 64 hexadecimal characters"
    url = value("bridge-url")
    if not url.startswith("wss://") and not (
        url.startswith("ws://") and "allow-insecure-localhost: true" in text
    ):
        return "bridge-url must be wss://, or ws:// with allow-insecure-localhost: true"
    server_id = value("server-id")
    if not 1 <= len(server_id) <= 64:
        return "server-id must contain 1-64 characters"
    footer = value("footer")
    if not 1 <= len(footer) <= 32:
        return "scoreboard.footer must contain 1-32 characters"
    return None


def setup(_: argparse.Namespace) -> int:
    SERVER.mkdir(parents=True, exist_ok=True)
    PLUGINS.mkdir(parents=True, exist_ok=True)
    java_binary()

    paper = SERVER / "server.jar"
    if not paper.exists():
        build = read_json(PAPER_API.format(v=PAPER_VERSION, b=PAPER_BUILD))
        fetch(build["downloads"]["server:default"]["url"], paper)

    for project in REQUIRED_PLUGINS:
        jar = PLUGINS / f"{project}.jar"
        if not jar.exists():
            fetch(GEYSER_API.format(p=project), jar)

    luckperms = PLUGINS / "LuckPerms.jar"
    if not luckperms.exists():
        fetch(read_json(LUCKPERMS_META)["downloads"]["bukkit"], luckperms)

    # Geyser refuses to serve Bedrock clients without it on this Paper version,
    # and Bedrock-on-a-phone is the cheapest way to get a second test player.
    via = PLUGINS / "ViaVersion.jar"
    if not via.exists():
        builds = read_json(VIAVERSION_API.format(v=PAPER_VERSION))
        if builds:
            fetch(builds[0]["files"][0]["url"], via)

    properties = SERVER / "server.properties"
    if not properties.exists():
        properties.write_text(SERVER_PROPERTIES)

    match_production_limits()

    config = PLUGINS / "MGXAccessBridge" / "config.yml"
    if not config.exists():
        config.parent.mkdir(parents=True, exist_ok=True)
        # BridgeConfig requires 32 bytes of hex. Nothing listens on the local
        # port, so the value only has to be well-formed, but "well-formed" is
        # exactly the check that rejected a placeholder here.
        config.write_text(BRIDGE_CONFIG.format(secret=secrets.token_hex(32)))
    problem = config_problem(config)
    if problem:
        log(f"WARNING: {config.name} would stop the plugin enabling: {problem}")

    eula = SERVER / "eula.txt"
    if not eula.exists():
        # Not accepted on the user's behalf: it is a licence agreement, and
        # agreeing to one is theirs to do.
        eula.write_text("eula=false\n")

    log(f"ready at {SERVER}")
    if "eula=true" not in eula.read_text():
        log("")
        log("ONE STEP LEFT: read https://aka.ms/MinecraftEULA, then set")
        log(f"  eula=true  in  {eula}")
    return 0


def deploy(_: argparse.Namespace) -> int:
    if not SERVER.exists():
        log("run 'setup' first")
        return 1
    env = dict(os.environ, JAVA_HOME=str(java_home()))
    log("building the plugin")
    result = subprocess.run(
        ["./gradlew", "clean", "shadowJar", "-q"], cwd=BRIDGE, env=env
    )
    if result.returncode != 0:
        log("build failed; nothing installed")
        return result.returncode
    config = PLUGINS / "MGXAccessBridge" / "config.yml"
    if config.exists():
        problem = config_problem(config)
        if problem:
            log(f"WARNING: the plugin will refuse to enable: {problem}")
    built = BRIDGE / "build" / "libs" / "MGXAccessBridge.jar"
    PLUGINS.mkdir(parents=True, exist_ok=True)
    installed = PLUGINS / "MGXAccessBridge.jar"
    shutil.copy2(built, installed)
    with zipfile.ZipFile(installed) as archive:
        descriptor = archive.read("plugin.yml").decode("utf-8")
    match = re.search(r"(?m)^version:\s*[\"']?([^\"'\s]+)", descriptor)
    version = match.group(1) if match else "unknown"
    digest = hashlib.sha256(installed.read_bytes()).hexdigest()
    revision = subprocess.run(
        ["git", "rev-parse", "HEAD"],
        cwd=REPO,
        capture_output=True,
        text=True,
        check=False,
    ).stdout.strip() or "unknown"
    manifest = {
        "commit": revision,
        "version": version,
        "bytes": installed.stat().st_size,
        "sha256": digest,
        "jar": str(installed.relative_to(REPO)),
    }
    TEST_BUILD_MANIFEST.write_text(json.dumps(manifest, indent=2) + "\n")
    log(f"installed MGXAccessBridge {version} into {PLUGINS}")
    log(f"  commit {revision}")
    log(f"  {installed.stat().st_size:,} bytes")
    log(f"  sha256 {digest}")
    log(f"  manifest {TEST_BUILD_MANIFEST}")
    return 0


def start(args: argparse.Namespace) -> int:
    eula = SERVER / "eula.txt"
    if not eula.exists() or "eula=true" not in eula.read_text():
        log("the Minecraft EULA has not been accepted.")
        log(f"read https://aka.ms/MinecraftEULA, then set eula=true in {eula}")
        return 1
    match_production_limits()
    log("starting Paper — join at  localhost  (Java) or  localhost:19132  (Bedrock)")
    log("stop it with the 'stop' console command, or ctrl-c")
    return subprocess.call(
        [
            str(java_binary()),
            f"-Xms{args.memory}",
            f"-Xmx{args.memory}",
            "-jar",
            "server.jar",
            "nogui",
        ],
        cwd=SERVER,
    )


def run(args: argparse.Namespace) -> int:
    code = deploy(args)
    return code if code else start(args)


def reset(_: argparse.Namespace) -> int:
    """Throws away the worlds and plugin data, keeping the downloaded jars."""
    for name in ("world", "world_nether", "world_the_end"):
        shutil.rmtree(SERVER / name, ignore_errors=True)
    shutil.rmtree(PLUGINS / "MGXAccessBridge", ignore_errors=True)
    log("worlds and plugin data cleared; jars kept")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    subcommands = parser.add_subparsers(dest="command", required=True)
    for name, handler, blurb in (
        ("setup", setup, "download Paper and the plugins (run once)"),
        ("deploy", deploy, "build the plugin and install it"),
        ("start", start, "start the server"),
        ("run", run, "deploy, then start"),
        ("reset", reset, "delete worlds and plugin data, keep the jars"),
    ):
        sub = subcommands.add_parser(name, help=blurb)
        sub.set_defaults(handler=handler)
        if name in ("start", "run"):
            sub.add_argument("--memory", default="2G", help="heap size, default 2G")
    args = parser.parse_args()
    return args.handler(args)


if __name__ == "__main__":
    sys.exit(main())
