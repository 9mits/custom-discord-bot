package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BulkBuyTest {
    private static final int FULL_INVENTORY = 36 * 64;

    @Test
    void aFortuneInBonemealIsCappedByTheOrderSizeNotTheBalance() {
        // Bone meal at $3 an item, seventeen million dollars: 5.6 million items.
        int most = BulkBuy.most(17_000_000L, 3L, FULL_INVENTORY, 64);
        assertEquals(BulkBuy.ceiling(FULL_INVENTORY, 64), most);
        assertTrue(most < 17_000_000L / 3L, "the cap has to bite well before the money does");
        assertEquals(BulkBuy.MAX_ORDER_STACKS, BulkBuy.overflow(most, FULL_INVENTORY) / 64);
    }

    @Test
    void anOrderBigEnoughToStandOverIsAllowed() {
        // The point of the feature: buy enough bone meal to feed a farm for an hour
        // instead of returning to the shop every few minutes.
        int most = BulkBuy.most(4_000_000L, 3L, FULL_INVENTORY, 64);
        assertTrue(most > 1_000_000, "an order has to be worth standing over: " + most);
        int minutes = BulkBuy.deliverySeconds(most, 64) / 60;
        assertTrue(minutes >= 4 && minutes <= 30, "delivery took " + minutes + " minutes");
    }

    @Test
    void aBusyFloorPausesTheDeliveryInsteadOfPilingUp() {
        // What makes any order size safe: nothing more is released until the hoppers
        // have taken what is already down.
        assertEquals(0, BulkBuy.releaseThisTick(100_000, 64, BulkBuy.GROUND_LIMIT));
        assertEquals(0, BulkBuy.releaseThisTick(100_000, 64, BulkBuy.GROUND_LIMIT + 500));
        assertTrue(BulkBuy.releaseThisTick(100_000, 64, BulkBuy.GROUND_LIMIT - 1) > 0);
    }

    @Test
    void aClearFloorReleasesAFixedRateAndNeverOvershoots() {
        assertEquals(BulkBuy.STACKS_PER_TICK * 64, BulkBuy.releaseThisTick(100_000, 64, 0));
        // The last tick of an order hands over only what is left.
        assertEquals(7, BulkBuy.releaseThisTick(7, 64, 0));
        assertEquals(0, BulkBuy.releaseThisTick(0, 64, 0));
        assertEquals(0, BulkBuy.releaseThisTick(-5, 64, 0));
    }

    @Test
    void anUnfinishedOrderIsRefundedForExactlyWhatIsLeft() {
        assertEquals(300L, BulkBuy.refundFor(100, 3L));
        assertEquals(0L, BulkBuy.refundFor(0, 3L));
        assertEquals(0L, BulkBuy.refundFor(-1, 3L));
        assertEquals(0L, BulkBuy.refundFor(100, 0L));
    }

    @Test
    void deliveryTimeIsHonestAboutHowLongToStandThere() {
        // One tick's worth arrives in the first second, not instantly.
        assertEquals(1, BulkBuy.deliverySeconds(BulkBuy.STACKS_PER_TICK * 64, 64));
        assertEquals(0, BulkBuy.deliverySeconds(0, 64));
        // Twenty ticks of releases is one second.
        assertEquals(1, BulkBuy.deliverySeconds(BulkBuy.STACKS_PER_TICK * 64 * 20, 64));
        assertEquals(2, BulkBuy.deliverySeconds(BulkBuy.STACKS_PER_TICK * 64 * 21, 64));
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
        // Ender pearls stack to sixteen, so the same number of stacks is a quarter the
        // items — stacks are what the delivery rate is measured in, not items.
        assertEquals(100 + BulkBuy.MAX_ORDER_STACKS * 16, BulkBuy.ceiling(100, 16));
        assertEquals(100 + BulkBuy.MAX_ORDER_STACKS * 64, BulkBuy.ceiling(100, 64));
        assertEquals(5 + BulkBuy.MAX_ORDER_STACKS, BulkBuy.ceiling(5, 1));
    }

    @Test
    void aNonsenseStackSizeDoesNotCollapseTheCeiling() {
        assertEquals(BulkBuy.ceiling(0, 64), BulkBuy.ceiling(0, 0));
        assertEquals(BulkBuy.ceiling(0, 64), BulkBuy.ceiling(0, -1));
    }

    @Test
    void aFullInventoryStillGetsTheWholeOrderDelivered() {
        // No room to carry anything, so every item bought is delivered to the floor —
        // still bounded, and still paced by the ground check.
        int most = BulkBuy.most(Long.MAX_VALUE / 2, 3L, 0, 64);
        assertEquals(BulkBuy.MAX_ORDER_STACKS * 64, most);
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
