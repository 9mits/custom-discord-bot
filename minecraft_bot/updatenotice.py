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
from urllib.parse import quote

import discord

#: The blog lives beside the bot in the same checkout.
REPO_ROOT = Path(__file__).resolve().parent.parent
POSTS_DIR = REPO_ROOT / "devblog" / "posts"

SITE_URL = "https://mysterioussmpx.blog"
JOIN_URL = f"{SITE_URL}/apply/"

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
_HEADING = re.compile(r"^(#{2,3})\s+(.+?)\s*$", re.MULTILINE)
_IMAGE = re.compile(r"!\[[^\]]*\]\(([^)\s]+)(?:\s+[^)]*)?\)")
_ITEM = re.compile(r":item\[[^\]]+\]\s*")
_SENTENCE = re.compile(r".*?[.!?](?:[*_]+)?(?=\s|$)|.+$")
#: Markdown that carries no meaning once the heading is a bullet.
_MARKUP = re.compile(r"[*_`]")

MAX_NOTICE_EMBEDS = 4
MAX_NOTICE_IMAGES = 3
NOTICE_SUMMARY_CHARACTERS = 330


@dataclass(frozen=True)
class NoticeFeature:
    """A real feature excerpt selected from the update post."""

    title: str
    summary: str
    image: str = ""


@dataclass(frozen=True)
class NoticeGroup:
    """A compact Discord card made from feature excerpts in the post."""

    title: str
    features: tuple[NoticeFeature, ...]


@dataclass(frozen=True)
class UpdateTemplate:
    """One published update, in the terms a notice needs."""

    slug: str
    title: str
    tagline: str
    date: str
    highlights: tuple[str, ...]
    draft: bool
    cover: str = ""
    spotlight_title: str = ""
    spotlight: Optional[NoticeFeature] = None
    notice_groups: tuple[NoticeGroup, ...] = ()

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
            "notice_embeds": 1 + int(self.spotlight is not None) + len(self.notice_groups),
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


def _clean_heading(value: str) -> str:
    return _MARKUP.sub("", str(value or "")).strip()


def _feature_excerpts(body: str) -> dict[str, NoticeFeature]:
    """Index feature headings and concise excerpts from the post itself.

    Notice metadata only chooses which features deserve room in the DM. The words
    and screenshots remain sourced from the published post, so the announcement
    cannot quietly promise something the update page does not.
    """
    headings = list(_HEADING.finditer(body))
    features: dict[str, NoticeFeature] = {}
    for index, match in enumerate(headings):
        if match.group(1) != "###":
            continue
        end = headings[index + 1].start() if index + 1 < len(headings) else len(body)
        block = body[match.end():end]
        title = _clean_heading(match.group(2))
        image_match = _IMAGE.search(block)
        image = image_match.group(1).strip() if image_match else ""
        copy = _IMAGE.sub("", block)
        copy = _ITEM.sub("", copy)
        copy = re.sub(r"^\s*[-*]\s+", "", copy, flags=re.MULTILINE)
        copy = re.sub(r"\s+", " ", copy).strip()
        sentences = _SENTENCE.findall(copy)
        chosen: list[str] = []
        length = 0
        for sentence in sentences:
            sentence = sentence.strip()
            if not sentence:
                continue
            projected = length + (1 if chosen else 0) + len(sentence)
            if chosen and (len(chosen) >= 3 or projected > NOTICE_SUMMARY_CHARACTERS):
                break
            if not chosen and projected > NOTICE_SUMMARY_CHARACTERS:
                sentence = sentence[: NOTICE_SUMMARY_CHARACTERS - 1].rstrip() + "…"
            chosen.append(sentence)
            length += (1 if length else 0) + len(sentence)
        if chosen:
            features[title.casefold()] = NoticeFeature(
                title=title,
                summary=" ".join(chosen),
                image=image,
            )
    return features


