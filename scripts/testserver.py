#!/usr/bin/env python3
"""Local Paper server for trying plugin changes before they reach players.

Production is GravelHost, which has no API and only an SFTP deploy, so the only
way to see a `minecraft-bridge/` change in game used to be to ship it to the
live server. This runs the same Paper build locally instead.

    python scripts/testserver.py setup    # once: fetch Paper and the production plugins
    python scripts/testserver.py deploy   # build and install without starting
    python scripts/testserver.py run      # build, install, start
    python scripts/testserver.py restart  # build, install, gracefully restart

The server lives in `runtime/testserver/`, which is git-ignored, so nothing here
can reach a commit. It is deliberately NOT a copy of production: offline mode and
no whitelist, so alt accounts can join to test the multiplayer events. Both packs
are built locally: Java receives its ZIP from a loopback-only HTTP server and the
Bedrock pack is installed directly into Geyser. Neither path depends on GitHub.

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
import signal
import subprocess
import sys
import tarfile
import time
import urllib.error
import urllib.request
import uuid
import zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
SERVER = REPO / "runtime" / "testserver"
PLUGINS = SERVER / "plugins"
BRIDGE = REPO / "minecraft-bridge"
JAVA_RESOURCES = REPO / "assets" / "resourcepack"
JAVA_BUILD = JAVA_RESOURCES / "build_pack.py"
JAVA_PACK = JAVA_RESOURCES / "MysteriousSMPX.zip"
BEDROCK_RESOURCES = REPO / "assets" / "resourcepack" / "bedrock"
BEDROCK_BUILD = BEDROCK_RESOURCES / "build_pack.py"
BEDROCK_PACK = BEDROCK_RESOURCES / "MysteriousSMPX-Bedrock.mcpack"
BEDROCK_MAPPINGS = BEDROCK_RESOURCES / "mgx_items.json"
TEST_BUILD_MANIFEST = SERVER / "test-build.json"
SERVER_PID = SERVER / "server.pid"
PACK_SERVER_PID = SERVER / "resource-pack-server.pid"
PACK_SERVER_PORT = 8768
PACK_SERVER_DIR = SERVER / "resourcepacks"
INSTALLED_JAVA_PACK = PACK_SERVER_DIR / JAVA_PACK.name

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
GRIM_URL = (
    "https://cdn.modrinth.com/data/LJNGWSvH/versions/GPdAbB8o/"
    "grimac-bukkit-2.3.74-5920e74.jar"
)
GRIM_SHA256 = "20f39cacc5cdbc8b7aff2df6e352174505ea77f5109c02b01dd369f9fe0aecca"
GRIM_PRINTER_PLACE_CHECKS = (
    "AirLiquidPlace",
    "FabricatedPlace",
    "FarPlace",
    "PositionPlace",
    "RotationPlace",
    "DuplicateRotPlace",
    "MultiPlace",
)
GRIM_PRINTER_PACKET_CHECKS = ("PacketOrderE",)
GRIM_PRINTER_CHECKS = GRIM_PRINTER_PLACE_CHECKS + GRIM_PRINTER_PACKET_CHECKS

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
require-resource-pack=true
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
verification-required: false
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


def local_bridge_config(text: str, *, verification_required: bool = False) -> str:
    """Sets the local-only verification mode without weakening production."""
    setting = f"verification-required: {str(verification_required).lower()}"
    pattern = re.compile(r"(?m)^verification-required\s*:\s*.*$")
    if pattern.search(text):
        return pattern.sub(setting, text)
    marker = "allow-insecure-localhost: true\n"
    if marker in text:
        return text.replace(marker, marker + setting + "\n", 1)
    suffix = "" if text.endswith("\n") else "\n"
    return text + suffix + setting + "\n"


def configure_local_bridge(config: Path, *, verification_required: bool = False) -> None:
    original = config.read_text()
    patched = local_bridge_config(
        original,
        verification_required=verification_required,
    )
    if patched != original:
        config.write_text(patched)
        state = "enabled" if verification_required else "disabled"
        log(f"{state} account verification on the local test server")


def fetch(url: str, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    log(f"downloading {destination.name}")
    request = urllib.request.Request(url, headers={"User-Agent": "mgx-testserver"})
    with urllib.request.urlopen(request, timeout=180) as response:
        destination.write_bytes(response.read())
    log(f"  {destination.name} ({destination.stat().st_size:,} bytes)")


def file_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def file_sha1(path: Path) -> str:
    return hashlib.sha1(path.read_bytes()).hexdigest()


def java_pack_properties(text: str, sha1: str) -> tuple[str, str]:
    """Point Paper at this exact local pack and invalidate old client caches."""
    pack_id = str(uuid.uuid5(uuid.NAMESPACE_URL, f"mgx-test-resource-pack:{sha1}"))
    updates = {
        "require-resource-pack": "true",
        "resource-pack": (
            f"http://127.0.0.1:{PACK_SERVER_PORT}/{JAVA_PACK.name}?sha1={sha1}"
        ),
        "resource-pack-sha1": sha1,
        "resource-pack-id": pack_id,
    }
    lines = text.splitlines()
    found: set[str] = set()
    for index, line in enumerate(lines):
        key = line.split("=", 1)[0] if "=" in line and not line.startswith("#") else ""
        if key in updates:
            lines[index] = f"{key}={updates[key]}"
            found.add(key)
    lines.extend(f"{key}={value}" for key, value in updates.items() if key not in found)
    return "\n".join(lines) + "\n", pack_id


def configure_java_resource_pack(pack: Path) -> tuple[str, str]:
    """Install the Java ZIP and make server.properties describe its exact bytes."""
    PACK_SERVER_DIR.mkdir(parents=True, exist_ok=True)
    shutil.copy2(pack, INSTALLED_JAVA_PACK)
    sha1 = file_sha1(INSTALLED_JAVA_PACK)
    properties = SERVER / "server.properties"
    original = properties.read_text() if properties.exists() else SERVER_PROPERTIES
    patched, pack_id = java_pack_properties(original, sha1)
    if patched != original:
        properties.write_text(patched)
        log("updated the Java resource-pack hash and cache identity")
    return sha1, pack_id


def pack_server_serves(pack: Path) -> bool:
    """Confirm the loopback server returns the newly installed bytes."""
    if not pack.is_file():
        return False
    wanted = file_sha1(pack)
    url = f"http://127.0.0.1:{PACK_SERVER_PORT}/{pack.name}?probe={wanted}"
    try:
        with urllib.request.urlopen(url, timeout=2) as response:
            return hashlib.sha1(response.read()).hexdigest() == wanted
    except (OSError, urllib.error.URLError):
        return False


def ensure_pack_server() -> None:
    """Keep one detached, loopback-only server available across Paper restarts."""
    if pack_server_serves(INSTALLED_JAVA_PACK):
        return
    process = subprocess.Popen(
        [
            sys.executable,
            "-m",
            "http.server",
            str(PACK_SERVER_PORT),
            "--bind",
            "127.0.0.1",
            "--directory",
            str(PACK_SERVER_DIR),
        ],
        cwd=SERVER,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        start_new_session=True,
    )
    PACK_SERVER_PID.write_text(f"{process.pid}\n")
    deadline = time.monotonic() + 5
    while time.monotonic() < deadline:
        if pack_server_serves(INSTALLED_JAVA_PACK):
            log(f"serving the Java resource pack on 127.0.0.1:{PACK_SERVER_PORT}")
            return
        if process.poll() is not None:
            break
        time.sleep(0.1)
    raise RuntimeError(
        f"could not serve {INSTALLED_JAVA_PACK} on 127.0.0.1:{PACK_SERVER_PORT}"
    )


def fetch_verified(url: str, destination: Path, expected_sha256: str) -> None:
    if destination.is_file() and file_sha256(destination) == expected_sha256:
        return
    temporary = destination.with_suffix(destination.suffix + ".downloading")
    try:
        fetch(url, temporary)
        actual = file_sha256(temporary)
        if actual != expected_sha256:
            raise RuntimeError(
                f"{destination.name} sha256 was {actual}, expected {expected_sha256}"
            )
        temporary.replace(destination)
    finally:
        temporary.unlink(missing_ok=True)


def grim_printer_config(text: str) -> str:
    """Prevent only known Printer false positives from undoing placements."""
    text = text.replace(
        "    # one is stood down. FarPlace still catches reach and FabricatedPlace still\n"
        "    # catches invented cursor packets — those two stay armed.",
        "    # one is stood down. The InvalidPlace checks still reject malformed\n"
        "    # placement packets.",
    )
    text = text.replace(
        "    # one is stood down. FarPlace still catches impossible reach, while the\n"
        "    # InvalidPlace checks still reject malformed placement packets.",
        "    # one is stood down. The InvalidPlace checks still reject malformed\n"
        "    # placement packets.",
    )
    lines = text.splitlines()
    for section in GRIM_PRINTER_CHECKS:
        settings = (
            ("cancelvl", "setbackvl")
            if section in GRIM_PRINTER_PLACE_CHECKS
            else ("setbackvl",)
        )
        header = f"{section}:"
        try:
            start = next(index for index, line in enumerate(lines) if line.strip() == header)
        except StopIteration:
            if lines and lines[-1].strip():
                lines.append("")
            lines.extend((header, *(f"    {setting}: -1" for setting in settings)))
            continue
        end = len(lines)
        for index in range(start + 1, len(lines)):
            line = lines[index]
            if line and not line[0].isspace() and not line.startswith("#"):
                end = index
                break
        existing_settings = [
            index
            for index in range(start + 1, end)
            if lines[index].strip().lower().startswith(
                tuple(f"{setting}:" for setting in settings)
            )
        ]
        for setting in reversed(existing_settings):
            del lines[setting]
        lines[start + 1:start + 1] = tuple(
            f"    {setting}: -1" for setting in settings
        )
    return "\n".join(lines) + "\n"


def grim_printer_punishments(text: str) -> str:
    """Log Printer patterns without feeding them into Grim's kick group."""
    text = text.replace(
        "      # four by design, so they must not count towards the 60-in-5-minutes kick\n"
        "      # below — a long print run reaches 60 flags in under a minute. FarPlace,\n"
        "      # FabricatedPlace, MultiPlace and InvalidPlaceB still alert and still punish.",
        "      # eight by design, so they must not count towards broad punishment groups\n"
        "      # below. They are logged by LitematicaPrinter without undoing blocks.\n"
        "      # InvalidPlaceB remains fully enforced.",
    )
    text = text.replace(
        "      # six by design, so they must not count towards the 60-in-5-minutes kick",
        "      # eight by design, so they must not count towards broad punishment groups",
    )
    text = text.replace(
        "      # seven by design, so they must not count towards the 60-in-5-minutes kick",
        "      # eight by design, so they must not count towards broad punishment groups",
    )
    text = text.replace(
        "      # FarPlace and InvalidPlaceB remain fully enforced.",
        "      # InvalidPlaceB remains fully enforced.",
    )
    lines = text.splitlines()
    exclusions = {
        line.strip()[4:-1]
        for line in lines
        if line.strip().startswith('- "!') and line.strip().endswith('"')
    }
    missing = [
        check for check in GRIM_PRINTER_PLACE_CHECKS if check not in exclusions
    ]
    if missing:
        place = next(
            index for index, line in enumerate(lines) if line.strip() == '- "Place"'
        )
        lines[place + 1:place + 1] = [f'      - "!{check}"' for check in missing]
    if "PacketOrderE" not in exclusions:
        packet_order = next(
            (
                index
                for index, line in enumerate(lines)
                if line.strip() == '- "PacketOrder"'
            ),
            None,
        )
        if packet_order is not None:
            lines.insert(packet_order + 1, '      - "!PacketOrderE"')
    printer = next(
        (index for index, line in enumerate(lines) if line.strip() == "LitematicaPrinter:"),
        None,
    )
    if printer is None:
        group = [
            "  LitematicaPrinter:",
            "    remove-violations-after: 300",
            "    checks:",
            *(f'      - "{check}"' for check in GRIM_PRINTER_CHECKS),
            "    commands:",
            '      - "5:5 [alert]"',
            '      - "1:1 [log]"',
        ]
        combat = next(
            (index for index, line in enumerate(lines) if line.strip() == "Combat:"),
            len(lines),
        )
        lines[combat:combat] = group
    else:
        end = next(
            (
                index
                for index in range(printer + 1, len(lines))
                if lines[index].startswith("  ")
                and not lines[index].startswith("    ")
                and lines[index].strip().endswith(":")
            ),
            len(lines),
        )
        commands = next(
            index
            for index in range(printer + 1, end)
            if lines[index].strip() == "commands:"
        )
        existing = {
            line.strip()[3:-1]
            for line in lines[printer + 1:commands]
            if line.strip().startswith('- "') and line.strip().endswith('"')
        }
        missing = [check for check in GRIM_PRINTER_CHECKS if check not in existing]
        lines[commands:commands] = [f'      - "{check}"' for check in missing]
    return "\n".join(lines) + "\n"


