"""The server information panel: one message, one embed, buttons for each topic.

Pages listed in :data:`PAGES` are the top-level buttons. A page may also declare
categories in :data:`SECTIONS`; those render as a second row of buttons on the
page's own (ephemeral) message, so a long command list never arrives as one wall
of text. Nothing here documents staff commands — the panel is read by members.
"""

from __future__ import annotations

from typing import Callable, Optional

import discord

from .perks import (
    BOOSTER_DAMAGE_PERCENT,
    BOOSTER_EXTRA_HEARTS,
    BOOSTER_HUNGER_REDUCTION_PERCENT,
    ELITE_DAMAGE_PERCENT,
    LEVEL_ROLE_MILESTONES,
    profile_for_role_ids,
)
from .presentation import (
    BRAND_NAME,
    FOOTER_ICON_URL,
    JAVA_SUPPORTED_RANGE,
    LOGO_ATTACHMENT_URI,
    SERVER_TAGLINE_PARAGRAPHS,
    SERVER_VERSION,
    brand_logo_file,
    mod_link,
    rules_embed,
    rules_image_file,
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

#: Clan limits mirrored from ClanStore and ClanService in minecraft-bridge, which
#: are what actually enforce them. Tests parse the Java to fail on any drift.
CLAN_MAX_MEMBERS = 25
CLAN_INVITE_EXPIRY_MINUTES = 5
CLAN_THEME_COLOURS = (
    "orange",
    "gold",
    "yellow",
    "red",
    "pink",
    "purple",
    "blue",
    "aqua",
    "green",
    "white",
)


def _hearts(count: int) -> str:
    return f"{count} extra heart" if count == 1 else f"{count} extra hearts"


def _milestone_rewards() -> str:
    """The ladder, showing the running total a member holds at each milestone.

    Derived by asking the real perk function what each rung grants, so the copy
    cannot drift from what the bridge actually applies.
    """
    lines = []
    for index, (role_id, _level) in enumerate(LEVEL_ROLE_MILESTONES):
        owned = [held for held, _milestone in LEVEL_ROLE_MILESTONES[: index + 1]]
        profile = profile_for_role_ids(owned)
        reward = f"**{_hearts(profile.extra_hearts)}**"
        if profile.elite:
            reward += f" and **+{ELITE_DAMAGE_PERCENT}% damage**"
        lines.append(f"> <@&{role_id}> — {reward}")
    return "\n".join(lines)


def _embed(title: str, description: str) -> discord.Embed:
    # A page whose fields speak for themselves passes no intro; sending an empty
    # description leaves a blank line above the first field.
    embed = discord.Embed(title=title, colour=THEME_COLOUR)
    if description:
        embed.description = description
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


def _addresses(settings) -> tuple[str, str, str]:
    """Java address, Bedrock address and Bedrock port, with honest fallbacks."""
    java = getattr(settings, "java_address", None) or "ask staff for the address"
    bedrock = getattr(settings, "bedrock_address", None) or "ask staff for the address"
    port = getattr(settings, "bedrock_port", None) or "19132"
    return java, bedrock, str(port)


def overview_embed(settings=None) -> discord.Embed:
    # This message lives in a channel members only see after they are accepted,
    # so it reads as the server handbook — never as joining instructions.
    embed = _page(
        "Information",
        "",
        [
            (
                "The server",
                "\n> \n".join(f"> {line}" for line in SERVER_TAGLINE_PARAGRAPHS)
                + "\n> \n> **Crossplay** — Java and Bedrock share one world",
            ),
            (
                "Client versions",
                f"> **Java** — {JAVA_SUPPORTED_RANGE}\n"
                "> **Bedrock** — any current version\n"
                "> \n"
                f"> The server runs **{SERVER_VERSION}**. Anything newer is "
                "blocked; anything older is translated automatically.",
            ),
        ],
    )
    embed.set_image(url=LOGO_ATTACHMENT_URI)
    return embed


def commands_embed() -> discord.Embed:
    return _page(
        "Commands",
        "Everything available to you in game.",
        [
            (
                "Most used",
                "> `/sethome` · `/home` — save a spot and return to it\n"
                "> `/tpa <player>` — ask to teleport to someone\n"
                "> `/msg <player> <message>` — private message\n"
                "> `/back` — return to where you were",
            ),
            (
                "Full list",
                "> The buttons below cover homes and travel, teleports, "
                "communication, clans, and your account.",
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
                f"> **{DEFAULT_HOME_LIMIT} homes** included\n"
                f"> Travel pauses **{TELEPORT_WARMUP_SECONDS} seconds**, and "
                "cancels if you move or take damage",
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
                f"> **{TELEPORT_WARMUP_SECONDS} second** wait, cancelled if you "
                "move or take damage\n"
                f"> **{TELEPORT_COOLDOWN_SECONDS} seconds** between uses",
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
                "> `/helpop <message>` — reach whoever is on duty\n"
                "> For rule breaking, or anything needing intervention",
            ),
        ],
    )


def commands_clans_embed(settings=None) -> discord.Embed:
    return _page(
        "Commands — Clans",
        "Founding, joining and running a clan.",
        [
            (
                "Everyday",
                "> `/clans create <name>` — found a clan and lead it\n"
                "> `/clans accept` · `/clans decline` — answer an invite\n"
                "> `/clans invite <player>` — invite an online player\n"
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
            (
                "Your clan's picture",
                "> `/clans icon <url>` — shown beside your clan on the leaderboard\n"
                "> `/clans icon clear` — back to the default\n"
                "> Must end in `.png`, `.jpg`, `.gif` or `.webp`\n"
                "> \n"
                "> *Avoid Discord attachment links — they expire after about a "
                "day.*",
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
                "General information",
                "> Members **cannot damage each other**\n"
                f"> Up to **{CLAN_MAX_MEMBERS} members**\n"
                "> Join by invite, or start your own with `/clans create`",
            ),
            (
                "Roles inside a clan",
                "> **Member** — clan chat and the roster\n"
                "> **Staff** — the above, plus invite, kick and the clan icon\n"
                "> **Leader** — the above, plus rename, colour, promote, transfer "
                "and disband",
            ),
        ],
    )


def clans_roles_embed(settings=None) -> discord.Embed:
    return _page(
        "Clans — Roles",
        "Three ranks, each able to do everything the one below it can.",
        [
            (
                "Member",
                "> `/clans chat` — speak to your clan only\n"
                "> `/clans info` · `/clans list` — the roster and every clan\n"
                "> `/clans leave` — depart the clan",
            ),
            (
                "Staff",
                "> Everything a member can, plus:\n"
                "> `/clans invite <player>` — bring someone in\n"
                "> `/clans kick <player>` — remove a **member**\n"
                "> `/clans icon <url>` — set the clan's leaderboard picture",
            ),
            (
                "Leader",
                "> Everything staff can, plus:\n"
                "> `/clans rename` · `/clans color` — change name or colour\n"
                "> `/clans promote` · `/clans demote` — manage clan staff\n"
                "> `/clans transfer <player>` — hand over the clan\n"
                "> `/clans disband` — close the clan for everyone",
            ),
            (
                "Who can remove whom",
                "> Staff can kick members\n"
                "> **Only the leader can remove** another staff member\n"
                "> The **leader cannot be kicked** by anyone\n"
                "> \n"
                "> *Promoting somebody puts them beyond everyone's reach but "
                "yours.*",
            ),
        ],
    )


def clans_joining_embed(settings=None) -> discord.Embed:
    return _page(
        "Clans — Joining",
        "Clans are invitation only. You cannot join one by asking the server.",
        [
            (
                "Getting invited",
                "> A leader or staff runs `/clans invite <player>`\n"
                "> You must be **online** to receive it\n"
                f"> It expires after **{CLAN_INVITE_EXPIRY_MINUTES} minutes**\n"
                "> \n"
                "> Answer with `/clans accept` or `/clans decline`",
            ),
            (
                "Starting your own",
                "> `/clans create <name>` — founds it and makes you leader\n"
                "> The name must not already be taken\n"
                "> `/clans list` — see what already exists\n"
                "> \n"
                f"> **Colours** — {', '.join(CLAN_THEME_COLOURS)}",
            ),
            (
                "When a clan is full",
                f"> At **{CLAN_MAX_MEMBERS} members** no further invites can be "
                "accepted, and an outstanding one fails when used. Somebody has "
                "to leave first.",
            ),
        ],
    )


def clans_leaving_embed(settings=None) -> discord.Embed:
    return _page(
        "Clans — Leaving",
        "How to depart a clan, hand it over, or close it entirely.",
        [
            (
                "Members and staff",
                "> `/clans leave` — immediate, no confirmation asked\n"
                "> \n"
                "> *You keep everything you own. Only the tag and the damage "
                "immunity go with it.*",
            ),
            (
                "The leader cannot simply leave",
                "> **Transfer or disband first** — there is no other way out\n"
                "> \n"
                "> *This stops a clan being stranded with a full roster and "
                "nobody able to run it.*",
            ),
            (
                "Handing it over",
                "> `/clans transfer <player>` — they become leader\n"
                "> You stay in the clan as **staff**, not removed\n"
                "> \n"
                "> *You keep invite and kick; renaming, promoting and disbanding "
                "pass to them.*",
            ),
            (
                "Disbanding",
                "> `/clans disband` — leader only, cannot be undone\n"
                "> \n"
                "> *It closes the clan for everyone at once, not just for you.*",
            ),
        ],
    )


def levels_embed() -> discord.Embed:
    max_hearts = profile_for_role_ids(
        [role_id for role_id, _level in LEVEL_ROLE_MILESTONES]
    ).extra_hearts
    combined_damage = ELITE_DAMAGE_PERCENT + BOOSTER_DAMAGE_PERCENT
    return _page(
        "Levels and Perks",
        "Chatting in text channels and talking in voice earns Discord levels, "
        "which become permanent bonuses in Minecraft.",
        [
            ("What each milestone gives you", _milestone_rewards()),
            (
                "It all stacks",
                "> Milestones add up — the figure beside each role is your total\n"
                f"> **Boosting** — +{BOOSTER_EXTRA_HEARTS} heart and "
                f"+{BOOSTER_DAMAGE_PERCENT}% damage on top of your level\n"
                f"> **Maximum** — {_hearts(max_hearts + BOOSTER_EXTRA_HEARTS)} and "
                f"+{combined_damage}% damage",
            ),
            (
                "Checking yours",
                "> `/perks` — your level, hearts and damage bonus\n"
                "> The sidebar shows the same while you play\n"
                f"> How levelling works: {LEVELS_CHANNEL_URL}",
            ),
        ],
    )


def boosting_embed() -> discord.Embed:
    max_hearts = profile_for_role_ids(
        [role_id for role_id, _level in LEVEL_ROLE_MILESTONES]
    ).extra_hearts
    combined_damage = ELITE_DAMAGE_PERCENT + BOOSTER_DAMAGE_PERCENT
    return _page(
        "Boosting",
        "Boosting the Discord server adds bonuses on top of your level rewards.",
        [
            (
                "What boosting adds",
                f"> **+{BOOSTER_EXTRA_HEARTS} extra heart**\n"
                f"> **+{BOOSTER_DAMAGE_PERCENT}% damage**\n"
                f"> **Hunger drains {BOOSTER_HUNGER_REDUCTION_PERCENT}% more "
                "slowly**",
            ),
            (
                "How it stacks",
                "> Added to your level rewards, never instead of them\n"
                f"> Damage adds rather than multiplies — level 50 plus boosting "
                f"is **+{combined_damage}%**\n"
                f"> **Maximum** — {_hearts(max_hearts + BOOSTER_EXTRA_HEARTS)} and "
                f"+{combined_damage}% damage",
            ),
            (
                "If you stop boosting",
                "> Your boosting perks are removed immediately\n"
                "> Keep boosting to keep them\n"
                "> \n"
                "> *Your level rewards, rank and clan are unaffected.*",
            ),
        ],
    )


def mods_embed() -> discord.Embed:
    xaeros_link = mod_link("Xaero's Minimap")
    return _page(
        "Mods and Voice Chat",
        "Mods that change how the game looks or runs are fine. Mods that show you "
        "what you could not see, or play the game for you, are not.",
        [
            (
                "Voice chat",
                f"> The server officially supports {mod_link('Simple Voice Chat')}, "
                "which lets you talk to players standing near you.\n"
                "> Install the version matching the Minecraft version you play on.\n"
                "> **Java only — see below for Bedrock.**",
            ),
            (
                "Examples of permitted mods on Java",
                f"> **Performance** — {mod_link('Sodium')}, {mod_link('Lithium')}, "
                f"{mod_link('OptiFine')}\n"
                f"> **Shaders** — {mod_link('Iris Shaders')}\n"
                f"> **Mapping** — {xaeros_link} or {mod_link('JourneyMap')}, with "
                "cave mapping and player radar off\n"
                f"> **Building** — {mod_link('Litematica')}, printer included\n"
                f"> **Quality of life** — {mod_link('AppleSkin')} and similar\n"
                f"> **Loader** — most of these need {mod_link('Fabric')}",
            ),
            (
                "Clients",
                "> Custom Minecraft clients such as "
                f"{mod_link('Lunar Client')} and {mod_link('Feather')} are "
                "permitted, and come with most of the above already installed.",
            ),
            (
                "Not allowed on any edition",
                "> **Seeing what is hidden** — X-ray, ore and cave finders, "
                "freecam, tracers, player radar\n"
                "> **Playing for you** — kill aura, aim assist, auto-clickers, "
                "auto-walk\n"
                "> **Changing what your character can do** — extra reach, speed, "
                "flight, no fall damage\n"
                "> \n"
                "> *Examples of each kind, not the full list.*",
            ),
            (
                "Bedrock",
                "> Bedrock cannot install mods at all, so nothing above applies "
                "to you.\n"
                "> **Simple Voice Chat does not work on Bedrock.** There is no "
                "way to add it.\n"
                "> Join a Discord voice channel instead.",
            ),
        ],
    )


def technical_embed(settings=None) -> discord.Embed:
    java_address, bedrock_address, bedrock_port = _addresses(settings)
    return _page(
        "Server and Versions",
        "What the server runs, and how to connect on each edition.",
        [
            (
                "Software",
                f"> **[Paper](https://papermc.io)** {SERVER_VERSION} — the server\n"
                "> **[Geyser](https://geysermc.org)** — lets Bedrock players in\n"
                "> **[ViaVersion](https://modrinth.com/plugin/viaversion)** and "
                "ViaBackwards — translate older Java clients",
            ),
            (
                "Java Edition",
                f"> **{JAVA_SUPPORTED_RANGE}** — newer releases are refused\n"
                f"> On something newer? Add a **{SERVER_VERSION}** entry under "
                "**Installations** in the launcher\n\n"
                "Add the server under **Multiplayer → Add Server**\n"
                f"```text\n{java_address}\n```",
            ),
            (
                "Bedrock Edition",
                "> Any current version, from phone, console, tablet or Windows\n"
                "> Add it as an external server with both values below\n\n"
                "**Address**\n"
                f"```text\n{bedrock_address}\n```\n"
                "**Port**\n"
                f"```text\n{bedrock_port}\n```",
            ),
        ],
    )


PAGES: dict[str, tuple[str, Callable[[Optional[object]], discord.Embed]]] = {
    "commands": ("Commands", lambda _settings: commands_embed()),
    "clans": ("Clans", lambda _settings: clans_embed()),
    "levels": ("Levels & Perks", lambda _settings: levels_embed()),
    "boosting": ("Boosting", lambda _settings: boosting_embed()),
    "mods": ("Mods & Voice Chat", lambda _settings: mods_embed()),
    "rules": ("Rules", lambda _settings: rules_embed()),
    "versions": ("Server & Versions", technical_embed),
}

#: Pages whose embed points at an attachment rather than a hosted image. An
#: ephemeral reply has to carry the file or Discord draws a broken image.
PAGE_FILES: dict[str, Callable[[], discord.File]] = {
    "rules": rules_image_file,
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
    "clans": {
        "roles": ("Roles", clans_roles_embed),
        "joining": ("Joining", clans_joining_embed),
        "leaving": ("Leaving", clans_leaving_embed),
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
        attachment = PAGE_FILES.get(self.page)
        if attachment is not None:
            payload["file"] = attachment()
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
