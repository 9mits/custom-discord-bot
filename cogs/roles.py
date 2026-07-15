"""Custom booster role CRUD: views, modals, embed builders, and /role commands."""

import discord
from discord import app_commands
from discord.ext import commands
import asyncio
from typing import Optional, List, Union

from core.constants import (
    DEFAULT_ANCHOR_ROLE_ID,
    EMBED_PALETTE,
    HOLO_PRIMARY,
    HOLO_SECONDARY,
    HOLO_TERTIARY,
    SCOPE_MODERATION,
    SCOPE_ROLES,
)
from core.context import bot, tree
from core.utils import iso_to_dt, now_iso
from .shared import (
    logger,
    truncate_text,
    format_log_quote,
    format_reason_value,
    make_embed,
    brand_embed,
    make_confirmation_embed,
    join_lines,
    format_user_ref,
    is_staff,
    fetch_image_bytes,
    get_custom_role_limit,
    hex_valid,
    build_role_landing_embed,
    make_action_log_embed,
    send_punishment_log,
    check_admin,
)
from .cases import (
    get_case_label,
    get_active_records_for_user,
    get_undo_reason_details,
)

def get_user_role_records(user_id: int) -> list:
    uid = str(user_id)
    data = bot.data_manager.roles.get(uid)
    if data is None:
        return []
    if isinstance(data, dict):
        data = [data]
        bot.data_manager.roles[uid] = data
    return data

def find_role_rec(user_id: int, role_id: int) -> Optional[dict]:
    for rec in get_user_role_records(user_id):
        if rec.get("role_id") == role_id:
            return rec
    return None

def build_role_info_embed(member: discord.Member, rec: dict, role_obj: Optional[discord.Role], include_tips=False) -> discord.Embed:
    color_hex = rec.get("color", "#000000")
    color = discord.Color(int(color_hex.lstrip("#"), 16)) if hex_valid(color_hex) else EMBED_PALETTE["muted"]
    embed = make_embed(
        "Manage Your Custom Role",
        f"> You're managing your custom role **{discord.utils.escape_markdown(role_obj.name if role_obj else rec.get('name', 'Unknown'))}**.\n> Use the menu below to rename it, update the color, icon, or style, or delete it.",
        kind="info" if color.value == 0 else "neutral",
        scope=SCOPE_ROLES,
        guild=member.guild,
    )
    embed.color = EMBED_PALETTE["muted"] if color.value == 0 else color
    if role_obj:
        embed.add_field(name="Role", value=f"{role_obj.mention}", inline=False)
        embed.add_field(name="Name", value=role_obj.name, inline=True)
        if rec.get("secondary_color"):
            embed.add_field(name="Secondary (Gradient)", value=f"`{rec.get('secondary_color')}`", inline=True)
        if rec.get("tertiary_color"):
            embed.add_field(name="Tertiary (Holograph)", value=f"`{rec.get('tertiary_color')}`", inline=True)
    else:
        embed.add_field(name="Role", value=f"<@&{rec.get('role_id')}> (missing)", inline=False)
        embed.add_field(name="Name", value=rec.get("name", "Unknown"), inline=True)

    embed.add_field(name="Color", value=f"`{rec.get('color','Unknown')}`", inline=True)
    
    created_at = rec.get("created_at")
    if created_at:
        dt = iso_to_dt(created_at)
        if dt:
            embed.add_field(name="Created", value=discord.utils.format_dt(dt, style="f"), inline=True)
            delta = discord.utils.utcnow() - dt
            days = delta.days
            hours = delta.seconds // 3600
            embed.add_field(name="Age", value=f"{days}d {hours}h", inline=True)
        else:
            embed.add_field(name="Created", value=created_at, inline=True)

    icon_url = rec.get("icon")
    if icon_url and icon_url.startswith(("http://", "https://")):
        embed.set_thumbnail(url=icon_url)
    else:
        embed.set_thumbnail(url=member.display_avatar.url)

    if include_tips:
        embed.add_field(
            name="Tips",
            value="> - Use the action menu below to update the name, colors, icon, and style.\n> - If the icon URL fails, use the upload flow instead.",
            inline=False,
        )

    return embed

def build_punish_embed(user: discord.Member, *, evidence_message: Optional[discord.Message] = None) -> discord.Embed:
    uid = str(user.id)
    history = bot.data_manager.punishments.get(uid, [])
    active_records = get_active_records_for_user(user.id)
    description = "> Select a violation category below."
    if evidence_message is not None:
        description += " The selected message will be saved to the punishment log and deleted when the action succeeds."
    else:
        description += " To include a message, use `/punish message_id:` or right-click it and choose **Punish Message**."
    embed = make_embed(
        "Moderation Console",
        description,
        kind="muted",
        scope=SCOPE_MODERATION,
        guild=user.guild if isinstance(user, discord.Member) else None,
        thumbnail=user.display_avatar.url,
    )
    embed.add_field(name="Target", value=format_user_ref(user), inline=True)
    embed.add_field(name="Prior Punishments", value=str(len(history)), inline=True)
    if active_records:
        embed.add_field(name="Active Punishments", value=str(len(active_records)), inline=True)
    if evidence_message is not None:
        jump_url = str(getattr(evidence_message, "jump_url", "") or "")
        message_id = str(evidence_message.id)
        message_value = f"[{message_id}]({jump_url})" if jump_url.startswith(("http://", "https://")) else f"`{message_id}`"
        embed.add_field(name="Message ID", value=message_value, inline=True)
        if evidence_message.content:
            embed.add_field(name="Flagged Message", value=format_log_quote(evidence_message.content, limit=900), inline=False)
        image_attachment = next(
            (
                attachment for attachment in evidence_message.attachments
                if str(getattr(attachment, "content_type", "") or "").startswith("image/")
            ),
            None,
        )
        if image_attachment is not None:
            embed.set_image(url=image_attachment.url)
    return embed


