"""The server information panel: one message, one embed, buttons for each topic.

Pages listed in :data:`PAGES` are the top-level buttons. A page may also declare
categories in :data:`SECTIONS`; those render as a second row of buttons on the
page's own (ephemeral) message, so a long command list never arrives as one wall
of text. Nothing here documents staff commands — the panel is read by members.
"""

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
        f"> <@&{role_id}> — level {level}" for role_id, level in LEVEL_ROLE_MILESTONES
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


def _addresses(settings) -> tuple[str, str, str]:
    """Java address, Bedrock address and Bedrock port, with honest fallbacks.

    Shared so the overview and the versions page can never drift apart.
    """
    java = getattr(settings, "java_address", None) or "ask staff for the address"
    bedrock = getattr(settings, "bedrock_address", None) or "ask staff for the address"
    port = getattr(settings, "bedrock_port", None) or "19132"
    return java, bedrock, str(port)


def overview_embed(settings=None) -> discord.Embed:
    # This message lives in a channel members only see after they are accepted,
    # so it reads as the server handbook — never as joining instructions.
    java_address, bedrock_address, bedrock_port = _addresses(settings)
    embed = _page(
        "Information",
        "Mysterious Girlfriend X Discord, in partnership with "
        "r/MysteriousGirlfriendX.\n\n"
        "This panel documents how Mysterious SMP X works. Each button below opens "
        "one section.",
        [
            (
                "The server",
                "> A survival SMP with full Java and Bedrock crossplay — one world, "
                "one community, whichever edition you own.\n"
                "> Built around building, clans, teamwork, rivalry and lore.\n"
                "> PvP is enabled, so raids and betrayals are part of the story. "
                "Every block placed or broken is logged, so genuine griefing can "
                "always be reverted.",
            ),
            (
                "Client versions",
                f"> **Java** — {JAVA_SUPPORTED_RANGE}, translated automatically.\n"
                f"> Releases newer than **{SERVER_VERSION}** are blocked for "
                "safety reasons.\n"
                "> **Bedrock** — the current release, from phone, console, tablet "
                "or Windows.",
            ),
            (
                "Server address",
                f"**Java Edition**\n```text\n{java_address}\n```\n"
                f"**Bedrock Edition**\n```text\n{bedrock_address}\n```\n"
                f"**Bedrock port**\n```text\n{bedrock_port}\n```",
            ),
            (
                "Getting started",
                "> Open **Commands** for everything you can type.\n"
                f"> Run `/sethome` once you find your spot — you get "
                f"**{DEFAULT_HOME_LIMIT}**.",
            ),
        ],
    )
    embed.set_image(url=LOGO_ATTACHMENT_URI)
    return embed


def commands_embed() -> discord.Embed:
    return _page(
        "Commands",
        "Everything available to you in game. Choose a category below for the "
        "full list.",
        [
            (
                "Most used",
                "> `/sethome` — save your current location\n"
                "> `/home` — return to it\n"
                "> `/tpa <player>` — ask to teleport to someone\n"
                "> `/msg <player> <message>` — send a private message",
            ),
            (
                "Categories",
                "> **Homes & Travel** — saving and returning to locations\n"
                "> **Teleports** — requests between players\n"
                "> **Communication** — messages, mail and chat\n"
                "> **Clans** — founding and running a clan\n"
                "> **Account** — your perks, preferences and server details",
            ),
        ],
    )


def commands_homes_embed(settings=None) -> discord.Embed:
    return _page(
        "Commands — Homes & Travel",
        "Saving locations and moving between them.",
        [
            (
                "Homes",
                "> `/sethome [name]` — save your current location\n"
                "> `/home [name]` — travel to a saved home\n"
                "> `/delhome <name>` — remove one\n"
                "> `/renamehome <old> <new>` — rename one",
            ),
            (
                "Travel",
                "> `/back` — return to your previous location\n"
                "> `/warp [name]` — list available warps, or travel to one",
            ),
            (
                "Worth knowing",
                f"> You may keep **{DEFAULT_HOME_LIMIT} homes**.\n"
                f"> Travel pauses **{TELEPORT_WARMUP_SECONDS} seconds** before it "
                "completes, and cancels if you move or take damage.",
            ),
        ],
    )


def commands_teleports_embed(settings=None) -> discord.Embed:
    return _page(
        "Commands — Teleports",
        "Teleporting to other players is by request; both sides must agree.",
        [
            (
                "Sending a request",
                "> `/tpa <player>` — ask to teleport to them\n"
                "> `/tpahere <player>` — ask them to teleport to you\n"
                "> `/tpacancel` — withdraw your request",
            ),
            (
                "Answering a request",
                "> `/tpaccept` — accept\n"
                "> `/tpdeny` — decline\n"
                "> `/tptoggle` — stop receiving requests entirely",
            ),
            (
                "Timing",
                f"> Teleports wait **{TELEPORT_WARMUP_SECONDS} seconds** and cancel "
                "if you move or take damage.\n"
                f"> There are **{TELEPORT_COOLDOWN_SECONDS} seconds** between uses.",
            ),
        ],
    )


