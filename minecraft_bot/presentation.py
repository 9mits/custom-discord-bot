"""Discord presentation helpers for Minecraft verification and access."""

from __future__ import annotations

import io
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional
from urllib.parse import quote

import discord

from .models import AccessStatus, MinecraftAccess, ReverseLinkRequest


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
MINECRAFT_SKIN_URL = "https://mc-heads.net/body/{identifier}/160.png"
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
    identifier = minecraft_uuid or username
    return MINECRAFT_HEAD_URL.format(identifier=quote(identifier, safe=""))


def skin_url(minecraft_uuid: str, username: str = "") -> str:
    """A full player render, falling back to the username when UUID is absent."""
    compact = str(minecraft_uuid or "").replace("-", "").lower()
    identifier = minecraft_uuid or username
    if compact.startswith(_BEDROCK_UUID_PREFIX) and username:
        identifier = username[1:] if username.startswith(".") else username
    return MINECRAFT_SKIN_URL.format(identifier=quote(identifier, safe=""))

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
SERVER_VERSION = "1.21.11"
#: ViaBackwards covers the older Dialog-capable releases and ViaVersion translates
#: newer ones. The mandatory Java resource pack also starts at pack format 63, so
#: advertising pre-1.21.6 clients would promise an experience the pack cannot serve.
JAVA_SUPPORTED_RANGE = "1.21.6 and newer"

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
    application: MinecraftAccess,
    *,
    allow_claimed_username: bool = False,
) -> Optional[str]:
    identifier = str(application.minecraft_uuid or "").strip()
    username = str(application.verified_username or application.claimed_username or "").strip()
    if identifier:
        return head_url(identifier, username)
    if not allow_claimed_username or not username:
        return None
    if application.edition.value == "BEDROCK":
        return BEDROCK_NAME_HEAD_URL.format(identifier=quote(username, safe=""))
    return MINECRAFT_HEAD_URL.format(identifier=quote(username, safe=""))


def _set_minecraft_thumbnail(embed: discord.Embed, thumbnail_url: Optional[str]) -> discord.Embed:
    if thumbnail_url:
        embed.set_thumbnail(url=thumbnail_url)
    return embed


def brand_logo_file() -> discord.File:
    return _asset_file(LOGO_PATH, LOGO_FILENAME, f"{BRAND_NAME} logo")


def brand_mark_file() -> discord.File:
    """The standalone X, which reads better than the wordmark at thumbnail size."""
    return _asset_file(MARK_PATH, MARK_FILENAME, f"{BRAND_NAME} mark")


def brand_icon_file() -> discord.File:
    return _asset_file(ICON_PATH, ICON_FILENAME, f"{BRAND_NAME} icon")


def brand_footer_file() -> discord.File:
    return _asset_file(FOOTER_PATH, FOOTER_FILENAME, f"{BRAND_NAME} footer icon")


def rules_image_file() -> discord.File:
    return _asset_file(RULES_PATH, RULES_FILENAME, f"{BRAND_NAME} rules")


def about_image_file() -> discord.File:
    return _asset_file(ABOUT_PATH, ABOUT_FILENAME, f"{BRAND_NAME} information mark")


def apply_image_file() -> discord.File:
    return _asset_file(APPLY_PATH, APPLY_FILENAME, f"{BRAND_NAME} verification mark")


def verification_image_file() -> discord.File:
    return _asset_file(VERIFY_PATH, VERIFY_FILENAME, f"{BRAND_NAME} verification")


def _asset_file(path: Path, filename: str, description: str) -> discord.File:
    # Memory-backed attachments cannot leak an OS file descriptor if a Discord send
    # is cancelled before discord.py gets the opportunity to close the File wrapper.
    return discord.File(io.BytesIO(path.read_bytes()), filename=filename, description=description)


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


#: Compact feature tiles keep the front page lively while the buttons beneath it
#: carry the detailed handbook. Do not turn these back into prose.
SERVER_FEATURES: tuple[tuple[str, str], ...] = (
    ("Crossplay", "Java and Bedrock together"),
    ("Economy", "Shop, sell, auctions and bounties"),
    ("Clans", "Team up, level up and build together"),
    ("Levels", "Earn permanent hearts and damage"),
    ("Voice Chat", "Proximity voice in the world"),
    ("Leaderboards", "Compete for the server podium"),
)


