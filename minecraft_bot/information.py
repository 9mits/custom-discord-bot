"""The server help centre: one message, one embed, buttons for each topic."""

from __future__ import annotations

from typing import Callable, Optional

import discord

from .perks import LEVEL_ROLE_MILESTONES
from .presentation import (
    BRAND_NAME,
    FOOTER_ICON_URL,
    LOGO_ATTACHMENT_URI,
    brand_logo_file,
)

THEME_COLOUR = discord.Color.from_rgb(255, 153, 0)

CONFIG_CHANNEL = "information_channel_id"
CONFIG_MESSAGE = "information_message_id"

#: Where members read how Discord levelling works.
LEVELS_CHANNEL_URL = "https://discord.com/channels/1476839721731620938/1476839722734190647"

#: The server runs 1.20.6. ViaVersion and ViaBackwards translate other Java clients,
#: and Geyser lets Bedrock players in, so this is deliberately about what a player
#: needs to know rather than an exact protocol list.
JAVA_VERSIONS = "1.8 and newer, including the latest release"
BEDROCK_VERSIONS = "the current version, on phone, console, tablet or Windows"


def _role_mentions() -> str:
    return "\n".join(
        f"> <@&{role_id}> — level {level}" for role_id, level in LEVEL_ROLE_MILESTONES
    )


def _embed(title: str, description: str) -> discord.Embed:
    embed = discord.Embed(title=title, description=description, colour=THEME_COLOUR)
    embed.set_footer(text=BRAND_NAME, icon_url=FOOTER_ICON_URL)
    return embed


def _apply_here(application_channel_id: Optional[int]) -> str:
    """Points at the real channel when we know it, so nobody has to go hunting."""
    return f"<#{application_channel_id}>" if application_channel_id else "the application channel"


def overview_embed(application_channel_id: Optional[int] = None) -> discord.Embed:
    embed = _embed(
        "Mysterious SMP X — Help Centre",
        "Mysterious Girlfriend X Discord, in partnership with r/MysteriousGirlfriendX.\n\n"
        "Everything you need to get started is here. Pick a topic below.\n\n"
        "**How to join**\n"
        f"> Apply in {_apply_here(application_channel_id)}.\n"
        "> Type your username, answer two short questions, then join the server once so we "
        "know the account is yours.\n"
        "> That first connection is turned away on purpose — it only proves ownership.\n"
        "> Staff review it after that, and you get the result in a DM either way.\n\n"
        "**Which version do I need?**\n"
        f"> **Java:** {JAVA_VERSIONS}\n"
        f"> **Bedrock:** {BEDROCK_VERSIONS}\n"
        "> Java and Bedrock players share one world and can play together.\n\n"
        "**What kind of server is it?**\n"
        "> Survival, and PvP is allowed. Raids, rivalries and betrayal are all part of it.\n"
        "> Every block placed and broken is logged, so real griefing can be undone.\n"
        "> Staff step in when something stops being a story and starts being harassment.",
    )
    embed.set_image(url=LOGO_ATTACHMENT_URI)
    return embed


def clans_embed() -> discord.Embed:
    return _embed(
        "Clans",
        "A clan is a group with a shared name, tag and colour. Your tag shows next to your "
        "name in chat, above your head, and in the player list.\n\n"
        "**Worth knowing**\n"
        "> Clan members cannot hurt each other. This is always on.\n"
        "> The clan name is the tag — there is no separate one to set.\n"
        "> Each clan has one leader, any number of staff, and its members.\n\n"
        "**Everyday commands**\n"
        "> `/clans create <name>` — start a clan and lead it\n"
        "> `/clans invite <player>` — invite someone\n"
        "> `/clans chat` — talk to just your clan\n"
        "> `/clans leave` — leave your clan\n\n"
        "**If you run one**\n"
        "> `/clans promote` and `/clans demote` — manage staff\n"
        "> `/clans rename` and `/clans color` — change name or colour\n"
        "> `/clans transfer <player>` — hand over the clan\n"
        "> `/clans kick <player>` — remove a member\n"
        "> `/clans disband` — close the clan\n\n"
        "**Looking at other clans**\n"
        "> `/clans list` — every clan on the server\n"
        "> `/claninfo [name]` — its leader, staff and members\n\n"
        "`/clans help` only shows what you can actually use right now.",
    )


def levels_embed() -> discord.Embed:
    return _embed(
        "Levels and Perks",
        "Your Discord level carries into Minecraft.\n\n"
        "**How you level up**\n"
        "> By taking part — chatting in text channels and talking in voice.\n"
        "> There is nothing to claim or buy. It happens as you join in.\n"
        f"> More detail: {LEVELS_CHANNEL_URL}\n\n"
        "**Milestone roles**\n"
        f"{_role_mentions()}\n\n"
        "**What you get**\n"
        "> Each milestone below 50 gives you **one extra heart**, up to **five**.\n"
        "> **Level 50** does not give a sixth heart. It gives **+15% damage** instead.\n\n"
        "Your level, hearts and damage bonus are shown on the scoreboard at the side of "
        "your screen. `/perks` shows them any time.",
    )


