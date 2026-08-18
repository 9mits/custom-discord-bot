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
}
