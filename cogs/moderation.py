"""Punishment execution, ModGroup slash commands, and moderation context menus."""

import discord
from discord import app_commands
from discord.ext import commands
import asyncio
from datetime import timedelta
import re
from typing import Optional, Union

from core.constants import (
    DEFAULT_RULES,
    SCOPE_MODERATION,
)
from core.services import (
    calculate_offense_punishment,
)
from core.context import abuse_system, bot, tree
from core.utils import now_iso, parse_duration_str
from .shared import (
    format_duration,
    format_log_quote,
    format_reason_value,
    truncate_text,
    make_embed,
    make_empty_state_embed,
    get_user_display_name,
    format_user_ref,
    send_punishment_log,
    respond_with_error,
    is_staff,
    resolve_member,
    get_valid_duration,
    handle_abuse,
)
from .cases import (
    get_case_label,
    build_punishment_execution_log_embed,
    build_no_history_embed,
    build_mod_help_embed,
)
from .history import HistoryView
from .case_panel import AllCasesView, build_case_link_view, generate_transcript_html, show_case_panel
from .roles import build_appeal_view, build_punish_embed

# ----------------- Message capture / purge helpers -----------------

# Cap on how far back a single channel is scanned when looking up a user's recent
# messages, and how many channels are scanned concurrently. Bounds the cost of the
# server-wide sweep used by the punish panel's Purge Messages / Save Logs buttons.
_PER_CHANNEL_SCAN_FLOOR = 200
_PER_CHANNEL_SCAN_CEIL = 1000
_CHANNEL_SCAN_CONCURRENCY = 8


async def _scan_channel_for_user(channel, me, user_id, per_channel_limit):
    if not channel.permissions_for(me).read_message_history:
        return []
    found = []
    try:
        async for message in channel.history(limit=per_channel_limit):
            if message.author.id == user_id:
                found.append(message)
    except (discord.Forbidden, discord.HTTPException):
        return []
    return found


async def collect_user_messages(guild, user_id, limit):
    """Best-effort: gather up to `limit` of a user's most recent messages across
    every readable text channel, newest-first. Channels are scanned concurrently
    (different rate-limit buckets) with a per-channel depth cap so the sweep stays
    bounded on large servers."""
    per_channel_limit = max(_PER_CHANNEL_SCAN_FLOOR, min(_PER_CHANNEL_SCAN_CEIL, limit * 4))
    semaphore = asyncio.Semaphore(_CHANNEL_SCAN_CONCURRENCY)

    async def _guarded(channel):
        async with semaphore:
            return await _scan_channel_for_user(channel, guild.me, user_id, per_channel_limit)

    results = await asyncio.gather(*[_guarded(c) for c in guild.text_channels], return_exceptions=True)
    messages = []
    for result in results:
        if isinstance(result, list):
            messages.extend(result)
    messages.sort(key=lambda m: m.created_at, reverse=True)
    return messages[:limit]


def build_user_transcript(messages, user) -> bytes:
    """Render a list of discord.Message (newest-first) into a modmail-style HTML
    transcript, reusing the shared renderer. Returns UTF-8 bytes."""
    records = []
    for message in messages:
        records.append({
            "author_name": message.author.display_name,
            "author_avatar_url": message.author.display_avatar.url,
            "created_at": message.created_at,
            "content": message.content,
            "attachments": [{"filename": a.filename, "url": a.url} for a in message.attachments],
            "stickers": [s.name for s in message.stickers],
            "channel_id": getattr(message.channel, "name", message.channel.id),
            "deleted": False,
            "edited": bool(message.edited_at),
        })
    return generate_transcript_html(records, user).encode("utf-8")


async def delete_messages_efficiently(messages) -> int:
    """Delete the given messages, grouped by channel: bulk-delete recent ones
    (<14 days) in chunks of 100, individually delete the rest. Relies on
    discord.py's HTTP layer for rate limiting (no fixed sleeps). Returns the
    number deleted."""
    cutoff = discord.utils.utcnow() - timedelta(days=14)
    by_channel = {}
    for message in messages:
        by_channel.setdefault(message.channel.id, (message.channel, []))[1].append(message)

    deleted = 0
    for channel, channel_messages in by_channel.values():
        recent = [m for m in channel_messages if m.created_at > cutoff]
        old = [m for m in channel_messages if m.created_at <= cutoff]
        for i in range(0, len(recent), 100):
            chunk = recent[i:i + 100]
            try:
                await channel.delete_messages(chunk)
                deleted += len(chunk)
            except (discord.Forbidden, discord.HTTPException):
                pass
        for message in old:
            try:
                await message.delete()
                deleted += 1
            except (discord.Forbidden, discord.HTTPException):
                pass
    return deleted


def capture_message_evidence(message: discord.Message) -> dict:
    attachments = []
    for attachment in message.attachments[:10]:
        url = str(getattr(attachment, "url", "") or "").strip()
        if not url.startswith(("http://", "https://")):
            continue
        attachments.append({
            "filename": truncate_text(str(getattr(attachment, "filename", "Attachment")), 100),
            "url": url,
            "content_type": str(getattr(attachment, "content_type", "") or ""),
        })
    return {
        "id": int(message.id),
        "channel_id": int(message.channel.id),
        "jump_url": str(getattr(message, "jump_url", "") or ""),
        "content": truncate_text(str(message.content or "").strip(), 1500),
        "attachments": attachments,
        "deleted": False,
    }


async def delete_evidence_message(message: discord.Message, evidence: dict) -> None:
    try:
        await message.delete()
        evidence["deleted"] = True
    except discord.NotFound:
        evidence["deleted"] = True
    except (discord.Forbidden, discord.HTTPException):
        evidence["delete_error"] = True


