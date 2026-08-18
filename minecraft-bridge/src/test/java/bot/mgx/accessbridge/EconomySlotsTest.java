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
    void everyActionSlotIsInsideTheBoard() {
        for (int slot : EconomySlots.onScreensWithBack()) {
            assertEquals(true, slot >= 0 && slot < EconomySlots.BOARD,
                    "slot " + slot + " is off the board");
        }
    }

    @Test
    void pagedScreensKeepTheirButtonsOffTheContentRows() {
        // A paged screen fills slots 0-44 with listings and reads the clicked slot as
        // an index into them, so a button there would be bought instead of pressed.
        // The buy screen is not paged and deliberately uses the upper rows.
        for (int slot : new int[] {MenuItems.PREVIOUS_SLOT, MenuItems.NEXT_SLOT, EconomySlots.MAIL_COLLECT}) {
            assertEquals(true, slot >= MenuItems.PER_PAGE,
                    "slot " + slot + " would be read as a listing");
        }
    }
}
