package bot.mgx.accessbridge;

/**
 * Paging arithmetic for the menu screens.
 *
 * <p>Free of Bukkit imports so it can be unit tested, which is the whole reason it is
 * a class rather than three lines inlined into each screen: the edge cases here —
 * empty lists, exact multiples of the page size, a page number that no longer exists
 * because the underlying list shrank — are where off-by-ones live.
 *
 * <p>Pages are one-based, because they are shown to players.
 */
final class MenuPaging {
    private MenuPaging() {
    }

    /** Total pages needed, never fewer than one so an empty screen still renders. */
    static int pageCount(int total, int perPage) {
        if (perPage <= 0 || total <= 0) {
            return 1;
        }
        return (total + perPage - 1) / perPage;
    }

    /** A page number forced inside the range, for when a list shrank under a viewer. */
    static int clampPage(int page, int total, int perPage) {
        return Math.max(1, Math.min(page, pageCount(total, perPage)));
    }

    /** Index of the first entry on a page, already clamped. */
    static int firstIndex(int page, int total, int perPage) {
        if (perPage <= 0 || total <= 0) {
            return 0;
        }
        return (clampPage(page, total, perPage) - 1) * perPage;
    }

    /** Index just past the last entry on a page, never beyond the list. */
    static int lastIndex(int page, int total, int perPage) {
        if (perPage <= 0 || total <= 0) {
            return 0;
        }
        return Math.min(firstIndex(page, total, perPage) + perPage, total);
    }

    /**
     * The middle of a board's last row, which is where Back always sits.
     *
     * <p>Derived from the size rather than fixed. A hard-coded slot 49 was written into
     * the 27-slot clan card, which is out of range: the write threw and took the whole
     * screen with it, so Back appeared to work on the big boards and do nothing on the
     * small ones. Clamped rather than trusted, since a caller passing a tiny inventory
     * should get a usable slot instead of an exception.
     */
    static int backSlot(int size) {
        return Math.max(0, size - 5);
    }

    static boolean hasPrevious(int page, int total, int perPage) {
        return clampPage(page, total, perPage) > 1;
    }

    static boolean hasNext(int page, int total, int perPage) {
        return clampPage(page, total, perPage) < pageCount(total, perPage);
    }
}