def boosting_embed() -> discord.Embed:
    return _embed(
        "Boosting",
        "Boosting the Discord server gives you perks in game.\n\n"
        "**While you are boosting**\n"
        "> **+10% damage**\n"
        "> **+1 extra heart**, on top of what your level already gives you\n"
        "> **Hunger drains 10% slower**\n\n"
        "**These add to your level perks**\n"
        "> A level 50 player who also boosts has **+25% damage** and **six extra hearts**.\n\n"
        "If you stop boosting, the perks simply stop. Nothing else about your account or "
        "your rank changes.",
    )


def commands_embed() -> discord.Embed:
    return _embed(
        "Commands",
        "**In Minecraft**\n"
        "> `/guide` — the in-game guide and full command list\n"
        "> `/perks` — your level rewards\n"
        "> `/clans` — everything to do with clans\n"
        "> `/claninfo [name]` — look at any clan\n"
        "> `/settings` — what **you** see: other players' clan tags, and Discord messages "
        "in chat\n"
        "> `/discordnames` — whether other people see **your** Discord name\n"
        "> `/discord` — the community invite\n\n"
        "**In Discord**\n"
        "> `/minecraft account` — your application and linked account\n"
        "> `/minecraft clan` — your clan and the actions your role allows\n"
        "> `/minecraft staff` — the staff tools your permissions grant\n"
        "> `/minecraft moderate` — staff only; kick, mute, ban and more, without "
        "opening the game\n"
        "> `/minecraft broadcast` — staff only; announce a message in game\n\n"
        "Settings only affect your own screen. Hiding clan tags hides them for you; "
        "everyone else still sees them.",
    )


def troubleshooting_embed(application_channel_id: Optional[int] = None) -> discord.Embed:
    return _embed(
        "Common Questions",
        "**My application says expired**\n"
        "> Applications wait a limited time for you to connect and prove the account is "
        "yours. If nobody connects in time it expires on its own.\n"
        f"> Nothing is lost — just apply again in {_apply_here(application_channel_id)}.\n"
        "> You can check the current state any time with `/minecraft account`.\n\n"
        "**I got kicked the first time I joined**\n"
        "> That is meant to happen. The first connection only checks the account is yours; "
        "it never lets you into the world.\n\n"
        "**I typed the wrong username**\n"
        "> Press **Apply** again to reveal **Cancel Pending Verification**, or run "
        "`/minecraft cancel`, then apply with the right one.\n\n"
        "**I did not get a DM**\n"
        "> Turn on direct messages from server members, then check `/minecraft account`. "
        "Anything that failed to send is retried.\n\n"
        "**Can I play on Bedrock and Java?**\n"
        "> Yes, but each account applies separately, because each one is verified on its own.",
    )


PAGES: dict[str, tuple[str, Callable[[Optional[int]], discord.Embed]]] = {
    "clans": ("Clans", lambda _channel: clans_embed()),
    "levels": ("Levels & Perks", lambda _channel: levels_embed()),
    "boosting": ("Boosting", lambda _channel: boosting_embed()),
    "commands": ("Commands", lambda _channel: commands_embed()),
    "help": ("Common Questions", troubleshooting_embed),
}


class InformationButton(
    discord.ui.DynamicItem[discord.ui.Button],
    template=r"mgx_info:(?P<page>\w+)",
):
    """Persistent so the help centre keeps working after a restart.

    Answers privately, so one member reading a topic does not change the panel for
    everyone else looking at it.
    """

    def __init__(self, page: str, *, item: Optional[discord.ui.Button] = None) -> None:
        self.page = page
        label = PAGES[page][0] if page in PAGES else page.title()
        super().__init__(
            item
            or discord.ui.Button(
                label=label,
                style=discord.ButtonStyle.secondary,
                custom_id=f"mgx_info:{page}",
            )
        )

    @classmethod
    async def from_custom_id(cls, interaction, item, match):  # type: ignore[override]
        return cls(match["page"], item=item)

    async def callback(self, interaction: discord.Interaction) -> None:
        page = PAGES.get(self.page)
        if page is None:
            await interaction.response.send_message(
                "That section no longer exists.", ephemeral=True
            )
            return
        settings = getattr(interaction.client, "settings", None)
        channel_id = getattr(settings, "application_channel_id", 0)
        await interaction.response.send_message(embed=page[1](channel_id), ephemeral=True)


class InformationView(discord.ui.View):
    def __init__(self) -> None:
        super().__init__(timeout=None)
        for page in PAGES:
            self.add_item(InformationButton(page))


def message_payload(application_channel_id: Optional[int] = None) -> dict[str, object]:
    return {
        "embed": overview_embed(application_channel_id),
        "attachments": [brand_logo_file()],
        "view": InformationView(),
    }
