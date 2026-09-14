"""The log explorer behind `/mgxstaff commandlog`.

The command log already keeps everything — every Discord command and panel action,
and every in-game action the server reports — but reading it meant one fixed list of
the newest twenty rows. The explorer pages through the whole trail and narrows it by
stream, by importance and by player, from dropdowns rather than retyped options.

Ephemeral and single-user, like the routing panel, so it carries no persistent ids.
"""

from __future__ import annotations

from typing import Any, Mapping, Optional

import discord

from . import logroutes
from .audit import server_event_title
from .presentation import BRAND_NAME, FOOTER_ICON_URL, branded_send, info_embed


PAGE_SIZE = 12

#: Streams that exist in the command log: Discord-side actions and the categories
#: the server reports. Access and verification lines are written by the bot only
#: to their channels and never enter this table, so offering them would always be empty.
_SERVER_TOPICS = ("security", "combat", "mining", "crate", "economy", "progression",
                  "cosmetic", "clan", "world", "staff", "admin")
STREAMS: Mapping[str, str] = {
    "all": "Everything",
    "discord": "Discord commands and panels",
    **{key: logroutes.BY_KEY[key].label for key in _SERVER_TOPICS},
}
VIEWS: Mapping[str, str] = {
    "all": "Every line",
    "important": "Important only",
    "failed": "Failed or denied",
}
_OUTCOMES = {"success": "OK", "failed": "FAILED", "denied": "DENIED"}


def filters_for(stream: str, view: str, *, actor_id: Optional[int] = None,
                command: Optional[str] = None, player: Optional[str] = None) -> dict[str, Any]:
    filters: dict[str, Any] = {
        "actor_id": actor_id,
        "command": command,
        "player": player,
        "important_only": view == "important",
        "failed_only": view == "failed",
    }
    if stream == "discord":
        filters["source"] = "discord"
    elif stream in _SERVER_TOPICS:
        filters["source"] = "server"
        filters["categories"] = logroutes.categories_for(stream)
    return filters


def _clip(text: Any, limit: int) -> str:
    value = " ".join(str(text or "").split())
    return value if len(value) <= limit else value[: limit - 3] + "..."


def row_line(row: Mapping[str, Any]) -> str:
    at = int(row.get("created_at") or 0)
    outcome = _OUTCOMES.get(str(row.get("outcome") or "").casefold(), str(row.get("outcome") or "").upper())
    if row.get("source") == "server":
        title = server_event_title(str(row.get("command") or "")).removeprefix("Minecraft ")
        summary = _clip(row.get("detail") or title, 110)
        mention = f" <@{row['actor_discord_id']}>" if str(row.get("actor_discord_id") or "0") != "0" else ""
        return f"<t:{at}:R> **{_clip(row.get('actor_label'), 24)}**{mention} {summary}"
    command = str(row.get("command") or "")
    action = f"/{command}" if row.get("source") == "command" else command
    line = f"<t:{at}:R> `{outcome}` **{_clip(action, 60)}** by <@{row.get('actor_discord_id')}>"
    if row.get("target_discord_id"):
        line += f" on <@{row['target_discord_id']}>"
    return line


