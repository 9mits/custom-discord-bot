"""The clan upgrade ladder, mirrored for Discord-side display.

Every figure here is enforced by ``ClanLevel.java`` in minecraft-bridge, which is
authoritative at runtime. This module exists so player-facing copy quotes a named
constant rather than a typed-in number; a test parses the Java and fails if the two
ever disagree, the same guard the clan limits and perk figures already have.

The secret level is deliberately absent from :data:`PUBLIC_LEVELS`. Nothing that
enumerates levels for players may include it — see :func:`visible_levels`.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Optional

MAX_PUBLIC_LEVEL = 5
SECRET_LEVEL = 6


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
    6: ClanPerks(3, 10, 15, 25, 15, 15),
}

#: Level to what buying it costs, as (material, amount) in Minecraft's own names.
COSTS: dict[int, tuple[tuple[str, int], ...]] = {
    1: (("DIAMOND", 30),),
    2: (("DIAMOND_BLOCK", 30),),
    3: (("DIAMOND", 64), ("NETHERITE_INGOT", 10), ("NETHER_STAR", 1)),
    4: (("NETHERITE_BLOCK", 10),),
    5: (("NETHERITE_BLOCK", 64), ("NETHER_STAR", 3), ("ENCHANTED_GOLDEN_APPLE", 1)),
    6: (("DRAGON_EGG", 1),),
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
    6: "✦",
}

STARTING_MEMBER_SLOTS = 3

#: Roster upgrades as (slots, material, amount). Diamonds then netherite, each step
#: dearer than the last; the plugin enforces the same ladder.
MEMBER_TIERS: tuple[tuple[int, str, int], ...] = (
    (5, "DIAMOND", 32),
    (8, "DIAMOND_BLOCK", 8),
    (12, "NETHERITE_INGOT", 12),
    (16, "NETHERITE_INGOT", 18),
    (20, "NETHERITE_INGOT", 24),
    (25, "NETHERITE_INGOT", 30),
)

MAX_MEMBER_SLOTS = MEMBER_TIERS[-1][0]

#: Levels that may be named to any player. The secret level is not among them.
PUBLIC_LEVELS = tuple(range(1, MAX_PUBLIC_LEVEL + 1))


def badge(level: int) -> str:
    return BADGES.get(_clamp(level), "")


def perks_for(level: int) -> ClanPerks:
    return PERKS.get(_clamp(level), ClanPerks())


def cost_of(level: int) -> tuple[tuple[str, int], ...]:
    return COSTS.get(level, ())


def readable_material(material: str) -> str:
    """``DIAMOND_BLOCK`` as ``Diamond Block``, matching the in-game messages."""
    return " ".join(word.capitalize() for word in (material or "").split("_") if word)


def described_cost(level: int) -> str:
    """The price of a level as one readable line."""
    return ", ".join(
        f"{amount}x {readable_material(material)}" for material, amount in cost_of(level)
    )


def visible_levels(current: int) -> tuple[int, ...]:
    """Levels a clan at ``current`` may see named.

    The secret level appears only once a clan has bought everything else, which is
    the whole of its secrecy — no surface may enumerate levels any other way.
    """
    if current >= MAX_PUBLIC_LEVEL:
        return PUBLIC_LEVELS + (SECRET_LEVEL,)
    return PUBLIC_LEVELS


def next_level(current: int) -> Optional[int]:
    """The level a clan would buy next, or None at the top of the ladder."""
    return None if current >= SECRET_LEVEL else current + 1


def describe(level: int) -> str:
    return "Unranked" if level <= 0 else f"Level {level}"


def tag(name: str, level: int) -> str:
    """A clan's name and level, as shown on Discord surfaces.

    In game the badge is one star recoloured per level. Embed text cannot carry that
    colour, so a colourless star here would say nothing — the level is written out
    instead, and the two surfaces deliberately read differently.
    """
    return f"[{name}] Lv{level}" if level > 0 else f"[{name}]"


def next_member_tier(slots: int) -> Optional[tuple[int, str, int]]:
    """The roster upgrade a clan at ``slots`` would buy next, or None at the top."""
    for tier in MEMBER_TIERS:
        if tier[0] > slots:
            return tier
    return None


def described_member_cost(tier: tuple[int, str, int]) -> str:
    _slots, material, amount = tier
    return f"{amount}x {readable_material(material)}"


def _clamp(level: int) -> int:
    return max(0, min(int(level), SECRET_LEVEL))
