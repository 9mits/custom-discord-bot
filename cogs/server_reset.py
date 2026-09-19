"""Administrator-only workflow for returning a Discord guild to an empty shell."""

from __future__ import annotations

from collections.abc import Awaitable, Iterable, Sequence
from dataclasses import dataclass, field

import discord

from core.constants import SCOPE_SYSTEM
from core.context import tree

from .shared import logger, make_embed

RESET_COMMAND_NAME = "reset-server"
KICK_ALL_COMMAND_NAME = "kick-all-members"
DESTROY_COMMAND_NAME = "destroy-server"
ACTIVE_SERVER_RESETS: set[int] = set()


def server_reset_confirmation_phrase(guild_id: int) -> str:
    return f"DELETE EVERYTHING {guild_id}"


def kick_all_confirmation_phrase(guild_id: int) -> str:
    return f"KICK EVERYONE {guild_id}"


def destroy_server_confirmation_phrase(guild_id: int) -> str:
    return f"DESTROY SERVER {guild_id}"


def can_reset_server(interaction: discord.Interaction) -> bool:
    permissions = getattr(interaction.user, "guild_permissions", None)
    return interaction.guild is not None and bool(
        permissions and permissions.administrator
    )


@dataclass
class ResetSectionResult:
    attempted: int = 0
    deleted: int = 0
    failures: list[str] = field(default_factory=list)


@dataclass
class ServerResetResult:
    sections: dict[str, ResetSectionResult] = field(default_factory=dict)
    protected_roles: list[str] = field(default_factory=list)

    def section(self, name: str) -> ResetSectionResult:
        return self.sections.setdefault(name, ResetSectionResult())

    @property
    def failure_count(self) -> int:
        return sum(len(section.failures) for section in self.sections.values())


@dataclass
class MemberRemovalResult:
    attempted: int = 0
    kicked: int = 0
    failures: list[str] = field(default_factory=list)
    requester_kicked: bool = False

    @property
    def failure_count(self) -> int:
        return len(self.failures)


def _role_hierarchy_blockers(
    guild: discord.Guild,
    roles: Sequence[discord.Role] | None = None,
) -> list[discord.Role]:
    me = getattr(guild, "me", None)
    top_role = getattr(me, "top_role", None)
    if top_role is None:
        return list(roles or getattr(guild, "roles", ()))
    return [
        role
        for role in (roles or guild.roles)
        if not role.is_default()
        and not role.managed
        and role.position >= top_role.position
    ]


def _member_hierarchy_blockers(
    guild: discord.Guild,
    members: Sequence[discord.Member],
) -> list[discord.Member]:
    me = getattr(guild, "me", None)
    top_role = getattr(me, "top_role", None)
    if me is None or top_role is None:
        return [member for member in members if member.id != guild.owner_id]
    return [
        member
        for member in members
        if member.id not in {guild.owner_id, me.id}
        and member.top_role.position >= top_role.position
    ]


def _hierarchy_blocker_text(
    guild: discord.Guild,
    *,
    roles: Sequence[discord.Role] = (),
    members: Sequence[discord.Member] = (),
) -> str:
    lines = [
        "Move the bot's highest role above every ordinary role and member, then try again.",
        "Discord's Administrator permission does not bypass role hierarchy.",
    ]
    if roles:
        names = ", ".join(f"`{role.name}`" for role in roles[:10])
        suffix = f" and {len(roles) - 10} more" if len(roles) > 10 else ""
        lines.append(f"Blocked roles: {names}{suffix}")
    if members:
        names = ", ".join(f"`{member}`" for member in members[:10])
        suffix = f" and {len(members) - 10} more" if len(members) > 10 else ""
        lines.append(f"Unkickable members: {names}{suffix}")
    lines.append(f"Bot role to move: **{guild.me.top_role.name if guild.me else 'bot role unavailable'}**")
    return "\n".join(lines)


def _item_label(item: object) -> str:
    name = getattr(item, "name", None) or getattr(item, "code", None)
    item_id = getattr(item, "id", None)
    if name and item_id:
        return f"{name} ({item_id})"
    if name:
        return str(name)
    if item_id:
        return str(item_id)
    return type(item).__name__


async def _record_operation(
    result: ServerResetResult,
    section_name: str,
    label: str,
    operation: Awaitable[object],
) -> None:
    section = result.section(section_name)
    section.attempted += 1
    try:
        await operation
    except Exception as exc:
        section.failures.append(f"{label}: {type(exc).__name__}: {exc}")
        logger.warning("Server reset could not remove %s from %s: %s", label, section_name, exc)
    else:
        section.deleted += 1


async def _fetch_items(
    guild: discord.Guild,
    result: ServerResetResult,
    section_name: str,
    method_name: str,
) -> list[object]:
    try:
        return list(await getattr(guild, method_name)())
    except Exception as exc:
        result.section(section_name).failures.append(
            f"Could not list items: {type(exc).__name__}: {exc}"
        )
        logger.warning("Server reset could not list %s in guild %s: %s", section_name, guild.id, exc)
        return []


async def _fetch_all_members(guild: discord.Guild) -> list[discord.Member]:
    return [member async for member in guild.fetch_members(limit=None)]


