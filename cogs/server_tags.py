"""Grants a role to members displaying a server tag, and takes it back when they drop it.

Discord exposes the equipped tag as the user's *primary guild*, which discord.py surfaces
as :class:`discord.PrimaryGuild`. It is part of the user object, so a change arrives as a
normal ``on_user_update`` — discord.py compares ``_primary_guild`` when deciding whether
to dispatch that event, so equipping or unequipping a tag is a real event rather than
something that has to be polled for.

Two servers can be linked. The accepted set is a list of guild IDs, so a member wearing
*any* linked server's tag earns the role here. That is why the check is against a set
rather than against this guild's own ID: the role lives in one server, the tag that earns
it may come from another.
"""

import asyncio
from typing import Iterable, List, Optional, Set

import discord
from discord import app_commands
from discord.ext import commands, tasks

from core.context import bot
from .shared import logger, make_embed

ROLE_KEY = "server_tag_role_id"
GUILDS_KEY = "server_tag_guild_ids"
RECONCILE_MINUTES = 10


def parse_guild_ids(raw: object) -> List[int]:
    """Reads stored IDs defensively; anything unparseable is simply not a linked server."""
    if not isinstance(raw, (list, tuple, set)):
        return []
    parsed: List[int] = []
    for value in raw:
        try:
            guild_id = int(value)
        except (TypeError, ValueError):
            continue
        if guild_id > 0 and guild_id not in parsed:
            parsed.append(guild_id)
    return parsed


def accepted_guild_ids(raw: object, home_guild_id: int) -> Set[int]:
    """Whose tags earn the role here. Unset means this server's own tag, nothing else."""
    linked = parse_guild_ids(raw)
    if not linked:
        return {int(home_guild_id)}
    return set(linked)


def tag_matches(
    primary_guild_id: Optional[int],
    identity_enabled: Optional[bool],
    accepted: Iterable[int],
) -> bool:
    """Whether this profile is wearing a tag that earns the role.

    ``identity_enabled`` is three-valued. ``False`` means the tag is deliberately hidden.
    ``None`` means the user has a primary guild but has not reaffirmed it after a change,
    and discord.py's own documentation warns the tag can still be shown then — so only an
    explicit ``False`` is treated as unequipped. Taking somebody's role away on an
    ambiguous state would flap it on and off every time Discord changed the badge.
    """
    if primary_guild_id is None or identity_enabled is False:
        return False
    return int(primary_guild_id) in {int(value) for value in accepted}


def wearer_of(member: discord.Member, accepted: Iterable[int]) -> bool:
    primary = getattr(member, "primary_guild", None)
    if primary is None:
        return False
    return tag_matches(primary.id, primary.identity_enabled, accepted)


