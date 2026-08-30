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
    /** Takes everything waiting in the auction mailbox. */
    static final int MAIL_COLLECT = 53;
    /** Sets the amount to whatever the player can afford and carry. */
    static final int BUY_MAX = 16;
    /** Opens the standing-order screen for this item. */
    static final int AUTOBUY = 25;
    /** On the standing-order screen: how often, whether to drop, start and stop. */
    static final int AUTO_INTERVAL = 11;
    static final int AUTO_DROP = 15;
    static final int AUTO_START = 31;
    /** Puts the amount back to one. */
    static final int BUY_RESET = 34;
    /**
     * The Amethyst shelf's once-a-day listing, in the middle of an otherwise empty
     * board.
     *
     * <p>This one is inside the content rows a paged screen reads as offer indices,
     * which is only safe while the shelf holds fewer items than the slot's own number.
     * It holds seven and there are only seven vanilla amethyst items, so the shelf has
     * nowhere to grow - and {@code ShopCatalogTest} fails if it ever does.
     */
    static final int AMETHYST_DAILY = 31;
    /** Commits the purchase. */
    static final int BUY_CONFIRM = 40;

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
                MAIL_COLLECT,
                BUY_MAX,
                AUTOBUY,
                BUY_RESET,
                AUTO_INTERVAL,
                AUTO_DROP,
                AUTO_START,
                AMETHYST_DAILY,
                BUY_CONFIRM,
        };
    }
}
