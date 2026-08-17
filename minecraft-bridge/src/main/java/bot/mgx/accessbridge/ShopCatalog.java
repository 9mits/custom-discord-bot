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

        long unitPrice() {
            return Math.max(1L, price / amount);
        }

        long costOf(int orders) {
            if (orders <= 0) {
                return 0L;
            }
            return Math.multiplyExact(price, orders);
        }

        long costOfItems(int items) {
            if (items <= 0) {
                return 0L;
            }
            return Math.multiplyExact(unitPrice(), items);
        }

        int maxOrders(long balance, int inventorySpace) {
            if (price <= 0L || amount <= 0 || balance < price || inventorySpace < amount) {
                return 0;
            }
            long affordable = balance / price;
            int bySpace = inventorySpace / amount;
            return (int) Math.min(bySpace, Math.min(Integer.MAX_VALUE, affordable));
        }

        int maxItems(long balance, int inventorySpace) {
            long unit = unitPrice();
            if (unit <= 0L || inventorySpace <= 0 || balance < unit) {
                return 0;
            }
            return (int) Math.min(inventorySpace, Math.min(Integer.MAX_VALUE, balance / unit));
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

    static List<String> materialsOnBothCounters() {
        List<String> both = new ArrayList<>();
        for (String material : SELL_BY_MATERIAL.keySet()) {
            if (BUY_BY_MATERIAL.containsKey(material)) {
                both.add(material);
            }
        }
        return both;
    }

    private static Map<Category, List<Offer>> buildShop() {
        Map<Category, List<Offer>> catalog = new EnumMap<>(Category.class);
        catalog.put(Category.BUILDING, List.of(
                offer("COBBLESTONE", 64, 450),
                offer("STONE", 64, 630),
                offer("DEEPSLATE", 64, 720),
                offer("ANDESITE", 64, 720),
                offer("DIORITE", 64, 720),
                offer("GRANITE", 64, 720),
                offer("TUFF", 64, 800),
                offer("DIRT", 64, 400),
                offer("GRASS_BLOCK", 64, 800),
                offer("SAND", 64, 720),
                offer("RED_SAND", 64, 1_000),
                offer("GRAVEL", 64, 540),
                offer("GLASS", 64, 1_200),
                offer("CLAY", 64, 900),
                offer("BRICKS", 64, 2_000),
                offer("TERRACOTTA", 64, 1_800),
                offer("MUD", 64, 800),
                offer("MUD_BRICKS", 64, 1_800),
                offer("MOSS_BLOCK", 64, 1_800),
                offer("SNOW_BLOCK", 64, 600),
                offer("CALCITE", 64, 2_500),
                offer("DRIPSTONE_BLOCK", 64, 1_800),
                offer("OBSIDIAN", 64, 12_000),
                offer("CRYING_OBSIDIAN", 64, 28_000),
                offer("AMETHYST_BLOCK", 64, 4_500)
        ));
        catalog.put(Category.WOOD, concat(
                wooded("LOG", 64, 2_000),
                List.of(offer("CRIMSON_STEM", 64, 2_000), offer("WARPED_STEM", 64, 2_000)),
                wooded("PLANKS", 64, 900),
                List.of(offer("CRIMSON_PLANKS", 64, 900), offer("WARPED_PLANKS", 64, 900)),
                wooded("LEAVES", 64, 1_000)
        ));
        catalog.put(Category.COLORED, concat(
                dyed("WOOL", 64, 4_500),
                dyed("TERRACOTTA", 64, 6_000),
                dyed("CONCRETE_POWDER", 64, 6_000)
        ));
        catalog.put(Category.FARMING, List.of(
                offer("WHEAT", 16, 1_120),
                offer("WHEAT_SEEDS", 16, 350),
                offer("CARROT", 16, 1_010),
                offer("POTATO", 16, 1_010),
                offer("BEETROOT", 16, 1_230),
                offer("BEETROOT_SEEDS", 16, 400),
                offer("PUMPKIN", 16, 1_790),
                offer("MELON", 16, 2_020),
                offer("SUGAR_CANE", 16, 1_790),
                offer("CACTUS", 16, 1_570),
                offer("BAMBOO", 32, 1_120),
                offer("COCOA_BEANS", 16, 1_230),
                offer("NETHER_WART", 16, 2_020),
                offer("KELP", 16, 560),
                offer("SWEET_BERRIES", 16, 670),
                offer("BONE", 1, 15),
                offer("BONE_MEAL", 16, 60),
                offer("LEATHER", 16, 1_570),
                offer("WHITE_WOOL", 16, 1_120),
                offer("OAK_SAPLING", 16, 700),
                offer("SPRUCE_SAPLING", 16, 700),
                offer("BIRCH_SAPLING", 16, 700)
        ));
        catalog.put(Category.FOOD, List.of(
                offer("BREAD", 16, 1_400),
                offer("COOKED_BEEF", 16, 1_400),
                offer("COOKED_PORKCHOP", 16, 1_400),
                offer("COOKED_CHICKEN", 16, 1_010),
                offer("COOKED_MUTTON", 16, 1_230),
                offer("BAKED_POTATO", 16, 1_010),
                offer("PUMPKIN_PIE", 16, 1_800),
                offer("COOKIE", 16, 900),
                offer("GOLDEN_CARROT", 16, 5_000)
        ));
        catalog.put(Category.MINERALS, List.of(
                offer("COAL", 16, 900),
                offer("COPPER_INGOT", 16, 1_570),
                offer("IRON_INGOT", 16, 2_690),
                offer("GOLD_INGOT", 16, 3_580),
                offer("REDSTONE", 16, 670),
                offer("LAPIS_LAZULI", 16, 900),
                offer("QUARTZ", 16, 1_230),
                offer("AMETHYST_SHARD", 16, 1_120)
        ));
        catalog.put(Category.REDSTONE, List.of(
                offer("REDSTONE", 16, 670),
                offer("REDSTONE_TORCH", 16, 900),
                offer("REPEATER", 8, 1_400),
                offer("COMPARATOR", 8, 1_800),
                offer("PISTON", 8, 2_400),
                offer("STICKY_PISTON", 8, 3_600),
                offer("OBSERVER", 8, 2_800),
                offer("HOPPER", 8, 4_800),
                offer("DISPENSER", 8, 2_400),
                offer("DROPPER", 8, 1_600)
        ));
        catalog.put(Category.NETHER, List.of(
                offer("NETHERRACK", 64, 360),
                offer("BLACKSTONE", 64, 810),
                offer("BASALT", 64, 630),
                offer("NETHER_BRICKS", 64, 1_400),
                offer("SOUL_SAND", 64, 2_400),
                offer("QUARTZ_BLOCK", 64, 3_200),
                offer("GLOWSTONE", 64, 4_800),
                offer("MAGMA_BLOCK", 64, 2_400)
        ));
        catalog.put(Category.OCEAN, concat(
                List.of(
                        offer("PRISMARINE", 64, 8_000),
                        offer("PRISMARINE_BRICKS", 64, 10_000),
                        offer("DARK_PRISMARINE", 64, 14_000),
                        offer("SEA_LANTERN", 64, 28_000),
                        offer("PACKED_ICE", 64, 8_000),
                        offer("BLUE_ICE", 64, 36_000)
                ),
                List.of(
                        offer("TUBE_CORAL_BLOCK", 64, 22_000),
                        offer("BRAIN_CORAL_BLOCK", 64, 22_000),
                        offer("BUBBLE_CORAL_BLOCK", 64, 22_000),
                        offer("FIRE_CORAL_BLOCK", 64, 22_000),
                        offer("HORN_CORAL_BLOCK", 64, 22_000)
                )
        ));
        catalog.put(Category.DECORATION, List.of(
                offer("LANTERN", 64, 4_000),
                offer("CHAIN", 64, 4_500),
                offer("ITEM_FRAME", 64, 3_000),
                offer("FLOWER_POT", 64, 1_500),
                offer("PAINTING", 64, 2_000)
        ));
        catalog.put(Category.UTILITY, List.of(
                offer("BUCKET", 1, 400),
                offer("WATER_BUCKET", 1, 600),
                offer("LAVA_BUCKET", 1, 1_200),
                offer("TORCH", 64, 700),
                offer("ENDER_PEARL", 4, 3_200),
                offer("LEAD", 1, 1_500),
                offer("NAME_TAG", 1, 8_000),
                offer("SADDLE", 1, 6_000),
                offer("EXPERIENCE_BOTTLE", 1, 600),
                offer("GLASS_BOTTLE", 16, 700),
                offer("PAPER", 16, 1_000),
                offer("BOOK", 16, 1_600)
        ));
        return catalog;
    }

    private static Map<String, SellQuote> buildSell() {
        Map<String, SellQuote> sell = new LinkedHashMap<>();
        putSell(sell, "WHEAT", 50);
        putSell(sell, "CARROT", 45);
        putSell(sell, "POTATO", 45);
        putSell(sell, "BEETROOT", 55);
        putSell(sell, "PUMPKIN", 80);
        putSell(sell, "MELON", 90);
        putSell(sell, "SUGAR_CANE", 80);
        putSell(sell, "CACTUS", 70);
        putSell(sell, "BAMBOO", 25);
        putSell(sell, "COCOA_BEANS", 55);
        putSell(sell, "NETHER_WART", 90);
        putSell(sell, "KELP", 25);
        putSell(sell, "DRIED_KELP", 40);
        putSell(sell, "SWEET_BERRIES", 30);
        putSell(sell, "GLOW_BERRIES", 35);
        putSell(sell, "HONEYCOMB", 70);
        putSell(sell, "HONEY_BOTTLE", 140);
        for (String dye : DYES) {
            putSell(sell, dye + "_WOOL", 50);
        }
        putSell(sell, "LEATHER", 70);
        putSell(sell, "FEATHER", 25);
        putSell(sell, "BEEF", 50);
        putSell(sell, "PORKCHOP", 50);
        putSell(sell, "CHICKEN", 40);
        putSell(sell, "MUTTON", 45);
        putSell(sell, "ROTTEN_FLESH", 15);
        putSell(sell, "BONE", 10);
        putSell(sell, "STRING", 40);
        putSell(sell, "SPIDER_EYE", 30);
        putSell(sell, "GUNPOWDER", 90);
        putSell(sell, "COAL", 40);
        putSell(sell, "REDSTONE", 30);
        putSell(sell, "LAPIS_LAZULI", 40);
        putSell(sell, "QUARTZ", 55);
        putSell(sell, "COPPER_INGOT", 70);
        putSell(sell, "IRON_INGOT", 120);
        putSell(sell, "GOLD_INGOT", 160);
        putSell(sell, "AMETHYST_SHARD", 50);
        putSell(sell, "FLINT", 12);
        putSell(sell, "CLAY_BALL", 10);
        putSell(sell, "COBBLESTONE", 5);
        putSell(sell, "STONE", 7);
        putSell(sell, "COBBLED_DEEPSLATE", 8);
        putSell(sell, "ANDESITE", 8);
        putSell(sell, "DIORITE", 8);
        putSell(sell, "GRANITE", 8);
        putSell(sell, "SAND", 8);
        putSell(sell, "GRAVEL", 6);
        putSell(sell, "NETHERRACK", 4);
        putSell(sell, "BASALT", 7);
        putSell(sell, "BLACKSTONE", 9);
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
