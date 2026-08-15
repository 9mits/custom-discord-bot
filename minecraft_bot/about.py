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
    """One page: a line of intro, then labelled points laid out in columns.

    Inline fields, unlike the information panel's stacked ones. Somebody deciding
    whether to apply is skimming rather than studying, and a heading above each
    point lets them find the one they care about.

    Each point is a sentence or two under its own heading — long enough to read
    properly, short enough that the column does not become the paragraph the
    layout was meant to break up.
    """
    embed = discord.Embed(title=title, description=intro, colour=THEME_COLOUR)
    for name, value in points:
        embed.add_field(name=name, value=value, inline=True)
    embed.set_footer(text=BRAND_NAME, icon_url=FOOTER_ICON_URL)
    return embed


def clans_about_embed(_settings=None) -> discord.Embed:
    return _page(
        "Clans",
        "A clan is a group of players under one name, with a shared tag and colour "
        "shown beside everyone in it.",
        [
            (
                "No friendly fire",
                "Members cannot damage each other, so you can fight alongside your "
                "own side without holding back.",
            ),
            (
                "Private clan chat",
                "Every clan has a channel only its own members can see.",
            ),
            (
                "A shared vault",
                "Members pool valuable materials into one balance that the clan "
                "spends together.",
            ),
            (
                f"{clans.MAX_PUBLIC_LEVEL} levels to climb",
                "Each level is bought from the vault, and its perks reach every "
                "member of the clan at once.",
            ),
            (
                "A roster that grows",
                f"Clans start with {clans.STARTING_MEMBER_SLOTS} seats and can be "
                f"expanded to {CLAN_MAX_MEMBERS}, one at a time.",
            ),
            (
                "Something to compete for",
                "Clans are ranked against each other on a leaderboard of their own.",
            ),
        ],
    )


def travel_about_embed(_settings=None) -> discord.Embed:
    return _page(
        "Homes, Travel & Warps",
        "The world is large and shared, so getting around it is part of how the "
        "server is set up.",
        [
            (
                f"{DEFAULT_HOME_LIMIT} saved homes",
                "Mark the places that matter to you and return to any of them "
                "whenever you like.",
            ),
            (
                "Teleporting to players",
                "Ask to join another player, or invite them to you. Both sides have "
                "to agree first.",
            ),
            (
                "Public warps",
                "Staff maintain shortcuts to spawn and the other places everyone "
                "uses.",
            ),
            (
                "Retracing your steps",
                "You can return to wherever you were standing before your last "
                "journey.",
            ),
            (
                "Left alone when you want",
                "Teleport requests can be turned off entirely.",
            ),
            (
                "Not an escape route",
                "Travelling is cancelled if you move or take damage, so it cannot "
                "be used to flee a fight.",
            ),
        ],
    )


def progression_about_embed(_settings=None) -> discord.Embed:
    embed = _page(
        "Levels & Rewards",
        "Time spent with the community counts for something in game.",
        [
            (
                "Extra hearts",
                "Talking here in Discord earns levels, and each milestone adds "
                "permanent health in Minecraft.",
            ),
            (
                f"+{ELITE_DAMAGE_PERCENT}% damage",
                "The highest milestone also increases the damage you deal in "
                "combat.",
            ),
            (
                "Boosting the server",
                f"Boosters receive +{BOOSTER_EXTRA_HEARTS} heart, "
                f"+{BOOSTER_DAMAGE_PERCENT}% damage and slower hunger on top of "
                "their level.",
            ),
            (
                "Everything stacks",
                "Your own level, your boost and your clan's level all add together.",
            ),
            (
                "Yours to keep",
                "None of it is lost when you die.",
            ),
            (
                "Leaderboards",
                "Wealth, kills, playtime, blocks mined and distance walked are all "
                "tracked.",
            ),
        ],
    )
    embed.add_field(
        name="Where levels are earned", value=LEVELS_CHANNEL_URL, inline=False
    )
    return embed


def social_about_embed(_settings=None) -> discord.Embed:
    return _page(
        "Talking & Voice",
        "Most of what happens on the server starts with people talking to each "
        "other.",
        [
            (
                "Proximity voice chat",
                f"{mod_link('Simple Voice Chat')} lets you speak to whoever is "
                "standing near you.",
            ),
            (
                "Chat linked to Discord",
                "In-game chat and a Discord channel carry each other, so nobody is "
                "left out of the conversation.",
            ),
            (
                "Private messages",
                "You can message any other player who is online.",
            ),
            (
                "Mail for absent players",
                "Leave a message and it waits until they next log in.",
            ),
            (
                "Your Discord name",
                "Show it beside your Minecraft username, or keep it hidden — the "
                "choice is yours.",
            ),
            (
                "Staff within reach",
                "Whoever is on duty can be contacted without leaving the game.",
            ),
        ],
    )


def world_about_embed(_settings=None) -> discord.Embed:
    return _page(
        "The World & Playing Fair",
        "One survival world, shared by everyone and kept in a state worth playing "
        "in.",
        [
            (
                "Java and Bedrock together",
                "Everyone shares the same world regardless of what they play on.",
            ),
            (
                "PvP with limits",
                "Fighting is part of the server, and the rules set out where it "
                "stops.",
            ),
            (
                "Builds are protected",
                "Griefing someone's base, or emptying the storage of anyone with no "
                "part in a fight, is not allowed.",
            ),
            (
                "Neutral ground",
                "Spawn, public farms and community projects belong to everybody.",
            ),
            (
                "Kept honest",
                "Anti-cheat runs on the server at all times.",
            ),
            (
                "A sensible mod policy",
                "Mods that improve how the game looks or runs are welcome; those "
                "that show you what you should not see are not.",
            ),
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
