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
MARK_FILENAME = "mysterious_smp_x_mark.png"
MARK_PATH = Path(__file__).resolve().parent.parent / "assets" / "minecraft" / MARK_FILENAME
MARK_ATTACHMENT_URI = f"attachment://{MARK_FILENAME}"
MINECRAFT_HEAD_URL = "https://mc-heads.net/head/{identifier}/128.png"
BEDROCK_NAME_HEAD_URL = "https://api.mcheads.org/head/.{identifier}/128"

#: The version the Paper server itself runs. ViaBackwards translates older Java
#: clients down to it and Geyser handles Bedrock, so player-facing text should
#: always pair this with the supported range below rather than stand alone.
SERVER_VERSION = "1.21.1"
#: Clients newer than the server are refused rather than translated up: GrimAC
#: cannot police a session it only sees through ViaVersion's up-translation, so
#: allowing them would leave the newest clients effectively unpoliced.
JAVA_SUPPORTED_RANGE = f"1.8 up to {SERVER_VERSION}"

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


def _panel_embed(title: str, description: str) -> discord.Embed:
    embed = discord.Embed(
        title=title,
        description=description,
        colour=THEME_COLOUR,
    )
    embed.set_footer(text=BRAND_NAME, icon_url=FOOTER_ICON_URL)
    return embed


def application_embeds() -> list[discord.Embed]:
    welcome = _panel_embed(
        "Welcome to Mysterious SMP X",
        "**Mysterious Girlfriend X Discord, in partnership with r/MysteriousGirlfriendX.**\n\n"
        "The official Minecraft SMP of the Mysterious Girlfriend X Discord community—a place "
        "to explore, build, battle, create stories, and enjoy the server together.\n\n"
        "> This is a private SMP, so every player applies and is reviewed before joining. "
        "Acceptance is intentionally approachable: if you want to play, we encourage you to apply.",
    )
    welcome.set_image(url=LOGO_ATTACHMENT_URI)
    apply = _panel_embed(
        "Apply to Mysterious SMP X",
        "Joining takes a few minutes and happens right here in Discord.\n\n"
        "**How it works**\n"
        "> 1. Press **Apply** and accept the server rules.\n"
        "> 2. Enter your exact Java username or Xbox gamertag.\n"
        "> 3. Join the Minecraft server once so we know the account is yours. "
        "That first connection is turned away on purpose — it only proves ownership.\n"
        "> 4. Come back to Discord and fill out a short application form.\n"
        "> 5. Staff review it, and you get your result in a DM either way.\n\n"
        "**Good to know**\n"
        "- Keep Discord DMs from server members enabled so the bot can reach you.\n"
        "- Entered the wrong username? Press **Apply** again for a private cancel option.",
    )
    return [welcome, apply]


def rules_embed(*, agreement: bool = False) -> discord.Embed:
    introduction = (
        "> Read every rule below. Select **I Agree** to confirm that you understand and accept "
        "them before opening the application form.\n\n"
        if agreement
        else "> These rules apply to every Mysterious SMP X player.\n\n"
    )
    ending = (
        "\n\n**Agreement**\nBy selecting **I Agree**, you confirm that you will follow these rules "
        "and understand that serious or repeated violations may result in loss of access."
        if agreement
        else ""
    )
    xaeros_link = mod_link("Xaero's Minimap")
    description = (
        introduction
        + "1. **Protect player builds** — Griefing active bases, storage, farms, or meaningful builds is not allowed. "
        "Minor damage during an agreed conflict must be repaired; lore is never permission to wipe someone's work.\n\n"
        "2. **Play fairly** — No hacked clients, x-ray, duping, exploits, or unfair advantages.\n\n"
        "3. **Keep client mods fair (Java)** — Mods that improve performance or comfort are welcome: "
        f"{mod_link('Sodium')}, {mod_link('Lithium')}, {mod_link('Iris Shaders')}, {mod_link('OptiFine')}, "
        f"{mod_link('AppleSkin')}, {mod_link('Simple Voice Chat')}, minimaps such as "
        f"{xaeros_link} or {mod_link('JourneyMap')} "
        f"(with cave mapping and player radar turned off), and {mod_link('Litematica')} for "
        "schematic building, including its auto-build printer. Anything that shows what you "
        "could not see yourself is not allowed: x-ray packs, freecam, baritone, and tracers.\n\n"
        "4. **Keep PvP fair** — Fighting, ambushes, and declared wars are allowed. Spawn-killing, combat logging, "
        "repeatedly targeting one player, or continuing after someone clearly disengages is not.\n\n"
        "5. **Build lore together** — Alliances, rivalries, wars, traps, theft, and betrayals may be part of the story "
        "when everyone involved still has a fair chance to play. Keep real arguments out of character.\n\n"
        "6. **Use proportional conflict** — A prank or stolen item does not justify destroying a base. Escalate through "
        "roleplay, leave evidence, and give other players a way to respond.\n\n"
        "7. **Protect server stability** — No lag machines, crash exploits, chunk bans, or destructive abuse.\n\n"
        "8. **Use common sense** — Loopholes do not excuse behavior that ruins the experience for others. Staff may "
        "step in when a conflict stops being fun or fair.\n\n"
        "**Have fun, create lore, and help make the server enjoyable for everyone.**"
        + ending
    )
    embed = info_embed(
        "Mysterious SMP X Rules & Agreement" if agreement else "Mysterious SMP X Rules",
        description,
    )
    embed.set_image(url=RULES_ATTACHMENT_URI)
    return embed


