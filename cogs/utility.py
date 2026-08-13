"""Everyday utility commands: member/role/channel info, staff notes, polls.

Nothing here issues punishments — `moderation.py` owns that. These are the small
lookups and conveniences that make the bot useful between incidents.
"""

from __future__ import annotations

import re
from datetime import datetime, timezone
from typing import List, Optional

import discord
from discord import app_commands
from discord.ext import commands

from core.constants import SCOPE_ANALYTICS, SCOPE_MODERATION, SCOPE_SYSTEM
from core.context import bot, tree
from core.responding import InteractionResponder
from core.utils import parse_duration_str, truncate_text

from .shared import (
    format_user_ref,
    has_permission_capability,
    logger,
    make_embed,
    make_empty_state_embed,
    resolve_member,
    respond_with_error,
    respond_with_operation_failure,
    send_log,
)


# Discord's own cap on a channel's slowmode delay.
MAX_SLOWMODE_SECONDS = 21600
MAX_POLL_OPTIONS = 10
NOTE_MAX_LENGTH = 1000

# Digit reactions carry the poll vote; there is no text equivalent for a tally, so
# these fall under the functional-reaction exception rather than decoration.
POLL_REACTIONS = ("1️⃣", "2️⃣", "3️⃣", "4️⃣", "5️⃣",
                  "6️⃣", "7️⃣", "8️⃣", "9️⃣", "\U0001f51f")

_KEY_PERMISSIONS = (
    ("administrator", "Administrator"),
    ("manage_guild", "Manage Server"),
    ("manage_roles", "Manage Roles"),
    ("manage_channels", "Manage Channels"),
    ("manage_messages", "Manage Messages"),
    ("moderate_members", "Timeout Members"),
    ("ban_members", "Ban Members"),
    ("kick_members", "Kick Members"),
    ("mention_everyone", "Mention Everyone"),
)


def format_timestamp(moment: Optional[datetime]) -> str:
    if moment is None:
        return "Unknown"
    stamp = int(moment.timestamp())
    return f"<t:{stamp}:F> (<t:{stamp}:R>)"


def summarize_roles(member: discord.Member, *, limit: int = 15) -> str:
    roles = [role.mention for role in reversed(member.roles) if not role.is_default()]
    if not roles:
        return "None"
    shown = roles[:limit]
    remaining = len(roles) - len(shown)
    text = " ".join(shown)
    if remaining > 0:
        text += f" and {remaining} more"
    return truncate_text(text, 1024)


def key_permissions(permissions: discord.Permissions) -> str:
    granted = [label for name, label in _KEY_PERMISSIONS if getattr(permissions, name, False)]
    return ", ".join(granted) if granted else "None"


def build_userinfo_embed(
    user: discord.abc.User,
    *,
    guild: Optional[discord.Guild],
    case_count: Optional[int] = None,
    note_count: Optional[int] = None,
) -> discord.Embed:
    embed = make_embed(
        "Member Information",
        f"> Details for {user.mention}.",
        kind="info",
        scope=SCOPE_ANALYTICS,
        guild=guild,
        thumbnail=user.display_avatar.url,
    )
    embed.add_field(name="User", value=format_user_ref(user), inline=True)
    embed.add_field(name="Username", value=f"`{user}`", inline=True)
    embed.add_field(name="Account Created", value=format_timestamp(user.created_at), inline=False)

    if isinstance(user, discord.Member):
        embed.add_field(name="Joined Server", value=format_timestamp(user.joined_at), inline=False)
        if user.premium_since:
            embed.add_field(name="Boosting Since", value=format_timestamp(user.premium_since), inline=False)
        if user.communication_disabled_until and user.communication_disabled_until > discord.utils.utcnow():
            embed.add_field(
                name="Timed Out Until",
                value=format_timestamp(user.communication_disabled_until),
                inline=False,
            )
        embed.add_field(name=f"Roles ({len(user.roles) - 1})", value=summarize_roles(user), inline=False)
        embed.add_field(name="Key Permissions", value=key_permissions(user.guild_permissions), inline=False)
    else:
        embed.add_field(name="Membership", value="Not a member of this server.", inline=False)

    if case_count is not None:
        embed.add_field(name="Moderation Cases", value=str(case_count), inline=True)
    if note_count is not None:
        embed.add_field(name="Staff Notes", value=str(note_count), inline=True)
    return embed