async def _clear_bans(
    guild: discord.Guild,
    result: ServerResetResult,
    *,
    reason: str,
) -> None:
    try:
        bans = [entry async for entry in guild.bans(limit=None)]
    except Exception as exc:
        result.section("Bans").failures.append(
            f"Could not list bans: {type(exc).__name__}: {exc}"
        )
        logger.warning("Server reset could not list bans in guild %s: %s", guild.id, exc)
        return

    for entry in bans:
        await _record_operation(
            result,
            "Bans",
            _item_label(entry.user),
            guild.unban(entry.user, reason=reason),
        )


async def _delete_items(
    result: ServerResetResult,
    section_name: str,
    items: Iterable[object],
    *,
    reason: str,
    include_reason: bool = True,
) -> None:
    for item in items:
        delete = item.delete
        operation = delete(reason=reason) if include_reason else delete()
        await _record_operation(result, section_name, _item_label(item), operation)


async def _delete_other_integrations(
    guild: discord.Guild,
    result: ServerResetResult,
    *,
    current_bot_id: int,
    reason: str,
) -> None:
    integrations = await _fetch_items(guild, result, "Integrations", "integrations")
    removable = [
        integration
        for integration in integrations
        if getattr(getattr(integration, "application", None), "id", None) != current_bot_id
    ]
    await _delete_items(result, "Integrations", removable, reason=reason)


async def _scrub_guild_identity(
    guild: discord.Guild,
    result: ServerResetResult,
    *,
    reason: str,
) -> None:
    await _record_operation(
        result,
        "Server identity",
        "Server name",
        guild.edit(name="Deleted Server", reason=reason),
    )


async def perform_kick_all_members(
    guild: discord.Guild,
    members: Sequence[discord.Member],
    *,
    requester_id: int,
    reason: str,
    keep_requester: bool = False,
) -> MemberRemovalResult:
    """Kick removable members, optionally preserving the requester."""
    result = MemberRemovalResult()
    me_id = getattr(getattr(guild, "me", None), "id", None)
    requester = None
    targets = []
    for member in members:
        if member.id in {guild.owner_id, me_id}:
            continue
        if member.id == requester_id:
            requester = member
            continue
        targets.append(member)

    for member in targets:
        result.attempted += 1
        try:
            await guild.kick(member, reason=reason)
        except discord.NotFound:
            result.kicked += 1
        except Exception as exc:
            result.failures.append(f"{_item_label(member)}: {type(exc).__name__}: {exc}")
            logger.warning("Could not kick %s during server destruction: %s", member.id, exc)
        else:
            result.kicked += 1

    if keep_requester or result.failures or requester is None or requester.id == guild.owner_id:
        return result

    result.attempted += 1
    try:
        await guild.kick(requester, reason=reason)
    except discord.NotFound:
        result.kicked += 1
        result.requester_kicked = True
    except Exception as exc:
        result.failures.append(f"{_item_label(requester)}: {type(exc).__name__}: {exc}")
        logger.warning("Could not kick requesting administrator %s: %s", requester.id, exc)
    else:
        result.kicked += 1
        result.requester_kicked = True
    return result


async def _reset_guild_profile(
    guild: discord.Guild,
    result: ServerResetResult,
    *,
    reason: str,
) -> None:
    if "COMMUNITY" in getattr(guild, "features", ()):
        await _record_operation(
            result,
            "Server settings",
            "Onboarding",
            guild.edit_onboarding(
                prompts=[],
                default_channels=[],
                enabled=False,
                reason=reason,
            ),
        )
        await _record_operation(
            result,
            "Server settings",
            "Welcome screen",
            guild.edit_welcome_screen(
                description=None,
                welcome_channels=[],
                enabled=False,
                reason=reason,
            ),
        )
        await _record_operation(
            result,
            "Server settings",
            "Community mode",
            guild.edit(community=False, reason=reason),
        )

    await _record_operation(
        result,
        "Server settings",
        "Profile images and description",
        guild.edit(
            description=None,
            icon=None,
            banner=None,
            splash=None,
            discovery_splash=None,
            reason=reason,
        ),
    )
    if "VANITY_URL" in getattr(guild, "features", ()):
        await _record_operation(
            result,
            "Server settings",
            "Vanity URL",
            guild.edit(vanity_code=None, reason=reason),
        )
    await _record_operation(
        result,
        "Server settings",
        "Default server settings",
        guild.edit(
            afk_channel=None,
            system_channel=None,
            rules_channel=None,
            public_updates_channel=None,
            default_notifications=discord.NotificationLevel.only_mentions,
            verification_level=discord.VerificationLevel.none,
            explicit_content_filter=discord.ContentFilter.disabled,
            premium_progress_bar_enabled=False,
            widget_enabled=False,
            invites_disabled_until=None,
            dms_disabled_until=None,
            reason=reason,
        ),
    )


