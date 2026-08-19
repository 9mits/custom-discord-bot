package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StackSplitTest {
    @Test
    void theReportedCaseSplitsInsteadOfMakingA99Stack() {
        assertArrayEquals(new int[] {64, 35}, StackSplit.portions(99, 64));
    }

    @Test
    void anExactStackIsOnePortion() {
        assertArrayEquals(new int[] {64}, StackSplit.portions(64, 64));
        assertArrayEquals(new int[] {16}, StackSplit.portions(16, 16));
    }

    @Test
    void aFullInventoryBuySplitsIntoWholeStacks() {
        int[] portions = StackSplit.portions(36 * 64, 64);
        assertEquals(36, portions.length);
        for (int portion : portions) {
            assertEquals(64, portion);
        }
    }

    @Test
    void itemsThatStackSmallerAreRespected() {
        // Ender pearls and buckets stack to 16, and the shop sells both.
        assertArrayEquals(new int[] {16, 16, 3}, StackSplit.portions(35, 16));
        // Saddles and name tags stack to one.
        assertArrayEquals(new int[] {1, 1, 1}, StackSplit.portions(3, 1));
    }

    @Test
    void nothingIsGainedOrLostInTheSplit() {
        for (int amount = 1; amount <= 300; amount++) {
            for (int stack : new int[] {1, 16, 64}) {
                int total = 0;
                for (int portion : StackSplit.portions(amount, stack)) {
                    assertTrue(portion > 0 && portion <= stack,
                            amount + "/" + stack + " produced a portion of " + portion);
                    total += portion;
                }
                assertEquals(amount, total, "split of " + amount + " by " + stack);
            }
        }
    }

    @Test
    void nothingToGiveIsNoPortions() {
        assertEquals(0, StackSplit.portions(0, 64).length);
        assertEquals(0, StackSplit.portions(-5, 64).length);
    }

    @Test
    void aNonsenseStackSizeFallsBackRatherThanLoopingForever() {
        assertArrayEquals(new int[] {64, 36}, StackSplit.portions(100, 0));
        assertArrayEquals(new int[] {64, 36}, StackSplit.portions(100, -1));
    }
}
