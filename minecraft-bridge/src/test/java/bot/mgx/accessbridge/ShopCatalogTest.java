package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

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

    @Test
    void shopChargesByTheSingleItem() {
        ShopCatalog.Offer cobble = ShopCatalog.offer("COBBLESTONE").orElseThrow();
        assertEquals(7L, cobble.unitPrice());
        assertEquals(448L, cobble.costOfItems(64));
    }
}
