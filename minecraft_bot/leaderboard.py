"""The permanent Minecraft leaderboard message and its head emojis."""

from __future__ import annotations

import logging
import re
from datetime import datetime, timedelta, timezone
from typing import Any, Iterable, Optional

import aiohttp
import discord

from .presentation import (
    BRAND_NAME,
    FOOTER_ICON_URL,
    MARK_ATTACHMENT_URI,
    MINECRAFT_HEAD_URL,
    brand_mark_file,
)

logger = logging.getLogger(__name__)

BRAND_COLOUR = discord.Color.from_rgb(255, 153, 0)
PODIUM = 3
#: Five rows keeps the pair of boards readable in one message.
DISPLAY_ROWS = 5
#: Head emojis are a reward, so they outlive a single bad week on the board.
EMOJI_RETENTION_DAYS = 14
#: Leaves the guild's remaining emoji slots for everything else.
EMOJI_BUDGET = 20
EMOJI_PREFIX = "mgx_head_"

#: The board everyone sees by default; the rest are a dropdown away.
DEFAULT_TYPE = "wealth"

TYPE_LABELS: dict[str, str] = {
    "wealth": "Richest",
    "kills": "Most Kills",
    "playtime": "Time Played",
    "blocks_mined": "Blocks Mined",
    "blocks_walked": "Distance Walked",
}
#: Mirrors LeaderboardType.clanEligible on the Paper side.
CLAN_TYPES = ("wealth", "kills", "playtime")

CONFIG_CHANNEL = "leaderboard_channel_id"
CONFIG_MESSAGE = "leaderboard_message_id"
CONFIG_EMOJIS = "leaderboard_head_emojis"


def _rows(snapshot: dict[str, Any], scope: str, board: str) -> list[dict[str, Any]]:
    section = snapshot.get(scope) or {}
    rows = section.get(board) or []
    return rows if isinstance(rows, list) else []


def _emoji_name(username: str) -> str:
    """Discord only accepts word characters, and caps emoji names at 32."""
    cleaned = re.sub(r"\W", "", username)[:20] or "player"
    return f"{EMOJI_PREFIX}{cleaned}"


