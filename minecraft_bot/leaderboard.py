"""The permanent Minecraft leaderboard message and its head emojis."""

from __future__ import annotations

import logging
import re
from datetime import datetime, timedelta, timezone
from typing import Any, Iterable, Optional

import aiohttp
import discord

from . import clans
from .presentation import (
    BRAND_NAME,
    FOOTER_ICON_URL,
    MARK_ICON_URL,
    head_url,
)

logger = logging.getLogger(__name__)

BRAND_COLOUR = discord.Color.from_rgb(255, 153, 0)
PODIUM = 3
#: Five rows keeps the pair of boards readable in one message.
DISPLAY_ROWS = 5
#: Head emojis are a reward, so they outlive a single bad week on the board.
EMOJI_RETENTION_DAYS = 14
#: Five individual boards and three clan boards, three places each, plus room for
#: the turnover that retention deliberately holds on to.
EMOJI_BUDGET = 32
EMOJI_PREFIX = "mgx_head_"
#: Deleting emojis is rate-limited, so a backlog is cleared over several refreshes.
EMOJI_CLEANUP_PER_PASS = 25

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
CONFIG_CLAN_CHANNEL = "leaderboard_clan_channel_id"
CONFIG_CLAN_MESSAGE = "leaderboard_clan_message_id"
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
        """Ensures every podium subject has an emoji; returns key -> markdown.

        Keys are a player uuid.
        """
        registry = await self._load()
        now = datetime.now(timezone.utc)
        podium = self._podium_subjects(snapshot)

        # Authoritative list rather than the gateway cache: a stale cache is what
        # caused the same head to be created over and over.
        try:
            existing = {emoji.id: emoji for emoji in await guild.fetch_emojis()}
        except discord.HTTPException:
            logger.warning("Could not list guild emojis; leaving the podium heads alone.")
            return {
                key: entry["markdown"]
                for key, entry in registry.items()
                if entry.get("markdown")
            }

        registry = await self._forget_orphans(guild, registry, existing)

        def is_current(key: str, source: str) -> bool:
            """Whether Discord still holds an emoji minted from this exact image.

            A Bedrock player who changes their gamertag keeps their uuid key, so
            the stored source has to be compared too or the old head would stay on
            the board forever. Entries saved before sources were recorded have
            none; those are left alone rather than re-minted, since the emoji
            itself is still right.
            """
            entry = registry.get(key) or {}
            emoji_id = int(entry.get("emoji_id", 0) or 0)
            if not emoji_id or emoji_id not in existing:
                return False
            stored = str(entry.get("source") or "")
            return not stored or stored == source

        missing = {
            key: subject
            for key, subject in podium.items()
            if not is_current(key, subject[1])
        }
        for key in podium:
            if key not in missing:
                registry[key]["last_podium"] = now.isoformat()
        # Ask Discord for nothing when the guild has no room: the alternative is a
        # guaranteed 400 for every podium player, every refresh, forever.
        free_slots = guild.emoji_limit - len(existing)
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
                for key, (label, source) in missing.items():
                    # A changed source (a Bedrock gamertag change) retires the old
                    # emoji before the new one is minted.
                    await self._retire(registry.get(key), existing)
                    created = await self._create(session, guild, label, source)
                    if created is not None:
                        registry[key] = {
                            "emoji_id": created.id,
                            "markdown": str(created),
                            "source": source,
                            "last_podium": now.isoformat(),
                        }

        registry = await self._reap(guild, registry, now, keep=set(podium), existing=existing)
        await self._save(registry)
        return {key: entry["markdown"] for key, entry in registry.items() if entry.get("markdown")}

    def _podium_subjects(self, snapshot: dict[str, Any]) -> dict[str, tuple[str, str]]:
        """Everything that earns an emoji: key -> (label, image address)."""
        return {
            uuid: (username, head_url(uuid, username))
            for uuid, username in self._podium_players(snapshot).items()
        }

    async def _retire(
        self, entry: Optional[dict[str, Any]], existing: dict[int, discord.Emoji]
    ) -> None:
        emoji = existing.get(int((entry or {}).get("emoji_id", 0) or 0))
        if emoji is None:
            return
        try:
            await emoji.delete(reason=f"{BRAND_NAME} leaderboard head replaced")
            existing.pop(emoji.id, None)
        except discord.HTTPException:
            logger.warning("Could not remove the replaced emoji %s", emoji.name)

    def _podium_players(self, snapshot: dict[str, Any]) -> dict[str, str]:
        """Every individual board mints heads for its own top three.

        Restricting this to the default board left the other four showing bare rows,
        because a player topping Distance Walked usually is not also the richest.
        The ceiling is five boards times three places, and heavy overlap in practice
        keeps it well under that.
        """
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
        label: str,
        url: str,
    ) -> Optional[discord.Emoji]:
        try:
            async with session.get(url) as response:
                if response.status != 200:
                    logger.warning("Image for %s returned HTTP %s", label, response.status)
                    return None
                image = await response.read()
            return await guild.create_custom_emoji(
                name=_emoji_name(label),
                image=image,
                reason=f"{BRAND_NAME} leaderboard podium",
            )
        except discord.HTTPException as error:
            # A missing head is cosmetic; the row still renders with its placement.
            # Expected refusals are logged flat, without a traceback each refresh.
            if error.code == 30008:
                logger.info("No emoji slots left for %s.", label)
            else:
                logger.warning("Could not create a podium emoji for %s: %s", label, error)
            return None
        except (aiohttp.ClientError, OSError) as error:
            logger.warning("Could not fetch the image for %s: %s", label, error)
            return None

    async def _forget_orphans(
        self,
        guild: discord.Guild,
        registry: dict[str, dict[str, Any]],
        existing: dict[int, discord.Emoji],
    ) -> dict[str, dict[str, Any]]:
        """Deletes head emojis no registry entry owns.

        A stale gateway cache previously caused the same head to be recreated on every
        refresh, so a guild can be carrying hundreds of duplicates. Anything wearing the
        prefix that this registry does not claim is one of those, and is removed.
        """
        owned = {
            int(entry.get("emoji_id", 0) or 0)
            for entry in registry.values()
        }
        orphans = [
            emoji
            for emoji_id, emoji in existing.items()
            if emoji.name.startswith(EMOJI_PREFIX) and emoji_id not in owned
        ]
        for emoji in orphans[:EMOJI_CLEANUP_PER_PASS]:
            try:
                await emoji.delete(reason=f"{BRAND_NAME} duplicate podium head")
                existing.pop(emoji.id, None)
            except discord.HTTPException:
                logger.warning("Could not remove duplicate podium emoji %s", emoji.name)
        if orphans:
            logger.info(
                "Removed %s duplicate podium head(s); %s still to clear.",
                min(len(orphans), EMOJI_CLEANUP_PER_PASS),
                max(0, len(orphans) - EMOJI_CLEANUP_PER_PASS),
            )
        return registry

    async def _reap(
        self,
        guild: discord.Guild,
        registry: dict[str, dict[str, Any]],
        now: datetime,
        *,
        keep: set[str],
        existing: dict[int, discord.Emoji],
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
            emoji = existing.get(int(entry.get("emoji_id", 0) or 0))
            if emoji is not None:
                try:
                    await emoji.delete(reason=f"{BRAND_NAME} leaderboard podium expired")
                except discord.HTTPException:
                    logger.exception("Could not remove a stale podium emoji")
        return registry


def _placement(index: int) -> str:
    return f"#{index + 1}"


def _render_row(
    index: int,
    row: dict[str, Any],
    *,
    scope: str,
    heads: dict[str, str],
    linked: dict[str, str],
) -> str:
    """One ranked entry. Every place uses the same layout; only heads stay podium-only."""
    podium = index < PODIUM
    value = str(row.get("display", row.get("value", 0)))
    place = f"**{_placement(index)}**"
    if scope == "clan":
        clan_name = str(row.get("clan") or "?")
        name = clans.tag(clan_name, int(row.get("level", 0) or 0))
        members = row.get("members")
        detail = f"`{value}`"
        if members:
            detail += f"  ·  {members} members"
        return f"{place}  **{name}**\n{detail}"

    uuid = str(row.get("minecraft_uuid") or "")
    username = str(row.get("username", "?"))
    icon = heads.get(uuid, "") if podium else ""
    lead = "  ".join(part for part in (place, icon, f"**{username}**") if part)
    identity: list[str] = []
    clan = row.get("clan")
    if clan:
        identity.append(f"[{clan}]")
    discord_id = linked.get(uuid)
    if discord_id:
        identity.append(f"<@{discord_id}>")
    lines = [lead]
    if identity:
        lines.append("  ·  ".join(identity))
    lines.append(f"`{value}`")
    return "\n".join(lines)


def _thumbnail(rows: list[dict[str, Any]]) -> str:
    """Whoever tops *this* board, so each one is visibly about its own leader.

    Clan boards fall through to the brand mark: a clan row carries no
    ``minecraft_uuid``, and clans have no picture of their own to show instead.

    A remote URL rather than an attachment: the dropdown replies are ephemeral and
    cannot carry a file, so attachment:// rendered nothing on four boards in five.
    """
    if not rows:
        return MARK_ICON_URL
    leader = rows[0]
    uuid = str(leader.get("minecraft_uuid") or "")
    if not uuid:
        return MARK_ICON_URL
    return head_url(uuid, str(leader.get("username") or ""))


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
    # This board reads the clan's own balance, not what its members carry, so say
    # so — otherwise a clan full of rich players looks broken.
    note = (
        "What each clan has been donated and still holds.\n\n"
        if scope == "clan" and board == "wealth"
        else ""
    )
    rows = _rows(snapshot, scope, board)
    if not rows:
        embed.description = note + "No standings yet. Play a little and this fills in."
    else:
        blocks = [
            _render_row(index, row, scope=scope, heads=heads, linked=linked)
            for index, row in enumerate(rows[:DISPLAY_ROWS])
        ]
        embed.description = note + "\n\n".join(blocks)

    generated = snapshot.get("generated_at")
    if generated:
        embed.timestamp = datetime.fromtimestamp(int(generated) / 1000, tz=timezone.utc)
    embed.set_thumbnail(url=_thumbnail(rows))
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
        # Heads belong on every board, not just the two on the permanent message.
        heads = getattr(bot, "leaderboard_heads", {}) or {}
        await interaction.response.send_message(
            embed=build_embed(
                snapshot, scope=self.scope, board=board, heads=heads, linked=linked
            ),
            ephemeral=True,
        )


class LeaderboardView(discord.ui.View):
    def __init__(self, scope: str = "individual") -> None:
        super().__init__(timeout=None)
        self.add_item(BoardSelect(scope))


def message_payload(
    snapshot: dict[str, Any],
    heads: dict[str, str],
    linked: Optional[dict[str, str]] = None,
    *,
    scope: str = "individual",
) -> dict[str, Any]:
    """One permanent message: the default board for this scope, plus its dropdown."""
    return {
        "embeds": [
            build_embed(
                snapshot,
                scope=scope,
                board=DEFAULT_TYPE,
                heads=heads,
                linked=linked if scope == "individual" else None,
            ),
        ],
        "attachments": [],
        "view": LeaderboardView(scope),
    }


async def purge_head_emojis(guild: discord.Guild) -> tuple[int, int]:
    """Deletes every podium head emoji in the guild.

    Returns (removed, failed). discord.py backs off on rate limits by itself, so this
    can take a while on a large backlog — it is meant to be awaited from a command
    that has already deferred.
    """
    try:
        emojis = await guild.fetch_emojis()
    except discord.HTTPException:
        logger.exception("Could not list guild emojis for a head purge")
        return 0, 0

    removed = 0
    failed = 0
    for emoji in emojis:
        if not emoji.name.startswith(EMOJI_PREFIX):
            continue
        try:
            await emoji.delete(reason=f"{BRAND_NAME} podium head purge")
            removed += 1
        except discord.HTTPException:
            failed += 1
            logger.warning("Could not delete podium head %s", emoji.name)
    return removed, failed
