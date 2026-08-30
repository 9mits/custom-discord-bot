package bot.mgx.accessbridge;

import java.math.BigDecimal;
import java.util.Locale;

/** How money is shown to players, and how they are allowed to type it back. */
final class EconomyFormat {
    /**
     * Long enough for the largest balance anybody can hold, short enough that no input can
     * ask {@link BigDecimal} to build a number big enough to be a problem on its own.
     */
    private static final int MAX_INPUT = 24;

    /** The one wording for "that was not an amount", so every entry point agrees. */
    static final String AMOUNT_HELP = "Enter an amount like 500, 2.5k, 200k or 1.4m.";

    private EconomyFormat() {
    }

    static String dollars(long amount) {
        return "$" + String.format(Locale.US, "%,d", Math.max(0L, amount));
    }

    static String compactDollars(long amount) {
        long safe = Math.max(0L, amount);
        if (safe >= 1_000_000_000L) {
            return "$" + compact(safe, 1_000_000_000L) + "B";
        }
        if (safe >= 1_000_000L) {
            return "$" + compact(safe, 1_000_000L) + "M";
        }
        if (safe >= 1_000L) {
            return "$" + compact(safe, 1_000L) + "K";
        }
        return "$" + safe;
    }

    private static String compact(long amount, long unit) {
        double value = amount / (double) unit;
        return value >= 100d || value == Math.rint(value)
                ? String.format(Locale.US, "%.0f", value)
                : String.format(Locale.US, "%.1f", value);
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

    /** What a player may type instead of the zeroes. Mirrors {@link #compactDollars}. */
    private static long unitFor(char suffix) {
        return switch (Character.toLowerCase(suffix)) {
            case 'k' -> 1_000L;
            case 'm' -> 1_000_000L;
            case 'b' -> 1_000_000_000L;
            case 't' -> 1_000_000_000_000L;
            default -> 0L;
        };
    }

    /**
     * Reads a money amount, accepting the same abbreviations the game uses to show one.
     *
     * <p>The suffixes are deliberately the ones {@link #compactDollars} prints: a player
     * who reads {@code $1.4M} on a board can type {@code 1.4m} back, and typing the
     * fourteen characters of the long form is never the only way to spend a balance.
     *
     * <p>A fractional result is refused rather than rounded. {@code 1.2345k} is $1234.50,
     * and quietly turning that into $1234 or $1235 is a different amount than the one that
     * was typed — for money, saying so is better than picking.
     */
    static long parseAmount(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException(AMOUNT_HELP);
        }
        String cleaned = raw.strip().replace(",", "").replace("$", "").replace("_", "");
        if (cleaned.isEmpty() || cleaned.length() > MAX_INPUT) {
            throw new IllegalArgumentException(AMOUNT_HELP);
        }

        long unit = unitFor(cleaned.charAt(cleaned.length() - 1));
        if (unit > 0L) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
            if (cleaned.isEmpty()) {
                throw new IllegalArgumentException(AMOUNT_HELP);
            }
        } else {
            unit = 1L;
        }

        BigDecimal scaled;
        try {
            // A plain constructor call, so no exponent notation and no locale surprises:
            // "1e5" is not an amount anybody means to type into a pay command.
            if (!cleaned.matches("-?\\d*\\.?\\d+")) {
                throw new NumberFormatException(cleaned);
            }
            scaled = new BigDecimal(cleaned).multiply(BigDecimal.valueOf(unit));
        } catch (NumberFormatException | ArithmeticException ignored) {
            throw new IllegalArgumentException(AMOUNT_HELP);
        }

        if (scaled.stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException(
                    "Amounts are whole dollars. " + AMOUNT_HELP
            );
        }
        long amount;
        try {
            amount = scaled.longValueExact();
        } catch (ArithmeticException tooBig) {
            throw new IllegalArgumentException("That amount is too large.");
        }
        if (amount <= 0L) {
            throw new IllegalArgumentException("The amount must be at least $1.");
        }
        return amount;
    }
}
