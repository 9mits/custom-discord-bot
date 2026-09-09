"""Update notices built from a dev-blog post or an explicitly private preview.

Writing the same announcement twice is how the Discord copy and the blog end up
disagreeing about what shipped. So a notice is not composed by hand: it is derived
from the post itself. The title, the hype line and the list of beats all come out
of `devblog/posts/`, which means an announcement can only ever describe an update
that is actually written up.

A draft can be previewed but never broadcast. The notice's whole purpose is to hand
people a link, and a draft is not on the site yet — a hundred DMs pointing at a 404
is worse than no announcement at all. Update 7 therefore has a compact private preview
until its full article is deliberately published.
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

MAX_NOTICE_EMBEDS = 10
MAX_NOTICE_IMAGES = 10
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
    image: str = ""
    colour: int = 0x9B59FF


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
    notice_cover: str = ""
    aliases: tuple[str, ...] = ()
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


def _built_in_preview_templates() -> tuple[UpdateTemplate, ...]:
    """Private notice drafts that can be previewed before their blog post ships.

    The Update 7 article and its full media set deliberately remain unpublished. This
    sectioned draft lets the owner review the exact comeback DM without publishing the
    article or allowing the owner console to broadcast a link that still returns 404.
    """
    return (
        UpdateTemplate(
            slug="update-7",
            title="Amethyst Dragon Update",
            aliases=("Amethyst Update",),
            tagline=(
                "🐉 THE AMETHYST DRAGON HAS AWAKENED! Break its crystals, climb "
                "RANKED PVP, chase ETERNAL RAINBOW GEAR that never expires — the "
                "AMETHYST CRATE IS BACK ON A TIMER — and GRIEFING IS OVER, your base "
                "is finally safe!"
            ),
            date="2026-09-08",
            highlights=(
                "Amethyst Dragon",
                "Eternal Rainbow Gear",
                "Ranked PvP",
                "Amethyst Crate",
                "Protected Bases",
            ),
            draft=True,
            notice_cover=f"{SITE_URL}/media/update-5/banner.png",
            spotlight_title="Amethyst Dragon",
            spotlight=NoticeFeature(
                title="🐉 A Cooperative World Boss",
                summary=(
                    "The Amethyst Dragon has awakened in its own crystal arena!\n"
                    "This is a shared fight: everyone who enters battles the same Dragon "
                    "together.\nBring it down before the arena's fight clock expires."
                ),
                image="dragon-victory.png",
            ),
            notice_groups=(
                NoticeGroup(
                    title="The Dragon's Treasure",
                    features=(
                        NoticeFeature(
                            title="📦 The Amethyst Dragon Crate",
                            summary=(
                                "When the Dragon falls, its arena stays open and the Dragon "
                                "Crate unlocks for a limited time. Spend your Mysterious Crate "
                                "Keys before its countdown reaches zero."
                            ),
                        ),
                        NoticeFeature(
                            title="⚔️ New Dragon Gear",
                            summary=(
                                "Sharpness VII weapons, crystal abilities, a 3x3 harvesting Hoe, "
                                "a Power VII Bow, faster Elytra, armour, supplies, and nine new "
                                "Dragon cosmetics."
                            ),
                        ),
                        NoticeFeature(
                            title="🌈 Eternal Gear",
                            summary=(
                                "Permanent rainbow versions of the full Amethyst set have the "
                                "same power with no timer. Each Eternal item is 2 in 100,000."
                            ),
                        ),
                        NoticeFeature(
                            title="🥚 One Egg — And One Secret",
                            summary=(
                                "One Amethyst Dragon Egg appears after victory. The hidden "
                                "Amethyst Dragon Ascendant jackpot is a music-synced royal aura."
                            ),
                        ),
                    ),
                    image="dragon-rewards.png",
                    colour=0x8E44FF,
                ),
                NoticeGroup(
                    title="Bigger Amethyst Blocks",
                    features=(
                        NoticeFeature(
                            title="🟣 Giant And Humongous",
                            summary=(
                                "Two larger, tougher and rarer Amethyst Block events have landed. "
                                "All tiers announce their coordinates and reward the players who "
                                "actually help mine them."
                            ),
                        ),
                    ),
                    image="humongous-amethyst.png",
                    colour=0xA545FF,
                ),
                NoticeGroup(
                    title="Dragon Leaderboards",
                    features=(
                        NoticeFeature(
                            title="🏅 Damage, Crystals And Clan Battles",
                            summary=(
                                "Every run records Dragon Damage and End Crystals Broken. Claimed "
                                "eggs also score in a Clan Battle with Shards and exclusive podium "
                                "auras."
                            ),
                        ),
                    ),
                    image="dragon-clan-battle.png",
                    colour=0xFF8808,
                ),
                NoticeGroup(
                    title="Ranked PvP",
                    features=(
                        NoticeFeature(
                            title="⚔️ Safe, Private Ranked Fights",
                            summary=(
                                "Challenge another player, fight with KEEP INVENTORY, then return "
                                "to the exact blocks you left. Optional money, item and cosmetic "
                                "wagers are locked only after both players agree."
                            ),
                        ),
                        NoticeFeature(
                            title="🏆 Bronze To Unreal",
                            summary=(
                                "Wins and losses move your RP through eight tiers. The arena is "
                                "fully destructible during the fight and restored afterwards."
                            ),
                        ),
                    ),
                    image="pvp-victory.png",
                    colour=0xE74C3C,
                ),
                NoticeGroup(
                    title="The Three Scythes",
                    features=(
                        NoticeFeature(
                            title="🗡️ Only The Podium Holds Them",
                            summary=(
                                "The #1 Apex, #2 Void and #3 Shadow Scythes carry bonus damage, "
                                "heavy sweeps and unique kill finishes—and move when the leaderboard "
                                "changes."
                            ),
                        ),
                    ),
                    image="pvp-scythes.png",
                    colour=0x673AB7,
                ),
                NoticeGroup(
                    title="The Amethyst Crate Is Back",
                    features=(
                        NoticeFeature(
                            title="💜 The Clock Was Reset",
                            summary=(
                                "The limited Amethyst Crate is open again for 2 Keys per pull. Its "
                                "hologram counts down to 12 September at 15:00 UTC."
                            ),
                        ),
                        NoticeFeature(
                            title="✨ Nine Exclusive Cosmetics",
                            summary=(
                                "The full timed Amethyst gear set and nine Amethyst cosmetics are "
                                "inside. Dragon, Eternal and secret rewards stay exclusive to the "
                                "Dragon Crate."
                            ),
                        ),
                    ),
                    image="amethyst-crate.png",
                    colour=0xB531FF,
                ),
                NoticeGroup(
                    title="The Server Is Protected Now",
                    features=(
                        NoticeFeature(
                            title="🛡️ No More Griefing",
                            summary=(
                                "Bases, farms, animals, clan builds and player storage are off "
                                "limits. Build the thing you were too scared to build."
                            ),
                        ),
                        NoticeFeature(
                            title="⚔️ Fights Go Through /pvp",
                            summary=(
                                "Agreed fights stay safe and restore themselves. Ambushing an "
                                "unrelated player mid-build is punished."
                            ),
                        ),
                    ),
                    image="pvp-hub.png",
                    colour=0x2ECC71,
                ),
                NoticeGroup(
                    title="Player Orders And Quality Of Life",
                    features=(
                        NoticeFeature(
                            title="🛒 Say What You Want To Buy",
                            summary=(
                                "Use /order to post exactly what you need and the price per item. "
                                "Other players can fill it partially or completely while you are "
                                "offline, and every offer is already funded."
                            ),
                        ),
                        NoticeFeature(
                            title="✨ Closer, Cleaner, Safer",
                            summary=(
                                "Closer Amethyst events, fixed Airdrops and Auto Buy, safer cosmetic "
                                "delivery, default Night Vision, cleaner tooltips and more."
                            ),
                        ),
                    ),
                    image="order-board.png",
                    colour=0x3498DB,
                ),
            ),
        ),
    )


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
                notice_cover=str(meta.get("notice_cover", "")).strip(),
                aliases=tuple(
                    alias.strip()
                    for alias in str(meta.get("notice_aliases", "")).split("|")
                    if alias.strip()
                ),
                spotlight_title=str(meta.get("notice_spotlight_title", "")).strip(),
                spotlight=spotlight,
                notice_groups=notice_groups,
            )
        )
    if posts_dir is None:
        present = {template.slug.casefold() for template in templates}
        templates.extend(
            template
            for template in _built_in_preview_templates()
            if template.slug.casefold() not in present
        )
    templates.sort(key=lambda item: (item.date, item.slug), reverse=True)
    return templates


def find_template(slug: str, posts_dir: Optional[Path] = None) -> Optional[UpdateTemplate]:
    wanted = str(slug or "").strip().lower()
    if posts_dir is None:
        # The private Update 7 article exists on the author's workstation, but is
        # intentionally absent from production. Resolve its stable preview names to
        # the same compact draft in both places instead of falling back to the older
        # published post whose title happened to be "Amethyst Update".
        for template in _built_in_preview_templates():
            names = {template.slug.lower(), template.title.lower()}
            names.update(alias.lower() for alias in template.aliases)
            if wanted in names:
                return template
    for template in load_update_templates(posts_dir):
        names = {template.slug.lower(), template.title.lower()}
        names.update(alias.lower() for alias in template.aliases)
        if wanted in names:
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
    name = re.sub(r"^the\s+", "", str(title or "").strip(), flags=re.IGNORECASE)
    without_suffix = re.sub(r"\s+update\s*$", "", name, flags=re.IGNORECASE)
    # "Amethyst Update" is already a useful name; reducing it to just
    # "Amethyst" makes the preview look unfinished. Longer titles read better
    # without repeating Update in the surrounding headline.
    return without_suffix if " " in without_suffix else name


def build_notice_embeds(template: UpdateTemplate) -> list[discord.Embed]:
    """Build one full-width card for every selected section of an update.

    Posts without editorial notice metadata retain the established single-embed
    summary. A configured notice is capped at Discord's ten-embed message limit.
    Only the lead links the article: Discord turns multiple same-URL image embeds
    into a gallery and hides their section copy.
    """
    if template.spotlight is None and not template.notice_groups:
        return [build_notice_embed(template)]

    update_name = _short_update_name(template.title)
    lead = discord.Embed(
        title=f"New Mysterious SMP X update! - {update_name}",
        description=template.tagline or "A new Mysterious SMP X update is live.",
        colour=discord.Colour(0xB531FF),
        url=template.url,
    )
    lead.set_author(name="Mysterious SMP X")
    cover = _media_url(template, template.notice_cover or template.cover)
    if cover:
        lead.set_image(url=cover)
    embeds = [lead]
    image_count = int(bool(cover))

    if template.spotlight is not None and len(embeds) < MAX_NOTICE_EMBEDS:
        feature = template.spotlight
        spotlight = discord.Embed(
            title=template.spotlight_title or update_name,
            colour=discord.Colour(0xFF8808),
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
            colour=discord.Colour(group.colour),
        )
        for feature in group.features:
            card.add_field(name=feature.title, value=f"> {feature.summary}", inline=False)
        image = _media_url(template, group.image) or next(
            (_media_url(template, item.image) for item in group.features if item.image), ""
        )
        if image and image_count < MAX_NOTICE_IMAGES:
            card.set_image(url=image)
            image_count += 1
        embeds.append(card)

    embeds[-1].set_footer(
        text="Sent once for this update. Read everything or stop future update DMs below."
    )
    return embeds


class UpdateNoticeView(discord.ui.View):
    """The update link and the recipient's reversible DM preference.

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
        label="Update DM settings",
        style=discord.ButtonStyle.secondary,
        custom_id=OPTOUT_CUSTOM_ID,
    )
    async def update_dm_settings(
        self, interaction: discord.Interaction, button: discord.ui.Button
    ) -> None:
        from .announce import log_update_notice

        if await self.bot.data.is_update_opted_out(interaction.user.id):
            await self.bot.data.set_update_optout(interaction.user.id, False)
            await interaction.response.send_message(
                embed=discord.Embed(
                    title="Update DMs Switched Back On",
                    description=(
                        "> You will receive future Mysterious SMP X update notices again."
                    ),
                    colour=discord.Colour(0x57F287),
                ),
                ephemeral=True,
            )
            await log_update_notice(
                self.bot,
                title="Update DMs Re-enabled",
                member=interaction.user,
                detail="The member turned future update notices back on.",
                success=True,
            )
            return

        await interaction.response.send_message(
            embed=discord.Embed(
                title="Stop Future Update DMs?",
                description=(
                    "> This stops future server-update announcements. It does not stop "
                    "account, verification, or necessary staff messages.\n\n"
                    "Press the red confirmation below to finish. You can turn updates "
                    "back on later from this same **Update DM settings** button."
                ),
                colour=discord.Colour(0xF06000),
            ),
            view=ConfirmUpdateOptOutView(self.bot),
            ephemeral=True,
        )


class ConfirmUpdateOptOutView(discord.ui.View):
    """One deliberate confirmation before a member silences future update notices."""

    def __init__(self, bot: Any) -> None:
        super().__init__(timeout=180)
        self.bot = bot

    @discord.ui.button(label="Yes, stop future update DMs", style=discord.ButtonStyle.danger)
    async def confirm(
        self, interaction: discord.Interaction, button: discord.ui.Button
    ) -> None:
        from .announce import log_update_notice

        await self.bot.data.set_update_optout(interaction.user.id, True)
        await interaction.response.edit_message(
            embed=discord.Embed(
                title="Update DMs Switched Off",
                description=(
                    "> You will not be sent another server-update announcement.\n\n"
                    "This affects update notices only. Press **Update DM settings** on "
                    "any previous notice to turn them back on instantly."
                ),
                colour=discord.Colour(0xF06000),
            ),
            view=None,
        )
        await log_update_notice(
            self.bot,
            title="Update DMs Disabled",
            member=interaction.user,
            detail="The member confirmed that future update notices should stop.",
            success=False,
        )