async def execute_punishment(
    interaction,
    target,
    moderator,
    reason,
    minutes,
    note,
    user_msg,
    is_escalated,
    origin_message=None,
    punishment_type="auto",
    public=False,
    purge_count=0,
    save_count=0,
    evidence_message=None,
):
    uid = str(target.id)
    history = bot.data_manager.punishments.get(uid, [])
    guild = interaction.guild
    member_target = target if isinstance(target, discord.Member) else await resolve_member(guild, target.id)
    
    # Determine Type
    if punishment_type == "auto":
        if minutes == -1: punishment_type = "ban"
        elif minutes == 0: punishment_type = "warn"
        else: punishment_type = "timeout"

    is_ban = (punishment_type == "ban")
    is_kick = (punishment_type == "kick")
    is_softban = (punishment_type == "softban")
    is_warning = (punishment_type == "warn")

    # Anti-Abuse: Hierarchy Check (moderator must outrank target; guild owner always bypasses)
    if member_target and member_target.id != guild.owner_id and member_target != moderator and moderator.id != guild.owner_id:
        if member_target.top_role >= moderator.top_role:
            blocked_embed = make_embed("Anti-Abuse Blocked", "> You cannot punish a user with equal or higher role hierarchy.", kind="error", scope=SCOPE_MODERATION, guild=interaction.guild)
            if interaction.response.is_done():
                await interaction.followup.send(embed=blocked_embed, ephemeral=True)
            else:
                await interaction.response.send_message(embed=blocked_embed, ephemeral=True)
            return

    # Anti-Abuse: Rate Limit
    if abuse_system.check_rate_limit(moderator.id, bot.data_manager.config):
        await handle_abuse(interaction, moderator)
        return

    if not interaction.response.is_done():
        await interaction.response.defer(ephemeral=True)

    try:
        if is_kick:
            if not member_target:
                await interaction.followup.send(embed=make_embed("Cannot Kick", "> User is not in the server, cannot kick.", kind="error", scope=SCOPE_MODERATION, guild=interaction.guild), ephemeral=True)
                return
            await guild.kick(member_target, reason=f"{reason} (By {moderator})")
        elif is_softban:
            # Softban: Ban (Delete 1 day of messages) -> Unban
            await guild.ban(target, reason=f"{reason} (By {moderator})", delete_message_days=1)
            await guild.unban(discord.Object(id=target.id), reason=f"Softban cleanup (By {moderator})")
        elif is_ban:
            # Handles both Perm (-1) and Temp (>0) bans
            await guild.ban(target, reason=f"{reason} (By {moderator})", delete_message_days=0)
        elif punishment_type == "timeout":
            if not member_target:
                await interaction.followup.send(embed=make_embed("Cannot Timeout", "> User is not in the server, cannot timeout.", kind="error", scope=SCOPE_MODERATION, guild=interaction.guild), ephemeral=True)
                return
            duration = get_valid_duration(minutes)
            await member_target.timeout(duration, reason=f"{reason} (By {moderator})")
    except discord.Forbidden:
        await interaction.followup.send(embed=make_embed("Permission Error", "> I cannot action this user. My role must be **above** theirs in the role list, and I need the matching permission (Moderate Members for timeouts, Ban Members for bans, Kick Members for kicks).", kind="error", scope=SCOPE_MODERATION, guild=interaction.guild), ephemeral=True)
        return
    except Exception as e:
        await interaction.followup.send(embed=make_embed("Error", f"> Error: {e}", kind="error", scope=SCOPE_MODERATION, guild=interaction.guild), ephemeral=True)
        return

    timestamp_iso = now_iso()
    source_message = None
    if evidence_message is not None:
        source_message = capture_message_evidence(evidence_message)
        await delete_evidence_message(evidence_message, source_message)

    # Create the case record first so the DM's appeal button can reference its case_id.
    record = {
        "reason": reason,
        "moderator": moderator.id,
        "duration_minutes": minutes,
        "timestamp": timestamp_iso,
        "escalated": is_escalated,
        "note": note,
        "user_msg": user_msg,
        "target_name": get_user_display_name(target),
        "type": punishment_type,
        "active": is_ban
    }
    if source_message is not None:
        record["source_message"] = source_message
    record = await bot.data_manager.add_punishment(uid, record, persist=False)
    case_label = get_case_label(record, len(history) + 1)

    # DM User
    try:
        if is_kick:
            action_verb = "Kicked"
        elif is_softban:
            action_verb = "Softbanned (Kicked + Messages Purged)"
        elif is_ban:
            action_verb = "Banned" if minutes == -1 else f"Banned for {format_duration(minutes)}"
        else:
            action_verb = "Warned" if is_warning else "Timed Out"

        dm_embed = make_embed(
            "Moderation Action Issued",
            f"> You have been **{action_verb}** in **{interaction.guild.name}**.",
            kind="danger",
            scope=SCOPE_MODERATION,
            guild=interaction.guild,
            thumbnail=interaction.guild.icon.url if interaction.guild.icon else None,
        )
        dm_embed.add_field(name="Reason", value=format_reason_value(reason, limit=1000), inline=False)
        if user_msg:
            dm_embed.add_field(name="Moderator Message", value=format_log_quote(user_msg, limit=1024), inline=False)

        if punishment_type == "timeout":
            dm_embed.add_field(name="Duration", value=format_duration(minutes), inline=True)
            unmute_dt = discord.utils.utcnow() + get_valid_duration(minutes if minutes > 0 else 0)
            dm_embed.add_field(name="Expires", value=discord.utils.format_dt(unmute_dt, "R"), inline=True)
        elif is_ban and minutes == -1:
            dm_embed.add_field(name="Duration", value="Ban", inline=True)

        if interaction.guild.icon:
            dm_embed.set_thumbnail(url=interaction.guild.icon.url)

        await target.send(embed=dm_embed, view=build_appeal_view(interaction.guild.id, record["case_id"]))
    except discord.Forbidden:
        pass
    
    # Update Stats
    bot.data_manager.config["stats"]["total_issued"] = bot.data_manager.config["stats"].get("total_issued", 0) + 1
    bot.data_manager.mark_config_dirty()
    await bot.data_manager.save_all()

    if is_kick:
        status = "Kicked"
    elif is_softban:
        status = "Softbanned"
    elif is_ban:
        status = "Banned"
    else:
        status = "Warning Logged" if is_warning else ("Escalated (Recidivism)" if is_escalated else "Standard")
        
    if reason == "Custom Punishment":
        status = "Custom"
        if is_ban: status = "Custom (Ban)"

    log_embed = build_punishment_execution_log_embed(
        guild=interaction.guild,
        case_label=case_label,
        actor=format_user_ref(moderator),
        target=format_user_ref(target),
        record=record,
        thumbnail=target.display_avatar.url,
    )

    # Optional: capture chat logs and/or purge the target's recent messages
    # (server-wide). One sweep covers both; a purge always saves an evidence
    # transcript to the mod log before deleting.
    purge_deleted = 0
    saved_count = 0
    if purge_count or save_count:
        needed = max(purge_count, save_count)
        try:
            recent_messages = await collect_user_messages(guild, target.id, needed)
        except Exception:
            recent_messages = []

        if save_count and recent_messages:
            captured = recent_messages[:save_count]
            try:
                data = build_user_transcript(captured, target)
                capture_embed = make_embed(
                    "Chat Log Captured",
                    f"> Saved **{len(captured)}** recent message(s) from {format_user_ref(target)} (no deletion).",
                    kind="info",
                    scope=SCOPE_MODERATION,
                    guild=guild,
                    thumbnail=target.display_avatar.url,
                )
                capture_embed.add_field(name="Case", value=case_label, inline=True)
                await send_punishment_log(guild, capture_embed, attachments=[(f"chatlog_{target.id}.html", data)])
                saved_count = len(captured)
            except Exception:
                pass

        if purge_count and recent_messages:
            to_purge = recent_messages[:purge_count]
            try:
                data = build_user_transcript(to_purge, target)
                evidence_embed = make_embed(
                    "Purge Evidence",
                    f"> Saved **{len(to_purge)}** message(s) from {format_user_ref(target)} before purging.",
                    kind="warning",
                    scope=SCOPE_MODERATION,
                    guild=guild,
                    thumbnail=target.display_avatar.url,
                )
                evidence_embed.add_field(name="Case", value=case_label, inline=True)
                await send_punishment_log(guild, evidence_embed, attachments=[(f"purged_{target.id}.html", data)])
            except Exception:
                pass
            purge_deleted = await delete_messages_efficiently(to_purge)

    # Response Embed (Private)
    response_embed = make_embed(
        "Action Successful",
        f"> **{target.mention}** has been punished successfully.",
        kind="success",
        scope=SCOPE_MODERATION,
        guild=interaction.guild,
        thumbnail=target.display_avatar.url,
    )
    response_embed.add_field(name="Case", value=case_label, inline=True)
    response_embed.add_field(name="Reason", value=format_reason_value(reason, limit=500), inline=False)
    response_embed.add_field(name="Type", value=status, inline=True)
    if not is_warning:
        response_embed.add_field(name="Duration", value=format_duration(minutes), inline=True)
    if purge_count or save_count:
        parts = []
        if save_count:
            parts.append(f"Saved {saved_count} message(s) to the log")
        if purge_count:
            parts.append(f"Purged {purge_deleted} message(s)")
        if parts:
            response_embed.add_field(name="Message Actions", value="> " + "; ".join(parts), inline=False)
    
    if interaction.message:
        try:
            await interaction.message.edit(content=None, embed=response_embed, view=build_case_link_view(record["case_id"]))
        except Exception:
            await interaction.followup.send(embed=response_embed, view=build_case_link_view(record["case_id"]), ephemeral=True)
    else:
        await interaction.followup.send(embed=response_embed, view=build_case_link_view(record["case_id"]), ephemeral=True)

    try:
        await interaction.delete_original_response()
    except Exception:
        pass

    if public:
        pub_embed = make_embed(
            f"{case_label} Issued",
            f"> **{target.mention}** has been punished.",
            kind="danger",
            scope=SCOPE_MODERATION,
            guild=interaction.guild,
        )
        pub_embed.add_field(name="Reason", value=format_reason_value(reason, limit=200), inline=False)
        pub_embed.add_field(name="Type", value=status, inline=True)
        if not is_warning and minutes != 0:
             pub_embed.add_field(name="Duration", value=format_duration(minutes), inline=True)
        try:
            await interaction.channel.send(embed=pub_embed)
        except Exception:
            pass

    await send_punishment_log(interaction.guild, log_embed, view=build_case_link_view(record["case_id"]))

    if origin_message:
        try:
            await origin_message.edit(embed=build_punish_embed(target))
        except Exception:
            pass