def application_welcome_embed() -> discord.Embed:
    """A compact overview with visual feature tiles and handbook controls."""
    welcome = _panel_embed(
        "Welcome to Mysterious SMP X",
        "**Mysterious Girlfriend X Discord, in partnership with r/MysteriousGirlfriendX.**\n\n"
        "> Crossplay survival where you can build, compete, fight or play peacefully.\n"
        "> Griefing and raiding are allowed outside protected server builds.\n"
        f"> **Java:** {JAVA_SUPPORTED_RANGE}  •  **Bedrock:** current versions",
    )
    for name, line in SERVER_FEATURES:
        welcome.add_field(name=name, value=line, inline=True)
    welcome.set_image(url=LOGO_ATTACHMENT_URI)
    return welcome


def application_apply_embed(settings) -> discord.Embed:
    """One obvious action: join, then complete the three-step lobby flow."""
    embed = _panel_embed(
        "JOIN THE SERVER",
        "> **Join now.** Unlinked players enter a private verification lobby.\n\n"
        + _connection_blocks(settings)
        + "\n\n"
        "**Verify in 3 steps**\n"
        "> **1.** Join the server.\n"
        "> **2.** Type `/verify your_discord_username` in the lobby.\n"
        "> **3.** Open the newest bot DM and press **Yes, This Is Me**.\n"
        "> You will enter automatically. Keep Minecraft open.\n\n"
        "> **No DM?** Enable direct messages from server members and try again.",
    )
    embed.set_thumbnail(url=APPLY_ATTACHMENT_URI)
    return embed


