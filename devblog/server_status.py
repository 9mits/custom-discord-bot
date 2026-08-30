"""Query the Minecraft server and write the numbers the site displays.

Run by CI immediately before the build, with the address supplied through
encrypted repository secrets:

    MC_SERVER_HOST=... MC_SERVER_PORT=... python devblog/server_status.py

The query endpoint reaches this script through the environment. `stats.json`
holds counts and a timestamp only; the public player-facing address is owned by
`config.SERVER_ADDRESS` instead of being copied out of CI configuration.

Speaks the Server List Ping protocol directly over a socket, so there is no
dependency beyond the standard library and no third-party service sees the
address either.
"""

from __future__ import annotations

import argparse
import json
import os
import socket
import struct
import sys
from datetime import datetime, timezone
from pathlib import Path

# Any recent protocol number works: the server answers a status request from a
# client it does not recognise, which is the whole point of the handshake's
# "next state 1".
PROTOCOL_VERSION = 767
TIMEOUT_SECONDS = 8


class QueryError(Exception):
    """The server could not be reached or did not answer sensibly."""


def _varint(value: int) -> bytes:
    out = b""
    while True:
        byte = value & 0x7F
        value >>= 7
        out += struct.pack("B", byte | (0x80 if value else 0))
        if not value:
            return out


def _read_varint(sock: socket.socket) -> int:
    number = shift = 0
    for _ in range(5):
        chunk = sock.recv(1)
        if not chunk:
            raise QueryError("connection closed while reading a length")
        byte = chunk[0]
        number |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return number
        shift += 7
    raise QueryError("malformed varint")


def _read_exactly(sock: socket.socket, count: int) -> bytes:
    buf = b""
    while len(buf) < count:
        chunk = sock.recv(count - len(buf))
        if not chunk:
            raise QueryError("connection closed after %d of %d bytes" % (len(buf), count))
        buf += chunk
    return buf


def query(host: str, port: int, timeout: float = TIMEOUT_SECONDS) -> dict:
    """Server List Ping. Returns the server's raw status document."""
    try:
        sock = socket.create_connection((host, port), timeout=timeout)
    except OSError as exc:
        raise QueryError("could not connect: %s" % exc)
    with sock:
        sock.settimeout(timeout)
        address = host.encode("utf-8")
        handshake = (
            b"\x00"
            + _varint(PROTOCOL_VERSION)
            + _varint(len(address))
            + address
            + struct.pack(">H", port)
            + _varint(1)
        )
        try:
            sock.sendall(_varint(len(handshake)) + handshake)
            sock.sendall(_varint(1) + b"\x00")
            _read_varint(sock)  # packet length
            _read_varint(sock)  # packet id
            payload = _read_exactly(sock, _read_varint(sock))
        except (OSError, QueryError) as exc:
            raise QueryError("status request failed: %s" % exc)
    try:
        return json.loads(payload.decode("utf-8"))
    except (UnicodeDecodeError, ValueError) as exc:
        raise QueryError("could not read the status document: %s" % exc)


def _version_number(raw: str) -> str:
    """`Paper 1.21.11` is server software; players care about `1.21.11`."""
    for word in str(raw).split():
        if word[:1].isdigit():
            return word
    return str(raw).strip()


def stats_from(status: dict) -> dict:
    """Only the numbers. Deliberately no address, no player names, no MOTD."""
    players = status.get("players") or {}
    version = status.get("version") or {}
    online = players.get("online")
    maximum = players.get("max")
    if not isinstance(online, int) or not isinstance(maximum, int):
        raise QueryError("the server did not report a player count")
    return {
        "online": online,
        "max": maximum,
        "version": _version_number(version.get("name") or ""),
        "checked_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--out",
        default=str(Path(__file__).resolve().parent / "data" / "stats.json"),
        help="Where to write the stats document.",
    )
    args = parser.parse_args()

    host = os.environ.get("MC_SERVER_HOST", "").strip()
    port_raw = os.environ.get("MC_SERVER_PORT", "").strip()
    if not host or not port_raw:
        # Not an error: a local build without the secrets simply has no stats,
        # and the site renders without the panel rather than inventing numbers.
        print("MC_SERVER_HOST/MC_SERVER_PORT not set - skipping, no stats written")
        return 0
    try:
        port = int(port_raw)
    except ValueError:
        print("MC_SERVER_PORT is not a number - skipping", file=sys.stderr)
        return 0

    try:
        stats = stats_from(query(host, port))
    except QueryError as exc:
        # A failure here is ambiguous — the server may be down, or the runner may
        # simply not be able to reach it. Neither justifies telling visitors the
        # server is offline, so write nothing and let the panel disappear.
        print("could not query the server (%s) - no stats written" % exc, file=sys.stderr)
        return 0

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(stats, indent=2) + "\n", encoding="utf-8")
    print("wrote %s: %d/%d online, version %s" % (out, stats["online"], stats["max"], stats["version"]))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