def commands_chat_embed(settings=None) -> discord.Embed:
    return _page(
        "Commands — Communication",
        "Talking to players in game, whether or not they are online.",
        [
            (
                "Private messages",
                "> `/msg <player> <message>` — send a private message\n"
                "> `/r <message>` — reply to the last message received\n"
                "> `/msgtoggle` — stop receiving private messages\n"
                "> `/ignore <player>` — mute someone privately",
            ),
            (
                "Offline and public",
                "> `/mail send <player> <message>` — message an offline player\n"
                "> `/mail read` — read your mail\n"
                "> `/me <action>` — emote in chat\n"
                "> `/afk [reason]` — mark yourself away",
            ),
            (
                "Needing help",
                "> `/helpop <message>` — reach whoever is on duty.\n"
                "> Use it for rule breaking or anything that needs intervention.",
            ),
        ],
    )


def commands_clans_embed(settings=None) -> discord.Embed:
    return _page(
        "Commands — Clans",
        "Founding, joining and running a clan. Open the **Clans** section for how "
        "they behave.",
        [
            (
                "Everyday",
                "> `/clans create <name>` — found a clan and lead it\n"
                "> `/clans invite <player>` — invite a player\n"
                "> `/clans chat` — speak to your clan only\n"
                "> `/clans list` — every clan on the server\n"
                "> `/clans leave` — depart your clan",
            ),
            (
                "If you lead one",
                "> `/clans promote` · `/clans demote` — manage clan staff\n"
                "> `/clans rename` · `/clans color` — change name or colour\n"
                "> `/clans transfer <player>` — hand over leadership\n"
                "> `/clans kick <player>` — remove a member\n"
                "> `/clans disband` — dissolve the clan",
            ),
            (
                "Inspecting",
                "> `/claninfo [name]` — a clan's leader, staff and members\n"
                "> `/clans help` — only what your role currently permits",
            ),
        ],
    )


def commands_account_embed(settings=None) -> discord.Embed:
    return _page(
        "Commands — Account",
        "Your perks and preferences, plus details about the server.",
        [
            (
                "Your account",
                "> `/perks` — your level rewards and bonuses\n"
                "> `/settings` — clan tags, Discord chat and Discord names\n"
                "> `/discordnames` — whether others see your Discord name\n"
                "> `/playtime` — time spent on the server",
            ),
            (
                "The server",
                "> `/list` — who is online\n"
                "> `/ping` — your connection latency\n"
                "> `/whitelisted [page]` — everyone with access\n"
                "> `/realname <name>` — look up a display name\n"
                "> `/rules` · `/motd` — rules and welcome text\n"
                "> `/guide` — the in-game guide\n"
                "> `/discord` — the community invite",
            ),
            (
                "In Discord",
                "> `/minecraft account` — your application and linked account\n"
                "> `/minecraft whitelist` — everyone with access\n"
                "> `/minecraft clan view` — your clan and permitted actions",
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
                "> Members cannot damage one another. This is permanent.\n"
                "> The clan name serves as the tag; there is no separate one.\n"
                "> Each clan has one leader, any number of staff, and its members.",
            ),
            (
                "Joining one",
                "> Clans are invitation only — a leader or clan staff must invite "
                "you.\n"
                "> `/clans list` shows every clan currently on the server.",
            ),
            (
                "Commands",
                "> The full command list lives under **Commands → Clans**.",
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
                "> Levels accrue from participation: chatting in text channels and "
                "speaking in voice channels.\n"
                f"> Full detail: {LEVELS_CHANNEL_URL}",
            ),
            ("Milestone roles", _role_mentions()),
            (
                "Rewards",
                "> Each milestone below 50 grants **one additional heart**, to a "
                "maximum of **five**.\n"
                "> **Level 50** replaces the sixth heart with **+15% damage**.",
            ),
            (
                "Reviewing your perks",
                "> Your level, hearts and damage bonus appear on the sidebar.\n"
                "> `/perks` displays them at any time.",
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
                "> **+10% damage**\n"
                "> **+1 heart**, in addition to your level rewards\n"
                "> **Hunger drains 10% more slowly**",
            ),
            (
                "Stacking with levels",
                "> A level 50 player who also boosts receives **+25% damage** and "
                "**six additional hearts**.\n"
                "> Ending a boost removes these bonuses only. Rank, clan and "
                "progress are unaffected.",
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
                f"> {mod_link('Simple Voice Chat')} carries proximity voice between "
                "nearby players.\n"
                "> Install the build matching your Minecraft version.",
            ),
            (
                "Permitted on Java",
                f"> **Performance** — {mod_link('Sodium')}, {mod_link('Lithium')}, "
                f"{mod_link('OptiFine')}\n"
                f"> **Shaders** — {mod_link('Iris Shaders')}\n"
                f"> **Mapping** — {xaeros_link} or {mod_link('JourneyMap')}, with "
                "cave mapping and player radar disabled\n"
                f"> **Building** — {mod_link('Litematica')}, including its printer\n"
                f"> **Quality of life** — {mod_link('AppleSkin')} and similar\n\n"
                f"*Most require the {mod_link('Fabric')} loader.*",
            ),
            (
                "Prohibited on every edition",
                "> X-ray, freecam, baritone, tracers, kill aura, reach modification, "
                "and anything else revealing what you could not observe yourself.\n"
                "> The server runs an anticheat and logs all block activity.",
            ),
            (
                "Bedrock",
                "> Client mods are unavailable, so voice chat, minimaps and "
                "performance mods do not apply.\n"
                "> Marketplace texture packs are permitted, being purely cosmetic.\n"
                "> For voice, use a Discord voice channel.",
            ),
        ],
    )