async def perform_server_reset(
    guild: discord.Guild,
    *,
    reason: str,
    final_channel_ids: Iterable[int] = (),
) -> ServerResetResult:
    """Delete removable guild content and bans without removing members."""
    result = ServerResetResult()
    final_ids = {int(channel_id) for channel_id in final_channel_ids}

    await _reset_guild_profile(guild, result, reason=reason)
    await _clear_bans(guild, result, reason=reason)

    resources = (
        ("AutoMod rules", "fetch_automod_rules", True),
        ("Scheduled events", "fetch_scheduled_events", True),
        ("Soundboard sounds", "fetch_soundboard_sounds", True),
        ("Webhooks", "webhooks", True),
        ("Invites", "invites", True),
        ("Server templates", "templates", False),
        ("Emojis", "fetch_emojis", True),
        ("Stickers", "fetch_stickers", True),
    )
    for section_name, method_name, include_reason in resources:
        items = await _fetch_items(guild, result, section_name, method_name)
        await _delete_items(
            result,
            section_name,
            items,
            reason=reason,
            include_reason=include_reason,
        )

    roles = await _fetch_items(guild, result, "Roles", "fetch_roles")
    me = getattr(guild, "me", None)
    top_role = getattr(me, "top_role", None)
    top_position = getattr(top_role, "position", -1)
    deletable_roles = []
    for role in roles:
        is_default = bool(role.is_default())
        is_managed = bool(getattr(role, "managed", False))
        above_bot = top_role is not None and int(getattr(role, "position", 0)) >= int(top_position)
        if is_default or is_managed or above_bot:
            result.protected_roles.append(_item_label(role))
            continue
        deletable_roles.append(role)
    deletable_roles.sort(key=lambda role: int(getattr(role, "position", 0)), reverse=True)
    await _delete_items(result, "Roles", deletable_roles, reason=reason)

    channels = await _fetch_items(guild, result, "Channels", "fetch_channels")

    def channel_order(channel: object) -> tuple[int, int]:
        channel_id = int(getattr(channel, "id", 0))
        if channel_id in final_ids:
            return (2, int(getattr(channel, "position", 0)))
        if getattr(channel, "type", None) is discord.ChannelType.category:
            return (1, int(getattr(channel, "position", 0)))
        return (0, int(getattr(channel, "position", 0)))

    channels.sort(key=channel_order)
    await _delete_items(result, "Channels", channels, reason=reason)
    return result


def build_reset_summary(guild_name: str, result: ServerResetResult) -> discord.Embed:
    embed = make_embed(
        "Server Reset Finished",
        f"> Finished clearing **{guild_name}**. Members were not removed; the ban list was cleared.",
        kind="success" if result.failure_count == 0 else "warning",
        scope=SCOPE_SYSTEM,
    )
    lines = []
    for name, section in result.sections.items():
        lines.append(f"**{name}:** {section.deleted}/{section.attempted} removed")
    embed.add_field(name="Results", value="\n".join(lines) or "No removable content was found.", inline=False)
    if result.protected_roles:
        embed.add_field(
            name="Discord-Protected Roles",
            value=(
                f"{len(result.protected_roles)} managed, default, or hierarchy-protected role(s) remain. "
                "Discord does not allow the bot to delete these."
            ),
            inline=False,
        )
    if result.failure_count:
        failures = [failure for section in result.sections.values() for failure in section.failures]
        rendered = "\n".join(f"- {failure}" for failure in failures)
        if len(rendered) > 1000:
            rendered = rendered[:997] + "..."
        embed.add_field(name=f"Failures ({result.failure_count})", value=rendered, inline=False)
    return embed


class ServerResetModal(discord.ui.Modal):
    def __init__(self, *, guild_id: int, guild_name: str, requester_id: int) -> None:
        super().__init__(title="Confirm Complete Server Reset", timeout=180)
        self.guild_id = guild_id
        self.guild_name = guild_name
        self.requester_id = requester_id
        phrase = server_reset_confirmation_phrase(guild_id)
        self.confirmation = discord.ui.TextInput(
            label="Type the exact confirmation phrase",
            placeholder=phrase,
            min_length=len(phrase),
            max_length=len(phrase),
        )
        self.add_item(self.confirmation)

    async def on_submit(self, interaction: discord.Interaction) -> None:
        guild = interaction.guild
        if (
            guild is None
            or guild.id != self.guild_id
            or interaction.user.id != self.requester_id
            or not can_reset_server(interaction)
        ):
            await interaction.response.send_message(
                embed=make_embed(
                    "Access Denied",
                    "> Only a current Discord administrator can confirm this reset.",
                    kind="danger",
                    scope=SCOPE_SYSTEM,
                    guild=guild,
                ),
                ephemeral=True,
            )
            return

        required = server_reset_confirmation_phrase(guild.id)
        if str(self.confirmation.value).strip() != required:
            await interaction.response.send_message(
                embed=make_embed(
                    "Reset Cancelled",
                    f"> The phrase did not match. Nothing was deleted.\n> Required: `{required}`",
                    kind="warning",
                    scope=SCOPE_SYSTEM,
                    guild=guild,
                ),
                ephemeral=True,
            )
            return

        if guild.id in ACTIVE_SERVER_RESETS:
            await interaction.response.send_message(
                embed=make_embed(
                    "Reset Already Running",
                    "> This server is already being reset.",
                    kind="warning",
                    scope=SCOPE_SYSTEM,
                    guild=guild,
                ),
                ephemeral=True,
            )
            return

        ACTIVE_SERVER_RESETS.add(guild.id)
        await interaction.response.defer(ephemeral=True)
        try:
            try:
                roles = await guild.fetch_roles()
            except discord.HTTPException as exc:
                await interaction.edit_original_response(
                    embed=make_embed(
                        "Preflight Failed",
                        f"> I could not inspect the live role hierarchy. Nothing was deleted.\n> `{exc}`",
                        kind="danger",
                        scope=SCOPE_SYSTEM,
                        guild=guild,
                    )
                )
                return
            role_blockers = _role_hierarchy_blockers(guild, roles)
            if role_blockers:
                await interaction.edit_original_response(
                    embed=make_embed(
                        "Move My Role Higher First",
                        "> Nothing was deleted.\n\n" + _hierarchy_blocker_text(guild, roles=role_blockers),
                        kind="danger",
                        scope=SCOPE_SYSTEM,
                        guild=guild,
                    )
                )
                return

            await interaction.edit_original_response(
                embed=make_embed(
                    "Reset In Progress",
                    "> The reset has started. A final report will be sent to you by DM because every channel is being deleted.",
                    kind="danger",
                    scope=SCOPE_SYSTEM,
                    guild=guild,
                )
            )
            try:
                await interaction.user.send(
                    embed=make_embed(
                        "Server Reset Started",
                        f"> **{guild.name}** (`{guild.id}`) is being cleared now. Members will remain and every ban will be removed.",
                        kind="danger",
                        scope=SCOPE_SYSTEM,
                        guild=guild,
                    )
                )
            except discord.HTTPException:
                pass

            reason = f"Full server reset requested by administrator {interaction.user} ({interaction.user.id})"
            current_channel = interaction.channel
            final_channel_ids = {
                int(channel_id)
                for channel_id in (
                    getattr(current_channel, "id", None),
                    getattr(current_channel, "parent_id", None),
                )
                if channel_id is not None
            }
            result = await perform_server_reset(
                guild,
                reason=reason,
                final_channel_ids=final_channel_ids,
            )
            summary = build_reset_summary(self.guild_name, result)
            try:
                await interaction.user.send(embed=summary)
            except discord.HTTPException:
                logger.warning("Could not DM the server-reset summary to administrator %s", interaction.user.id)
            try:
                await interaction.edit_original_response(embed=summary)
            except discord.HTTPException:
                pass
        except Exception:
            logger.exception("Unexpected failure while resetting guild %s", guild.id)
            try:
                await interaction.user.send(
                    embed=make_embed(
                        "Server Reset Interrupted",
                        "> The reset stopped because of an unexpected error. Check the remaining server content before trying again.",
                        kind="danger",
                        scope=SCOPE_SYSTEM,
                    )
                )
            except discord.HTTPException:
                pass
        finally:
            ACTIVE_SERVER_RESETS.discard(guild.id)


