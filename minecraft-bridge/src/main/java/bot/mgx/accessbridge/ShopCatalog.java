package bot.mgx.accessbridge;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Server shop and instant-sell prices.
 *
 * <p>Free of Bukkit so the table and the pro-rata maths can be unit tested. Rare
 * progression items are deliberately absent: elytra, netherite, totems, shulker
 * shells and enchanted golden apples are not sold here.
 */
final class ShopCatalog {
    enum Category {
        BUILDING("Building Blocks", "BRICKS"),
        FARMING("Farming & Nature", "WHEAT"),
        ORES("Ores & Materials", "DIAMOND"),
        MOBS("Mob Drops", "ROTTEN_FLESH"),
        UTILITY("Utility", "SADDLE"),
        REDSTONE("Redstone", "REDSTONE"),
        MISC("Miscellaneous", "BUCKET"),
        FOOD("Food", "COOKED_BEEF");

        private final String title;
        private final String icon;

        Category(String title, String icon) {
            this.title = title;
            this.icon = icon;
        }

        String title() {
            return title;
        }

        String icon() {
            return icon;
        }
    }

    record Offer(String material, int amount, long price) {
        Offer {
            material = material == null ? "" : material.toUpperCase(Locale.ROOT);
            if (material.isEmpty() || amount <= 0 || price <= 0L) {
                throw new IllegalArgumentException("Shop offer is incomplete");
            }
        }

        long costOf(int orders) {
            if (orders <= 0) {
                return 0L;
            }
            return Math.multiplyExact(price, orders);
        }

        /** Instant-sell credit for a raw count, floored to whole dollars. */
        long creditFor(int count) {
            if (count <= 0) {
                return 0L;
            }
            return price * (long) count / amount;
        }

        int unitsFor(int count) {
            if (count <= 0) {
                return 0;
            }
            return count / amount;
        }

        int maxOrders(long balance, int inventorySpace) {
            if (price <= 0L || amount <= 0 || balance < price || inventorySpace < amount) {
                return 0;
            }
            long affordable = balance / price;
            int bySpace = inventorySpace / amount;
            return (int) Math.min(bySpace, Math.min(Integer.MAX_VALUE, affordable));
        }
    }

    private static final Map<Category, List<Offer>> BY_CATEGORY = build();
    private static final Map<String, Offer> BY_MATERIAL = index();

    private ShopCatalog() {
    }

    static List<Offer> offers(Category category) {
        return BY_CATEGORY.getOrDefault(category, List.of());
    }

    static Optional<Offer> offer(String material) {
        if (material == null || material.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_MATERIAL.get(material.toUpperCase(Locale.ROOT)));
    }

    static boolean isSellable(String material) {
        return offer(material).isPresent();
    }

