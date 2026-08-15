"""What the server is like, for people deciding whether to apply.

The counterpart to `information.py`, and deliberately not the same thing. That
panel **teaches** accepted members how to play here — every command, every limit,
every cost. This one helps somebody who cannot join yet **understand** what they
would be joining: what a clan is for, that you can save places and travel to
friends, what levelling actually earns you.

So the rule for everything in this file: describe the mechanic, never the command.
A slash command is useless to somebody who has not been accepted, and a page full
of them reads as homework rather than an invitation. A test walks every page here
and fails on any that names one.

Figures are quoted from the same constants the game enforces, so the pitch cannot
promise something the server does not do.
"""

from __future__ import annotations

from typing import Callable, Optional

import discord

from . import clans
from .information import CLAN_MAX_MEMBERS, DEFAULT_HOME_LIMIT, LEVELS_CHANNEL_URL
from .perks import (
    BOOSTER_DAMAGE_PERCENT,
    BOOSTER_EXTRA_HEARTS,
    ELITE_DAMAGE_PERCENT,
)
from .presentation import (
    BRAND_NAME,
    FOOTER_ICON_URL,
    THEME_COLOUR,
    mod_link,
    rules_embed,
    rules_image_file,
)


def _page(title: str, intro: str, points: list[tuple[str, str]]) -> discord.Embed:
    """One page: a single line of intro, then short labelled points in columns.

    Inline fields, unlike the information panel's stacked ones. Somebody deciding
    whether to apply is skimming, not studying: three short columns per row can be
    taken in at a glance, where the same content as prose is a wall they bounce off.
    Keep each point to a label and a line — if one needs a paragraph, it belongs in
    the information panel instead.
    """
    embed = discord.Embed(title=title, description=intro, colour=THEME_COLOUR)
    for name, value in points:
        embed.add_field(name=name, value=value, inline=True)
    embed.set_footer(text=BRAND_NAME, icon_url=FOOTER_ICON_URL)
    return embed


def clans_about_embed(_settings=None) -> discord.Embed:
    return _page(
        "Clans",
        "A group of players under one name, with a tag and colour on everyone in it.",
        [
            ("No friendly fire", "Members cannot hurt each other."),
            ("Clan chat", "Private to your own members."),
            ("Shared vault", "Everyone donates into it."),
            (
                f"{clans.MAX_PUBLIC_LEVEL} levels",
                "Bought with the vault. Perks reach every member.",
            ),
            (
                f"Roster {clans.STARTING_MEMBER_SLOTS} → {CLAN_MAX_MEMBERS}",
                "Seats are earned one at a time.",
            ),
            ("Ranked", "Clans compete on their own leaderboard."),
        ],
    )


def travel_about_embed(_settings=None) -> discord.Embed:
    return _page(
        "Homes, Travel & Warps",
        "The map is large. You are not meant to walk it every time.",
        [
            (f"{DEFAULT_HOME_LIMIT} homes", "Save your places, return any time."),
            ("Teleports", "To other players, if they agree."),
            ("Warps", "Public destinations staff set up."),
            ("Retrace", "Get back to where you just were."),
            ("Do not disturb", "Turn requests off when you want quiet."),
            ("No escape hatch", "Travel cancels if you move or take damage."),
        ],
    )


def progression_about_embed(_settings=None) -> discord.Embed:
    embed = _page(
        "Levels & Rewards",
        "Time spent with the community is worth something in game.",
        [
            ("Extra hearts", "Earned by talking here in Discord."),
            (f"+{ELITE_DAMAGE_PERCENT}% damage", "Waiting at the top level."),
            (
                "Boosting",
                f"+{BOOSTER_EXTRA_HEARTS} heart, +{BOOSTER_DAMAGE_PERCENT}% damage, "
                "slower hunger.",
            ),
            ("It all stacks", "Your level, your boost and your clan add up."),
            ("Permanent", "Dying does not take any of it away."),
            ("Leaderboards", "Wealth, kills, playtime, blocks, distance."),
        ],
    )
    embed.add_field(name="Where it is earned", value=LEVELS_CHANNEL_URL, inline=False)
    return embed


def social_about_embed(_settings=None) -> discord.Embed:
    return _page(
        "Talking & Voice",
        "Most of what happens here starts with people talking to each other.",
        [
            ("Proximity voice", f"{mod_link('Simple Voice Chat')} — hear whoever is near you."),
            ("Linked chat", "Minecraft and Discord share a channel."),
            ("Private messages", "To anyone else online."),
            ("Mail", "Waits for players who are offline."),
            ("Your Discord name", "Show it beside your username, or hide it."),
            ("Staff on call", "Reachable from inside the game."),
        ],
    )


def world_about_embed(_settings=None) -> discord.Embed:
    return _page(
        "The World & Playing Fair",
        "One survival world, shared by everyone, kept worth playing in.",
        [
            ("Crossplay", "Java and Bedrock in the same world."),
            ("PvP", "Allowed, with rules around it."),
            ("No griefing", "Builds and storage are off limits."),
            ("Neutral ground", "Spawn, public farms, community builds."),
            ("Anti-cheat", "Running at all times."),
            ("Mods", "Looks and performance yes. Seeing through walls, no."),
        ],
    )


#: Pages shown on the application panel, in the order the buttons appear.
#:
#: Rules is the one page shared with the information panel: rules are rules, and
#: an applicant agrees to them as part of applying, so a second copy could only
#: drift from the real one.
ABOUT_PAGES: dict[str, tuple[str, Callable[[Optional[object]], discord.Embed]]] = {
    "clans": ("Clans", clans_about_embed),
    "travel": ("Homes & Travel", travel_about_embed),
    "levels": ("Levels & Rewards", progression_about_embed),
    "social": ("Talking & Voice", social_about_embed),
    "world": ("The World", world_about_embed),
    "rules": ("Rules", lambda _settings: rules_embed()),
}

#: Pages whose embed points at an attachment; an ephemeral reply has to carry the
#: file or Discord draws a broken image.
ABOUT_PAGE_FILES: dict[str, Callable[[], discord.File]] = {
    "rules": rules_image_file,
}


class AboutButton(
    discord.ui.DynamicItem[discord.ui.Button],
    template=r"mgx_about:(?P<page>\w+)",
):
    """Persistent, so the application panel keeps working after a restart.

    Answers privately: the panel is read by everyone in the channel, and one person
    reading about clans should not change what anybody else is looking at.
    """

    def __init__(self, page: str, *, item: Optional[discord.ui.Button] = None) -> None:
        self.page = page
        label = ABOUT_PAGES[page][0] if page in ABOUT_PAGES else page.title()
        super().__init__(
            item
            or discord.ui.Button(
                label=label,
                style=discord.ButtonStyle.secondary,
                custom_id=f"mgx_about:{page}",
            )
        )

    @classmethod
    async def from_custom_id(cls, interaction, item, match):  # type: ignore[override]
        return cls(match["page"], item=item)

    async def callback(self, interaction: discord.Interaction) -> None:
        page = ABOUT_PAGES.get(self.page)
        if page is None:
            await interaction.response.send_message(
                "That section no longer exists.", ephemeral=True
            )
            return
        settings = getattr(interaction.client, "settings", None)
        payload: dict[str, object] = {"embed": page[1](settings), "ephemeral": True}
        attachment = ABOUT_PAGE_FILES.get(self.page)
        if attachment is not None:
            payload["file"] = attachment()
        await interaction.response.send_message(**payload)
