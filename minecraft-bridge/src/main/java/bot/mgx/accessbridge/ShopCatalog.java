package bot.mgx.accessbridge;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Server shop buy prices and instant-sell prices.
 *
 * <p>They are different tables. Shop is a convenience sink; sell is how farms
 * make money. Rare progression items are not sold: elytra, netherite, totems,
 * shulker shells and enchanted golden apples.
 */
final class ShopCatalog {
    enum Category {
        BUILDING("Building Blocks", "BRICKS"),
        WOOD("Wood", "OAK_LOG"),
        COLORED("Colored Blocks", "WHITE_WOOL"),
        FARMING("Farming", "WHEAT"),
        MINERALS("Minerals", "DIAMOND"),
        MOBS("Mob Drops", "ROTTEN_FLESH"),
        REDSTONE("Redstone", "REDSTONE"),
        FOOD("Food", "COOKED_BEEF"),
        OCEAN("Ocean", "PRISMARINE"),
        NETHER("Nether", "NETHERRACK"),
        END("End", "END_STONE"),
        DECORATION("Decoration", "LANTERN"),
        TRANSPORT("Transportation", "RAIL"),
        MISC("Miscellaneous", "BUCKET");

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

        int maxOrders(long balance, int inventorySpace) {
            if (price <= 0L || amount <= 0 || balance < price || inventorySpace < amount) {
                return 0;
            }
            long affordable = balance / price;
            int bySpace = inventorySpace / amount;
            return (int) Math.min(bySpace, Math.min(Integer.MAX_VALUE, affordable));
        }
    }

    record SellQuote(String material, long unitPrice) {
        long creditFor(int count) {
            if (count <= 0 || unitPrice <= 0L) {
                return 0L;
            }
            return unitPrice * (long) count;
        }
    }

    private static final String[] DYES = {
            "WHITE", "ORANGE", "MAGENTA", "LIGHT_BLUE", "YELLOW", "LIME", "PINK",
            "GRAY", "LIGHT_GRAY", "CYAN", "PURPLE", "BLUE", "BROWN", "GREEN", "RED", "BLACK"
    };

    private static final Map<Category, List<Offer>> BY_CATEGORY = buildShop();
    private static final Map<String, Offer> BUY_BY_MATERIAL = indexBuy();
    private static final Map<String, SellQuote> SELL_BY_MATERIAL = buildSell();

    private ShopCatalog() {
    }

    static List<Offer> offers(Category category) {
        return BY_CATEGORY.getOrDefault(category, List.of());
    }

