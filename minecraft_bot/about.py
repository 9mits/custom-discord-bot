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
        "A clan is a team you join with other players. Its name and colour appear "
        "beside yours in chat, above your head and in the player list.",
        [
            (
                "No friendly fire",
                "Attacks between clan members do nothing, so you can fight beside "
                "your own team without hitting them by accident.",
            ),
            (
                "Private clan chat",
                "A separate chat that only your clan sees, so you can plan without "
                "the whole server reading it.",
            ),
            (
                "A shared bank",
                "Members hand valuable items to the clan through a menu. What they "
                "are worth becomes the clan's balance.",
            ),
            (
                f"{clans.MAX_PUBLIC_LEVEL} levels to buy",
                "The clan spends that balance on levels. Each one permanently "
                "strengthens every member — more health, damage and speed.",
            ),
            (
                "A roster that grows",
                f"A new clan holds {clans.STARTING_MEMBER_SLOTS} players. More "
                f"seats are bought the same way, up to {CLAN_MAX_MEMBERS}.",
            ),
            (
                "Something to compete for",
                "Clans are ranked against each other by wealth and activity on a "
                "leaderboard of their own.",
            ),
        ],
    )


def travel_about_embed(_settings=None) -> discord.Embed:
    return _page(
        "Homes, Travel & Warps",
        "The world is far too large to walk across every time, so the server gives "
        "you ways to teleport around it.",
        [
            (
                f"{DEFAULT_HOME_LIMIT} homes to save",
                f"Name up to {DEFAULT_HOME_LIMIT} spots — your base, a farm, a mine "
                "— and teleport straight back to any of them from anywhere.",
            ),
            (
                "Teleporting to a player",
                "Send someone a request to teleport to them, or to bring them to "
                "you. Nothing happens until they accept it.",
            ),
            (
                "Public warps",
                "Named places anyone can teleport to, such as spawn and the builds "
                "the whole server shares.",
            ),
            (
                "Return where you were",
                "After any teleport you can jump straight back to the spot you left, "
                "so a trip is never one way.",
            ),
            (
                "Left alone when you want",
                "Teleport requests can be switched off, and nobody can arrive beside "
                "you without your say-so.",
            ),
            (
                "Not an escape route",
                "Every teleport makes you stand still for a moment first, and is "
                "cancelled if you move or take damage.",
            ),
        ],
    )


def progression_about_embed(_settings=None) -> discord.Embed:
    embed = _page(
        "Levels & Rewards",
        "Being part of the community here is rewarded in game. These are Discord "
        "levels, earned by talking — not Minecraft experience points.",
        [
            (
                "Extra hearts",
                "Chatting in this Discord raises your level, and passing a milestone "
                "permanently adds hearts to your health bar in game.",
            ),
            (
                f"+{ELITE_DAMAGE_PERCENT}% damage",
                "Reaching the highest milestone also makes every hit you land in "
                "Minecraft harder.",
            ),
            (
                "Boosting the server",
                f"Boosting this Discord adds +{BOOSTER_EXTRA_HEARTS} heart, "
                f"+{BOOSTER_DAMAGE_PERCENT}% damage and slower hunger on top of "
                "your level.",
            ),
            (
                "Everything stacks",
                "Your Discord level, your boost and your clan's level all add "
                "together on the same character.",
            ),
            (
                "Yours to keep",
                "Dying costs you nothing here — these bonuses are permanent once "
                "earned.",
            ),
            (
                "Leaderboards",
                "The server tracks wealth, kills, playtime, blocks mined and "
                "distance walked, for players and for clans.",
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
        "Minecraft and this Discord are joined together, so you are never talking "
        "to an empty room.",
        [
            (
                "Proximity voice chat",
                f"With {mod_link('Simple Voice Chat')} installed you can speak "
                "aloud to players near you, and hear them fade as they walk off.",
            ),
            (
                "Chat that carries over",
                "What is said in Minecraft appears in a Discord channel, and "
                "replies there appear in game.",
            ),
            (
                "Private messages",
                "Message any other player who is online, without the rest of the "
                "server seeing it.",
            ),
            (
                "Mail for absent players",
                "Leave a message for someone offline and it is waiting for them "
                "next time they log in.",
            ),
            (
                "Your Discord name",
                "Choose whether your Discord username is shown beside your "
                "Minecraft one, or kept private.",
            ),
            (
                "Staff within reach",
                "Whoever is on duty can be called from inside the game if something "
                "needs sorting out.",
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
                "PvP with limits",
                "Players can fight each other, but only within rules that say when "
                "it is fair and when it is not.",
            ),
            (
                "Your build is safe",
                "Breaking into or destroying somebody else's base, or emptying "
                "their chests, is not allowed here.",
            ),
            (
                "Neutral ground",
                "Spawn, public farms and community projects belong to everyone and "
                "are left alone.",
            ),
            (
                "A sensible mod policy",
                "Mods that improve how the game looks or runs are fine. Ones that "
                "reveal ores through walls or play for you are not.",
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
