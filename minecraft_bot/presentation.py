"""Discord presentation helpers for Minecraft applications."""

from __future__ import annotations

from datetime import datetime, timezone
from pathlib import Path
from typing import Optional
from urllib.parse import quote

import discord

from .models import ApplicationStatus, MinecraftApplication


BRAND_NAME = "Mysterious SMP X"
THEME_COLOUR = discord.Colour.from_rgb(255, 153, 0)
SUCCESS_COLOUR = discord.Colour.from_rgb(87, 242, 135)
ERROR_COLOUR = discord.Colour.from_rgb(237, 66, 69)
LOGO_FILENAME = "mysterious_smp_x_logo.png"
LOGO_PATH = Path(__file__).resolve().parent.parent / "assets" / "minecraft" / LOGO_FILENAME
LOGO_ATTACHMENT_URI = f"attachment://{LOGO_FILENAME}"
ICON_FILENAME = "mysterious_smp_x_icon.png"
ICON_PATH = Path(__file__).resolve().parent.parent / "assets" / "minecraft" / ICON_FILENAME
ICON_ATTACHMENT_URI = f"attachment://{ICON_FILENAME}"
FOOTER_FILENAME = "mysterious_smp_x_footer.png"
FOOTER_PATH = Path(__file__).resolve().parent.parent / "assets" / "minecraft" / FOOTER_FILENAME
FOOTER_ATTACHMENT_URI = f"attachment://{FOOTER_FILENAME}"
FOOTER_ICON_URL = (
    "https://raw.githubusercontent.com/9mits/custom-discord-bot/main/"
    f"assets/minecraft/{FOOTER_FILENAME}"
)
RULES_FILENAME = "mysterious_smp_x_rules.png"
RULES_PATH = Path(__file__).resolve().parent.parent / "assets" / "minecraft" / RULES_FILENAME
RULES_ATTACHMENT_URI = f"attachment://{RULES_FILENAME}"
VERIFY_FILENAME = "mysterious_smp_x_verify.png"
VERIFY_PATH = Path(__file__).resolve().parent.parent / "assets" / "minecraft" / VERIFY_FILENAME
VERIFY_ATTACHMENT_URI = f"attachment://{VERIFY_FILENAME}"
ABOUT_FILENAME = "mysterious_smp_x_about.png"
ABOUT_PATH = Path(__file__).resolve().parent.parent / "assets" / "minecraft" / ABOUT_FILENAME
ABOUT_ATTACHMENT_URI = f"attachment://{ABOUT_FILENAME}"
APPLY_FILENAME = "mysterious_smp_x_apply.png"
APPLY_PATH = Path(__file__).resolve().parent.parent / "assets" / "minecraft" / APPLY_FILENAME
APPLY_ATTACHMENT_URI = f"attachment://{APPLY_FILENAME}"
MARK_FILENAME = "mysterious_smp_x_mark.png"
MARK_PATH = Path(__file__).resolve().parent.parent / "assets" / "minecraft" / MARK_FILENAME
MARK_ATTACHMENT_URI = f"attachment://{MARK_FILENAME}"
MINECRAFT_HEAD_URL = "https://mc-heads.net/head/{identifier}/128.png"
BEDROCK_NAME_HEAD_URL = "https://api.mcheads.org/head/.{identifier}/128"
#: A remote copy of the mark, for embeds that are not sent with an attachment —
#: an ephemeral reply cannot carry one, so attachment:// silently renders nothing.
MARK_ICON_URL = (
    "https://raw.githubusercontent.com/9mits/custom-discord-bot/main/"
    f"assets/minecraft/{MARK_FILENAME}"
)
#: Floodgate hands Bedrock players a UUID whose first eight bytes are zero. The
#: Java head services cannot resolve those, so they are looked up by name instead.
_BEDROCK_UUID_PREFIX = "0" * 16


def head_url(minecraft_uuid: str, username: str = "") -> str:
    """The head image for a player, on either edition."""
    compact = str(minecraft_uuid or "").replace("-", "").lower()
    if compact.startswith(_BEDROCK_UUID_PREFIX):
        # Geyser prefixes Bedrock gamertags with a dot, which the URL already has.
        name = username[1:] if username.startswith(".") else username
        if name:
            return BEDROCK_NAME_HEAD_URL.format(identifier=quote(name, safe=""))
    return MINECRAFT_HEAD_URL.format(identifier=quote(minecraft_uuid, safe=""))

#: How the server is described, in the one place both panels read it from. The
#: application panel and the information panel would otherwise pitch the server
#: two different ways to the same person, before and after they joined.
SERVER_TAGLINE_PARAGRAPHS = (
    "The official Minecraft SMP of the Mysterious Girlfriend X community — a "
    "survival server where teamwork, competition, PvP, building and peaceful "
    "survival all come together.",
    "Play it your way: team up, compete, fight, build, or simply enjoy the world.",
)
SERVER_TAGLINE = "\n\n".join(SERVER_TAGLINE_PARAGRAPHS)