class ServerResetConfirmView(discord.ui.View):
    def __init__(self, *, guild_id: int, guild_name: str, requester_id: int) -> None:
        super().__init__(timeout=120)
        self.guild_id = guild_id
        self.guild_name = guild_name
        self.requester_id = requester_id

    @discord.ui.button(label="Continue to Typed Confirmation", style=discord.ButtonStyle.danger)
    async def continue_reset(self, interaction: discord.Interaction, button: discord.ui.Button) -> None:
        if (
            interaction.guild is None
            or interaction.guild.id != self.guild_id
            or interaction.user.id != self.requester_id
            or not can_reset_server(interaction)
        ):
            await interaction.response.send_message(
                embed=make_embed(
                    "Access Denied",
                    "> Only a current Discord administrator can use this control.",
                    kind="danger",
                    scope=SCOPE_SYSTEM,
                    guild=interaction.guild,
                ),
                ephemeral=True,
            )
            return
        await interaction.response.send_modal(
            ServerResetModal(
                guild_id=self.guild_id,
                guild_name=self.guild_name,
                requester_id=self.requester_id,
            )
        )

    @discord.ui.button(label="Cancel", style=discord.ButtonStyle.secondary)
    async def cancel_reset(self, interaction: discord.Interaction, button: discord.ui.Button) -> None:
        await interaction.response.edit_message(
            embed=make_embed(
                "Reset Cancelled",
                "> Nothing was deleted.",
                kind="muted",
                scope=SCOPE_SYSTEM,
                guild=interaction.guild,
            ),
            view=None,
        )
        self.stop()


def build_member_removal_summary(
    guild_name: str,
    result: MemberRemovalResult,
    *,
    complete: bool,
) -> discord.Embed:
    embed = make_embed(
        "Member Removal Finished" if complete else "Member Removal Incomplete",
        (
            f"> Removed **{result.kicked}/{result.attempted}** targeted members from **{guild_name}**.\n"
            "> You were preserved. Discord's legal server owner cannot be kicked."
        ),
        kind="success" if complete else "warning",
        scope=SCOPE_SYSTEM,
    )
    if complete:
        embed.add_field(
            name="Final Step",
            value="The cleanup bot is leaving now. You remain in the emptied server with its legal owner.",
            inline=False,
        )
    else:
        rendered = "\n".join(f"- {failure}" for failure in result.failures)
        if len(rendered) > 1000:
            rendered = rendered[:997] + "..."
        embed.add_field(
            name=f"Failures ({result.failure_count})",
            value=rendered or "The operation did not complete.",
            inline=False,
        )
        embed.add_field(
            name="Kept For Recovery",
            value="You and the cleanup bot remain so the failed removals can be corrected and retried.",
            inline=False,
        )
    return embed


