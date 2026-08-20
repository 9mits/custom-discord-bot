package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AuctionItemNameTest {
    @Test
    void customNameIsWhatAuctionSearchRecords() {
        assertEquals(
                "Drool Trail",
                AuctionItemName.resolve("SLIME_BALL", "  Drool Trail  ")
        );
    }

    @Test
    void unnamedVanillaItemsKeepTheirReadableMaterialName() {
        assertEquals("Enchanted Golden Apple", AuctionItemName.resolve(
                "ENCHANTED_GOLDEN_APPLE", null
        ));
        assertEquals("Raw Copper", AuctionItemName.resolve("RAW_COPPER", "  "));
    }

    @Test
    void MissingMaterialStillHasASafeSearchName() {
        assertEquals("Item", AuctionItemName.resolve(null, null));
        assertEquals("Item", AuctionItemName.resolve("___", null));
    }
}
