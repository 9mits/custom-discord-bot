"""Update notices built from a published dev-blog post.

Writing the same announcement twice is how the Discord copy and the blog end up
disagreeing about what shipped. So a notice is not composed by hand: it is derived
from the post itself. The title, the hype line and the list of beats all come out
of `devblog/posts/`, which means an announcement can only ever describe an update
that is actually written up.

A draft post can be previewed but never broadcast. The notice's whole purpose is
to hand people a link, and a draft is not on the site yet — a hundred DMs pointing
at a 404 is worse than no announcement at all.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Optional

import discord

#: The blog lives beside the bot in the same checkout.
REPO_ROOT = Path(__file__).resolve().parent.parent
POSTS_DIR = REPO_ROOT / "devblog" / "posts"

SITE_URL = "https://mysterioussmpx.blog"

#: Only these get announced. An event post is its own thing and a page is not news.
UPDATE_CATEGORY = "update"

#: Beats to list in the notice. Past this many the embed stops being a headline and
#: starts being the post. Sized so a normal update lists every section it has: an
#: update where a headline feature is silently cut is worse than a long notice.
MAX_HIGHLIGHTS = 12

#: Buttons must keep the same custom_id across restarts or an older notice's button
#: stops resolving. Never renumber these.
OPTOUT_CUSTOM_ID = "mgx:update-notice:optout"

_SECTION = re.compile(r"^##\s+(?!#)(.+?)\s*$", re.MULTILINE)
_FEATURE = re.compile(r"^###\s+(.+?)\s*$", re.MULTILINE)
#: Markdown that carries no meaning once the heading is a bullet.
_MARKUP = re.compile(r"[*_`]")


@dataclass(frozen=True)
class UpdateTemplate:
    """One published update, in the terms a notice needs."""

    slug: str
    title: str
    tagline: str
    date: str
    highlights: tuple[str, ...]
    draft: bool

    @property
    def url(self) -> str:
        return f"{SITE_URL}/{self.slug}/"

    @property
    def label(self) -> str:
        return f"{self.title} (draft)" if self.draft else self.title

    def as_dict(self) -> dict[str, Any]:
        return {
            "slug": self.slug,
            "title": self.title,
            "label": self.label,
            "tagline": self.tagline,
            "date": self.date,
            "highlights": list(self.highlights),
            "draft": self.draft,
            "url": self.url,
        }


def _front_matter(text: str) -> tuple[dict[str, str], str]:
    """The post's own format: a `---` block, then the body."""
    if not text.startswith("---"):
        return {}, text
    parts = text.split("\n---", 2)
    if len(parts) < 2:
        return {}, text
    meta: dict[str, str] = {}
    for line in parts[0][3:].splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or ":" not in stripped:
            continue
        key, _, value = stripped.partition(":")
        meta[key.strip().lower()] = value.strip().strip('"').strip("'")
    body = parts[1]
    if body.startswith("-"):
        body = body.lstrip("-")
    return meta, body.lstrip("\n")


def _highlights(body: str) -> tuple[str, ...]:
    """The update's beats.

    Section headings are the beats the author chose; feature headings are only used
    when a post has no sections, because a long post has far too many of them to be
    a summary.
    """
    found = _SECTION.findall(body) or _FEATURE.findall(body)
    cleaned = [_MARKUP.sub("", item).strip() for item in found]
    return tuple(item for item in cleaned if item)[:MAX_HIGHLIGHTS]


def load_update_templates(posts_dir: Optional[Path] = None) -> list[UpdateTemplate]:
    """Every update post, newest first. Drafts included and flagged as such."""
    directory = posts_dir or POSTS_DIR
    if not directory.is_dir():
        return []
    templates: list[UpdateTemplate] = []
    for path in sorted(directory.glob("*.md")):
        try:
            meta, body = _front_matter(path.read_text(encoding="utf-8"))
        except OSError:
            continue
        if str(meta.get("category", "")).strip().lower() != UPDATE_CATEGORY:
            continue
        title = str(meta.get("title", "")).strip()
        if not title:
            continue
        slug = str(meta.get("slug", "")).strip()
        if not slug:
            match = re.match(r"^\d{4}-\d{2}-\d{2}-(.+)$", path.stem)
            slug = match.group(1) if match else path.stem
        date = str(meta.get("date", "")).strip() or path.stem[:10]
        templates.append(
            UpdateTemplate(
                slug=slug,
                title=title,
                tagline=str(meta.get("tagline", "")).strip(),
                date=date,
                highlights=_highlights(body),
                draft=str(meta.get("draft", "")).strip().lower() in {"1", "true", "yes"},
            )
        )
    templates.sort(key=lambda item: (item.date, item.slug), reverse=True)
    return templates


def find_template(slug: str, posts_dir: Optional[Path] = None) -> Optional[UpdateTemplate]:
    wanted = str(slug or "").strip().lower()
    for template in load_update_templates(posts_dir):
        if template.slug.lower() == wanted:
            return template
    return None


def build_notice_embed(template: UpdateTemplate) -> discord.Embed:
    """The notice itself. Hype line first, then what actually landed."""
    lines: list[str] = []
    if template.tagline:
        lines.append(template.tagline)
    if template.highlights:
        if lines:
            lines.append("")
        lines.append("**What landed**")
        lines.extend(f"> {item}" for item in template.highlights)
    lines.append("")
    lines.append("Read the full update for the numbers, the odds and the screenshots.")

    embed = discord.Embed(
        title=f"{template.title} is live!",
        description="\n".join(lines),
        colour=discord.Colour(0xF06000),
        url=template.url,
    )
    embed.set_author(name="Mysterious SMP X")
    embed.set_footer(text="You get this because you are a verified Mysterious SMP X player.")
    return embed


class UpdateNoticeView(discord.ui.View):
    """The two buttons under an update notice.

    Persistent by construction: no timeout and a fixed custom_id, so the opt-out on a
    notice sent months ago still works after a restart.
    """

    def __init__(self, bot: Any, url: str = SITE_URL) -> None:
        super().__init__(timeout=None)
        self.bot = bot
        # A link button carries no custom_id and needs no callback; Discord opens it.
        self.add_item(
            discord.ui.Button(
                label="Read the full update",
                style=discord.ButtonStyle.link,
                url=url or SITE_URL,
            )
        )

    @discord.ui.button(
        label="Stop update DMs",
        style=discord.ButtonStyle.danger,
        custom_id=OPTOUT_CUSTOM_ID,
    )
    async def stop_updates(
        self, interaction: discord.Interaction, button: discord.ui.Button
    ) -> None:
        await self.bot.data.set_update_optout(interaction.user.id, True)
        await interaction.response.send_message(
            embed=discord.Embed(
                title="Update DMs Switched Off",
                description=(
                    "> You will not be sent another update announcement.\n\n"
                    "This covers update notices only. Messages about your account, "
                    "your verification and anything staff need to tell you still "
                    "reach you, because those are how the server works.\n\n"
                    "Changed your mind? Ask an administrator to turn them back on."
                ),
                colour=discord.Colour(0xF06000),
            ),
            ephemeral=True,
        )
