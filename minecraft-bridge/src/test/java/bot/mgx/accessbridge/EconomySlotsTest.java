package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class EconomySlotsTest {
    @Test
    void backSitsWhereItAlwaysHas() {
        assertEquals(49, EconomySlots.back());
    }

    @Test
    void noButtonOnABackBearingScreenSharesBacksSlot() {
        // The bug this guards: "Collect all" was drawn on 49 and Back was drawn after
        // it, so the button was invisible; the click handler resolves Back before the
        // per-screen dispatch, so it was unreachable even to a blind click. Expired
        // auction listings reached the mailbox and could never be taken out.
        for (int slot : EconomySlots.onScreensWithBack()) {
            assertNotEquals(EconomySlots.back(), slot,
                    "slot " + slot + " collides with Back and would be swallowed");
        }
    }

    @Test
    void everyActionSlotIsOnTheNavigationRow() {
        // Rows 0-44 hold content, so a button anywhere in that range would be mistaken
        // for a listing, an offer or a mail item by the index arithmetic.
        for (int slot : EconomySlots.onScreensWithBack()) {
            assertEquals(true, slot >= MenuItems.PER_PAGE && slot < EconomySlots.BOARD,
                    "slot " + slot + " is not on the navigation row");
        }
    }
}
