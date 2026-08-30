"""Canonical Discord command namespaces for the Minecraft bot.

Keeping these in one dependency-free module prevents help copy, audit labels, and
the registered command tree from quietly choosing different prefixes again.
"""

MEMBER_GROUP = "minecraft"
STAFF_GROUP = "mgxstaff"
ADMIN_GROUP = "mgxadmin"

# Historical audit rows keep their original command paths.  These names are only
# accepted while reading those records; they are never advertised or registered.
LEGACY_STAFF_GROUP = "mcstaff"
LEGACY_ADMIN_GROUP = "mcadmin"

CANONICAL_GROUPS = (MEMBER_GROUP, STAFF_GROUP, ADMIN_GROUP)
AUDIT_GROUPS = CANONICAL_GROUPS + (LEGACY_STAFF_GROUP, LEGACY_ADMIN_GROUP)
