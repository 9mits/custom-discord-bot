package bot.mgx.accessbridge;

import java.util.Locale;

/** How money is shown to players. */
final class EconomyFormat {
    private EconomyFormat() {
    }

    static String dollars(long amount) {
        return "$" + String.format(Locale.US, "%,d", Math.max(0L, amount));
    }

    static String remaining(long millis) {
        if (millis <= 0L) {
            return "expired";
        }
        long totalMinutes = Math.max(1L, millis / 60_000L);
        long days = totalMinutes / (60L * 24L);
        long hours = (totalMinutes / 60L) % 24L;
        long minutes = totalMinutes % 60L;
        if (days > 0L) {
            return days + "d " + hours + "h";
        }
        if (hours > 0L) {
            return hours + "h " + minutes + "m";
        }
        return minutes + "m";
    }

    static long parseAmount(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("Enter a whole dollar amount.");
        }
        String cleaned = raw.strip().replace(",", "").replace("$", "");
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("Enter a whole dollar amount.");
        }
        long amount;
        try {
            amount = Long.parseLong(cleaned);
        } catch (NumberFormatException ignored) {
            throw new IllegalArgumentException("Enter a whole dollar amount.");
        }
        if (amount <= 0L) {
            throw new IllegalArgumentException("The amount must be at least $1.");
        }
        return amount;
    }
}
