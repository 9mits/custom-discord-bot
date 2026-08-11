"""/export — bulk message export to downloadable HTML transcripts.

Staff pick member(s) and/or channel(s), run an export, and the bot collects
those messages, renders them with the shared modmail-style HTML transcript
renderer, and stores the file in the database. Past exports can be
re-downloaded from the same menu.
"""

import asyncio
import io
from datetime import datetime, timezone
from types import SimpleNamespace
from typing import List, Optional, Set

import discord
from discord import app_commands
from discord.ext import commands

from core.constants import SCOPE_MODERATION
from core.context import bot, tree
from core.heavy_jobs import (
    HeavyJob,
    HeavyJobKind,
    HeavyJobOverloaded,
    HeavyJobPriority,
    HeavyJobStopped,
    HeavyJobTimedOut,
)
from core.utils import truncate_text
from .shared import (
    extract_snowflake_id,
    has_permission_capability,
    make_embed,
    make_empty_state_embed,
    resolve_channel_input,
    respond_with_error,
)
from .case_panel import generate_transcript_html

# Bounds so a single export can't run away on a busy server. A whole-channel
# export is capped at EXPORT_MESSAGE_CAP newest messages; a user-only sweep scans
# every channel but only PER_CHANNEL_USER_SCAN deep each.
EXPORT_MESSAGE_CAP = 5000
PER_CHANNEL_USER_SCAN = 1000
SCAN_CONCURRENCY = 6


def _staff_check(interaction: discord.Interaction) -> bool:
    return has_permission_capability(interaction, "messages.export")


# ----------------- Collection + rendering -----------------

async def _scan_channel(channel, me, user_ids: Set[int], per_channel_limit: int, progress: Optional[dict] = None):
    if not channel.permissions_for(me).read_message_history:
        return []
    found = []
    try:
        async for message in channel.history(limit=per_channel_limit):
            if user_ids and message.author.id not in user_ids:
                continue
            found.append(message)
            if progress is not None:
                progress["messages"] += 1
    except (discord.Forbidden, discord.HTTPException):
        return []
    return found


async def collect_export_messages(
    guild,
    user_ids: Set[int],
    channel_ids: Set[int],
    progress: Optional[dict] = None,
    *,
    job_queue=None,
) -> List[discord.Message]:
    """Gather messages matching the selected members and/or channels, newest
    first, capped at EXPORT_MESSAGE_CAP. Channels are scanned concurrently. When a
    `progress` dict is passed, it is updated live with counts for the loading bar."""
    if channel_ids:
        channels = [guild.get_channel(cid) for cid in channel_ids]
        channels = [c for c in channels if c is not None]
        per_channel_limit = EXPORT_MESSAGE_CAP
    else:
        channels = list(guild.text_channels)
        per_channel_limit = PER_CHANNEL_USER_SCAN

    if progress is not None:
        progress["total"] = len(channels)

    async def _guarded(channel):
        found = await _scan_channel(channel, guild.me, user_ids, per_channel_limit, progress)
        if progress is not None:
            progress["done"] += 1
        return found

    if job_queue is not None:
        results = await job_queue.map_scans(channels, _guarded)
    else:
        semaphore = asyncio.Semaphore(SCAN_CONCURRENCY)

        async def _local_guarded(channel):
            async with semaphore:
                return await _guarded(channel)

        results = await asyncio.gather(
            *(_local_guarded(channel) for channel in channels),
            return_exceptions=True,
        )

    messages: List[discord.Message] = []
    for result in results:
        if isinstance(result, list):
            messages.extend(result)
    messages.sort(key=lambda m: m.created_at, reverse=True)
    return messages[:EXPORT_MESSAGE_CAP]


def _progress_bar(done: int, total: int, width: int = 14) -> str:
    total = total or 1
    filled = max(0, min(width, round(width * done / total)))
    return "▰" * filled + "▱" * (width - filled)


def _loading_embed(guild, progress: dict) -> discord.Embed:
    total = progress.get("total", 0)
    done = progress.get("done", 0)
    found = progress.get("messages", 0)
    if total > 1:
        body = (
            "> Collecting messages, please wait…\n\n"
            f"`{_progress_bar(done, total)}`  {done}/{total} channels\n"
            f"Messages found: **{found}**"
        )
    else:
        body = f"> Collecting messages, please wait…\n\nMessages found: **{found}**"
    return make_embed("Exporting…", body, kind="info", scope=SCOPE_MODERATION, guild=guild)


def build_export_bytes(messages: List[discord.Message], title: str) -> bytes:
    """Render messages (newest-first) into a modmail-style HTML transcript."""
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
    title_user = SimpleNamespace(display_name=title, id=f"{len(messages)} messages")
    return generate_transcript_html(records, title_user).encode("utf-8")


# ----------------- Menu view -----------------