class HeadEmojiStore:
    """Creates a head emoji when a player reaches the podium, and reaps stale ones.

    Emojis are only created when the podium actually changes, never on every refresh,
    which is what keeps this clear of Discord's emoji rate limits.
    """

    def __init__(self, bot: Any) -> None:
        self.bot = bot

    async def _load(self) -> dict[str, dict[str, Any]]:
        stored = await self.bot.data.get_config(CONFIG_EMOJIS, default={})
        return stored if isinstance(stored, dict) else {}

    async def _save(self, registry: dict[str, dict[str, Any]]) -> None:
        await self.bot.data.set_config(CONFIG_EMOJIS, registry)

    async def sync(self, guild: discord.Guild, snapshot: dict[str, Any]) -> dict[str, str]:
        """Ensures every current podium player has an emoji; returns uuid -> markdown."""
        registry = await self._load()
        now = datetime.now(timezone.utc)
        podium = self._podium_players(snapshot)

        missing = {
            uuid: username
            for uuid, username in podium.items()
            if not (
                (entry := registry.get(uuid))
                and guild.get_emoji(int(entry.get("emoji_id", 0) or 0)) is not None
            )
        }
        for uuid in podium:
            if uuid not in missing:
                registry[uuid]["last_podium"] = now.isoformat()
        # Ask Discord for nothing when the guild has no room: the alternative is a
        # guaranteed 400 for every podium player, every refresh, forever.
        free_slots = guild.emoji_limit - len(guild.emojis)
        if missing and free_slots <= 0:
            logger.info(
                "Guild %s is at its emoji limit (%s); skipping %s podium head(s).",
                guild.id,
                guild.emoji_limit,
                len(missing),
            )
            missing = {}
        elif len(missing) > free_slots:
            # Take the ones we can fit rather than failing the whole batch.
            missing = dict(list(missing.items())[:free_slots])
        if missing:
            # One session for the whole podium; opening one per head was wasteful.
            async with aiohttp.ClientSession(
                timeout=aiohttp.ClientTimeout(total=15)
            ) as session:
                for uuid, username in missing.items():
                    created = await self._create(session, guild, uuid, username)
                    if created is not None:
                        registry[uuid] = {
                            "emoji_id": created.id,
                            "markdown": str(created),
                            "last_podium": now.isoformat(),
                        }

        registry = await self._reap(guild, registry, now, keep=set(podium))
        await self._save(registry)
        return {uuid: entry["markdown"] for uuid, entry in registry.items() if entry.get("markdown")}

    def _podium_players(self, snapshot: dict[str, Any]) -> dict[str, str]:
        players: dict[str, str] = {}
        for board in TYPE_LABELS:
            for row in _rows(snapshot, "individual", board)[:PODIUM]:
                uuid = str(row.get("minecraft_uuid") or "")
                if uuid:
                    players.setdefault(uuid, str(row.get("username") or "player"))
        return players

    async def _create(
        self,
        session: aiohttp.ClientSession,
        guild: discord.Guild,
        uuid: str,
        username: str,
    ) -> Optional[discord.Emoji]:
        try:
            url = MINECRAFT_HEAD_URL.format(identifier=uuid)
            async with session.get(url) as response:
                if response.status != 200:
                    logger.warning("Head image for %s returned HTTP %s", username, response.status)
                    return None
                image = await response.read()
            return await guild.create_custom_emoji(
                name=_emoji_name(username),
                image=image,
                reason=f"{BRAND_NAME} leaderboard podium",
            )
        except discord.HTTPException as error:
            # A missing head is cosmetic; the row still renders with its placement.
            # Expected refusals are logged flat, without a traceback each refresh.
            if error.code == 30008:
                logger.info("No emoji slots left for %s's head.", username)
            else:
                logger.warning("Could not create a podium emoji for %s: %s", username, error)
            return None
        except (aiohttp.ClientError, OSError) as error:
            logger.warning("Could not fetch %s's head image: %s", username, error)
            return None

    async def _reap(
        self,
        guild: discord.Guild,
        registry: dict[str, dict[str, Any]],
        now: datetime,
        *,
        keep: set[str],
    ) -> dict[str, dict[str, Any]]:
        cutoff = now - timedelta(days=EMOJI_RETENTION_DAYS)

        def last_podium(entry: dict[str, Any]) -> datetime:
            try:
                return datetime.fromisoformat(str(entry.get("last_podium")))
            except (TypeError, ValueError):
                return cutoff

        expired = [
            uuid
            for uuid, entry in registry.items()
            if uuid not in keep and last_podium(entry) < cutoff
        ]
        # Budget eviction: oldest podium appearance goes first.
        surplus = len(registry) - len(expired) - EMOJI_BUDGET
        if surplus > 0:
            candidates = sorted(
                (uuid for uuid in registry if uuid not in keep and uuid not in expired),
                key=lambda uuid: last_podium(registry[uuid]),
            )
            expired.extend(candidates[:surplus])

        for uuid in expired:
            entry = registry.pop(uuid, {})
            emoji = guild.get_emoji(int(entry.get("emoji_id", 0) or 0))
            if emoji is not None:
                try:
                    await emoji.delete(reason=f"{BRAND_NAME} leaderboard podium expired")
                except discord.HTTPException:
                    logger.exception("Could not remove a stale podium emoji")
        return registry


def _placement(index: int) -> str:
    return f"#{index + 1}"


