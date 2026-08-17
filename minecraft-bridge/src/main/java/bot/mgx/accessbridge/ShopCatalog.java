package bot.mgx.accessbridge;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Shop is convenience at a premium. Sell is a short list of things you actually
 * harvest by playing — not every AFK farm output.
 *
 * <p>Not sold: elytra, netherite, totems, shulker shells, enchanted golden apples,
 * blaze rods, slime, gunpowder, or other farm-meta drops. Those stay in the world
 * or go on the auction house.
 */
final class ShopCatalog {
    enum Category {
        BUILDING("Building", "BRICKS"),
        COLORED("Color", "WHITE_WOOL"),
        FARMING("Farming", "WHEAT"),
        MINERALS("Minerals", "DIAMOND"),
        UTILITY("Utility", "SADDLE"),
        REDSTONE("Redstone", "REDSTONE"),
        FOOD("Food", "BREAD"),
        MISC("Misc", "TORCH");

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
                offer("COBBLESTONE", 64, 15_000),
                offer("STONE", 64, 18_000),
                offer("DIRT", 64, 10_000),
                offer("SAND", 64, 22_000),
                offer("GRAVEL", 64, 18_000),
                offer("GLASS", 64, 28_000),
                offer("OAK_LOG", 64, 30_000),
                offer("SPRUCE_LOG", 64, 30_000),
                offer("BIRCH_LOG", 64, 30_000),
                offer("DEEPSLATE", 64, 18_000),
                offer("BRICKS", 64, 35_000),
                offer("SANDSTONE", 64, 25_000)
        ));
        catalog.put(Category.COLORED, concat(
                dyed("WOOL", 64, 22_000),
                dyed("CONCRETE", 64, 35_000),
                dyed("STAINED_GLASS", 64, 32_000)
        ));
        catalog.put(Category.FARMING, List.of(
                offer("BONE_MEAL", 16, 12_000),
                offer("WHEAT", 16, 10_000),
                offer("CARROT", 16, 10_000),
                offer("POTATO", 16, 10_000),
                offer("LEATHER", 16, 18_000)
        ));
        catalog.put(Category.MINERALS, List.of(
                offer("COAL", 16, 20_000),
                offer("COPPER_INGOT", 16, 25_000),
                offer("IRON_INGOT", 16, 55_000),
                offer("GOLD_INGOT", 16, 90_000),
                offer("REDSTONE", 16, 18_000),
                offer("LAPIS_LAZULI", 16, 22_000),
                offer("QUARTZ", 16, 28_000),
                offer("EMERALD", 1, 12_000),
                offer("DIAMOND", 1, 75_000)
        ));
        catalog.put(Category.UTILITY, List.of(
                offer("BUCKET", 1, 5_000),
                offer("WATER_BUCKET", 1, 6_500),
                offer("LAVA_BUCKET", 1, 15_000),
                offer("LEAD", 1, 15_000),
                offer("SADDLE", 1, 80_000),
                offer("NAME_TAG", 1, 100_000),
                offer("ENDER_PEARL", 4, 35_000),
                offer("ENDER_CHEST", 1, 120_000),
                offer("EXPERIENCE_BOTTLE", 1, 8_000)
        ));
        catalog.put(Category.REDSTONE, List.of(
                offer("REDSTONE", 16, 18_000),
                offer("REDSTONE_TORCH", 16, 20_000),
                offer("REPEATER", 8, 22_000),
                offer("COMPARATOR", 8, 28_000),
                offer("PISTON", 8, 40_000),
                offer("STICKY_PISTON", 8, 65_000),
                offer("OBSERVER", 8, 45_000),
                offer("HOPPER", 8, 80_000)
        ));
        catalog.put(Category.FOOD, List.of(
                offer("BREAD", 16, 8_000),
                offer("COOKED_BEEF", 16, 15_000),
                offer("COOKED_PORKCHOP", 16, 15_000),
                offer("COOKED_CHICKEN", 16, 12_000),
                offer("BAKED_POTATO", 16, 8_000),
                offer("GOLDEN_CARROT", 16, 40_000)
        ));
        catalog.put(Category.MISC, List.of(
                offer("TORCH", 64, 12_000),
                offer("LANTERN", 16, 18_000),
                offer("PAPER", 16, 8_000),
                offer("BOOK", 16, 14_000),
                offer("GLASS_BOTTLE", 16, 6_000),
                offer("ITEM_FRAME", 16, 12_000),
                offer("OAK_BOAT", 1, 4_000),
                offer("RAIL", 64, 25_000)
        ));
        return catalog;
    }

    private static Map<String, SellQuote> buildSell() {
        Map<String, SellQuote> sell = new LinkedHashMap<>();
        putSell(sell, "WHEAT", 70);
        putSell(sell, "CARROT", 70);
        putSell(sell, "POTATO", 70);
        putSell(sell, "BEETROOT", 90);
        putSell(sell, "PUMPKIN", 150);
        putSell(sell, "MELON", 120);
        putSell(sell, "COCOA_BEANS", 90);
        putSell(sell, "NETHER_WART", 150);
        putSell(sell, "LEATHER", 150);
        putSell(sell, "FEATHER", 60);
        putSell(sell, "BEEF", 60);
        putSell(sell, "PORKCHOP", 60);
        putSell(sell, "CHICKEN", 50);
        putSell(sell, "MUTTON", 50);
        putSell(sell, "EGG", 30);
        putSell(sell, "WHITE_WOOL", 60);
        putSell(sell, "ROTTEN_FLESH", 50);
        putSell(sell, "BONE", 120);
        putSell(sell, "STRING", 90);
        putSell(sell, "OAK_LOG", 70);
        putSell(sell, "SPRUCE_LOG", 70);
        putSell(sell, "BIRCH_LOG", 70);
        putSell(sell, "COAL", 90);
        putSell(sell, "RAW_COPPER", 70);
        putSell(sell, "RAW_IRON", 180);
        putSell(sell, "RAW_GOLD", 270);
        putSell(sell, "REDSTONE", 50);
        putSell(sell, "LAPIS_LAZULI", 60);
        putSell(sell, "QUARTZ", 90);
        putSell(sell, "DIAMOND", 900);
        putSell(sell, "EMERALD", 240);
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
}
