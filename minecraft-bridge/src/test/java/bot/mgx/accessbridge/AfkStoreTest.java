package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AfkStoreTest {
    private static AfkStore store(Path dir) throws Exception {
        return new AfkStore(dir.resolve("afk.json"));
    }

    @Test
    void stretchesAccumulateAcrossSessions(@TempDir Path dir) throws Exception {
        AfkStore store = store(dir);
        UUID player = UUID.randomUUID();

        store.record(player, 60_000L);
        store.record(player, 30_000L);

        AfkStore.Totals totals = store.totals(player);
        assertEquals(90_000L, totals.afkMillis());
        assertEquals(90L, totals.afkSeconds());
        assertEquals(2L, totals.sessions(), "two separate stretches, not one");
    }

    /** The whole point: a restart used to lose every figure, because nothing was written. */
    @Test
    void totalsSurviveARestart(@TempDir Path dir) throws Exception {
        UUID player = UUID.randomUUID();
        store(dir).record(player, 120_000L);

        AfkStore reloaded = store(dir);
        assertEquals(120_000L, reloaded.totals(player).afkMillis());
        assertEquals(1L, reloaded.totals(player).sessions());
    }

    @Test
    void anUnknownPlayerIsZeroRatherThanAbsent(@TempDir Path dir) throws Exception {
        AfkStore.Totals totals = store(dir).totals(UUID.randomUUID());
        assertEquals(0L, totals.afkMillis());
        assertEquals(0L, totals.sessions());
    }

    @Test
    void aZeroLengthStretchIsNotASession(@TempDir Path dir) throws Exception {
        AfkStore store = store(dir);
        UUID player = UUID.randomUUID();
        store.record(player, 0L);
        store.record(player, -5L);
        assertEquals(0L, store.totals(player).sessions());
    }

    @Test
    void totalsAcrossPlayersAddUp(@TempDir Path dir) throws Exception {
        AfkStore store = store(dir);
        store.record(UUID.randomUUID(), 10_000L);
        store.record(UUID.randomUUID(), 25_000L);
        assertEquals(35_000L, store.totalMillis());
        assertEquals(2, store.all().size());
    }

    @Test
    void aTruncatedFileIsNoHistoryRatherThanACrash(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("afk.json");
        Files.createDirectories(dir);
        Files.writeString(file, "{\"broken\":");
        assertEquals(0L, new AfkStore(file).totalMillis());
    }

    @Test
    void resetClearsEverything(@TempDir Path dir) throws Exception {
        AfkStore store = store(dir);
        UUID player = UUID.randomUUID();
        store.record(player, 60_000L);
        store.reset();
        assertEquals(0L, store.totals(player).afkMillis());
        assertTrue(store.all().isEmpty());
    }
}
