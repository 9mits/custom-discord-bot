"""Site-wide settings for the dev blog. Edit this file, not the theme.

Anything left as an empty string is simply omitted from the rendered pages, so
a value you have not filled in yet never shows up as a dead link.
"""

SITE_NAME = "Mysterious SMP X"
SITE_TAGLINE = "Update notes, patch logs, and behind-the-scenes from the SMP."

# Where the site is published. Used for the RSS feed and social share tags.
# Override per build with --site-url or the DEVBLOG_SITE_URL env var.
DEFAULT_SITE_URL = "https://mysterioussmpx.blog"

# TODO: fill these in. Empty means the link is left out entirely.
DISCORD_URL = ""       # e.g. "https://discord.gg/xxxxxxx"
SERVER_ADDRESS = ""    # e.g. "play.example.com"
