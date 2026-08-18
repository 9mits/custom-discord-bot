package bot.mgx.accessbridge;

/**
 * Where the buttons sit on the economy boards, for every screen that also draws Back.
 *
 * <p>Exists so a test can assert the one thing a screen cannot check about itself:
 * that no button shares a slot with Back. "Collect all" and Back both landed on 49
 * on the auction mailbox. Back is drawn last, so the button was invisible, and the
 * click handler resolves Back before the per-screen dispatch, so it was unreachable
 * even to a player clicking blind — expired listings went into the mailbox and could
 * never be taken out again.
 *
 * <p>Free of Bukkit imports so the check runs in the unit suite.
 */
final class EconomySlots {
    /** Every economy board is a double chest. */
    static final int BOARD = 54;
    /** Cycles how many a plain click buys, on a shop category page. */
    static final int BUY_AMOUNT = 47;
    /** Takes everything waiting in the auction mailbox. */
    static final int MAIL_COLLECT = 53;

    private EconomySlots() {
    }

    /** The slot Back occupies on a full board, which nothing else may use. */
    static int back() {
        return MenuPaging.backSlot(BOARD);
    }

    /** Every action slot drawn on a screen that also has a Back button. */
    static int[] onScreensWithBack() {
        return new int[] {
                MenuItems.PREVIOUS_SLOT,
                MenuItems.NEXT_SLOT,
                BUY_AMOUNT,
                MAIL_COLLECT,
        };
    }
}