    private static Map<Category, List<Offer>> build() {
        Map<Category, List<Offer>> catalog = new EnumMap<>(Category.class);
        catalog.put(Category.BUILDING, List.of(
                offer("COBBLESTONE", 64, 35),
                offer("STONE", 64, 50),
                offer("DIRT", 64, 30),
                offer("SAND", 64, 60),
                offer("GRAVEL", 64, 50),
                offer("GLASS", 64, 75),
                offer("OAK_LOG", 64, 125),
                offer("SPRUCE_LOG", 64, 125),
                offer("BIRCH_LOG", 64, 125),
                offer("DEEPSLATE", 64, 50),
                offer("BRICKS", 64, 150),
                offer("OBSIDIAN", 16, 200)
        ));
        catalog.put(Category.FARMING, List.of(
                offer("BONE", 1, 1),
                offer("BONE_MEAL", 16, 20),
                offer("WHEAT", 16, 25),
                offer("CARROT", 16, 25),
                offer("POTATO", 16, 25),
                offer("SUGAR_CANE", 16, 30),
                offer("CACTUS", 16, 30),
                offer("PUMPKIN", 16, 40),
                offer("MELON", 16, 30),
                offer("BAMBOO", 32, 25),
                offer("LEATHER", 16, 60)
        ));
        catalog.put(Category.ORES, List.of(
                offer("COAL", 16, 50),
                offer("COPPER_INGOT", 16, 60),
                offer("IRON_INGOT", 16, 150),
                offer("GOLD_INGOT", 16, 225),
                offer("REDSTONE", 16, 35),
                offer("LAPIS_LAZULI", 16, 50),
                offer("QUARTZ", 16, 60),
                offer("EMERALD", 1, 40),
                offer("DIAMOND", 1, 250)
        ));
        catalog.put(Category.MOBS, List.of(
                offer("ROTTEN_FLESH", 16, 15),
                offer("STRING", 16, 30),
                offer("SPIDER_EYE", 16, 30),
                offer("GUNPOWDER", 16, 100),
                offer("SLIME_BALL", 16, 100),
                offer("ENDER_PEARL", 4, 75),
                offer("BLAZE_ROD", 4, 100),
                offer("GHAST_TEAR", 1, 100)
        ));
        catalog.put(Category.UTILITY, List.of(
                offer("LEAD", 1, 50),
                offer("SADDLE", 1, 200),
                offer("NAME_TAG", 1, 250),
                offer("EXPERIENCE_BOTTLE", 1, 25),
                offer("ENDER_CHEST", 1, 300),
                offer("GOLDEN_APPLE", 1, 500)
        ));
        catalog.put(Category.REDSTONE, List.of(
                offer("REDSTONE", 16, 35),
                offer("REDSTONE_TORCH", 16, 40),
                offer("REPEATER", 8, 60),
                offer("COMPARATOR", 8, 80),
                offer("PISTON", 8, 100),
                offer("STICKY_PISTON", 8, 150),
                offer("OBSERVER", 8, 100),
                offer("DISPENSER", 8, 100),
                offer("DROPPER", 8, 75),
                offer("HOPPER", 8, 200),
                offer("TARGET", 8, 75),
                offer("DAYLIGHT_DETECTOR", 4, 75)
        ));
        catalog.put(Category.MISC, List.of(
                offer("PAPER", 16, 20),
                offer("BOOK", 16, 50),
                offer("GLASS_BOTTLE", 16, 25),
                offer("BUCKET", 1, 20),
                offer("WATER_BUCKET", 1, 25),
                offer("LAVA_BUCKET", 1, 50),
                offer("SNOWBALL", 16, 10),
                offer("CLAY_BALL", 16, 25),
                offer("FLINT", 16, 25),
                offer("TORCH", 64, 50)
        ));
        catalog.put(Category.FOOD, List.of(
                offer("BREAD", 16, 30),
                offer("COOKED_BEEF", 16, 50),
                offer("COOKED_PORKCHOP", 16, 50),
                offer("COOKED_CHICKEN", 16, 40),
                offer("COOKED_MUTTON", 16, 40),
                offer("COOKED_COD", 16, 35),
                offer("COOKED_SALMON", 16, 40),
                offer("BAKED_POTATO", 16, 25),
                offer("GOLDEN_CARROT", 16, 100),
                offer("PUMPKIN_PIE", 16, 50),
                offer("COOKIE", 16, 20)
        ));
        return catalog;
    }

    private static Map<String, Offer> index() {
        Map<String, Offer> index = new java.util.LinkedHashMap<>();
        for (List<Offer> offers : BY_CATEGORY.values()) {
            for (Offer offer : offers) {
                index.putIfAbsent(offer.material(), offer);
            }
        }
        return Map.copyOf(index);
    }

    private static Offer offer(String material, int amount, long price) {
        return new Offer(material, amount, price);
    }

    static List<Category> categories() {
        return List.of(Category.values());
    }

    static Optional<Category> categoryById(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Category.valueOf(raw.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    static List<Offer> allOffers() {
        List<Offer> all = new ArrayList<>();
        BY_CATEGORY.values().forEach(all::addAll);
        return all;
    }
}
