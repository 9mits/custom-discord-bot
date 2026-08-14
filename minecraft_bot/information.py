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
    embed = _page(
        "Information",
        "",
        [
            (
                "The server",
                "A survival Minecraft server where teamwork, competition, PvP, "
                "building and peaceful survival all sit side by side.\n\n"
                "Play it your way — team up, compete, fight, build, or simply "
                "enjoy the world.\n\n"
                "> **Crossplay** — Java and Bedrock share one world",
            ),
            (
                "Client versions",
                f"> **Java** — {JAVA_SUPPORTED_RANGE}\n"
                "> **Bedrock** — any current version\n\n"
                f"The server runs **{SERVER_VERSION}**. Older Java clients are "
                "translated automatically; newer ones are blocked.",
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
                "> `/clans icon <url>` sets the image shown beside your clan on the "
                "Discord leaderboard, and `/clans icon clear` puts the default "
                "back. Leaders and clan staff can both set it.\n"
                "> \n"
                "> It must be a direct address ending in `.png`, `.jpg`, `.gif` or "
                "`.webp`. Avoid Discord attachment links: those expire after about "
                "a day and your clan would quietly lose its picture.",
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
                "> The **leader cannot be kicked** by anyone\n\n"
                "Promoting somebody puts them beyond everyone's reach but yours.",
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
                f"> It expires after **{CLAN_INVITE_EXPIRY_MINUTES} minutes**\n\n"
                "Answer with `/clans accept` or `/clans decline`.",
            ),
            (
                "Starting your own",
                "> `/clans create <name>` — founds it and makes you leader\n"
                "> The name must not already be taken\n"
                "> `/clans list` — see what already exists\n\n"
                f"Colours: {', '.join(CLAN_THEME_COLOURS)}.",
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
                "> `/clans leave` — immediate, no confirmation asked\n\n"
                "You keep everything you own. Only the tag and the damage "
                "immunity go with it.",
            ),
            (
                "The leader cannot simply leave",
                "> **Transfer or disband first** — there is no other way out\n\n"
                "This stops a clan being stranded with a full roster and nobody "
                "able to run it.",
            ),
            (
                "Handing it over",
                "> `/clans transfer <player>` — they become leader\n"
                "> You stay in the clan as **staff**, not removed\n\n"
                "You keep invite and kick; renaming, promoting and disbanding "
                "pass to them.",
            ),
            (
                "Disbanding",
                "> `/clans disband` — leader only, cannot be undone\n\n"
                "It closes the clan for everyone at once, not just for you.",
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
        "Chatting in text channels and talking in voice earns Discord levels, and "
        "levels become permanent bonuses in Minecraft. Nothing is bought or claimed.",
        [
            (
                "What each milestone gives you",
                _milestone_rewards()
                + "\n\n*The figure beside each role is your **running total**, not "
                "an extra on top — rewards build up as you climb.*",
            ),
            (
                "It all stacks",
                "Milestones add up as you climb, and boosting stacks on top of "
                "them rather than replacing anything.\n\n"
                f"> **Boosting** — +{BOOSTER_EXTRA_HEARTS} heart and "
                f"+{BOOSTER_DAMAGE_PERCENT}% damage, on top of your level\n"
                f"> **Maximum** — {_hearts(max_hearts + BOOSTER_EXTRA_HEARTS)} and "
                f"+{combined_damage}% damage",
            ),
            (
                "Checking yours",
                "The sidebar shows yours in game, or run `/perks` any time.\n\n"
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
        "Boosting the Discord server adds bonuses on top of your level rewards. "
        "It never replaces them.",
        [
            (
                "What boosting adds",
                f"> **+{BOOSTER_EXTRA_HEARTS} extra heart**\n"
                f"> **+{BOOSTER_DAMAGE_PERCENT}% damage**\n"
                f"> **Hunger drains {BOOSTER_HUNGER_REDUCTION_PERCENT}% more "
                "slowly**",
            ),
            (
                "How it stacks with your level",
                "> These are added to whatever your level already earned you, not "
                "given instead of it. The two damage bonuses add together rather "
                f"than multiplying, so level 50 (+{ELITE_DAMAGE_PERCENT}%) "
                f"alongside boosting (+{BOOSTER_DAMAGE_PERCENT}%) comes to "
                f"**+{combined_damage}%** and not +26.5%.\n"
                "> \n"
                f"> A level 50 player who boosts therefore holds "
                f"**{_hearts(max_hearts + BOOSTER_EXTRA_HEARTS)}** and "
                f"**+{combined_damage}% damage**, which is the highest anyone can "
                "reach.",
            ),
            (
                "If you stop boosting",
                "> Only these three bonuses go away. Your level rewards, your "
                "rank, your clan and everything you have built are completely "
                "untouched.",
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
                f"> {mod_link('Simple Voice Chat')} carries proximity voice, so "
                "you hear whoever is standing near you and nobody else. Install "
                "the build that matches the Minecraft version you play on — the "
                "download page lists one for each.",
            ),
            (
                "Permitted on Java",
                "Anything that changes how the game looks or runs, without telling "
                "you something you could not have seen yourself. These are "
                "examples rather than the whole list:\n\n"
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
                "Clients",
                f"Launchers like {mod_link('Lunar Client')} and "
                f"{mod_link('Feather')} are fine, and bundle most of the "
                "performance and quality-of-life mods above for you.\n\n"
                "> Whatever the client bundles still has to follow the rules "
                "below. A launcher does not make a banned module allowed.",
            ),
            (
                "Prohibited on every edition",
                "Anything that shows you what you could not have seen, or does "
                "what you could not have done unaided. The server runs an "
                "anticheat, so this is checked rather than merely asked for.\n\n"
                "> X-ray · freecam · baritone · tracers · kill aura · reach "
                "modification",
            ),
            (
                "Bedrock",
                "> Bedrock does not support client mods at all, so voice chat, "
                "minimaps and performance mods are simply unavailable there. "
                "Marketplace texture packs are fine, since they only change how "
                "the game looks. If you want to talk to people while you play, "
                "join a Discord voice channel instead.",
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
                f"> The server runs **[Paper](https://papermc.io)** "
                f"{SERVER_VERSION}, with **[Geyser](https://geysermc.org)** "
                "letting Bedrock players in and "
                "**[ViaVersion](https://modrinth.com/plugin/viaversion)** "
                "alongside ViaBackwards translating older Java clients down to "
                "it. All three run on our side, so there is nothing for you to "
                "install.",
            ),
            (
                "Java Edition",
                f"> Any version from **{JAVA_SUPPORTED_RANGE}** will connect. "
                f"Newer releases are refused, so if your launcher has moved past "
                f"{SERVER_VERSION} you will need to create a **{SERVER_VERSION}** "
                "entry under **Installations** and play on that instead.\n\n"
                "Add the server under **Multiplayer → Add Server**:\n"
                f"```text\n{java_address}\n```",
            ),
            (
                "Bedrock Edition",
                "> Join on the current release from a phone, console, tablet or "
                "Windows by adding an external server with both of the values "
                "below.\n\n"
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
                "> An application expires when one of its steps is left unfinished "
                "for too long. Nothing is lost when that happens: press **Apply** "
                f"again in {_apply_here(settings)}, or run `/minecraft account` "
                "first if you would like to see exactly where it stopped.",
            ),
            (
                "Disconnected on first join",
                "> That is meant to happen. Your first connection exists only to "
                "prove the account is yours, and it never lets you into the world "
                "regardless of whether anything went wrong.",
            ),
            (
                "Verified but no form submitted",
                "> Look for the **Continue Application** button in your direct "
                f"messages, or press **Apply** again in {_apply_here(settings)}. "
                "Either one picks up from where you stopped rather than starting "
                "over.",
            ),
            (
                "Incorrect username submitted",
                "> Press **Apply** again and it will offer you **Cancel Pending "
                "Verification**, or run `/minecraft cancel` if you would rather do "
                "it directly. Once cancelled, apply again with the correct name.",
            ),
            (
                "No direct message received",
                "> Turn on direct messages from server members and then check "
                "`/minecraft account`. Anything that failed to reach you is "
                "retried automatically, so you should not need to ask for it "
                "again.",
            ),
            (
                "Playing on both editions",
                "> You are welcome to. Each account has to apply separately, "
                "because each one is verified on its own — there is no way to link "
                "a second account to an application you have already finished.",
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