# ----------------- Embeds -----------------

class PunishDetailsModal(discord.ui.Modal):
    def __init__(self, target, moderator, reason, rules, origin_message=None, public=False, reaction_count=None, purge_count=0, save_count=0, evidence_message=None):
        super().__init__(title=f"Punish: {target.display_name}")
        self.target = target
        self.moderator = moderator
        self.reason = reason
        self.rules = rules
        self.origin_message = origin_message
        self.public = public
        self.reaction_count = reaction_count
        self.purge_count = purge_count
        self.save_count = save_count
        self.evidence_message = evidence_message

    mod_note = discord.ui.TextInput(
        label="Moderator Note (Internal)",
        style=discord.TextStyle.paragraph,
        placeholder="Visible only to staff. Required.",
        required=True
    )

    mod_message = discord.ui.TextInput(
        label="Message to User (Optional)",
        style=discord.TextStyle.paragraph,
        placeholder="Visible to the user. Explain why they are being punished.",
        required=False
    )
    
    duration_override = discord.ui.TextInput(
        label="Duration/Type Override (Optional)",
        placeholder="e.g. 2d, 1w, ban, warn, kick. Leave blank for auto.",
        required=False
    )

    async def on_submit(self, interaction: discord.Interaction) -> None:
        await interaction.response.defer(ephemeral=True)
        
        reason = self.reason
        rules = self.rules
        note = self.mod_note.value
        user_msg = self.mod_message.value
        override = self.duration_override.value.strip().lower()
        
        minutes = 0
        is_escalated = False
        punishment_type = "auto"

        if override:
            if override == "kick":
                punishment_type = "kick"
            elif override == "softban":
                punishment_type = "softban"
            else:
                minutes = parse_duration_str(override)
                if minutes == -1: punishment_type = "ban"
                elif minutes == 0: punishment_type = "warn"
        else:
            # Use advanced calculation
            minutes, is_escalated, _ = calculate_offense_punishment(rules, bot.data_manager.punishments.get(str(self.target.id), []))
        
        if self.reaction_count:
            action_verb = "Punish"
            if punishment_type == "ban": action_verb = "Ban"
            elif punishment_type == "kick": action_verb = "Kick"
            elif punishment_type == "timeout": action_verb = "Timeout"
            elif punishment_type == "warn": action_verb = "Warn"
            elif punishment_type == "softban": action_verb = "Softban"

            embed = make_embed(
                "Public Execution Started",
                f"React to this message to **{action_verb}** {self.target.mention}.\n\nThe execution will happen when **{self.reaction_count}** reactions are reached.",
                kind="danger",
                scope=SCOPE_MODERATION,
                guild=interaction.guild,
                thumbnail=self.target.display_avatar.url,
            )
            embed.add_field(name="Reason", value=format_reason_value(reason, limit=200), inline=False)
            if minutes > 0:
                embed.add_field(name="Duration", value=format_duration(minutes), inline=True)
            
            msg = await interaction.followup.send(embed=embed, ephemeral=False)
            await msg.add_reaction("✅")
            
            bot.active_executions[msg.id] = {
                "target_id": self.target.id,
                "count": self.reaction_count,
                "reason": reason,
                "note": note,
                "user_msg": user_msg,
                "moderator_id": self.moderator.id,
                "duration": minutes,
                "type": punishment_type,
                "escalated": is_escalated
            }
            return

        await execute_punishment(
            interaction,
            self.target,
            self.moderator,
            reason,
            minutes,
            note,
            user_msg,
            is_escalated,
            self.origin_message,
            punishment_type=punishment_type,
            public=self.public,
            purge_count=self.purge_count,
            save_count=self.save_count,
            evidence_message=self.evidence_message,
        )

class CustomPunishDetailsModal(discord.ui.Modal):
    def __init__(self, target, moderator, p_type, origin_message, public=False, reaction_count=None, purge_count=0, save_count=0, evidence_message=None):
        super().__init__(title=f"Configure {p_type.replace('_', ' ').title()}")
        self.target = target
        self.moderator = moderator
        self.p_type = p_type
        self.origin_message = origin_message
        self.public = public
        self.reaction_count = reaction_count
        self.purge_count = purge_count
        self.save_count = save_count
        self.evidence_message = evidence_message
        
        self.custom_reason = discord.ui.TextInput(
            label="Reason",
            placeholder="e.g. Violation of rules",
            max_length=100,
            required=True
        )
        self.add_item(self.custom_reason)
        
        self.duration_str = None
        if p_type in ["timeout", "ban_temp"]:
            self.duration_str = discord.ui.TextInput(
                label="Duration",
                placeholder="e.g. 1h, 30m, 1d",
                max_length=20,
                required=True
            )
            self.add_item(self.duration_str)
            
        self.mod_note = discord.ui.TextInput(
            label="Moderator Note (Internal)",
            style=discord.TextStyle.paragraph,
            placeholder="Visible only to staff.",
            required=True
        )
        self.add_item(self.mod_note)
        
        self.mod_message = discord.ui.TextInput(
            label="Message to User (Optional)",
            style=discord.TextStyle.paragraph,
            placeholder="Visible to the user.",
            required=False
        )
        self.add_item(self.mod_message)

    async def on_submit(self, interaction: discord.Interaction) -> None:
        await interaction.response.defer(ephemeral=True)
        
        minutes = 0
        final_type = self.p_type
        
        if self.p_type == "ban_perm":
            final_type = "ban"
            minutes = -1
        elif self.p_type == "ban_temp":
            final_type = "ban"
            if self.duration_str:
                minutes = parse_duration_str(self.duration_str.value)
                if minutes <= 0:
                    await interaction.followup.send(embed=make_embed("Invalid Duration", "> Invalid duration for temporary ban.", kind="error", scope=SCOPE_MODERATION, guild=interaction.guild), ephemeral=True)
                    return
        elif self.p_type == "timeout":
            final_type = "timeout"
            if self.duration_str:
                minutes = parse_duration_str(self.duration_str.value)
                if minutes <= 0:
                    await interaction.followup.send(embed=make_embed("Invalid Duration", "> Invalid duration for timeout.", kind="error", scope=SCOPE_MODERATION, guild=interaction.guild), ephemeral=True)
                    return
        elif self.p_type == "kick":
            final_type = "kick"
            minutes = 0
        elif self.p_type == "softban":
            final_type = "softban"
            minutes = 0
        elif self.p_type == "warn":
            final_type = "warn"
            minutes = 0

        if self.reaction_count:
            action_verb = "Punish"
            if final_type == "ban": action_verb = "Ban"
            elif final_type == "kick": action_verb = "Kick"
            elif final_type == "timeout": action_verb = "Timeout"
            elif final_type == "warn": action_verb = "Warn"
            elif final_type == "softban": action_verb = "Softban"

            embed = make_embed(
                "Public Execution Started",
                f"React to this message to **{action_verb}** {self.target.mention}.\n\nThe execution will happen when **{self.reaction_count}** reactions are reached.",
                kind="danger",
                scope=SCOPE_MODERATION,
                guild=interaction.guild,
                thumbnail=self.target.display_avatar.url,
            )
            embed.add_field(name="Reason", value=format_reason_value(self.custom_reason.value, limit=200), inline=False)
            if minutes > 0:
                embed.add_field(name="Duration", value=format_duration(minutes), inline=True)
            
            msg = await interaction.followup.send(embed=embed, ephemeral=False)
            await msg.add_reaction("✅")
            
            bot.active_executions[msg.id] = {
                "target_id": self.target.id,
                "count": self.reaction_count,
                "reason": self.custom_reason.value,
                "note": self.mod_note.value,
                "user_msg": self.mod_message.value,
                "moderator_id": self.moderator.id,
                "duration": minutes,
                "type": final_type,
                "escalated": False
            }
            return

        await execute_punishment(
            interaction, 
            self.target, 
            self.moderator, 
            self.custom_reason.value, 
            minutes, 
            self.mod_note.value, 
            self.mod_message.value, 
            False, # Custom punishments don't follow auto-escalation logic
            self.origin_message,
            punishment_type=final_type,
            public=self.public,
            purge_count=self.purge_count,
            save_count=self.save_count,
            evidence_message=self.evidence_message,
        )

