package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuctionStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void aPurchasePaysTheSellerMinusTaxAndRemovesTheListing() throws Exception {
        EconomyStore money = new EconomyStore(temporaryDirectory.resolve("balances.json"));
        AuctionStore auctions = new AuctionStore(temporaryDirectory.resolve("auctions.json"));
        UUID seller = UUID.randomUUID();
        UUID buyer = UUID.randomUUID();
        money.deposit(buyer, 200);
        AuctionStore.Listing listing = auctions.list(
                seller, "Seller", 100, "DIAMOND", 1, "Diamond", "item-bytes", 1_000L
        );

        AuctionStore.Purchase purchase = auctions.buy(buyer, listing.id(), money, 2_000L);

        assertEquals(100L, purchase.paid());
        assertEquals(95L, purchase.received());
        assertEquals(100L, money.balance(buyer));
        assertEquals(95L, money.balance(seller));
        assertTrue(auctions.browse("", 2_000L).isEmpty());
    }

    @Test
    void aPlayerCannotBuyTheirOwnListing() throws Exception {
        EconomyStore money = new EconomyStore(temporaryDirectory.resolve("balances.json"));
        AuctionStore auctions = new AuctionStore(temporaryDirectory.resolve("auctions.json"));
        UUID seller = UUID.randomUUID();
        money.deposit(seller, 500);
        AuctionStore.Listing listing = auctions.list(
                seller, "Seller", 50, "DIRT", 64, "Dirt", "item-bytes", 1_000L
        );

        assertThrows(IllegalArgumentException.class,
                () -> auctions.buy(seller, listing.id(), money, 2_000L));
        assertEquals(500L, money.balance(seller));
        assertEquals(1, auctions.browse("", 2_000L).size());
    }

    @Test
    void anExpiredListingMovesToTheSellersMailbox() throws Exception {
        AuctionStore auctions = new AuctionStore(temporaryDirectory.resolve("auctions.json"));
        UUID seller = UUID.randomUUID();
        auctions.list(seller, "Seller", 10, "STONE", 64, "Stone", "item-bytes", 1_000L);

        int moved = auctions.expire(1_000L + AuctionStore.LISTING_DURATION_MILLIS + 1L);

        assertEquals(1, moved);
        assertTrue(auctions.browse("", System.currentTimeMillis()).isEmpty());
        assertEquals(1, auctions.mailboxOf(seller).size());
        assertEquals(1, auctions.collect(seller).size());
        assertTrue(auctions.mailboxOf(seller).isEmpty());
    }

    @Test
    void searchMatchesMaterialOrName() throws Exception {
        AuctionStore auctions = new AuctionStore(temporaryDirectory.resolve("auctions.json"));
        auctions.list(UUID.randomUUID(), "Kai", 10, "DIAMOND_SWORD", 1, "Sharp Sword", "a", 1L);
        auctions.list(UUID.randomUUID(), "Ada", 10, "DIRT", 64, "Dirt", "b", 2L);

        assertEquals(1, auctions.browse("diamond", 3L).size());
        assertEquals(1, auctions.browse("sharp", 3L).size());
        assertEquals(1, auctions.browse("ada", 3L).size());
        assertEquals(2, auctions.browse("", 3L).size());
    }

    @Test
    void aFourteenthListingIsTheLastOneAllowed() throws Exception {
        AuctionStore auctions = new AuctionStore(temporaryDirectory.resolve("auctions.json"));
        UUID seller = UUID.randomUUID();
        for (int index = 0; index < AuctionStore.MAX_LISTINGS_PER_PLAYER; index++) {
            auctions.list(seller, "Seller", 1, "DIRT", 1, "Dirt", "item-" + index, index);
        }

        assertThrows(IllegalArgumentException.class,
                () -> auctions.list(seller, "Seller", 1, "DIRT", 1, "Dirt", "overflow", 99));
    }

    @Test
    void fivePercentTaxFloorsToWholeDollars() {
        assertEquals(0L, AuctionStore.taxOn(19));
        assertEquals(5L, AuctionStore.taxOn(100));
        assertEquals(1L, AuctionStore.taxOn(20));
    }
}
