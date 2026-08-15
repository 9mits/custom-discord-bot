package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MenuPagingTest {
    private static final int PER_PAGE = 45;

    @Test
    void anEmptyListStillHasOnePageToDraw() {
        // Returning zero pages would render nothing at all, not even the "nobody here
        // yet" placeholder the screens rely on.
        assertEquals(1, MenuPaging.pageCount(0, PER_PAGE));
        assertEquals(0, MenuPaging.firstIndex(1, 0, PER_PAGE));
        assertEquals(0, MenuPaging.lastIndex(1, 0, PER_PAGE));
        assertFalse(MenuPaging.hasPrevious(1, 0, PER_PAGE));
        assertFalse(MenuPaging.hasNext(1, 0, PER_PAGE));
    }

    @Test
    void anExactMultipleDoesNotGrowATrailingEmptyPage() {
        assertEquals(1, MenuPaging.pageCount(PER_PAGE, PER_PAGE));
        assertEquals(2, MenuPaging.pageCount(PER_PAGE * 2, PER_PAGE));
        assertEquals(3, MenuPaging.pageCount(PER_PAGE * 2 + 1, PER_PAGE));
        assertFalse(MenuPaging.hasNext(1, PER_PAGE, PER_PAGE));
        assertTrue(MenuPaging.hasNext(1, PER_PAGE + 1, PER_PAGE));
    }

    @Test
    void slicesCoverEveryEntryExactlyOnce() {
        int total = 107;
        int seen = 0;
        for (int page = 1; page <= MenuPaging.pageCount(total, PER_PAGE); page++) {
            int first = MenuPaging.firstIndex(page, total, PER_PAGE);
            int last = MenuPaging.lastIndex(page, total, PER_PAGE);
            assertEquals(seen, first, "page " + page + " does not resume where the last ended");
            assertTrue(last > first, "page " + page + " is empty");
            seen = last;
        }
        assertEquals(total, seen, "the pages do not cover the whole list");
    }

    @Test
    void aPageThatNoLongerExistsIsPulledBackIntoRange() {
        // A viewer can be sitting on page 4 when the list shrinks to one page.
        assertEquals(1, MenuPaging.clampPage(4, 10, PER_PAGE));
        assertEquals(1, MenuPaging.clampPage(0, 10, PER_PAGE));
        assertEquals(1, MenuPaging.clampPage(-5, 10, PER_PAGE));
        assertEquals(2, MenuPaging.clampPage(9, PER_PAGE + 1, PER_PAGE));
        // And the slice for that stale page stays inside the list.
        assertEquals(0, MenuPaging.firstIndex(4, 10, PER_PAGE));
        assertEquals(10, MenuPaging.lastIndex(4, 10, PER_PAGE));
    }

    @Test
    void arrowsAppearOnlyWhereThereIsSomewhereToGo() {
        int total = PER_PAGE * 3;
        assertFalse(MenuPaging.hasPrevious(1, total, PER_PAGE));
        assertTrue(MenuPaging.hasNext(1, total, PER_PAGE));
        assertTrue(MenuPaging.hasPrevious(2, total, PER_PAGE));
        assertTrue(MenuPaging.hasNext(2, total, PER_PAGE));
        assertTrue(MenuPaging.hasPrevious(3, total, PER_PAGE));
        assertFalse(MenuPaging.hasNext(3, total, PER_PAGE));
    }

    @Test
    void anImpossiblePageSizeDoesNotDivideByZero() {
        assertEquals(1, MenuPaging.pageCount(50, 0));
        assertEquals(0, MenuPaging.firstIndex(2, 50, 0));
        assertEquals(0, MenuPaging.lastIndex(2, 50, 0));
    }
}
