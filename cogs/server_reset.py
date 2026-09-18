"""Administrator-only workflow for returning a Discord guild to an empty shell."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Awaitable, Iterable

import discord

from core.constants import SCOPE_SYSTEM
from core.context import tree
from .shared import logger, make_embed


RESET_COMMAND_NAME = "reset-server"
ACTIVE_SERVER_RESETS: set[int] = set()


def server_reset_confirmation_phrase(guild_id: int) -> str:
    return f"DELETE EVERYTHING {guild_id}"


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


async def _delete_items(
    result: ServerResetResult,
    section_name: str,
    items: Iterable[object],
    *,
    reason: str,
    include_reason: bool = True,
) -> None:
    for item in items:
        delete = getattr(item, "delete")
        operation = delete(reason=reason) if include_reason else delete()
        await _record_operation(result, section_name, _item_label(item), operation)


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
            reason=reason,
        ),
    )


async def perform_server_reset(
    guild: discord.Guild,
    *,
    reason: str,
    final_channel_ids: Iterable[int] = (),
) -> ServerResetResult:
    """Delete removable guild content without touching members or the ban list."""
    result = ServerResetResult()
    final_ids = {int(channel_id) for channel_id in final_channel_ids}

    await _reset_guild_profile(guild, result, reason=reason)

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
        is_default = bool(getattr(role, "is_default")())
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
        f"> Finished clearing **{guild_name}**. Members and the ban list were not touched.",
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
                        f"> **{guild.name}** (`{guild.id}`) is being cleared now. Members and bans will remain.",
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


@tree.command(
    name=RESET_COMMAND_NAME,
    description="Permanently clear server content while preserving members and bans.",
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
            "scheduled events, AutoMod rules, and server profile artwork/settings"
        ),
        inline=False,
    )
    embed.add_field(
        name="Will Preserve",
        value="Every member, every bot member, the owner, and the complete ban list.",
        inline=False,
    )
    embed.add_field(
        name="Discord Will Preserve",
        value="`@everyone` plus bot, integration, subscription, and other managed roles that Discord forbids deleting.",
        inline=False,
    )
    embed.add_field(
        name="Final Confirmation",
        value=f"The next screen requires: `{server_reset_confirmation_phrase(guild.id)}`",
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


async def setup(bot) -> None:
    bot.tree.add_command(reset_server)