class CreateRoleModal(discord.ui.Modal, title="Create your custom role"):
    role_name = discord.ui.TextInput(label="Role name", max_length=100)
    hex_color = discord.ui.TextInput(label="Hex color (Optional)", placeholder="#FF66CC", max_length=7, required=False)
    icon_url = discord.ui.TextInput(label="Icon URL (optional)", required=False, placeholder="https://...")

    def __init__(self, member: discord.Member):
        super().__init__()
        self._member = member

    async def on_submit(self, interaction: discord.Interaction) -> None:
        member = self._member
        guild = interaction.guild

        await interaction.response.defer(ephemeral=True)

        allowed = get_custom_role_limit(member)
        if allowed <= 0:
            await interaction.followup.send(embed=make_embed("Access Denied", "> You are not authorized to create a custom role.", kind="error", scope=SCOPE_ROLES, guild=interaction.guild), ephemeral=True)
            return

        current = len(get_user_role_records(member.id))
        if current >= allowed:
            await interaction.followup.send(embed=make_embed("Role Limit Reached", f"> You are allowed {allowed} role(s) and already have {current}.", kind="error", scope=SCOPE_ROLES, guild=interaction.guild), ephemeral=True)
            return

        name = self.role_name.value.strip()[:100]
        color_text = self.hex_color.value.strip() if self.hex_color.value else None

        if color_text:
            if not hex_valid(color_text):
                await interaction.followup.send(embed=make_embed("Invalid Color", "> Invalid hex color (use #RRGGBB).", kind="error", scope=SCOPE_ROLES, guild=interaction.guild), ephemeral=True)
                return
        else:
            color_text = "#000000" # Default

        try:
            color = discord.Color(int(color_text.lstrip("#"), 16))
        except Exception:
            color = discord.Color.default()

        try:
            new_role = await guild.create_role(name=name, color=color, mentionable=True, reason=f"Custom role created by {member}")
        except discord.Forbidden:
            await interaction.followup.send(embed=make_embed("Permission Error", "> Bot lacks permissions or role hierarchy prevents creation.", kind="error", scope=SCOPE_ROLES, guild=interaction.guild), ephemeral=True)
            return
        except Exception as e:
            await interaction.followup.send(embed=make_embed("Failed", f"> Failed to create role: {e}", kind="error", scope=SCOPE_ROLES, guild=interaction.guild), ephemeral=True)
            return

        anchor_id = int(bot.data_manager.config.get("role_anchor", DEFAULT_ANCHOR_ROLE_ID))
        anchor = guild.get_role(anchor_id)
        if not anchor:
            try:
                anchor = await guild.fetch_role(anchor_id)
            except Exception:
                pass

        if anchor:
            try:
                target_pos = max(anchor.position - 1, 1)
                await new_role.edit(position=target_pos, reason="Positioning under anchor")
            except discord.Forbidden:
                pass  # bot role not high enough to reposition
            except Exception as e:
                logger.warning("Failed to position custom role under anchor: %s", e)

        icon_val = self.icon_url.value.strip() if self.icon_url.value else None
        icon_warning = None
        applied_icon_url = None
        if icon_val:
            img, icon_warning = await fetch_image_bytes(icon_val)
            if img:
                try:
                    await new_role.edit(display_icon=img)
                    applied_icon_url = icon_val
                except Exception:
                    icon_warning = "Role created, but Discord rejected the icon."

        try:
            await member.add_roles(new_role, reason="Assigned custom role")
        except Exception as e:
            logger.warning("Failed to assign custom role %s to %s: %s", new_role.id, member.id, e)

        records = get_user_role_records(member.id)
        records.append({
            "role_id": new_role.id,
            "name": name,
            "color": color_text,
            "icon": applied_icon_url,
            "created_at": now_iso(),
        })
        bot.data_manager.roles[str(member.id)] = records
        await bot.data_manager.save_roles()

        embed = make_embed(
            "Custom Role Created",
            f"> Your role {new_role.mention} has been created successfully.",
            kind="success",
            scope=SCOPE_ROLES,
            guild=guild,
        )
        embed.color = color
        embed.add_field(name="Role", value=f"{new_role.mention}", inline=False)
        embed.add_field(name="Color", value=color_text, inline=True)
        if applied_icon_url:
            embed.set_thumbnail(url=applied_icon_url)
        if icon_warning:
            embed.add_field(name="Icon", value=f"> {truncate_text(icon_warning, 300)}", inline=False)
        await interaction.followup.send(embed=embed, ephemeral=True)

class EditNameModal(discord.ui.Modal, title="Edit role name"):
    new_name = discord.ui.TextInput(label="New role name", max_length=100)
    def __init__(self, member, role):
        super().__init__()
        self.member = member
        self.role = role
    async def on_submit(self, interaction) -> None:
        name = self.new_name.value.strip()[:100]
        try:
            await self.role.edit(name=name, reason=f"Renamed by {interaction.user}")
        except Exception as e:
            await interaction.response.send_message(embed=make_embed("Failed", f"> Failed: {e}", kind="error", scope=SCOPE_ROLES, guild=interaction.guild), ephemeral=True)
            return
        rec = find_role_rec(self.member.id, self.role.id)
        if rec:
            rec["name"] = name
            await bot.data_manager.save_roles()
        embed = make_embed(
            "Role Renamed",
            f"> The custom role has been renamed to `{name}`.",
            kind="success",
            scope=SCOPE_ROLES,
            guild=interaction.guild,
        )
        embed.color = self.role.color
        await interaction.response.send_message(embed=embed, ephemeral=True)

class EditColorModal(discord.ui.Modal, title="Edit role color"):
    new_color = discord.ui.TextInput(label="Hex color", placeholder="#FF66CC", max_length=7)
    def __init__(self, member, role):
        super().__init__()
        self.member = member
        self.role = role
    async def on_submit(self, interaction) -> None:
        c = self.new_color.value.strip()
        if not hex_valid(c):
            await interaction.response.send_message(embed=make_embed("Invalid Color", "> Invalid hex color.", kind="error", scope=SCOPE_ROLES, guild=interaction.guild), ephemeral=True)
            return
        try:
            color = discord.Color(int(c.lstrip("#"),16))
            await self.role.edit(color=color, reason=f"Edited by {interaction.user}")
        except Exception as e:
            await interaction.response.send_message(embed=make_embed("Failed", f"> Failed: {e}", kind="error", scope=SCOPE_ROLES, guild=interaction.guild), ephemeral=True)
            return
        rec = find_role_rec(self.member.id, self.role.id)
        if rec:
            rec["color"] = c
            await bot.data_manager.save_roles()
        embed = make_embed(
            "Role Color Updated",
            f"> The role color has been changed to `{c}`.",
            kind="success",
            scope=SCOPE_ROLES,
            guild=interaction.guild,
        )
        embed.color = color
        await interaction.response.send_message(embed=embed, ephemeral=True)

class ConfirmRevokeView(discord.ui.View):
    def __init__(self, parent_view, target_message):
        super().__init__(timeout=60)
        self.parent_view = parent_view
        self.target_message = target_message

    @discord.ui.button(label="Yes, Revoke", style=discord.ButtonStyle.danger)
    async def confirm(self, interaction: discord.Interaction, button: discord.ui.Button) -> None:
        if not is_staff(interaction):
            await interaction.response.send_message(embed=make_embed("Access Denied", "> You do not have permission to use this.", kind="error", scope=SCOPE_ROLES, guild=interaction.guild), ephemeral=True)
            return
        await self.parent_view.finish_revoke(interaction, self.target_message)

    @discord.ui.button(label="Cancel", style=discord.ButtonStyle.secondary)
    async def cancel(self, interaction: discord.Interaction, button: discord.ui.Button) -> None:
        await interaction.response.edit_message(embed=make_embed("Revocation Cancelled", "> No changes were made to this punishment.", kind="muted", scope=SCOPE_MODERATION, guild=interaction.guild), view=None)

class DenyAppealModal(discord.ui.Modal, title="Deny Appeal"):
    reason = discord.ui.TextInput(label="Reason for Denial", style=discord.TextStyle.paragraph, required=True)

    def __init__(self, target_id: int, origin_message: discord.Message):
        super().__init__()
        self.target_id = target_id
        self.origin_message = origin_message

    async def on_submit(self, interaction: discord.Interaction) -> None:
        if not is_staff(interaction):
            await interaction.response.send_message(embed=make_embed("Access Denied", "> You do not have permission to use this.", kind="error", scope=SCOPE_MODERATION, guild=interaction.guild), ephemeral=True)
            return
        embed = self.origin_message.embeds[0]
        embed.color = discord.Color.red()
        embed.add_field(name="Status", value=f"> Denied by {interaction.user.mention}\n> Reason: {self.reason.value}", inline=False)
        brand_embed(embed, guild=interaction.guild, scope=SCOPE_MODERATION)

        await self.origin_message.edit(embed=embed, view=None)
        
        user = interaction.guild.get_member(self.target_id)
        if not user:
            try: user = await interaction.client.fetch_user(self.target_id)
            except Exception: user = None
            
        if user:
            try:
                dm_embed = make_embed(
                    "Appeal Denied",
                    f"> Your punishment appeal in **{interaction.guild.name}** was reviewed and denied.",
                    kind="danger",
                    scope=SCOPE_MODERATION,
                    guild=interaction.guild,
                    thumbnail=interaction.guild.icon.url if interaction.guild.icon else None,
                )
                dm_embed.add_field(name="Reason", value=format_reason_value(self.reason.value, limit=1024), inline=False)
                await user.send(embed=dm_embed)
            except Exception:
                pass
        
        await interaction.response.send_message(embed=make_embed("Appeal Denied", "> The appeal has been denied and the user has been notified.", kind="success", scope=SCOPE_MODERATION, guild=interaction.guild), ephemeral=True)

