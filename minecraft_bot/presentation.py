"""Discord presentation helpers for Minecraft applications."""

from __future__ import annotations

from datetime import datetime, timezone
from pathlib import Path
from typing import Optional

import discord

from .models import ApplicationStatus, MinecraftApplication


BRAND_NAME = "Mysterious SMP X"
THEME_COLOUR = discord.Colour.from_rgb(255, 153, 0)
LOGO_FILENAME = "mysterious_smp_x_logo.png"
LOGO_PATH = Path(__file__).resolve().parent.parent / "assets" / "minecraft" / LOGO_FILENAME
LOGO_ATTACHMENT_URI = f"attachment://{LOGO_FILENAME}"
ICON_FILENAME = "mysterious_smp_x_icon.png"
ICON_PATH = Path(__file__).resolve().parent.parent / "assets" / "minecraft" / ICON_FILENAME
ICON_ATTACHMENT_URI = f"attachment://{ICON_FILENAME}"
FOOTER_FILENAME = "mysterious_smp_x_footer.png"
FOOTER_PATH = Path(__file__).resolve().parent.parent / "assets" / "minecraft" / FOOTER_FILENAME
FOOTER_ATTACHMENT_URI = f"attachment://{FOOTER_FILENAME}"


def _safe(value: object, limit: int = 1000) -> str:
    text = discord.utils.escape_markdown(str(value or "Not provided"), as_needed=True)
    return text[:limit]


