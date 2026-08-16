"""What a linked player may do in Minecraft, as reported by the server.

Permissions live in LuckPerms on the Paper side, so Discord cannot work them out on
its own. The server pushes a snapshot and this reads it, which is how a command panel
can offer someone exactly the tools they hold and nothing else.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Optional

#: Staff tools the server reports, with how they read in Discord. Order is the order
#: they are listed in, so it runs from investigation through to punishment.
STAFF_TOOL_LABELS: dict[str, str] = {
    "inspect": "Inspect blocks",
    "lookup": "Search block history",
    "rollback": "Roll back damage",
    "restore": "Restore a rollback",
    "alerts": "Anticheat alerts",
    "heal": "Heal a player",
    "god": "Invulnerability",
    "invsee": "View inventories",
    "vanish": "Vanish",
    "broadcast": "Broadcast",
    "gamemode": "Change game mode",
    "mute": "Mute",
    "kick": "Kick",
    "tempban": "Temporary ban",
    "ban": "Ban",
    "unban": "Lift a ban",
}

#: Staff tools that can run from Discord as a fire-and-forget console command, with
#: which arguments they need. Mirrors StaffTools.ALL in the plugin — the Java side is
#: authoritative and re-checks the permission itself; this only decides what to offer
#: and which fields to ask for before sending.
REMOTE_STAFF_TOOLS: dict[str, dict[str, bool]] = {
    "heal": {"needs_target": True, "needs_reason": False, "needs_duration": False},
    "kick": {"needs_target": True, "needs_reason": False, "needs_duration": False},
    "mute": {"needs_target": True, "needs_reason": False, "needs_duration": False},
    "ban": {"needs_target": True, "needs_reason": False, "needs_duration": False},
    "tempban": {"needs_target": True, "needs_reason": False, "needs_duration": True},
    "unban": {"needs_target": True, "needs_reason": False, "needs_duration": False},
    "broadcast": {"needs_target": False, "needs_reason": True, "needs_duration": False},
}

#: Clan actions and the roles allowed to use them, mirroring the plugin's own rules.
CLAN_ACTIONS: dict[str, tuple[str, tuple[str, ...]]] = {
    "invite": ("Invite a player", ("leader", "staff")),
    "kick": ("Remove a member", ("leader", "staff")),
    "promote": ("Promote to staff", ("leader",)),
    "demote": ("Demote a staff member", ("leader",)),
    "rename": ("Rename the clan", ("leader",)),
    "color": ("Change the colour", ("leader",)),
    "transfer": ("Transfer leadership", ("leader",)),
    "disband": ("Disband the clan", ("leader",)),
    "leave": ("Leave the clan", ("leader", "staff", "member")),
    "donate": ("Donate items in game", ("leader", "staff", "member")),
    "upgrade": ("Buy a level or roster slot in game", ("leader", "staff")),
}


@dataclass(frozen=True)
class PlayerCapabilities:
    """One player's standing, as last reported by the server."""

    minecraft_uuid: str
    clan: Optional[str] = None
    clan_role: Optional[str] = None
    clan_colour: int = 0xFF9900
    clan_members: int = 0
    clan_level: int = 0
    clan_balance: int = 0
    clan_member_slots: int = 0
    staff_tools: tuple[str, ...] = field(default_factory=tuple)

    @property
    def in_clan(self) -> bool:
        return bool(self.clan)

    @property
    def is_staff(self) -> bool:
        return bool(self.staff_tools)

    def may(self, action: str) -> bool:
        """Whether this player's clan role allows an action."""
        entry = CLAN_ACTIONS.get(action)
        if entry is None or not self.in_clan:
            return False
        return self.clan_role in entry[1]

    def available_clan_actions(self) -> list[tuple[str, str]]:
        return [
            (action, label)
            for action, (label, _roles) in CLAN_ACTIONS.items()
            if self.may(action)
        ]

    def available_staff_tools(self) -> list[tuple[str, str]]:
        held = set(self.staff_tools)
        return [(key, label) for key, label in STAFF_TOOL_LABELS.items() if key in held]

    def available_remote_tools(self) -> list[tuple[str, str]]:
        """Staff tools this player holds that can also be run from Discord."""
        held = set(self.staff_tools)
        return [
            (key, STAFF_TOOL_LABELS[key])
            for key in REMOTE_STAFF_TOOLS
            if key in held
        ]

    def may_run_remotely(self, tool: str) -> bool:
        return tool in REMOTE_STAFF_TOOLS and tool in self.staff_tools


def capabilities_for(snapshot: dict[str, Any], minecraft_uuid: str) -> PlayerCapabilities:
    """Reads one player out of a server snapshot, defaulting to no privileges."""
    entry = (snapshot.get("players") or {}).get(str(minecraft_uuid))
    if not isinstance(entry, dict):
        return PlayerCapabilities(minecraft_uuid=str(minecraft_uuid))
    tools = entry.get("staff_tools")
    return PlayerCapabilities(
        minecraft_uuid=str(minecraft_uuid),
        clan=entry.get("clan"),
        clan_role=entry.get("clan_role"),
        clan_colour=int(entry.get("clan_colour", 0xFF9900) or 0xFF9900),
        clan_members=int(entry.get("clan_members", 0) or 0),
        clan_level=int(entry.get("clan_level", 0) or 0),
        clan_balance=int(entry.get("clan_balance", 0) or 0),
        clan_member_slots=int(entry.get("clan_member_slots", 0) or 0),
        staff_tools=tuple(tools) if isinstance(tools, list) else (),
    )