def build_destroy_summary(
    guild_name: str,
    reset_result: ServerResetResult,
    member_result: MemberRemovalResult | None,
) -> discord.Embed:
    complete = reset_result.failure_count == 0 and (
        member_result is not None and member_result.failure_count == 0
    )
    embed = make_embed(
        "Server Destruction Finished" if complete else "Server Destruction Incomplete",
        (
            f"> **{guild_name}** was stripped as far as Discord's API allows."
            if complete
            else f"> **{guild_name}** was only partially stripped. Recovery access was recreated where possible."
        ),
        kind="success" if complete else "warning",
        scope=SCOPE_SYSTEM,
    )
    reset_lines = [
        f"**{name}:** {section.deleted}/{section.attempted} removed"
        for name, section in reset_result.sections.items()
    ]
    embed.add_field(
        name="Server Content",
        value="\n".join(reset_lines) or "No removable content was found.",
        inline=False,
    )
    if member_result is not None:
        embed.add_field(
            name="Members",
            value=(
                f"**{member_result.kicked}/{member_result.attempted}** targeted members removed. "
                "Discord's legal owner remains."
            ),
            inline=False,
        )
    failures = [
        failure
        for section in reset_result.sections.values()
        for failure in section.failures
    ]
    if member_result is not None:
        failures.extend(member_result.failures)
    if failures:
        rendered = "\n".join(f"- {failure}" for failure in failures)
        if len(rendered) > 1000:
            rendered = rendered[:997] + "..."
        embed.add_field(name=f"Failures ({len(failures)})", value=rendered, inline=False)
    embed.add_field(
        name="Discord Cannot Erase",
        value=(
            "The server ID, creation record, audit log, `@everyone`, and legal owner are controlled by Discord. "
            "No bot can delete those."
        ),
        inline=False,
    )
    if complete:
        embed.add_field(
            name="Cleanup Bot",
            value="The cleanup bot is leaving now, so its managed role disappears too.",
            inline=False,
        )
    return embed


async def _send_dm(user: discord.abc.User, embed: discord.Embed) -> None:
    try:
        await user.send(embed=embed)
    except discord.HTTPException:
        logger.warning("Could not send destructive-operation DM to %s", user.id)


async def _create_recovery_access(
    guild: discord.Guild,
    requester_id: int,
    *,
    reason: str,
    summary: discord.Embed,
) -> None:
    try:
        requester = guild.get_member(requester_id) or await guild.fetch_member(requester_id)
        role = await guild.create_role(
            name="Destruction Recovery Admin",
            permissions=discord.Permissions(administrator=True),
            reason=reason,
        )
        await requester.add_roles(role, reason=reason)
        overwrites = {
            guild.default_role: discord.PermissionOverwrite(view_channel=False),
            requester: discord.PermissionOverwrite(view_channel=True, send_messages=True),
            guild.me: discord.PermissionOverwrite(view_channel=True, send_messages=True),
        }
        channel = await guild.create_text_channel(
            "destruction-recovery",
            overwrites=overwrites,
            reason=reason,
        )
        await channel.send(embed=summary)
    except Exception as exc:
        logger.warning("Could not create server-destruction recovery access in %s: %s", guild.id, exc)


class KickAllMembersModal(discord.ui.Modal):
    def __init__(self, *, guild_id: int, guild_name: str, requester_id: int) -> None:
        super().__init__(title="Confirm Kick Every Member", timeout=180)
        self.guild_id = guild_id
        self.guild_name = guild_name
        self.requester_id = requester_id
        phrase = kick_all_confirmation_phrase(guild_id)
        self.confirmation = discord.ui.TextInput(
            label="Type the exact confirmation phrase",
            placeholder=phrase,
            min_length=len(phrase),
            max_length=len(phrase),
        )
        self.add_item(self.confirmation)

    async def on_submit(self, interaction: discord.Interaction) -> None:
        guild = interaction.guild
        if (
            guild is None
            or guild.id != self.guild_id
            or interaction.user.id != self.requester_id
            or not can_reset_server(interaction)
        ):
            await interaction.response.send_message(
                embed=make_embed(
                    "Access Denied",
                    "> Only the initiating Discord administrator can confirm this action.",
                    kind="danger",
                    scope=SCOPE_SYSTEM,
                    guild=guild,
                ),
                ephemeral=True,
            )
            return
        required = kick_all_confirmation_phrase(guild.id)
        if str(self.confirmation.value).strip() != required:
            await interaction.response.send_message(
                embed=make_embed(
                    "Member Removal Cancelled",
                    f"> The phrase did not match. Nobody was kicked.\n> Required: `{required}`",
                    kind="warning",
                    scope=SCOPE_SYSTEM,
                    guild=guild,
                ),
                ephemeral=True,
            )
            return
        if guild.id in ACTIVE_SERVER_RESETS:
            await interaction.response.send_message(
                embed=make_embed(
                    "Destructive Action Already Running",
                    "> Another destructive operation is already running in this server.",
                    kind="warning",
                    scope=SCOPE_SYSTEM,
                    guild=guild,
                ),
                ephemeral=True,
            )
            return

        ACTIVE_SERVER_RESETS.add(guild.id)
        await interaction.response.defer(ephemeral=True)
        try:
            try:
                members = await _fetch_all_members(guild)
            except discord.HTTPException as exc:
                await interaction.edit_original_response(
                    embed=make_embed(
                        "Preflight Failed",
                        f"> I could not retrieve every member. Nobody was kicked.\n> `{exc}`",
                        kind="danger",
                        scope=SCOPE_SYSTEM,
                        guild=guild,
                    )
                )
                return
            blockers = _member_hierarchy_blockers(guild, members)
            if blockers:
                await interaction.edit_original_response(
                    embed=make_embed(
                        "Move My Role Higher First",
                        "> Nobody was kicked.\n\n" + _hierarchy_blocker_text(guild, members=blockers),
                        kind="danger",
                        scope=SCOPE_SYSTEM,
                        guild=guild,
                    )
                )
                return

            await interaction.edit_original_response(
                embed=make_embed(
                    "Member Removal In Progress",
                    "> Every other removable member and bot is being kicked. You will remain in the server.",
                    kind="danger",
                    scope=SCOPE_SYSTEM,
                    guild=guild,
                )
            )
            await _send_dm(
                interaction.user,
                make_embed(
                    "Member Removal Started",
                    f"> Every other removable member of **{guild.name}** is being kicked now. You will remain.",
                    kind="danger",
                    scope=SCOPE_SYSTEM,
                    guild=guild,
                ),
            )
            reason = f"Kick-all requested by administrator {interaction.user} ({interaction.user.id})"
            result = await perform_kick_all_members(
                guild,
                members,
                requester_id=interaction.user.id,
                reason=reason,
                keep_requester=True,
            )
            complete = result.failure_count == 0
            summary = build_member_removal_summary(self.guild_name, result, complete=complete)
            await _send_dm(interaction.user, summary)
            try:
                await interaction.edit_original_response(embed=summary)
            except discord.HTTPException:
                pass
            if complete:
                try:
                    await guild.leave()
                except discord.HTTPException as exc:
                    logger.warning("Cleanup bot could not leave guild %s: %s", guild.id, exc)
        finally:
            ACTIVE_SERVER_RESETS.discard(guild.id)