#: The version the Paper server itself runs. ViaBackwards translates older Java
#: clients down to it and Geyser handles Bedrock, so player-facing text should
#: always pair this with the supported range below rather than stand alone.
SERVER_VERSION = "1.21.1"
#: Every version joins: ViaBackwards translates older clients down, ViaVersion
#: translates newer ones up. The cap that used to sit here was removed on
#: 2026-08-15 — it could not be applied to Java alone, because Geyser injects
#: Bedrock players at the newest Java protocol, so it was turning away every
#: mobile player too. GrimAC polices up-translated sessions regardless.
JAVA_SUPPORTED_RANGE = "1.8 and newer"

#: Official download pages, used to hyperlink mod and version names in embeds.
#: Only well-known official pages belong here — never guess a link.
MOD_LINKS: dict[str, str] = {
    "Simple Voice Chat": "https://modrinth.com/plugin/simple-voice-chat",
    "Sodium": "https://modrinth.com/mod/sodium",
    "Lithium": "https://modrinth.com/mod/lithium",
    "Iris Shaders": "https://modrinth.com/mod/iris",
    "OptiFine": "https://optifine.net/downloads",
    "Xaero's Minimap": "https://modrinth.com/mod/xaeros-minimap",
    "JourneyMap": "https://modrinth.com/mod/journeymap",
    "Litematica": "https://modrinth.com/mod/litematica",
    "AppleSkin": "https://modrinth.com/mod/appleskin",
    "Fabric": "https://fabricmc.net/use/installer/",
    "Lunar Client": "https://www.lunarclient.com",
    "Feather": "https://feathermc.com",
    "Minecraft": "https://www.minecraft.net/download",
}


def mod_link(name: str) -> str:
    """The mod name as a bold Discord hyperlink to its official download page."""
    url = MOD_LINKS.get(name)
    return f"**[{name}]({url})**" if url else name


def _safe(value: object, limit: int = 1000) -> str:
    text = discord.utils.escape_markdown(str(value or "Not provided"), as_needed=True)
    return text[:limit]


def minecraft_head_url(
    application: MinecraftApplication,
    *,
    allow_claimed_username: bool = False,
) -> Optional[str]:
    identifier = str(application.minecraft_uuid or "").strip()
    if identifier:
        return MINECRAFT_HEAD_URL.format(identifier=quote(identifier, safe=""))
    if not allow_claimed_username:
        return None
    username = str(application.verified_username or application.claimed_username or "").strip()
    if not username:
        return None
    template = BEDROCK_NAME_HEAD_URL if application.edition.value == "BEDROCK" else MINECRAFT_HEAD_URL
    return template.format(identifier=quote(username, safe=""))


def _set_minecraft_thumbnail(embed: discord.Embed, thumbnail_url: Optional[str]) -> discord.Embed:
    if thumbnail_url:
        embed.set_thumbnail(url=thumbnail_url)
    return embed


def brand_logo_file() -> discord.File:
    return discord.File(
        LOGO_PATH,
        filename=LOGO_FILENAME,
        description=f"{BRAND_NAME} logo",
    )


def brand_mark_file() -> discord.File:
    """The standalone X, which reads better than the wordmark at thumbnail size."""
    return discord.File(
        MARK_PATH,
        filename=MARK_FILENAME,
        description=f"{BRAND_NAME} mark",
    )


def brand_icon_file() -> discord.File:
    return discord.File(
        ICON_PATH,
        filename=ICON_FILENAME,
        description=f"{BRAND_NAME} icon",
    )


def brand_footer_file() -> discord.File:
    return discord.File(
        FOOTER_PATH,
        filename=FOOTER_FILENAME,
        description=f"{BRAND_NAME} footer icon",
    )


def rules_image_file() -> discord.File:
    return discord.File(
        RULES_PATH,
        filename=RULES_FILENAME,
        description=f"{BRAND_NAME} rules",
    )


def about_image_file() -> discord.File:
    return discord.File(
        ABOUT_PATH,
        filename=ABOUT_FILENAME,
        description=f"{BRAND_NAME} information mark",
    )


def apply_image_file() -> discord.File:
    return discord.File(
        APPLY_PATH,
        filename=APPLY_FILENAME,
        description=f"{BRAND_NAME} application mark",
    )


def verification_image_file() -> discord.File:
    return discord.File(
        VERIFY_PATH,
        filename=VERIFY_FILENAME,
        description=f"{BRAND_NAME} verification",
    )


def branded_send(embed: discord.Embed) -> dict[str, object]:
    return {"embed": embed}


def branded_edit(embed: discord.Embed) -> dict[str, object]:
    return {"embed": embed}