def build_roleinfo_embed(role: discord.Role, *, guild: Optional[discord.Guild]) -> discord.Embed:
    embed = make_embed(
        "Role Information",
        f"> Details for {role.mention}.",
        kind="info",
        scope=SCOPE_ANALYTICS,
        guild=guild,
    )
    embed.add_field(name="Role", value=f"{role.mention} (`{role.id}`)", inline=True)
    embed.add_field(name="Members", value=str(len(role.members)), inline=True)
    embed.add_field(name="Position", value=str(role.position), inline=True)
    embed.add_field(name="Colour", value=str(role.colour), inline=True)
    embed.add_field(name="Hoisted", value="Yes" if role.hoist else "No", inline=True)
    embed.add_field(name="Mentionable", value="Yes" if role.mentionable else "No", inline=True)
    embed.add_field(name="Managed by Integration", value="Yes" if role.managed else "No", inline=True)
    embed.add_field(name="Created", value=format_timestamp(role.created_at), inline=False)
    embed.add_field(name="Key Permissions", value=key_permissions(role.permissions), inline=False)
    return embed


def build_channelinfo_embed(channel, *, guild: Optional[discord.Guild]) -> discord.Embed:
    embed = make_embed(
        "Channel Information",
        f"> Details for {channel.mention}.",
        kind="info",
        scope=SCOPE_ANALYTICS,
        guild=guild,
    )
    embed.add_field(name="Channel", value=f"{channel.mention} (`{channel.id}`)", inline=True)
    embed.add_field(name="Type", value=str(channel.type).replace("_", " ").title(), inline=True)
    category = getattr(channel, "category", None)
    embed.add_field(name="Category", value=category.name if category else "None", inline=True)
    embed.add_field(name="Created", value=format_timestamp(channel.created_at), inline=False)

    topic = getattr(channel, "topic", None)
    if topic:
        embed.add_field(name="Topic", value=truncate_text(topic, 1024), inline=False)
    slowmode = getattr(channel, "slowmode_delay", 0)
    if slowmode:
        embed.add_field(name="Slowmode", value=f"{slowmode} seconds", inline=True)
    if getattr(channel, "nsfw", False):
        embed.add_field(name="Age Restricted", value="Yes", inline=True)
    return embed


def build_note_list_embed(
    user: discord.abc.User,
    notes: List[dict],
    *,
    guild: Optional[discord.Guild],
) -> discord.Embed:
    if not notes:
        return make_empty_state_embed(
            "Staff Notes",
            f"> No staff notes have been recorded for {user.mention}.",
            scope=SCOPE_MODERATION,
            guild=guild,
        )
    embed = make_embed(
        "Staff Notes",
        f"> {len(notes)} note{'s' if len(notes) != 1 else ''} recorded for {user.mention}.",
        kind="info",
        scope=SCOPE_MODERATION,
        guild=guild,
    )
    for note in notes[:10]:
        created = note.get("created_at")
        try:
            stamp = f"<t:{int(datetime.fromisoformat(str(created)).timestamp())}:R>"
        except (TypeError, ValueError):
            stamp = "Unknown time"
        embed.add_field(
            name=f"Note {note.get('note_id')} · {stamp}",
            value=truncate_text(str(note.get("content") or ""), 1024),
            inline=False,
        )
    return embed


def parse_poll_options(raw: str) -> List[str]:
    """Split `a | b | c` (or newline-separated) into distinct, bounded choices."""
    parts = [part.strip() for part in re.split(r"\||\n", str(raw or "")) if part.strip()]
    unique: List[str] = []
    for part in parts:
        trimmed = truncate_text(part, 100)
        if trimmed not in unique:
            unique.append(trimmed)
    return unique[:MAX_POLL_OPTIONS]


def build_poll_embed(
    question: str,
    options: List[str],
    *,
    author: discord.abc.User,
    guild: Optional[discord.Guild],
) -> discord.Embed:
    lines = [f"{POLL_REACTIONS[index]} {option}" for index, option in enumerate(options)]
    embed = make_embed(
        truncate_text(question, 256),
        "> " + "\n> ".join(lines),
        kind="info",
        scope=SCOPE_SYSTEM,
        guild=guild,
    )
    embed.add_field(name="Started By", value=author.mention, inline=True)
    embed.add_field(name="How to Vote", value="React with the number of your choice.", inline=True)
    return embed


# ---------------------------------------------------------------------------
# Commands
# ---------------------------------------------------------------------------