class CustomTypeSelect(discord.ui.Select):
    def __init__(self, target, moderator, origin_message, public=False, reaction_count=None, purge_count=0, save_count=0, evidence_message=None):
        self.target = target
        self.moderator = moderator
        self.origin_message = origin_message
        self.public = public
        self.reaction_count = reaction_count
        self.purge_count = purge_count
        self.save_count = save_count
        self.evidence_message = evidence_message
        options = [
            discord.SelectOption(label="Timeout", value="timeout", description="Mute user for a duration"),
            discord.SelectOption(label="Kick", value="kick", description="Remove user from server"),
            discord.SelectOption(label="Softban", value="softban", description="Kick + Delete Messages"),
            discord.SelectOption(label="Ban (Temporary)", value="ban_temp", description="Ban for a duration"),
            discord.SelectOption(label="Ban (Permanent)", value="ban_perm", description="Ban indefinitely"),
            discord.SelectOption(label="Warning", value="warn", description="Log a warning")
        ]
        super().__init__(placeholder="Select punishment type...", min_values=1, max_values=1, options=options)

    async def callback(self, interaction: discord.Interaction) -> None:
        p_type = self.values[0]
        await interaction.response.send_modal(CustomPunishDetailsModal(self.target, self.moderator, p_type, self.origin_message, public=self.public, reaction_count=self.reaction_count, purge_count=self.purge_count, save_count=self.save_count, evidence_message=self.evidence_message))

class CustomTypeView(discord.ui.View):
    def __init__(self, target, moderator, origin_message, public=False, reaction_count=None, purge_count=0, save_count=0, evidence_message=None):
        super().__init__(timeout=60)
        self.add_item(CustomTypeSelect(target, moderator, origin_message, public=public, reaction_count=reaction_count, purge_count=purge_count, save_count=save_count, evidence_message=evidence_message))

class PunishSelect(discord.ui.Select):
    def __init__(self, target: discord.User, moderator: discord.Member):
        self.target = target
        self.moderator = moderator
        rules_config = bot.data_manager.config.get("punishment_rules", DEFAULT_RULES)
        options = []
        for reason, rules in rules_config.items():
            base_str = format_duration(rules['base'])
            esc_str = format_duration(rules['escalated'])
            if rules['base'] == 0:
                desc = f"1st: Warning • Repeat: {esc_str}"
            else:
                desc = f"Base: {base_str} • Repeat: {esc_str}"
            options.append(discord.SelectOption(label=reason, description=desc))
        options.append(discord.SelectOption(label="Custom Punishment", value="custom", description="Define a custom reason and duration."))
        super().__init__(placeholder="Select a punishment reason...", min_values=1, max_values=1, options=options, row=0)

    async def callback(self, interaction: discord.Interaction) -> None:
        # Read the current modifier state off the panel so the toggle buttons take effect.
        view: "PunishView" = self.view
        public = getattr(view, "public", False)
        reaction_count = getattr(view, "reaction_count", None)
        purge_count = getattr(view, "purge_count", 0)
        save_count = getattr(view, "save_count", 0)
        evidence_message = getattr(view, "evidence_message", None)

        if self.values[0] == "custom":
            await interaction.response.send_message(embed=make_embed("Custom Punishment", "> Select the type of custom punishment below.", kind="info", scope=SCOPE_MODERATION, guild=interaction.guild), view=CustomTypeView(self.target, self.moderator, interaction.message, public=public, reaction_count=reaction_count, purge_count=purge_count, save_count=save_count, evidence_message=evidence_message), ephemeral=True)
            return
        reason = self.values[0]
        rules_config = bot.data_manager.config.get("punishment_rules", DEFAULT_RULES)
        rules = rules_config.get(reason)
        if not rules:
            return
        await interaction.response.send_modal(PunishDetailsModal(self.target, self.moderator, reason, rules, interaction.message, public=public, reaction_count=reaction_count, purge_count=purge_count, save_count=save_count, evidence_message=evidence_message))


class PunishCountModal(discord.ui.Modal):
    """Popup launched by the Purge / Save Logs buttons to set how many of the
    target's recent messages to act on."""
    def __init__(self, parent_view: "PunishView", kind: str):
        super().__init__(title="Purge Messages" if kind == "purge" else "Save Chat Logs")
        self.parent_view = parent_view
        self.kind = kind
        current = parent_view.purge_count if kind == "purge" else parent_view.save_count
        action = "delete" if kind == "purge" else "save to the mod log"
        self.count_input = discord.ui.TextInput(
            label="How many recent messages?",
            placeholder=f"0–500. How many to {action}. 0 turns it off.",
            default=str(current) if current else "",
            required=False,
            max_length=3,
        )
        self.add_item(self.count_input)

    async def on_submit(self, interaction: discord.Interaction) -> None:
        digits = "".join(ch for ch in self.count_input.value if ch.isdigit())
        value = max(0, min(500, int(digits))) if digits else 0
        if self.kind == "purge":
            self.parent_view.purge_count = value
        else:
            self.parent_view.save_count = value
        self.parent_view.sync_modifier_buttons()
        await interaction.response.edit_message(view=self.parent_view)


class PunishPurgeButton(discord.ui.Button):
    def __init__(self):
        super().__init__(label="Purge Messages", style=discord.ButtonStyle.secondary, row=1)

    async def callback(self, interaction: discord.Interaction) -> None:
        await interaction.response.send_modal(PunishCountModal(self.view, "purge"))


class PunishSaveButton(discord.ui.Button):
    def __init__(self):
        super().__init__(label="Save Logs", style=discord.ButtonStyle.secondary, row=1)

    async def callback(self, interaction: discord.Interaction) -> None:
        await interaction.response.send_modal(PunishCountModal(self.view, "save"))


EXECUTION_VOTE_PRESETS = (2, 3, 5, 8, 10, 15, 20, 25)


class ExecutionVotesSelect(discord.ui.Select):
    """Vote-threshold picker shown on the punish panel in public-execution mode."""

    def __init__(self, panel: "PunishView"):
        self.panel = panel
        options = [
            discord.SelectOption(label=f"{count} votes to trigger", value=str(count), default=count == panel.reaction_count)
            for count in EXECUTION_VOTE_PRESETS
        ]
        if panel.reaction_count not in EXECUTION_VOTE_PRESETS:
            options.insert(0, discord.SelectOption(label=f"{panel.reaction_count} votes to trigger", value=str(panel.reaction_count), default=True))
        super().__init__(placeholder="Votes required to trigger...", min_values=1, max_values=1, options=options, row=1)

    async def callback(self, interaction: discord.Interaction) -> None:
        view = self.panel
        view.reaction_count = int(self.values[0])
        view.remove_item(self)
        view.add_item(ExecutionVotesSelect(view))
        await interaction.response.edit_message(view=view)