def application_panel() -> discord.ui.View:
    from .ui import ApplyButton, RulesButton

    view = discord.ui.View(timeout=None)
    view.add_item(ApplyButton())
    view.add_item(RulesButton())
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


def live_status_embed(application: MinecraftApplication, settings) -> discord.Embed:
    status = application.status
    if status is ApplicationStatus.PENDING_VERIFICATION:
        expiry = datetime.fromtimestamp(application.verification_expires_at, timezone.utc)
        connection = _connection_blocks(settings)
        edition_note = (
            "The server identifies Java or Bedrock automatically."
            if application.auto_detect_edition
            else f"Use the {application.edition.value.title()} connection details."
        )
        next_action = (
            f"Connect once as `{_safe(application.claimed_username, 100)}` before "
            f"{discord.utils.format_dt(expiry, 'R')}. {edition_note}"
        )
    elif status is ApplicationStatus.PENDING_APPLICATION:
        expiry = datetime.fromtimestamp(application.verification_expires_at, timezone.utc)
        connection = ""
        next_action = (
            f"Your account `{_safe(application.verified_username or application.claimed_username, 100)}` "
            "is verified. One step left: fill out the short application form. "
            "Press **Continue Application** below, or press **Apply** on the application panel. "
            f"Please finish before {discord.utils.format_dt(expiry, 'R')} or the application expires."
        )
    elif status is ApplicationStatus.PENDING_REVIEW:
        connection = ""
        next_action = (
            f"Your application has already been sent with the verified account "
            f"`{_safe(application.verified_username or application.claimed_username, 100)}`. "
            "Please wait for a staff decision; no further action is needed."
        )
    elif status is ApplicationStatus.APPROVAL_QUEUED:
        connection = ""
        next_action = "Approval is being applied to the Minecraft server automatically."
    elif status is ApplicationStatus.APPROVED:
        connection = _connection_blocks(settings)
        next_action = "Access is active. Use the server details below whenever you want to join."
    elif status is ApplicationStatus.DENIED:
        connection = ""
        next_action = application.applicant_reason or "The application was not approved. Get help if you need clarification."
    elif status is ApplicationStatus.EXPIRED:
        connection = ""
        next_action = (
            "This application expired because nobody joined the Minecraft server in time to "
            "verify the account. Nothing is lost — press **Apply** on the application panel to start again."
            if not application.verified_at
            else "This application expired because the written form was not finished in time. "
            "Nothing is lost — press **Apply** on the application panel to start again."
        )
    elif status is ApplicationStatus.CANCELLED:
        connection = ""
        next_action = "This application was cancelled. You may apply again."
    else:
        connection = ""
        next_action = "This Minecraft access record is no longer active."
    description = (
        f"> Application **#{application.id}** · {status.value.replace('_', ' ').title()}\n\n"
        f"**What happens now**\n{next_action}"
    )
    if connection:
        description += f"\n\n{connection}"
    embed = info_embed("Your Minecraft Application", description)
    if status is ApplicationStatus.PENDING_VERIFICATION:
        embed.set_image(url=VERIFY_ATTACHMENT_URI)
    return embed


def verified_embed(application: MinecraftApplication, settings=None) -> discord.Embed:
    if application.status is ApplicationStatus.PENDING_APPLICATION:
        deadline = datetime.fromtimestamp(application.verification_expires_at, timezone.utc)
        channel_id = getattr(settings, "application_channel_id", 0) if settings else 0
        panel_hint = f"<#{channel_id}>" if channel_id else "the application channel"
        return info_embed(
            "Account Verified — One Step Left",
            "> Your Minecraft account is verified. Now tell us a little about yourself.\n\n"
            f"**Verified account**\n{application.edition.value.title()} · "
            f"`{_safe(application.verified_username, 100)}`\n\n"
            "**Finish your application**\n"
            "> Press **Continue Application** below and answer two short questions.\n"
            f"> You can also press **Apply** in {panel_hint} — it continues where you left off.\n"
            f"> Please finish before {discord.utils.format_dt(deadline, 'R')}, or the application expires.",
        )
    return info_embed(
        "Account Verified — Application Sent",
        "> Your Minecraft account was verified and your application has been sent!\n\n"
        f"**Verified account**\n{application.edition.value.title()} · "
        f"`{_safe(application.verified_username, 100)}`\n\n"
        "Staff will review your application. No further action is needed.",
    )


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
    if notification == "verification":
        embed = verified_embed(application, settings)
    elif notification == "decision" and application.status is ApplicationStatus.APPROVED:
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
