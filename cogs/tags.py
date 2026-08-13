"""Server-defined content: reusable tags and self-assignable role panels.

Both let staff customise what the bot says and offers without a code change or a
deploy — tags are canned answers stored in config, and self-roles are a persistent
picker over an allow-list of roles.
"""

from __future__ import annotations

import re
from typing import Dict, List, Optional

import discord
from discord import app_commands
from discord.ext import commands

from core.constants import SCOPE_ROLES, SCOPE_SYSTEM
from core.context import bot, tree
from core.responding import InteractionResponder
from core.utils import truncate_text

from .shared import (
    format_user_ref,
    has_permission_capability,
    logger,
    make_embed,
    make_empty_state_embed,
    respond_with_error,
    respond_with_operation_failure,
    send_log,
)


TAG_NAME_PATTERN = re.compile(r"^[a-z0-9][a-z0-9_-]{0,30}$")
TAG_CONTENT_LIMIT = 2000
MAX_TAGS = 100
MAX_SELF_ROLES = 25

SELF_ROLE_CUSTOM_ID = "selfrole_picker"


# ---------------------------------------------------------------------------
# Tags
# ---------------------------------------------------------------------------


def normalize_tag_name(raw: str) -> str:
    return str(raw or "").strip().lower()


def validate_tag_name(raw: str) -> Optional[str]:
    """Return the normalized name, or None when it is not a usable tag name."""
    name = normalize_tag_name(raw)
    return name if TAG_NAME_PATTERN.fullmatch(name) else None


def get_tags(config: Optional[dict] = None) -> Dict[str, dict]:
    config = bot.data_manager.config if config is None else config
    tags = config.get("tags")
    if not isinstance(tags, dict):
        return {}
    return {
        str(name): record
        for name, record in tags.items()
        if isinstance(record, dict) and record.get("content")
    }


def search_tag_names(query: str, config: Optional[dict] = None, *, limit: int = 25) -> List[str]:
    needle = normalize_tag_name(query)
    names = sorted(get_tags(config))
    if needle:
        names = [name for name in names if needle in name]
    return names[:limit]


def build_tag_embed(name: str, record: dict, *, guild: Optional[discord.Guild]) -> discord.Embed:
    return make_embed(
        truncate_text(str(record.get("title") or name), 256),
        truncate_text(str(record.get("content") or ""), 4000),
        kind="info",
        scope=SCOPE_SYSTEM,
        guild=guild,
    )


def build_tag_list_embed(config: Optional[dict], *, guild: Optional[discord.Guild]) -> discord.Embed:
    tags = get_tags(config)
    if not tags:
        return make_empty_state_embed(
            "Tags",
            "> No tags have been created yet. Staff can add one with `/tag-admin create`.",
            scope=SCOPE_SYSTEM,
            guild=guild,
        )
    lines = []
    for name in sorted(tags):
        record = tags[name]
        summary = truncate_text(str(record.get("title") or record.get("content") or ""), 80)
        lines.append(f"`{name}` · {summary}")
    embed = make_embed(
        f"Tags ({len(tags)})",
        truncate_text("\n".join(lines), 4000),
        kind="info",
        scope=SCOPE_SYSTEM,
        guild=guild,
    )
    embed.add_field(name="Usage", value="Run `/tag name:<tag>` to post one.", inline=False)
    return embed


async def _tag_autocomplete(interaction: discord.Interaction, current: str):
    try:
        names = search_tag_names(current)
    except Exception:
        logger.warning("Tag autocomplete failed", exc_info=True)
        return []
    return [app_commands.Choice(name=name, value=name) for name in names]


@tree.command(name="tag", description="Post a saved server tag.")
@app_commands.describe(name="Tag to post", public="Post it visibly in the channel instead of privately")
@app_commands.autocomplete(name=_tag_autocomplete)
async def tag(interaction: discord.Interaction, name: str, public: Optional[bool] = None) -> None:
    record = get_tags().get(normalize_tag_name(name))
    if record is None:
        await respond_with_error(
            interaction,
            f"No tag named `{normalize_tag_name(name)}` exists. Use `/tags` to see what is available.",
            scope=SCOPE_SYSTEM,
        )
        return

    # Only staff may push a tag into the channel for everyone to see.
    show_publicly = bool(public) and has_permission_capability(interaction, "cases.read")
    await InteractionResponder(interaction).send(
        embed=build_tag_embed(normalize_tag_name(name), record, guild=interaction.guild),
        ephemeral=not show_publicly,
    )


