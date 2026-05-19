# modules/commands/__init__.py
# Re-exports every public name from the commands subpackage so that
#   from modules.commands import *
# gives mbx_legacy.py (and all external code) the same namespace.

from .shared import *
from .cases import *
from .moderation import *
from .roles import *
from .modmail import *
from .automod import *
from .config import *
from .analytics import *
from .system import *
