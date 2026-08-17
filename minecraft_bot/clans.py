"""The clan upgrade ladder, mirrored for Discord-side display.

Every figure here is enforced by ``ClanLevel.java`` in minecraft-bridge, which is
authoritative at runtime. This module exists so player-facing copy quotes a named
constant rather than a typed-in number; a test parses the Java and fails if the two
ever disagree.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Optional

MAX_PUBLIC_LEVEL = 5


@dataclass(frozen=True)
class ClanPerks:
    """Totals a clan holds at a level, not increments over the level below."""

    extra_hearts: int = 0
    strength: int = 0
    saturation: int = 0
    digging_speed: int = 0
    resistance: int = 0
    speed: int = 0

    def is_none(self) -> bool:
        return self == ClanPerks()

    def described(self) -> list[str]:
        """Each perk as a line, in the order the in-game panels list them."""
        lines: list[str] = []
        if self.extra_hearts:
            noun = "heart" if self.extra_hearts == 1 else "hearts"
            lines.append(f"+{self.extra_hearts} extra {noun}")
        for percent, label in (
            (self.strength, "strength"),
            (self.saturation, "saturation"),
            (self.digging_speed, "digging speed"),
            (self.resistance, "resistance"),
            (self.speed, "speed"),
        ):
            if percent:
                lines.append(f"+{percent}% {label}")
        return lines


#: Level to its cumulative perks.
PERKS: dict[int, ClanPerks] = {
    0: ClanPerks(),
    1: ClanPerks(strength=3, saturation=3),
    2: ClanPerks(strength=5, saturation=5, digging_speed=10),
    3: ClanPerks(1, 10, 10, 15, 5, 5),
    4: ClanPerks(2, 10, 15, 20, 10, 10),
    5: ClanPerks(3, 10, 15, 25, 15, 15),
}

#: Level to what buying it costs, in whole dollars.
COSTS: dict[int, int] = {
    1: 15_000,
    2: 150_000,
    3: 2_000_000,
    4: 40_000_000,
    5: 500_000_000,
}

#: One glyph the whole way up, recoloured in game rather than repeated — a growing
#: row of stars sits in front of every chat line. Discord embed text cannot carry
#: that colour, which is why :func:`tag` writes the level as a number instead.
BADGES: dict[int, str] = {
    0: "",
    1: "★",
    2: "★",
    3: "★",
    4: "★",
    5: "★",
}

STARTING_MEMBER_SLOTS = 3

#: Roster upgrades as (slots, dollars), one member at a time.
MEMBER_TIERS: tuple[tuple[int, int], ...] = (
    (4, 500),
    (5, 1_000),
    (6, 2_000),
    (7, 4_000),
    (8, 8_000),
    (9, 15_000),
    (10, 25_000),
    (11, 40_000),
    (12, 65_000),
    (13, 100_000),
    (14, 150_000),
    (15, 250_000),
    (16, 400_000),
    (17, 650_000),
    (18, 1_000_000),
    (19, 1_500_000),
    (20, 2_500_000),
    (21, 4_000_000),
    (22, 6_500_000),
    (23, 10_000_000),
    (24, 20_000_000),
    (25, 40_000_000),
)

MAX_MEMBER_SLOTS = MEMBER_TIERS[-1][0]

PUBLIC_LEVELS = tuple(range(1, MAX_PUBLIC_LEVEL + 1))


def badge(level: int) -> str:
    return BADGES.get(_clamp(level), "")


def perks_for(level: int) -> ClanPerks:
    return PERKS.get(_clamp(level), ClanPerks())


def cost_of(level: int) -> int:
    return COSTS.get(level, 0)


def described_cost(level: int) -> str:
    """The price of a level as one readable line."""
    amount = cost_of(level)
    return f"${amount:,}" if amount else ""


def visible_levels(current: int) -> tuple[int, ...]:
    return PUBLIC_LEVELS


def next_level(current: int) -> Optional[int]:
    """The level a clan would buy next, or None at the top of the ladder."""
    return None if current >= MAX_PUBLIC_LEVEL else current + 1


def describe(level: int) -> str:
    return "Unranked" if level <= 0 else f"Level {level}"


def tag(name: str, level: int) -> str:
    """A clan's name and level, as shown on Discord surfaces.

    In game the badge is one star recoloured per level. Embed text cannot carry that
    colour, so a colourless star here would say nothing — the level is written out
    instead, and the two surfaces deliberately read differently.
    """
    return f"[{name}] Lv{level}" if level > 0 else f"[{name}]"


def next_member_tier(slots: int) -> Optional[tuple[int, int]]:
    """The roster upgrade a clan at ``slots`` would buy next, or None at the top."""
    for tier in MEMBER_TIERS:
        if tier[0] > slots:
            return tier
    return None


def described_member_cost(tier: tuple[int, int]) -> str:
    _slots, amount = tier
    return f"${amount:,}"


def _clamp(level: int) -> int:
    return max(0, min(int(level), MAX_PUBLIC_LEVEL))