@tree.command(name="tags", description="List every saved server tag.")
async def tags(interaction: discord.Interaction) -> None:
    await InteractionResponder(interaction).send(
        embed=build_tag_list_embed(None, guild=interaction.guild), ephemeral=True
    )


tag_admin_group = app_commands.Group(
    name="tag-admin",
    description="Create, edit, and delete server tags.",
)


@tag_admin_group.command(name="create", description="Create a new server tag.")
@app_commands.describe(
    name="Short lowercase key, for example appeal-process",
    content="What the tag should say",
    title="Optional heading; defaults to the tag name",
)
async def tag_create(
    interaction: discord.Interaction,
    name: str,
    content: app_commands.Range[str, 1, TAG_CONTENT_LIMIT],
    title: Optional[app_commands.Range[str, 1, 256]] = None,
) -> None:
    if not has_permission_capability(interaction, "config_panel"):
        await respond_with_error(interaction, "You do not have permission to manage tags.", scope=SCOPE_SYSTEM)
        return

    key = validate_tag_name(name)
    if key is None:
        await respond_with_error(
            interaction,
            "Tag names must be 1-31 characters of lowercase letters, numbers, `-`, or `_`.",
            scope=SCOPE_SYSTEM,
        )
        return

    existing = get_tags()
    if key in existing:
        await respond_with_error(
            interaction,
            f"A tag named `{key}` already exists. Use `/tag-admin edit` to change it.",
            scope=SCOPE_SYSTEM,
        )
        return
    if len(existing) >= MAX_TAGS:
        await respond_with_error(
            interaction,
            f"This server already has the maximum of {MAX_TAGS} tags. Delete one first.",
            scope=SCOPE_SYSTEM,
        )
        return

    responder = InteractionResponder(interaction)
    await responder.defer(ephemeral=True)
    try:
        await bot.data_manager.mutate_config(
            lambda config: config.setdefault("tags", {}).__setitem__(
                key, {"title": title, "content": content, "author_id": interaction.user.id}
            )
        )
    except Exception as error:
        await respond_with_operation_failure(interaction, error, operation="create tag", scope=SCOPE_SYSTEM)
        return

    await responder.send(
        embed=make_embed(
            "Tag Created",
            f"> `{key}` is now available through `/tag`.",
            kind="success",
            scope=SCOPE_SYSTEM,
            guild=interaction.guild,
        ),
        ephemeral=True,
    )
    await _log_tag_change(interaction, "Tag Created", key)


@tag_admin_group.command(name="edit", description="Change an existing tag's content.")
@app_commands.describe(name="Tag to change", content="Replacement text", title="Optional replacement heading")
@app_commands.autocomplete(name=_tag_autocomplete)
async def tag_edit(
    interaction: discord.Interaction,
    name: str,
    content: app_commands.Range[str, 1, TAG_CONTENT_LIMIT],
    title: Optional[app_commands.Range[str, 1, 256]] = None,
) -> None:
    if not has_permission_capability(interaction, "config_panel"):
        await respond_with_error(interaction, "You do not have permission to manage tags.", scope=SCOPE_SYSTEM)
        return

    key = normalize_tag_name(name)
    if key not in get_tags():
        await respond_with_error(interaction, f"No tag named `{key}` exists.", scope=SCOPE_SYSTEM)
        return

    responder = InteractionResponder(interaction)
    await responder.defer(ephemeral=True)

    def update(config):
        record = config.setdefault("tags", {}).setdefault(key, {})
        record["content"] = content
        # Keep the old heading when the editor did not supply a new one.
        if title is not None:
            record["title"] = title
        record["editor_id"] = interaction.user.id

    try:
        await bot.data_manager.mutate_config(update)
    except Exception as error:
        await respond_with_operation_failure(interaction, error, operation="edit tag", scope=SCOPE_SYSTEM)
        return

    await responder.send(
        embed=make_embed(
            "Tag Updated",
            f"> `{key}` was updated.",
            kind="success",
            scope=SCOPE_SYSTEM,
            guild=interaction.guild,
        ),
        ephemeral=True,
    )
    await _log_tag_change(interaction, "Tag Updated", key)