@tree.command(name="userinfo", description="View account, join, and role details for a member.")
@app_commands.describe(user="Member to inspect; defaults to you")
async def userinfo(interaction: discord.Interaction, user: Optional[discord.User] = None) -> None:
    responder = InteractionResponder(interaction)
    await responder.defer(ephemeral=True)

    target = user or interaction.user
    resolved: discord.abc.User = target
    if interaction.guild is not None:
        member = await resolve_member(interaction.guild, target.id)
        if member is not None:
            resolved = member

    case_count = note_count = None
    if has_permission_capability(interaction, "cases.read"):
        try:
            case_count = len(bot.data_manager.punishments.get(str(target.id), []))
            note_count = await bot.data_manager.count_user_notes(target.id)
        except Exception:
            logger.warning("Could not load case or note counts for %s", target.id, exc_info=True)

    await responder.send(
        embed=build_userinfo_embed(
            resolved, guild=interaction.guild, case_count=case_count, note_count=note_count
        ),
        ephemeral=True,
    )


@tree.command(name="avatar", description="View a member's full-size avatar.")
@app_commands.describe(user="Member whose avatar to show; defaults to you")
async def avatar(interaction: discord.Interaction, user: Optional[discord.User] = None) -> None:
    target = user or interaction.user
    embed = make_embed(
        "Avatar",
        f"> Avatar for {target.mention}.",
        kind="info",
        scope=SCOPE_ANALYTICS,
        guild=interaction.guild,
    )
    embed.set_image(url=target.display_avatar.url)
    await InteractionResponder(interaction).send(embed=embed, ephemeral=True)


@tree.command(name="roleinfo", description="View permissions and membership for a role.")
@app_commands.describe(role="Role to inspect")
async def roleinfo(interaction: discord.Interaction, role: discord.Role) -> None:
    await InteractionResponder(interaction).send(
        embed=build_roleinfo_embed(role, guild=interaction.guild), ephemeral=True
    )


@tree.command(name="channelinfo", description="View details for a channel.")
@app_commands.describe(channel="Channel to inspect; defaults to this one")
async def channelinfo(
    interaction: discord.Interaction,
    channel: Optional[discord.abc.GuildChannel] = None,
) -> None:
    target = channel or interaction.channel
    if target is None:
        await respond_with_error(interaction, "That channel could not be resolved.", scope=SCOPE_ANALYTICS)
        return
    await InteractionResponder(interaction).send(
        embed=build_channelinfo_embed(target, guild=interaction.guild), ephemeral=True
    )


@tree.command(name="ping", description="Check whether the bot is responsive.")
async def ping(interaction: discord.Interaction) -> None:
    latency_ms = round(bot.latency * 1000)
    embed = make_embed(
        "Pong",
        f"> Gateway latency is **{latency_ms} ms**.",
        kind="success" if latency_ms < 300 else "warning",
        scope=SCOPE_SYSTEM,
        guild=interaction.guild,
    )
    await InteractionResponder(interaction).send(embed=embed, ephemeral=True)


@tree.command(name="timestamp", description="Turn a duration from now into Discord timestamp codes.")
@app_commands.describe(duration="How far from now, for example 2h30m, 3d, or 1w")
async def timestamp(interaction: discord.Interaction, duration: str) -> None:
    minutes = parse_duration_str(duration)
    if minutes <= 0:
        await respond_with_error(
            interaction,
            "Enter a positive duration such as `30m`, `2h`, `3d`, or `1w`.",
            scope=SCOPE_SYSTEM,
        )
        return

    target = int(datetime.now(timezone.utc).timestamp()) + minutes * 60
    formats = [
        ("Relative", "R"), ("Short Time", "t"), ("Long Time", "T"),
        ("Short Date", "d"), ("Long Date", "D"), ("Full", "F"),
    ]
    lines = [f"**{label}** · `<t:{target}:{code}>` renders as <t:{target}:{code}>" for label, code in formats]
    embed = make_embed(
        "Timestamp Codes",
        "> " + "\n> ".join(lines),
        kind="info",
        scope=SCOPE_SYSTEM,
        guild=interaction.guild,
    )
    embed.add_field(name="Note", value="Paste a code into any message; it renders in each viewer's timezone.", inline=False)
    await InteractionResponder(interaction).send(embed=embed, ephemeral=True)


