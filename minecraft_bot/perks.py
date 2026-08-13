"""Discord level milestones and rank roles exposed to the Minecraft bridge."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Iterable, Optional


LEVEL_ROLE_MILESTONES = (
    (1476839722172158018, 5),
    (1476839722172158019, 10),
    (1476839722172158020, 20),
    (1476839722172158021, 30),
    (1476839722172158022, 40),
    (1476839722172158023, 50),
)
ELITE_ROLE_ID = LEVEL_ROLE_MILESTONES[-1][0]


# Discord rank roles mapped to LuckPerms groups. Priority is NOT taken from this
# tuple: rank_for_role_ids() honours the order it is given, and the bot passes a
# member's roles sorted by Discord hierarchy, so dragging a role in Discord
# reorders ranks with no code change. Colours mirror the Discord role colours.
RANK_ROLES = (
    (1476839722247786593, "owner", "OWNER", 0x4FA8DC),
    (1476839722247786591, "admin", "ADMIN", 0xA33A32),
    (1476839722247786590, "community-manager", "MANAGER", 0xF0A868),
    (1476839722247786589, "staff", "STAFF", 0xA855F7),
    (1476839722230747137, "legend", "LEGEND", 0x99A9F0),
    (1484195104649515058, "og", "OG", 0x9BA5A8),
    (1484190796042207423, "supporter", "SUPPORTER", 0x45B6D4),
    (1476839722247786587, "partner", "PARTNER", 0xE8399E),
    (1476877246902960249, "booster", "BOOSTER", 0xFF73FA),
)
RANK_GROUPS = tuple(group for _role_id, group, _label, _colour in RANK_ROLES)


@dataclass(frozen=True)
class MinecraftLevelProfile:
    level: int
    extra_hearts: int
    elite: bool


@dataclass(frozen=True)
class MinecraftRank:
    group: str
    label: str
    colour: int


def profile_for_role_ids(role_ids: Iterable[int]) -> MinecraftLevelProfile:
    """Return the highest milestone and cumulative perks for Discord roles."""
    owned = {int(role_id) for role_id in role_ids}
    level = max(
        (milestone for role_id, milestone in LEVEL_ROLE_MILESTONES if role_id in owned),
        default=0,
    )
    return MinecraftLevelProfile(
        level=level,
        extra_hearts=sum(
            1
            for _role_id, milestone in LEVEL_ROLE_MILESTONES
            if milestone < 50 and milestone <= level
        ),
        elite=ELITE_ROLE_ID in owned,
    )


def rank_for_role_ids(role_ids: Iterable[int]) -> Optional[MinecraftRank]:
    """Return the rank for the first mapped role, or None when none are mapped.

    Priority comes from the caller's ordering, so pass roles highest-first to
    let Discord's own role hierarchy decide which rank wins.
    """
    ranks = {
        role_id: MinecraftRank(group=group, label=label, colour=colour)
        for role_id, group, label, colour in RANK_ROLES
    }
    for role_id in role_ids:
        try:
            rank = ranks.get(int(role_id))
        except (TypeError, ValueError):
            continue
        if rank is not None:
            return rank
    return None
