package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CrateFilterStoreTest {
    @TempDir
    Path folder;

    @Test
    void togglingReportsTheNewStateAndSurvivesAReload() throws IOException {
        Path file = folder.resolve("crate-filters.json");
        UUID player = UUID.randomUUID();
        CrateFilterStore store = new CrateFilterStore(file);

        assertFalse(store.discards(player, "raw_iron"));
        assertTrue(store.toggle(player, "raw_iron"));
        assertTrue(store.discards(player, "raw_iron"));
        assertEquals(1, store.count(player));

        CrateFilterStore reopened = new CrateFilterStore(file);
        assertTrue(reopened.discards(player, "raw_iron"));
        assertFalse(reopened.toggle(player, "raw_iron"));
        assertEquals(0, reopened.count(player));
        assertFalse(new CrateFilterStore(file).discards(player, "raw_iron"));
    }

    @Test
    void filtersAreSeparatePerPlayerAndIdsAreCaseInsensitive() throws IOException {
        Path file = folder.resolve("crate-filters.json");
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        CrateFilterStore store = new CrateFilterStore(file);

        store.toggle(first, "  RAW_IRON  ");

        assertTrue(store.discards(first, "raw_iron"));
        assertFalse(store.discards(second, "raw_iron"));
        assertEquals(Set.of("raw_iron"), store.all(first));
        assertEquals(Set.of(), store.all(second));
    }

    @Test
    void clearingReportsHowMuchItRemoved() throws IOException {
        Path file = folder.resolve("crate-filters.json");
        UUID player = UUID.randomUUID();
        CrateFilterStore store = new CrateFilterStore(file);
        store.toggle(player, "raw_iron");
        store.toggle(player, "raw_gold");

        assertEquals(2, store.clear(player));
        assertEquals(0, store.clear(player));
        assertEquals(0, new CrateFilterStore(file).count(player));
    }

    /**
     * A cosmetic goes to {@code /wardrobe} rather than the inventory, so it cannot be
     * the reason an AFK run backs up — and throwing a 1-in-500,000 aura away by
     * mis-click is not a mistake the picker should be able to make.
     */
    @Test
    void thePickerNeverOffersACosmetic() {
        for (CrateKind kind : CrateKind.values()) {
            List<CrateCatalog.Reward> offered = kind.rewards().stream()
                    .filter(reward -> !reward.cosmetic())
                    .toList();
            assertFalse(offered.isEmpty(), kind + " has nothing to trash");
            assertTrue(offered.stream().noneMatch(CrateCatalog.Reward::cosmetic));
            assertTrue(offered.size() < kind.rewards().size(),
                    kind + " should still hold cosmetics that cannot be trashed");
        }
    }
}