@tree.command(name="slowmode", description="Set or clear the slowmode delay on a channel.")
@app_commands.describe(
    seconds=f"Delay in seconds (0 clears it, maximum {MAX_SLOWMODE_SECONDS})",
    channel="Channel to change; defaults to this one",
)
async def slowmode(
    interaction: discord.Interaction,
    seconds: app_commands.Range[int, 0, MAX_SLOWMODE_SECONDS],
    channel: Optional[discord.TextChannel] = None,
) -> None:
    if not has_permission_capability(interaction, "channels.lock"):
        await respond_with_error(interaction, "You do not have permission to change slowmode.", scope=SCOPE_MODERATION)
        return

    target = channel or interaction.channel
    if not isinstance(target, discord.TextChannel):
        await respond_with_error(interaction, "Slowmode can only be set on a text channel.", scope=SCOPE_MODERATION)
        return

    responder = InteractionResponder(interaction)
    await responder.defer(ephemeral=True)
    try:
        await target.edit(slowmode_delay=int(seconds), reason=f"Slowmode set by {interaction.user}")
    except Exception as error:
        await respond_with_operation_failure(interaction, error, operation="set slowmode", scope=SCOPE_MODERATION)
        return

    description = (
        f"> Slowmode cleared in {target.mention}."
        if seconds == 0
        else f"> Slowmode in {target.mention} set to **{seconds} seconds**."
    )
    await responder.send(
        embed=make_embed("Slowmode Updated", description, kind="success", scope=SCOPE_MODERATION, guild=interaction.guild),
        ephemeral=True,
    )

    log_embed = make_embed(
        "Slowmode Updated",
        "> A channel's slowmode delay was changed.",
        kind="info",
        scope=SCOPE_MODERATION,
        guild=interaction.guild,
    )
    log_embed.add_field(name="Actor", value=format_user_ref(interaction.user), inline=True)
    log_embed.add_field(name="Channel", value=f"{target.mention} (`{target.id}`)", inline=True)
    log_embed.add_field(name="Delay", value=f"{seconds} seconds", inline=True)
    await send_log(interaction.guild, log_embed)


@tree.command(name="nickname", description="Change or reset a member's nickname.")
@app_commands.describe(user="Member to rename", nickname="New nickname; leave empty to reset")
async def nickname(
    interaction: discord.Interaction,
    user: discord.User,
    nickname: Optional[app_commands.Range[str, 1, 32]] = None,
) -> None:
    if not has_permission_capability(interaction, "cases.read"):
        await respond_with_error(interaction, "You do not have permission to change nicknames.", scope=SCOPE_MODERATION)
        return
    if interaction.guild is None:
        await respond_with_error(interaction, "This command only works inside a server.", scope=SCOPE_MODERATION)
        return

    responder = InteractionResponder(interaction)
    await responder.defer(ephemeral=True)

    member = await resolve_member(interaction.guild, user.id)
    if member is None:
        await respond_with_error(interaction, "That member is not in this server.", scope=SCOPE_MODERATION)
        return

    previous = member.display_name
    try:
        await member.edit(nick=nickname, reason=f"Nickname changed by {interaction.user}")
    except Exception as error:
        await respond_with_operation_failure(interaction, error, operation="change nickname", scope=SCOPE_MODERATION)
        return

    description = (
        f"> Nickname reset for {member.mention}."
        if nickname is None
        else f"> {member.mention} is now shown as **{nickname}**."
    )
    await responder.send(
        embed=make_embed("Nickname Updated", description, kind="success", scope=SCOPE_MODERATION, guild=interaction.guild),
        ephemeral=True,
    )

    log_embed = make_embed(
        "Nickname Updated",
        "> A member's nickname was changed by staff.",
        kind="info",
        scope=SCOPE_MODERATION,
        guild=interaction.guild,
    )
    log_embed.add_field(name="Actor", value=format_user_ref(interaction.user), inline=True)
    log_embed.add_field(name="Member", value=format_user_ref(member), inline=True)
    log_embed.add_field(name="Before", value=truncate_text(previous, 256), inline=True)
    log_embed.add_field(name="After", value=truncate_text(nickname or member.name, 256), inline=True)
    await send_log(interaction.guild, log_embed)


