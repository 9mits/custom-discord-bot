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


def _safe(value: object, limit: int = 1000) -> str:
    text = discord.utils.escape_markdown(str(value or "Not provided"), as_needed=True)
    return text[:limit]


def brand_logo_file() -> discord.File:
    return discord.File(
        LOGO_PATH,
        filename=LOGO_FILENAME,
        description=f"{BRAND_NAME} logo",
    )


def _connection_blocks(settings) -> str:
    return (
        "**Java server address**\n"
        f"```text\n{settings.java_address}\n```\n"
        "**Bedrock server address**\n"
        f"```text\n{settings.bedrock_address}\n```\n"
        "**Bedrock port**\n"
        f"```text\n{settings.bedrock_port}\n```"
    )


def application_panel() -> discord.ui.LayoutView:
    from .ui import ApplyButton

    view = discord.ui.LayoutView(timeout=None)
    container = discord.ui.Container(accent_colour=THEME_COLOUR)
    container.add_item(
        discord.ui.Section(
            discord.ui.TextDisplay("## Apply to Mysterious SMP X"),
            discord.ui.TextDisplay(
                "Apply entirely through Discord, verify ownership with one Minecraft connection, "
                "and receive your result here. Verification never grants early access to the world."
            ),
            accessory=discord.ui.Thumbnail(
                LOGO_ATTACHMENT_URI,
                description=f"{BRAND_NAME} logo",
            ),
        )
    )
    container.add_item(
        discord.ui.TextDisplay(
            "**Before you begin**\n"
            "- Enter your exact Java username or Xbox gamertag.\n"
            "- Keep Discord DMs enabled so the bot can send status updates.\n"
            "- Entered the wrong username? Press **Apply** again for a private cancellation option."
        )
    )
    container.add_item(discord.ui.Separator())
    row = discord.ui.ActionRow()
    row.add_item(ApplyButton())
    container.add_item(row)
    container.add_item(discord.ui.Separator(visible=False))
    container.add_item(discord.ui.TextDisplay(f"-# {BRAND_NAME} — Secure Minecraft applications"))
    view.add_item(container)
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
    embed.set_thumbnail(url=LOGO_ATTACHMENT_URI)
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
    embed.set_footer(text=BRAND_NAME, icon_url=LOGO_ATTACHMENT_URI)
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
    if application.edition.value == "JAVA":
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
        f"Application `#{application.id}` is waiting for ownership verification.\n\n"
        f"{connection}\n\n"
        f"**Complete before:** {discord.utils.format_dt(expires_at, 'R')}\n\n"
        "**What happens next**\n"
        "1. Add the server using the details above.\n"
        f"2. Connect once with the account named `{_safe(application.claimed_username, 100)}`.\n"
        "3. The first connection is intentionally declined after the account is verified.\n"
        "4. Your application is then sent to staff automatically.\n\n"
        "**Wrong username?** Use **Cancel Pending Verification** on the application panel or "
        "run `/minecraft cancel`, then apply again.",
    )


def approval_embed(settings) -> discord.Embed:
    return info_embed(
        "Minecraft Application Approved",
        "Your application was approved and your account now has access to the server.\n\n"
        + _connection_blocks(settings)
        + "\n\nUse the address for your edition. Keep this message for quick access later.",
        success=True,
    )
