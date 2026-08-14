package bot.mgx.accessbridge;

import java.util.Locale;

/**
 * Validation for the image address behind {@code /clans icon}.
 *
 * <p>Deliberately free of Bukkit imports so the rules stay unit-testable; the
 * command classes delegate here rather than parsing addresses themselves.
 */
final class ClanIcon {
    /** Long enough for signed CDN addresses, short enough to keep the save file sane. */
    static final int MAX_LENGTH = 500;

    private ClanIcon() {
    }

    /**
     * Returns the address to store, or throws with a message meant for the player.
     *
     * <p>Only the address is checked. Whether it still resolves later is not
     * knowable here, and Discord attachment links in particular expire.
     */
    static String normalize(String requested) {
        String url = requested == null ? "" : requested.trim();
        if (url.isEmpty()) {
            throw new ClanStore.ClanException("Usage: /clans icon <direct image address>");
        }
        if (url.length() > MAX_LENGTH) {
            throw new ClanStore.ClanException(
                    "That address is longer than " + MAX_LENGTH + " characters."
            );
        }
        String lowered = url.toLowerCase(Locale.ROOT);
        if (!lowered.startsWith("https://")) {
            throw new ClanStore.ClanException("The address must begin with https://");
        }
        if (url.chars().anyMatch(Character::isWhitespace)) {
            throw new ClanStore.ClanException("The address cannot contain spaces.");
        }
        if (!hasImageExtension(lowered)) {
            throw new ClanStore.ClanException(
                    "That must be a direct image address ending in .png, .jpg, .gif or .webp."
            );
        }
        return url;
    }

    /**
     * Whether the path names an image file.
     *
     * <p>Checked before any query string, because signed CDN addresses carry their
     * expiry and signature after a {@code ?} and would otherwise never match.
     */
    private static boolean hasImageExtension(String loweredUrl) {
        int query = loweredUrl.indexOf('?');
        String path = query < 0 ? loweredUrl : loweredUrl.substring(0, query);
        return path.endsWith(".png")
                || path.endsWith(".jpg")
                || path.endsWith(".jpeg")
                || path.endsWith(".gif")
                || path.endsWith(".webp");
    }

    /** Whether an address is one Discord will stop serving once its signature expires. */
    static boolean isExpiringDiscordLink(String url) {
        String lowered = url == null ? "" : url.toLowerCase(Locale.ROOT);
        boolean discordHost = lowered.startsWith("https://cdn.discordapp.com/attachments/")
                || lowered.startsWith("https://media.discordapp.net/attachments/");
        return discordHost && lowered.contains("ex=");
    }
}
