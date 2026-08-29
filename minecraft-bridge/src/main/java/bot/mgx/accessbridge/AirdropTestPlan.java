package bot.mgx.accessbridge;

import java.util.List;
import java.util.Locale;

/** Pure command parser for the local Amethyst Airdrop test suite. */
final class AirdropTestPlan {
    enum Action {
        HELP,
        SPAWN,
        STATUS,
        EXPIRE,
        REMOVE,
        PROGRESS_SET,
        PROGRESS_RESET
    }

    record Request(
            Action action,
            AirdropCatalog.Rarity rarity,
            List<String> cosmeticIds,
            long cratesOpened,
            long airdropsOpened,
            String targetName
    ) {
        Request {
            cosmeticIds = cosmeticIds == null ? List.of() : List.copyOf(cosmeticIds);
        }
    }

    static final List<String> ACTIONS = List.of(
            "all", "common", "rare", "legendary", "mythic", "cosmetic",
            "status", "expire", "remove", "progress", "help"
    );
    static final List<String> COSMETICS = List.of("kill", "trail", "aura", "all");
    static final long MAXIMUM_TEST_PROGRESS = 1_000_000L;

    private AirdropTestPlan() {
    }

    static Request parse(String[] args) {
        if (args.length < 2) {
            return request(Action.HELP);
        }
        if (args[1].equalsIgnoreCase("help")) {
            requireLength(args, 2);
            return request(Action.HELP);
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        return switch (action) {
            case "all" -> {
                requireLength(args, 2);
                yield spawn(AirdropCatalog.Rarity.MYTHIC, AirdropCatalog.cosmeticIds());
            }
            case "common" -> spawnRarity(args, AirdropCatalog.Rarity.COMMON);
            case "rare" -> spawnRarity(args, AirdropCatalog.Rarity.RARE);
            case "legendary" -> spawnRarity(args, AirdropCatalog.Rarity.LEGENDARY);
            case "mythic" -> spawnRarity(args, AirdropCatalog.Rarity.MYTHIC);
            case "cosmetic", "cosmetics" -> cosmetic(args);
            case "status" -> status(args);
            case "expire" -> simple(args, Action.EXPIRE);
            case "remove", "cleanup", "clear" -> simple(args, Action.REMOVE);
            case "progress", "leaderboard", "leaderboards" -> progress(args);
            default -> throw new IllegalArgumentException(usage());
        };
    }

    static String usage() {
        return "Usage: /mgxadmin testairdrop "
                + "<all|common|rare|legendary|mythic|cosmetic|status|expire|remove|progress>";
    }

    private static Request spawnRarity(String[] args, AirdropCatalog.Rarity rarity) {
        requireLength(args, 2);
        return spawn(rarity, List.of());
    }

    private static Request cosmetic(String[] args) {
        if (args.length != 3) {
            throw new IllegalArgumentException(
                    "Usage: /mgxadmin testairdrop cosmetic <kill|trail|aura|all>"
            );
        }
        List<String> ids = switch (args[2].toLowerCase(Locale.ROOT)) {
            case "kill", "kill-effect", "kill_effect" -> List.of("resonant_shatter");
            case "trail" -> List.of("crystalfall_wake");
            case "aura" -> List.of("airdrop_apotheosis");
            case "all" -> AirdropCatalog.cosmeticIds();
            default -> throw new IllegalArgumentException(
                    "Use kill, trail, aura, or all for the cosmetic test."
            );
        };
        return spawn(AirdropCatalog.Rarity.MYTHIC, ids);
    }

    private static Request status(String[] args) {
        if (args.length > 3) {
            throw new IllegalArgumentException("Usage: /mgxadmin testairdrop status [player]");
        }
        return new Request(
                Action.STATUS, null, List.of(), 0L, 0L,
                args.length == 3 ? args[2] : null
        );
    }

    private static Request simple(String[] args, Action action) {
        requireLength(args, 2);
        return request(action);
    }

    private static Request progress(String[] args) {
        if (args.length >= 3 && args[2].equalsIgnoreCase("reset")) {
            if (args.length > 4) {
                throw new IllegalArgumentException(
                        "Usage: /mgxadmin testairdrop progress reset [player]"
                );
            }
            return new Request(
                    Action.PROGRESS_RESET, null, List.of(), 0L, 0L,
                    args.length == 4 ? args[3] : null
            );
        }
        if (args.length < 4 || args.length > 5) {
            throw new IllegalArgumentException(
                    "Usage: /mgxadmin testairdrop progress <crates> <airdrops> [player]"
            );
        }
        return new Request(
                Action.PROGRESS_SET,
                null,
                List.of(),
                progressCount(args[2]),
                progressCount(args[3]),
                args.length == 5 ? args[4] : null
        );
    }

    private static long progressCount(String token) {
        final long value;
        try {
            value = Long.parseLong(token);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Leaderboard test counts must be whole numbers.");
        }
        if (value < 0L || value > MAXIMUM_TEST_PROGRESS) {
            throw new IllegalArgumentException(
                    "Leaderboard test counts must be between 0 and "
                            + MAXIMUM_TEST_PROGRESS + "."
            );
        }
        return value;
    }

    private static Request spawn(AirdropCatalog.Rarity rarity, List<String> cosmeticIds) {
        return new Request(Action.SPAWN, rarity, cosmeticIds, 0L, 0L, null);
    }

    private static Request request(Action action) {
        return new Request(action, null, List.of(), 0L, 0L, null);
    }

    private static void requireLength(String[] args, int expected) {
        if (args.length != expected) {
            throw new IllegalArgumentException(usage());
        }
    }
}
