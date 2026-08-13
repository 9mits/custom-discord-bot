package bot.mgx.accessbridge;

import org.bukkit.Material;

import java.util.Map;

/**
 * Values the things players actually fight over, so "richest" tracks standing rather
 * than hoarded cobblestone. There is no economy plugin, so these are arbitrary but
 * deliberately ordered: one netherite ingot outranks a stack of diamonds.
 *
 * <p>Anything not listed is worth nothing, which keeps bulk storage from drowning
 * out real wealth.
 */
final class WealthTable {
    private static final Map<Material, Integer> VALUES = Map.ofEntries(
            Map.entry(Material.NETHER_STAR, 2_000),
            Map.entry(Material.BEACON, 2_500),
            Map.entry(Material.ELYTRA, 1_500),
            Map.entry(Material.TOTEM_OF_UNDYING, 800),
            Map.entry(Material.ENCHANTED_GOLDEN_APPLE, 600),
            Map.entry(Material.NETHERITE_BLOCK, 900),
            Map.entry(Material.NETHERITE_INGOT, 100),
            Map.entry(Material.NETHERITE_SCRAP, 60),
            Map.entry(Material.ANCIENT_DEBRIS, 60),
            Map.entry(Material.NETHERITE_HELMET, 220),
            Map.entry(Material.NETHERITE_CHESTPLATE, 260),
            Map.entry(Material.NETHERITE_LEGGINGS, 240),
            Map.entry(Material.NETHERITE_BOOTS, 220),
            Map.entry(Material.NETHERITE_SWORD, 200),
            Map.entry(Material.NETHERITE_PICKAXE, 200),
            Map.entry(Material.NETHERITE_AXE, 200),
            Map.entry(Material.DIAMOND_BLOCK, 81),
            Map.entry(Material.DIAMOND, 9),
            Map.entry(Material.EMERALD_BLOCK, 45),
            Map.entry(Material.EMERALD, 5),
            Map.entry(Material.GOLD_BLOCK, 27),
            Map.entry(Material.GOLD_INGOT, 3),
            Map.entry(Material.IRON_BLOCK, 9),
            Map.entry(Material.IRON_INGOT, 1),
            Map.entry(Material.SHULKER_BOX, 120),
            Map.entry(Material.ENDER_CHEST, 60),
            Map.entry(Material.EXPERIENCE_BOTTLE, 4),
            Map.entry(Material.DRAGON_EGG, 5_000),
            Map.entry(Material.DRAGON_HEAD, 1_200),
            Map.entry(Material.HEART_OF_THE_SEA, 400),
            Map.entry(Material.CONDUIT, 900),
            Map.entry(Material.TRIDENT, 500),
            Map.entry(Material.ENCHANTED_BOOK, 40),
            Map.entry(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE, 700)
    );

    private WealthTable() {
    }

    static int valueOf(Material material) {
        return VALUES.getOrDefault(material, 0);
    }

    /** Every dyed variant of a shulker box counts as a plain one. */
    static int valueOfIncludingVariants(Material material) {
        int direct = valueOf(material);
        if (direct > 0) {
            return direct;
        }
        return material.name().endsWith("SHULKER_BOX") ? valueOf(Material.SHULKER_BOX) : 0;
    }
}
