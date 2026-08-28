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
     * A triple pull is three separate crates: three keys, three rewards, three reels.
     * The rows it uses have to be real slots in the 45-slot roll screen, and each reel
     * needs a clear seven-slot strip with its own centre.
     */
    @Test
    void tripleRowsFitTheRollScreenWithoutOverlapping() {
        int[] rows = {9, 18, 27};
        assertEquals(CrateService.TRIPLE_PULL_SIZE, rows.length);

        java.util.Set<Integer> used = new java.util.LinkedHashSet<>();
        for (int row : rows) {
            for (int slot = row; slot <= row + 8; slot++) {
                assertTrue(slot >= 0 && slot < 45, "slot " + slot + " is off the screen");
                assertTrue(used.add(slot), "row " + row + " overlaps another reel");
            }
            int reelFirst = row + 1;
            int reelLast = row + 7;
            assertEquals(row + 4, (reelFirst + reelLast) / 2,
                    "the winning slot is not the middle of the strip");
        }
        assertEquals(27, used.size());
    }

    /**
     * The reel must not open over an effect. Anything with something to watch reports a
     * length the crate menu waits out; everything else reports zero and carries straight
     * on, which is what keeps an ordinary auto run at full speed.
     */
    @Test
    void onlyRevealsWorthWatchingHoldTheCrateMenu() {
        assertEquals(0L, CosmeticEffectService.revealDurationTicks(
                CrateCatalog.RevealTier.NONE));
        assertEquals(0L, CosmeticEffectService.revealDurationTicks(
                CrateCatalog.RevealTier.LEGENDARY));

        long mythic = CosmeticEffectService.revealDurationTicks(CrateCatalog.RevealTier.MYTHIC);
        long exotic = CosmeticEffectService.revealDurationTicks(CrateCatalog.RevealTier.SECRET);
        long secret = CosmeticEffectService.revealDurationTicks(
                CrateCatalog.RevealTier.GENUINE_SECRET);

        assertTrue(mythic > 0L);
        assertTrue(exotic > mythic, "the rarer reveal runs longer");
        assertTrue(secret > exotic, "the rarest reveal runs longest");
    }

    /** The tier names moved up one: what was Secret is Exotic, and the jackpot is Secret. */
    @Test
    void theJackpotIsTheOneCalledSecret() {
        assertEquals("Secret", CosmeticCatalog
                .find(CosmeticCatalog.HIDDEN_AMETHYST_COSMETIC_ID).orElseThrow().rarityDisplay());
        assertEquals("Exotic", CosmeticCatalog
                .find("event_horizon").orElseThrow().rarityDisplay());
        assertEquals("Exotic", CrateCatalog.Category.SECRET.displayName());
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