def technical_embed(settings=None) -> discord.Embed:
    java_address, bedrock_address, bedrock_port = _addresses(settings)
    return _page(
        "Server and Versions",
        "The software the server runs, and the connection details for each edition.",
        [
            (
                "Software",
                f"> **[Paper](https://papermc.io)** {SERVER_VERSION}\n"
                "> **[Geyser](https://geysermc.org)** provides Bedrock access\n"
                "> **[ViaVersion](https://modrinth.com/plugin/viaversion)** with "
                "ViaBackwards translates older Java clients\n\n"
                "*All run server-side; none require installation.*",
            ),
            (
                "Java Edition",
                f"> Supported versions are **{JAVA_SUPPORTED_RANGE}**.\n"
                f"> Newer releases are refused — if your launcher has moved past "
                f"{SERVER_VERSION}, create a **{SERVER_VERSION}** entry under "
                "**Installations**.\n\n"
                "Add the server under **Multiplayer → Add Server**:\n"
                f"```text\n{java_address}\n```",
            ),
            (
                "Bedrock Edition",
                "> Join on the current release from phone, console, tablet or "
                "Windows by adding an external server.\n\n"
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
                "> An application expires when a step is not completed in time.\n"
                f"> Nothing is lost — press **Apply** again in {_apply_here(settings)}, "
                "or check `/minecraft account`.",
            ),
            (
                "Disconnected on first join",
                "> This is intended. The first connection verifies account ownership "
                "only and never grants world access.",
            ),
            (
                "Verified but no form submitted",
                "> Use **Continue Application** in your direct messages, or press "
                f"**Apply** in {_apply_here(settings)} to resume where you stopped.",
            ),
            (
                "Incorrect username submitted",
                "> Press **Apply** again to reveal **Cancel Pending Verification**, "
                "or run `/minecraft cancel`, then reapply with the correct name.",
            ),
            (
                "No direct message received",
                "> Enable direct messages from server members, then check "
                "`/minecraft account`.\n"
                "> Undelivered messages are retried automatically.",
            ),
            (
                "Playing on both editions",
                "> Permitted. Each account applies separately, as each is verified "
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

#: Categories shown as a second row of buttons on a page's own message. Keeping
#: each category to one screenful is the whole point, so resist growing them.
SECTIONS: dict[str, dict[str, tuple[str, Callable[[Optional[object]], discord.Embed]]]] = {
    "commands": {
        "homes": ("Homes & Travel", commands_homes_embed),
        "teleports": ("Teleports", commands_teleports_embed),
        "chat": ("Communication", commands_chat_embed),
        "clans": ("Clans", commands_clans_embed),
        "account": ("Account", commands_account_embed),
    },
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
        payload: dict[str, object] = {"embed": page[1](settings), "ephemeral": True}
        if self.page in SECTIONS:
            payload["view"] = SectionView(self.page)
        await interaction.response.send_message(**payload)


class SectionButton(
    discord.ui.DynamicItem[discord.ui.Button],
    template=r"mgx_sec:(?P<page>\w+):(?P<section>\w+)",
):
    """A category within one page.

    Edits the member's own ephemeral message rather than sending another, so
    switching categories never buries the panel under a stack of replies.
    """

    def __init__(
        self, page: str, section: str, *, item: Optional[discord.ui.Button] = None
    ) -> None:
        self.page = page
        self.section = section
        entry = SECTIONS.get(page, {}).get(section)
        super().__init__(
            item
            or discord.ui.Button(
                label=entry[0] if entry else section.title(),
                style=discord.ButtonStyle.secondary,
                custom_id=f"mgx_sec:{page}:{section}",
            )
        )

    @classmethod
    async def from_custom_id(cls, interaction, item, match):  # type: ignore[override]
        return cls(match["page"], match["section"], item=item)

    async def callback(self, interaction: discord.Interaction) -> None:
        entry = SECTIONS.get(self.page, {}).get(self.section)
        if entry is None:
            await interaction.response.send_message(
                "That category no longer exists.", ephemeral=True
            )
            return
        settings = getattr(interaction.client, "settings", None)
        await interaction.response.edit_message(
            embed=entry[1](settings), view=SectionView(self.page)
        )


class SectionView(discord.ui.View):
    def __init__(self, page: str) -> None:
        super().__init__(timeout=None)
        for section in SECTIONS.get(page, {}):
            self.add_item(SectionButton(page, section))


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