@tag_admin_group.command(name="delete", description="Delete a server tag.")
@app_commands.describe(name="Tag to delete")
@app_commands.autocomplete(name=_tag_autocomplete)
async def tag_delete(interaction: discord.Interaction, name: str) -> None:
    if not has_permission_capability(interaction, "config_panel"):
        await respond_with_error(interaction, "You do not have permission to manage tags.", scope=SCOPE_SYSTEM)
        return

    key = normalize_tag_name(name)
    if key not in get_tags():
        await respond_with_error(interaction, f"No tag named `{key}` exists.", scope=SCOPE_SYSTEM)
        return

    responder = InteractionResponder(interaction)
    await responder.defer(ephemeral=True)
    try:
        await bot.data_manager.mutate_config(
            lambda config: config.get("tags", {}).pop(key, None)
        )
    except Exception as error:
        await respond_with_operation_failure(interaction, error, operation="delete tag", scope=SCOPE_SYSTEM)
        return

    await responder.send(
        embed=make_embed(
            "Tag Deleted",
            f"> `{key}` was removed.",
            kind="success",
            scope=SCOPE_SYSTEM,
            guild=interaction.guild,
        ),
        ephemeral=True,
    )
    await _log_tag_change(interaction, "Tag Deleted", key)


async def _log_tag_change(interaction: discord.Interaction, title: str, key: str) -> None:
    embed = make_embed(
        title,
        "> A server tag was changed.",
        kind="info",
        scope=SCOPE_SYSTEM,
        guild=interaction.guild,
    )
    embed.add_field(name="Actor", value=format_user_ref(interaction.user), inline=True)
    embed.add_field(name="Tag", value=f"`{key}`", inline=True)
    await send_log(interaction.guild, embed)


# ---------------------------------------------------------------------------
# Self-assignable roles
# ---------------------------------------------------------------------------


def get_self_role_ids(config: Optional[dict] = None) -> List[int]:
    config = bot.data_manager.config if config is None else config
    raw = config.get("self_roles")
    if not isinstance(raw, list):
        return []
    role_ids: List[int] = []
    for value in raw:
        try:
            role_id = int(value)
        except (TypeError, ValueError):
            continue
        if role_id and role_id not in role_ids:
            role_ids.append(role_id)
    return role_ids[:MAX_SELF_ROLES]


def resolve_self_roles(guild: discord.Guild, config: Optional[dict] = None) -> List[discord.Role]:
    """Configured self-roles that still exist and sit below the bot's top role."""
    me = guild.me
    ceiling = me.top_role if me is not None else None
    roles = []
    for role_id in get_self_role_ids(config):
        role = guild.get_role(role_id)
        if role is None or role.managed or role.is_default():
            continue
        if ceiling is not None and role >= ceiling:
            continue
        roles.append(role)
    return roles


def build_self_role_embed(roles: List[discord.Role], *, guild: Optional[discord.Guild]) -> discord.Embed:
    if not roles:
        return make_empty_state_embed(
            "Self Roles",
            "> No self-assignable roles are configured yet. Staff can add them with "
            "`/selfrole-admin add`.",
            scope=SCOPE_ROLES,
            guild=guild,
        )
    return make_embed(
        "Self Roles",
        "> " + "\n> ".join(role.mention for role in roles),
        kind="info",
        scope=SCOPE_ROLES,
        guild=guild,
    )