#: Each rule as (heading, body). Held as data so the reference panel and the
#: application agreement can never show different rules, and so tests can walk
#: them without parsing prose.
SERVER_RULES: tuple[tuple[str, str], ...] = (
    (
        "1. Griefing is allowed",
        "- **Fair game** — player bases, farms, animals, clan builds, anything "
        "you find out there\n"
        "- **Off limits** — spawn, and any build marked as server-coordinated\n\n"
        "Raid it, burn it, flood it, take it apart. Claimed or not, occupied or "
        "not, and a build that looks abandoned is as fair a target as one that "
        "does not.\n\n"
        "This is deliberate. A world where anything can be lost is a world worth "
        "defending: it gives clans a reason to fortify, alliances a reason to "
        "mean something, and everyone a reason to log in and find out what "
        "changed overnight. The best stories this server has came from somebody "
        "losing something. Build accordingly.",
    ),
    (
        "2. Raiding and theft are fair game",
        "Chests, barrels, shulkers, storage rooms — if you can reach it, you can "
        "take it. An unlocked chest is an opportunity, not an oversight, and "
        "being offline is not protection.\n\n"
        "Hidden bases, decoys, traps and distance are your defence. Use them, and "
        "assume your rivals are using them too.",
    ),
    (
        "3. Server builds are the exception",
        "Spawn and anything officially marked as a server build — the hub, public "
        "roads and portals, event arenas, staff-run community projects — are "
        "never valid targets. Do not break, burn, flood, build over or trap "
        "them.\n\n"
        "Most are held by WorldGuard and will simply refuse your block. Some are "
        "not. A missing region is an oversight rather than permission: if a build "
        "is labelled server-coordinated, treat it as protected whether or not the "
        "plugin stops you. Do not fight at spawn either, regardless of who "
        "started it.",
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
        "5. Fight players, not people",
        "Griefing is aimed at builds and loot, because those can be rebuilt and "
        "retaken. It is not a way to drive somebody off the server. Singling out "
        "one player until they stop logging in is harassment however it is "
        "dressed up, and is judged on its effect rather than on what you meant "
        "by it.",
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
        "- **Plays for you** — kill aura, aim assist, auto-walk\n"
        "- **Changes what your character can do** — extra reach, speed, flight, "
        "no fall damage\n\n"
        "Macros and auto-clickers are allowed.\n\n"
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
        "11. Link as many accounts as you want",
        "You may link as many Java and Bedrock accounts as you like. Use "
        "**Link Other Accounts** in Discord, pick the edition, and verify each "
        "one the same way.\n\n"
        "Using another account to evade a punishment is still banned. Do not "
        "share an account: anything done on it is your responsibility.",
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
    """The optional Discord-first path beside the primary join-first instructions."""
    from .ui import VerifyButton

    view = discord.ui.View(timeout=None)
    view.add_item(VerifyButton())
    return view


def application_guide_view() -> discord.ui.View:
    """Every handbook button, kept under the compact welcome card."""
    from .information import PAGES, InformationButton

    view = discord.ui.View(timeout=None)
    for page in PAGES:
        view.add_item(InformationButton(page))
    return view


def info_embed(title: str, description: str, *, success: bool = False, error: bool = False) -> discord.Embed:
    embed = discord.Embed(
        title=title,
        description=description,
        colour=THEME_COLOUR,
        timestamp=discord.utils.utcnow(),
    )
    embed.set_footer(text=BRAND_NAME, icon_url=FOOTER_ICON_URL)
    return embed


def verification_embed(application: MinecraftAccess, settings) -> discord.Embed:
    expires_at = datetime.fromtimestamp(application.verification_expires_at, timezone.utc)
    connection = _connection_blocks(settings)
    embed = info_embed(
        "Verify Your Minecraft Account",
        "> First step: show us the account is really yours.\n\n"
        f"{connection}\n\n"
        f"**Complete before:** {discord.utils.format_dt(expires_at, 'R')}\n\n"
        "**What happens next**\n"
        "1. Add the server using the details above.\n"
        f"2. Connect once with the account named `{_safe(application.claimed_username, 100)}`.\n"
        "3. You will be let straight in, and your access is active from that moment.\n\n"
        "**Wrong username?** Press **Verify** on the panel to reveal the private "
        "**Cancel Pending Verification** option, or run `/minecraft cancel`, then start again.",
    )
    embed.set_image(url=VERIFY_ATTACHMENT_URI)
    return embed


def application_card_files(application: MinecraftAccess) -> list[discord.File]:
    """Files the card's embed references, so an edit re-sends rather than strips them.

    Editing a message with ``attachments=[]`` removes the upload while the embed still
    points at ``attachment://``, which is what made the verify image vanish.
    """
    # Tolerates a partially built application: a missing status must not break the
    # card, and no attachment is the safe answer.
    if getattr(application, "status", None) is AccessStatus.PENDING_VERIFICATION:
        return [verification_image_file()]
    return []


def _relative(timestamp: int) -> str:
    """A deadline as Discord's own relative time, for use inside a sentence."""
    return discord.utils.format_dt(
        datetime.fromtimestamp(int(timestamp), timezone.utc), "R"
    )


def _add_connection_fields(embed: discord.Embed, settings, edition=None) -> None:
    """Server addresses as their own fields. Always both editions.

    Adjacent code fences with no newline between them collapse in Discord, which
    is how the Bedrock port used to vanish from the live card.
    """
    del edition
    embed.add_field(
        name="Java — PC/Mac",
        value=(
            "Multiplayer → Add Server\n"
            f"```text\n{settings.java_address}\n```"
        ),
        inline=False,
    )
    embed.add_field(
        name="Bedrock — mobile, console, Windows",
        value=(
            "Servers → Add Server. Use both the address and the port.\n"
            f"**Address**\n```text\n{settings.bedrock_address}\n```\n"
            f"**Port**\n```text\n{settings.bedrock_port}\n```"
        ),
        inline=False,
    )


def live_status_embed(application: MinecraftAccess, settings) -> discord.Embed:
    """The applicant's own card, rewritten in place as the application moves.

    The title says where they are and the body says what happens next. Nothing
    else: the status and the account were shown as their own fields, which asked
    somebody to read a form to learn one sentence of news. The application number
    goes with them — it identifies the record to staff, not to the person waiting
    on it.

    Only the pending screen carries more: a deadline where it can run out and
    the server addresses where the player still has to connect.
    """
    status = application.status
    username = _safe(application.verified_username or application.claimed_username, 100)
    edition = application.edition.value.title()

    show_connection = False
    if status is AccessStatus.PENDING_VERIFICATION:
        # The deadline reads as part of the sentence rather than as a field of its
        # own. Discord renders it as "in 3 days", which only sits naturally after a
        # verb — "expires in 3 days" — and never under a heading like "Finish by".
        expires = _relative(application.verification_expires_at)
        edition_note = "" if application.auto_detect_edition else f" on {edition}"
        title = "Verify Your Account"
        body = (
            f"> Join the server using `{username}`{edition_note}.\n"
            "> You will be let straight in, and your access is active from that "
            "moment.\n"
            "\n"
            f"> This expires {expires}."
        )
        show_connection = True
    elif status is AccessStatus.VERIFIED:
        title = "Verification Successful!"
        body = f"> `{username}` is verified and your access is active."
    elif status is AccessStatus.EXPIRED:
        title = "Verification Expired"
        body = (
            "> You did not join in time, so this verification expired.\n"
            "> Press **Verify** on the panel to start again."
        )
    elif status is AccessStatus.CANCELLED:
        title = "Verification Cancelled"
        body = (
            "> Your verification was cancelled.\n"
            "> Press **Verify** on the panel to start again."
        )
    else:
        title = "Access Ended"
        body = "> This Minecraft access record is no longer active."

    # A body that carries its own quote markers decides its own layout, including
    # where the bar deliberately breaks. Everything else is quoted wholesale.
    embed = info_embed(title, body if body.startswith(">") else quote_block(body))
    if show_connection:
        _add_connection_fields(embed, settings)
    if status is AccessStatus.PENDING_VERIFICATION:
        embed.set_image(url=VERIFY_ATTACHMENT_URI)
    return embed


def approval_embed(settings, information_channel_id: int | str | None = None) -> discord.Embed:
    # Deliberately says nothing about a maintenance hold. It used to, and promised
    # a "you will be notified when it opens" message that nothing ever sent — and
    # a hold can be lifted minutes after the DM, which left the wrong copy sitting
    # in somebody's inbox for good. The server states its own closure, on the kick
    # screen, at the moment it is true.
    start_here = (
        f"> Read <#{information_channel_id}> for all server information, or use the "
        "[complete server guide](https://mysterioussmpx.blog/guide)."
        if information_channel_id
        else "> Read the [complete server guide](https://mysterioussmpx.blog/guide) "
        "for commands, rules, and everything you need to get started."
    )
    return info_embed(
        "Verification Successful!",
        "> Your Minecraft account is verified and your access is active.\n"
        "> Return to Minecraft—you will enter automatically.\n\n"
        "**Start Here**\n"
        + start_here,
        success=True,
    )


def reverse_link_request_embed(request: ReverseLinkRequest) -> discord.Embed:
    return info_embed(
        "Confirm Your Minecraft Account",
        f"> A player currently connected as **{request.current_username}** wants to "
        "link this Discord account.\n"
        "> Approve only if that is you. The request expires in 10 minutes.\n\n"
        f"**Edition:** {request.edition.value.title()}\n"
        f"**Minecraft account:** `{request.current_username}`\n\n"
        "> We will never ask for your Discord password, token, QR code, or login "
        "details. This button only confirms the account already signed into Discord.",
    )


def application_dm_embed(
    application: MinecraftAccess,
    settings,
    notification: str,
    *,
    information_channel_id: int | str | None = None,
) -> discord.Embed:
    if notification == "decision" and application.status is AccessStatus.VERIFIED:
        embed = approval_embed(settings, information_channel_id)
        embed.colour = SUCCESS_COLOUR
    else:
        raise ValueError("This access record has no DM notification for its state")
    embed.set_thumbnail(url=ICON_ATTACHMENT_URI)
    return embed


def application_log_embed(application: MinecraftAccess) -> discord.Embed:
    expires_at = datetime.fromtimestamp(application.verification_expires_at, timezone.utc)
    verified = application.status is AccessStatus.VERIFIED
    embed = info_embed(
        f"Access Granted #{application.id}"
        if verified
        else f"Verification Started #{application.id}",
        f"> <@{application.discord_user_id}> verified their account and can play."
        if verified
        else f"> <@{application.discord_user_id}> started Minecraft verification.",
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
    if verified and application.verified_at:
        verified_at = datetime.fromtimestamp(application.verified_at, timezone.utc)
        embed.add_field(
            name="Verified",
            value=discord.utils.format_dt(verified_at, "R"),
            inline=True,
        )
    else:
        embed.add_field(
            name="Verification Expires",
            value=discord.utils.format_dt(expires_at, "R"),
            inline=True,
        )
    embed.add_field(
        name="Member",
        value=f"<@{application.discord_user_id}> · `{application.discord_user_id}`",
        inline=False,
    )
    return _set_minecraft_thumbnail(
        embed,
        minecraft_head_url(application, allow_claimed_username=True),
    )


def verification_log_embed(application: MinecraftAccess) -> discord.Embed:
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
    return _set_minecraft_thumbnail(embed, head_url(str(minecraft_uuid or ""), username))