class PunishView(discord.ui.View):
    def __init__(self, target, moderator, public=False, reaction_count=None, purge_count=0, save_count=0, evidence_message=None):
        super().__init__(timeout=120)
        self.target = target
        self.moderator = moderator
        self.public = public
        self.reaction_count = reaction_count
        self.purge_count = purge_count
        self.save_count = save_count
        self.evidence_message = evidence_message
        self.purge_btn = None
        self.save_btn = None
        self.add_item(PunishSelect(target, moderator))
        # Message capture controls apply only to direct punishments; public
        # execution uses its vote-threshold selector instead.
        if reaction_count is None:
            self.purge_btn = PunishPurgeButton()
            self.save_btn = PunishSaveButton()
            self.add_item(self.purge_btn)
            self.add_item(self.save_btn)
            self.sync_modifier_buttons()
        else:
            self.add_item(ExecutionVotesSelect(self))

    def sync_modifier_buttons(self) -> None:
        if self.purge_btn is not None:
            self.purge_btn.label = f"Purge Messages: {self.purge_count}" if self.purge_count else "Purge Messages"
            self.purge_btn.style = discord.ButtonStyle.danger if self.purge_count else discord.ButtonStyle.secondary
        if self.save_btn is not None:
            self.save_btn.label = f"Save Logs: {self.save_count}" if self.save_count else "Save Logs"
            self.save_btn.style = discord.ButtonStyle.primary if self.save_count else discord.ButtonStyle.secondary

# Undone records are stashed here (capped) so the undo log's "Revoke Undo"
# button can restore them even after a bot restart.
UNDONE_CASE_CACHE_LIMIT = 50


def stash_undone_case(target_id: int, record: dict) -> None:
    case_id = record.get("case_id")
    if not isinstance(case_id, int):
        return
    store = bot.data_manager.config.setdefault("undone_cases", {})
    store[str(case_id)] = {"target_id": target_id, "record": record}
    while len(store) > UNDONE_CASE_CACHE_LIMIT:
        del store[min(store, key=int)]
    bot.data_manager.mark_config_dirty()


class RevokeUndoButton(
    discord.ui.DynamicItem[discord.ui.Button],
    template=r"case:revoke_undo:(?P<case_id>[0-9]+)",
):
    """Restart-surviving 'Revoke Undo' button on undo log messages."""

    def __init__(self, case_id: int) -> None:
        super().__init__(
            discord.ui.Button(
                label="Revoke Undo",
                style=discord.ButtonStyle.danger,
                custom_id=f"case:revoke_undo:{case_id}",
            )
        )
        self.case_id = case_id

    @classmethod
    async def from_custom_id(cls, interaction: discord.Interaction, item: discord.ui.Button, match, /) -> "RevokeUndoButton":
        return cls(int(match["case_id"]))

    async def callback(self, interaction: discord.Interaction) -> None:
        if not is_staff(interaction):
            await interaction.response.send_message(embed=make_embed("Access Denied", "> You do not have permission to use this.", kind="error", scope=SCOPE_MODERATION, guild=interaction.guild), ephemeral=True)
            return

        store = bot.data_manager.config.get("undone_cases", {})
        entry = store.pop(str(self.case_id), None)
        if not entry:
            await interaction.response.send_message(embed=make_embed("Not Available", "> This undo can no longer be revoked — it was already restored or has expired.", kind="muted", scope=SCOPE_MODERATION, guild=interaction.guild), ephemeral=True)
            return
        bot.data_manager.mark_config_dirty()

        await interaction.response.defer()

        record = entry.get("record") or {}
        target_id = int(entry.get("target_id") or 0)
        await bot.data_manager.add_punishment(str(target_id), record)
        await bot.data_manager.save_config()

        # Re-apply physical punishment
        guild = interaction.guild
        target = guild.get_member(target_id)
        if not target:
            try:
                target = await bot.fetch_user(target_id)
            except Exception:
                pass

        action_taken = "History Restored"
        p_type = record.get("type")
        dur = record.get("duration_minutes", 0)

        try:
            if p_type == "ban":
                await guild.ban(discord.Object(id=target_id), reason="Undo Revoked: Restoring Punishment")
                action_taken += " & User Banned"
            elif p_type == "timeout" and isinstance(target, discord.Member):
                if dur > 0:
                    await target.timeout(get_valid_duration(dur), reason="Undo Revoked: Restoring Punishment")
                    action_taken += " & User Timed Out"
        except Exception as e:
            action_taken += f" (Physical action failed: {e})"

        embed = interaction.message.embeds[0]
        embed.color = discord.Color.orange()
        embed.add_field(name="Update", value=f"> **Undo Revoked** by {interaction.user.mention}\n> {action_taken}", inline=False)
        await interaction.edit_original_response(embed=embed, view=None)


def build_revoke_undo_view(case_id: int) -> discord.ui.View:
    view = discord.ui.View(timeout=None)
    view.add_item(RevokeUndoButton(case_id))
    return view

async def show_punish_menu(interaction: discord.Interaction, user: discord.User, reaction_count=None, evidence_message=None):
    await interaction.response.defer(ephemeral=True)
    embed = build_punish_embed(user, evidence_message=evidence_message)
    view = PunishView(user, interaction.user, reaction_count=reaction_count, evidence_message=evidence_message)
    await interaction.followup.send(embed=embed, view=view, ephemeral=True)

async def show_history_menu(
    interaction: discord.Interaction,
    user: discord.Member,
    *,
    mode: str = "history",
    selected_case_id: Optional[int] = None,
    initial_undo_reason: Optional[str] = None,
):
    await interaction.response.defer(ephemeral=True)
    uid = str(user.id)
    history_data = bot.data_manager.punishments.get(uid, [])
    if not history_data:
        await interaction.followup.send(embed=build_no_history_embed(user, interaction.guild), ephemeral=True)
        return
    view = HistoryView(
        user,
        mode=mode,
        selected_case_id=selected_case_id,
        initial_undo_reason=initial_undo_reason,
    )
    message = await interaction.followup.send(embed=view.build_embed(), view=view, ephemeral=True, wait=True)
    view.message = message


def _staff_check(interaction: discord.Interaction) -> bool:
    return is_staff(interaction)


async def _resolve_selected_member(interaction: discord.Interaction, selected_user: Union[discord.Member, discord.User]) -> Optional[discord.Member]:
    if isinstance(selected_user, discord.Member):
        return selected_user
    return await resolve_member(interaction.guild, selected_user.id)


async def _resolve_user_id_input(
    interaction: discord.Interaction,
    raw: str,
) -> Optional[Union[discord.Member, discord.User]]:
    """Resolve a raw user-ID or mention string to a Member (preferred) or User.

    Sends an error response and returns None when the input is malformed or no
    matching user exists. Used as a reliable fallback for the native user:
    picker, which can fail to select some real members client-side.
    """
    digits = "".join(ch for ch in raw if ch.isdigit())
    if not digits:
        await respond_with_error(interaction, "That isn't a valid user ID or mention.", scope=SCOPE_MODERATION)
        return None

    uid = int(digits)
    target: Optional[Union[discord.Member, discord.User]] = None
    if interaction.guild is not None:
        target = await resolve_member(interaction.guild, uid)
    if target is None:
        try:
            target = await bot.fetch_user(uid)
        except (discord.NotFound, discord.HTTPException):
            target = None
    if target is None:
        await respond_with_error(interaction, "No user was found with that ID.", scope=SCOPE_MODERATION)
        return None
    return target


