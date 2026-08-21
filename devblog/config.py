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

# --- the server ---------------------------------------------------------
# Drives the Copy IP button in the community band and the footer address.
# Empty hides both.
SERVER_ADDRESS = ""            # e.g. "play.mysterioussmpx.com"

# --- links ------------------------------------------------------------------
DISCORD_URL = "https://discord.gg/mgx"
APPLY_URL = (
    "https://discord.com/channels/1476839721731620938"
    "/1537882766597955594/1538269923988471919"
)
REDDIT_URL = "https://reddit.com/r/MysteriousGirlfriendX"
YOUTUBE_URL = ""               # e.g. "https://youtube.com/@yourchannel"
TWITTER_URL = ""               # e.g. "https://x.com/yourhandle"