class AppealRevokeButton(
    discord.ui.DynamicItem[discord.ui.Button],
    template=r"case:appeal_revoke:(?P<case_id>[0-9]+)",
):
    """Restart-surviving accept button on appeal log messages. Delegates the
    reversal to the shared undo engine instead of timestamp-matched removal."""

    def __init__(self, case_id: int) -> None:
        super().__init__(
            discord.ui.Button(
                label="Revoke Punishment",
                style=discord.ButtonStyle.danger,
                custom_id=f"case:appeal_revoke:{case_id}",
            )
        )
        self.case_id = case_id

    @classmethod
    async def from_custom_id(cls, interaction: discord.Interaction, item: discord.ui.Button, match, /) -> "AppealRevokeButton":
        return cls(int(match["case_id"]))

    async def callback(self, interaction: discord.Interaction) -> None:
        if not is_staff(interaction):
            await interaction.response.send_message(embed=make_embed("Access Denied", "> You do not have permission to use this.", kind="error", scope=SCOPE_MODERATION, guild=interaction.guild), ephemeral=True)
            return
        await interaction.response.send_message(embed=make_embed("Confirm Revocation", "> Are you sure you want to revoke this punishment?", kind="warning", scope=SCOPE_MODERATION, guild=interaction.guild), view=ConfirmRevokeView(self, interaction.message), ephemeral=True)

    async def finish_revoke(self, interaction: discord.Interaction, message: discord.Message) -> None:
        if not is_staff(interaction):
            await interaction.response.send_message(embed=make_embed("Access Denied", "> You do not have permission to use this.", kind="error", scope=SCOPE_MODERATION, guild=interaction.guild), ephemeral=True)
            return
        await interaction.response.edit_message(embed=make_embed("Processing", "> Processing the revocation request...", kind="muted", scope=SCOPE_MODERATION, guild=interaction.guild), view=None)

        guild = interaction.guild
        target_user_id, record = bot.data_manager.get_case(self.case_id)
        if not record or not target_user_id:
            await interaction.edit_original_response(embed=make_embed("Case Not Found", "> This case is no longer on record — it may have been undone already.", kind="error", scope=SCOPE_MODERATION, guild=guild))
            return
        case_label = get_case_label(record)

        user = guild.get_member(int(target_user_id))
        if not user:
            try:
                user = await bot.fetch_user(int(target_user_id))
            except Exception:
                user = None
        if user is None:
            await interaction.edit_original_response(embed=make_embed("User Not Found", "> The punished user could not be resolved.", kind="error", scope=SCOPE_MODERATION, guild=guild))
            return

        undo_reason = get_undo_reason_details("appeal_accepted", None)[1]
        from .history import execute_undo_and_log
        success, removed_record, action_taken = await execute_undo_and_log(interaction, user, self.case_id, undo_reason)
        if not success or not removed_record:
            await interaction.edit_original_response(embed=make_embed("Revocation Failed", f"> {action_taken}", kind="error", scope=SCOPE_MODERATION, guild=guild))
            return

        # Mark the appeal message resolved and drop its buttons.
        try:
            embed = message.embeds[0]
            embed.color = discord.Color.green()
            embed.title = f"{case_label} Appeal Resolved"
            embed.add_field(name="Status", value=f"> Revoked by {interaction.user.mention}\n> {action_taken}", inline=False)
            brand_embed(embed, guild=guild, scope=SCOPE_MODERATION)
            await message.edit(embed=embed, view=None)
        except Exception:
            pass

        try:
            dm_embed = make_embed(
                "Punishment Revoked",
                f"> {case_label} in **{guild.name}** has been revoked.",
                kind="success",
                scope=SCOPE_MODERATION,
                guild=guild,
                thumbnail=guild.icon.url if guild.icon else None,
            )
            dm_embed.add_field(name="Outcome", value=truncate_text(action_taken, 1024), inline=False)
            await user.send(embed=dm_embed)
        except Exception:
            pass

        await interaction.edit_original_response(embed=make_embed("Punishment Revoked", "> The punishment has been revoked successfully.", kind="success", scope=SCOPE_MODERATION, guild=guild))


class AppealDenyButton(
    discord.ui.DynamicItem[discord.ui.Button],
    template=r"case:appeal_deny:(?P<case_id>[0-9]+)",
):
    def __init__(self, case_id: int) -> None:
        super().__init__(
            discord.ui.Button(
                label="Deny Appeal",
                style=discord.ButtonStyle.secondary,
                custom_id=f"case:appeal_deny:{case_id}",
            )
        )
        self.case_id = case_id

    @classmethod
    async def from_custom_id(cls, interaction: discord.Interaction, item: discord.ui.Button, match, /) -> "AppealDenyButton":
        return cls(int(match["case_id"]))

    async def callback(self, interaction: discord.Interaction) -> None:
        if not is_staff(interaction):
            await interaction.response.send_message(embed=make_embed("Access Denied", "> You do not have permission to use this.", kind="error", scope=SCOPE_MODERATION, guild=interaction.guild), ephemeral=True)
            return
        target_user_id, _ = bot.data_manager.get_case(self.case_id)
        target_id = int(target_user_id) if target_user_id else 0
        await interaction.response.send_modal(DenyAppealModal(target_id, interaction.message))


def build_appeal_decision_view(case_id: int) -> discord.ui.View:
    from .case_panel import OpenCaseButton
    view = discord.ui.View(timeout=None)
    view.add_item(AppealRevokeButton(case_id))
    view.add_item(AppealDenyButton(case_id))
    view.add_item(OpenCaseButton(case_id))
    return view


class AppealModal(discord.ui.Modal, title="Appeal Punishment"):
    reason = discord.ui.TextInput(label="Why should this be revoked?", style=discord.TextStyle.paragraph, max_length=500)

    def __init__(self, guild_id: int, case_id: int):
        super().__init__()
        self.guild_id = guild_id
        self.case_id = case_id

    async def on_submit(self, interaction: discord.Interaction) -> None:
        guild = bot.get_guild(self.guild_id)
        if not guild:
            await interaction.response.send_message(embed=make_embed("Server Not Found", "> The server could not be found.", kind="error", scope=SCOPE_MODERATION), ephemeral=True)
            return

        target_user_id, record = bot.data_manager.get_case(self.case_id)
        if not record:
            await interaction.response.send_message(embed=make_embed("Nothing to Appeal", "> This punishment is no longer on record.", kind="muted", scope=SCOPE_MODERATION), ephemeral=True)
            return
        case_label = get_case_label(record)

        embed = make_action_log_embed(
            f"{case_label} Appeal",
            "A user submitted an appeal for moderator review.",
            guild=guild,
            kind="warning",
            scope=SCOPE_MODERATION,
            actor=format_user_ref(interaction.user),
            target=case_label,
            reason=record.get("reason", "Unknown"),
            message=self.reason.value,
            notes=[f"Case ID: {self.case_id}"],
            thumbnail=interaction.user.display_avatar.url,
            author_name=f"{interaction.user.display_name} ({interaction.user.id})",
            author_icon=interaction.user.display_avatar.url,
        )

        view = build_appeal_decision_view(self.case_id)

        # Check for specific appeal channel
        appeal_cid = bot.data_manager.config.get("appeal_channel_id")
        sent = False
        if appeal_cid:
            appeal_chan = guild.get_channel(appeal_cid)
            if appeal_chan:
                try:
                    await appeal_chan.send(embed=embed, view=view)
                    sent = True
                except Exception:
                    pass

        # Fallback to General Logs only if Appeal Log failed or isn't set
        if not sent:
            await send_punishment_log(guild, embed, view=view)

        await interaction.response.send_message(embed=make_embed("Appeal Submitted", "> Your appeal has been sent to the staff team.", kind="success", scope=SCOPE_MODERATION, guild=interaction.guild), ephemeral=True)