def build_embed(
    snapshot: dict[str, Any],
    *,
    scope: str,
    board: str,
    heads: Optional[dict[str, str]] = None,
    linked: Optional[dict[str, str]] = None,
) -> discord.Embed:
    """Renders one board. Scope is ``individual`` or ``clan``."""
    heads = heads or {}
    linked = linked or {}
    label = TYPE_LABELS.get(board, board.replace("_", " ").title())
    scope_label = "Clans" if scope == "clan" else "Players"

    embed = discord.Embed(
        title=f"{label} — {scope_label}",
        colour=BRAND_COLOUR,
    )
    rows = _rows(snapshot, scope, board)
    if not rows:
        embed.description = "No standings yet. Play a little and this fills in."
    else:
        lines = []
        for index, row in enumerate(rows[:DISPLAY_ROWS]):
            podium = index < PODIUM
            value = str(row.get("display", row.get("value", 0)))
            if scope == "clan":
                name = str(row.get("clan") or "?")
                members = row.get("members")
                suffix = f" · {members} members" if members else ""
                icon = ""
            else:
                uuid = str(row.get("minecraft_uuid") or "")
                name = str(row.get("username", "?"))
                clan = row.get("clan")
                suffix = f" · [{clan}]" if clan else ""
                # Mentions render without pinging inside an embed description.
                discord_id = linked.get(uuid)
                if discord_id:
                    suffix = f" · <@{discord_id}>{suffix}"
                icon = heads.get(uuid, "") if podium else ""
            # The podium is bold and carries the head; the rest stay quiet beneath it.
            if podium:
                body = f"**{_placement(index)} · {name}** — `{value}`{suffix}"
            else:
                body = f"{_placement(index)} · {name} — {value}{suffix}"
            lines.append(f"{icon} {body}".strip())
        embed.description = "\n".join(lines)

    generated = snapshot.get("generated_at")
    if generated:
        embed.timestamp = datetime.fromtimestamp(int(generated) / 1000, tz=timezone.utc)
    embed.set_thumbnail(url=MARK_ATTACHMENT_URI)
    embed.set_footer(text=BRAND_NAME, icon_url=FOOTER_ICON_URL)
    return embed


def boards_for(scope: str) -> Iterable[str]:
    return CLAN_TYPES if scope == "clan" else tuple(TYPE_LABELS)


class BoardSelect(discord.ui.DynamicItem[discord.ui.Select], template=r"mgx_board:(?P<scope>\w+)"):
    """Persistent so the dropdown keeps working after a bot restart.

    Choosing a board replies privately rather than editing the shared message, so one
    player browsing cannot change what everyone else is looking at.
    """

    def __init__(self, scope: str, *, item: Optional[discord.ui.Select] = None) -> None:
        self.scope = scope
        super().__init__(
            item
            or discord.ui.Select(
                custom_id=f"mgx_board:{scope}",
                placeholder=f"View another {'clan' if scope == 'clan' else 'player'} board…",
                options=[
                    discord.SelectOption(label=TYPE_LABELS[board], value=board)
                    for board in boards_for(scope)
                ],
            )
        )

    @classmethod
    async def from_custom_id(cls, interaction, item, match):  # type: ignore[override]
        return cls(match["scope"], item=item)

    async def callback(self, interaction: discord.Interaction) -> None:
        board = self.item.values[0]
        bot = interaction.client
        snapshot = getattr(getattr(bot, "bridge", None), "latest_leaderboard", {}) or {}
        # Reuse the links the refresh loop already resolved rather than hitting the
        # database again for every dropdown interaction.
        linked = getattr(bot, "leaderboard_links", {}) or {}
        await interaction.response.send_message(
            embed=build_embed(snapshot, scope=self.scope, board=board, linked=linked),
            ephemeral=True,
        )


class LeaderboardView(discord.ui.View):
    def __init__(self) -> None:
        super().__init__(timeout=None)
        self.add_item(BoardSelect("individual"))
        self.add_item(BoardSelect("clan"))


def message_payload(
    snapshot: dict[str, Any],
    heads: dict[str, str],
    linked: Optional[dict[str, str]] = None,
) -> dict[str, Any]:
    """The permanent message: both default boards side by side, plus the dropdowns."""
    return {
        "embeds": [
            build_embed(
                snapshot, scope="individual", board=DEFAULT_TYPE, heads=heads, linked=linked
            ),
            build_embed(snapshot, scope="clan", board=DEFAULT_TYPE),
        ],
        "attachments": [brand_mark_file()],
        "view": LeaderboardView(),
    }