def application_panel_files() -> list[discord.File]:
    # The footer uses a hosted URL. Uploading the same icon without referencing
    # its attachment URI makes Discord render it as a loose file below the card.
    return [brand_logo_file()]


def _connection_blocks(settings) -> str:
    return (
        "**Java Edition — PC/Mac launcher**\n"
        "> Choose **Multiplayer → Add Server**, then paste this address. Java players do not use the Bedrock port.\n"
        f"```text\n{settings.java_address}\n```\n"
        "**Bedrock Edition — phone, console, or Windows**\n"
        "> Add an external server using both the address and port below.\n"
        "**Address**\n"
        f"```text\n{settings.bedrock_address}\n```\n"
        "**Port**\n"
        f"```text\n{settings.bedrock_port}\n```"
    )


def quote_block(text: str) -> str:
    """Renders a block of copy as a Discord quote.

    The quote bar is what separates one section from the next visually; without
    it a page of headings and paragraphs runs together into a wall.

    Three rules that are easy to get wrong. A blank line inside a quote ends it, so
    blank lines are quoted too and the block stays whole. That marker has to be
    "> " and not ">": Discord only starts a quote on the bracket *and a space*, so
    a bare one is printed as a literal > in the middle of the block. And a fenced
    code block cannot be quoted — the fence markers and the content both end up
    inside the quote and Discord prints the markers literally — so any value
    containing one is left exactly as it is.
    """
    body = str(text)
    if "```" in body:
        return body
    lines = []
    for line in body.splitlines():
        stripped = line.strip()
        if not stripped:
            lines.append("> ")
        elif stripped.startswith(">"):
            lines.append(line)
        else:
            lines.append(f"> {line}")
    return "\n".join(lines)


def _panel_embed(title: str, description: str) -> discord.Embed:
    embed = discord.Embed(title=title, colour=THEME_COLOUR)
    if description:
        embed.description = description
    embed.set_footer(text=BRAND_NAME, icon_url=FOOTER_ICON_URL)
    return embed


#: Each feature as (name, one line on what it is). Someone reading this is
#: deciding whether the server suits them, so it lists what is actually here
#: rather than describing the atmosphere twice. How any of it works belongs in
#: the information panel they get after acceptance.
SERVER_FEATURES: tuple[tuple[str, str], ...] = (
    ("Clans", "Your own name, tag and colour, with no friendly fire"),
    ("Levels", "Chatting in Discord earns permanent extra hearts and damage"),
    ("Homes and travel", "Save your spots and teleport to friends"),
    ("Voice chat", "Proximity voice with whoever is standing near you"),
    ("Leaderboards", "Richest, kills, playtime, blocks mined and distance walked"),
    ("Crossplay", "Java and Bedrock in one shared world"),
)


def application_welcome_embed() -> discord.Embed:
    """What the server is. The first thing anybody sees, so it sells nothing else."""
    welcome = _panel_embed(
        "Welcome to Mysterious SMP X",
        # The partnership line stays unquoted so it reads as a header above the
        # quote rather than the first line of it.
        "**Mysterious Girlfriend X Discord, in partnership with r/MysteriousGirlfriendX.**\n\n"
        + quote_block(SERVER_TAGLINE),
    )
    # Inline fields lay the features out in columns, matching the Contact Staff
    # panel. Each feature names itself, so the showcase needs no header of its
    # own; keep the lines short enough not to wrap into a tall column.
    for name, line in SERVER_FEATURES:
        welcome.add_field(name=name, value=line, inline=True)
    welcome.add_field(
        name="Joining",
        value=(
            "> A private server: everyone applies and is reviewed before "
            "joining. Acceptance is intentionally straightforward — if you would "
            "like to play, apply."
        ),
        inline=False,
    )
    welcome.set_image(url=LOGO_ATTACHMENT_URI)
    return welcome


def application_guide_embed() -> discord.Embed:
    """What the server is actually like, for somebody deciding whether to apply.

    Laid out like the information panel so the two feel like one product, but they
    answer different questions. Information *teaches* accepted members how to play —
    commands, limits, costs. This *explains*: what a clan is for, that you can save
    places and travel to friends, what levelling earns you. The pages behind these
    buttons live in `about.py` and never name a command, because a command is no use
    to somebody who cannot join yet.
    """
    embed = _panel_embed("Before You Apply", "")
    embed.add_field(
        name="Can I play on my version?",
        value=(
            f"> **Java** — {JAVA_SUPPORTED_RANGE}\n"
            "> **Bedrock** — any current version, on phone, console or Windows\n"
            f"> The server runs **{SERVER_VERSION}** and translates in both "
            "directions, so join on whatever you already play."
        ),
        inline=False,
    )
    embed.add_field(
        name="Read the rules first",
        value=(
            "> You accept them as part of applying, and they are what staff "
            "enforce once you are in."
        ),
        inline=False,
    )
    embed.set_thumbnail(url=ABOUT_ATTACHMENT_URI)
    return embed


