package bot.mgx.accessbridge;

import java.util.Locale;
import java.util.Map;

/**
 * What the things players fight over are worth, keyed by material name.
 *
 * <p>Free of Bukkit imports so the clan vault can be valued and unit-tested;
 * {@link WealthTable} holds the {@code Material} overloads and delegates here. There
 * is no economy plugin, so these are arbitrary but deliberately ordered: one
 * netherite ingot outranks a stack of diamonds.
 *
 * <p>Anything unlisted is worth nothing, which is also what makes it undonatable —
 * it keeps bulk storage out of the clan vault and off the richest board.
 */
final class WealthValues {
    private static final Map<String, Integer> VALUES = Map.ofEntries(
            Map.entry("NETHER_STAR", 2_000),
            Map.entry("BEACON", 2_500),
            Map.entry("ELYTRA", 1_500),
            Map.entry("TOTEM_OF_UNDYING", 800),
            Map.entry("ENCHANTED_GOLDEN_APPLE", 600),
            Map.entry("NETHERITE_BLOCK", 900),
            Map.entry("NETHERITE_INGOT", 100),
            Map.entry("NETHERITE_SCRAP", 60),
            Map.entry("ANCIENT_DEBRIS", 60),
            Map.entry("NETHERITE_HELMET", 220),
            Map.entry("NETHERITE_CHESTPLATE", 260),
            Map.entry("NETHERITE_LEGGINGS", 240),
            Map.entry("NETHERITE_BOOTS", 220),
            Map.entry("NETHERITE_SWORD", 200),
            Map.entry("NETHERITE_PICKAXE", 200),
            Map.entry("NETHERITE_AXE", 200),
            Map.entry("DIAMOND_BLOCK", 81),
            Map.entry("DIAMOND", 9),
            Map.entry("EMERALD_BLOCK", 45),
            Map.entry("EMERALD", 5),
            Map.entry("GOLD_BLOCK", 27),
            Map.entry("GOLD_INGOT", 3),
            Map.entry("IRON_BLOCK", 9),
            Map.entry("IRON_INGOT", 1),
            Map.entry("SHULKER_BOX", 120),
            Map.entry("ENDER_CHEST", 60),
            Map.entry("EXPERIENCE_BOTTLE", 4),
            Map.entry("DRAGON_EGG", 5_000),
            Map.entry("DRAGON_HEAD", 1_200),
            Map.entry("HEART_OF_THE_SEA", 400),
            Map.entry("CONDUIT", 900),
            Map.entry("TRIDENT", 500),
            Map.entry("ENCHANTED_BOOK", 40),
            Map.entry("NETHERITE_UPGRADE_SMITHING_TEMPLATE", 700)
    );

    private WealthValues() {
    }

    static int valueOf(String material) {
        return VALUES.getOrDefault(normalize(material), 0);
    }

    /** Every dyed variant of a shulker box counts as a plain one. */
    static int valueOfIncludingVariants(String material) {
        String normalized = normalize(material);
        int direct = valueOf(normalized);
        if (direct > 0) {
            return direct;
        }
        return normalized.endsWith("SHULKER_BOX") ? valueOf("SHULKER_BOX") : 0;
    }

    /** Whether the clan vault will take this at all. */
    static boolean isValuable(String material) {
        return valueOfIncludingVariants(material) > 0;
    }

    /** The total worth of a banked vault, which is what "clan balance" means. */
    static long totalOf(Map<String, Integer> vault) {
        if (vault == null) {
            return 0;
        }
        long total = 0;
        for (Map.Entry<String, Integer> entry : vault.entrySet()) {
            total += (long) valueOfIncludingVariants(entry.getKey())
                    * Math.max(0, entry.getValue() == null ? 0 : entry.getValue());
        }
        return total;
    }

    static String normalize(String material) {
        return material == null ? "" : material.trim().toUpperCase(Locale.ROOT);
    }

    /** "Diamond Block" from "DIAMOND_BLOCK", for messages meant to be read. */
    static String readable(String material) {
        String normalized = normalize(material);
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
}
