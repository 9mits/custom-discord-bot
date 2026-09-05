package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AmethystProgressStoreTest {
    @TempDir
    Path temporary;

    @Test
    void countersPersistIndependentlyAndNotifyTheLeaderboard() throws Exception {
        Path file = temporary.resolve("amethyst-progress.json");
        UUID player = UUID.randomUUID();
        AtomicInteger changes = new AtomicInteger();
        AmethystProgressStore store = new AmethystProgressStore(file);
        store.onChange(changes::incrementAndGet);

        assertEquals(3L, store.recordCratesOpened(player, 3));
        assertEquals(1L, store.recordAirdropOpened(player));
        assertEquals(125L, store.recordDragonDamage(player, 125));
        assertEquals(1L, store.recordDragonCrystal(player));
        assertEquals(1L, store.recordDragonCrate(player));
        assertEquals(1L, store.recordDragonEgg(player));
        assertEquals(6, changes.get());

        AmethystProgressStore reloaded = new AmethystProgressStore(file);
        assertEquals(new AmethystProgressStore.Counts(3L, 1L, 125L, 1L, 1L, 1L),
                reloaded.counts(player));
        assertEquals(1, reloaded.snapshots().size());
    }

    @Test
    void resetClearsBothBoardsWithoutCreatingNegativeProgress() throws Exception {
        Path file = temporary.resolve("reset-progress.json");
        UUID player = UUID.randomUUID();
        AmethystProgressStore store = new AmethystProgressStore(file);

        assertEquals(0L, store.recordCratesOpened(player, -2));
        assertTrue(store.snapshots().isEmpty());
        store.recordCratesOpened(player, 2);
        store.recordAirdropOpened(player);
        assertEquals(1, store.clearAll());

        assertTrue(store.snapshots().isEmpty());
        assertTrue(new AmethystProgressStore(file).snapshots().isEmpty());
    }

    @Test
    void testFixturesSetExactCountsAndZeroRemovesThePlayer() throws Exception {
        Path file = temporary.resolve("test-fixture-progress.json");
        UUID player = UUID.randomUUID();
        AtomicInteger changes = new AtomicInteger();
        AmethystProgressStore store = new AmethystProgressStore(file);
        store.onChange(changes::incrementAndGet);

        assertEquals(
                new AmethystProgressStore.Counts(12L, 8L),
                store.set(player, 12L, 8L)
        );
        assertEquals(
                new AmethystProgressStore.Counts(12L, 8L),
                new AmethystProgressStore(file).counts(player)
        );

        assertEquals(
                new AmethystProgressStore.Counts(0L, 0L),
                store.set(player, 0L, 0L)
        );
        assertTrue(store.snapshots().isEmpty());
        assertTrue(new AmethystProgressStore(file).snapshots().isEmpty());
        assertEquals(2, changes.get());
    }
}