def application_apply_embed() -> discord.Embed:
    """The step-by-step, kept on its own message so the button stands alone."""
    embed = _panel_embed(
        "Apply to Mysterious SMP X",
        "Applying takes a few minutes and is completed entirely within Discord.\n\n"
        "**How it works**\n"
        "> **1.** Press **Apply** and accept the server rules.\n"
        "> **2.** Enter your exact Java username or Xbox gamertag.\n"
        "> **3.** Join the server once to verify. You will be disconnected.\n"
        "> **4.** Return to Discord and complete a short form.\n"
        "> **5.** Staff review it and send the outcome by direct message.\n\n"
        "**Before you begin**\n"
        "> Enable direct messages from server members so the bot can reach you.\n"
        "> Entered the wrong username? Press **Apply** again to cancel privately.",
    )
    embed.set_thumbnail(url=APPLY_ATTACHMENT_URI)
    return embed


#: Each rule as (heading, body). Held as data so the reference panel and the
#: application agreement can never show different rules, and so tests can walk
#: them without parsing prose.
SERVER_RULES: tuple[tuple[str, str], ...] = (
    (
        "1. Do not grief",
        "Destroying, defacing, burning or flooding another player's base, farm, "
        "build or animals is prohibited — whether or not it is claimed, locked, "
        "lit or occupied. A build that looks abandoned still belongs to someone. "
        "Damage caused during an agreed fight must be repaired in full, by you, "
        "before you log off.",
    ),
    (
        "2. Theft has limits",
        "- **Fair** — items taken from someone who is an active party to a "
        "declared conflict\n"
        "- **Griefing** — emptying storage, clearing shulkers or ender chests, or "
        "taking from anyone with no part in the conflict\n\n"
        "An unlocked chest is not an invitation, and being offline is not consent.",
    ),
    (
        "3. Respect spawn and shared builds",
        "Spawn, public farms, roads, portals and community projects are neutral "
        "ground. Do not build over, claim, dismantle or place traps within them, "
        "and do not fight at spawn regardless of who started it.",
    ),
    (
        "4. Keep PvP fair",
        "- **Allowed** — fighting, ambushes and declared wars\n"
        "- **Not allowed** — spawn-killing, corpse camping, killing the same "
        "player on sight repeatedly, or pursuing someone who has clearly "
        "disengaged\n\n"
        "Logging out to escape a fight is treated as the death you avoided.",
    ),
    (
        "5. Keep conflict proportional",
        "A prank or a stolen item never justifies destroying a base. Retaliation "
        "must leave the other player a fair chance to respond, and must stop when "
        "they disengage. Whether an escalation was proportional is judged by "
        "staff, not by the person escalating.",
    ),
    (
        "6. Respect other players",
        "Harassment, slurs, discrimination, sexual content, threats and sharing "
        "someone's personal information are prohibited everywhere — chat, signs, "
        "books, item names and builds included. These are acted on immediately, "
        "are never excused as roleplay, and do not require the target to complain "
        "first.",
    ),
    (
        "7. Keep it in character",
        "What happens in Minecraft stays in Minecraft. Conflict belongs to the "
        "story rather than to the people playing, and never follows anyone into "
        "Discord or anywhere else.\n"
        "- **In character** — alliances, rivalries, betrayal\n"
        "- **Not** — real arguments, grudges carried outside the game, personal "
        "attacks",
    ),
    (
        "8. Do not cheat",
        "Hacked clients, duping and exploits are banned on sight. Whatever it is "
        "called, a modification is cheating if it does any of the following:\n"
        "- **Shows what you could not see** — X-ray, ore and cave finders, "
        "freecam, tracers, player radar\n"
        "- **Plays for you** — kill aura, aim assist, auto-clickers, auto-walk\n"
        "- **Changes what your character can do** — extra reach, speed, flight, "
        "no fall damage\n\n"
        "Not knowing what your client bundles is not a defence.",
    ),
    (
        "9. Permitted mods and launchers",
        "Performance, shader, mapping, building and quality-of-life mods are "
        "welcome, as are custom launchers such as Lunar Client and Feather. Two "
        "conditions apply:\n"
        "- Minimaps must have cave mapping and player radar turned off\n"
        "- A launcher bundling anything from rule 8 does not make it permitted",
    ),
    (
        "10. Report exploits rather than using them",
        "If you find a duplication bug, a way through a protection, or anything "
        "the server clearly did not intend, tell staff. Using it, profiting from "
        "it before reporting it, or passing it to anyone else is treated as "
        "cheating.",
    ),
    (
        "11. One account per player",
        "Alternate accounts are prohibited when used to evade a punishment, "
        "bypass a whitelist decision, or claim a second set of perks. Do not "
        "share your account: anything done on it is your responsibility.",
    ),
    (
        "12. Protect the server",
        "Ordinary farms are fine. Lag machines, crash exploits, chunk bans and "
        "anything else built or run to strain server stability are prohibited, "
        "including work you did not realise would cause it once staff have asked "
        "you to stop.",
    ),
    (
        "13. Staff decisions are final",
        "A loophole is not permission, and not having read a rule is not a "
        "defence. If you are unsure whether something is allowed, ask before "
        "doing it rather than afterwards. Staff may intervene in any conflict "
        "that stops being fair, and their ruling stands. Appeal in Discord, never "
        "in game.",
    ),
)

