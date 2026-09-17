"""The Discord rank roles exposed to the Minecraft bridge."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Iterable, Optional


# The server's only two ranks. Everything else — the level milestones, the boost perks
# and the LEGEND/OG/SUPPORTER/PARTNER ladder — belonged to the Discord that was deleted,
# and went with it.
#
# Owner sits above Developer in Discord, so it is the tag that shows. Control runs the
# other way: Developer is the role with every permission, and Owner is deliberately kept
# off anything destructive. See AdminCommandService in minecraft-bridge.
RANK_ROLES = (
    (1550144602554634320, "owner", "OWNER", 0x4FA8DC),
    (1550144558296334366, "developer", "DEVELOPER", 0x53E5FF),
)
OWNER_ROLE_ID = next(role_id for role_id, group, *_rest in RANK_ROLES if group == "owner")
DEVELOPER_ROLE_ID = next(role_id for role_id, group, *_rest in RANK_ROLES if group == "developer")
RANK_GROUPS = tuple(group for _role_id, group, _label, _colour in RANK_ROLES)


@dataclass(frozen=True)
class MinecraftRank:
    group: str
    label: str
    colour: int
    weight: int = 0


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