class AppealButton(
    discord.ui.DynamicItem[discord.ui.Button],
    template=r"case:appeal:(?P<guild_id>[0-9]+):(?P<case_id>[0-9]+)",
):
    """Restart-surviving appeal button attached to punishment DMs. Resolves the
    case at click time instead of carrying a payload."""

    def __init__(self, guild_id: int, case_id: int) -> None:
        super().__init__(
            discord.ui.Button(
                label="Appeal Punishment",
                style=discord.ButtonStyle.secondary,
                custom_id=f"case:appeal:{guild_id}:{case_id}",
            )
        )
        self.guild_id = guild_id
        self.case_id = case_id

    @classmethod
    async def from_custom_id(cls, interaction: discord.Interaction, item: discord.ui.Button, match, /) -> "AppealButton":
        return cls(int(match["guild_id"]), int(match["case_id"]))

    async def callback(self, interaction: discord.Interaction) -> None:
        guild = bot.get_guild(self.guild_id)
        if not guild:
            await interaction.response.send_message(embed=make_embed("Server Not Found", "> The server could not be found.", kind="error", scope=SCOPE_MODERATION), ephemeral=True)
            return
        target_user_id, record = bot.data_manager.get_case(self.case_id)
        if not record or target_user_id != str(interaction.user.id):
            await interaction.response.send_message(embed=make_embed("Nothing to Appeal", "> This punishment is no longer on record.", kind="muted", scope=SCOPE_MODERATION), ephemeral=True)
            return
        await interaction.response.send_modal(AppealModal(self.guild_id, self.case_id))


def build_appeal_view(guild_id: int, case_id: int, *, server_url: Optional[str] = None) -> discord.ui.View:
    view = discord.ui.View(timeout=None)
    view.add_item(AppealButton(guild_id, case_id))
    if server_url and server_url.startswith(("http://", "https://")):
        view.add_item(discord.ui.Button(
            label="Return to Server",
            style=discord.ButtonStyle.link,
            url=server_url,
        ))
    return view

class GradientModal(discord.ui.Modal, title="Set Gradient Style"):
    secondary = discord.ui.TextInput(label="Secondary Color (Hex)", placeholder="#RRGGBB", min_length=7, max_length=7)

    def __init__(self, member, role):
        super().__init__()
        self.member = member
        self.role = role

    async def on_submit(self, interaction: discord.Interaction) -> None:
        sec_val = self.secondary.value.strip()
        if not hex_valid(sec_val):
            await interaction.response.send_message(embed=make_embed("Invalid Color", "> Invalid hex color.", kind="error", scope=SCOPE_ROLES, guild=interaction.guild), ephemeral=True)
            return

        sec_int = int(sec_val.lstrip("#"), 16)
        prim_int = self.role.color.value

        try:
            edited_role = await self.role.edit(
                color=prim_int,
                secondary_color=sec_int,
                tertiary_color=None,
                reason=f"Gradient style update by {interaction.user}",
            )
            if edited_role is not None:
                self.role = edited_role

            rec = find_role_rec(self.member.id, self.role.id)
            if rec:
                rec['color'] = f"#{prim_int:06X}"
                rec['secondary_color'] = sec_val
                rec['tertiary_color'] = None
                await bot.data_manager.save_roles()

            await interaction.response.send_message(
                embed=make_confirmation_embed(
                    "Gradient Style Applied",
                    f"> The role now uses Discord's enhanced gradient colors with secondary color `{sec_val}`.",
                    scope=SCOPE_ROLES,
                    guild=interaction.guild,
                ),
                ephemeral=True,
            )
        except discord.HTTPException as e:
            await interaction.response.send_message(embed=make_embed("Failed", f"> Failed to update style: {e.status} {e.text}", kind="error", scope=SCOPE_ROLES, guild=interaction.guild), ephemeral=True)
        except Exception as e:
            await interaction.response.send_message(embed=make_embed("Failed", f"> Failed to update style: {e}", kind="error", scope=SCOPE_ROLES, guild=interaction.guild), ephemeral=True)

class RoleStyleView(discord.ui.View):
    def __init__(self, member, role):
        super().__init__(timeout=60)
        self.member = member
        self.role = role

    @discord.ui.button(label="Static (Reset)", style=discord.ButtonStyle.secondary)
    async def static_style(self, interaction: discord.Interaction, button: discord.ui.Button) -> None:
        try:
            edited_role = await self.role.edit(
                color=self.role.color.value,
                secondary_color=None,
                tertiary_color=None,
                reason=f"Style reset by {interaction.user}",
            )
            if edited_role is not None:
                self.role = edited_role

            rec = find_role_rec(self.member.id, self.role.id)
            if rec:
                rec['secondary_color'] = None
                rec['tertiary_color'] = None
                await bot.data_manager.save_roles()
            await interaction.response.send_message(embed=make_embed("Style Reset", "> Role style has been reset to static.", kind="success", scope=SCOPE_ROLES, guild=interaction.guild), ephemeral=True)
        except discord.HTTPException as e:
            await interaction.response.send_message(embed=make_embed("Failed", f"> Failed: {e.status} {e.text}", kind="error", scope=SCOPE_ROLES, guild=interaction.guild), ephemeral=True)
        except Exception as e:
            await interaction.response.send_message(embed=make_embed("Failed", f"> Failed: {e}", kind="error", scope=SCOPE_ROLES, guild=interaction.guild), ephemeral=True)

    @discord.ui.button(label="Gradient", style=discord.ButtonStyle.primary)
    async def gradient_style(self, interaction: discord.Interaction, button: discord.ui.Button) -> None:
        await interaction.response.send_modal(GradientModal(self.member, self.role))

    @discord.ui.button(label="Holographic", style=discord.ButtonStyle.success)
    async def holographic_style(self, interaction: discord.Interaction, button: discord.ui.Button) -> None:
        try:
            edited_role = await self.role.edit(
                color=HOLO_PRIMARY,
                secondary_color=HOLO_SECONDARY,
                tertiary_color=HOLO_TERTIARY,
                reason=f"Holographic style update by {interaction.user}",
            )
            if edited_role is not None:
                self.role = edited_role

            rec = find_role_rec(self.member.id, self.role.id)
            if rec:
                rec['color'] = f"#{HOLO_PRIMARY:06X}"
                rec['secondary_color'] = f"#{HOLO_SECONDARY:06X}"
                rec['tertiary_color'] = f"#{HOLO_TERTIARY:06X}"
                await bot.data_manager.save_roles()

            await interaction.response.send_message(
                embed=make_confirmation_embed(
                    "Holographic Style Applied",
                    "> The role now uses Discord's holographic enhanced role style preset.",
                    scope=SCOPE_ROLES,
                    guild=interaction.guild,
                ),
                ephemeral=True,
            )
        except discord.HTTPException as e:
            await interaction.response.send_message(embed=make_embed("Failed", f"> Failed: {e.status} {e.text}", kind="error", scope=SCOPE_ROLES, guild=interaction.guild), ephemeral=True)
        except Exception as e:
            await interaction.response.send_message(embed=make_embed("Failed", f"> Failed: {e}", kind="error", scope=SCOPE_ROLES, guild=interaction.guild), ephemeral=True)

