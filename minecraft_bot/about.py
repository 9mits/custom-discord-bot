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
        "A clan is a team you join with other players. Its name and colour show in "
        "chat, above your head and in the player list.",
        [
            (
                "No Friendly Fire",
                "Clan members cannot damage each other, so you can fight next to "
                "your own team without hitting them.",
            ),
            (
                "Clan Chat",
                "A private chat channel that only your own clan can see.",
            ),
            (
                "Clan Balance",
                "A one-way deposit system where members put in items and ores. "
                "Nothing can be taken back out once it is in.",
            ),
            (
                "Clan Levels",
                f"Spend the balance to buy up to {clans.MAX_PUBLIC_LEVEL} levels. "
                "Each one permanently raises every member's health, damage and speed.",
            ),
            (
                "Clan Roster",
                f"A clan starts with {clans.STARTING_MEMBER_SLOTS} seats and buys "
                f"more with its balance, up to {CLAN_MAX_MEMBERS} members.",
            ),
            (
                "Clan Leaderboard",
                "Clans are ranked against each other by wealth, so the balance is "
                "worth building up.",
            ),
        ],
    )


def travel_about_embed(_settings=None) -> discord.Embed:
    return _page(
        "Homes, Travel & Warps",
        "The world is far too large to walk across, so there are several ways to "
        "teleport around it.",
        [
            (
                "Homes",
                f"Save up to {DEFAULT_HOME_LIMIT} locations and teleport to them "
                "from anywhere. Set them at your base, a farm, or a mine.",
            ),
            (
                "Teleport Requests",
                "Ask a player to let you teleport to them, or to come to you. "
                "Nothing happens until they accept.",
            ),
            (
                "Warps",
                "Public locations that anyone can teleport to, such as spawn and "
                "shared community builds.",
            ),
            (
                "Return Teleport",
                "Teleport back to wherever you were before your last one, so a trip "
                "is never one way.",
            ),
            (
                "Do Not Disturb",
                "Switch teleport requests off and nobody can appear beside you or "
                "pull you to them.",
            ),
            (
                "Teleport Delay",
                "Every teleport makes you stand still for a few seconds and cancels "
                "if you move or take damage, so it cannot be used to escape a fight.",
            ),
        ],
    )


def progression_about_embed(_settings=None) -> discord.Embed:
    embed = _page(
        "Levels & Rewards",
        "These are Discord levels, earned by talking in this server — not Minecraft "
        "experience points.",
        [
            (
                "Extra Hearts",
                "Passing a Discord level milestone permanently adds hearts to your "
                "health bar in Minecraft.",
            ),
            (
                "Elite Damage",
                f"The top milestone adds +{ELITE_DAMAGE_PERCENT}% damage to every "
                "hit you land.",
            ),
            (
                "Booster Perks",
                f"Boosting this Discord gives +{BOOSTER_EXTRA_HEARTS} heart, "
                f"+{BOOSTER_DAMAGE_PERCENT}% damage and slower hunger on top of "
                "your level.",
            ),
            (
                "Stacking",
                "Your Discord level, your booster perks and your clan level all "
                "apply to the same character at once.",
            ),
            (
                "Permanent",
                "Every bonus is yours for good. Dying does not take any of it away.",
            ),
            (
                "Leaderboards",
                "Rankings for wealth, kills, playtime, blocks mined and distance "
                "walked, for both players and clans.",
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
        "Minecraft and this Discord are wired together, so a conversation carries "
        "between them.",
        [
            (
                "Proximity Voice Chat",
                f"Install {mod_link('Simple Voice Chat')} and you can talk out loud "
                "to players near you, fading out as they walk away.",
            ),
            (
                "Discord Chat Sync",
                "Minecraft chat appears in a Discord channel, and messages sent "
                "there appear in game.",
            ),
            (
                "Private Messages",
                "Message any player who is online without the rest of the server "
                "seeing it.",
            ),
            (
                "Mail",
                "Send a message to a player who is offline. They receive it the "
                "next time they log in.",
            ),
            (
                "Discord Name Display",
                "Choose whether your Discord username shows next to your Minecraft "
                "name in game.",
            ),
            (
                "Staff Help",
                "Call whoever is on duty from inside the game when you need them.",
            ),
        ],
    )


def world_about_embed(_settings=None) -> discord.Embed:
    return _page(
        "The World & Playing Fair",
        "Everyone plays together in one shared survival world — the same map, the "
        "same players, the same rules.",
        [
            (
                "Crossplay",
                "Java and Bedrock play together in the same world.",
            ),
            (
                "PvP",
                "You can fight other players, within rules that set out when it is "
                "fair and when it is not.",
            ),
            (
                "Grief Protection",
                "Breaking into or destroying somebody's base, or emptying their "
                "chests, is not allowed.",
            ),
            (
                "Neutral Zones",
                "Spawn, public farms and community builds belong to everyone and "
                "are left alone.",
            ),
            (
                "Mod Policy",
                "Performance and visual mods are fine. X-ray, cheats and anything "
                "that plays the game for you are not.",
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