def configure_grim() -> None:
    jar = PLUGINS / "GrimAC.jar"
    if not jar.is_file():
        return
    folder = PLUGINS / "GrimAC"
    folder.mkdir(parents=True, exist_ok=True)
    defaults = {
        "config.yml": "config/en.yml",
        "punishments.yml": "punishments/en.yml",
    }
    with zipfile.ZipFile(jar) as archive:
        for filename, member in defaults.items():
            destination = folder / filename
            if not destination.exists():
                destination.write_bytes(archive.read(member))
    config = folder / "config.yml"
    punishments = folder / "punishments.yml"
    patched_config = grim_printer_config(config.read_text())
    patched_punishments = grim_printer_punishments(punishments.read_text())
    if patched_config != config.read_text():
        config.write_text(patched_config)
        log("configured Grim placement checks for Litematica Printer")
    if patched_punishments != punishments.read_text():
        punishments.write_text(patched_punishments)
        log("kept Printer placement checks logged but outside Grim's kick group")


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

    fetch_verified(GRIM_URL, PLUGINS / "GrimAC.jar", GRIM_SHA256)
    configure_grim()

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
    configure_local_bridge(config)
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
    log("building the Java resource pack")
    result = subprocess.run([sys.executable, str(JAVA_BUILD)], cwd=JAVA_RESOURCES)
    if result.returncode != 0:
        log("Java resource build failed; nothing installed")
        return result.returncode
    log("building the Bedrock resource pack and Geyser mappings")
    result = subprocess.run([sys.executable, str(BEDROCK_BUILD)], cwd=BEDROCK_RESOURCES)
    if result.returncode != 0:
        log("Bedrock resource build failed; nothing installed")
        return result.returncode
    config = PLUGINS / "MGXAccessBridge" / "config.yml"
    if config.exists():
        configure_local_bridge(config)
        problem = config_problem(config)
        if problem:
            log(f"WARNING: the plugin will refuse to enable: {problem}")
    built = BRIDGE / "build" / "libs" / "MGXAccessBridge.jar"
    PLUGINS.mkdir(parents=True, exist_ok=True)
    installed = PLUGINS / "MGXAccessBridge.jar"
    shutil.copy2(built, installed)
    geyser = PLUGINS / "Geyser-Spigot"
    installed_pack = geyser / "packs" / BEDROCK_PACK.name
    installed_mappings = geyser / "custom_mappings" / BEDROCK_MAPPINGS.name
    installed_pack.parent.mkdir(parents=True, exist_ok=True)
    installed_mappings.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(BEDROCK_PACK, installed_pack)
    shutil.copy2(BEDROCK_MAPPINGS, installed_mappings)
    java_sha1, java_pack_id = configure_java_resource_pack(JAVA_PACK)
    ensure_pack_server()
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
        "java_pack": {
            "path": str(INSTALLED_JAVA_PACK.relative_to(REPO)),
            "bytes": INSTALLED_JAVA_PACK.stat().st_size,
            "sha1": java_sha1,
            "sha256": file_sha256(INSTALLED_JAVA_PACK),
            "id": java_pack_id,
        },
        "bedrock_pack": {
            "path": str(installed_pack.relative_to(REPO)),
            "bytes": installed_pack.stat().st_size,
            "sha256": file_sha256(installed_pack),
        },
        "geyser_mappings": {
            "path": str(installed_mappings.relative_to(REPO)),
            "bytes": installed_mappings.stat().st_size,
            "sha256": file_sha256(installed_mappings),
        },
    }
    TEST_BUILD_MANIFEST.write_text(json.dumps(manifest, indent=2) + "\n")
    log(f"installed MGXAccessBridge {version} into {PLUGINS}")
    log(f"  commit {revision}")
    log(f"  {installed.stat().st_size:,} bytes")
    log(f"  sha256 {digest}")
    log(f"installed {INSTALLED_JAVA_PACK.name} for Java clients")
    log(f"  sha1 {java_sha1}")
    log(f"  sha256 {file_sha256(INSTALLED_JAVA_PACK)}")
    log(f"  resource-pack-id {java_pack_id}")
    log(f"installed {installed_pack.name} for Geyser Bedrock clients")
    log(f"  sha256 {file_sha256(installed_pack)}")
    log(f"installed {installed_mappings.name} custom-item mappings")
    log(f"  sha256 {file_sha256(installed_mappings)}")
    log(f"  manifest {TEST_BUILD_MANIFEST}")
    return 0


