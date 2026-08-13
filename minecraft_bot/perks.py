"""Discord level milestones exposed to the Minecraft bridge."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Iterable


LEVEL_ROLE_MILESTONES = (
    (1476839722172158018, 5),
    (1476839722172158019, 10),
    (1476839722172158020, 20),
    (1476839722172158021, 30),
    (1476839722172158022, 40),
    (1476839722172158023, 50),
)
ELITE_ROLE_ID = LEVEL_ROLE_MILESTONES[-1][0]


@dataclass(frozen=True)
class MinecraftLevelProfile:
    level: int
    extra_hearts: int
    elite: bool


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
