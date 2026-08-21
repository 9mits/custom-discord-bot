"""Regenerate the guide and rules pages from the bot's own Discord embeds.

The information panel in Discord and these pages must say the same thing, and
keeping two copies of ~900 lines in step by hand is how they drift. So the site
generates them instead:

    python devblog/sync_from_bot.py

Writes `pages/guide.md` and `pages/rules.md`. Both are committed, so the site
build itself never imports discord.py — only this script does, and only when a
human runs it after the bot's copy changes.

`pages/apply.md` is hand-written and is not touched here.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
REPO = ROOT.parent
sys.path.insert(0, str(REPO))

try:
    from minecraft_bot import information as info
    from minecraft_bot import presentation as pres
    from minecraft_bot.perks import LEVEL_ROLE_MILESTONES, RANK_ROLES
except ModuleNotFoundError as exc:  # pragma: no cover - guidance beats a traceback
    sys.exit(
        "Could not import the bot package (%s).\n"
        "Run this from the repo root with the bot's dependencies installed:\n"
        "    pip install -r requirements.txt" % exc
    )

# Discord renders <@&id> as a role chip. The web cannot, so every id the copy
# mentions has to resolve to a name here or the sync fails rather than shipping
# a raw snowflake.
ROLE_LABELS = {}
for _role_id, _level in LEVEL_ROLE_MILESTONES:
    ROLE_LABELS[int(_role_id)] = "Level %d" % _level
for _entry in RANK_ROLES:
    ROLE_LABELS[int(_entry[0])] = str(_entry[2]).title()

GUIDE_PAGES = ("commands", "clans", "levels", "boosting", "mods", "versions")

#: Web-specific rewording. The bot falls back to "ask staff for the address"
#: when it has no settings, which is the right answer inside Discord but not on
#: a public page — there the answer is that applying is how you get it. The
#: address itself is private and is never published here.
WEB_REWRITES = (
    ("ask staff for the address", "given to you when your application is accepted"),
)


def discord_to_markdown(text: str) -> str:
    """Undo the Discord-only parts of an embed's copy."""
    if not text:
        return ""
    lines = []
    for line in text.split("\n"):
        # Embeds quote nearly everything for the tight left rule Discord draws.
        # On a page that rule means "blockquote", which is not what is meant.
        lines.append(re.sub(r"^>\s?", "", line))
    out = "\n".join(lines)

    def role(match: "re.Match[str]") -> str:
        rid = int(match.group(1))
        if rid not in ROLE_LABELS:
            raise SystemExit(
                "Unknown role id %d in the bot copy. Add it to ROLE_LABELS in "
                "devblog/sync_from_bot.py so the page does not ship a raw id." % rid
            )
        return "**%s**" % ROLE_LABELS[rid]

    out = re.sub(r"<@&(\d+)>", role, out)
    for before, after in WEB_REWRITES:
        out = out.replace(before, after)
    leftover = re.search(r"<[@#][!&]?\d+>", out)
    if leftover:
        raise SystemExit("Unresolved Discord mention %r in the bot copy." % leftover.group(0))
    return out.strip()


def embed_to_markdown(embed, level: int = 2) -> str:
    """One embed becomes a heading, its intro, and a sub-heading per field."""
    parts = []
    if embed.title:
        parts.append("%s %s" % ("#" * level, embed.title))
    description = discord_to_markdown(embed.description or "")
    if description:
        parts.append(description)
    for field in embed.fields:
        parts.append("%s %s" % ("#" * (level + 1), field.name))
        value = discord_to_markdown(field.value or "")
        if value:
            parts.append(value)
    return "\n\n".join(parts)


def front_matter(**keys: str) -> str:
    return "---\n%s\n---" % "\n".join("%s: %s" % (k, v) for k, v in keys.items())


def build_guide() -> str:
    blocks = [
        front_matter(
            title="Server Guide",
            nav="Guide",
            order="1",
            tagline="Everything the in-game information panel covers — commands, "
                    "clans, levels, perks, mods and version support.",
        ),
        "This page mirrors the **information panel** in Discord. It is generated "
        "from the bot's own copy, so the two never drift.",
    ]
    for key in GUIDE_PAGES:
        label, factory = info.PAGES[key]
        blocks.append(embed_to_markdown(factory(None), level=2))
        for _section_key, (_section_label, section_factory) in info.SECTIONS.get(key, {}).items():
            blocks.append(embed_to_markdown(section_factory(None), level=3))
    return "\n\n".join(blocks).rstrip() + "\n"


def build_rules() -> str:
    blocks = [
        front_matter(
            title="Server Rules",
            nav="Rules",
            order="2",
            tagline="These rules apply to every Mysterious SMP X player. Read them "
                    "before you apply — accepting them is part of the application.",
        ),
    ]
    for heading, body in pres.SERVER_RULES:
        # The rule number already leads the heading in the bot's copy.
        blocks.append("## %s" % heading)
        blocks.append(discord_to_markdown(body))
    blocks.append("## Enforcement")
    blocks.append(discord_to_markdown(pres.ENFORCEMENT_NOTE))
    return "\n\n".join(blocks).rstrip() + "\n"


def main() -> int:
    targets = {
        ROOT / "pages" / "guide.md": build_guide(),
        ROOT / "pages" / "rules.md": build_rules(),
    }
    for path, text in targets.items():
        path.parent.mkdir(parents=True, exist_ok=True)
        changed = not path.exists() or path.read_text(encoding="utf-8") != text
        path.write_text(text, encoding="utf-8")
        print("%s %s (%d lines)" % ("updated" if changed else "unchanged",
                                    path.relative_to(REPO), text.count("\n")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