ENFORCEMENT_NOTE = (
    "Serious or repeated breaches result in removal from the server. Staff weigh "
    "intent, history and the harm caused, and may act on any of the above without "
    "waiting for a report."
)


def rules_embed(*, agreement: bool = False) -> discord.Embed:
    # Fields rather than one description: each rule gets a heading Discord draws
    # tightly above its own text, and the room to be specific enough to argue from.
    # Quoted at the call site rather than in info_embed, which every status and
    # error message in the bot also goes through.
    embed = info_embed(
        "Mysterious SMP X Rules & Agreement" if agreement else "Mysterious SMP X Rules",
        quote_block(
            "Read every rule below, then select **I Agree** to confirm that you "
            "understand and accept them."
            if agreement
            else "These rules apply to every Mysterious SMP X player."
        ),
    )
    for heading, body in SERVER_RULES:
        embed.add_field(name=heading, value=quote_block(body), inline=False)
    embed.add_field(
        name="Enforcement", value=quote_block(ENFORCEMENT_NOTE), inline=False
    )
    if agreement:
        embed.add_field(
            name="Agreement",
            value="By selecting **I Agree** you confirm that you have read these "
            "rules, will follow them, and accept that staff enforce them.",
            inline=False,
        )
    embed.set_image(url=RULES_ATTACHMENT_URI)
    return embed


def application_panel() -> discord.ui.View:
    """Just Apply. The reading lives on its own message above this one."""
    from .ui import ApplyButton

    view = discord.ui.View(timeout=None)
    view.add_item(ApplyButton())
    return view


def application_guide_view() -> discord.ui.View:
    """The information panel's own buttons, so both surfaces show the same pages.

    Everything except Link Your Other Edition, which needs an account that an
    applicant does not have yet.

    Imported inside the function because `information` reads its embeds back out
    of this module, and importing it at module scope would close the loop.
    """
    from .information import PAGES, InformationButton

    view = discord.ui.View(timeout=None)
    for page in PAGES:
        view.add_item(InformationButton(page))
    return view


def review_embed(
    application: MinecraftApplication,
    *,
    user: Optional[discord.User | discord.Member] = None,
    member: Optional[discord.Member] = None,
) -> discord.Embed:
    user_id = int(application.discord_user_id)
    creation = discord.utils.snowflake_time(user_id)
    display_name = user.name if user is not None else "Unknown user"
    mention = f"<@{user_id}>"
    status_title = application.status.value.replace("_", " ").title()
    embed = discord.Embed(
        title=f"Minecraft Application #{application.id}",
        description=f"**Applicant**\n{mention} · {_safe(display_name, 100)} · `{user_id}`",
        colour=THEME_COLOUR,
        timestamp=datetime.fromtimestamp(application.created_at, timezone.utc),
    )
    embed.add_field(name="Status", value=status_title, inline=True)
    embed.add_field(name="Edition", value=application.edition.value.title(), inline=True)
    embed.add_field(name="Claimed Username", value=f"`{_safe(application.claimed_username, 100)}`", inline=True)
    embed.add_field(name="Verified Username", value=f"`{_safe(application.verified_username, 100)}`", inline=True)
    embed.add_field(name="Minecraft UUID", value=f"`{_safe(application.minecraft_uuid, 100)}`", inline=True)
    if application.xuid:
        embed.add_field(name="Floodgate XUID", value=f"`{_safe(application.xuid, 100)}`", inline=True)
    embed.add_field(name="Discord Created", value=discord.utils.format_dt(creation, "F"), inline=True)
    joined = member.joined_at if member is not None else None
    embed.add_field(
        name="Joined Discord",
        value=discord.utils.format_dt(joined, "F") if joined else "Unavailable",
        inline=True,
    )
    embed.add_field(
        name="Why do you want to join?",
        value=_safe(application.answers.get("why"), 1000),
        inline=False,
    )
    embed.add_field(
        name="About the applicant",
        value=_safe(application.answers.get("about"), 1000),
        inline=False,
    )
    embed.add_field(
        name="Submitted",
        value=discord.utils.format_dt(datetime.fromtimestamp(application.created_at, timezone.utc), "F"),
        inline=False,
    )
    if application.reviewed_at and application.reviewed_by:
        reviewed_at = datetime.fromtimestamp(application.reviewed_at, timezone.utc)
        embed.add_field(
            name="Staff Review",
            value=f"Reviewed by <@{application.reviewed_by}> · {discord.utils.format_dt(reviewed_at, 'F')}",
            inline=False,
        )
    if application.status is ApplicationStatus.DENIED and application.applicant_reason:
        embed.add_field(name="Applicant-Facing Reason", value=_safe(application.applicant_reason), inline=False)
    _set_minecraft_thumbnail(embed, minecraft_head_url(application))
    embed.set_footer(text=BRAND_NAME, icon_url=FOOTER_ICON_URL)
    return embed


