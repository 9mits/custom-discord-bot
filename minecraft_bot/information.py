"""The server help centre: one message, one embed, buttons for each topic."""

from __future__ import annotations

from typing import Callable, Optional

import discord

from .perks import LEVEL_ROLE_MILESTONES
from .presentation import (
    BRAND_NAME,
    FOOTER_ICON_URL,
    JAVA_SUPPORTED_RANGE,
    LOGO_ATTACHMENT_URI,
    SERVER_VERSION,
    brand_logo_file,
    mod_link,
)

THEME_COLOUR = discord.Color.from_rgb(255, 153, 0)

CONFIG_CHANNEL = "information_channel_id"
CONFIG_MESSAGE = "information_message_id"

#: Where members read how Discord levelling works.
LEVELS_CHANNEL_URL = "https://discord.com/channels/1476839721731620938/1476839722734190647"


def _role_mentions() -> str:
    return "\n".join(
        f"<@&{role_id}> — level {level}" for role_id, level in LEVEL_ROLE_MILESTONES
    )


def _embed(title: str, description: str) -> discord.Embed:
    embed = discord.Embed(title=title, description=description, colour=THEME_COLOUR)
    embed.set_footer(text=BRAND_NAME, icon_url=FOOTER_ICON_URL)
    return embed


def _page(title: str, intro: str, sections: list[tuple[str, str]]) -> discord.Embed:
    """One help page: a short intro, then each section as its own embed field.

    Fields give every section a bold header and clear air around it, which reads
    far better than one long quoted description.
    """
    embed = _embed(title, intro)
    for name, value in sections:
        embed.add_field(name=name, value=value, inline=False)
    return embed


def _apply_here(settings) -> str:
    """Points at the real channel when we know it, so nobody has to go hunting.

    Accepts either the settings object or a bare channel id.
    """
    if isinstance(settings, int):
        channel_id = settings
    else:
        channel_id = getattr(settings, "application_channel_id", 0) if settings else 0
    return f"<#{channel_id}>" if channel_id else "the application channel"


def overview_embed(settings=None) -> discord.Embed:
    # This message lives in a channel members only see after they are accepted,
    # so it reads as the server handbook — never as joining instructions.
    embed = _page(
        "Mysterious SMP X — Help Centre",
        "Mysterious Girlfriend X Discord, in partnership with r/MysteriousGirlfriendX.\n\n"
        "Welcome in — your application made it through, and the world is yours to "
        "explore. This channel is the server's help centre: each button below opens "
        "a short guide to one part of the server.",
        [
            (
                "Which version should I play on?",
                f"The server runs **{SERVER_VERSION}**, so that version gives the "
                "smoothest experience.\n\n"
                f"- **Java** — anything from {JAVA_SUPPORTED_RANGE} joins fine; the "
                "server translates older versions automatically. Releases newer "
                f"than {SERVER_VERSION} are turned away, so set your launcher back "
                "if it has updated past it.\n"
                "- **Bedrock** — just keep the game updated. Phone, console, tablet "
                "and Windows all work.\n"
                "- Whichever edition you use, everyone plays together in one shared "
                "world.",
            ),
            (
                "What kind of server is this?",
                "A survival server where the community writes its own stories. PvP is "
                "on, so alliances, raids, rivalries and even betrayals are all part of "
                "the fun — as long as everyone involved still gets to enjoy the game.\n\n"
                "Your builds are safer than they look: every block placed or broken is "
                "recorded, so genuine griefing can always be rolled back. And if a "
                "conflict ever stops feeling like a story and starts feeling personal, "
                "staff will step in to help.",
            ),
        ],
    )
    embed.set_image(url=LOGO_ATTACHMENT_URI)
    return embed


def clans_embed() -> discord.Embed:
    return _page(
        "Clans",
        "A clan is a group with a shared name, tag and colour. Your tag shows next "
        "to your name in chat, above your head, and in the player list.",
        [
            (
                "Worth knowing",
                "- Clan members cannot hurt each other. This is always on.\n"
                "- The clan name is the tag — there is no separate one to set.\n"
                "- Each clan has one leader, any number of staff, and its members.",
            ),
            (
                "Everyday commands",
                "`/clans create <name>` — start a clan and lead it\n"
                "`/clans invite <player>` — invite someone\n"
                "`/clans chat` — talk to just your clan\n"
                "`/clans leave` — leave your clan",
            ),
            (
                "If you run one",
                "`/clans promote` · `/clans demote` — manage staff\n"
                "`/clans rename` · `/clans color` — change name or colour\n"
                "`/clans transfer <player>` — hand over the clan\n"
                "`/clans kick <player>` — remove a member\n"
                "`/clans disband` — close the clan",
            ),
            (
                "Looking at other clans",
                "`/clans list` — every clan on the server\n"
                "`/claninfo [name]` — its leader, staff and members\n\n"
                "*`/clans help` only shows what you can actually use right now.*",
            ),
        ],
    )