class IconURLModal(discord.ui.Modal, title="Set Icon via URL"):
    url = discord.ui.TextInput(label="Image URL", placeholder="https://...", required=True)

    def __init__(self, member, role):
        super().__init__()
        self.member = member
        self.role = role

    async def on_submit(self, interaction: discord.Interaction) -> None:
        await interaction.response.defer(ephemeral=True)
        val = self.url.value.strip()
        
        img, error = await fetch_image_bytes(val)
        if not img:
            await interaction.followup.send(embed=make_embed("Failed", f"> {error or 'Failed to download image. Check the URL.'}", kind="error", scope=SCOPE_ROLES, guild=interaction.guild), ephemeral=True)
            return

        try:
            await self.role.edit(display_icon=img, reason=f"Icon updated by {interaction.user}")
            rec = find_role_rec(self.member.id, self.role.id)
            if rec:
                rec["icon"] = val
                await bot.data_manager.save_roles()
            await interaction.followup.send(embed=make_embed("Icon Updated", "> Icon updated successfully!", kind="success", scope=SCOPE_ROLES, guild=interaction.guild), ephemeral=True)
        except Exception as e:
            await interaction.followup.send(embed=make_embed("Failed", f"> Failed to update icon: {e}", kind="error", scope=SCOPE_ROLES, guild=interaction.guild), ephemeral=True)

class UploadIconView(discord.ui.View):
    def __init__(self, member, role):
        super().__init__(timeout=60)
        self.member = member
        self.role = role

    @discord.ui.button(label="Upload File", style=discord.ButtonStyle.primary)
    async def upload_file(self, interaction: discord.Interaction, button: discord.ui.Button) -> None:
        for child in self.children:
            child.disabled = True
        await interaction.response.edit_message(view=self)
        
        await interaction.followup.send(interaction.user.mention, embed=make_embed("Upload Image", "> Please reply to this message with your image file now.", kind="info", scope=SCOPE_ROLES, guild=interaction.guild), ephemeral=True)
        
        def check(m) -> None:
            return m.author.id == interaction.user.id and m.channel.id == interaction.channel.id and m.attachments

        try:
            msg = await bot.wait_for('message', check=check, timeout=60)
            attachment = msg.attachments[0]
            if attachment.size > 256000:
                await interaction.followup.send(embed=make_embed("File Too Large", "> Image too big! Max size is 256KB.", kind="error", scope=SCOPE_ROLES, guild=interaction.guild), ephemeral=True)
                return

            img_data = await attachment.read()
            await self.role.edit(display_icon=img_data, reason=f"Icon updated by {interaction.user}")
            await interaction.followup.send(embed=make_embed("Icon Updated", "> Icon updated successfully!", kind="success", scope=SCOPE_ROLES, guild=interaction.guild), ephemeral=True)
            
            rec = find_role_rec(self.member.id, self.role.id)
            if rec:
                rec["icon"] = attachment.url
                await bot.data_manager.save_roles()
            
            try: await msg.delete()
            except Exception: pass

        except asyncio.TimeoutError:
            await interaction.followup.send(embed=make_embed("Timed Out", "> The upload timed out. Please try again.", kind="error", scope=SCOPE_ROLES, guild=interaction.guild), ephemeral=True)
        except Exception as e:
            await interaction.followup.send(embed=make_embed("Failed", f"> Failed: {e}", kind="error", scope=SCOPE_ROLES, guild=interaction.guild), ephemeral=True)

    @discord.ui.button(label="Enter URL", style=discord.ButtonStyle.secondary)
    async def enter_url(self, interaction: discord.Interaction, button: discord.ui.Button) -> None:
        await interaction.response.send_modal(IconURLModal(self.member, self.role))

class RoleActionSelect(discord.ui.Select):
    def __init__(self, member, role):
        self.member = member
        self.role = role
        options = [
            discord.SelectOption(label="Rename Role", value="name", description="Change the role name."),
            discord.SelectOption(label="Change Color", value="color", description="Update the primary role color."),
            discord.SelectOption(label="Update Icon", value="icon", description="Open the icon upload or URL options."),
            discord.SelectOption(label="Change Style", value="style", description="Pick static, gradient, or holographic style."),
            discord.SelectOption(label="Delete Role", value="delete", description="Remove the custom role permanently."),
        ]
        super().__init__(placeholder="Choose a role action...", min_values=1, max_values=1, options=options)

    async def callback(self, interaction: discord.Interaction) -> None:
        action = self.values[0]
        if action == "name":
            await interaction.response.send_modal(EditNameModal(self.member, self.role))
            return
        if action == "color":
            await interaction.response.send_modal(EditColorModal(self.member, self.role))
            return
        if action == "icon":
            embed = make_embed(
                "Update Role Icon",
                "> Choose how you'd like to set your role icon below.\n\n"
                "**Upload File** — attach an image directly (max 256 KB).\n"
                "**Enter URL** — paste a direct link to an image.",
                kind="info",
                scope=SCOPE_ROLES,
                guild=interaction.guild,
            )
            await interaction.response.send_message(embed=embed, view=UploadIconView(self.member, self.role), ephemeral=True)
            return
        if action == "style":
            await interaction.response.send_message(embed=make_embed("Role Style", "> Choose a role style below.", kind="info", scope=SCOPE_ROLES, guild=interaction.guild), view=RoleStyleView(self.member, self.role), ephemeral=True)
            return
        if action == "delete":
            confirm_view = ConfirmDelete(self.member, self.role)
            await interaction.response.send_message(embed=make_embed("Confirm Deletion", "> Are you sure you want to delete this role?", kind="warning", scope=SCOPE_ROLES, guild=interaction.guild), view=confirm_view, ephemeral=True)
            confirm_view.message = await interaction.original_response()

class EditView(discord.ui.View):
    def __init__(self, member, role):
        super().__init__(timeout=600)
        self.member = member
        self.role = role
        self.add_item(RoleActionSelect(member, role))

class ConfirmDelete(discord.ui.View):
    def __init__(self, member, role):
        super().__init__(timeout=60)
        self.member = member
        self.role = role
        self.message: Optional[discord.Message] = None

    async def on_timeout(self) -> None:
        if self.message:
            try:
                await self.message.edit(
                    embed=make_embed("Deletion Timed Out", "> No action was taken. Re-run the menu to try again.", kind="muted", scope=SCOPE_ROLES, guild=self.member.guild),
                    view=None,
                )
            except Exception:
                pass

    @discord.ui.button(label="Confirm", style=discord.ButtonStyle.danger)
    async def confirm(self, interaction: discord.Interaction, button: discord.ui.Button) -> None:
        try:
            await self.role.delete(reason=f"Deleted by {interaction.user} (via Menu)")
        except Exception:
            pass
        uid = str(self.member.id)
        records = get_user_role_records(self.member.id)
        records = [r for r in records if r.get("role_id") != self.role.id]
        if records:
            bot.data_manager.roles[uid] = records
        else:
            bot.data_manager.roles.pop(uid, None)
        await bot.data_manager.save_roles()
        await interaction.response.edit_message(embed=make_embed("Role Deleted", "> Your custom role has been deleted.", kind="success", scope=SCOPE_ROLES, guild=interaction.guild), view=None)
        self.stop()

    @discord.ui.button(label="Cancel", style=discord.ButtonStyle.secondary)
    async def cancel(self, interaction: discord.Interaction, button: discord.ui.Button) -> None:
        await interaction.response.edit_message(embed=make_embed("Deletion Cancelled", "> Your custom role was not deleted.", kind="muted", scope=SCOPE_ROLES, guild=interaction.guild), view=None)
        self.stop()