class SelfRoleSelect(discord.ui.DynamicItem[discord.ui.Select], template=SELF_ROLE_CUSTOM_ID):
    """Persistent picker that toggles a member's self-assignable roles."""

    def __init__(self, roles: List[discord.Role], *, item: Optional[discord.ui.Select] = None) -> None:
        options = [
            discord.SelectOption(label=truncate_text(role.name, 100), value=str(role.id))
            for role in roles[:MAX_SELF_ROLES]
        ]
        super().__init__(item or discord.ui.Select(
            custom_id=SELF_ROLE_CUSTOM_ID,
            placeholder="Choose your roles...",
            min_values=0,
            max_values=max(1, len(options)),
            options=options or [discord.SelectOption(label="No roles configured", value="none")],
            disabled=not options,
        ))

    @classmethod
    async def from_custom_id(cls, interaction, item, match):
        guild = interaction.guild
        roles = resolve_self_roles(guild) if guild is not None else []
        return cls(roles, item=item)

    async def callback(self, interaction: discord.Interaction) -> None:
        guild = interaction.guild
        member = interaction.user
        if guild is None or not isinstance(member, discord.Member):
            await respond_with_error(interaction, "This picker only works inside a server.", scope=SCOPE_ROLES)
            return

        responder = InteractionResponder(interaction)
        await responder.defer(ephemeral=True)

        available = {role.id: role for role in resolve_self_roles(guild)}
        selected_values = getattr(self, "_values", None) or self.item.values
        chosen = {int(value) for value in selected_values if str(value).isdigit()}

        held = {role.id for role in member.roles}
        to_add = [available[role_id] for role_id in chosen & set(available) if role_id not in held]
        to_remove = [available[role_id] for role_id in set(available) - chosen if role_id in held]

        try:
            if to_add:
                await member.add_roles(*to_add, reason="Self-assigned role")
            if to_remove:
                await member.remove_roles(*to_remove, reason="Self-removed role")
        except Exception as error:
            await respond_with_operation_failure(interaction, error, operation="update self roles", scope=SCOPE_ROLES)
            return

        if not to_add and not to_remove:
            description = "> Your roles are already up to date."
        else:
            parts = []
            if to_add:
                parts.append("Added " + ", ".join(role.mention for role in to_add))
            if to_remove:
                parts.append("Removed " + ", ".join(role.mention for role in to_remove))
            description = "> " + "\n> ".join(parts)

        await responder.send(
            embed=make_embed("Roles Updated", description, kind="success", scope=SCOPE_ROLES, guild=guild),
            ephemeral=True,
        )


class SelfRoleView(discord.ui.View):
    def __init__(self, roles: List[discord.Role]) -> None:
        super().__init__(timeout=None)
        self.add_item(SelfRoleSelect(roles))


@tree.command(name="selfroles", description="Pick your own roles.")
async def selfroles(interaction: discord.Interaction) -> None:
    if interaction.guild is None:
        await respond_with_error(interaction, "This command only works inside a server.", scope=SCOPE_ROLES)
        return

    roles = resolve_self_roles(interaction.guild)
    await InteractionResponder(interaction).send(
        embed=build_self_role_embed(roles, guild=interaction.guild),
        view=SelfRoleView(roles) if roles else None,
        ephemeral=True,
    )


selfrole_admin_group = app_commands.Group(
    name="selfrole-admin",
    description="Choose which roles members may assign themselves.",
)


@selfrole_admin_group.command(name="add", description="Allow members to assign themselves a role.")
@app_commands.describe(role="Role to make self-assignable")
async def selfrole_add(interaction: discord.Interaction, role: discord.Role) -> None:
    if not has_permission_capability(interaction, "config_panel"):
        await respond_with_error(interaction, "You do not have permission to manage self roles.", scope=SCOPE_ROLES)
        return
    if interaction.guild is None:
        await respond_with_error(interaction, "This command only works inside a server.", scope=SCOPE_ROLES)
        return

    if role.managed or role.is_default():
        await respond_with_error(
            interaction,
            "Integration-managed roles and @everyone cannot be self-assigned.",
            scope=SCOPE_ROLES,
        )
        return

    me = interaction.guild.me
    if me is not None and role >= me.top_role:
        await respond_with_error(
            interaction,
            f"{role.mention} sits at or above my highest role, so I cannot grant it.",
            scope=SCOPE_ROLES,
        )
        return
    if role.permissions.administrator or role.permissions.manage_guild:
        await respond_with_error(
            interaction,
            "That role carries administrative permissions and cannot be self-assigned.",
            scope=SCOPE_ROLES,
        )
        return

    current = get_self_role_ids()
    if role.id in current:
        await respond_with_error(interaction, f"{role.mention} is already self-assignable.", scope=SCOPE_ROLES)
        return
    if len(current) >= MAX_SELF_ROLES:
        await respond_with_error(
            interaction,
            f"A self-role picker holds at most {MAX_SELF_ROLES} roles. Remove one first.",
            scope=SCOPE_ROLES,
        )
        return

    responder = InteractionResponder(interaction)
    await responder.defer(ephemeral=True)
    try:
        await bot.data_manager.set_config_values(self_roles=current + [role.id])
    except Exception as error:
        await respond_with_operation_failure(interaction, error, operation="add self role", scope=SCOPE_ROLES)
        return

    await responder.send(
        embed=make_embed(
            "Self Role Added",
            f"> Members can now assign themselves {role.mention} through `/selfroles`.",
            kind="success",
            scope=SCOPE_ROLES,
            guild=interaction.guild,
        ),
        ephemeral=True,
    )
    await _log_self_role_change(interaction, "Self Role Added", role)