def levels_embed() -> discord.Embed:
    return _page(
        "Levels and Perks",
        "Your Discord level carries into Minecraft.",
        [
            (
                "How you level up",
                "By taking part — chatting in text channels and talking in voice. "
                "There is nothing to claim or buy; it happens as you join in.\n\n"
                f"More detail: {LEVELS_CHANNEL_URL}",
            ),
            ("Milestone roles", _role_mentions()),
            (
                "What you get",
                "Each milestone below 50 gives you **one extra heart**, up to "
                "**five**.\n\n"
                "**Level 50** does not give a sixth heart — it gives **+15% damage** "
                "instead.",
            ),
            (
                "Seeing your perks",
                "Your level, hearts and damage bonus are shown on the scoreboard at "
                "the side of your screen. `/perks` shows them any time.",
            ),
        ],
    )


def boosting_embed() -> discord.Embed:
    return _page(
        "Boosting",
        "Boosting the Discord server gives you perks in game.",
        [
            (
                "While you are boosting",
                "- **+10% damage**\n"
                "- **+1 extra heart**, on top of what your level already gives you\n"
                "- **Hunger drains 10% slower**",
            ),
            (
                "They stack with your level perks",
                "A level 50 player who also boosts has **+25% damage** and **six "
                "extra hearts**.\n\n"
                "If you stop boosting, the perks simply stop. Nothing else about your "
                "account or your rank changes.",
            ),
        ],
    )


def mods_embed() -> discord.Embed:
    xaeros_link = mod_link("Xaero's Minimap")
    return _page(
        "Mods and Voice Chat",
        "What you can install on your client, and what to leave out.",
        [
            (
                "Voice chat — supported",
                f"The server supports {mod_link('Simple Voice Chat')}, so you can "
                "talk to players near you in game.\n\n"
                "Install the mod version that matches the Minecraft version you play "
                "on — the download page lists them all. Voice works best when your "
                f"client runs **{SERVER_VERSION}**, the version the server runs.",
            ),
            (
                "Allowed on Java",
                f"**Performance** — {mod_link('Sodium')}, {mod_link('Lithium')}, "
                f"{mod_link('OptiFine')}\n"
                f"**Shaders** — {mod_link('Iris Shaders')}\n"
                f"**Maps** — {xaeros_link} or {mod_link('JourneyMap')}, with cave "
                "mapping and player radar turned off\n"
                f"**Building** — {mod_link('Litematica')}, including its auto-build "
                "printer\n"
                f"**Comfort** — {mod_link('AppleSkin')} and similar quality-of-life "
                "mods\n\n"
                f"*Most of these run on the {mod_link('Fabric')} mod loader.*",
            ),
            (
                "Not allowed on any edition",
                "X-ray, freecam, baritone, tracers, or anything that shows what you "
                "could not see yourself.",
            ),
            (
                "On Bedrock",
                "Bedrock does not support client mods, so voice chat, minimaps and "
                "performance mods are unavailable there. Marketplace texture packs "
                "are fine because they are cosmetic only.\n\n"
                "For voice, join a Discord voice channel instead.",
            ),
        ],
    )