def build_role_settings_embed(guild: discord.Guild) -> discord.Embed:
    conf = bot.data_manager.config
    embed = make_embed(
        "Custom Role Settings",
        "> Manage who can create custom roles, review tracked roles, and open admin edit tools from one control panel.",
        kind="info",
        scope=SCOPE_ROLES,
        guild=guild,
    )
    embed.add_field(name="Whitelisted Users", value=str(len(conf.get("cr_whitelist_users", {}))), inline=True)
    embed.add_field(name="Whitelisted Roles", value=str(len(conf.get("cr_whitelist_roles", {}))), inline=True)
    embed.add_field(name="Blocked Entries", value=str(len(conf.get("cr_blacklist_users", [])) + len(conf.get("cr_blacklist_roles", []))), inline=True)
    total_tracked = sum(len(v) if isinstance(v, list) else 1 for v in bot.data_manager.roles.values())
    embed.add_field(name="Tracked Custom Roles", value=str(total_tracked), inline=True)
    embed.add_field(
        name="What You Can Do",
        value=join_lines([
            "Review the current allow/block lists.",
            "Allow or block specific members or roles from custom role access.",
            "Reset one entry or open a member's custom role admin panel.",
        ]),
        inline=False,
    )
    return embed


def build_role_permissions_overview_embed(guild: discord.Guild) -> discord.Embed:
    conf = bot.data_manager.config
    embed = make_embed(
        "Custom Role Access Rules",
        "> Current allow and block rules for the custom role system.",
        kind="info",
        scope=SCOPE_ROLES,
        guild=guild,
    )

    wl_users = conf.get("cr_whitelist_users", {})
    if wl_users:
        lines = [f"<@{uid}>: {limit}" for uid, limit in wl_users.items()]
        embed.add_field(name="Allowed Users", value=truncate_text("\n".join(lines), 1024), inline=False)
    else:
        embed.add_field(name="Allowed Users", value="None", inline=False)

    wl_roles = conf.get("cr_whitelist_roles", {})
    if wl_roles:
        lines = [f"<@&{rid}>: {limit}" for rid, limit in wl_roles.items()]
        embed.add_field(name="Allowed Roles", value=truncate_text("\n".join(lines), 1024), inline=False)
    else:
        embed.add_field(name="Allowed Roles", value="None", inline=False)

    bl_users = conf.get("cr_blacklist_users", [])
    embed.add_field(name="Blocked Users", value=truncate_text(", ".join(f"<@{uid}>" for uid in bl_users) or "None", 1024), inline=False)
    bl_roles = conf.get("cr_blacklist_roles", [])
    embed.add_field(name="Blocked Roles", value=truncate_text(", ".join(f"<@&{rid}>" for rid in bl_roles) or "None", 1024), inline=False)
    return embed


class RoleSettingsLimitModal(discord.ui.Modal, title="Set Role Limit"):
    limit_value = discord.ui.TextInput(label="Role Limit", placeholder="1", required=False, max_length=3)

    def __init__(self, *, action: str, target: Union[discord.Member, discord.Role]):
        super().__init__()
        self.action = action
        self.target = target

    async def on_submit(self, interaction: discord.Interaction) -> None:
        try:
            limit = max(1, int(self.limit_value.value or 1))
        except ValueError:
            await interaction.response.send_message(embed=make_embed("Invalid Input", "> Role limit must be a number.", kind="error", scope=SCOPE_ROLES, guild=interaction.guild), ephemeral=True)
            return

        await role_manage(interaction, self.action, self.target, limit)


class RoleSettingsMemberTargetSelect(discord.ui.UserSelect):
    def __init__(self, parent: "RoleSettingsTargetSelectView"):
        super().__init__(
            placeholder="Choose a member...",
            min_values=1,
            max_values=1,
        )
        self._target_view = parent

    async def callback(self, interaction: discord.Interaction) -> None:
        selected = self.values[0]
        if isinstance(selected, discord.Member):
            member = selected
        else:
            try:
                member = await interaction.guild.fetch_member(selected.id)
            except Exception:
                member = None
        if member is None:
            await interaction.response.send_message(embed=make_embed("Member Not Found", "> That member could not be found in this server.", kind="error", scope=SCOPE_ROLES, guild=interaction.guild), ephemeral=True)
            return
        await self._target_view.handle_target(interaction, member)


class RoleSettingsRoleTargetSelect(discord.ui.RoleSelect):
    def __init__(self, parent: "RoleSettingsTargetSelectView"):
        super().__init__(
            placeholder="Choose a role...",
            min_values=1,
            max_values=1,
        )
        self._target_view = parent

    async def callback(self, interaction: discord.Interaction) -> None:
        await self._target_view.handle_target(interaction, self.values[0])


class RoleSettingsTargetSelectView(discord.ui.View):
    def __init__(self, *, requester_id: int, action: str, target_type: str, require_limit: bool = False):
        super().__init__(timeout=180)
        self.requester_id = requester_id
        self.action = action
        self.require_limit = require_limit
        if target_type == "member":
            self.add_item(RoleSettingsMemberTargetSelect(self))
        else:
            self.add_item(RoleSettingsRoleTargetSelect(self))

    async def interaction_check(self, interaction: discord.Interaction) -> bool:
        if interaction.user.id == self.requester_id:
            return True
        await interaction.response.send_message(embed=make_embed("Access Denied", "> This selector belongs to another administrator.", kind="error", scope=SCOPE_ROLES, guild=interaction.guild), ephemeral=True)
        return False

    async def handle_target(self, interaction: discord.Interaction, target: Union[discord.Member, discord.Role]) -> None:
        if self.require_limit:
            await interaction.response.send_modal(RoleSettingsLimitModal(action=self.action, target=target))
            return
        await role_manage(interaction, self.action, target, 1)


async def send_role_target_picker(
    interaction: discord.Interaction,
    *,
    title: str,
    action: str,
    target_type: str,
    require_limit: bool = False,
):
    target_label = "member" if target_type == "member" else "role"
    embed = make_embed(
        title,
        f"> Choose the {target_label} from the selector below.",
        kind="info",
        scope=SCOPE_ROLES,
        guild=interaction.guild,
    )
    await interaction.response.send_message(
        embed=embed,
        view=RoleSettingsTargetSelectView(
            requester_id=interaction.user.id,
            action=action,
            target_type=target_type,
            require_limit=require_limit,
        ),
        ephemeral=True,
    )


class RoleSettingsAccessSelect(discord.ui.Select):
    def __init__(self):
        options = [
            discord.SelectOption(label="Allow Member", value="whitelist_member", description="Whitelist one member and set a role limit."),
            discord.SelectOption(label="Allow Role", value="whitelist_role", description="Whitelist one role and set a role limit."),
            discord.SelectOption(label="Block Member", value="blacklist_member", description="Block one member from custom role access."),
            discord.SelectOption(label="Block Role", value="blacklist_role", description="Block one role from custom role access."),
            discord.SelectOption(label="Reset Member", value="reset_member", description="Remove one member from all role access lists."),
            discord.SelectOption(label="Reset Role", value="reset_role", description="Remove one role from all role access lists."),
        ]
        super().__init__(placeholder="Choose an access rule action...", min_values=1, max_values=1, options=options)

    async def callback(self, interaction: discord.Interaction) -> None:
        value = self.values[0]
        if value == "whitelist_member":
            await send_role_target_picker(interaction, title="Allow Member", action="whitelist", target_type="member", require_limit=True)
            return
        if value == "whitelist_role":
            await send_role_target_picker(interaction, title="Allow Role", action="whitelist", target_type="role", require_limit=True)
            return
        if value == "blacklist_member":
            await send_role_target_picker(interaction, title="Block Member", action="blacklist", target_type="member")
            return
        if value == "blacklist_role":
            await send_role_target_picker(interaction, title="Block Role", action="blacklist", target_type="role")
            return
        if value == "reset_member":
            await send_role_target_picker(interaction, title="Reset Member", action="reset", target_type="member")
            return
        if value == "reset_role":
            await send_role_target_picker(interaction, title="Reset Role", action="reset", target_type="role")


