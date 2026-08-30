package bot.mgx.accessbridge;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopCatalogTest {
    @Test
    void shopUnitPriceStaysBetweenSellAndDoubleSell() {
        for (String material : ShopCatalog.materialsOnBothCounters()) {
            long sell = ShopCatalog.sellCredit(material, 1);
            long buy = ShopCatalog.offer(material).orElseThrow().unitPrice();
            assertTrue(buy >= sell, material + " shop " + buy + " < sell " + sell);
            assertTrue(buy <= sell * 2, material + " shop " + buy + " > 2x sell " + sell);
        }
    }

    @Test
    void boneIsCheaperToSellThanFarmCrops() {
        long boneSell = ShopCatalog.sellCredit("BONE", 1);
        long boneBuy = ShopCatalog.offer("BONE").orElseThrow().price();
        assertEquals(10L, boneSell);
        assertEquals(15L, boneBuy);
        assertTrue(boneSell < ShopCatalog.sellCredit("KELP", 1));
        assertTrue(boneSell < ShopCatalog.sellCredit("BAMBOO", 1));
        assertTrue(boneSell < ShopCatalog.sellCredit("WHEAT", 1));
        assertTrue(boneSell < ShopCatalog.sellCredit("SUGAR_CANE", 1));
        assertTrue(boneBuy < ShopCatalog.sellCredit("SUGAR_CANE", 1));
    }

    @Test
    void caneBeatsTrashGensAndKeepsUpWithGunpowder() {
        assertTrue(ShopCatalog.sellCredit("SUGAR_CANE", 1) > ShopCatalog.sellCredit("COBBLESTONE", 1));
        assertTrue(ShopCatalog.sellCredit("SUGAR_CANE", 1) > ShopCatalog.sellCredit("KELP", 1));
        assertTrue(ShopCatalog.sellCredit("GUNPOWDER", 1) <= ShopCatalog.sellCredit("SUGAR_CANE", 1) + 20);
        assertFalse(ShopCatalog.isSellable("DIAMOND"));
    }

    @Test
    void rareProgressionItemsStayOffBothCounters() {
        assertFalse(ShopCatalog.offer("ELYTRA").isPresent());
        assertFalse(ShopCatalog.isSellable("TOTEM_OF_UNDYING"));
        assertFalse(ShopCatalog.isSellable("SHULKER_SHELL"));
        assertFalse(ShopCatalog.isSellable("ENCHANTED_GOLDEN_APPLE"));
    }

    @Test
    void everySellRowIsFiledUnderAHeading() {
        List<String> unfiled = new ArrayList<>();
        for (ShopCatalog.SellQuote quote : ShopCatalog.allSellQuotes()) {
            if (quote.group() == null || quote.group().isBlank()) {
                unfiled.add(quote.material());
            }
        }
        assertEquals(List.of(), unfiled);
    }

    @Test
    void everyShelfFitsOnOnePage() {
        // A shelf that spills onto a second page is the squashed-together problem the
        // split was meant to fix, so this is the line to hold when adding stock.
        List<String> overflowing = new ArrayList<>();
        for (ShopCatalog.Category category : ShopCatalog.categories()) {
            int size = ShopCatalog.offers(category).size();
            if (size > 45) {
                overflowing.add(category.name() + " has " + size);
            }
        }
        assertEquals(List.of(), overflowing);
    }

    /**
     * The Amethyst shelf leads the hub because it is the one that leaves. The hub draws
     * the grid in enum order, so being first in the enum is the whole mechanism.
     */
    @Test
    void theAmethystShelfLeadsTheHub() {
        assertEquals(ShopCatalog.Category.AMETHYST, ShopCatalog.categories().get(0));
        assertTrue(ShopCatalog.Category.AMETHYST.limited());
        assertEquals(CrateKind.AMETHYST.closesAt(), ShopCatalog.Category.AMETHYST.closesAt(),
                "the shelf and the crate must close together");
    }

    /** Every other shelf is permanent; a second deadline would need its own countdown. */
    @Test
    void onlyTheAmethystShelfEverCloses() {
        for (ShopCatalog.Category category : ShopCatalog.categories()) {
            if (category != ShopCatalog.Category.AMETHYST) {
                assertFalse(category.limited(), category.name());
                assertEquals(Long.MAX_VALUE, category.closesAt(), category.name());
            }
        }
    }

    @Test
    void theAmethystShelfIsGoneFromTheHubOnceItCloses() {
        long closesAt = ShopCatalog.Category.AMETHYST.closesAt();
        assertTrue(ShopCatalog.categories(closesAt - 1L).contains(ShopCatalog.Category.AMETHYST));
        assertFalse(ShopCatalog.categories(closesAt).contains(ShopCatalog.Category.AMETHYST));
        assertEquals(
                ShopCatalog.categories().size() - 1,
                ShopCatalog.categories(closesAt).size(),
                "closing the amethyst shelf must not take another one with it"
        );
    }

    /**
     * Vanilla amethyst only, and all of it. The shelf is a themed corner of the shop,
     * not a second Amethyst Crate: what the crate pays out is the reason to open one.
     */
    @Test
    void theAmethystShelfStocksEveryVanillaAmethystItemAndNothingElse() {
        List<String> stocked = new ArrayList<>();
        for (ShopCatalog.Offer offer : ShopCatalog.offers(ShopCatalog.Category.AMETHYST)) {
            stocked.add(offer.material());
        }
        assertEquals(
                List.of(
                        "AMETHYST_SHARD", "AMETHYST_BLOCK", "AMETHYST_CLUSTER",
                        "LARGE_AMETHYST_BUD", "MEDIUM_AMETHYST_BUD", "SMALL_AMETHYST_BUD",
                        "BUDDING_AMETHYST"
                ),
                stocked
        );
        for (String material : stocked) {
            assertTrue(material.contains("AMETHYST"), material + " is not an amethyst item");
        }
    }

    /**
     * The five crate exclusives reach the shop through the daily listing alone, which
     * mints them from the crate's own recipe. A shelf offer is a bare material with no
     * data on it, so stocking one of their materials here would not sell the Amethyst
     * Totem - it would sell a plain totem off the amethyst shelf, which is worse.
     *
     * <p>Scoped to that shelf on purpose. Cosmetic rewards are drawn with amethyst
     * materials as their icon, so a server-wide search by material name would flag the
     * shards this shelf exists to sell.
     */
    @Test
    void theAmethystShelfStocksNoCrateExclusiveMaterial() {
        List<String> exclusiveMaterials = new ArrayList<>();
        for (CrateCatalog.Reward reward : CrateCatalog.amethystAdminRewards()) {
            if (CrateCatalog.isExclusiveAmethyst(reward) && !reward.cosmetic()) {
                exclusiveMaterials.add(reward.materialName());
            }
        }
        assertFalse(exclusiveMaterials.isEmpty(), "the exclusives should not have vanished");
        List<String> stocked = new ArrayList<>();
        for (ShopCatalog.Offer offer : ShopCatalog.offers(ShopCatalog.Category.AMETHYST)) {
            if (exclusiveMaterials.contains(offer.material())) {
                stocked.add(offer.material());
            }
        }
        assertEquals(List.of(), stocked);
    }

    /**
     * A cluster breaks into four shards the sell counter pays for, so a cluster cheaper
     * than those four is the same money printer {@code DERIVED_FORMS} guards elsewhere -
     * and it cannot be listed there, because the shop does not buy clusters back.
     */
    @Test
    void aClusterCostsMoreThanTheShardsItBreaksInto() {
        long cluster = ShopCatalog.offer("AMETHYST_CLUSTER").orElseThrow().unitPrice();
        assertTrue(cluster > ShopCatalog.sellCredit("AMETHYST_SHARD", 4),
                "cluster " + cluster + " <= 4 shards");
    }

    @Test
    void everyShelfFitsOnTheHub() {
        // The hub draws one icon per category into a fixed grid; a shelf past the end
        // of it would exist but be unreachable.
        assertEquals(true, ShopCatalog.categories().size() <= 21,
                "more shelves than the hub grid has slots");
    }

    @Test
    void everyCategoryHasOffers() {
        for (ShopCatalog.Category category : ShopCatalog.categories()) {
            assertFalse(ShopCatalog.offers(category).isEmpty(), category.name());
        }
    }

    /**
     * A name the game does not have is not a crash: {@code EconomyMenuService.materialOf}
     * falls back to BARRIER, so a typo ships as a buyable barrier block that nobody
     * notices until a player buys 64 of them. This is the only place that can catch it,
     * which is why paper-api is on the test classpath.
     *
     * <p>Checks the name resolves and is not a legacy alias. {@code isItem()} would be
     * the stronger test but it reads the Bukkit registry, which only exists on a
     * running server.
     */
    @Test
    void everyCatalogNameIsARealItem() {
        List<String> broken = new ArrayList<>();
        for (ShopCatalog.Offer offer : ShopCatalog.allOffers()) {
            Material material = Material.getMaterial(offer.material());
            if (material == null || material.isLegacy()) {
                broken.add("buy " + offer.material());
            }
        }
        for (ShopCatalog.SellQuote quote : ShopCatalog.allSellQuotes()) {
            Material material = Material.getMaterial(quote.material());
            if (material == null || material.isLegacy()) {
                broken.add("sell " + quote.material());
            }
        }
        for (ShopCatalog.Category category : ShopCatalog.categories()) {
            Material icon = Material.getMaterial(category.icon());
            if (icon == null || icon.isLegacy()) {
                broken.add("icon " + category.name() + " " + category.icon());
            }
        }
        assertEquals(List.of(), broken, "catalog entries that are not real items");
    }

    /**
     * An item may sensibly appear in two categories — redstone dust belongs under both
     * Minerals and Redstone, white wool under both Colour and Farming — and the bundle
     * sizes may differ, since a farmer buys 16 and a builder buys 64. What may not
     * differ is the price per item, because that is the only figure the shop actually
     * charges: {@code buy} multiplies {@code unitPrice} by however many the player
     * asked for. Two listings that disagree on it would make the same block cost
     * different amounts depending on which page you found it.
     */
    @Test
    void anItemStockedTwiceCostsTheSamePerItem() {
        Map<String, Long> unitByMaterial = new LinkedHashMap<>();
        List<String> disagreeing = new ArrayList<>();
        for (ShopCatalog.Category category : ShopCatalog.categories()) {
            for (ShopCatalog.Offer shelf : ShopCatalog.offers(category)) {
                Long first = unitByMaterial.putIfAbsent(shelf.material(), shelf.unitPrice());
                if (first != null && first != shelf.unitPrice()) {
                    disagreeing.add(shelf.material() + " costs " + first
                            + " elsewhere but " + shelf.unitPrice() + " in " + category.name());
                }
            }
        }
        assertEquals(List.of(), disagreeing);
    }

    @Test
    void shopChargesByTheSingleItem() {
        ShopCatalog.Offer cobble = ShopCatalog.offer("COBBLESTONE").orElseThrow();
        assertEquals(7L, cobble.unitPrice());
        assertEquals(448L, cobble.costOfItems(64));
    }

    @Test
    void phantomMembraneIsExpensiveBecausePhantomsAreGone() {
        ShopCatalog.Offer membrane = ShopCatalog.offer("PHANTOM_MEMBRANE").orElseThrow();
        assertEquals(8_000L, membrane.unitPrice());
        assertTrue(membrane.unitPrice() >= ShopCatalog.offer("NAME_TAG").orElseThrow().unitPrice());
    }

    /**
     * Buyable items that turn into something else the shop pays for, and how many come
     * out of one. Breaking counts as much as crafting: nobody needs a recipe to place a
     * clay block and mine it back.
     *
     * <p>Only conversions a player can actually perform belong here. A quartz block
     * holds four quartz but no recipe or drop gives them back, so it is not a way out.
     */
    private static final Map<String, Map<String, Integer>> DERIVED_FORMS = Map.of(
            "CLAY", Map.of("CLAY_BALL", 4),
            "HAY_BLOCK", Map.of("WHEAT", 9),
            "COAL_BLOCK", Map.of("COAL", 9),
            "REDSTONE_BLOCK", Map.of("REDSTONE", 9),
            "LAPIS_BLOCK", Map.of("LAPIS_LAZULI", 9),
            "IRON_BLOCK", Map.of("IRON_INGOT", 9),
            "GOLD_BLOCK", Map.of("GOLD_INGOT", 9),
            "COPPER_BLOCK", Map.of("COPPER_INGOT", 9),
            "DRIED_KELP_BLOCK", Map.of("DRIED_KELP", 9),
            "STONE", Map.of("COBBLESTONE", 1)
    );

    /**
     * The shop must not sell a block for less than the shop itself pays for what comes
     * out of it. Clay shipped at $14 a block and broke into four clay balls worth $40,
     * which is an unattended money printer — buy, place, break, sell, repeat — and the
     * buy-versus-sell check above could not see it, because clay the block is not on
     * the sell counter at all. Only its pieces are.
     */
    @Test
    void breakingOrCraftingWhatTheShopSellsNeverBeatsItsPrice() {
        List<String> loops = new ArrayList<>();
        DERIVED_FORMS.forEach((material, yields) -> {
            ShopCatalog.Offer offer = ShopCatalog.offer(material).orElse(null);
            if (offer == null) {
                return;
            }
            long payout = 0L;
            for (Map.Entry<String, Integer> yield : yields.entrySet()) {
                payout += ShopCatalog.sellCredit(yield.getKey(), yield.getValue());
            }
            if (payout > offer.unitPrice()) {
                loops.add(material + " costs " + offer.unitPrice()
                        + " and breaks into " + payout + " of sellables");
            }
        });
        assertEquals(List.of(), loops, "buy-break-sell loops that print money");
    }
}