def brand_logo_file() -> discord.File:
    return discord.File(
        LOGO_PATH,
        filename=LOGO_FILENAME,
        description=f"{BRAND_NAME} logo",
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


def branded_send(embed: discord.Embed) -> dict[str, object]:
    return {"embed": embed}


def branded_edit(embed: discord.Embed) -> dict[str, object]:
    return {"embed": embed}


def application_panel_files() -> list[discord.File]:
    return [brand_logo_file(), brand_footer_file()]


def _connection_blocks(settings) -> str:
    return (
        "**Java server address**\n"
        f"```text\n{settings.java_address}\n```\n"
        "**Bedrock server address**\n"
        f"```text\n{settings.bedrock_address}\n```\n"
        "**Bedrock port**\n"
        f"```text\n{settings.bedrock_port}\n```"
    )


def _panel_embed(title: str, description: str) -> discord.Embed:
    embed = discord.Embed(
        title=title,
        description=description,
        colour=THEME_COLOUR,
    )
    embed.set_footer(text=BRAND_NAME, icon_url=FOOTER_ATTACHMENT_URI)
    return embed


def application_embeds() -> list[discord.Embed]:
    welcome = _panel_embed(
        "Welcome to Mysterious SMP X",
        "The official Minecraft SMP of the Mysterious Girlfriend X Discord community—a place "
        "to explore, build, battle, create stories, and enjoy the server together.\n\n"
        "> This is a private SMP, so every player applies and is reviewed before joining. "
        "Acceptance is intentionally approachable: if you want to play, we encourage you to apply.",
    )
    welcome.set_image(url=LOGO_ATTACHMENT_URI)
    welcome.remove_footer()
    apply = _panel_embed(
        "Apply to Mysterious SMP X",
        "Apply entirely through Discord, verify ownership with one Minecraft connection, "
        "and receive your result privately. Verification never grants early access to the world.\n\n"
        "**Before you begin**\n"
        "- Enter your exact Java username or Xbox gamertag.\n"
        "- Keep Discord DMs enabled so the bot can send status updates.\n"
        "- Entered the wrong username? Press **Apply** again for a private cancellation option.",
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
    description = (
        introduction
        + "1. **Respect builds** — Do not grief, damage, or alter another player's work without permission.\n\n"
        "2. **Play fairly** — No hacked clients, x-ray, duping, exploits, or unfair advantages.\n\n"
        "3. **Keep PvP reasonable** — PvP is allowed; repeated targeting, spawn-killing, and harassment are not.\n\n"
        "4. **Keep drama in-game** — Alliances, rivalries, wars, and betrayals are welcome when they remain fun.\n\n"
        "5. **Protect server stability** — No lag machines, crash exploits, chunk bans, or destructive abuse.\n\n"
        "6. **Use common sense** — Loopholes do not excuse behavior that ruins the experience for others.\n\n"
        "**Have fun, create lore, and help make the server enjoyable for everyone.**"
        + ending
    )
    return info_embed(
        "Mysterious SMP X Rules & Agreement" if agreement else "Mysterious SMP X Rules",
        description,
    )


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
    embed.set_footer(text=BRAND_NAME)
    return embed


def info_embed(title: str, description: str, *, success: bool = False, error: bool = False) -> discord.Embed:
    embed = discord.Embed(
        title=title,
        description=description,
        colour=THEME_COLOUR,
        timestamp=discord.utils.utcnow(),
    )
    embed.set_footer(text=BRAND_NAME)
    return embed


def verification_embed(application: MinecraftApplication, settings) -> discord.Embed:
    expires_at = datetime.fromtimestamp(application.verification_expires_at, timezone.utc)
    if application.auto_detect_edition:
        connection = _connection_blocks(settings)
    elif application.edition.value == "JAVA":
        connection = (
            "**Java server address**\n"
            f"```text\n{settings.java_address}\n```"
        )
    else:
        connection = (
            "**Bedrock server address**\n"
            f"```text\n{settings.bedrock_address}\n```\n"
            "**Bedrock port**\n"
            f"```text\n{settings.bedrock_port}\n```"
        )
    return info_embed(
        "Verify Your Minecraft Account",
        "> Your application is ready for Minecraft ownership verification.\n\n"
        f"{connection}\n\n"
        f"**Complete before:** {discord.utils.format_dt(expires_at, 'R')}\n\n"
        "**What happens next**\n"
        "1. Add the server using the details above.\n"
        f"2. Connect once with the account named `{_safe(application.claimed_username, 100)}`.\n"
        "3. The first connection is intentionally declined after the account is verified.\n"
        "4. Your application is then sent to staff automatically.\n\n"
        "**Wrong username?** Press **Apply** again on the application panel to reveal the private "
        "**Cancel Pending Verification** option, or run `/minecraft cancel`, then apply again.",
    )


def live_status_embed(application: MinecraftApplication, settings) -> discord.Embed:
    status = application.status
    steps = [
        ("Application received", True),
        ("Account verified", application.verified_at is not None),
        (
            "Staff review complete",
            status in {
                ApplicationStatus.APPROVED,
                ApplicationStatus.DENIED,
                ApplicationStatus.REVOKED,
            },
        ),
    ]
    progress = "\n".join(f"{'Complete' if complete else 'Next'} · {label}" for label, complete in steps)
    if status is ApplicationStatus.PENDING_VERIFICATION:
        expiry = datetime.fromtimestamp(application.verification_expires_at, timezone.utc)
        connection = _connection_blocks(settings)
        next_action = (
            f"Connect once as `{_safe(application.claimed_username, 100)}` before "
            f"{discord.utils.format_dt(expiry, 'R')}. The server identifies Java or Bedrock automatically."
        )
    elif status is ApplicationStatus.PENDING_REVIEW:
        connection = ""
        next_action = "Your account is verified. Staff will review the application; no further action is needed."
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
        next_action = "The verification window expired. Start a new application when you are ready."
    elif status is ApplicationStatus.CANCELLED:
        connection = ""
        next_action = "This application was cancelled. You may apply again."
    else:
        connection = ""
        next_action = "This Minecraft access record is no longer active."
    description = (
        f"> Application **#{application.id}** · {status.value.replace('_', ' ').title()}\n\n"
        f"**Progress**\n{progress}\n\n**Next step**\n{next_action}"
    )
    if connection:
        description += f"\n\n{connection}"
    return info_embed("Your Minecraft Application", description)


def verified_embed(application: MinecraftApplication) -> discord.Embed:
    return info_embed(
        "Minecraft Account Verified",
        f"> Your **{application.edition.value.title()}** account "
        f"`{_safe(application.verified_username, 100)}` was securely matched to your application.\n\n"
        "**What happens now**\n"
        "- Your application has been sent to the staff review queue.\n"
        "- Staff aim to respond within **24 hours**.\n"
        "- You will receive another DM when a decision is finalized.\n\n"
        "You do not need to connect again. Minecraft access remains locked until approval.",
        success=True,
    )


def denial_embed(application: MinecraftApplication) -> discord.Embed:
    reason = _safe(
        application.applicant_reason or "No public reason was provided.",
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


def application_log_embed(application: MinecraftApplication) -> discord.Embed:
    expires_at = datetime.fromtimestamp(application.verification_expires_at, timezone.utc)
    embed = info_embed(
        f"Application Submitted #{application.id}",
        f"> <@{application.discord_user_id}> entered the Minecraft verification stage.",
    )
    embed.add_field(
        name="Edition",
        value="Detected on connection" if application.auto_detect_edition else application.edition.value.title(),
        inline=True,
    )
    embed.add_field(
        name="Claimed Username",
        value=f"`{_safe(application.claimed_username, 100)}`",
        inline=True,
    )
    embed.add_field(
        name="Verification Expires",
        value=discord.utils.format_dt(expires_at, "R"),
        inline=True,
    )
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
    return embed


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
    return embed


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
    return embed


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
    return embed
