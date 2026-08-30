"""Fetch the published standings and write the document the site renders.

Run by CI immediately before the build, with the dashboard's address supplied
through an encrypted repository secret:

    MGX_PANEL_URL=http://host:port python devblog/leaderboard_snapshot.py

**The address is private and never leaves this script.** The repository is
public, so the panel's host must not be written into any tracked file or into
the output. `leaderboards.json` holds standings and a timestamp and nothing
else; a test asserts that.

Why a build-time snapshot rather than the page calling the API itself: the
published site is HTTPS and the dashboard is plain HTTP, so a browser refuses
the request as mixed content, and it would need CORS besides. A CI runner has
neither restriction, so it fetches once and publishes the result beside the
page, which is the same shape `server_status.py` already uses for the player
counts on the home page.

Only the endpoint that is already public is read. Settings, exact loot weights
and audit logs live behind the owner-gated half of the API and are never
requested here.
"""

from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

DATA_DIR = Path(__file__).resolve().parent / "data"
OUTPUT = DATA_DIR / "leaderboards.json"
TIMEOUT_SECONDS = 20

#: Keys copied out of the response. An allowlist rather than a filter, so a future
#: field added to the API cannot reach a public page by simply existing.
GROUPS = ("individual", "clan")
ROW_FIELDS = (
    "username", "name", "clan", "display", "value", "score", "rank", "minecraft_uuid",
    "head_url", "skin_url", "discord_username", "clan_id", "colour",
    "members", "level", "medals", "badges", "icon", "clan_icon",
)


def clean_rows(rows: object) -> list[dict]:
    if not isinstance(rows, list):
        return []
    cleaned = []
    for row in rows:
        if not isinstance(row, dict):
            continue
        cleaned.append({k: row[k] for k in ROW_FIELDS if k in row})
    return cleaned


def clean(snapshot: object) -> dict:
    """Keeps only the standings. Anything else the endpoint grows stays out."""
    if not isinstance(snapshot, dict):
        return {}
    out: dict = {}
    for group in GROUPS:
        boards = snapshot.get(group)
        if not isinstance(boards, dict):
            continue
        out[group] = {
            str(key): clean_rows(value)
            for key, value in boards.items()
            if clean_rows(value)
        }
    return {k: v for k, v in out.items() if v}


def fetch(base_url: str) -> dict:
    url = base_url.rstrip("/") + "/api/leaderboards"
    request = urllib.request.Request(url, headers={"User-Agent": "mgx-devblog-build"})
    with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response:
        return json.loads(response.read().decode("utf-8"))


def main() -> int:
    base = os.environ.get("MGX_PANEL_URL", "").strip()
    if not base:
        # Not configured is not a failure: the page falls back to its empty state.
        print("MGX_PANEL_URL is not set; no leaderboard snapshot written.")
        return 0
    try:
        snapshot = clean(fetch(base))
    except (urllib.error.URLError, OSError, ValueError, TimeoutError) as exc:
        # Never fail the build over standings. The site is worth more than the board.
        print(f"Could not reach the dashboard ({type(exc).__name__}); "
              "no leaderboard snapshot written.", file=sys.stderr)
        return 0
    if not snapshot:
        print("The dashboard returned no standings; no snapshot written.")
        return 0

    DATA_DIR.mkdir(parents=True, exist_ok=True)
    document = {
        "boards": snapshot,
        "checked_at": datetime.now(timezone.utc).replace(microsecond=0).isoformat(),
        "version": 1,
    }
    OUTPUT.write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")
    total = sum(len(rows) for boards in snapshot.values() for rows in boards.values())
    print(f"Wrote {OUTPUT.name}: "
          f"{sum(len(b) for b in snapshot.values())} board(s), {total} row(s).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