def info_embed(title: str, description: str, *, success: bool = False, error: bool = False) -> discord.Embed:
    embed = discord.Embed(
        title=title,
        description=description,
        colour=THEME_COLOUR,
        timestamp=discord.utils.utcnow(),
    )
    embed.set_footer(text=BRAND_NAME, icon_url=FOOTER_ICON_URL)
    return embed


def verification_embed(application: MinecraftApplication, settings) -> discord.Embed:
    expires_at = datetime.fromtimestamp(application.verification_expires_at, timezone.utc)
    if application.auto_detect_edition:
        connection = _connection_blocks(settings)
    elif application.edition.value == "JAVA":
        connection = (
            "**Java Edition — PC/Mac launcher**\n"
            "> Choose **Multiplayer → Add Server**. Do not enter a separate Bedrock port.\n"
            f"```text\n{settings.java_address}\n```"
        )
    else:
        connection = (
            "**Bedrock Edition — phone, console, or Windows**\n"
            "> Add an external server using both values below.\n"
            "**Address**\n"
            f"```text\n{settings.bedrock_address}\n```\n"
            "**Port**\n"
            f"```text\n{settings.bedrock_port}\n```"
        )
    embed = info_embed(
        "Verify Your Minecraft Account",
        "> First step: show us the account is really yours.\n\n"
        f"{connection}\n\n"
        f"**Complete before:** {discord.utils.format_dt(expires_at, 'R')}\n\n"
        "**What happens next**\n"
        "1. Add the server using the details above.\n"
        f"2. Connect once with the account named `{_safe(application.claimed_username, 100)}`.\n"
        "3. That first connection is turned away on purpose — it only proves ownership.\n"
        "4. Then come back to Discord and fill out the short application form. "
        "A DM with a button will be waiting for you.\n\n"
        "**Wrong username?** Press **Apply** again on the application panel to reveal the private "
        "**Cancel Pending Verification** option, or run `/minecraft cancel`, then apply again.",
    )
    embed.set_image(url=VERIFY_ATTACHMENT_URI)
    return embed


def application_card_files(application: MinecraftApplication) -> list[discord.File]:
    """Files the card's embed references, so an edit re-sends rather than strips them.

    Editing a message with ``attachments=[]`` removes the upload while the embed still
    points at ``attachment://``, which is what made the verify image vanish.
    """
    # Tolerates a partially built application: a missing status must not break the
    # card, and no attachment is the safe answer.
    if getattr(application, "status", None) is ApplicationStatus.PENDING_VERIFICATION:
        return [verification_image_file()]
    return []


def _status_label(status: ApplicationStatus) -> str:
    return status.value.replace("_", " ").title()


def _add_connection_fields(embed: discord.Embed, settings, edition=None) -> None:
    """Server addresses as their own fields, one per edition.

    Fields rather than more description text: an address is the one thing someone
    has to copy exactly, and it is far easier to find under its own heading than
    part-way down a paragraph.
    """
    if edition is None or edition.value == "JAVA":
        embed.add_field(
            name="Java — PC/Mac",
            value=(
                "Multiplayer → Add Server\n"
                f"```text\n{settings.java_address}\n```"
            ),
            inline=True,
        )
    if edition is None or edition.value == "BEDROCK":
        embed.add_field(
            name="Bedrock — mobile, console, Windows",
            value=(
                "Servers → Add Server\n"
                f"```text\n{settings.bedrock_address}\n```"
                f"```text\nPort {settings.bedrock_port}\n```"
            ),
            inline=True,
        )