async def _resolve_message_input(interaction: discord.Interaction, raw: str) -> Optional[discord.Message]:
    ids = re.findall(r"\d{15,22}", str(raw or ""))
    if not ids:
        await respond_with_error(interaction, "Enter a valid message ID or Discord message link.", scope=SCOPE_MODERATION)
        return None

    message_id = int(ids[-1])
    channel = interaction.channel
    if len(ids) >= 2 and interaction.guild is not None:
        channel_id = int(ids[-2])
        channel = interaction.guild.get_channel_or_thread(channel_id)
        if channel is None:
            try:
                channel = await interaction.guild.fetch_channel(channel_id)
            except (discord.NotFound, discord.Forbidden, discord.HTTPException):
                channel = None
    if channel is None or not hasattr(channel, "fetch_message"):
        await respond_with_error(interaction, "I could not access the channel for that message.", scope=SCOPE_MODERATION)
        return None

    try:
        message = await channel.fetch_message(message_id)
    except discord.NotFound:
        await respond_with_error(interaction, "No message with that ID was found in the selected channel.", scope=SCOPE_MODERATION)
        return None
    except (discord.Forbidden, discord.HTTPException):
        await respond_with_error(interaction, "I could not read that message or channel.", scope=SCOPE_MODERATION)
        return None

    if message.guild is None or interaction.guild is None or message.guild.id != interaction.guild.id:
        await respond_with_error(interaction, "The message must be from this server.", scope=SCOPE_MODERATION)
        return None
    if message.author.bot:
        await respond_with_error(interaction, "Bot messages cannot be punished.", scope=SCOPE_MODERATION)
        return None
    return message


async def _resolve_message_author(interaction: discord.Interaction, message: discord.Message):
    if isinstance(message.author, discord.Member):
        return message.author
    member = await resolve_member(interaction.guild, message.author.id)
    return member or message.author


class CaseIdModal(discord.ui.Modal, title="Open Case by ID"):
    case_id = discord.ui.TextInput(label="Case ID", placeholder="123", max_length=12)

    async def on_submit(self, interaction: discord.Interaction) -> None:
        try:
            selected_case_id = int(self.case_id.value.strip())
        except ValueError:
            await respond_with_error(interaction, "Enter a valid numeric case ID.", scope=SCOPE_MODERATION)
            return
        await show_case_panel(interaction, case_id=selected_case_id)


class ModerationTargetSelect(discord.ui.UserSelect):
    def __init__(self, parent: "ModerationTargetPickerView"):
        super().__init__(
            placeholder="Choose a member...",
            min_values=1,
            max_values=1,
            row=0,
        )
        self._target_view = parent

    async def callback(self, interaction: discord.Interaction) -> None:
        await self._target_view.handle_user(interaction, self.values[0])


class ModerationTargetPickerView(discord.ui.View):
    def __init__(self, *, requester_id: int, action: str, initial_undo_reason: Optional[str] = None, reaction_count: Optional[int] = None):
        super().__init__(timeout=180)
        self.requester_id = requester_id
        self.action = action
        self.initial_undo_reason = initial_undo_reason
        self.reaction_count = reaction_count
        self.add_item(ModerationTargetSelect(self))
        if action == "case":
            self.add_item(CaseIdButton())

    async def interaction_check(self, interaction: discord.Interaction) -> bool:
        if interaction.user.id == self.requester_id:
            return True
        await interaction.response.send_message(embed=make_embed("Access Denied", "> This picker belongs to another moderator.", kind="error", scope=SCOPE_MODERATION, guild=interaction.guild), ephemeral=True)
        return False

    async def handle_user(self, interaction: discord.Interaction, selected_user: Union[discord.Member, discord.User]) -> None:
        if not is_staff(interaction):
            await interaction.response.send_message(embed=make_embed("Access Denied", "> You do not have permission to use this panel.", kind="error", scope=SCOPE_MODERATION, guild=interaction.guild), ephemeral=True)
            return

        if self.action == "punish":
            await show_punish_menu(interaction, selected_user, reaction_count=self.reaction_count)
            return

        member = await _resolve_selected_member(interaction, selected_user)
        if member is None:
            await respond_with_error(interaction, "That user is not currently in this server.", scope=SCOPE_MODERATION)
            return

        if self.action == "history":
            await show_history_menu(interaction, member)
            return
        if self.action == "undo":
            await show_history_menu(interaction, member, mode="undo", initial_undo_reason=self.initial_undo_reason)
            return
        if self.action == "case":
            await show_case_panel(interaction, user=member)


class CaseIdButton(discord.ui.Button):
    def __init__(self):
        super().__init__(label="Open by Case ID", style=discord.ButtonStyle.secondary, row=1)

    async def callback(self, interaction: discord.Interaction) -> None:
        await interaction.response.send_modal(CaseIdModal())


async def send_target_picker(
    interaction: discord.Interaction,
    *,
    action: str,
    title: str,
    description: str,
    initial_undo_reason: Optional[str] = None,
    reaction_count: Optional[int] = None,
) -> None:
    embed = make_embed(
        title,
        description,
        kind="info",
        scope=SCOPE_MODERATION,
        guild=interaction.guild,
    )
    await interaction.response.send_message(
        embed=embed,
        view=ModerationTargetPickerView(
            requester_id=interaction.user.id,
            action=action,
            initial_undo_reason=initial_undo_reason,
            reaction_count=reaction_count,
        ),
        ephemeral=True,
    )


@tree.command(name="punish", description="Open the punishment panel for a member.")
@app_commands.describe(
    user="The member to punish.",
    userid="A user ID or mention. Use this if the member isn't selectable in the user picker.",
    message_id="A message ID or link to save as evidence and delete after punishment.",
)
@app_commands.check(_staff_check)
async def punish(
    interaction: discord.Interaction,
    user: Optional[discord.User] = None,
    userid: Optional[str] = None,
    message_id: Optional[str] = None,
):
    # `userid` is a reliable fallback for the native user picker, which (being
    # client-side) silently fails to select some real members in larger servers.
    # Post-to-channel, purge, and save-logs are toggles on the panel itself.
    if message_id:
        evidence_message = await _resolve_message_input(interaction, message_id)
        if evidence_message is None:
            return
        message_target = await _resolve_message_author(interaction, evidence_message)
        if user is not None and user.id != message_target.id:
            await respond_with_error(interaction, "The selected user is not the author of that message.", scope=SCOPE_MODERATION)
            return
        if userid:
            requested_target = await _resolve_user_id_input(interaction, userid)
            if requested_target is None:
                return
            if requested_target.id != message_target.id:
                await respond_with_error(interaction, "The supplied user ID is not the author of that message.", scope=SCOPE_MODERATION)
                return
        await show_punish_menu(interaction, message_target, evidence_message=evidence_message)
        return

    if user is None and userid:
        target = await _resolve_user_id_input(interaction, userid)
        if target is None:
            return
        await show_punish_menu(interaction, target)
        return

    if user is None:
        await send_target_picker(
            interaction,
            action="punish",
            title="Choose a Target",
            description="> Select a member to open the punishment panel.",
        )
        return
    # The inline `user:` option can resolve to a bare discord.User (no guild
    # member data) for some accounts, which later blocks muting/kicking even
    # though the target is in the server. Upgrade to a full guild Member up
    # front so this path matches the in-app member picker.
    if not isinstance(user, discord.Member) and interaction.guild is not None:
        member = await resolve_member(interaction.guild, user.id)
        if member is not None:
            user = member
    await show_punish_menu(interaction, user)


@tree.command(name="publicexecution", description="Put a member up for a community-vote punishment.")
@app_commands.describe(user="The member to put up for the vote.")
@app_commands.check(_staff_check)
async def publicexecution(interaction: discord.Interaction, user: Optional[discord.User] = None):
    # Same target-selection flow as /punish, but the chosen punishment is held
    # until enough ✅ reactions land on a public embed (see PunishDetailsModal
    # / CustomPunishDetailsModal, counted in cogs/events.py:on_raw_reaction_add).
    # The vote threshold is adjusted on the panel itself (ExecutionVotesSelect).
    if user is None:
        await send_target_picker(
            interaction,
            action="punish",
            title="Choose a Target",
            description="> Select a member to put up for a public execution. The vote threshold can be adjusted on the panel.",
            reaction_count=5,
        )
        return

    if not isinstance(user, discord.Member) and interaction.guild is not None:
        member = await resolve_member(interaction.guild, user.id)
        if member is not None:
            user = member
    await show_punish_menu(interaction, user, reaction_count=5)


