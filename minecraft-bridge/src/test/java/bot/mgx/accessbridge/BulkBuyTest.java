package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BulkBuyTest {
    private static final int FULL_INVENTORY = 36 * 64;

    @Test
    void aFortuneInBonemealIsCappedRatherThanDroppedOnTheWorld() {
        // The case this exists for: bone meal at $3 an item, seventeen million
        // dollars, 5.6 million items. Handing that over is 88,000 item entities.
        int most = BulkBuy.most(17_000_000L, 3L, FULL_INVENTORY, 64);
        assertEquals(BulkBuy.ceiling(FULL_INVENTORY, 64), most);
        assertTrue(most < 17_000_000L / 3L, "the cap has to bite well before the money does");
        assertEquals(BulkBuy.MAX_OVERFLOW_STACKS, BulkBuy.overflow(most, FULL_INVENTORY) / 64);
    }

    @Test
    void aModestPurseIsLimitedByMoneyNotByTheCap() {
        // Ten dollars of bone meal is three items, and nothing else should interfere.
        assertEquals(3, BulkBuy.most(10L, 3L, FULL_INVENTORY, 64));
        assertEquals(0, BulkBuy.overflow(3, FULL_INVENTORY));
    }

    @Test
    void anEmptyPurseBuysNothing() {
        assertEquals(0, BulkBuy.most(0L, 3L, FULL_INVENTORY, 64));
        assertEquals(0, BulkBuy.most(2L, 3L, FULL_INVENTORY, 64));
        // A free item would divide by zero into an unbounded purchase.
        assertEquals(0, BulkBuy.most(1_000L, 0L, FULL_INVENTORY, 64));
        assertEquals(0, BulkBuy.most(1_000L, -5L, FULL_INVENTORY, 64));
    }

    @Test
    void theCeilingFollowsTheItemsOwnStackSize() {
        // Ender pearls stack to sixteen, so the same number of dropped stacks is a
        // quarter the items — the entity count is what is being bounded, not the count.
        assertEquals(100 + BulkBuy.MAX_OVERFLOW_STACKS * 16, BulkBuy.ceiling(100, 16));
        assertEquals(100 + BulkBuy.MAX_OVERFLOW_STACKS * 64, BulkBuy.ceiling(100, 64));
        // A saddle stacks to one, so the spill is one entity per saddle.
        assertEquals(5 + BulkBuy.MAX_OVERFLOW_STACKS, BulkBuy.ceiling(5, 1));
    }

    @Test
    void aNonsenseStackSizeDoesNotCollapseTheCeiling() {
        assertEquals(BulkBuy.ceiling(0, 64), BulkBuy.ceiling(0, 0));
        assertEquals(BulkBuy.ceiling(0, 64), BulkBuy.ceiling(0, -1));
    }

    @Test
    void aFullInventoryStillAllowsTheSpill() {
        // No room to carry anything, so every item bought is dropped — but still
        // bounded, which is what stops a full-inventory player crashing the server.
        int most = BulkBuy.most(Long.MAX_VALUE / 2, 3L, 0, 64);
        assertEquals(BulkBuy.MAX_OVERFLOW_STACKS * 64, most);
        assertEquals(most, BulkBuy.overflow(most, 0));
    }

    @Test
    void overflowIsWhatWillNotFit() {
        assertEquals(0, BulkBuy.overflow(50, 100));
        assertEquals(0, BulkBuy.overflow(100, 100));
        assertEquals(40, BulkBuy.overflow(140, 100));
        assertEquals(140, BulkBuy.overflow(140, -1));
    }
}
