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
    LEVEL_ROLE_MILESTONES,
)
from .presentation import (
    BRAND_NAME,
    FOOTER_ICON_URL,
    THEME_COLOUR,
    mod_link,
    rules_embed,
    rules_image_file,
)


def _page(title: str, intro: str, sections: list[tuple[str, str]]) -> discord.Embed:
    """One page: a short intro, then each section as its own field.

    Drawn the same way the information panel draws its pages, so a member who has
    read both does not feel they are looking at two different products.
    """
    embed = discord.Embed(title=title, description=intro, colour=THEME_COLOUR)
    for name, value in sections:
        embed.add_field(name=name, value=value, inline=False)
    embed.set_footer(text=BRAND_NAME, icon_url=FOOTER_ICON_URL)
    return embed


def clans_about_embed(_settings=None) -> discord.Embed:
    top_level = clans.MAX_PUBLIC_LEVEL
    return _page(
        "Clans",
        "A clan is a group of players under one name, with a tag and colour shown "
        "beside every member in chat, above their heads, and in the player list.",
        [
            (
                "Why join one",
                "> Clan members **cannot hurt each other**, so you can fight "
                "alongside friends without worrying about crossfire.\n"
                "> Every clan has its own private chat.\n"
                "> A clan is how most people end up with a base, a project and "
                "somebody to build it with.",
            ),
            (
                "Clans get stronger",
                "> Members pool valuable materials into a shared vault, and the "
                f"clan spends it to climb **{top_level} levels**.\n"
                "> Each level makes **everyone in the clan** tougher — extra "
                "hearts, more strength, faster digging, resistance and speed.\n"
                "> Those bonuses stack on top of whatever you have earned yourself.",
            ),
            (
                "Room to grow",
                f"> A new clan holds **{clans.STARTING_MEMBER_SLOTS} members** and "
                f"can be expanded to **{CLAN_MAX_MEMBERS}**.\n"
                "> Leaders and their chosen staff run the roster; everyone else "
                "just plays.\n"
                "> Clans are ranked against each other, so there is something to "
                "compete for beyond your own base.",
            ),
        ],
    )


def travel_about_embed(_settings=None) -> discord.Embed:
    return _page(
        "Homes, Travel & Warps",
        "The map is large and shared. You are not expected to walk across it every "
        "time you want to do something.",
        [
            (
                "Save the places you care about",
                f"> Mark **{DEFAULT_HOME_LIMIT} places** as your own and return to "
                "any of them whenever you like.\n"
                "> Your base, your farm, that cave you have not finished — name "
                "them and they are a moment away.",
            ),
            (
                "Reach other players",
                "> Ask somebody to let you teleport to them, or invite them to "
                "come to you. **Both sides have to agree**, so nobody appears "
                "beside you uninvited.\n"
                "> You can also turn requests off entirely when you want to be "
                "left alone.",
            ),
            (
                "Shared destinations",
                "> Staff set up public warps to the places everyone uses — spawn, "
                "community builds and the like.\n"
                "> Wandered too far, or died somewhere awkward? You can get back "
                "to where you just were.",
            ),
            (
                "It is not a free escape",
                "> Travelling makes you stand still for a moment first, and is "
                "cancelled if you move or take damage — so it cannot be used to "
                "vanish out of a fight.",
            ),
        ],
    )


def progression_about_embed(_settings=None) -> discord.Embed:
    top_milestone = LEVEL_ROLE_MILESTONES[-1][1]
    return _page(
        "Levels & Rewards",
        "Time spent with the community is worth something in game. Talking here in "
        "Discord earns levels, and those levels make your character permanently "
        "stronger in Minecraft.",
        [
            (
                "What levelling gives you",
                f"> Extra hearts as you climb, up to level **{top_milestone}**.\n"
                f"> At the top, **+{ELITE_DAMAGE_PERCENT}% damage** in combat as "
                "well.\n"
                "> These are permanent and follow you everywhere — they are not "
                "something you can lose by dying.",
            ),
            (
                "Boosting the Discord",
                f"> Boosters get **+{BOOSTER_EXTRA_HEARTS} heart**, "
                f"**+{BOOSTER_DAMAGE_PERCENT}% damage** and get hungry more "
                "slowly.\n"
                "> That is on top of your level, not instead of it.",
            ),
            (
                "It all stacks",
                "> Your own level, your boost and your clan's level add together.\n"
                f"> How levelling works: {LEVELS_CHANNEL_URL}",
            ),
            (
                "Something to chase",
                "> Leaderboards track the richest players, kills, time played, "
                "blocks mined and distance walked — for people and for clans.",
            ),
        ],
    )


def social_about_embed(_settings=None) -> discord.Embed:
    return _page(
        "Talking & Voice",
        "Most of what happens here happens because people are talking to each other.",
        [
            (
                "Proximity voice chat",
                f"> {mod_link('Simple Voice Chat')} lets you actually speak to "
                "whoever is standing near you, and hear them get quieter as they "
                "walk away.\n"
                "> Optional, but it is what makes building together feel like "
                "being in the same room.",
            ),
            (
                "Chat that reaches everywhere",
                "> Minecraft chat and a Discord channel are linked, so people "
                "here can follow along and answer even when they are not playing.\n"
                "> Your Discord name can be shown beside your Minecraft one, or "
                "hidden — that is your choice.",
            ),
            (
                "Privately, and to people who are offline",
                "> Private messages, and mail that waits for somebody who is not "
                "online.\n"
                "> Staff can be reached from in game whenever you need them.",
            ),
        ],
    )


def world_about_embed(_settings=None) -> discord.Embed:
    return _page(
        "The World & Playing Fair",
        "One survival world, shared by everyone, kept in a state worth playing in.",
        [
            (
                "Java and Bedrock together",
                "> Everyone is in the same world regardless of what they play on. "
                "Phone, console, PC — the same base, the same clan, the same "
                "people.",
            ),
            (
                "Survival, not a free-for-all",
                "> PvP exists, and so do rules about it. Griefing somebody's "
                "build, emptying their storage, or taking from people with no "
                "part in a fight are not allowed.\n"
                "> Spawn, public farms and community projects are neutral ground.",
            ),
            (
                "Kept honest",
                "> Anti-cheat runs on the server, and the mods that give an unfair "
                "view of the world are not permitted.\n"
                "> Mods that only make the game look better or run faster are "
                "welcome.",
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