class KickAllMembersView(discord.ui.View):
    def __init__(self, *, guild_id: int, guild_name: str, requester_id: int) -> None:
        super().__init__(timeout=120)
        self.guild_id = guild_id
        self.guild_name = guild_name
        self.requester_id = requester_id

    @discord.ui.button(label="Continue to Typed Confirmation", style=discord.ButtonStyle.danger)
    async def continue_kick(self, interaction: discord.Interaction, button: discord.ui.Button) -> None:
        if (
            interaction.guild is None
            or interaction.guild.id != self.guild_id
            or interaction.user.id != self.requester_id
            or not can_reset_server(interaction)
        ):
            await interaction.response.send_message(
                embed=make_embed(
                    "Access Denied",
                    "> Only the initiating Discord administrator can use this control.",
                    kind="danger",
                    scope=SCOPE_SYSTEM,
                    guild=interaction.guild,
                ),
                ephemeral=True,
            )
            return
        await interaction.response.send_modal(
            KickAllMembersModal(
                guild_id=self.guild_id,
                guild_name=self.guild_name,
                requester_id=self.requester_id,
            )
        )

    @discord.ui.button(label="Cancel", style=discord.ButtonStyle.secondary)
    async def cancel_kick(self, interaction: discord.Interaction, button: discord.ui.Button) -> None:
        await interaction.response.edit_message(
            embed=make_embed(
                "Member Removal Cancelled",
                "> Nobody was kicked.",
                kind="muted",
                scope=SCOPE_SYSTEM,
                guild=interaction.guild,
            ),
            view=None,
        )
        self.stop()