def live_status_embed(application: MinecraftApplication, settings) -> discord.Embed:
    """The applicant's own card, rewritten in place as the application moves.

    Laid out as fields rather than one long description. The card is read at a
    glance — "where am I, and what do I do next" — and a paragraph made the
    applicant hunt for both.
    """
    status = application.status
    username = _safe(application.verified_username or application.claimed_username, 100)

    summary = ""
    next_action = ""
    show_deadline = False
    show_connection = False
    if status is ApplicationStatus.PENDING_VERIFICATION:
        summary = "> Join the Minecraft server once to prove the account is yours."
        next_action = (
            f"Log in as `{username}` using the address for your edition below. "
            "You will be disconnected with a confirmation — that is expected."
            if application.auto_detect_edition
            else f"Log in as `{username}` using the {application.edition.value.title()} "
            "address below. You will be disconnected with a confirmation — that is expected."
        )
        show_deadline = True
        show_connection = True
    elif status is ApplicationStatus.PENDING_APPLICATION:
        summary = "> Your account is verified. One short form left."
        next_action = (
            "Press **Continue Application** below and answer two questions. "
            "**Apply** on the application panel picks up where you left off."
        )
        show_deadline = True
    elif status is ApplicationStatus.PENDING_REVIEW:
        summary = "> Sent to staff. Nothing else for you to do."
        next_action = "You will get a DM as soon as staff decide."
    elif status is ApplicationStatus.APPROVAL_QUEUED:
        summary = "> Approved. Access is being applied to the server now."
        next_action = "This takes a moment. You will get a DM when it is ready."
    elif status is ApplicationStatus.APPROVED:
        if getattr(settings, "maintenance_mode", False):
            summary = "> You are accepted. The server is not open yet."
            next_action = (
                "You cannot join until it opens, even though you are accepted. "
                "You will be told the moment it does."
            )
        else:
            summary = "> Access is active. Join whenever you like."
            show_connection = True
    elif status is ApplicationStatus.DENIED:
        summary = "> Staff did not approve this application."
        next_action = application.applicant_reason or "No public reason was given."
    elif status is ApplicationStatus.EXPIRED:
        summary = "> This application ran out of time."
        next_action = (
            "Nobody joined the server in time to verify the account. Press **Apply** "
            "on the application panel to start again — nothing is lost."
            if not application.verified_at
            else "The written form was not finished in time. Press **Apply** on the "
            "application panel to start again — nothing is lost."
        )
    elif status is ApplicationStatus.CANCELLED:
        summary = "> This application was cancelled."
        next_action = "Press **Apply** on the application panel whenever you want to try again."
    else:
        summary = "> This Minecraft access record is no longer active."

    embed = info_embed("Your Minecraft Application", summary)
    embed.add_field(
        name="Status", value=f"#{application.id} · {_status_label(status)}", inline=True
    )
    embed.add_field(
        name="Account",
        value=f"{application.edition.value.title()} · `{username}`",
        inline=True,
    )
    if show_deadline:
        # Only read when it is shown: the later states have no deadline, and asking
        # for one there would couple the card to a field it does not use.
        deadline = datetime.fromtimestamp(application.verification_expires_at, timezone.utc)
        embed.add_field(
            name="Finish by",
            value=discord.utils.format_dt(deadline, "R"),
            inline=True,
        )
    if next_action:
        embed.add_field(name="What to do next", value=next_action, inline=False)
    if show_connection:
        _add_connection_fields(
            embed,
            settings,
            None if application.auto_detect_edition else application.edition,
        )
    if status is ApplicationStatus.PENDING_VERIFICATION:
        embed.set_image(url=VERIFY_ATTACHMENT_URI)
    return embed


def denial_embed(application: MinecraftApplication) -> discord.Embed:
    reason = _safe(
        getattr(application, "applicant_reason", None) or "No public reason was provided.",
        1000,
    ).replace("\n", "\n> ")
    return info_embed(
        "Minecraft Application Denied",
        "> Staff completed the review of your Minecraft application.\n\n"
        f"**Decision reason**\n> {reason}\n\n"
        "If you need clarification, contact the server team through the normal support channel. "
        "Do not submit repeated applications unless staff ask you to reapply.",
        error=True,
    )


def approval_embed(settings) -> discord.Embed:
    # Telling somebody their access is active while the server is holding every
    # login would read as a broken promise the moment they tried it.
    if getattr(settings, "maintenance_mode", False):
        return info_embed(
            "Minecraft Application Approved",
            "> Your application was approved and your place is kept.\n\n"
            "**The server is not open yet.** You cannot join until it opens, even "
            "though you are accepted — nothing is wrong with your account.\n\n"
            "You will be told here the moment it opens.",
            success=True,
        )
    return info_embed(
        "Minecraft Application Approved",
        "> Your application was approved and Minecraft access is now active.\n\n"
        + _connection_blocks(settings)
        + "\n\n**Before joining**\n"
        "- Use the address for your Minecraft edition.\n"
        "- Join with the same account you verified.\n"
        "- Keep this message for quick access later.",
        success=True,
    )


def application_dm_embed(
    application: MinecraftApplication,
    settings,
    notification: str,
) -> discord.Embed:
    if notification == "decision" and application.status is ApplicationStatus.APPROVED:
        embed = approval_embed(settings)
        embed.colour = SUCCESS_COLOUR
    elif notification == "decision" and application.status is ApplicationStatus.DENIED:
        embed = denial_embed(application)
        embed.colour = ERROR_COLOUR
    else:
        raise ValueError("Application does not have a DM notification for this state")
    embed.set_thumbnail(url=ICON_ATTACHMENT_URI)
    return embed