@tree.command(name="history", description="View a member's moderation history.")
@app_commands.describe(user="The member whose history to view.")
@app_commands.check(_staff_check)
async def history(interaction: discord.Interaction, user: Optional[discord.Member] = None):
    if user is None:
        await send_target_picker(
            interaction,
            action="history",
            title="Choose a Member",
            description="> Select a member to view their moderation history.",
        )
        return
    await show_history_menu(interaction, user)


@tree.command(name="cases", description="Browse every moderation case on the server in case order.")
@app_commands.check(_staff_check)
async def cases(interaction: discord.Interaction):
    await interaction.response.defer(ephemeral=True)
    view = AllCasesView(interaction.guild)
    if not view.cases:
        await interaction.followup.send(
            embed=make_empty_state_embed(
                "No Cases",
                "> No moderation cases have been recorded yet.",
                scope=SCOPE_MODERATION,
                guild=interaction.guild,
            ),
            ephemeral=True,
        )
        return
    message = await interaction.followup.send(embed=view.build_embed(), view=view, ephemeral=True, wait=True)
    view.message = message


@tree.command(name="undo", description="Reverse a logged moderation action.")
@app_commands.describe(
    user="The member whose action to reverse.",
    reason="Reason to prefill in the undo panel.",
)
@app_commands.check(_staff_check)
async def undo(interaction: discord.Interaction, user: Optional[discord.Member] = None, reason: Optional[str] = None):
    if user is None:
        await send_target_picker(
            interaction,
            action="undo",
            title="Choose a Member",
            description="> Select a member to open the undo panel.",
            initial_undo_reason=reason,
        )
        return
    await show_history_menu(interaction, user, mode="undo", initial_undo_reason=reason)


# Filtered purges scan at most this many recent messages. Old messages need
# per-message deletes anyway (no bulk past 14 days), so a deeper scan mostly
# burns API time for nothing.
PURGE_SCAN_LIMIT = 2000


async def execute_purge(channel, amount: int, target_id: Optional[int], keyword: Optional[str]) -> int:
    if target_id is None and not keyword:
        deleted = await channel.purge(limit=amount)
        return len(deleted)

    # Filtered path: scan once, stopping as soon as we have `amount` matches,
    # then hand off to the shared bulk-delete helper.
    keyword_lower = keyword.lower() if keyword else None
    matched = []
    async for message in channel.history(limit=PURGE_SCAN_LIMIT):
        if len(matched) >= amount:
            break
        if target_id and message.author.id != target_id:
            continue
        if keyword_lower and keyword_lower not in message.content.lower():
            continue
        matched.append(message)
    return await delete_messages_efficiently(matched)


async def send_purge_log(interaction: discord.Interaction, deleted: int, target_id: Optional[int], keyword: Optional[str]) -> None:
    if deleted == 0:
        return
    log_embed = make_embed(
        "Messages Purged",
        "> A bulk message purge was executed in a channel.",
        kind="warning",
        scope=SCOPE_MODERATION,
        guild=interaction.guild,
    )
    log_embed.add_field(name="Actor", value=format_user_ref(interaction.user), inline=True)
    log_embed.add_field(name="Channel", value=f"{interaction.channel.mention} (`{interaction.channel.id}`)", inline=True)
    log_embed.add_field(name="Amount", value=str(deleted), inline=True)
    if target_id:
        log_embed.add_field(name="Target", value=f"<@{target_id}>", inline=True)
    if keyword:
        log_embed.add_field(name="Keyword", value=keyword, inline=True)
    await send_punishment_log(interaction.guild, log_embed)


class PurgeAmountSelect(discord.ui.Select):
    def __init__(self, panel: "PurgePanelView"):
        self.panel = panel
        options = [
            discord.SelectOption(label=f"{count} messages", value=str(count), default=count == panel.amount)
            for count in (10, 25, 50, 100, 250, 500)
        ]
        super().__init__(placeholder="How many messages to remove...", min_values=1, max_values=1, options=options, row=0)

    async def callback(self, interaction: discord.Interaction) -> None:
        self.panel.amount = int(self.values[0])
        await self.panel.refresh(interaction)


class PurgeUserSelect(discord.ui.UserSelect):
    def __init__(self, panel: "PurgePanelView"):
        self.panel = panel
        super().__init__(placeholder="Filter by member (optional)...", min_values=0, max_values=1, row=1)

    async def callback(self, interaction: discord.Interaction) -> None:
        self.panel.target_id = self.values[0].id if self.values else None
        await self.panel.refresh(interaction)


class PurgeFilterModal(discord.ui.Modal, title="Purge Filters"):
    user_id_input = discord.ui.TextInput(label="User ID (optional)", required=False, max_length=25, placeholder="Filter by a raw user ID, even if they left.")
    keyword_input = discord.ui.TextInput(label="Keyword (optional)", required=False, max_length=100, placeholder="Only delete messages containing this text.")

    def __init__(self, panel: "PurgePanelView"):
        super().__init__()
        self.panel = panel
        if panel.target_id:
            self.user_id_input.default = str(panel.target_id)
        if panel.keyword:
            self.keyword_input.default = panel.keyword

    async def on_submit(self, interaction: discord.Interaction) -> None:
        digits = "".join(ch for ch in self.user_id_input.value if ch.isdigit())
        self.panel.target_id = int(digits) if digits else None
        self.panel.keyword = self.keyword_input.value.strip() or None
        await self.panel.refresh(interaction)


class PurgePanelView(discord.ui.View):
    def __init__(self):
        super().__init__(timeout=180)
        self.amount = 50
        self.target_id: Optional[int] = None
        self.keyword: Optional[str] = None
        self.amount_select = PurgeAmountSelect(self)
        self.add_item(self.amount_select)
        self.add_item(PurgeUserSelect(self))

    def build_embed(self, guild: Optional[discord.Guild]) -> discord.Embed:
        embed = make_embed(
            "Purge Messages",
            "> Set the sweep below, then press Run Purge. Filters are optional; without them the newest messages in this channel are removed.",
            kind="warning",
            scope=SCOPE_MODERATION,
            guild=guild,
        )
        embed.add_field(name="Amount", value=str(self.amount), inline=True)
        embed.add_field(name="Member Filter", value=f"<@{self.target_id}>" if self.target_id else "None", inline=True)
        embed.add_field(name="Keyword Filter", value=self.keyword or "None", inline=True)
        return embed

    async def refresh(self, interaction: discord.Interaction) -> None:
        self.remove_item(self.amount_select)
        self.amount_select = PurgeAmountSelect(self)
        self.add_item(self.amount_select)
        await interaction.response.edit_message(embed=self.build_embed(interaction.guild), view=self)

    @discord.ui.button(label="ID / Keyword Filters", style=discord.ButtonStyle.secondary, row=2)
    async def more_filters(self, interaction: discord.Interaction, button: discord.ui.Button) -> None:
        await interaction.response.send_modal(PurgeFilterModal(self))

    @discord.ui.button(label="Clear Filters", style=discord.ButtonStyle.secondary, row=2)
    async def clear_filters(self, interaction: discord.Interaction, button: discord.ui.Button) -> None:
        self.target_id = None
        self.keyword = None
        await self.refresh(interaction)

    @discord.ui.button(label="Run Purge", style=discord.ButtonStyle.danger, row=2)
    async def run(self, interaction: discord.Interaction, button: discord.ui.Button) -> None:
        await interaction.response.edit_message(embed=make_embed("Purging", "> Removing messages...", kind="muted", scope=SCOPE_MODERATION, guild=interaction.guild), view=None)
        try:
            deleted = await execute_purge(interaction.channel, self.amount, self.target_id, self.keyword)
        except discord.HTTPException as e:
            await interaction.edit_original_response(embed=make_embed("Failed to Purge", f"> Failed to purge: {e}", kind="error", scope=SCOPE_MODERATION, guild=interaction.guild))
            return
        await send_purge_log(interaction, deleted, self.target_id, self.keyword)
        if deleted == 0:
            await interaction.edit_original_response(embed=make_embed("No Messages Found", "> No matching messages found to purge.", kind="info", scope=SCOPE_MODERATION, guild=interaction.guild))
        else:
            target_str = f"<@{self.target_id}>" if self.target_id else "Anyone"
            await interaction.edit_original_response(embed=make_embed("Messages Cleared", f"> Cleared **{deleted}** messages from {target_str}.", kind="success", scope=SCOPE_MODERATION, guild=interaction.guild))
        self.stop()