class RoleSettingsAccessView(discord.ui.View):
    def __init__(self):
        super().__init__(timeout=180)
        self.add_item(RoleSettingsAccessSelect())


class TrackedRolesSelect(discord.ui.Select):
    def __init__(self, guild: discord.Guild):
        options = []
        for uid, data in bot.data_manager.roles.items():
            recs = data if isinstance(data, list) else [data]
            owner = guild.get_member(int(uid)) if str(uid).isdigit() else None
            owner_name = owner.display_name if owner else f"Unknown ({uid})"
            for rec in recs:
                rid = rec.get("role_id")
                role = guild.get_role(rid) if rid else None
                role_name = role.name if role else rec.get("name", "Unknown Role")
                options.append(discord.SelectOption(
                    label=truncate_text(role_name, 100),
                    value=f"{uid}:{rid}",
                    description=truncate_text(f"Owner: {owner_name}", 100),
                ))
        if not options:
            options = [discord.SelectOption(label="No roles tracked", value="__empty__", description="There are no custom roles yet.")]
        super().__init__(placeholder="Select a role to inspect...", min_values=1, max_values=1, options=options[:25])

    async def callback(self, interaction: discord.Interaction) -> None:
        if self.values[0] == "__empty__":
            await interaction.response.defer()
            return
        uid_str, rid_str = self.values[0].split(":", 1)
        try:
            role_id = int(rid_str)
            user_id = int(uid_str)
        except ValueError:
            await interaction.response.defer()
            return
        rec = find_role_rec(user_id, role_id)
        target = interaction.guild.get_member(user_id)
        role_obj = interaction.guild.get_role(role_id)
        if rec and role_obj and target:
            embed = build_role_info_embed(target, rec, role_obj, include_tips=True)
            embed.set_footer(text=f"Admin view — owner: {target.display_name}")
            await interaction.response.send_message(embed=embed, view=EditView(target, role_obj), ephemeral=True)
        else:
            await interaction.response.send_message(
                embed=make_embed("Not Found", "> That role or member could not be resolved.", kind="error", scope=SCOPE_ROLES, guild=interaction.guild),
                ephemeral=True,
            )


class TrackedRolesView(discord.ui.View):
    def __init__(self, guild: discord.Guild):
        super().__init__(timeout=180)
        self.add_item(TrackedRolesSelect(guild))


class RoleSettingsActionSelect(discord.ui.Select):
    def __init__(self):
        options = [
            discord.SelectOption(label="Review Access", value="review_access", description="Open the current allow and block lists."),
            discord.SelectOption(label="Tracked Roles", value="tracked_roles", description="Open the current custom role registry."),
            discord.SelectOption(label="Change Access Rules", value="access_rules", description="Open the access rule action menu."),
            discord.SelectOption(label="Manage Member Role", value="manage_member", description="Open one member's custom role panel."),
        ]
        super().__init__(placeholder="Choose a role settings action...", min_values=1, max_values=1, options=options)

    async def callback(self, interaction: discord.Interaction) -> None:
        action = self.values[0]
        if action == "review_access":
            await interaction.response.send_message(embed=build_role_permissions_overview_embed(interaction.guild), ephemeral=True)
            return
        if action == "tracked_roles":
            roles_data = bot.data_manager.roles
            total_roles = sum(len(v) if isinstance(v, list) else 1 for v in roles_data.values())
            total_owners = len(roles_data)
            missing = 0
            for uid, data in roles_data.items():
                recs = data if isinstance(data, list) else [data]
                for rec in recs:
                    if not interaction.guild.get_role(rec.get("role_id", 0)):
                        missing += 1
            embed = make_embed(
                "Tracked Custom Roles",
                "> Overview of all custom roles in this server.\n> Select a role from the dropdown to open its management panel.",
                kind="info",
                scope=SCOPE_ROLES,
                guild=interaction.guild,
            )
            embed.add_field(name="Total Roles", value=str(total_roles), inline=True)
            embed.add_field(name="Unique Owners", value=str(total_owners), inline=True)
            embed.add_field(name="Missing from Server", value=str(missing), inline=True)
            await interaction.response.send_message(embed=embed, view=TrackedRolesView(interaction.guild), ephemeral=True)
            return
        if action == "access_rules":
            await interaction.response.send_message(
                embed=build_role_permissions_overview_embed(interaction.guild),
                view=RoleSettingsAccessView(),
                ephemeral=True,
            )
            return
        if action == "manage_member":
            await send_role_target_picker(interaction, title="Manage Member Role", action="manage_user", target_type="member")


class RoleSettingsView(discord.ui.View):
    def __init__(self):
        super().__init__(timeout=180)
        self.add_item(RoleSettingsActionSelect())


class RolePickerSelect(discord.ui.Select):
    def __init__(self, member: discord.Member, valid_roles: list, at_limit: bool):
        self.member = member
        options = []
        for rec, role_obj in valid_roles:
            color_hex = rec.get("color", "#000000")
            options.append(discord.SelectOption(
                label=truncate_text(role_obj.name, 100),
                value=str(role_obj.id),
                description=f"Color: {color_hex}",
            ))
        if not at_limit:
            options.append(discord.SelectOption(
                label="Create New Role",
                value="__create__",
                description="Add another custom role.",
            ))
        super().__init__(placeholder="Choose a role to manage...", min_values=1, max_values=1, options=options)
        self._valid_roles = valid_roles

    async def callback(self, interaction: discord.Interaction) -> None:
        value = self.values[0]
        if value == "__create__":
            await interaction.response.send_modal(CreateRoleModal(self.member))
            return
        role_id = int(value)
        pair = next(((rec, r) for rec, r in self._valid_roles if r.id == role_id), None)
        if not pair:
            await interaction.response.send_message(embed=make_embed("Not Found", "> That role could not be found.", kind="error", scope=SCOPE_ROLES, guild=interaction.guild), ephemeral=True)
            return
        rec, role_obj = pair
        embed = build_role_info_embed(self.member, rec, role_obj, include_tips=True)
        await interaction.response.send_message(embed=embed, view=EditView(self.member, role_obj), ephemeral=True)


class RolePickerView(discord.ui.View):
    def __init__(self, member: discord.Member, valid_roles: list, at_limit: bool):
        super().__init__(timeout=120)
        self.add_item(RolePickerSelect(member, valid_roles, at_limit))


# ----------------- Commands -----------------
# --- Command Groups ---