class ExportMemberSelect(discord.ui.UserSelect):
    def __init__(self):
        super().__init__(placeholder="Filter by member(s) — optional", min_values=0, max_values=25, row=0)

    async def callback(self, interaction: discord.Interaction) -> None:
        view: "ExportMenuView" = self.view
        view.selected_user_ids = {u.id for u in self.values}
        await interaction.response.edit_message(embed=view.build_embed(), view=view)


class ExportChannelSelect(discord.ui.ChannelSelect):
    def __init__(self):
        super().__init__(
            placeholder="Filter by channel(s) — optional",
            channel_types=[discord.ChannelType.text],
            min_values=0,
            max_values=25,
            row=1,
        )

    async def callback(self, interaction: discord.Interaction) -> None:
        view: "ExportMenuView" = self.view
        view.selected_channel_ids = {c.id for c in self.values}
        await interaction.response.edit_message(embed=view.build_embed(), view=view)


class ExportRunButton(discord.ui.Button):
    def __init__(self):
        super().__init__(label="Run Export", style=discord.ButtonStyle.success, row=2)

    async def callback(self, interaction: discord.Interaction) -> None:
        view: "ExportMenuView" = self.view
        if not view.selected_user_ids and not view.selected_channel_ids:
            await respond_with_error(interaction, "Select at least one member or channel first.", scope=SCOPE_MODERATION)
            return

        await interaction.response.defer()

        job_queue = getattr(bot, "heavy_jobs", None)
        if job_queue is None:
            await interaction.edit_original_response(
                embed=make_embed(
                    "Export Unavailable",
                    "> The background work queue is not ready yet. Try again shortly.",
                    kind="warning",
                    scope=SCOPE_MODERATION,
                    guild=interaction.guild,
                ),
                view=view,
            )
            return

        # Drive a live loading bar while channels are scanned: the collector
        # updates `progress` in place and a ticker re-renders it every 2s.
        progress = {"messages": 0, "done": 0, "total": 0}
        stop = asyncio.Event()

        async def ticker():
            while not stop.is_set():
                try:
                    await interaction.edit_original_response(embed=_loading_embed(interaction.guild, progress), view=None)
                except Exception:
                    pass
                try:
                    await asyncio.wait_for(stop.wait(), timeout=2.0)
                except asyncio.TimeoutError:
                    pass

        job = HeavyJob(
            kind=HeavyJobKind.EXPORT,
            priority=HeavyJobPriority.EXPORT,
            guild_id=interaction.guild.id,
            operation=lambda: collect_export_messages(
                interaction.guild,
                view.selected_user_ids,
                view.selected_channel_ids,
                progress,
                job_queue=job_queue,
            ),
        )
        try:
            job_future = job_queue.enqueue(job)
        except (HeavyJobOverloaded, HeavyJobStopped):
            await interaction.edit_original_response(
                embed=make_embed(
                    "System Busy",
                    "> This server already has a history-heavy task running, or the queue is full. Try again shortly.",
                    kind="warning",
                    scope=SCOPE_MODERATION,
                    guild=interaction.guild,
                ),
                view=view,
            )
            return

        ticker_task = asyncio.create_task(ticker())
        failure_embed = None
        try:
            messages = await job_future
        except HeavyJobTimedOut:
            messages = None
            failure_embed = make_embed(
                "Export Timed Out",
                "> The export exceeded three minutes and was stopped. Narrow the member or channel selection and try again.",
                kind="warning",
                scope=SCOPE_MODERATION,
                guild=interaction.guild,
            )
        except Exception:
            messages = None
            failure_embed = make_embed(
                "Export Failed",
                "> The export could not be completed. Try again shortly.",
                kind="error",
                scope=SCOPE_MODERATION,
                guild=interaction.guild,
            )
        finally:
            stop.set()
            await ticker_task

        if failure_embed is not None:
            await interaction.edit_original_response(embed=failure_embed, view=view)
            return

        if not messages:
            await interaction.edit_original_response(embed=view.build_embed(), view=view)
            await interaction.followup.send(
                embed=make_empty_state_embed(
                    "Nothing to Export",
                    "> No messages matched that selection (or I can't read those channels).",
                    scope=SCOPE_MODERATION,
                    guild=interaction.guild,
                ),
                ephemeral=True,
            )
            return

        title = view.export_title()
        data = build_export_bytes(messages, title)
        filename = f"export_{datetime.now(timezone.utc).strftime('%Y%m%d_%H%M%S')}.html"
        export_id = await bot.data_manager.save_export(
            requester_id=interaction.user.id,
            title=title,
            filename=filename,
            message_count=len(messages),
            content=data,
        )

        await view.reload_exports()
        view.sync_download_options()
        await interaction.edit_original_response(embed=view.build_embed(), view=view)
        await interaction.followup.send(
            content=f"Export #{export_id} ready — **{len(messages)}** messages.",
            file=discord.File(io.BytesIO(data), filename=filename),
            ephemeral=True,
        )