class DestroyServerModal(discord.ui.Modal):
    def __init__(self, *, guild_id: int, guild_name: str, requester_id: int) -> None:
        super().__init__(title="Confirm Server Destruction", timeout=180)
        self.guild_id = guild_id
        self.guild_name = guild_name
        self.requester_id = requester_id
        phrase = destroy_server_confirmation_phrase(guild_id)
        self.confirmation = discord.ui.TextInput(
            label="Type the exact confirmation phrase",
            placeholder=phrase,
            min_length=len(phrase),
            max_length=len(phrase),
        )
        self.add_item(self.confirmation)

    async def on_submit(self, interaction: discord.Interaction) -> None:
        guild = interaction.guild
        if (
            guild is None
            or guild.id != self.guild_id
            or interaction.user.id != self.requester_id
            or not can_reset_server(interaction)
        ):
            await interaction.response.send_message(
                embed=make_embed(
                    "Access Denied",
                    "> Only the initiating Discord administrator can confirm this destruction.",
                    kind="danger",
                    scope=SCOPE_SYSTEM,
                    guild=guild,
                ),
                ephemeral=True,
            )
            return
        required = destroy_server_confirmation_phrase(guild.id)
        if str(self.confirmation.value).strip() != required:
            await interaction.response.send_message(
                embed=make_embed(
                    "Destruction Cancelled",
                    f"> The phrase did not match. Nothing was deleted.\n> Required: `{required}`",
                    kind="warning",
                    scope=SCOPE_SYSTEM,
                    guild=guild,
                ),
                ephemeral=True,
            )
            return
        if guild.id in ACTIVE_SERVER_RESETS:
            await interaction.response.send_message(
                embed=make_embed(
                    "Destructive Action Already Running",
                    "> Another destructive operation is already running in this server.",
                    kind="warning",
                    scope=SCOPE_SYSTEM,
                    guild=guild,
                ),
                ephemeral=True,
            )
            return

        ACTIVE_SERVER_RESETS.add(guild.id)
        await interaction.response.defer(ephemeral=True)
        try:
            try:
                roles = await guild.fetch_roles()
                members = await _fetch_all_members(guild)
            except discord.HTTPException as exc:
                await interaction.edit_original_response(
                    embed=make_embed(
                        "Preflight Failed",
                        f"> I could not inspect every role and member. Nothing was deleted.\n> `{exc}`",
                        kind="danger",
                        scope=SCOPE_SYSTEM,
                        guild=guild,
                    )
                )
                return
            role_blockers = _role_hierarchy_blockers(guild, roles)
            member_blockers = _member_hierarchy_blockers(guild, members)
            if role_blockers or member_blockers:
                await interaction.edit_original_response(
                    embed=make_embed(
                        "Move My Role Higher First",
                        "> Nothing was deleted or kicked.\n\n" + _hierarchy_blocker_text(
                            guild,
                            roles=role_blockers,
                            members=member_blockers,
                        ),
                        kind="danger",
                        scope=SCOPE_SYSTEM,
                        guild=guild,
                    )
                )
                return

            await interaction.edit_original_response(
                embed=make_embed(
                    "Server Destruction In Progress",
                    "> Content and bans are being erased first, then every removable member will be kicked and the bot will leave.",
                    kind="danger",
                    scope=SCOPE_SYSTEM,
                    guild=guild,
                )
            )
            await _send_dm(
                interaction.user,
                make_embed(
                    "Server Destruction Started",
                    f"> **{guild.name}** (`{guild.id}`) is now being stripped. Final results will arrive here.",
                    kind="danger",
                    scope=SCOPE_SYSTEM,
                    guild=guild,
                ),
            )
            reason = f"Full server destruction requested by administrator {interaction.user} ({interaction.user.id})"
            current_channel = interaction.channel
            final_channel_ids = {
                int(channel_id)
                for channel_id in (
                    getattr(current_channel, "id", None),
                    getattr(current_channel, "parent_id", None),
                )
                if channel_id is not None
            }
            reset_result = await perform_server_reset(
                guild,
                reason=reason,
                final_channel_ids=final_channel_ids,
            )
            member_result = None
            if reset_result.failure_count == 0:
                await _scrub_guild_identity(
                    guild,
                    reset_result,
                    reason=reason,
                )
            if reset_result.failure_count == 0:
                await _delete_other_integrations(
                    guild,
                    reset_result,
                    current_bot_id=guild.me.id,
                    reason=reason,
                )
            if reset_result.failure_count == 0:
                try:
                    members = await _fetch_all_members(guild)
                except discord.HTTPException as exc:
                    reset_result.section("Members").failures.append(
                        f"Could not refresh members: {type(exc).__name__}: {exc}"
                    )
            if reset_result.failure_count == 0:
                member_result = await perform_kick_all_members(
                    guild,
                    members,
                    requester_id=interaction.user.id,
                    reason=reason,
                )
            summary = build_destroy_summary(self.guild_name, reset_result, member_result)
            await _send_dm(interaction.user, summary)
            complete = (
                reset_result.failure_count == 0
                and member_result is not None
                and member_result.failure_count == 0
            )
            if complete:
                try:
                    await guild.leave()
                except discord.HTTPException as exc:
                    logger.warning("Cleanup bot could not leave destroyed guild %s: %s", guild.id, exc)
            else:
                await _create_recovery_access(
                    guild,
                    interaction.user.id,
                    reason=reason,
                    summary=summary,
                )
        finally:
            ACTIVE_SERVER_RESETS.discard(guild.id)


class DestroyServerView(discord.ui.View):
    def __init__(self, *, guild_id: int, guild_name: str, requester_id: int) -> None:
        super().__init__(timeout=120)
        self.guild_id = guild_id
        self.guild_name = guild_name
        self.requester_id = requester_id

    @discord.ui.button(label="Continue to Typed Confirmation", style=discord.ButtonStyle.danger)
    async def continue_destroy(self, interaction: discord.Interaction, button: discord.ui.Button) -> None:
        if (
            interaction.guild is None
            or interaction.guild.id != self.guild_id
            or interaction.user.id != self.requester_id
            or not can_reset_server(interaction)
        ):
            await interaction.response.send_message(
                embed=make_embed(
                    "Access Denied",
                    "> Only the initiating Discord administrator can use this control.",
                    kind="danger",
                    scope=SCOPE_SYSTEM,
                    guild=interaction.guild,
                ),
                ephemeral=True,
            )
            return
        await interaction.response.send_modal(
            DestroyServerModal(
                guild_id=self.guild_id,
                guild_name=self.guild_name,
                requester_id=self.requester_id,
            )
        )

    @discord.ui.button(label="Cancel", style=discord.ButtonStyle.secondary)
    async def cancel_destroy(self, interaction: discord.Interaction, button: discord.ui.Button) -> None:
        await interaction.response.edit_message(
            embed=make_embed(
                "Destruction Cancelled",
                "> Nothing was deleted or kicked.",
                kind="muted",
                scope=SCOPE_SYSTEM,
                guild=interaction.guild,
            ),
            view=None,
        )
        self.stop()


