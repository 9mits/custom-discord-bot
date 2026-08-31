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
RELAY_PREFIX = "https://r.jina.ai/http://"

#: Keys copied out of the response. An allowlist rather than a filter, so a future
#: field added to the API cannot reach a public page by simply existing.
GROUPS = ("individual", "clan")
BATTLE_FIELDS = ("id", "kind", "name", "objective", "started_at", "ends_at")
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
    battle = snapshot.get("clan_battle")
    if isinstance(battle, dict):
        cleaned_battle = {key: battle[key] for key in BATTLE_FIELDS if key in battle}
        if cleaned_battle:
            out["clan_battle"] = cleaned_battle
    return {k: v for k, v in out.items() if v}


def fetch(base_url: str) -> dict:
    url = base_url.rstrip("/") + "/api/leaderboards"
    request = urllib.request.Request(url, headers={"User-Agent": "mgx-devblog-build"})
    with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response:
        return json.loads(response.read().decode("utf-8"))


def relay_url(base_url: str) -> str:
    """Route the public HTTP endpoint through standard HTTPS for hosted CI.

    Some hosting networks reject GitHub runner traffic on the dashboard's custom
    port. The relay sees only the same unauthenticated standings endpoint that is
    already public; the sanitizer below still decides what reaches the site.
    """
    if not base_url.casefold().startswith("http://"):
        raise ValueError("The HTTPS relay is only for a public HTTP dashboard")
    return RELAY_PREFIX + base_url[7:].rstrip("/") + "/api/leaderboards"


def fetch_via_relay(base_url: str) -> dict:
    request = urllib.request.Request(
        relay_url(base_url), headers={"User-Agent": "mgx-devblog-build"}
    )
    with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response:
        body = response.read().decode("utf-8")
    marker = "Markdown Content:\n"
    if marker not in body:
        raise ValueError("The leaderboard relay returned an unexpected document")
    return json.loads(body.split(marker, 1)[1])


def main() -> int:
    base = os.environ.get("MGX_PANEL_URL", "").strip()
    if not base:
        # Not configured is not a failure: the page falls back to its empty state.
        print("MGX_PANEL_URL is not set; no leaderboard snapshot written.")
        return 0
    try:
        raw = fetch(base)
    except (urllib.error.URLError, OSError, ValueError, TimeoutError):
        try:
            raw = fetch_via_relay(base)
            print("Fetched standings through the HTTPS relay.")
        except (urllib.error.URLError, OSError, ValueError, TimeoutError) as exc:
            # Never fail the build over standings. The site is worth more than the board.
            print(f"Could not reach the dashboard ({type(exc).__name__}); "
                  "no leaderboard snapshot written.", file=sys.stderr)
            return 0
    snapshot = clean(raw)
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
    total = sum(
        len(rows)
        for group in GROUPS
        for rows in snapshot.get(group, {}).values()
    )
    board_count = sum(len(snapshot.get(group, {})) for group in GROUPS)
    print(f"Wrote {OUTPUT.name}: "
          f"{board_count} board(s), {total} row(s).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
