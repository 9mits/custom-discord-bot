package bot.mgx.accessbridge;

import java.util.List;
import java.util.Locale;

/**
 * The icons a player can put on a home.
 *
 * <p>Every path here was checked against a real client jar before it was written
 * down, and {@code SpriteExistsTest} rechecks them on every build. That matters
 * more than it sounds: a sprite naming no texture draws a magenta square and says
 * nothing about why, and several ordinary-looking items — a bed, a shield — have no
 * flat texture at all because they are drawn from an entity model.
 */
final class HomeIcons {
    static final String DEFAULT = "item/oak_door";

    private static final List<String> SPRITES = List.of(
            "item/oak_door",
            "item/iron_door",
            "item/spruce_door",
            "item/birch_door",
            "item/dark_oak_door",
            "block/oak_planks",
            "block/stone_bricks",
            "block/bricks",
            "block/cobblestone",
            "block/deepslate",
            "block/quartz_block_top",
            "block/sandstone_top",
            "block/purpur_block",
            "block/prismarine",
            "block/glowstone",
            "block/sea_lantern",
            "block/redstone_block",
            "block/gold_block",
            "block/diamond_block",
            "block/emerald_block",
            "block/iron_block",
            "block/netherite_block",
            "block/amethyst_block",
            "block/obsidian",
            "block/bookshelf",
            "block/crafting_table_top",
            "block/furnace_front",
            "block/anvil",
            "block/beacon",
            "block/dirt",
            "block/grass_block_side",
            "block/sand",
            "block/snow",
            "block/ice",
            "block/netherrack",
            "block/end_stone",
            "block/bedrock",
            "item/diamond",
            "item/emerald",
            "item/gold_ingot",
            "item/iron_ingot",
            "item/netherite_ingot",
            "item/amethyst_shard",
            "item/nether_star",
            "item/ender_pearl",
            "item/ender_eye",
            "item/blaze_rod",
            "item/book",
            "item/map",
            "item/compass_00",
            "item/clock_00",
            "item/bucket",
            "item/water_bucket",
            "item/lava_bucket",
            "item/milk_bucket",
            "item/apple",
            "item/golden_apple",
            "item/bread",
            "item/cooked_beef",
            "item/cake",
            "item/cookie",
            "item/sugar",
            "item/wheat",
            "item/carrot",
            "item/potato",
            "item/beetroot",
            "item/melon_slice",
            "item/pumpkin_pie",
            "item/egg",
            "item/diamond_sword",
            "item/diamond_pickaxe",
            "item/diamond_axe",
            "item/diamond_shovel",
            "item/diamond_hoe",
            "item/bow",
            "item/arrow",
            "item/trident",
            "item/fishing_rod",
            "item/diamond_helmet",
            "item/diamond_chestplate",
            "item/diamond_boots",
            "item/elytra",
            "item/totem_of_undying",
            "item/experience_bottle",
            "item/firework_rocket",
            "item/tnt_minecart",
            "item/name_tag",
            "item/lead",
            "item/saddle",
            "item/paper",
            "item/writable_book",
            "item/painting",
            "item/item_frame",
            "item/flint_and_steel",
            "item/shears",
            "item/bone",
            "item/feather",
            "item/leather",
            "item/string",
            "item/redstone",
            "item/glowstone_dust",
            "item/gunpowder",
            "item/slime_ball",
            "item/ghast_tear",
            "item/spider_eye",
            "item/rotten_flesh",
            "item/bell",
            "item/comparator",
            "item/repeater",
            "item/lantern",
            "item/campfire",
            "item/barrier",
            "item/spyglass",
            "item/trial_key",
            "item/heart_of_the_sea",
            "item/nautilus_shell",
            "item/echo_shard",
            "item/goat_horn",
            "item/music_disc_cat",
            "item/enchanted_book",
            "item/shulker_shell",
            "item/phantom_membrane",
            "item/honeycomb"
    );

    private HomeIcons() {
    }

    static List<String> all() {
        return SPRITES;
    }

    static boolean known(String sprite) {
        return sprite != null && SPRITES.contains(sprite);
    }

    /** {@code block/grass_block_side} reads as "Grass Block" on a button. */
    static String label(String sprite) {
        String name = sprite.substring(sprite.indexOf('/') + 1);
        for (String suffix : List.of("_00", "_top", "_side", "_front")) {
            if (name.endsWith(suffix)) {
                name = name.substring(0, name.length() - suffix.length());
            }
        }
        StringBuilder text = new StringBuilder();
        for (String word : name.split("_")) {
            if (word.isEmpty()) {
                continue;
            }
            if (!text.isEmpty()) {
                text.append(' ');
            }
            text.append(Character.toUpperCase(word.charAt(0)))
                    .append(word.substring(1).toLowerCase(Locale.ROOT));
        }
        return text.toString();
    }

    /** Matches on the readable label, so a player searches for what they can see. */
    static List<String> search(String query) {
        if (query == null || query.isBlank()) {
            return SPRITES;
        }
        String needle = query.strip().toLowerCase(Locale.ROOT);
        return SPRITES.stream()
                .filter(sprite -> label(sprite).toLowerCase(Locale.ROOT).contains(needle))
                .toList();
    }
}
