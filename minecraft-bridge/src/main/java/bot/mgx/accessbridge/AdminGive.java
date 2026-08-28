package bot.mgx.accessbridge;

import java.util.List;
import java.util.Locale;

/**
 * Parses {@code /mgxadmin give <target> <what> [value]}.
 *
 * <p>Pure on purpose: the command itself needs Bukkit to find players and hand out
 * items, but deciding what was asked for does not, which is what makes it testable.
 */
final class AdminGive {
    /** Keys are handed over as items, so one command cannot exceed a stack. */
    static final int MAX_KEYS = 64;

    static final List<String> TYPES = List.of(
            "money", "key", "cosmetic", "cosmetics", "reward", "amethyst"
    );

    /** Every cosmetic an administrator can preview or grant through command completion. */
    static List<String> cosmeticIds() {
        return CosmeticCatalog.visualEntries().stream()
                .map(CosmeticCatalog.Definition::id)
                .toList();
    }

    enum Type {
        MONEY,
        KEY,
        COSMETIC,
        LEADERBOARD_COSMETICS,
        REWARD,
        AMETHYST_REWARDS
    }

    record Request(Type type, long amount, String cosmeticId) {
    }

    private AdminGive() {
    }

    static Request parse(String rawType, String value) {
        if (rawType == null || rawType.isBlank()) {
            throw new IllegalArgumentException(usage());
        }
        String type = rawType.trim().toLowerCase(Locale.ROOT);
        switch (type) {
            case "money", "cash", "balance" -> {
                if (value == null) {
                    throw new IllegalArgumentException("Give how much money? " + usage());
                }
                long amount = EconomyFormat.parseAmount(value);
                if (amount <= 0) {
                    throw new IllegalArgumentException("Give a positive amount of money.");
                }
                return new Request(Type.MONEY, amount, null);
            }
            case "key", "keys", "crate", "crates" -> {
                // The amount is optional here because one key is the common case.
                int amount = value == null ? 1 : parseCount(value);
                if (amount < 1 || amount > MAX_KEYS) {
                    throw new IllegalArgumentException(
                            "Give between 1 and " + MAX_KEYS + " keys at a time."
                    );
                }
                return new Request(Type.KEY, amount, null);
            }
            case "cosmetic" -> {
                if (value == null || value.isBlank()) {
                    throw new IllegalArgumentException(
                            "Give which cosmetic? Use /mgxadmin give <player> cosmetic <id>."
                    );
                }
                return new Request(Type.COSMETIC, 1, value.trim().toLowerCase(Locale.ROOT));
            }
            case "cosmetics" -> {
                if (value == null || value.isBlank()) {
                    return new Request(Type.LEADERBOARD_COSMETICS, 1, null);
                }
                return new Request(Type.COSMETIC, 1, value.trim().toLowerCase(Locale.ROOT));
            }
            case "reward", "crate_reward" -> {
                if (value == null || value.isBlank()) {
                    throw new IllegalArgumentException(
                            "Give which reward? Use /mgxadmin give <player> reward <id>."
                    );
                }
                return new Request(Type.REWARD, 1, value.trim().toLowerCase(Locale.ROOT));
            }
            case "amethyst", "amethyst_crate", "amethyst_rewards" -> {
                return new Request(Type.AMETHYST_REWARDS, 1, null);
            }
            default -> throw new IllegalArgumentException(usage());
        }
    }

    private static int parseCount(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("The amount must be a whole number.");
        }
    }

    static String usage() {
        return "Usage: /mgxadmin give <player|everyone> "
                + "<money|key|cosmetic <id>|cosmetics|reward <id>|amethyst>";
    }
}