@tree.command(name="purge", description="Bulk-delete recent messages; run without options to open the filter panel.")
@app_commands.describe(amount="Delete this many recent messages right away. Omit to open the filter panel.")
@app_commands.check(_staff_check)
async def purge(interaction: discord.Interaction, amount: Optional[app_commands.Range[int, 1, 999]] = None):
    if amount is None:
        view = PurgePanelView()
        await interaction.response.send_message(embed=view.build_embed(interaction.guild), view=view, ephemeral=True)
        return

    await interaction.response.defer(ephemeral=True)
    try:
        deleted = await execute_purge(interaction.channel, amount, None, None)
    except discord.HTTPException as e:
        await interaction.followup.send(embed=make_embed("Failed to Purge", f"> Failed to purge: {e}", kind="error", scope=SCOPE_MODERATION, guild=interaction.guild), ephemeral=True)
        return
    await send_purge_log(interaction, deleted, None, None)
    await interaction.followup.send(embed=make_embed("Messages Cleared", f"> Cleared **{deleted}** messages.", kind="success", scope=SCOPE_MODERATION, guild=interaction.guild), ephemeral=True)


@tree.command(name="lock", description="Lock the current channel so members can't send messages.")
@app_commands.check(_staff_check)
async def lock(interaction: discord.Interaction):
    await interaction.response.defer(ephemeral=True)
    channel = interaction.channel
    default_role = interaction.guild.default_role
    overwrite = channel.overwrites_for(default_role)
    overwrite.send_messages = False
    try:
        await channel.set_permissions(default_role, overwrite=overwrite, reason=f"Locked by {interaction.user}")
        public_embed = make_embed(
            "Channel Locked",
            "> This channel is temporarily locked by the moderation team.",
            kind="danger",
            scope=SCOPE_MODERATION,
            guild=interaction.guild,
        )
        msg = await channel.send(embed=public_embed)
        if "locked_channels" not in bot.data_manager.config: bot.data_manager.config["locked_channels"] = {}
        bot.data_manager.config["locked_channels"][str(channel.id)] = msg.id
        await bot.data_manager.save_config()
        await interaction.followup.send(embed=make_embed("Channel Locked", "> Channel has been locked successfully.", kind="success", scope=SCOPE_MODERATION, guild=interaction.guild), ephemeral=True)
    except Exception as e:
        await interaction.followup.send(embed=make_embed("Error", f"> Error: {e}", kind="error", scope=SCOPE_MODERATION, guild=interaction.guild), ephemeral=True)


@tree.command(name="unlock", description="Unlock the current channel and restore messaging.")
@app_commands.check(_staff_check)
async def unlock(interaction: discord.Interaction):
    await interaction.response.defer(ephemeral=True)
    channel = interaction.channel
    default_role = interaction.guild.default_role
    overwrite = channel.overwrites_for(default_role)
    overwrite.send_messages = None
    try:
        await channel.set_permissions(default_role, overwrite=overwrite, reason=f"Unlocked by {interaction.user}")
        cid = str(channel.id)
        if "locked_channels" in bot.data_manager.config:
            if cid in bot.data_manager.config["locked_channels"]:
                try:
                    msg = await channel.fetch_message(bot.data_manager.config["locked_channels"][cid])
                    await msg.delete()
                except Exception: pass
                del bot.data_manager.config["locked_channels"][cid]
                await bot.data_manager.save_config()
        await interaction.followup.send(embed=make_embed("Channel Unlocked", "> Channel has been unlocked successfully.", kind="success", scope=SCOPE_MODERATION, guild=interaction.guild), ephemeral=True)
    except Exception as e:
        await interaction.followup.send(embed=make_embed("Error", f"> Error: {e}", kind="error", scope=SCOPE_MODERATION, guild=interaction.guild), ephemeral=True)


class ModGuideSelect(discord.ui.Select):
    def __init__(self, current: str = "overview"):
        options = [
            discord.SelectOption(label="Overview", value="overview", default=current == "overview"),
            discord.SelectOption(label="Actions", value="actions", default=current == "actions"),
            discord.SelectOption(label="Cases & History", value="cases", default=current == "cases"),
            discord.SelectOption(label="Channel Controls", value="channels", default=current == "channels"),
        ]
        super().__init__(placeholder="Choose a guide section...", min_values=1, max_values=1, options=options)

    async def callback(self, interaction: discord.Interaction) -> None:
        page = self.values[0]
        await interaction.response.edit_message(embed=build_mod_help_embed(interaction.guild, page), view=ModGuideView(page))


class ModGuideView(discord.ui.View):
    def __init__(self, page: str = "overview"):
        super().__init__(timeout=300)
        self.add_item(ModGuideSelect(page))


@tree.command(name="mod-guide", description="View the moderation command guide.")
@app_commands.check(_staff_check)
async def mod_help(interaction: discord.Interaction):
    await interaction.response.send_message(embed=build_mod_help_embed(interaction.guild), view=ModGuideView(), ephemeral=True)


@tree.command(name="case", description="Open a specific moderation case.")
@app_commands.describe(
    caseid="The case ID to open.",
    user="The member whose latest case to open.",
)
@app_commands.check(_staff_check)
async def case(interaction: discord.Interaction, caseid: Optional[app_commands.Range[int, 1, 999999]] = None, user: Optional[discord.Member] = None):
    if caseid is None and user is None:
        await send_target_picker(
            interaction,
            action="case",
            title="Open a Case",
            description="> Select a member to open their latest case, or open a specific case by ID.",
        )
        return
    await show_case_panel(interaction, case_id=caseid, user=user)


@tree.context_menu(name="Punish")
async def punish_context(interaction: discord.Interaction, user: discord.User):
    if not is_staff(interaction):
        await interaction.response.send_message(embed=make_embed("Access Denied", "> You do not have permission to use this command.", kind="error", scope=SCOPE_MODERATION, guild=interaction.guild), ephemeral=True)
        return
    await show_punish_menu(interaction, user)


@tree.context_menu(name="Punish Message")
async def punish_message_context(interaction: discord.Interaction, message: discord.Message):
    if not is_staff(interaction):
        await interaction.response.send_message(embed=make_embed("Access Denied", "> You do not have permission to use this command.", kind="error", scope=SCOPE_MODERATION, guild=interaction.guild), ephemeral=True)
        return
    if message.author.bot:
        await respond_with_error(interaction, "Bot messages cannot be punished.", scope=SCOPE_MODERATION)
        return
    target = await _resolve_message_author(interaction, message)
    await show_punish_menu(interaction, target, evidence_message=message)


@tree.context_menu(name="Moderation History")
async def history_context(interaction: discord.Interaction, user: discord.Member):
    if not is_staff(interaction):
        await interaction.response.send_message(embed=make_embed("Access Denied", "> You do not have permission to use this command.", kind="error", scope=SCOPE_MODERATION, guild=interaction.guild), ephemeral=True)
        return
    await show_history_menu(interaction, user)



class ModerationCog(commands.Cog):
    def __init__(self, bot):
        self.bot = bot


async def setup(bot):
    await bot.add_cog(ModerationCog(bot))
    bot.tree.add_command(punish)
    bot.tree.add_command(publicexecution)
    bot.tree.add_command(history)
    bot.tree.add_command(cases)
    bot.tree.add_command(undo)
    bot.tree.add_command(purge)
    bot.tree.add_command(lock)
    bot.tree.add_command(unlock)
    bot.tree.add_command(mod_help)
    bot.tree.add_command(case)
    bot.tree.add_command(punish_context)
    bot.tree.add_command(punish_message_context)
    bot.tree.add_command(history_context)