@tree.command(name="role", description="Manage your custom role.")
async def role_cmd(interaction: discord.Interaction):
    try:
        await interaction.response.defer(ephemeral=True)
    except discord.HTTPException as e:
        if e.code != 40060:
            raise e
    
    limit = get_custom_role_limit(interaction.user)

    if limit <= 0:
        await interaction.followup.send(embed=make_embed("Access Denied", "> You don't have access to custom roles. Ask a staff member to grant you access via `/role settings`.", kind="error", scope=SCOPE_ROLES, guild=interaction.guild), ephemeral=True)
        return

    uid = str(interaction.user.id)
    records = get_user_role_records(interaction.user.id)

    # Validate each stored record against Discord, clean up deleted roles
    valid_roles: List[tuple] = []
    cleaned = False
    for rec in list(records):
        role_id = rec.get("role_id")
        role_obj = interaction.guild.get_role(role_id)
        if not role_obj:
            try:
                role_obj = await interaction.guild.fetch_role(role_id)
            except discord.NotFound:
                records.remove(rec)
                cleaned = True
                continue
            except Exception:
                pass
        if role_obj:
            valid_roles.append((rec, role_obj))

    if cleaned:
        if records:
            bot.data_manager.roles[uid] = records
        else:
            bot.data_manager.roles.pop(uid, None)
        await bot.data_manager.save_roles()

    n = len(valid_roles)
    at_limit = n >= limit

    if n == 0:
        # No roles — show landing with Create button
        embed = build_role_landing_embed(interaction.user, limit=max(1, limit))
        view = discord.ui.View()
        btn = discord.ui.Button(label="Create Role", style=discord.ButtonStyle.success)
        async def create_callback(inter: discord.Interaction) -> None:
            await inter.response.send_modal(CreateRoleModal(inter.user))
        btn.callback = create_callback
        view.add_item(btn)
        await interaction.followup.send(embed=embed, view=view, ephemeral=True)
    elif n == 1 and limit == 1:
        # Exactly one role, no room for more — go straight to manage
        rec, role_obj = valid_roles[0]
        embed = build_role_info_embed(interaction.user, rec, role_obj, include_tips=True)
        await interaction.followup.send(embed=embed, view=EditView(interaction.user, role_obj), ephemeral=True)
    else:
        # Multiple roles, or 1 role with room to create more — show picker
        slots_text = f"**{n} / {limit}** custom role{'s' if limit != 1 else ''} used."
        action_text = "Select a role below to edit it, or create a new one to continue customizing your profile." if not at_limit else "Select a role below to manage or update it."
        embed = make_embed(
            "Manage Your Custom Roles",
            f"> {slots_text}\n\n> {action_text}",
            kind="info",
            scope=SCOPE_ROLES,
            guild=interaction.guild,
            thumbnail=interaction.user.display_avatar.url,
        )
        await interaction.followup.send(embed=embed, view=RolePickerView(interaction.user, valid_roles, at_limit), ephemeral=True)

# --- Setup / Config System ---

async def role_manage(interaction: discord.Interaction, action: str, target: Optional[Union[discord.Member, discord.Role]] = None, limit: int = 1):
    await interaction.response.defer(ephemeral=True)
    conf = bot.data_manager.config
    
    if action == "manage_user":
        if not isinstance(target, discord.Member):
            await interaction.followup.send(embed=make_embed("Invalid Target", "> Target must be a user.", kind="error", scope=SCOPE_ROLES, guild=interaction.guild), ephemeral=True)
            return

        records = get_user_role_records(target.id)
        valid_roles = [(rec, interaction.guild.get_role(rec.get("role_id"))) for rec in records]
        valid_roles = [(rec, r) for rec, r in valid_roles if r]

        if not valid_roles:
            await interaction.followup.send(embed=make_embed("No Custom Role", f"> {target.mention} does not have a custom role.", kind="info", scope=SCOPE_ROLES, guild=interaction.guild), ephemeral=True)
        elif len(valid_roles) == 1:
            rec, role = valid_roles[0]
            embed = build_role_info_embed(target, rec, role, include_tips=True)
            embed.set_footer(text=f"Admin Control Panel for {target.display_name}")
            await interaction.followup.send(embed=embed, view=EditView(target, role), ephemeral=True)
        else:
            embed = make_embed(
                f"Custom Roles — {target.display_name}",
                f"> This member has **{len(valid_roles)}** custom roles. Select one to manage.",
                kind="info",
                scope=SCOPE_ROLES,
                guild=interaction.guild,
            )
            await interaction.followup.send(embed=embed, view=RolePickerView(target, valid_roles, at_limit=True), ephemeral=True)
        return

    if target is None:
        await interaction.followup.send(embed=make_embed("Missing Target", "> Target is required for this action.", kind="error", scope=SCOPE_ROLES, guild=interaction.guild), ephemeral=True)
        return

    tid = str(target.id)
    msg = ""

    if action == "whitelist":
        if isinstance(target, discord.Member):
            if "cr_whitelist_users" not in conf: conf["cr_whitelist_users"] = {}
            conf["cr_whitelist_users"][tid] = limit
            if "cr_blacklist_users" in conf and tid in conf["cr_blacklist_users"]:
                conf["cr_blacklist_users"].remove(tid)
            msg = f"Whitelisted user {target.mention} with limit **{limit}**."
        else:
            if "cr_whitelist_roles" not in conf: conf["cr_whitelist_roles"] = {}
            conf["cr_whitelist_roles"][tid] = limit
            if "cr_blacklist_roles" in conf and tid in conf["cr_blacklist_roles"]:
                conf["cr_blacklist_roles"].remove(tid)
            msg = f"Whitelisted role {target.mention} with limit **{limit}**."
    
    elif action == "blacklist":
        if isinstance(target, discord.Member):
            if "cr_blacklist_users" not in conf: conf["cr_blacklist_users"] = []
            if tid not in conf["cr_blacklist_users"]:
                conf["cr_blacklist_users"].append(tid)
            if "cr_whitelist_users" in conf and tid in conf["cr_whitelist_users"]:
                del conf["cr_whitelist_users"][tid]
            msg = f"Blacklisted user {target.mention}."
        else:
            if "cr_blacklist_roles" not in conf: conf["cr_blacklist_roles"] = []
            if tid not in conf["cr_blacklist_roles"]:
                conf["cr_blacklist_roles"].append(tid)
            if "cr_whitelist_roles" in conf and tid in conf["cr_whitelist_roles"]:
                del conf["cr_whitelist_roles"][tid]
            msg = f"Blacklisted role {target.mention}."

    elif action == "reset":
        changes = []
        if isinstance(target, discord.Member):
            if "cr_whitelist_users" in conf and tid in conf["cr_whitelist_users"]:
                del conf["cr_whitelist_users"][tid]
                changes.append("Removed from User Whitelist")
            if "cr_blacklist_users" in conf and tid in conf["cr_blacklist_users"]:
                conf["cr_blacklist_users"].remove(tid)
                changes.append("Removed from User Blacklist")
        else:
            if "cr_whitelist_roles" in conf and tid in conf["cr_whitelist_roles"]:
                del conf["cr_whitelist_roles"][tid]
                changes.append("Removed from Role Whitelist")
            if "cr_blacklist_roles" in conf and tid in conf["cr_blacklist_roles"]:
                conf["cr_blacklist_roles"].remove(tid)
                changes.append("Removed from Role Blacklist")
        
        if changes:
            msg = f"Reset {target.mention}: {', '.join(changes)}"
        else:
            msg = f"{target.mention} was not in any list."

    await bot.data_manager.save_config()
    await interaction.followup.send(embed=make_embed("Access Updated", f"> {msg}", kind="success", scope=SCOPE_ROLES, guild=interaction.guild), ephemeral=True)

@tree.command(name="role-settings", description="Configure custom role access.")
@app_commands.default_permissions(administrator=True)
@app_commands.check(check_admin)
async def role_settings(interaction: discord.Interaction):
    embed = build_role_settings_embed(interaction.guild)
    await interaction.response.send_message(embed=embed, view=RoleSettingsView(), ephemeral=True)

class RolesCog(commands.Cog):
    def __init__(self, bot):
        self.bot = bot


async def setup(bot):
    await bot.add_cog(RolesCog(bot))
    bot.tree.add_command(role_cmd)
    bot.tree.add_command(role_settings)
