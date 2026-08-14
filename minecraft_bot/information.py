"""The permanent server guidebook: one message, one embed, buttons for the detail."""

from __future__ import annotations

from typing import Optional

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


def _role_mentions() -> str:
    """Level milestones as role mentions, smallest first."""
    return "\n".join(
        f"- <@&{role_id}> — Level {level}"
        for role_id, level in LEVEL_ROLE_MILESTONES
    )


def _embed(title: str, description: str) -> discord.Embed:
    embed = discord.Embed(title=title, description=description, colour=THEME_COLOUR)
    embed.set_footer(text=BRAND_NAME, icon_url=FOOTER_ICON_URL)
    return embed


def overview_embed() -> discord.Embed:
    embed = _embed(
        "Mysterious SMP X — Server Guide",
        "**Mysterious Girlfriend X Discord, in partnership with r/MysteriousGirlfriendX, "
        "presents Mysterious SMP X.**\n\n"
        "A survival SMP where the world is shaped by the people in it. Build, trade, ally, "
        "feud — the stories here are yours to write.\n\n"
        "**What this server is**\n"
        "- Survival, cross-play between Java and Bedrock\n"
        "- PvP is allowed; so are rivalries, raids and betrayal\n"
        "- Everything is logged, so genuine grief can be investigated and rolled back\n"
        "- Staff step in when conflict stops being a story and starts being harassment\n\n"
        "**Getting in**\n"
        "Apply from the application channel. You verify ownership by connecting once, then "
        "staff review it. You are told the result privately either way.\n\n"
        "Use the buttons below for the detail on clans, levels, boosting and commands.",
    )
    embed.set_image(url=LOGO_ATTACHMENT_URI)
    return embed


def clans_embed() -> discord.Embed:
    return _embed(
        "Clans",
        "A clan is a named group with its own tag and colour. The tag appears beside your "
        "name in chat, above your head and in the player list.\n\n"
        "**The rules of a clan**\n"
        "- Members cannot damage one another. This cannot be turned off.\n"
        "- The clan name *is* the tag — there is no separate one to edit.\n"
        "- A clan has one leader, any number of staff, and its members.\n\n"
        "**Running one**\n"
        "- `/clans create <name>` — found a clan. You lead it.\n"
        "- `/clans invite <player>` — invite someone; they accept or decline.\n"
        "- `/clans promote` and `/clans demote` — manage your staff.\n"
        "- `/clans transfer <player>` — hand over leadership.\n"
        "- `/clans rename` and `/clans color` — change how the clan reads.\n"
        "- `/clans kick <player>` and `/clans leave` — manage membership.\n"
        "- `/clans chat` — talk privately to your clan.\n"
        "- `/clans disband` — end it.\n\n"
        "**Looking around**\n"
        "- `/clans list` — every clan on the server.\n"
        "- `/claninfo [name]` — a clan's leader, staff, members and roster.\n\n"
        "`/clans help` shows only the commands you can currently use.",
    )


def levels_embed() -> discord.Embed:
    return _embed(
        "Levels and Perks",
        "Your Discord level carries into Minecraft. **You gain levels by taking part in the "
        "community — chatting in text channels and talking in voice.** There is nothing to "
        f"claim; it happens as you join in.\n\nMore detail: {LEVELS_CHANNEL_URL}\n\n"
        "**Milestone roles**\n"
        f"{_role_mentions()}\n\n"
        "**What each milestone gives you**\n"
        "- Every milestone below 50 adds **one extra heart**, up to **five**.\n"
        "- **Level 50** does not add a sixth heart. Instead it grants **+15% damage** — a "
        "permanent edge in a fight rather than more health to lose.\n\n"
        "Your level, hearts and power are always visible on the scoreboard at the side of "
        "your screen, and `/perks` will show them on demand.",
    )


def boosting_embed() -> discord.Embed:
    return _embed(
        "Boosting the Server",
        "Boosting Mysterious Girlfriend X on Discord changes how you play here, not just how "
        "you look.\n\n"
        "**While you boost**\n"
        "- **+10% damage**\n"
        "- **+1 extra heart**, on top of anything your level already gave you\n"
        "- **Hunger drains 10% more slowly**\n\n"
        "**These stack with your level.** A level 50 player who also boosts fights at "
        "**+25% damage** and carries **six extra hearts**.\n\n"
        "Boost perks follow the boost. Stop boosting and they simply lapse — nothing else "
        "about your account changes, and your rank tag is unaffected.",
    )


def commands_embed() -> discord.Embed:
    return _embed(
        "Commands",
        "**In Minecraft**\n"
        "- `/guide` — the in-game guide, including a full command list\n"
        "- `/perks` — your level rewards\n"
        "- `/clans` — everything clan-related\n"
        "- `/claninfo [name]` — inspect any clan\n"
        "- `/settings` — choose what *you* see: other players' clan tags, and whether "
        "Discord messages appear in chat\n"
        "- `/discordnames` — choose whether others see *your* Discord name\n"
        "- `/discord` — the community invite\n\n"
        "**In Discord**\n"
        "- `/minecraft account` — your application and linked account\n\n"
        "Settings are personal. Hiding clan tags hides them for you alone; nobody else's "
        "view changes.",
    )


PAGES: dict[str, tuple[str, object]] = {
    "clans": ("Clans", clans_embed),
    "levels": ("Levels & Perks", levels_embed),
    "boosting": ("Boosting", boosting_embed),
    "commands": ("Commands", commands_embed),
}


class InformationButton(
    discord.ui.DynamicItem[discord.ui.Button],
    template=r"mgx_info:(?P<page>\w+)",
):
    """Persistent so the guide keeps working after a restart.

    Answers privately, so one member reading about clans does not change the panel
    for everyone else looking at it.
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
        await interaction.response.send_message(embed=page[1](), ephemeral=True)


class InformationView(discord.ui.View):
    def __init__(self) -> None:
        super().__init__(timeout=None)
        for page in PAGES:
            self.add_item(InformationButton(page))


def message_payload() -> dict[str, object]:
    return {
        "embed": overview_embed(),
        "attachments": [brand_logo_file()],
        "view": InformationView(),
    }