async def explorer_embed(data: Any, *, stream: str, view: str, page: int,
                         actor_id: Optional[int] = None, command: Optional[str] = None,
                         player: Optional[str] = None) -> tuple[discord.Embed, int, int]:
    filters = filters_for(stream, view, actor_id=actor_id, command=command, player=player)
    total = await data.count_command_log(**filters)
    pages = max(1, -(-total // PAGE_SIZE))
    page = max(0, min(page, pages - 1))
    rows = await data.list_command_log(limit=PAGE_SIZE, offset=page * PAGE_SIZE, **filters)
    body = "\n".join(row_line(row) for row in rows) or "> Nothing matches these filters yet."
    embed = info_embed(f"Log Explorer · {STREAMS.get(stream, 'Everything')}", _clip_block(body))
    parts = [VIEWS.get(view, "Every line"), f"{total:,} lines", f"page {page + 1}/{pages}"]
    if actor_id:
        parts.append(f"member {actor_id}")
    if player:
        parts.append(f"player {player}")
    if command:
        parts.append(f"matching {command}")
    embed.set_footer(text=f"{BRAND_NAME} · {' · '.join(parts)}", icon_url=FOOTER_ICON_URL)
    return embed, pages, page


def _clip_block(text: str) -> str:
    return text if len(text) <= 4_000 else text[:3_997] + "..."


class _Select(discord.ui.Select):
    def __init__(self, explorer: "LogExplorerView", attribute: str, choices: Mapping[str, str], row: int) -> None:
        current = getattr(explorer, attribute)
        super().__init__(
            options=[discord.SelectOption(label=label, value=key, default=key == current)
                     for key, label in choices.items()],
            min_values=1, max_values=1, row=row,
        )
        self.attribute = attribute

    async def callback(self, interaction: discord.Interaction) -> None:
        explorer: LogExplorerView = self.view  # type: ignore[assignment]
        setattr(explorer, self.attribute, self.values[0])
        explorer.page = 0
        await explorer.redraw(interaction)


class _Step(discord.ui.Button):
    def __init__(self, label: str, step: Optional[int], *, disabled: bool = False) -> None:
        super().__init__(label=label, style=discord.ButtonStyle.secondary, disabled=disabled, row=2)
        self.step = step

    async def callback(self, interaction: discord.Interaction) -> None:
        explorer: LogExplorerView = self.view  # type: ignore[assignment]
        if self.step is None:
            await interaction.response.send_modal(_PlayerModal(explorer))
            return
        explorer.page += self.step
        await explorer.redraw(interaction)


class _PlayerModal(discord.ui.Modal, title="Filter by Minecraft player"):
    name = discord.ui.TextInput(label="Minecraft username (blank clears it)", required=False, max_length=32)

    def __init__(self, explorer: "LogExplorerView") -> None:
        super().__init__()
        self.explorer = explorer
        self.name.default = explorer.player or ""

    async def on_submit(self, interaction: discord.Interaction) -> None:
        self.explorer.player = str(self.name.value or "").strip() or None
        self.explorer.page = 0
        await self.explorer.redraw(interaction)


class LogExplorerView(discord.ui.View):
    def __init__(self, bot: Any, requester_id: int, *, actor_id: Optional[int] = None,
                 command: Optional[str] = None) -> None:
        super().__init__(timeout=900)
        self.bot = bot
        self.requester_id = int(requester_id)
        self.actor_id = actor_id
        self.command = command
        self.player: Optional[str] = None
        self.stream = "all"
        self.view_mode = "all"
        self.page = 0

    async def render(self) -> discord.Embed:
        embed, pages, self.page = await explorer_embed(
            self.bot.data, stream=self.stream, view=self.view_mode, page=self.page,
            actor_id=self.actor_id, command=self.command, player=self.player,
        )
        self.clear_items()
        self.add_item(_Select(self, "stream", STREAMS, 0))
        self.add_item(_Select(self, "view_mode", VIEWS, 1))
        self.add_item(_Step("Previous", -1, disabled=self.page <= 0))
        self.add_item(_Step("Next", 1, disabled=self.page >= pages - 1))
        self.add_item(_Step("Refresh", 0))
        self.add_item(_Step("Player filter", None))
        return embed

    async def interaction_check(self, interaction: discord.Interaction) -> bool:
        if interaction.user.id == self.requester_id:
            return True
        await interaction.response.send_message(
            **branded_send(info_embed("Not Your Explorer", "> Run `/mgxstaff commandlog` to open your own.", error=True)),
            ephemeral=True,
        )
        return False

    async def redraw(self, interaction: discord.Interaction) -> None:
        await interaction.response.defer()
        embed = await self.render()
        await interaction.edit_original_response(embed=embed, view=self)