def application_log_embed(application: MinecraftApplication) -> discord.Embed:
    expires_at = datetime.fromtimestamp(application.verification_expires_at, timezone.utc)
    has_answers = bool(application.answers)
    embed = info_embed(
        f"Application Submitted #{application.id}"
        if has_answers
        else f"Application Started #{application.id}",
        f"> <@{application.discord_user_id}> completed the written application form."
        if has_answers
        else f"> <@{application.discord_user_id}> entered the Minecraft verification stage.",
    )
    embed.add_field(
        name="Edition",
        value="Detected on connection" if application.auto_detect_edition else application.edition.value.title(),
        inline=True,
    )
    embed.add_field(
        name="Claimed Username",
        value=f"`{_safe(application.verified_username or application.claimed_username, 100)}`",
        inline=True,
    )
    if has_answers and application.verified_at:
        verified_at = datetime.fromtimestamp(application.verified_at, timezone.utc)
        embed.add_field(
            name="Account Verified",
            value=discord.utils.format_dt(verified_at, "R"),
            inline=True,
        )
    else:
        embed.add_field(
            name="Verification Expires",
            value=discord.utils.format_dt(expires_at, "R"),
            inline=True,
        )
    if has_answers:
        embed.add_field(
            name="Why They Want to Join",
            value=_safe(application.answers.get("why"), 1000),
            inline=False,
        )
        embed.add_field(
            name="What They Would Bring",
            value=_safe(application.answers.get("about"), 1000),
            inline=False,
        )
    embed.add_field(
        name="Applicant",
        value=f"<@{application.discord_user_id}> · `{application.discord_user_id}`",
        inline=False,
    )
    return _set_minecraft_thumbnail(
        embed,
        minecraft_head_url(application, allow_claimed_username=True),
    )


def verification_log_embed(application: MinecraftApplication) -> discord.Embed:
    embed = info_embed(
        f"Account Verified #{application.id}",
        f"> <@{application.discord_user_id}> completed Minecraft ownership verification.",
        success=True,
    )
    embed.add_field(name="Edition", value=application.edition.value.title(), inline=True)
    embed.add_field(
        name="Verified Username",
        value=f"`{_safe(application.verified_username, 100)}`",
        inline=True,
    )
    embed.add_field(
        name="Minecraft UUID",
        value=f"`{_safe(application.minecraft_uuid, 100)}`",
        inline=False,
    )
    if application.xuid:
        embed.add_field(name="Floodgate XUID", value=f"`{_safe(application.xuid, 100)}`", inline=False)
    return _set_minecraft_thumbnail(embed, minecraft_head_url(application))


def decision_log_embed(application: MinecraftApplication) -> discord.Embed:
    status = application.status.value.replace("_", " ").title()
    embed = info_embed(
        f"Application {status} #{application.id}",
        f"> The application for <@{application.discord_user_id}> changed to **{status}**.",
    )
    embed.add_field(name="Edition", value=application.edition.value.title(), inline=True)
    embed.add_field(
        name="Minecraft Account",
        value=f"`{_safe(application.verified_username or application.claimed_username, 100)}`",
        inline=True,
    )
    if application.applicant_reason:
        embed.add_field(
            name="Applicant-Facing Reason",
            value=_safe(application.applicant_reason, 1000),
            inline=False,
        )
    return _set_minecraft_thumbnail(
        embed,
        minecraft_head_url(application, allow_claimed_username=True),
    )


def player_activity_embed(
    *,
    joined: bool,
    username: str,
    minecraft_uuid: str,
    edition: str,
    xuid: Optional[str] = None,
    discord_user_id: Optional[str] = None,
) -> discord.Embed:
    action = "Joined" if joined else "Left"
    embed = info_embed(
        f"Player {action} the Server",
        f"> `{_safe(username, 100)}` **{action.lower()}** the Minecraft server.",
    )
    embed.add_field(name="Edition", value=_safe(edition.title(), 50), inline=True)
    embed.add_field(name="Minecraft UUID", value=f"`{_safe(minecraft_uuid, 100)}`", inline=False)
    if xuid:
        embed.add_field(name="Floodgate XUID", value=f"`{_safe(xuid, 100)}`", inline=False)
    if discord_user_id:
        embed.add_field(
            name="Linked Discord Account",
            value=f"<@{discord_user_id}> · `{discord_user_id}`",
            inline=False,
        )
    return _set_minecraft_thumbnail(
        embed,
        MINECRAFT_HEAD_URL.format(identifier=quote(str(minecraft_uuid).strip(), safe="")),
    )
