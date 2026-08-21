"""Site-wide settings for the dev blog. Edit this file, not the theme.

Anything left as an empty string is omitted from the rendered pages, so a value
you have not filled in yet never shows up as a dead link or an empty box.
"""

SITE_NAME = "Mysterious SMP X"
SITE_SHORT = "MGX"
SITE_TAGLINE = "Update notes, patch logs, and behind-the-scenes from the SMP."

# Where the site is published. Used for the RSS feed and social share tags.
# Override per build with --site-url or the DEVBLOG_SITE_URL env var.
DEFAULT_SITE_URL = "https://mysterioussmpx.blog"

# Default category pill shown above a post title; a post can override it with
# its own `category:` front matter key.
DEFAULT_CATEGORY = "Mysterious SMP X"

# --- the sidebar server card ------------------------------------------------
# SERVER_ADDRESS drives the card, the copy button and the footer. Empty hides
# the whole card.
SERVER_ADDRESS = ""            # e.g. "play.mysterioussmpx.com"
SERVER_EDITIONS = "Java & Bedrock"
SERVER_VERSION = ""            # e.g. "1.21.1"

# --- links ------------------------------------------------------------------
DISCORD_URL = ""               # e.g. "https://discord.gg/xxxxxxx"
APPLY_URL = ""                 # e.g. a whitelist application form
YOUTUBE_URL = ""
TWITTER_URL = ""
