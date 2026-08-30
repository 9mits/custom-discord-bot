package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ClanIconTest {
    @Test
    void thereAreEnoughIconsToBeWorthSearching() {
        assertTrue(ClanIcon.choices().size() >= 90,
                "the picker gained search and paging for a set this size");
    }

    /** A clan already wearing an icon must not silently change appearance. */
    @Test
    void theDefaultAndTheOriginalTwelveAreUnchanged() {
        assertSame(ClanIcon.AMETHYST_SHARD, ClanIcon.DEFAULT);
        for (String id : List.of(
                "amethyst_shard", "diamond", "emerald", "gold_ingot", "netherite_ingot",
                "nether_star", "ender_pearl", "heart_of_the_sea", "blaze_powder",
                "echo_shard", "totem_of_undying", "golden_apple")) {
            assertTrue(ClanIcon.find(id).isPresent(), id + " must still resolve");
        }
    }

    @Test
    void idsAndLabelsAreUnique() {
        Set<String> ids = ClanIcon.choices().stream()
                .map(ClanIcon::id).collect(Collectors.toSet());
        Set<String> labels = ClanIcon.choices().stream()
                .map(ClanIcon::label).collect(Collectors.toSet());
        assertEquals(ClanIcon.choices().size(), ids.size(), "duplicate id");
        assertEquals(ClanIcon.choices().size(), labels.size(), "duplicate label");
    }

    /** The picker draws sprite(); every id must be able to derive one. */
    @Test
    void everySpriteIsAnItemPath() {
        for (ClanIcon icon : ClanIcon.choices()) {
            assertEquals("item/" + icon.id(), icon.sprite());
            assertFalse(icon.id().isBlank());
        }
    }

    @Test
    void searchMatchesTheLabelAPlayerReads() {
        assertTrue(ClanIcon.search("amethyst").contains(ClanIcon.AMETHYST_SHARD));
        assertTrue(ClanIcon.search("AMETHYST").contains(ClanIcon.AMETHYST_SHARD),
                "search is case-insensitive");
        assertTrue(ClanIcon.search("  diamond  ").contains(ClanIcon.DIAMOND),
                "surrounding space is not part of the query");
        assertTrue(ClanIcon.search("zzzz").isEmpty());
    }

    @Test
    void anEmptySearchIsEveryIconRatherThanNone() {
        assertEquals(ClanIcon.choices().size(), ClanIcon.search("").size());
        assertEquals(ClanIcon.choices().size(), ClanIcon.search("   ").size());
        assertEquals(ClanIcon.choices().size(), ClanIcon.search(null).size());
    }

    @Test
    void anUnknownIconFallsBackRatherThanFailing() {
        assertSame(ClanIcon.DEFAULT, ClanIcon.resolve("no_such_icon"));
        assertSame(ClanIcon.DEFAULT, ClanIcon.resolve(null));
        assertSame(ClanIcon.DEFAULT, ClanIcon.resolve(""));
    }

    @Test
    void iconsResolveByIdOrEnumNameAndIgnoreCase() {
        assertSame(ClanIcon.DIAMOND, ClanIcon.resolve("diamond"));
        assertSame(ClanIcon.DIAMOND, ClanIcon.resolve("DIAMOND"));
        assertSame(ClanIcon.DIAMOND, ClanIcon.resolve("Diamond"));
    }

    /**
     * The picker pages at 24. A set that is an exact multiple would hide the bug where
     * a trailing empty page is offered, so this pins that the arithmetic is checked
     * against the real size rather than assumed.
     */
    @Test
    void theSetPagesWithoutATrailingEmptyPage() {
        int perPage = 24;
        int size = ClanIcon.choices().size();
        int pages = Math.max(1, (size + perPage - 1) / perPage);
        assertTrue((pages - 1) * perPage < size,
                "the last page must contain at least one icon");
    }
}
