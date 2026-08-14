"""The server information panel: one message, one embed, buttons for each topic."""

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

#: Homes granted to the default rank, matching `sethome-multiple.default` in the
#: EssentialsX config. Documenting the wrong figure is worse than omitting it.
DEFAULT_HOME_LIMIT = 3
#: Seconds between teleports, and the stand-still delay before one completes.
#: Both come from the EssentialsX config and are what players actually feel.
TELEPORT_COOLDOWN_SECONDS = 30
TELEPORT_WARMUP_SECONDS = 5


def _role_mentions() -> str:
    return "\n".join(
        f"<@&{role_id}> — level {level}" for role_id, level in LEVEL_ROLE_MILESTONES
    )


def _embed(title: str, description: str) -> discord.Embed:
    embed = discord.Embed(title=title, description=description, colour=THEME_COLOUR)
    embed.set_footer(text=BRAND_NAME, icon_url=FOOTER_ICON_URL)
    return embed


def _page(title: str, intro: str, sections: list[tuple[str, str]]) -> discord.Embed:
    """One page: a short intro, then each section as its own embed field.

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
        "Information",
        "Mysterious Girlfriend X Discord, in partnership with "
        "r/MysteriousGirlfriendX.\n\n"
        "This panel documents how Mysterious SMP X works. Each button below opens "
        "one section.",
        [
            (
                "The server",
                "A survival world shared by Java and Bedrock players. PvP is enabled, "
                "so raiding and rivalry are permitted; every block placed or broken is "
                "logged, so griefing can be reverted and staff can intervene.",
            ),
            (
                "Client versions",
                f"The server runs **{SERVER_VERSION}**. Java clients from "
                f"**{JAVA_SUPPORTED_RANGE}** are translated automatically; releases "
                "newer than that are refused. Bedrock players join on the current "
                "release from any device.",
            ),
            (
                "Getting started",
                f"Open **Commands** for the full command list. Set a home with "
                f"`/sethome` as soon as you find your spot — you get "
                f"**{DEFAULT_HOME_LIMIT}**.",
            ),
        ],
    )
    embed.set_image(url=LOGO_ATTACHMENT_URI)
    return embed


def commands_embed() -> discord.Embed:
    return _page(
        "Commands",
        "Every command available to players, grouped by purpose. Arguments in "
        "`<>` are required; those in `[]` are optional.",
        [
            (
                "Homes and travel",
                f"`/sethome [name]` — save your location; **{DEFAULT_HOME_LIMIT}** "
                "homes are included\n"
                "`/home [name]` — return to a saved home\n"
                "`/delhome <name>` · `/renamehome <old> <new>` — manage your homes\n"
                "`/spawn` — return to the world spawn\n"
                "`/back` — return to your previous location\n"
                "`/warp [name]` — travel to a staff-created warp",
            ),
            (
                "Teleport requests",
                "`/tpa <player>` — ask to teleport to someone\n"
                "`/tpahere <player>` — ask someone to teleport to you\n"
                "`/tpaccept` · `/tpdeny` — answer a pending request\n"
                "`/tpacancel` — withdraw your request\n"
                "`/tptoggle` — stop receiving requests entirely\n\n"
                f"Teleports wait **{TELEPORT_WARMUP_SECONDS} seconds** and cancel if "
                f"you move or take damage, with **{TELEPORT_COOLDOWN_SECONDS} "
                "seconds** between uses.",
            ),
            (
                "Communication",
                "`/msg <player> <message>` — send a private message\n"
                "`/r <message>` — reply to the last message received\n"
                "`/mail send <player> <message>` · `/mail read` — message offline players\n"
                "`/ignore <player>` — mute someone privately\n"
                "`/afk [reason]` — mark yourself away\n"
                "`/me <action>` — emote in chat\n"
                "`/helpop <message>` — contact online staff",
            ),
            (
                "Clans",
                "`/clans` — create, invite, chat and manage\n"
                "`/claninfo [name]` — inspect any clan\n\n"
                "Open the **Clans** section for the full breakdown.",
            ),
            (
                "Your account and preferences",
                "`/perks` — your level rewards and bonuses\n"
                "`/settings` — what you see: clan tags, Discord chat, Discord names\n"
                "`/discordnames` — whether others see your Discord name\n"
                "`/playtime` — time spent on the server\n"
                "`/guide` — the in-game guide",
            ),
            (
                "Server information",
                "`/list` — who is online\n"
                "`/ping` — your connection latency\n"
                "`/whitelisted [page]` — everyone with access\n"
                "`/realname <name>` — look up a display name\n"
                "`/rules` · `/motd` — server rules and welcome text\n"
                "`/discord` — the community invite",
            ),
            (
                "In Discord",
                "`/minecraft account` — your application and linked account\n"
                "`/minecraft whitelist` — everyone with access\n"
                "`/minecraft clan view` — your clan and permitted actions\n"
                "`/mcstaff …` — staff only; moderate without opening the game",
            ),
        ],
    )


def clans_embed() -> discord.Embed:
    return _page(
        "Clans",
        "A clan is a named group with a shared tag and colour, shown beside your "
        "name in chat, above your head, and in the player list.",
        [
            (
                "How clans behave",
                "- Members cannot damage one another. This is permanent and cannot "
                "be disabled.\n"
                "- The clan name serves as the tag; there is no separate tag to set.\n"
                "- Each clan has one leader, any number of staff, and its members.",
            ),
            (
                "Member commands",
                "`/clans create <name>` — found a clan and lead it\n"
                "`/clans invite <player>` — invite a player\n"
                "`/clans chat` — speak to your clan only\n"
                "`/clans list` — every clan on the server\n"
                "`/clans leave` — depart your clan",
            ),
            (
                "Leader and staff commands",
                "`/clans promote` · `/clans demote` — manage clan staff\n"
                "`/clans rename` · `/clans color` — change name or colour\n"
                "`/clans transfer <player>` — hand over leadership\n"
                "`/clans kick <player>` — remove a member\n"
                "`/clans disband` — dissolve the clan",
            ),
            (
                "Inspecting clans",
                "`/claninfo [name]` lists a clan's leader, staff and members.\n\n"
                "*`/clans help` shows only what your role currently permits.*",
            ),
        ],
    )


def levels_embed() -> discord.Embed:
    return _page(
        "Levels and Perks",
        "Discord activity determines your in-game bonuses. Nothing is purchased "
        "or claimed.",
        [
            (
                "Earning levels",
                "Levels accrue from participation: chatting in text channels and "
                "speaking in voice channels.\n\n"
                f"Full detail: {LEVELS_CHANNEL_URL}",
            ),
            ("Milestone roles", _role_mentions()),
            (
                "Rewards",
                "Each milestone below 50 grants **one additional heart**, to a "
                "maximum of **five**.\n\n"
                "**Level 50** replaces the sixth heart with **+15% damage**.",
            ),
            (
                "Reviewing your perks",
                "Your level, hearts and damage bonus appear on the sidebar. `/perks` "
                "displays them at any time.",
            ),
        ],
    )


def boosting_embed() -> discord.Embed:
    return _page(
        "Boosting",
        "Boosting the Discord server grants additional in-game bonuses.",
        [
            (
                "While boosting",
                "- **+10% damage**\n"
                "- **+1 heart**, in addition to your level rewards\n"
                "- **Hunger drains 10% more slowly**",
            ),
            (
                "Stacking with levels",
                "A level 50 player who also boosts receives **+25% damage** and "
                "**six additional hearts**.\n\n"
                "Ending a boost removes these bonuses only. Rank, clan and progress "
                "are unaffected.",
            ),
        ],
    )


def mods_embed() -> discord.Embed:
    xaeros_link = mod_link("Xaero's Minimap")
    return _page(
        "Mods and Voice Chat",
        "Client modifications permitted on Mysterious SMP X, and those that are not.",
        [
            (
                "Voice chat",
                f"The server supports {mod_link('Simple Voice Chat')} for proximity "
                "voice between nearby players.\n\n"
                "Install the build matching your Minecraft version; the download page "
                "lists each one.",
            ),
            (
                "Permitted on Java",
                f"**Performance** — {mod_link('Sodium')}, {mod_link('Lithium')}, "
                f"{mod_link('OptiFine')}\n"
                f"**Shaders** — {mod_link('Iris Shaders')}\n"
                f"**Mapping** — {xaeros_link} or {mod_link('JourneyMap')}, with cave "
                "mapping and player radar disabled\n"
                f"**Building** — {mod_link('Litematica')}, including its printer\n"
                f"**Quality of life** — {mod_link('AppleSkin')} and similar\n\n"
                f"*Most require the {mod_link('Fabric')} loader.*",
            ),
            (
                "Prohibited on every edition",
                "X-ray, freecam, baritone, tracers, kill aura, reach modification, "
                "and anything else revealing information you could not observe "
                "yourself. The server runs an anticheat and logs all block activity.",
            ),
            (
                "Bedrock",
                "Bedrock does not support client mods, so voice chat, minimaps and "
                "performance mods are unavailable. Marketplace texture packs are "
                "permitted, being purely cosmetic.\n\n"
                "For voice, use a Discord voice channel.",
            ),
        ],
    )


def technical_embed(settings=None) -> discord.Embed:
    java_address = getattr(settings, "java_address", None) or "ask staff for the address"
    bedrock_address = getattr(settings, "bedrock_address", None) or "ask staff for the address"
    bedrock_port = getattr(settings, "bedrock_port", None) or "19132"
    return _page(
        "Server and Versions",
        "The software the server runs, and the connection details for each edition.",
        [
            (
                "Software",
                f"**[Paper](https://papermc.io)** {SERVER_VERSION}, with "
                "**[Geyser](https://geysermc.org)** providing Bedrock access and "
                "**[ViaVersion](https://modrinth.com/plugin/viaversion)** with "
                "ViaBackwards translating older Java clients.\n\n"
                "All run server-side; none require installation.",
            ),
            (
                "Java Edition",
                f"Supported versions are **{JAVA_SUPPORTED_RANGE}**. Newer releases "
                f"are refused, so if your launcher has moved past {SERVER_VERSION}, "
                f"create a **{SERVER_VERSION}** entry under **Installations**.\n\n"
                "Add the server under **Multiplayer → Add Server**:\n"
                f"```text\n{java_address}\n```",
            ),
            (
                "Bedrock Edition",
                "Join on the current release from phone, console, tablet or Windows "
                "by adding an external server with both values below.\n\n"
                "**Address**\n"
                f"```text\n{bedrock_address}\n```\n"
                "**Port**\n"
                f"```text\n{bedrock_port}\n```",
            ),
        ],
    )


def troubleshooting_embed(settings=None) -> discord.Embed:
    return _page(
        "Troubleshooting",
        "Resolutions for the issues raised most often.",
        [
            (
                "Expired applications",
                "An application expires when a step is not completed in time — either "
                "the account was never verified in game, or the written form was not "
                "submitted afterwards.\n\n"
                f"Nothing is lost. Press **Apply** again in {_apply_here(settings)}, "
                "or check the current state with `/minecraft account`.",
            ),
            (
                "Disconnected on first join",
                "This is intended. The first connection verifies account ownership "
                "only and never grants world access.",
            ),
            (
                "Verified but no form submitted",
                "Use the **Continue Application** button in your direct messages, or "
                f"press **Apply** in {_apply_here(settings)} to resume where you "
                "stopped.",
            ),
            (
                "Incorrect username submitted",
                "Press **Apply** again to reveal **Cancel Pending Verification**, or "
                "run `/minecraft cancel`, then reapply with the correct name.",
            ),
            (
                "No direct message received",
                "Enable direct messages from server members, then check "
                "`/minecraft account`. Undelivered messages are retried automatically.",
            ),
            (
                "Playing on both editions",
                "Permitted. Each account applies separately, as each is verified "
                "independently.",
            ),
        ],
    )


PAGES: dict[str, tuple[str, Callable[[Optional[object]], discord.Embed]]] = {
    "commands": ("Commands", lambda _settings: commands_embed()),
    "clans": ("Clans", lambda _settings: clans_embed()),
    "levels": ("Levels & Perks", lambda _settings: levels_embed()),
    "boosting": ("Boosting", lambda _settings: boosting_embed()),
    "mods": ("Mods & Voice Chat", lambda _settings: mods_embed()),
    "versions": ("Server & Versions", technical_embed),
    "help": ("Troubleshooting", troubleshooting_embed),
}


class InformationButton(
    discord.ui.DynamicItem[discord.ui.Button],
    template=r"mgx_info:(?P<page>\w+)",
):
    """Persistent so the panel keeps working after a restart.

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
