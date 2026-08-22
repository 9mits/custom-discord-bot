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
    /**
     * One shelf per kind of thing, rather than a few shelves each holding several.
     *
     * <p>Stone, earth, glass, wool, concrete and terracotta used to share two
     * categories between them, so finding a block meant paging past thirty unrelated
     * ones. The hub has room for twenty-one.
     */
    enum Category {
        STONE("Stone", "STONE"),
        EARTH("Earth", "DIRT"),
        WOOD("Wood", "OAK_LOG"),
        GLASS("Glass", "GLASS"),
        WOOL("Wool", "WHITE_WOOL"),
        CONCRETE("Concrete", "LIGHT_BLUE_CONCRETE"),
        TERRACOTTA("Terracotta", "ORANGE_TERRACOTTA"),
        NETHER("Nether", "NETHERRACK"),
        OCEAN("Ocean", "PRISMARINE"),
        FARMING("Farming", "WHEAT"),
        FOOD("Food", "BREAD"),
        MINERALS("Minerals", "IRON_INGOT"),
        REDSTONE("Redstone", "REDSTONE"),
        STORAGE("Storage", "CHEST"),
        TRANSPORT("Transport", "MINECART"),
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

        long costOfItems(int items) {
            if (items <= 0) {
                return 0L;
            }
            return Math.multiplyExact(unitPrice(), items);
        }

        int maxItems(long balance, int inventorySpace) {
            long unit = unitPrice();
            if (unit <= 0L || inventorySpace <= 0 || balance < unit) {
                return 0;
            }
            return (int) Math.min(inventorySpace, Math.min(Integer.MAX_VALUE, balance / unit));
        }
    }

    /** {@code group} is only a heading for the price list; it does not affect pricing. */
    record SellQuote(String material, String group, long unitPrice) {
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
            "OAK", "SPRUCE", "BIRCH", "JUNGLE", "ACACIA", "DARK_OAK", "MANGROVE", "CHERRY",
            "PALE_OAK"
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
        catalog.put(Category.STONE, List.of(
                offer("COBBLESTONE", 64, 450),
                offer("STONE", 64, 630),
                offer("SMOOTH_STONE", 64, 800),
                offer("STONE_BRICKS", 64, 800),
                offer("MOSSY_COBBLESTONE", 64, 900),
                offer("MOSSY_STONE_BRICKS", 64, 1_000),
                offer("CRACKED_STONE_BRICKS", 64, 900),
                offer("CHISELED_STONE_BRICKS", 64, 1_000),
                offer("ANDESITE", 64, 720),
                offer("POLISHED_ANDESITE", 64, 800),
                offer("DIORITE", 64, 720),
                offer("POLISHED_DIORITE", 64, 800),
                offer("GRANITE", 64, 720),
                offer("POLISHED_GRANITE", 64, 800),
                offer("DEEPSLATE", 64, 720),
                // On the sell counter at 8, so this has to stay inside the 1x-2x band.
                offer("COBBLED_DEEPSLATE", 64, 640),
                offer("POLISHED_DEEPSLATE", 64, 800),
                offer("DEEPSLATE_BRICKS", 64, 900),
                offer("DEEPSLATE_TILES", 64, 1_000),
                offer("CHISELED_DEEPSLATE", 64, 1_000),
                offer("TUFF", 64, 800),
                offer("POLISHED_TUFF", 64, 900),
                offer("TUFF_BRICKS", 64, 1_000),
                offer("CHISELED_TUFF", 64, 1_100),
                offer("SANDSTONE", 64, 800),
                offer("SMOOTH_SANDSTONE", 64, 900),
                offer("CUT_SANDSTONE", 64, 900),
                offer("CHISELED_SANDSTONE", 64, 1_000),
                offer("RED_SANDSTONE", 64, 1_100),
                offer("SMOOTH_RED_SANDSTONE", 64, 1_200),
                offer("CUT_RED_SANDSTONE", 64, 1_200),
                offer("BRICKS", 64, 2_000),
                offer("CALCITE", 64, 2_500),
                offer("DRIPSTONE_BLOCK", 64, 1_800),
                offer("OBSIDIAN", 64, 12_000),
                offer("CRYING_OBSIDIAN", 64, 28_000),
                offer("AMETHYST_BLOCK", 64, 4_500)
        ));
        catalog.put(Category.EARTH, List.of(
                offer("DIRT", 64, 400),
                offer("COARSE_DIRT", 64, 450),
                offer("ROOTED_DIRT", 64, 500),
                offer("GRASS_BLOCK", 64, 800),
                offer("PODZOL", 64, 900),
                offer("MYCELIUM", 64, 1_200),
                offer("SAND", 64, 720),
                offer("RED_SAND", 64, 1_000),
                offer("GRAVEL", 64, 540),
                // Breaks into four clay balls, which are on the sell counter, so this
                // block is priced against those four and not against the earth around
                // it. At 900 a block cost $14 and broke into $40, which is a money
                // printer a crafter can run unattended.
                offer("CLAY", 64, 1_920),
                offer("MUD", 64, 800),
                offer("PACKED_MUD", 64, 1_000),
                offer("MUD_BRICKS", 64, 1_800),
                offer("MOSS_BLOCK", 64, 1_800),
                offer("SNOW_BLOCK", 64, 600),
                offer("ICE", 64, 900)
        ));
        catalog.put(Category.WOOD, concat(
                wooded("LOG", 64, 2_000),
                strippedLogs(64, 2_000),
                List.of(offer("CRIMSON_STEM", 64, 2_000), offer("WARPED_STEM", 64, 2_000)),
                wooded("PLANKS", 64, 900),
                List.of(offer("CRIMSON_PLANKS", 64, 900), offer("WARPED_PLANKS", 64, 900)),
                wooded("LEAVES", 64, 1_000),
                List.of(
                        offer("BAMBOO_BLOCK", 64, 2_000),
                        offer("STRIPPED_BAMBOO_BLOCK", 64, 2_000),
                        offer("BAMBOO_PLANKS", 64, 900),
                        offer("BAMBOO_MOSAIC", 64, 1_000)
                )
        ));
        catalog.put(Category.GLASS, concat(
                List.of(
                        offer("GLASS", 64, 1_200),
                        offer("GLASS_PANE", 64, 800),
                        offer("TINTED_GLASS", 64, 3_000)
                ),
                dyed("STAINED_GLASS", 64, 2_400),
                dyed("STAINED_GLASS_PANE", 64, 1_600)
        ));
        catalog.put(Category.WOOL, concat(
                dyed("WOOL", 64, 4_500),
                dyed("CARPET", 64, 3_000)
        ));
        catalog.put(Category.CONCRETE, concat(
                // Set concrete, not just the powder. Hardening it means placing water a
                // stack at a time, which is the step people bought powder to avoid.
                dyed("CONCRETE", 64, 7_000),
                dyed("CONCRETE_POWDER", 64, 6_000)
        ));
        catalog.put(Category.TERRACOTTA, concat(
                List.of(offer("TERRACOTTA", 64, 1_800)),
                dyed("TERRACOTTA", 64, 6_000),
                dyed("GLAZED_TERRACOTTA", 64, 9_000)
        ));
        catalog.put(Category.NETHER, List.of(
                offer("NETHERRACK", 64, 360),
                offer("BLACKSTONE", 64, 810),
                offer("POLISHED_BLACKSTONE", 64, 900),
                offer("POLISHED_BLACKSTONE_BRICKS", 64, 1_000),
                offer("CHISELED_POLISHED_BLACKSTONE", 64, 1_100),
                offer("BASALT", 64, 630),
                offer("SMOOTH_BASALT", 64, 800),
                offer("POLISHED_BASALT", 64, 800),
                offer("NETHER_BRICKS", 64, 1_400),
                offer("RED_NETHER_BRICKS", 64, 2_000),
                offer("CHISELED_NETHER_BRICKS", 64, 1_600),
                offer("SOUL_SAND", 64, 2_400),
                offer("SOUL_SOIL", 64, 2_400),
                offer("QUARTZ_BLOCK", 64, 3_200),
                offer("SMOOTH_QUARTZ", 64, 3_200),
                offer("QUARTZ_BRICKS", 64, 3_200),
                offer("QUARTZ_PILLAR", 64, 3_200),
                offer("CHISELED_QUARTZ_BLOCK", 64, 3_200),
                offer("GLOWSTONE", 64, 4_800),
                offer("SHROOMLIGHT", 64, 6_000),
                offer("MAGMA_BLOCK", 64, 2_400),
                offer("CRIMSON_NYLIUM", 64, 2_000),
                offer("WARPED_NYLIUM", 64, 2_000)
        ));
        catalog.put(Category.OCEAN, List.of(
                offer("PRISMARINE", 64, 8_000),
                offer("PRISMARINE_BRICKS", 64, 10_000),
                offer("DARK_PRISMARINE", 64, 14_000),
                offer("SEA_LANTERN", 64, 28_000),
                offer("PACKED_ICE", 64, 8_000),
                offer("BLUE_ICE", 64, 36_000),
                offer("TUBE_CORAL_BLOCK", 64, 22_000),
                offer("BRAIN_CORAL_BLOCK", 64, 22_000),
                offer("BUBBLE_CORAL_BLOCK", 64, 22_000),
                offer("FIRE_CORAL_BLOCK", 64, 22_000),
                offer("HORN_CORAL_BLOCK", 64, 22_000)
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
                // Sells at 40, so this has to stay inside the 1x-2x band.
                offer("STRING", 16, 960),
                offer("OAK_SAPLING", 16, 700),
                offer("SPRUCE_SAPLING", 16, 700),
                offer("BIRCH_SAPLING", 16, 700),
                offer("PALE_OAK_SAPLING", 16, 700),
                offer("HAY_BLOCK", 16, 8_000),
                offer("DRIED_KELP_BLOCK", 16, 6_400),
                offer("COMPOSTER", 16, 1_600)
        ));
        catalog.put(Category.FOOD, List.of(
                offer("APPLE", 16, 1_120),
                offer("BREAD", 16, 1_400),
                offer("COOKED_BEEF", 16, 1_400),
                offer("COOKED_PORKCHOP", 16, 1_400),
                offer("COOKED_CHICKEN", 16, 1_010),
                offer("COOKED_MUTTON", 16, 1_230),
                offer("COOKED_SALMON", 16, 1_230),
                offer("BAKED_POTATO", 16, 1_010),
                offer("PUMPKIN_PIE", 16, 1_800),
                offer("COOKIE", 16, 900),
                offer("DRIED_KELP", 16, 700),
                offer("GOLDEN_CARROT", 16, 5_000)
        ));
        catalog.put(Category.MINERALS, List.of(
                offer("COAL", 16, 900),
                offer("CHARCOAL", 16, 900),
                offer("COPPER_INGOT", 16, 1_570),
                offer("IRON_INGOT", 16, 2_690),
                offer("GOLD_INGOT", 16, 3_580),
                offer("REDSTONE", 16, 670),
                offer("LAPIS_LAZULI", 16, 900),
                offer("QUARTZ", 16, 1_230),
                offer("AMETHYST_SHARD", 16, 1_120),
                offer("COAL_BLOCK", 16, 8_000),
                offer("COPPER_BLOCK", 16, 14_000),
                offer("CUT_COPPER", 16, 14_000),
                offer("LAPIS_BLOCK", 16, 8_000),
                offer("IRON_BLOCK", 8, 12_000),
                offer("GOLD_BLOCK", 8, 16_000)
        ));
        catalog.put(Category.REDSTONE, List.of(
                offer("REDSTONE", 16, 670),
                offer("REDSTONE_BLOCK", 16, 5_600),
                offer("REDSTONE_TORCH", 16, 900),
                offer("REPEATER", 8, 1_400),
                offer("COMPARATOR", 8, 1_800),
                offer("OBSERVER", 8, 2_800),
                offer("TARGET", 16, 3_200),
                offer("DAYLIGHT_DETECTOR", 8, 2_400),
                offer("SCULK_SENSOR", 8, 8_000),
                offer("CALIBRATED_SCULK_SENSOR", 8, 9_600),
                offer("LIGHTNING_ROD", 16, 4_800),
                offer("LEVER", 64, 640),
                offer("STONE_BUTTON", 64, 640),
                offer("OAK_BUTTON", 64, 640),
                offer("STONE_PRESSURE_PLATE", 64, 1_280),
                offer("OAK_PRESSURE_PLATE", 64, 1_280),
                offer("TRIPWIRE_HOOK", 16, 2_880),
                offer("PISTON", 8, 2_400),
                offer("STICKY_PISTON", 8, 3_600),
                offer("SLIME_BLOCK", 8, 9_600),
                offer("SLIME_BALL", 16, 2_240),
                offer("HONEY_BLOCK", 8, 6_000),
                offer("NOTE_BLOCK", 16, 2_400),
                offer("REDSTONE_LAMP", 16, 3_840),
                offer("COPPER_BULB", 16, 8_000),
                offer("IRON_DOOR", 16, 5_440),
                offer("IRON_TRAPDOOR", 8, 5_360),
                offer("TNT", 8, 5_600)
        ));
        catalog.put(Category.STORAGE, List.of(
                offer("CHEST", 16, 2_400),
                offer("TRAPPED_CHEST", 16, 5_280),
                offer("BARREL", 16, 2_400),
                offer("HOPPER", 8, 4_800),
                offer("DISPENSER", 8, 2_400),
                offer("DROPPER", 8, 1_600),
                offer("CRAFTER", 8, 9_600),
                offer("CRAFTING_TABLE", 16, 1_600),
                offer("FURNACE", 16, 2_400),
                offer("BLAST_FURNACE", 8, 8_000),
                offer("SMOKER", 16, 4_400),
                offer("ITEM_FRAME", 64, 3_000)
        ));
        catalog.put(Category.TRANSPORT, List.of(
                offer("RAIL", 64, 4_480),
                offer("POWERED_RAIL", 32, 8_000),
                offer("DETECTOR_RAIL", 32, 6_400),
                offer("ACTIVATOR_RAIL", 32, 7_360),
                offer("MINECART", 8, 6_400),
                offer("CHEST_MINECART", 8, 7_600),
                offer("HOPPER_MINECART", 8, 11_200),
                offer("FURNACE_MINECART", 8, 7_200),
                offer("OAK_BOAT", 8, 1_600),
                offer("SCAFFOLDING", 64, 3_200),
                offer("LADDER", 64, 1_200)
        ));
        catalog.put(Category.DECORATION, List.of(
                offer("TORCH", 64, 700),
                offer("SOUL_TORCH", 64, 1_400),
                offer("LANTERN", 64, 4_000),
                offer("SOUL_LANTERN", 64, 4_500),
                offer("IRON_CHAIN", 64, 4_500),
                offer("CANDLE", 64, 3_000),
                offer("GLOW_ITEM_FRAME", 64, 6_000),
                offer("FLOWER_POT", 64, 1_500),
                offer("PAINTING", 64, 2_000),
                offer("ARMOR_STAND", 16, 2_400),
                offer("BOOKSHELF", 16, 6_144),
                offer("DECORATED_POT", 16, 2_400)
        ));
        catalog.put(Category.UTILITY, List.of(
                offer("BUCKET", 1, 400),
                offer("WATER_BUCKET", 1, 600),
                offer("LAVA_BUCKET", 1, 1_200),
                offer("SHEARS", 1, 400),
                offer("FLINT_AND_STEEL", 1, 250),
                offer("COMPASS", 1, 900),
                offer("CLOCK", 1, 1_000),
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
        putSell(sell, "Crops", "WHEAT", 50);
        putSell(sell, "Crops", "CARROT", 45);
        putSell(sell, "Crops", "POTATO", 45);
        putSell(sell, "Crops", "BEETROOT", 55);
        putSell(sell, "Crops", "PUMPKIN", 80);
        putSell(sell, "Crops", "MELON", 90);
        putSell(sell, "Crops", "SUGAR_CANE", 80);
        putSell(sell, "Crops", "CACTUS", 70);
        putSell(sell, "Crops", "BAMBOO", 25);
        putSell(sell, "Crops", "COCOA_BEANS", 55);
        putSell(sell, "Crops", "NETHER_WART", 90);
        putSell(sell, "Crops", "KELP", 25);
        putSell(sell, "Crops", "DRIED_KELP", 40);
        putSell(sell, "Crops", "SWEET_BERRIES", 30);
        putSell(sell, "Crops", "GLOW_BERRIES", 35);
        putSell(sell, "Crops", "HONEYCOMB", 70);
        putSell(sell, "Crops", "HONEY_BOTTLE", 140);
        for (String dye : DYES) {
            putSell(sell, "Wool", dye + "_WOOL", 50);
        }
        putSell(sell, "Mob drops", "LEATHER", 70);
        putSell(sell, "Mob drops", "FEATHER", 25);
        putSell(sell, "Mob drops", "BEEF", 50);
        putSell(sell, "Mob drops", "PORKCHOP", 50);
        putSell(sell, "Mob drops", "CHICKEN", 40);
        putSell(sell, "Mob drops", "MUTTON", 45);
        putSell(sell, "Mob drops", "ROTTEN_FLESH", 15);
        putSell(sell, "Mob drops", "BONE", 10);
        putSell(sell, "Mob drops", "STRING", 40);
        putSell(sell, "Mob drops", "SPIDER_EYE", 30);
        putSell(sell, "Mob drops", "GUNPOWDER", 90);
        putSell(sell, "Ores", "COAL", 40);
        putSell(sell, "Ores", "REDSTONE", 30);
        putSell(sell, "Ores", "LAPIS_LAZULI", 40);
        putSell(sell, "Ores", "QUARTZ", 55);
        putSell(sell, "Ores", "COPPER_INGOT", 70);
        putSell(sell, "Ores", "IRON_INGOT", 120);
        putSell(sell, "Ores", "GOLD_INGOT", 160);
        putSell(sell, "Ores", "AMETHYST_SHARD", 50);
        putSell(sell, "Ores", "FLINT", 12);
        // Four of these come out of one bought clay block, so this figure and the
        // shop's clay price are one decision. See DERIVED_FORMS in ShopCatalogTest.
        putSell(sell, "Ores", "CLAY_BALL", 6);
        putSell(sell, "Stone", "COBBLESTONE", 5);
        putSell(sell, "Stone", "STONE", 7);
        putSell(sell, "Stone", "COBBLED_DEEPSLATE", 8);
        putSell(sell, "Stone", "ANDESITE", 8);
        putSell(sell, "Stone", "DIORITE", 8);
        putSell(sell, "Stone", "GRANITE", 8);
        putSell(sell, "Stone", "SAND", 8);
        putSell(sell, "Stone", "GRAVEL", 6);
        putSell(sell, "Stone", "NETHERRACK", 4);
        putSell(sell, "Stone", "BASALT", 7);
        putSell(sell, "Stone", "BLACKSTONE", 9);
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

    private static void putSell(Map<String, SellQuote> sell, String group, String material, long unit) {
        sell.put(material, new SellQuote(material, group, unit));
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

    private static List<Offer> strippedLogs(int amount, long price) {
        List<Offer> offers = new ArrayList<>();
        for (String wood : WOOD) {
            offers.add(offer("STRIPPED_" + wood + "_LOG", amount, price));
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