    static Optional<Offer> offer(String material) {
        if (material == null || material.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(BUY_BY_MATERIAL.get(material.toUpperCase(Locale.ROOT)));
    }

    static Optional<SellQuote> sellQuote(String material) {
        if (material == null || material.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(SELL_BY_MATERIAL.get(material.toUpperCase(Locale.ROOT)));
    }

    static boolean isSellable(String material) {
        return sellQuote(material).isPresent();
    }

    static long sellCredit(String material, int count) {
        return sellQuote(material).map(quote -> quote.creditFor(count)).orElse(0L);
    }

    static List<SellQuote> allSellQuotes() {
        return List.copyOf(SELL_BY_MATERIAL.values());
    }

    static List<Offer> allOffers() {
        List<Offer> all = new ArrayList<>();
        BY_CATEGORY.values().forEach(all::addAll);
        return all;
    }

    static List<Category> categories() {
        return List.of(Category.values());
    }

    private static Map<Category, List<Offer>> buildShop() {
        Map<Category, List<Offer>> catalog = new EnumMap<>(Category.class);
        catalog.put(Category.BUILDING, List.of(
                offer("COBBLESTONE", 64, 4_500),
                offer("STONE", 64, 5_500),
                offer("SMOOTH_STONE", 64, 6_000),
                offer("STONE_BRICKS", 64, 6_500),
                offer("COBBLED_DEEPSLATE", 64, 5_500),
                offer("DEEPSLATE", 64, 6_000),
                offer("DEEPSLATE_BRICKS", 64, 7_500),
                offer("DEEPSLATE_TILES", 64, 7_500),
                offer("DIRT", 64, 3_500),
                offer("COARSE_DIRT", 64, 5_000),
                offer("MUD", 64, 5_000),
                offer("MUD_BRICKS", 64, 7_000),
                offer("SAND", 64, 7_500),
                offer("RED_SAND", 64, 9_000),
                offer("SANDSTONE", 64, 8_000),
                offer("RED_SANDSTONE", 64, 9_500),
                offer("GRAVEL", 64, 6_000),
                offer("GLASS", 64, 9_000),
                offer("BRICKS", 64, 10_500),
                offer("TERRACOTTA", 64, 9_000),
                offer("CALCITE", 64, 9_000),
                offer("TUFF", 64, 6_000),
                offer("TUFF_BRICKS", 64, 7_500),
                offer("DRIPSTONE_BLOCK", 64, 7_500),
                offer("MOSS_BLOCK", 64, 7_500),
                offer("SNOW_BLOCK", 64, 3_500),
                offer("ICE", 64, 7_500),
                offer("PACKED_ICE", 64, 12_000)
        ));
        catalog.put(Category.WOOD, List.of(
                offer("OAK_LOG", 64, 9_000),
                offer("SPRUCE_LOG", 64, 9_000),
                offer("BIRCH_LOG", 64, 9_000),
                offer("JUNGLE_LOG", 64, 10_500),
                offer("ACACIA_LOG", 64, 10_500),
                offer("DARK_OAK_LOG", 64, 10_500),
                offer("MANGROVE_LOG", 64, 12_000),
                offer("CHERRY_LOG", 64, 12_000),
                offer("CRIMSON_STEM", 64, 13_500),
                offer("WARPED_STEM", 64, 13_500)
        ));
        catalog.put(Category.COLORED, concat(
                dyed("WOOL", 64, 7_500),
                dyed("CONCRETE", 64, 12_000),
                dyed("CONCRETE_POWDER", 64, 9_000),
                dyed("TERRACOTTA", 64, 10_500),
                dyed("STAINED_GLASS", 64, 10_500),
                dyed("CARPET", 64, 4_500)
        ));
        catalog.put(Category.FARMING, List.of(
                offer("BONE", 1, 300),
                offer("BONE_MEAL", 16, 3_500),
                offer("WHEAT", 16, 3_000),
                offer("CARROT", 16, 3_000),
                offer("POTATO", 16, 3_000),
                offer("SUGAR_CANE", 16, 6_000),
                offer("CACTUS", 16, 5_500),
                offer("PUMPKIN", 16, 6_000),
                offer("MELON", 16, 5_500),
                offer("BAMBOO", 32, 3_500),
                offer("LEATHER", 16, 6_000)
        ));
        catalog.put(Category.MINERALS, List.of(
                offer("COAL", 16, 4_500),
                offer("COPPER_INGOT", 16, 5_500),
                offer("IRON_INGOT", 16, 12_000),
                offer("GOLD_INGOT", 16, 18_000),
                offer("REDSTONE", 16, 3_500),
                offer("LAPIS_LAZULI", 16, 4_500),
                offer("QUARTZ", 16, 5_500),
                offer("EMERALD", 1, 1_500),
                offer("DIAMOND", 1, 6_000)
        ));
        catalog.put(Category.MOBS, List.of(
                offer("ROTTEN_FLESH", 16, 2_000),
                offer("STRING", 16, 3_500),
                offer("SPIDER_EYE", 16, 3_000),
                offer("GUNPOWDER", 16, 7_500),
                offer("SLIME_BALL", 16, 5_500),
                offer("ENDER_PEARL", 4, 3_000),
                offer("BLAZE_ROD", 4, 4_500),
                offer("GHAST_TEAR", 1, 3_000)
        ));
        catalog.put(Category.REDSTONE, List.of(
                offer("REDSTONE", 16, 3_500),
                offer("REDSTONE_TORCH", 16, 4_500),
                offer("REPEATER", 8, 4_500),
                offer("COMPARATOR", 8, 6_000),
                offer("PISTON", 8, 6_000),
                offer("STICKY_PISTON", 8, 9_000),
                offer("OBSERVER", 8, 7_500),
                offer("DISPENSER", 8, 6_000),
                offer("DROPPER", 8, 4_500),
                offer("HOPPER", 8, 12_000)
        ));
        catalog.put(Category.FOOD, List.of(
                offer("BREAD", 16, 3_000),
                offer("COOKED_BEEF", 16, 4_500),
                offer("COOKED_PORKCHOP", 16, 4_500),
                offer("COOKED_CHICKEN", 16, 3_500),
                offer("COOKED_MUTTON", 16, 3_500),
                offer("COOKED_COD", 16, 3_500),
                offer("COOKED_SALMON", 16, 4_000),
                offer("BAKED_POTATO", 16, 3_000),
                offer("GOLDEN_CARROT", 16, 9_000),
                offer("PUMPKIN_PIE", 16, 4_500),
                offer("COOKIE", 16, 2_500)
        ));
        catalog.put(Category.OCEAN, List.of(
                offer("PRISMARINE", 64, 9_000),
                offer("PRISMARINE_BRICKS", 64, 10_500),
                offer("DARK_PRISMARINE", 64, 12_000),
                offer("SEA_LANTERN", 16, 7_500),
                offer("INK_SAC", 16, 3_000),
                offer("GLOW_INK_SAC", 16, 4_500),
                offer("SEA_PICKLE", 16, 4_500)
        ));
        catalog.put(Category.NETHER, List.of(
                offer("NETHERRACK", 64, 3_500),
                offer("SOUL_SAND", 64, 7_500),
                offer("SOUL_SOIL", 64, 7_500),
                offer("BASALT", 64, 6_000),
                offer("BLACKSTONE", 64, 7_500),
                offer("GLOWSTONE", 16, 6_000),
                offer("MAGMA_BLOCK", 16, 4_500),
                offer("NETHER_BRICKS", 64, 9_000),
                offer("NETHER_WART", 16, 4_500)
        ));
        catalog.put(Category.END, List.of(
                offer("END_STONE", 64, 7_500),
                offer("END_STONE_BRICKS", 64, 9_000),
                offer("PURPUR_BLOCK", 64, 12_000),
                offer("PURPUR_PILLAR", 64, 12_000),
                offer("CHORUS_FRUIT", 16, 4_500),
                offer("POPPED_CHORUS_FRUIT", 16, 6_000)
        ));
        catalog.put(Category.DECORATION, concat(
                List.of(
                        offer("DANDELION", 16, 3_000),
                        offer("POPPY", 16, 3_000),
                        offer("BLUE_ORCHID", 16, 3_000),
                        offer("ALLIUM", 16, 3_000),
                        offer("AZURE_BLUET", 16, 3_000),
                        offer("RED_TULIP", 16, 3_000),
                        offer("ORANGE_TULIP", 16, 3_000),
                        offer("WHITE_TULIP", 16, 3_000),
                        offer("PINK_TULIP", 16, 3_000),
                        offer("OXEYE_DAISY", 16, 3_000),
                        offer("CORNFLOWER", 16, 3_000),
                        offer("LILY_OF_THE_VALLEY", 16, 3_000),
                        offer("CANDLE", 16, 4_500)
                ),
                dyed("CANDLE", 16, 4_500),
                List.of(
                        offer("ITEM_FRAME", 16, 4_500),
                        offer("GLOW_ITEM_FRAME", 16, 6_000),
                        offer("PAINTING", 8, 3_000),
                        offer("ARMOR_STAND", 4, 3_000),
                        offer("LANTERN", 16, 6_000),
                        offer("SOUL_LANTERN", 16, 7_500),
                        offer("FLOWER_POT", 16, 3_000),
                        offer("CHAIN", 16, 4_500),
                        offer("BOOKSHELF", 16, 7_500)
                )
        ));
        catalog.put(Category.TRANSPORT, concat(
                List.of(
                        offer("RAIL", 64, 7_500),
                        offer("POWERED_RAIL", 16, 7_500),
                        offer("DETECTOR_RAIL", 16, 6_000),
                        offer("ACTIVATOR_RAIL", 16, 6_000),
                        offer("MINECART", 1, 3_000),
                        offer("CHEST_MINECART", 1, 4_000)
                ),
                boats(1, 1_500)
        ));
        catalog.put(Category.MISC, List.of(
                offer("PAPER", 16, 2_500),
                offer("BOOK", 16, 4_500),
                offer("GLASS_BOTTLE", 16, 2_500),
                offer("BUCKET", 1, 1_800),
                offer("WATER_BUCKET", 1, 2_400),
                offer("LAVA_BUCKET", 1, 3_500),
                offer("SNOWBALL", 16, 1_500),
                offer("CLAY_BALL", 16, 3_000),
                offer("FLINT", 16, 3_000),
                offer("TORCH", 64, 4_500)
        ));
        return catalog;
    }

    private static Map<String, SellQuote> buildSell() {
        Map<String, SellQuote> sell = new LinkedHashMap<>();
        putSell(sell, "SUGAR_CANE", 200);
        putSell(sell, "CACTUS", 180);
        putSell(sell, "KELP", 120);
        putSell(sell, "DRIED_KELP", 150);
        putSell(sell, "BAMBOO", 30);
        putSell(sell, "SWEET_BERRIES", 90);
        putSell(sell, "GLOW_BERRIES", 100);
        putSell(sell, "WHEAT", 70);
        putSell(sell, "CARROT", 70);
        putSell(sell, "POTATO", 70);
        putSell(sell, "BEETROOT", 90);
        putSell(sell, "PUMPKIN", 150);
        putSell(sell, "MELON", 120);
        putSell(sell, "MELON_SLICE", 20);
        putSell(sell, "COCOA_BEANS", 90);
        putSell(sell, "NETHER_WART", 150);
        putSell(sell, "HONEYCOMB", 120);
        putSell(sell, "HONEY_BOTTLE", 180);
        putSell(sell, "BROWN_MUSHROOM", 90);
        putSell(sell, "RED_MUSHROOM", 90);
        putSell(sell, "BONE", 120);
        putSell(sell, "BONE_BLOCK", 1_100);
        putSell(sell, "ROTTEN_FLESH", 50);
        putSell(sell, "STRING", 90);
        putSell(sell, "SPIDER_EYE", 70);
        putSell(sell, "GUNPOWDER", 200);
        putSell(sell, "SLIME_BALL", 120);
        putSell(sell, "ARROW", 30);
        putSell(sell, "BLAZE_ROD", 300);
        putSell(sell, "BLAZE_POWDER", 130);
        putSell(sell, "MAGMA_CREAM", 150);
        putSell(sell, "ENDER_PEARL", 180);
        putSell(sell, "PHANTOM_MEMBRANE", 240);
        putSell(sell, "LEATHER", 150);
        putSell(sell, "FEATHER", 60);
        putSell(sell, "BEEF", 60);
        putSell(sell, "PORKCHOP", 60);
        putSell(sell, "CHICKEN", 50);
        putSell(sell, "MUTTON", 50);
        putSell(sell, "EGG", 30);
        putSell(sell, "RABBIT_HIDE", 70);
        putSell(sell, "RABBIT_FOOT", 180);
        putSell(sell, "WHITE_WOOL", 60);
        putSell(sell, "COBBLESTONE", 20);
        putSell(sell, "STONE", 30);
        putSell(sell, "BASALT", 60);
        putSell(sell, "OBSIDIAN", 120);
        putSell(sell, "CLAY_BALL", 50);
        putSell(sell, "SNOW_BLOCK", 20);
        putSell(sell, "OAK_LOG", 70);
        putSell(sell, "SPRUCE_LOG", 70);
        putSell(sell, "BIRCH_LOG", 70);
        putSell(sell, "JUNGLE_LOG", 80);
        putSell(sell, "ACACIA_LOG", 80);
        putSell(sell, "DARK_OAK_LOG", 80);
        putSell(sell, "MANGROVE_LOG", 100);
        putSell(sell, "CHERRY_LOG", 100);
        putSell(sell, "CRIMSON_STEM", 110);
        putSell(sell, "WARPED_STEM", 110);
        putSell(sell, "COAL", 90);
        putSell(sell, "RAW_COPPER", 70);
        putSell(sell, "RAW_IRON", 180);
        putSell(sell, "RAW_GOLD", 270);
        putSell(sell, "REDSTONE", 50);
        putSell(sell, "LAPIS_LAZULI", 60);
        putSell(sell, "QUARTZ", 90);
        putSell(sell, "DIAMOND", 900);
        putSell(sell, "EMERALD", 240);
        putSell(sell, "PRISMARINE_SHARD", 90);
        putSell(sell, "PRISMARINE_CRYSTALS", 120);
        putSell(sell, "INK_SAC", 60);
        putSell(sell, "GLOW_INK_SAC", 90);
        putSell(sell, "SEA_PICKLE", 150);
        return Map.copyOf(sell);
    }

    private static Map<String, Offer> indexBuy() {
        Map<String, Offer> index = new LinkedHashMap<>();
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

    private static void putSell(Map<String, SellQuote> sell, String material, long unit) {
        sell.put(material, new SellQuote(material, unit));
    }

    @SafeVarargs
    private static List<Offer> concat(List<Offer>... parts) {
        List<Offer> all = new ArrayList<>();
        for (List<Offer> part : parts) {
            all.addAll(part);
        }
        return List.copyOf(all);
    }

    private static List<Offer> dyed(String suffix, int amount, long price) {
        List<Offer> offers = new ArrayList<>();
        for (String dye : DYES) {
            offers.add(offer(dye + "_" + suffix, amount, price));
        }
        return List.copyOf(offers);
    }

    private static List<Offer> boats(int amount, long price) {
        return List.of(
                offer("OAK_BOAT", amount, price),
                offer("SPRUCE_BOAT", amount, price),
                offer("BIRCH_BOAT", amount, price),
                offer("JUNGLE_BOAT", amount, price),
                offer("ACACIA_BOAT", amount, price),
                offer("DARK_OAK_BOAT", amount, price),
                offer("MANGROVE_BOAT", amount, price),
                offer("CHERRY_BOAT", amount, price)
        );
    }
}