class ServerTagCog(commands.Cog):
    servertag = app_commands.Group(
        name="servertag",
        description="Give a role to members wearing this server's tag.",
        default_permissions=discord.Permissions(manage_guild=True),
        guild_only=True,
    )

    def __init__(self, bot_instance: commands.Bot) -> None:
        self.bot = bot_instance
        self._lock = asyncio.Lock()

    async def cog_load(self) -> None:
        self.reconcile_loop.start()

    async def cog_unload(self) -> None:
        self.reconcile_loop.cancel()

    # ---- configuration -------------------------------------------------

    def _role(self, guild: discord.Guild) -> Optional[discord.Role]:
        role_id = bot.data_manager.config.get(ROLE_KEY) or 0
        try:
            role_id = int(role_id)
        except (TypeError, ValueError):
            return None
        return guild.get_role(role_id) if role_id else None

    def _accepted(self, guild: discord.Guild) -> Set[int]:
        return accepted_guild_ids(bot.data_manager.config.get(GUILDS_KEY), guild.id)

    # ---- syncing -------------------------------------------------------

    async def sync_member(self, member: discord.Member) -> Optional[bool]:
        """Applies or removes the role. Returns the new state, or None if nothing to do."""
        if member.bot:
            return None
        role = self._role(member.guild)
        if role is None:
            return None
        if not self._assignable(member.guild, role):
            return None

        wanted = wearer_of(member, self._accepted(member.guild))
        held = role in member.roles
        if wanted == held:
            return None
        try:
            if wanted:
                await member.add_roles(role, reason="Equipped a linked server tag")
            else:
                await member.remove_roles(role, reason="Removed the linked server tag")
        except discord.Forbidden:
            logger.warning(
                "Missing permission to update the server tag role for %s", member.id
            )
            return None
        except discord.HTTPException as error:
            logger.warning("Server tag role update failed for %s: %s", member.id, error)
            return None
        return wanted

    def _assignable(self, guild: discord.Guild, role: discord.Role) -> bool:
        """A role above the bot cannot be granted; say so once rather than 429ing on it."""
        me = guild.me
        if me is None or not me.guild_permissions.manage_roles:
            return False
        return role < me.top_role

    async def sync_guild(self, guild: discord.Guild) -> int:
        changed = 0
        for member in guild.members:
            if await self.sync_member(member) is not None:
                changed += 1
        return changed

    # ---- events --------------------------------------------------------

    @commands.Cog.listener()
    async def on_user_update(self, before: discord.User, after: discord.User) -> None:
        """The tag lives on the user, so this is where equipping and unequipping lands."""
        before_tag = getattr(before, "primary_guild", None)
        after_tag = getattr(after, "primary_guild", None)
        if _identity(before_tag) == _identity(after_tag):
            return
        for guild in self.bot.guilds:
            member = guild.get_member(after.id)
            if member is not None:
                await self.sync_member(member)

    @commands.Cog.listener()
    async def on_member_join(self, member: discord.Member) -> None:
        await self.sync_member(member)

    @tasks.loop(minutes=RECONCILE_MINUTES)
    async def reconcile_loop(self) -> None:
        """Catches anything the gateway missed, and anything changed while offline."""
        async with self._lock:
            for guild in self.bot.guilds:
                try:
                    await self.sync_guild(guild)
                except Exception as error:  # noqa: BLE001 - a sweep must never die
                    logger.warning("Server tag reconcile failed for %s: %s", guild.id, error)

    @reconcile_loop.before_loop
    async def _before_reconcile(self) -> None:
        await self.bot.wait_until_ready()

    # ---- commands ------------------------------------------------------

    @servertag.command(name="role", description="Set the role given to server tag wearers.")
    @app_commands.describe(role="Leave empty to turn the feature off.")
    async def set_role(
        self, interaction: discord.Interaction, role: Optional[discord.Role] = None
    ) -> None:
        bot.data_manager.config[ROLE_KEY] = role.id if role else 0
        bot.data_manager.mark_config_dirty()
        if role is None:
            await interaction.response.send_message(
                embed=make_embed("Server Tag", "Server tag roles are now off.", kind="info"),
                ephemeral=True,
            )
            return
        if interaction.guild and not self._assignable(interaction.guild, role):
            await interaction.response.send_message(
                embed=make_embed(
                    "Server Tag",
                    f"Saved, but I cannot grant {role.mention} — it sits above my own top "
                    "role, or I am missing Manage Roles.",
                    kind="warning",
                ),
                ephemeral=True,
            )
            return
        await interaction.response.defer(ephemeral=True)
        changed = await self.sync_guild(interaction.guild) if interaction.guild else 0
        await interaction.followup.send(
            embed=make_embed(
                "Server Tag",
                f"{role.mention} now tracks the server tag. Updated {changed} member(s).",
                kind="success",
            ),
            ephemeral=True,
        )

    @servertag.command(name="link", description="Also accept another server's tag.")
    @app_commands.describe(guild_id="The other server's ID.")
    async def link(self, interaction: discord.Interaction, guild_id: str) -> None:
        try:
            target = int(guild_id.strip())
        except ValueError:
            await interaction.response.send_message(
                embed=make_embed("Server Tag", "That is not a server ID.", kind="danger"),
                ephemeral=True,
            )
            return
        current = self._accepted(interaction.guild)
        current.add(target)
        bot.data_manager.config[GUILDS_KEY] = sorted(current)
        bot.data_manager.mark_config_dirty()
        await interaction.response.defer(ephemeral=True)
        changed = await self.sync_guild(interaction.guild)
        await interaction.followup.send(
            embed=make_embed(
                "Server Tag",
                f"Now accepting {len(current)} server tag(s). Updated {changed} member(s).",
                kind="success",
            ),
            ephemeral=True,
        )

    @servertag.command(name="unlink", description="Stop accepting another server's tag.")
    @app_commands.describe(guild_id="The other server's ID.")
    async def unlink(self, interaction: discord.Interaction, guild_id: str) -> None:
        try:
            target = int(guild_id.strip())
        except ValueError:
            await interaction.response.send_message(
                embed=make_embed("Server Tag", "That is not a server ID.", kind="danger"),
                ephemeral=True,
            )
            return
        current = self._accepted(interaction.guild)
        current.discard(target)
        if not current:
            current = {interaction.guild.id}
        bot.data_manager.config[GUILDS_KEY] = sorted(current)
        bot.data_manager.mark_config_dirty()
        await interaction.response.defer(ephemeral=True)
        changed = await self.sync_guild(interaction.guild)
        await interaction.followup.send(
            embed=make_embed(
                "Server Tag",
                f"Now accepting {len(current)} server tag(s). Updated {changed} member(s).",
                kind="success",
            ),
            ephemeral=True,
        )

    @servertag.command(name="status", description="Show the current server tag setup.")
    async def status(self, interaction: discord.Interaction) -> None:
        role = self._role(interaction.guild) if interaction.guild else None
        accepted = self._accepted(interaction.guild) if interaction.guild else set()
        wearers = sum(
            1 for member in interaction.guild.members if wearer_of(member, accepted)
        ) if interaction.guild else 0
        lines = [
            f"Role: {role.mention if role else 'not set'}",
            "Accepted tags: " + ", ".join(f"`{value}`" for value in sorted(accepted)),
            f"Wearing one right now: **{wearers}**",
        ]
        await interaction.response.send_message(
            embed=make_embed("Server Tag", "\n".join(lines), kind="info"), ephemeral=True
        )


def _identity(primary: Optional[discord.PrimaryGuild]) -> tuple:
    if primary is None:
        return (None, None)
    return (primary.id, primary.identity_enabled)


async def setup(bot_instance: commands.Bot) -> None:
    await bot_instance.add_cog(ServerTagCog(bot_instance))
