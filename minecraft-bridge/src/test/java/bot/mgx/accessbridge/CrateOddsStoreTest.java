package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CrateOddsStoreTest {
    private static final CrateKind KIND = CrateKind.DEFAULT;
    private static final double RATE = 0.10d;

    private static CrateOddsStore store(Path directory) throws Exception {
        return new CrateOddsStore(directory.resolve("crate-odds.json"));
    }

    private static void open(CrateOddsStore store, UUID player, int times, boolean rare) {
        for (int i = 0; i < times; i++) {
            store.record(KIND, player, rare, RATE);
        }
    }

    @Test
    void anOpenContributesItsOwnExpectationRatherThanThePublishedRate(@TempDir Path dir)
            throws Exception {
        CrateOddsStore store = store(dir);
        store.record(KIND, UUID.randomUUID(), false, 0.25d);
        assertEquals(0.25d, store.counts(KIND).expectedHits(), 1e-9);
    }

    /**
     * The fairness inversion this closes: a whale grinding a banked key stack used to set
     * the odds every casual player rolled on.
     */
    @Test
    void noSingleAccountMayOutweighTheWindow(@TempDir Path dir) throws Exception {
        CrateOddsStore store = store(dir);
        UUID whale = UUID.randomUUID();

        open(store, whale, (int) CrateOddsStore.PLAYER_WINDOW_CAP + 500, true);

        assertEquals(CrateOddsStore.PLAYER_WINDOW_CAP, store.counts(KIND).opens(),
                "opens past the quota must not be counted");
        assertEquals(CrateOddsStore.PLAYER_WINDOW_CAP, store.contribution(KIND, whale));
    }

    @Test
    void aPlayerAtQuotaDoesNotBlockAnybodyElse(@TempDir Path dir) throws Exception {
        CrateOddsStore store = store(dir);
        UUID whale = UUID.randomUUID();
        UUID casual = UUID.randomUUID();

        open(store, whale, (int) CrateOddsStore.PLAYER_WINDOW_CAP + 200, true);
        open(store, casual, 10, false);

        assertEquals(CrateOddsStore.PLAYER_WINDOW_CAP + 10L, store.counts(KIND).opens());
        assertEquals(10L, store.contribution(KIND, casual));
    }

    /** A quota that never decays is a player permanently barred from the sample. */
    @Test
    void quotasDecayWithTheWindow(@TempDir Path dir) throws Exception {
        CrateOddsStore store = store(dir);
        UUID[] players = new UUID[6];
        for (int i = 0; i < players.length; i++) {
            players[i] = UUID.randomUUID();
        }

        // Six players at quota carries the window past its decay point.
        for (UUID player : players) {
            open(store, player, (int) CrateOddsStore.PLAYER_WINDOW_CAP, false);
        }

        assertTrue(store.counts(KIND).opens() < CrateOddsBalance.WINDOW_OPENS,
                "the window must have decayed");
        assertTrue(store.contribution(KIND, players[0]) < CrateOddsStore.PLAYER_WINDOW_CAP,
                "a decayed window has to give quota back");
    }

    @Test
    void countersSurviveARestart(@TempDir Path dir) throws Exception {
        UUID player = UUID.randomUUID();
        CrateOddsStore first = store(dir);
        open(first, player, 20, true);

        CrateOddsStore reloaded = store(dir);
        assertEquals(20L, reloaded.counts(KIND).opens());
        assertEquals(20L, reloaded.counts(KIND).rareHits());
        assertEquals(20 * RATE, reloaded.counts(KIND).expectedHits(), 1e-9);
        assertEquals(20L, reloaded.contribution(KIND, player),
                "a restart must not hand a player a fresh quota");
    }

    /**
     * A window written before opens carried their own expectation cannot be compared
     * against one that does, and there is no honest way to infer the luck behind it.
     */
    @Test
    void aWindowWithoutExpectationsIsDiscardedRatherThanGuessedAt(@TempDir Path dir)
            throws Exception {
        Path file = dir.resolve("crate-odds.json");
        Files.writeString(file, "{\"DEFAULT\":{\"opens\":3000,\"rare\":900}}");

        CrateOddsStore store = new CrateOddsStore(file);
        assertEquals(0L, store.counts(KIND).opens());
        assertEquals(0d, store.counts(KIND).expectedHits(), 1e-9);
    }

    @Test
    void aTruncatedFileIsNoCountersRatherThanACrash(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("crate-odds.json");
        Files.writeString(file, "{\"DEFAULT\":{\"opens\":30,");

        CrateOddsStore store = new CrateOddsStore(file);
        assertEquals(0L, store.counts(KIND).opens());
    }

    @Test
    void resetClearsQuotasAsWellAsCounts(@TempDir Path dir) throws Exception {
        CrateOddsStore store = store(dir);
        UUID player = UUID.randomUUID();
        open(store, player, 30, true);

        store.reset();

        assertEquals(0L, store.counts(KIND).opens());
        assertEquals(0L, store.contribution(KIND, player));
    }
}
