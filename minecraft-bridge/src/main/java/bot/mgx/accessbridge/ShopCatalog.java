package bot.mgx.accessbridge;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Shop is building and convenience. Sell is Donut-style bulk farming.
 *
 * <p>Diamonds, emeralds, netherite, totems, enchanted books and End loot are
 * not bought or sold here — those go on the auction house.
 */
final class ShopCatalog {
    enum Category {
        BUILDING("Building", "STONE"),
        WOOD("Wood", "OAK_LOG"),
        COLORED("Color", "WHITE_WOOL"),
        FARMING("Farming", "WHEAT"),
        FOOD("Food", "BREAD"),
        MINERALS("Minerals", "IRON_INGOT"),
        REDSTONE("Redstone", "REDSTONE"),
        NETHER("Nether", "NETHERRACK"),
        OCEAN("Ocean", "PRISMARINE"),
        DECORATION("Decoration", "LANTERN"),
        UTILITY("Utility", "WATER_BUCKET");

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
    private static final String[] WOOD = {
            "OAK", "SPRUCE", "BIRCH", "JUNGLE", "ACACIA", "DARK_OAK", "MANGROVE", "CHERRY"
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
                offer("COBBLESTONE", 64, 2_000),
                offer("STONE", 64, 3_000),
                offer("DEEPSLATE", 64, 4_000),
                offer("ANDESITE", 64, 4_000),
                offer("DIORITE", 64, 4_000),
                offer("GRANITE", 64, 4_000),
                offer("TUFF", 64, 5_000),
                offer("DIRT", 64, 2_000),
                offer("GRASS_BLOCK", 64, 6_000),
                offer("SAND", 64, 7_500),
                offer("RED_SAND", 64, 12_000),
                offer("GRAVEL", 64, 6_000),
                offer("GLASS", 64, 10_000),
                offer("CLAY", 64, 15_000),
                offer("BRICKS", 64, 18_000),
                offer("TERRACOTTA", 64, 15_000),
                offer("MUD", 64, 6_000),
                offer("MUD_BRICKS", 64, 15_000),
                offer("MOSS_BLOCK", 64, 15_000),
                offer("SNOW_BLOCK", 64, 5_000),
                offer("CALCITE", 64, 25_000),
                offer("DRIPSTONE_BLOCK", 64, 15_000),
                offer("OBSIDIAN", 64, 150_000),
                offer("CRYING_OBSIDIAN", 64, 400_000),
                offer("AMETHYST_BLOCK", 64, 100_000)
        ));
        catalog.put(Category.WOOD, concat(
                wooded("LOG", 64, 18_000),
                List.of(offer("CRIMSON_STEM", 64, 18_000), offer("WARPED_STEM", 64, 18_000)),
                wooded("PLANKS", 64, 8_000),
                List.of(offer("CRIMSON_PLANKS", 64, 8_000), offer("WARPED_PLANKS", 64, 8_000)),
                wooded("LEAVES", 64, 10_000)
        ));
        catalog.put(Category.COLORED, concat(
                dyed("WOOL", 64, 15_000),
                dyed("TERRACOTTA", 64, 20_000),
                dyed("CONCRETE_POWDER", 64, 20_000)
        ));
        catalog.put(Category.FARMING, List.of(
                offer("WHEAT", 16, 5_000),
                offer("WHEAT_SEEDS", 16, 2_000),
                offer("CARROT", 16, 4_500),
                offer("POTATO", 16, 4_500),
                offer("BEETROOT", 16, 6_000),
                offer("BEETROOT_SEEDS", 16, 2_500),
                offer("PUMPKIN", 16, 10_000),
                offer("MELON", 16, 12_000),
                offer("SUGAR_CANE", 16, 8_000),
                offer("CACTUS", 16, 8_000),
                offer("BAMBOO", 32, 4_000),
                offer("COCOA_BEANS", 16, 6_000),
                offer("NETHER_WART", 16, 12_000),
                offer("KELP", 16, 3_000),
                offer("SWEET_BERRIES", 16, 3_000),
                offer("BONE", 1, 350),
                offer("BONE_MEAL", 16, 4_000),
                offer("LEATHER", 16, 14_000),
                offer("WHITE_WOOL", 16, 6_000),
                offer("OAK_SAPLING", 16, 4_000),
                offer("SPRUCE_SAPLING", 16, 4_000),
                offer("BIRCH_SAPLING", 16, 4_000)
        ));
        catalog.put(Category.FOOD, List.of(
                offer("BREAD", 16, 5_000),
                offer("COOKED_BEEF", 16, 10_000),
                offer("COOKED_PORKCHOP", 16, 10_000),
                offer("COOKED_CHICKEN", 16, 7_000),
                offer("COOKED_MUTTON", 16, 8_000),
                offer("BAKED_POTATO", 16, 5_000),
                offer("PUMPKIN_PIE", 16, 8_000),
                offer("COOKIE", 16, 3_500),
                offer("GOLDEN_CARROT", 16, 25_000)
        ));
        catalog.put(Category.MINERALS, List.of(
                offer("COAL", 16, 8_000),
                offer("COPPER_INGOT", 16, 12_000),
                offer("IRON_INGOT", 16, 25_000),
                offer("GOLD_INGOT", 16, 40_000),
                offer("REDSTONE", 16, 6_000),
                offer("LAPIS_LAZULI", 16, 8_000),
                offer("QUARTZ", 16, 12_000),
                offer("AMETHYST_SHARD", 16, 10_000)
        ));
        catalog.put(Category.REDSTONE, List.of(
                offer("REDSTONE", 16, 6_000),
                offer("REDSTONE_TORCH", 16, 8_000),
                offer("REPEATER", 8, 12_000),
                offer("COMPARATOR", 8, 16_000),
                offer("PISTON", 8, 22_000),
                offer("STICKY_PISTON", 8, 35_000),
                offer("OBSERVER", 8, 25_000),
                offer("HOPPER", 8, 45_000),
                offer("DISPENSER", 8, 22_000),
                offer("DROPPER", 8, 14_000)
        ));
        catalog.put(Category.NETHER, List.of(
                offer("NETHERRACK", 64, 3_000),
                offer("BLACKSTONE", 64, 12_000),
                offer("BASALT", 64, 10_000),
                offer("NETHER_BRICKS", 64, 12_000),
                offer("SOUL_SAND", 64, 25_000),
                offer("QUARTZ_BLOCK", 64, 35_000),
                offer("GLOWSTONE", 64, 50_000),
                offer("MAGMA_BLOCK", 64, 25_000)
        ));
        catalog.put(Category.OCEAN, concat(
                List.of(
                        offer("PRISMARINE", 64, 150_000),
                        offer("PRISMARINE_BRICKS", 64, 200_000),
                        offer("DARK_PRISMARINE", 64, 275_000),
                        offer("SEA_LANTERN", 64, 750_000),
                        offer("PACKED_ICE", 64, 150_000),
                        offer("BLUE_ICE", 64, 900_000)
                ),
                List.of(
                        offer("TUBE_CORAL_BLOCK", 64, 500_000),
                        offer("BRAIN_CORAL_BLOCK", 64, 500_000),
                        offer("BUBBLE_CORAL_BLOCK", 64, 500_000),
                        offer("FIRE_CORAL_BLOCK", 64, 500_000),
                        offer("HORN_CORAL_BLOCK", 64, 500_000)
                )
        ));
        catalog.put(Category.DECORATION, List.of(
                offer("LANTERN", 64, 75_000),
                offer("CHAIN", 64, 80_000),
                offer("ITEM_FRAME", 64, 50_000),
                offer("FLOWER_POT", 64, 25_000),
                offer("PAINTING", 64, 30_000)
        ));
        catalog.put(Category.UTILITY, List.of(
                offer("BUCKET", 1, 3_000),
                offer("WATER_BUCKET", 1, 4_000),
                offer("LAVA_BUCKET", 1, 8_000),
                offer("TORCH", 64, 8_000),
                offer("ENDER_PEARL", 4, 20_000),
                offer("LEAD", 1, 12_000),
                offer("NAME_TAG", 1, 75_000),
                offer("SADDLE", 1, 50_000),
                offer("EXPERIENCE_BOTTLE", 1, 6_000),
                offer("GLASS_BOTTLE", 16, 4_000),
                offer("PAPER", 16, 5_000),
                offer("BOOK", 16, 9_000)
        ));
        return catalog;
    }

    private static Map<String, SellQuote> buildSell() {
        Map<String, SellQuote> sell = new LinkedHashMap<>();
        putSell(sell, "WHEAT", 75);
        putSell(sell, "CARROT", 60);
        putSell(sell, "POTATO", 60);
        putSell(sell, "BEETROOT", 90);
        putSell(sell, "PUMPKIN", 150);
        putSell(sell, "MELON", 175);
        putSell(sell, "SUGAR_CANE", 100);
        putSell(sell, "CACTUS", 110);
        putSell(sell, "BAMBOO", 20);
        putSell(sell, "COCOA_BEANS", 75);
        putSell(sell, "NETHER_WART", 150);
        putSell(sell, "KELP", 20);
        putSell(sell, "DRIED_KELP", 45);
        putSell(sell, "SWEET_BERRIES", 35);
        putSell(sell, "GLOW_BERRIES", 40);
        putSell(sell, "HONEYCOMB", 100);
        putSell(sell, "HONEY_BOTTLE", 225);
        for (String dye : DYES) {
            putSell(sell, dye + "_WOOL", 90);
        }
        putSell(sell, "LEATHER", 200);
        putSell(sell, "FEATHER", 50);
        putSell(sell, "BEEF", 90);
        putSell(sell, "PORKCHOP", 90);
        putSell(sell, "CHICKEN", 60);
        putSell(sell, "MUTTON", 75);
        putSell(sell, "ROTTEN_FLESH", 25);
        putSell(sell, "BONE", 80);
        putSell(sell, "STRING", 75);
        putSell(sell, "SPIDER_EYE", 55);
        putSell(sell, "GUNPOWDER", 200);
        putSell(sell, "COAL", 75);
        putSell(sell, "REDSTONE", 45);
        putSell(sell, "LAPIS_LAZULI", 60);
        putSell(sell, "QUARTZ", 100);
        putSell(sell, "COPPER_INGOT", 125);
        putSell(sell, "IRON_INGOT", 275);
        putSell(sell, "GOLD_INGOT", 375);
        putSell(sell, "AMETHYST_SHARD", 80);
        putSell(sell, "FLINT", 20);
        putSell(sell, "CLAY_BALL", 15);
        putSell(sell, "COBBLESTONE", 4);
        putSell(sell, "STONE", 6);
        putSell(sell, "COBBLED_DEEPSLATE", 7);
        putSell(sell, "ANDESITE", 7);
        putSell(sell, "DIORITE", 7);
        putSell(sell, "GRANITE", 7);
        putSell(sell, "SAND", 6);
        putSell(sell, "GRAVEL", 5);
        putSell(sell, "NETHERRACK", 3);
        putSell(sell, "BASALT", 6);
        putSell(sell, "BLACKSTONE", 8);
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

    private static List<Offer> wooded(String suffix, int amount, long price) {
        List<Offer> offers = new ArrayList<>();
        for (String wood : WOOD) {
            offers.add(offer(wood + "_" + suffix, amount, price));
        }
        return List.copyOf(offers);
    }
}
