"""The routing panel behind `/mgxadmin logs`.

One screen that shows every log stream and where it currently goes, and changes any
of them in two clicks: pick the topic, pick the channel. Muting and clearing are
buttons rather than another menu, because "send this nowhere" and "put this back on
whatever it inherits" are different answers and a channel picker cannot express
either.

The table itself lives in :mod:`minecraft_bot.logroutes`, which knows nothing about
Discord. This module only draws it.
"""

from __future__ import annotations

from typing import Optional

import discord

from . import logroutes
from .presentation import branded_send, info_embed
from .sentinel import CONFIG_ALERT_ROLE


def routing_embed(
    settings, *, selected: Optional[str] = None, alert_role_id: int = 0
) -> discord.Embed:
    """Every stream and its destination, grouped the way the panel lists them."""
    embed = info_embed(
        "Minecraft Log Routing",
        "> Choose a stream, then the channel it is written to. Routine lines arrive as "
        "compact digests; important and security lines are always sent on their own.",
    )
    for group in logroutes.GROUPS:
        lines = []
        for topic in logroutes.TOPICS:
            if topic.group != group:
                continue
            marker = "**›** " if topic.key == selected else ""
            lines.append(f"{marker}{topic.label} — {logroutes.destination_label(settings, topic.key)}")
        if lines:
            embed.add_field(name=group, value="\n".join(lines)[:1024], inline=False)
    embed.add_field(
        name="Security alerts",
        value=(
            f"High and critical incidents ping <@&{alert_role_id}>."
            if alert_role_id
            else "No alert role. Critical incidents ping the server owner; high ones post silently."
        ),
        inline=False,
    )
    if selected:
        topic = logroutes.BY_KEY[selected]
        embed.add_field(name=f"Selected: {topic.label}", value=topic.description, inline=False)
    else:
        embed.add_field(
            name="How routes work",
            value=(
                "A stream with no channel of its own follows the log it used to share, then the "
                "Commands log. Muted streams are still kept in `/mgxstaff commandlog`. "
                "Security cannot be muted."
            ),
            inline=False,
        )
    return embed


class _TopicSelect(discord.ui.Select):
    def __init__(self, view_state: "LogRoutingView") -> None:
        options = [
            discord.SelectOption(
                label=topic.label,
                value=topic.key,
                description=topic.description[:100],
                default=topic.key == view_state.selected,
            )
            for topic in logroutes.TOPICS
        ]
        super().__init__(
            placeholder="Choose a log stream",
            min_values=1,
            max_values=1,
            options=options,
            row=0,
        )

    async def callback(self, interaction: discord.Interaction) -> None:
        view: LogRoutingView = self.view  # type: ignore[assignment]
        view.selected = self.values[0]
        await view.redraw(interaction)


class _DestinationSelect(discord.ui.ChannelSelect):
    def __init__(self, view_state: "LogRoutingView") -> None:
        label = logroutes.BY_KEY[view_state.selected].label if view_state.selected else ""
        super().__init__(
            placeholder=(
                f"Send {label} to…" if label else "Choose a stream above first"
            ),
            min_values=1,
            max_values=1,
            channel_types=[discord.ChannelType.text],
            disabled=view_state.selected is None,
            row=1,
        )

    async def callback(self, interaction: discord.Interaction) -> None:
        view: LogRoutingView = self.view  # type: ignore[assignment]
        if view.selected is None:
            await interaction.response.defer()
            return
        await view.apply(interaction, self.values[0].id)


class _MuteButton(discord.ui.Button):
    def __init__(self, view_state: "LogRoutingView") -> None:
        super().__init__(
            label="Mute this stream",
            style=discord.ButtonStyle.danger,
            disabled=view_state.selected is None or view_state.selected in logroutes.UNMUTABLE,
            row=2,
        )

    async def callback(self, interaction: discord.Interaction) -> None:
        view: LogRoutingView = self.view  # type: ignore[assignment]
        await view.apply(interaction, logroutes.MUTED)


