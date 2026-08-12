"""Discord presentation helpers for Minecraft applications."""

from __future__ import annotations

from datetime import datetime, timezone
from typing import Optional

import discord

from .models import ApplicationStatus, MinecraftApplication


BRAND_NAME = "Mysterious SMP X"
THEME_COLOUR = discord.Colour.from_rgb(88, 101, 242)


def _safe(value: object, limit: int = 1000) -> str:
    text = discord.utils.escape_markdown(str(value or "Not provided"), as_needed=True)
    return text[:limit]


def application_panel() -> discord.ui.LayoutView:
    from .ui import ApplyButton

    view = discord.ui.LayoutView(timeout=None)
    container = discord.ui.Container(accent_colour=THEME_COLOUR)
    container.add_item(discord.ui.TextDisplay("## Apply to Mysterious SMP X"))
    container.add_item(discord.ui.TextDisplay(
        "Submit your application here, then connect to the Minecraft server once to verify "
        "the account belongs to you. You will not enter the world until staff approve you."
    ))
    container.add_item(discord.ui.Separator())
    row = discord.ui.ActionRow()
    row.add_item(ApplyButton())
    container.add_item(row)
    container.add_item(discord.ui.Separator(visible=False))
    container.add_item(discord.ui.TextDisplay(f"-# {BRAND_NAME}"))
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
    embed.add_field(name="Status", value=status_title, inline=True)
    embed.add_field(name="Edition", value=application.edition.value.title(), inline=True)
    embed.add_field(name="Verified Username", value=_safe(application.verified_username), inline=True)
    embed.add_field(name="Minecraft UUID", value=f"`{_safe(application.minecraft_uuid, 100)}`", inline=False)
    if application.xuid:
        embed.add_field(name="Floodgate XUID", value=f"`{_safe(application.xuid, 100)}`", inline=False)
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
    colour = discord.Colour.green() if success else discord.Colour.red() if error else THEME_COLOUR
    embed = discord.Embed(title=title, description=description, colour=colour)
    embed.set_footer(text=BRAND_NAME)
    return embed
