"""Where each kind of Minecraft log is written.

The bot had two destinations — an activity channel and an important one — and five
settings that all pointed at the activity channel because `/mgxadmin log-channel`
wrote them together. That is fine while the server reports almost nothing. It stops
being fine the moment it reports what it actually does: kills, ore finds and crate
rewards landing in the same channel as verification results means nobody reads any
of them.

So routing is a table. Every topic resolves to a channel, an explicit route wins,
and a topic with no route falls back to the setting it used to live in and then to
the activity channel — which is what a server that has never configured anything
already expects, so nothing moves until somebody moves it.

Nothing here sends anything. :func:`resolve` answers "where does this go", and the
callers do the sending.

Self-contained by design, like the rest of `minecraft_bot`: stdlib only.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Iterable, Mapping, Optional


#: An explicit route to nowhere. Distinct from "no route", which inherits.
MUTED = 0


@dataclass(frozen=True)
class Topic:
    """One routable stream of logs."""

    key: str
    label: str
    description: str
    #: The settings field this topic lived in before routing existed. A topic with
    #: no route falls back to it, so upgrading changes nothing until somebody
    #: routes something.
    fallback: str = ""

    @property
    def choice_name(self) -> str:
        return self.label


#: Every stream that can be pointed at a channel.
#:
#: The first five are the streams the bot already had; the rest are what the server
#: itself now reports. Order is the order the panel and `/mgxadmin logs show` list
#: them in, so it runs from "what the bot did" to "what happened in the world".
TOPICS: tuple[Topic, ...] = (
    Topic(
        "important",
        "Important",
        "Denied or failed actions and access-changing staff commands.",
        "critical_log_channel_id",
    ),
    Topic(
        "command",
        "Commands",
        "Every /minecraft, /mgxstaff and /mgxadmin invocation.",
        "command_log_channel_id",
    ),
    Topic(
        "access",
        "Access",
        "Verification starts, access grants, revocations, and account changes.",
        "access_log_channel_id",
    ),
    Topic(
        "verification",
        "Verification",
        "Account link requests, approvals and refusals.",
        "verification_log_channel_id",
    ),
    Topic(
        "session",
        "Joins & Leaves",
        "Players connecting to and leaving the server.",
        "player_log_channel_id",
    ),
    Topic("chat", "In-game Chat", "Chat relayed out of Minecraft."),
    Topic("combat", "Combat", "Player kills, deaths and boss fights."),
    Topic("mining", "Mining", "Ores broken, with the rare finds called out."),
    Topic("crate", "Crates", "Openings, every reward won, and keys issued."),
    Topic("economy", "Economy", "Shop, sales, payments and auctions."),
    Topic("progression", "Progression", "Advancements and experience milestones."),
    Topic("cosmetic", "Cosmetics", "Cosmetics equipped, unequipped and stolen."),
    Topic("clan", "Clans", "Clan membership, ranks, treasury and upgrades."),
    Topic("world", "World", "Notable building, containers and destruction."),
    Topic("staff", "Staff", "In-game staff actions."),
    Topic("admin", "Admin", "Administrator commands run on the server."),
)

BY_KEY: Mapping[str, Topic] = {topic.key: topic for topic in TOPICS}

#: Where a topic nobody has routed ends up once its own fallback is unset too.
DEFAULT_FALLBACK = "command_log_channel_id"

#: Server-reported categories that do not name a topic of their own. Anything not
#: listed and not a topic key is treated as `world`, so a category added to the
#: plugin logs somewhere sensible before this table catches up with it.
CATEGORY_ALIASES: Mapping[str, str] = {
    # Saved routes from before verification-only access used this topic name.
    "application": "access",
    "server": "world",
    "lootbox": "crate",
    "pvp": "combat",
    "ore": "mining",
    "money": "economy",
    "shop": "economy",
    "wardrobe": "cosmetic",
}


def topic_for_category(category: str) -> str:
    """The topic a server-reported `category` belongs to."""
    key = str(category or "").strip().casefold()
    if key in BY_KEY:
        return key
    return CATEGORY_ALIASES.get(key, "world")


def normalize(raw: Any) -> dict[str, int]:
    """Reads a stored routing table back, dropping anything unrecognisable.

    Settings round-trip through SQLite as JSON, so this has to cope with a table
    written by an older build, a topic that has since been removed, and a channel
    id stored as text. A bad row is dropped rather than raising: a corrupt routing
    table must not stop the bot logging.
    """
    if not isinstance(raw, Mapping):
        return {}
    routes: dict[str, int] = {}
    for key, value in raw.items():
        topic = str(key).strip().casefold()
        topic = CATEGORY_ALIASES.get(topic, topic)
        if topic not in BY_KEY:
            continue
        try:
            channel_id = int(value)
        except (TypeError, ValueError):
            continue
        if channel_id < 0:
            continue
        routes[topic] = channel_id
    return routes


def routes_of(settings: Any) -> dict[str, int]:
    return normalize(getattr(settings, "log_routes", None))


def _legacy(settings: Any, topic_key: str) -> int:
    topic = BY_KEY.get(topic_key)
    if topic is None or not topic.fallback:
        return 0
    try:
        return int(getattr(settings, topic.fallback, 0) or 0)
    except (TypeError, ValueError):
        return 0


def resolve(settings: Any, topic: str, *, important: bool = False) -> int:
    """The channel one log line goes to, or 0 for nowhere.

    An `important` line tries the important route first. Muting that route does not
    silence the line — it puts it back with the rest of its topic, which is where it
    would have gone before anybody separated the two. Access-changing events should
    not be droppable by a routing decision that reads like a tidying-up.
    """
    key = topic_for_category(topic)
    routes = routes_of(settings)
    if important:
        chosen = routes.get("important", _legacy(settings, "important"))
        if chosen:
            return chosen
    if key in routes:
        # An explicit 0 here is a deliberate mute, so it is returned as one.
        return routes[key]
    inherited = _legacy(settings, key)
    if inherited:
        return inherited
    try:
        return int(getattr(settings, DEFAULT_FALLBACK, 0) or 0)
    except (TypeError, ValueError):
        return 0


def destination_label(settings: Any, topic: str) -> str:
    """How one row of the routing table reads in Discord."""
    key = topic_for_category(topic)
    routes = routes_of(settings)
    if key in routes:
        channel_id = routes[key]
        return f"<#{channel_id}>" if channel_id else "Muted"
    channel_id = resolve(settings, key)
    if not channel_id:
        return "Not set"
    return f"<#{channel_id}> (inherited)"


def with_route(settings: Any, topic: str, channel_id: Optional[int]) -> dict[str, int]:
    """The routing table that results from pointing one topic somewhere.

    `channel_id` of None clears the override and puts the topic back on whatever it
    inherits; 0 mutes it outright.
    """
    key = str(topic).strip().casefold()
    if key not in BY_KEY:
        raise ValueError(f"Unknown log topic: {topic}")
    routes = routes_of(settings)
    if channel_id is None:
        routes.pop(key, None)
        return routes
    value = int(channel_id)
    if value < 0:
        raise ValueError("A log route must be a channel or 0 to mute it")
    routes[key] = value
    return routes


def summary_rows(settings: Any, topics: Iterable[Topic] = TOPICS) -> list[str]:
    return [
        f"**{topic.label}** — {destination_label(settings, topic.key)}"
        for topic in topics
    ]