def start(args: argparse.Namespace) -> int:
    eula = SERVER / "eula.txt"
    if not eula.exists() or "eula=true" not in eula.read_text():
        log("the Minecraft EULA has not been accepted.")
        log(f"read https://aka.ms/MinecraftEULA, then set eula=true in {eula}")
        return 1
    config = PLUGINS / "MGXAccessBridge" / "config.yml"
    if config.exists():
        configure_local_bridge(
            config,
            verification_required=bool(getattr(args, "verification", False)),
        )
    match_production_limits()
    configure_grim()
    running = running_server_pid()
    if running is not None:
        log(f"Paper is already running as pid {running}; use 'restart'")
        return 1
    log("starting Paper — join at  localhost  (Java) or  localhost:19132  (Bedrock)")
    log("stop it with the 'stop' console command, or ctrl-c")
    process = subprocess.Popen(
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
    SERVER_PID.write_text(f"{process.pid}\n")
    try:
        return process.wait()
    finally:
        try:
            if SERVER_PID.read_text().strip() == str(process.pid):
                SERVER_PID.unlink()
        except FileNotFoundError:
            pass


def running_server_pid() -> int | None:
    """Return the managed Paper pid, discarding stale or unrelated pid files."""
    try:
        pid = int(SERVER_PID.read_text().strip())
    except (FileNotFoundError, ValueError):
        return None
    if pid <= 0:
        SERVER_PID.unlink(missing_ok=True)
        return None
    result = subprocess.run(
        ["ps", "-p", str(pid), "-o", "command="],
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode != 0 or "server.jar" not in result.stdout:
        SERVER_PID.unlink(missing_ok=True)
        return None
    return pid


def stop_server(pid: int, timeout: float = 90.0) -> bool:
    """Ask Paper to stop gracefully and wait for its JVM to exit."""
    log(f"stopping Paper pid {pid} gracefully")
    try:
        os.kill(pid, signal.SIGTERM)
    except ProcessLookupError:
        SERVER_PID.unlink(missing_ok=True)
        return True
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if running_server_pid() is None:
            log("Paper stopped")
            return True
        time.sleep(0.25)
    log(f"Paper did not stop within {timeout:.0f}s; leaving it running")
    return False


def run(args: argparse.Namespace) -> int:
    code = deploy(args)
    return code if code else start(args)


def restart(args: argparse.Namespace) -> int:
    """Deploy first, then gracefully replace the currently running Paper process."""
    code = deploy(args)
    if code:
        return code
    running = running_server_pid()
    if running is not None and not stop_server(running):
        return 1
    return start(args)


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
        ("restart", restart, "deploy, then gracefully restart the server"),
        ("reset", reset, "delete worlds and plugin data, keep the jars"),
    ):
        sub = subcommands.add_parser(name, help=blurb)
        sub.set_defaults(handler=handler)
        if name in ("start", "run", "restart"):
            sub.add_argument("--memory", default="2G", help="heap size, default 2G")
            sub.add_argument(
                "--verification",
                action="store_true",
                help="temporarily require Discord verification on this local run",
            )
    args = parser.parse_args()
    return args.handler(args)


if __name__ == "__main__":
    sys.exit(main())
