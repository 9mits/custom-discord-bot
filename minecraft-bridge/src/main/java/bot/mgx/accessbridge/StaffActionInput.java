package bot.mgx.accessbridge;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Sanitizes what a Discord-originated staff action carries before it ever reaches a
 * command string.
 *
 * <p>Deliberately free of Bukkit and LuckPerms types so it stays unit-testable: those
 * are {@code compileOnly} and absent at test runtime, the same reason {@link SidebarText}
 * exists on its own.
 */
final class StaffActionInput {
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^\\.?[A-Za-z0-9_]{1,16}$");
    private static final Pattern DURATION_PATTERN = Pattern.compile("^\\d+[smhdw]$");

    private StaffActionInput() {
    }

    /** A Java or Floodgate-prefixed Bedrock username, or a refusal — never anything else. */
    static String sanitizeUsername(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (!USERNAME_PATTERN.matcher(trimmed).matches()) {
            throw new StaffActionException("\"" + trimmed + "\" is not a valid player name.");
        }
        return trimmed;
    }

    /** Essentials-style durations only: {@code 10m}, {@code 2h}, {@code 7d}. */
    static String sanitizeDuration(String raw) {
        String trimmed = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (trimmed.isEmpty()) {
            return "";
        }
        if (!DURATION_PATTERN.matcher(trimmed).matches()) {
            throw new StaffActionException("Duration must look like 10m, 2h, or 7d.");
        }
        return trimmed;
    }

    /** Strips control characters and a leading slash, so nothing here reads as another command. */
    static String sanitizeFreeText(String raw, int limit) {
        if (raw == null) {
            return "";
        }
        String cleaned = raw.replaceAll("[\\r\\n\\t]", " ").strip();
        if (cleaned.startsWith("/")) {
            cleaned = cleaned.substring(1);
        }
        return cleaned.length() > limit ? cleaned.substring(0, limit) : cleaned;
    }
}