class ExportDownloadSelect(discord.ui.Select):
    def __init__(self):
        super().__init__(placeholder="Download a past export...", min_values=1, max_values=1, row=3, options=[discord.SelectOption(label="No exports yet", value="none")])

    async def callback(self, interaction: discord.Interaction) -> None:
        if self.values[0] == "none":
            await interaction.response.defer()
            return
        export_id = int(self.values[0])
        record = await bot.data_manager.get_export(export_id)
        if not record:
            await respond_with_error(interaction, "That export could not be found — it may have been pruned.", scope=SCOPE_MODERATION)
            return
        await interaction.response.send_message(
            content=f"Export #{export_id} — **{record['message_count']}** messages.",
            file=discord.File(io.BytesIO(record["content"]), filename=record["filename"]),
            ephemeral=True,
        )


class ExportMenuView(discord.ui.View):
    def __init__(
        self,
        requester_id: int,
        guild: discord.Guild,
        *,
        user_ids: Optional[Set[int]] = None,
        channel_ids: Optional[Set[int]] = None,
    ):
        super().__init__(timeout=600)
        self.requester_id = requester_id
        self.guild = guild
        self.selected_user_ids: Set[int] = set(user_ids or ())
        self.selected_channel_ids: Set[int] = set(channel_ids or ())
        self.exports: List[dict] = []
        self.download_select = ExportDownloadSelect()
        self.add_item(ExportMemberSelect())
        self.add_item(ExportChannelSelect())
        self.add_item(ExportRunButton())
        self.add_item(self.download_select)

    async def interaction_check(self, interaction: discord.Interaction) -> bool:
        if interaction.user.id == self.requester_id:
            return True
        await respond_with_error(interaction, "This export menu belongs to another staff member.", scope=SCOPE_MODERATION)
        return False

    async def reload_exports(self) -> None:
        self.exports = await bot.data_manager.list_exports()

    def sync_download_options(self) -> None:
        options = []
        for record in self.exports[:25]:
            created = str(record.get("created_at", ""))[:10]
            options.append(discord.SelectOption(
                label=f"#{record['export_id']} • {truncate_text(record['title'], 70)}",
                description=f"{record['message_count']} messages • {created}",
                value=str(record["export_id"]),
            ))
        if not options:
            options = [discord.SelectOption(label="No exports yet", value="none")]
        self.download_select.options = options

    def export_title(self) -> str:
        members = f"{len(self.selected_user_ids)} member(s)" if self.selected_user_ids else "all members"
        channels = f"{len(self.selected_channel_ids)} channel(s)" if self.selected_channel_ids else "all channels"
        return f"Export • {members} in {channels}"

    def build_embed(self) -> discord.Embed:
        embed = make_embed(
            "Message Export",
            "> Choose member(s) and/or channel(s) below, then **Run Export**.",
            kind="info",
            scope=SCOPE_MODERATION,
            guild=self.guild,
        )
        members = ", ".join(f"<@{uid}>" for uid in self.selected_user_ids) if self.selected_user_ids else "Any member"
        channels = ", ".join(f"<#{cid}>" for cid in self.selected_channel_ids) if self.selected_channel_ids else "All text channels"
        embed.add_field(name="Members", value=truncate_text(members, 1024), inline=True)
        embed.add_field(name="Channels", value=truncate_text(channels, 1024), inline=True)
        return embed


@tree.command(name="export", description="Export a member's or channel's messages to a downloadable HTML file.")
@app_commands.describe(
    userid="A user ID or mention to preselect as the member filter.",
    channelid="A text channel ID or mention to preselect as the channel filter.",
)
@app_commands.check(_staff_check)
async def export(
    interaction: discord.Interaction,
    userid: Optional[str] = None,
    channelid: Optional[str] = None,
):
    user_ids: Set[int] = set()
    channel_ids: Set[int] = set()

    if userid is not None:
        user_id = extract_snowflake_id(userid)
        if user_id is None:
            await respond_with_error(interaction, "That isn't a valid user ID or mention.", scope=SCOPE_MODERATION)
            return
        user_ids.add(user_id)

    if channelid is not None:
        if extract_snowflake_id(channelid) is None:
            await respond_with_error(interaction, "That isn't a valid channel ID or mention.", scope=SCOPE_MODERATION)
            return
        channel = await resolve_channel_input(interaction.guild, channelid)
        if channel is None:
            await respond_with_error(interaction, "No channel in this server was found with that ID.", scope=SCOPE_MODERATION)
            return
        if not isinstance(channel, discord.TextChannel):
            await respond_with_error(interaction, "Message exports require a text channel.", scope=SCOPE_MODERATION)
            return
        channel_ids.add(channel.id)

    view = ExportMenuView(
        interaction.user.id,
        interaction.guild,
        user_ids=user_ids,
        channel_ids=channel_ids,
    )
    await view.reload_exports()
    view.sync_download_options()
    await interaction.response.send_message(embed=view.build_embed(), view=view, ephemeral=True)


class ExportCog(commands.Cog):
    def __init__(self, bot):
        self.bot = bot


async def setup(bot):
    await bot.add_cog(ExportCog(bot))
    bot.tree.add_command(export)
