package bot.mgx.accessbridge;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The clan upgrade ladder: what each level costs, what it grants, and how it is badged.
 *
 * <p>Deliberately free of Bukkit imports so the whole table stays unit-testable;
 * {@link ClanStore} and the command classes delegate here rather than restating any of
 * it. Costs name their material as a string for the same reason — the service layer
 * resolves those to {@code Material}.
 *
 * <p>Perk figures are cumulative totals held at a level, not per-level increments: a
 * level 4 clan has two extra hearts in all, not two on top of level 3's one.
 */
final class ClanLevel {
    /** The highest level a clan can see documented or reach through normal play. */
    static final int MAX_PUBLIC_LEVEL = 5;
    /**
     * Revealed only to a clan that already stands at {@link #MAX_PUBLIC_LEVEL}, and
     * holdable by one clan on the server. Never name it in help, docs or tab
     * completion — {@link #isSecret(int)} guards the surfaces that enumerate levels.
     */
    static final int SECRET_LEVEL = 6;

    /** Totals a clan holds at a level, applied to every member while they belong to it. */
    record Perks(
            int extraHearts,
            double strength,
            double saturation,
            double diggingSpeed,
            double resistance,
            double speed
    ) {
        static final Perks NONE = new Perks(0, 0, 0, 0, 0, 0);

        boolean isNone() {
            return equals(NONE);
        }
    }

    /** One line of an upgrade price. The material is a {@code Material} enum name. */
    record Cost(String material, int amount) {
    }

    private static final Map<Integer, Perks> PERKS = Map.of(
            0, Perks.NONE,
            1, new Perks(0, 0.03, 0.03, 0.00, 0.00, 0.00),
            2, new Perks(0, 0.05, 0.05, 0.10, 0.00, 0.00),
            3, new Perks(1, 0.10, 0.10, 0.15, 0.05, 0.05),
            4, new Perks(2, 0.10, 0.15, 0.20, 0.10, 0.10),
            5, new Perks(3, 0.10, 0.15, 0.25, 0.15, 0.15),
            6, new Perks(3, 0.10, 0.15, 0.25, 0.15, 0.15)
    );

    private static final Map<Integer, List<Cost>> COSTS = Map.of(
            1, List.of(new Cost("DIAMOND", 30)),
            2, List.of(new Cost("DIAMOND_BLOCK", 30)),
            3, List.of(
                    new Cost("DIAMOND", 64),
                    new Cost("NETHERITE_INGOT", 10),
                    new Cost("NETHER_STAR", 1)
            ),
            4, List.of(new Cost("NETHERITE_BLOCK", 10)),
            5, List.of(
                    new Cost("NETHERITE_BLOCK", 64),
                    new Cost("NETHER_STAR", 3),
                    new Cost("ENCHANTED_GOLDEN_APPLE", 1)
            ),
            6, List.of(new Cost("DRAGON_EGG", 1))
    );

    /**
     * What the vault accepts. Restricting it to the upgrade materials keeps clans.json
     * small and stops the vault becoming general storage.
     */
    static final Set<String> DEPOSITABLE = COSTS.values().stream()
            .flatMap(List::stream)
            .map(Cost::material)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

    /** One star per level, so the level reads without anyone printing "LEVEL 3". */
    private static final Map<Integer, String> BADGES = Map.of(
            0, "",
            1, "★",
            2, "★★",
            3, "★★★",
            4, "★★★★",
            5, "★★★★★",
            6, "✦"
    );

    private static final Map<Integer, Integer> BADGE_COLORS = Map.of(
            0, 0xFFFFFF,
            1, 0xAAAAAA,
            2, 0x55FF55,
            3, 0x55FFFF,
            4, 0xFFAA00,
            5, 0xFF55FF,
            6, 0xAA00AA
    );

    private ClanLevel() {
    }

    static Perks perksFor(int level) {
        return PERKS.getOrDefault(clamp(level), Perks.NONE);
    }

    /** The price of reaching {@code level}, or empty when there is nothing to buy. */
    static List<Cost> costOf(int level) {
        return COSTS.getOrDefault(level, List.of());
    }

    static String badge(int level) {
        return BADGES.getOrDefault(clamp(level), "");
    }

    static int badgeColor(int level) {
        return BADGE_COLORS.getOrDefault(clamp(level), 0xFFFFFF);
    }

    /** Whether a level exists at all. */
    static boolean isValid(int level) {
        return level >= 0 && level <= SECRET_LEVEL;
    }

    /** Whether a level must stay hidden from a clan standing below it. */
    static boolean isSecret(int level) {
        return level > MAX_PUBLIC_LEVEL;
    }

    /**
     * Whether {@code current} may see {@code level} named. A clan learns the secret
     * level only once it has nothing else left to buy.
     */
    static boolean canSee(int current, int level) {
        return !isSecret(level) || current >= MAX_PUBLIC_LEVEL;
    }

    static boolean isDepositable(String material) {
        return DEPOSITABLE.contains(normalizeMaterial(material));
    }

    static String normalizeMaterial(String material) {
        return material == null ? "" : material.trim().toUpperCase(Locale.ROOT);
    }

    /** "Diamond Block" from "DIAMOND_BLOCK", for messages meant to be read. */
    static String readableMaterial(String material) {
        String normalized = normalizeMaterial(material);
        if (normalized.isEmpty()) {
            return "";
        }
        StringBuilder readable = new StringBuilder();
        for (String word : normalized.split("_")) {
            if (word.isEmpty()) {
                continue;
            }
            if (readable.length() > 0) {
                readable.append(' ');
            }
            readable.append(word.charAt(0)).append(word.substring(1).toLowerCase(Locale.ROOT));
        }
        return readable.toString();
    }

    /**
     * What {@code vault} still lacks to buy {@code level}, in cost order. Empty means
     * the upgrade is affordable.
     */
    static Map<String, Integer> shortfall(Map<String, Integer> vault, int level) {
        LinkedHashMap<String, Integer> missing = new LinkedHashMap<>();
        for (Cost cost : costOf(level)) {
            int held = vault == null ? 0 : vault.getOrDefault(cost.material(), 0);
            if (held < cost.amount()) {
                missing.put(cost.material(), cost.amount() - held);
            }
        }
        return missing;
    }

    private static int clamp(int level) {
        return Math.max(0, Math.min(level, SECRET_LEVEL));
    }
}