def technical_embed(settings=None) -> discord.Embed:
    java_address = getattr(settings, "java_address", None) or "ask staff for the address"
    bedrock_address = getattr(settings, "bedrock_address", None) or "ask staff for the address"
    bedrock_port = getattr(settings, "bedrock_port", None) or "19132"
    return _page(
        "Server and Versions",
        "What the server runs, and how to connect on each edition.",
        [
            (
                "What the server runs",
                f"**[Paper](https://papermc.io)** {SERVER_VERSION}, with "
                "**[Geyser](https://geysermc.org)** so Bedrock players can join, and "
                "**[ViaVersion](https://modrinth.com/plugin/viaversion)** with "
                "ViaBackwards so older Java clients can too.\n\n"
                "These all run on the server — you never need to install them.",
            ),
            (
                "Java Edition",
                f"Join on any version from {JAVA_SUPPORTED_RANGE}. Newer releases "
                f"are refused, so if your launcher has moved past {SERVER_VERSION}, "
                f"add a **{SERVER_VERSION}** entry under **Installations** and play "
                "on that.\n\n"
                "Add the server in **Multiplayer → Add Server** with this address:\n"
                f"```text\n{java_address}\n```",
            ),
            (
                "Bedrock Edition",
                "Join on the current release from phone, console, tablet or Windows. "
                "Add an external server with both values below.\n\n"
                "**Address**\n"
                f"```text\n{bedrock_address}\n```\n"
                "**Port**\n"
                f"```text\n{bedrock_port}\n```",
            ),
        ],
    )


def commands_embed() -> discord.Embed:
    return _page(
        "Commands",
        "The custom commands this server adds, in game and in Discord.",
        [
            (
                "In Minecraft",
                "`/guide` — the in-game guide and full command list\n"
                "`/perks` — your level rewards\n"
                "`/clans` — everything to do with clans\n"
                "`/claninfo [name]` — look at any clan\n"
                "`/whitelisted` — everyone with access and their Discord name\n"
                "`/settings` — what **you** see: clan tags and Discord chat\n"
                "`/discordnames` — whether others see **your** Discord name\n"
                "`/discord` — the community invite",
            ),
            (
                "In Discord",
                "`/minecraft account` — your application and linked account\n"
                "`/minecraft whitelist` — everyone with access and their Discord "
                "account\n"
                "`/minecraft clan view` — your clan and the actions your role allows\n"
                "`/mcstaff tools` — staff only; the staff tools your permissions "
                "grant\n"
                "`/mcstaff kick`, `/mcstaff ban` and more — staff only; moderate "
                "without opening the game\n"
                "`/mcstaff broadcast` — staff only; announce a message in game",
            ),
            (
                "Good to know",
                "Settings only affect your own screen. Hiding clan tags hides them "
                "for you; everyone else still sees them.",
            ),
        ],
    )


def troubleshooting_embed(settings=None) -> discord.Embed:
    return _page(
        "Common Questions",
        "Quick answers to the things people ask most.",
        [
            (
                "My application says expired",
                "An application expires when a step is not finished in time: either "
                "nobody joined the server to verify the account, or the written form "
                "was never completed after verifying.\n\n"
                f"Nothing is lost — just press **Apply** again in {_apply_here(settings)}. "
                "You can check the current state any time with `/minecraft account`.",
            ),
            (
                "I got kicked the first time I joined",
                "That is meant to happen. The first connection only checks the "
                "account is yours; it never lets you into the world.",
            ),
            (
                "I verified but have not filled out the form",
                "Check your DMs for the **Continue Application** button, or press "
                f"**Apply** in {_apply_here(settings)} — it continues where you left "
                "off.",
            ),
            (
                "I typed the wrong username",
                "Press **Apply** again to reveal **Cancel Pending Verification**, or "
                "run `/minecraft cancel`, then apply with the right one.",
            ),
            (
                "I did not get a DM",
                "Turn on direct messages from server members, then check "
                "`/minecraft account`. Anything that failed to send is retried.",
            ),
            (
                "Can I play on Bedrock and Java?",
                "Yes, but each account applies separately, because each one is "
                "verified on its own.",
            ),
        ],
    )


PAGES: dict[str, tuple[str, Callable[[Optional[object]], discord.Embed]]] = {
    "clans": ("Clans", lambda _settings: clans_embed()),
    "levels": ("Levels & Perks", lambda _settings: levels_embed()),
    "boosting": ("Boosting", lambda _settings: boosting_embed()),
    "mods": ("Mods & Voice Chat", lambda _settings: mods_embed()),
    "versions": ("Server & Versions", technical_embed),
    "commands": ("Commands", lambda _settings: commands_embed()),
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
        await interaction.response.send_message(embed=page[1](settings), ephemeral=True)


class InformationView(discord.ui.View):
    def __init__(self) -> None:
        super().__init__(timeout=None)
        for page in PAGES:
            self.add_item(InformationButton(page))


def message_payload(settings=None) -> dict[str, object]:
    return {
        "embed": overview_embed(settings),
        "attachments": [brand_logo_file()],
        "view": InformationView(),
    }