@selfrole_admin_group.command(name="remove", description="Stop members assigning themselves a role.")
@app_commands.describe(role="Role to remove from the picker")
async def selfrole_remove(interaction: discord.Interaction, role: discord.Role) -> None:
    if not has_permission_capability(interaction, "config_panel"):
        await respond_with_error(interaction, "You do not have permission to manage self roles.", scope=SCOPE_ROLES)
        return

    current = get_self_role_ids()
    if role.id not in current:
        await respond_with_error(interaction, f"{role.mention} is not self-assignable.", scope=SCOPE_ROLES)
        return

    responder = InteractionResponder(interaction)
    await responder.defer(ephemeral=True)
    try:
        await bot.data_manager.set_config_values(
            self_roles=[role_id for role_id in current if role_id != role.id]
        )
    except Exception as error:
        await respond_with_operation_failure(interaction, error, operation="remove self role", scope=SCOPE_ROLES)
        return

    await responder.send(
        embed=make_embed(
            "Self Role Removed",
            f"> {role.mention} is no longer self-assignable.",
            kind="success",
            scope=SCOPE_ROLES,
            guild=interaction.guild,
        ),
        ephemeral=True,
    )
    await _log_self_role_change(interaction, "Self Role Removed", role)


@selfrole_admin_group.command(name="panel", description="Post the public self-role picker in this channel.")
async def selfrole_panel(interaction: discord.Interaction) -> None:
    if not has_permission_capability(interaction, "config_panel"):
        await respond_with_error(interaction, "You do not have permission to post the self-role panel.", scope=SCOPE_ROLES)
        return
    if interaction.guild is None:
        await respond_with_error(interaction, "This command only works inside a server.", scope=SCOPE_ROLES)
        return

    roles = resolve_self_roles(interaction.guild)
    if not roles:
        await respond_with_error(
            interaction,
            "Add at least one role with `/selfrole-admin add` before posting the panel.",
            scope=SCOPE_ROLES,
        )
        return

    responder = InteractionResponder(interaction)
    await responder.defer(ephemeral=True)
    try:
        message = await interaction.channel.send(
            embed=build_self_role_embed(roles, guild=interaction.guild),
            view=SelfRoleView(roles),
        )
    except Exception as error:
        await respond_with_operation_failure(interaction, error, operation="post self role panel", scope=SCOPE_ROLES)
        return

    await responder.send(
        embed=make_embed(
            "Panel Posted",
            f"> The self-role picker is live: {message.jump_url}",
            kind="success",
            scope=SCOPE_ROLES,
            guild=interaction.guild,
        ),
        ephemeral=True,
    )


async def _log_self_role_change(interaction: discord.Interaction, title: str, role: discord.Role) -> None:
    embed = make_embed(
        title,
        "> The self-assignable role list was changed.",
        kind="info",
        scope=SCOPE_ROLES,
        guild=interaction.guild,
    )
    embed.add_field(name="Actor", value=format_user_ref(interaction.user), inline=True)
    embed.add_field(name="Role", value=f"{role.mention} (`{role.id}`)", inline=True)
    await send_log(interaction.guild, embed)


class TagsCog(commands.Cog):
    def __init__(self, bot):
        self.bot = bot


async def setup(bot_instance) -> None:
    await bot_instance.add_cog(TagsCog(bot_instance))
    bot_instance.tree.add_command(tag)
    bot_instance.tree.add_command(tags)
    bot_instance.tree.add_command(selfroles)
    bot_instance.tree.add_command(tag_admin_group)
    bot_instance.tree.add_command(selfrole_admin_group)