class _ClearButton(discord.ui.Button):
    def __init__(self, view_state: "LogRoutingView") -> None:
        super().__init__(
            label="Back to inherited",
            style=discord.ButtonStyle.secondary,
            disabled=view_state.selected is None,
            row=2,
        )

    async def callback(self, interaction: discord.Interaction) -> None:
        view: LogRoutingView = self.view  # type: ignore[assignment]
        await view.apply(interaction, None)


class _AlertRoleSelect(discord.ui.RoleSelect):
    def __init__(self) -> None:
        super().__init__(
            placeholder="Role to ping for high and critical security incidents",
            min_values=0,
            max_values=1,
            row=3,
        )

    async def callback(self, interaction: discord.Interaction) -> None:
        view: LogRoutingView = self.view  # type: ignore[assignment]
        if not await view.owned_by(interaction):
            return
        await interaction.response.defer()
        role_id = int(self.values[0].id) if self.values else 0
        await view.bot.data.set_configs(
            {CONFIG_ALERT_ROLE: role_id or None}, actor_id=interaction.user.id
        )
        view.alert_role_id = role_id
        await view.redraw(interaction, responded=True)


class _ResetButton(discord.ui.Button):
    def __init__(self) -> None:
        super().__init__(label="Clear every route", style=discord.ButtonStyle.secondary, row=2)

    async def callback(self, interaction: discord.Interaction) -> None:
        view: LogRoutingView = self.view  # type: ignore[assignment]
        if not await view.owned_by(interaction):
            return
        await interaction.response.defer()
        await view.bot.update_settings(actor_id=interaction.user.id, log_routes={})
        await view.redraw(interaction, responded=True)


class LogRoutingView(discord.ui.View):
    """The panel. Ephemeral and single-user, so it carries no persistent custom ids."""

    def __init__(
        self, bot, requester_id: int, selected: Optional[str] = None, *, alert_role_id: int = 0
    ) -> None:
        super().__init__(timeout=600)
        self.bot = bot
        self.requester_id = int(requester_id)
        self.selected = selected
        self.alert_role_id = int(alert_role_id or 0)
        self._build()

    def _build(self) -> None:
        self.clear_items()
        self.add_item(_TopicSelect(self))
        self.add_item(_DestinationSelect(self))
        self.add_item(_MuteButton(self))
        self.add_item(_ClearButton(self))
        self.add_item(_ResetButton())
        self.add_item(_AlertRoleSelect())

    async def owned_by(self, interaction: discord.Interaction) -> bool:
        if interaction.user.id == self.requester_id:
            return True
        await interaction.response.send_message(
            **branded_send(
                info_embed(
                    "Not Your Panel",
                    "> Run `/mgxadmin logs` to open your own.",
                    error=True,
                )
            ),
            ephemeral=True,
        )
        return False

    async def apply(self, interaction: discord.Interaction, channel_id: Optional[int]) -> None:
        if not await self.owned_by(interaction):
            return
        await interaction.response.defer()
        try:
            routes = logroutes.with_route(self.bot.settings, self.selected, channel_id)
            await self.bot.update_settings(actor_id=interaction.user.id, log_routes=routes)
        except ValueError as exc:
            await interaction.followup.send(
                **branded_send(info_embed("Route Not Changed", f"> {exc}", error=True)),
                ephemeral=True,
            )
            return
        await self.redraw(interaction, responded=True)

    async def redraw(self, interaction: discord.Interaction, *, responded: bool = False) -> None:
        if not responded:
            if not await self.owned_by(interaction):
                return
            await interaction.response.defer()
        self._build()
        await interaction.edit_original_response(
            embed=routing_embed(
                self.bot.settings, selected=self.selected, alert_role_id=self.alert_role_id
            ),
            view=self,
        )