def _selected_notice(meta: dict[str, str], body: str) -> tuple[
    Optional[NoticeFeature], tuple[NoticeGroup, ...]
]:
    features = _feature_excerpts(body)

    def choose(name: str) -> Optional[NoticeFeature]:
        return features.get(_clean_heading(name).casefold())

    spotlight = choose(meta.get("notice_spotlight", ""))
    groups: list[NoticeGroup] = []
    for key in sorted(meta):
        if not re.fullmatch(r"notice_group_\d+", key):
            continue
        pieces = [part.strip() for part in meta[key].split("|") if part.strip()]
        if len(pieces) < 2:
            continue
        selected = tuple(feature for name in pieces[1:] if (feature := choose(name)))
        if selected:
            groups.append(NoticeGroup(title=pieces[0], features=selected[:3]))
        if len(groups) >= MAX_NOTICE_EMBEDS - 2:
            break
    return spotlight, tuple(groups)


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
        spotlight, notice_groups = _selected_notice(meta, body)
        templates.append(
            UpdateTemplate(
                slug=slug,
                title=title,
                tagline=str(meta.get("tagline", "")).strip(),
                date=date,
                highlights=_highlights(body),
                draft=str(meta.get("draft", "")).strip().lower() in {"1", "true", "yes"},
                cover=str(meta.get("cover", "")).strip(),
                spotlight_title=str(meta.get("notice_spotlight_title", "")).strip(),
                spotlight=spotlight,
                notice_groups=notice_groups,
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


def _media_url(template: UpdateTemplate, image: str) -> str:
    source = str(image or "").strip()
    if source.startswith("https://"):
        return source
    filename = Path(source).name
    if not filename:
        return ""
    return f"{SITE_URL}/media/{quote(template.slug)}/{quote(filename)}"


def _short_update_name(title: str) -> str:
    name = re.sub(r"\s+update\s*$", "", str(title or "").strip(), flags=re.IGNORECASE)
    return re.sub(r"^the\s+", "", name, flags=re.IGNORECASE)


def build_notice_embeds(template: UpdateTemplate) -> list[discord.Embed]:
    """Build a restrained visual story when a post selects notice features.

    Posts without editorial notice metadata retain the established single-embed
    summary. A configured notice is capped at four embeds and three large images:
    enough to feel substantial, but not a second copy of the full article.
    """
    if template.spotlight is None and not template.notice_groups:
        return [build_notice_embed(template)]

    update_name = _short_update_name(template.title)
    lead = discord.Embed(
        title=f"New Mysterious SMP X update! — {update_name}",
        description=template.tagline or "A new Mysterious SMP X update is live.",
        colour=discord.Colour(0xB532FF),
        url=template.url,
    )
    lead.set_author(name="Mysterious SMP X")
    cover = _media_url(template, template.cover)
    if cover:
        lead.set_image(url=cover)
    embeds = [lead]
    image_count = int(bool(cover))

    if template.spotlight is not None and len(embeds) < MAX_NOTICE_EMBEDS:
        feature = template.spotlight
        spotlight = discord.Embed(
            title=template.spotlight_title or update_name,
            colour=discord.Colour(0xF06000),
            url=template.url,
        )
        spotlight.add_field(name=feature.title, value=f">>> {feature.summary}", inline=False)
        image = _media_url(template, feature.image)
        if image and image_count < MAX_NOTICE_IMAGES:
            spotlight.set_image(url=image)
            image_count += 1
        embeds.append(spotlight)

    for group in template.notice_groups:
        if len(embeds) >= MAX_NOTICE_EMBEDS:
            break
        card = discord.Embed(
            title=group.title,
            colour=discord.Colour(0x9B59FF),
            url=template.url,
        )
        for feature in group.features:
            card.add_field(name=feature.title, value=f"> {feature.summary}", inline=False)
        image = next((_media_url(template, item.image) for item in group.features if item.image), "")
        if image and image_count < MAX_NOTICE_IMAGES:
            card.set_image(url=image)
            image_count += 1
        embeds.append(card)

    embeds[-1].set_footer(
        text="Sent once for this update. Read everything or stop future update DMs below."
    )
    return embeds


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
        self.add_item(
            discord.ui.Button(
                label="Play again",
                style=discord.ButtonStyle.link,
                url=JOIN_URL,
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