@tree.command(name="poll", description="Post a reaction poll with up to ten choices.")
@app_commands.describe(
    question="The question to ask",
    options="Choices separated by | — for example: Yes | No | Maybe",
)
async def poll(
    interaction: discord.Interaction,
    question: app_commands.Range[str, 1, 256],
    options: str,
) -> None:
    if not has_permission_capability(interaction, "cases.read"):
        await respond_with_error(interaction, "You do not have permission to start a poll.", scope=SCOPE_SYSTEM)
        return

    choices = parse_poll_options(options)
    if len(choices) < 2:
        await respond_with_error(
            interaction,
            "Give at least two choices separated by `|`, for example `Yes | No`.",
            scope=SCOPE_SYSTEM,
        )
        return

    responder = InteractionResponder(interaction)
    await responder.defer(ephemeral=True)
    try:
        message = await interaction.channel.send(
            embed=build_poll_embed(question, choices, author=interaction.user, guild=interaction.guild)
        )
        for index in range(len(choices)):
            await message.add_reaction(POLL_REACTIONS[index])
    except Exception as error:
        await respond_with_operation_failure(interaction, error, operation="create poll", scope=SCOPE_SYSTEM)
        return

    await responder.send(
        embed=make_embed(
            "Poll Created",
            f"> Your poll is live: {message.jump_url}",
            kind="success",
            scope=SCOPE_SYSTEM,
            guild=interaction.guild,
        ),
        ephemeral=True,
    )


# Private staff notes on a member — context that is not a punishment.
note_group = app_commands.Group(name="note", description="Record private staff notes about a member.")


@note_group.command(name="add", description="Record a staff note about a member.")
@app_commands.describe(user="Member the note is about", content="What to record")
async def note_add(
    interaction: discord.Interaction,
    user: discord.User,
    content: app_commands.Range[str, 1, NOTE_MAX_LENGTH],
) -> None:
    if not has_permission_capability(interaction, "cases.read"):
        await respond_with_error(interaction, "You do not have permission to manage staff notes.", scope=SCOPE_MODERATION)
        return

    responder = InteractionResponder(interaction)
    await responder.defer(ephemeral=True)
    try:
        note_id = await bot.data_manager.add_user_note(
            user_id=user.id, author_id=interaction.user.id, content=content
        )
    except Exception as error:
        await respond_with_operation_failure(interaction, error, operation="add staff note", scope=SCOPE_MODERATION)
        return

    await responder.send(
        embed=make_embed(
            "Note Recorded",
            f"> Note `{note_id}` saved for {user.mention}.",
            kind="success",
            scope=SCOPE_MODERATION,
            guild=interaction.guild,
        ),
        ephemeral=True,
    )


@note_group.command(name="list", description="View staff notes recorded about a member.")
@app_commands.describe(user="Member whose notes to view")
async def note_list(interaction: discord.Interaction, user: discord.User) -> None:
    if not has_permission_capability(interaction, "cases.read"):
        await respond_with_error(interaction, "You do not have permission to view staff notes.", scope=SCOPE_MODERATION)
        return

    responder = InteractionResponder(interaction)
    await responder.defer(ephemeral=True)
    try:
        notes = await bot.data_manager.list_user_notes(user.id)
    except Exception as error:
        await respond_with_operation_failure(interaction, error, operation="list staff notes", scope=SCOPE_MODERATION)
        return

    await responder.send(
        embed=build_note_list_embed(user, notes, guild=interaction.guild), ephemeral=True
    )


@note_group.command(name="remove", description="Delete a staff note by its id.")
@app_commands.describe(note="Note id, as shown by /note list")
async def note_remove(interaction: discord.Interaction, note: app_commands.Range[int, 1]) -> None:
    if not has_permission_capability(interaction, "cases.read"):
        await respond_with_error(interaction, "You do not have permission to manage staff notes.", scope=SCOPE_MODERATION)
        return

    responder = InteractionResponder(interaction)
    await responder.defer(ephemeral=True)
    try:
        deleted = await bot.data_manager.delete_user_note(int(note))
    except Exception as error:
        await respond_with_operation_failure(interaction, error, operation="remove staff note", scope=SCOPE_MODERATION)
        return

    if deleted is None:
        await respond_with_error(interaction, f"No staff note with id `{note}` exists.", scope=SCOPE_MODERATION)
        return

    await responder.send(
        embed=make_embed(
            "Note Removed",
            f"> Note `{note}` was deleted.",
            kind="success",
            scope=SCOPE_MODERATION,
            guild=interaction.guild,
        ),
        ephemeral=True,
    )


class UtilityCog(commands.Cog):
    def __init__(self, bot):
        self.bot = bot


async def setup(bot_instance) -> None:
    await bot_instance.add_cog(UtilityCog(bot_instance))
    for command in (userinfo, avatar, roleinfo, channelinfo, ping, timestamp, slowmode, nickname, poll):
        bot_instance.tree.add_command(command)
    bot_instance.tree.add_command(note_group)