@tree.command(
    name=RESET_COMMAND_NAME,
    description="Permanently clear server content and bans while preserving members.",
)
async def reset_server(interaction: discord.Interaction) -> None:
    guild = interaction.guild
    if guild is None or not can_reset_server(interaction):
        await interaction.response.send_message(
            embed=make_embed(
                "Access Denied",
                "> Only a Discord administrator can run this command.",
                kind="danger",
                scope=SCOPE_SYSTEM,
                guild=guild,
            ),
            ephemeral=True,
        )
        return

    deletable_roles = sum(
        1
        for role in guild.roles
        if not role.is_default()
        and not role.managed
        and guild.me is not None
        and role.position < guild.me.top_role.position
    )
    embed = make_embed(
        "Reset This Entire Server?",
        "> This permanently removes the server structure and content. It cannot be undone.",
        kind="danger",
        scope=SCOPE_SYSTEM,
        guild=guild,
    )
    embed.add_field(
        name="Will Delete",
        value=(
            f"**{len(guild.channels)} channels** and **{deletable_roles} deletable roles**\n"
            "Messages, emojis, stickers, soundboard sounds, webhooks, invites, templates, "
            "scheduled events, AutoMod rules, the complete ban list, and server profile artwork/settings"
        ),
        inline=False,
    )
    embed.add_field(
        name="Will Preserve",
        value="Every current member, every bot member, and the legal server owner.",
        inline=False,
    )
    embed.add_field(
        name="Discord Will Preserve",
        value="`@everyone` plus bot, integration, subscription, and other managed roles that Discord forbids deleting.",
        inline=False,
    )
    embed.add_field(
        name="Before Continuing",
        value=(
            "Move the **Mysterious Bot X** role above every ordinary role first. "
            "The live preflight will refuse without deleting anything if the hierarchy is not sufficient.\n\n"
            f"The next screen requires: `{server_reset_confirmation_phrase(guild.id)}`"
        ),
        inline=False,
    )
    await interaction.response.send_message(
        embed=embed,
        view=ServerResetConfirmView(
            guild_id=guild.id,
            guild_name=guild.name,
            requester_id=interaction.user.id,
        ),
        ephemeral=True,
    )


@tree.command(
    name=KICK_ALL_COMMAND_NAME,
    description="Kick every other removable member and bot, then remove the cleanup bot.",
)
async def kick_all_members(interaction: discord.Interaction) -> None:
    guild = interaction.guild
    if guild is None or not can_reset_server(interaction):
        await interaction.response.send_message(
            embed=make_embed(
                "Access Denied",
                "> Only a Discord administrator can run this command.",
                kind="danger",
                scope=SCOPE_SYSTEM,
                guild=guild,
            ),
            ephemeral=True,
        )
        return
    preserved_ids = {
        guild.owner_id,
        interaction.user.id,
        getattr(guild.me, "id", None),
    }
    target_count = sum(member.id not in preserved_ids for member in guild.members)
    embed = make_embed(
        "Kick Everyone Else?",
        "> This permanently removes every other member and bot Discord allows the cleanup bot to kick.",
        kind="danger",
        scope=SCOPE_SYSTEM,
        guild=guild,
    )
    embed.add_field(
        name="Targets",
        value=(
            f"Approximately **{target_count} cached members**. The initiating administrator is always preserved. "
            "The command fetches the complete live member list before starting."
        ),
        inline=False,
    )
    embed.add_field(
        name="Discord Will Preserve",
        value=(
            "You and the legal server owner remain. The cleanup bot leaves by itself after every other removal succeeds."
        ),
        inline=False,
    )
    embed.add_field(
        name="Before Continuing",
        value=(
            "Move the **Mysterious Bot X** role above every member's highest role first. "
            "The command refuses before kicking anybody if it finds a hierarchy blocker.\n\n"
            f"The next screen requires: `{kick_all_confirmation_phrase(guild.id)}`"
        ),
        inline=False,
    )
    await interaction.response.send_message(
        embed=embed,
        view=KickAllMembersView(
            guild_id=guild.id,
            guild_name=guild.name,
            requester_id=interaction.user.id,
        ),
        ephemeral=True,
    )


@tree.command(
    name=DESTROY_COMMAND_NAME,
    description="Erase server content and bans, kick members, then remove the bot.",
)
async def destroy_server(interaction: discord.Interaction) -> None:
    guild = interaction.guild
    if guild is None or not can_reset_server(interaction):
        await interaction.response.send_message(
            embed=make_embed(
                "Access Denied",
                "> Only a Discord administrator can run this command.",
                kind="danger",
                scope=SCOPE_SYSTEM,
                guild=guild,
            ),
            ephemeral=True,
        )
        return
    embed = make_embed(
        "Destroy This Server's Contents?",
        "> This is the closest Discord permits a bot to come to deleting a server. It cannot be undone.",
        kind="danger",
        scope=SCOPE_SYSTEM,
        guild=guild,
    )
    embed.add_field(
        name="Will Erase",
        value=(
            "Every removable channel, message, role, ban, emoji, sticker, sound, webhook, invite, template, "
            "scheduled event, AutoMod rule, non-cleanup-bot integration, onboarding/welcome configuration, "
            "and resettable profile/server setting. The empty shell is renamed **Deleted Server**."
        ),
        inline=False,
    )
    embed.add_field(
        name="Will Remove",
        value="Every kickable member and bot, including the initiating administrator. The cleanup bot leaves last.",
        inline=False,
    )
    embed.add_field(
        name="Discord Will Preserve",
        value=(
            "The legal owner, server ID, creation record, audit log, `@everyone`, and any Discord-managed state "
            "whose backing account/service cannot be removed. No bot can erase those."
        ),
        inline=False,
    )
    embed.add_field(
        name="Before Continuing",
        value=(
            "Move the **Mysterious Bot X** role above every ordinary role and every member's highest role. "
            "A live preflight refuses before changing anything if the bot cannot finish.\n\n"
            f"The next screen requires: `{destroy_server_confirmation_phrase(guild.id)}`"
        ),
        inline=False,
    )
    await interaction.response.send_message(
        embed=embed,
        view=DestroyServerView(
            guild_id=guild.id,
            guild_name=guild.name,
            requester_id=interaction.user.id,
        ),
        ephemeral=True,
    )


async def setup(bot) -> None:
    bot.tree.add_command(reset_server)
    bot.tree.add_command(kick_all_members)
    bot.tree.add_command(destroy_server)
