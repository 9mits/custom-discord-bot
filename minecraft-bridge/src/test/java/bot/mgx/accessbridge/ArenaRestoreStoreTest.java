package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The record that lets a dug-up arena be put back, including after a crash. */
final class ArenaRestoreStoreTest {
    private static final UUID DUEL = UUID.randomUUID();
    private static final UUID WORLD = UUID.randomUUID();

    private static ArenaRestoreStore.Snapshot at(int x, String data) {
        return new ArenaRestoreStore.Snapshot(x, 64, 0, data, "");
    }

    /**
     * A block broken, replaced and broken again has to come back as the world
     * generated it — which is the first state seen, not the last.
     */
    @Test
    void theFirstStateSeenAtAPositionIsTheOneKept(@TempDir Path folder) throws Exception {
        ArenaRestoreStore store = new ArenaRestoreStore(folder.resolve("restore.json"));
        store.remember(DUEL, WORLD, "world", at(10, "minecraft:deepslate"));
        store.remember(DUEL, WORLD, "world", at(10, "minecraft:air"));
        store.remember(DUEL, WORLD, "world", at(11, "minecraft:stone"));

        assertEquals(2, store.size(DUEL));
        assertEquals("minecraft:deepslate", store.find(DUEL).orElseThrow()
                .blocks().get(ArenaRestoreStore.key(10, 64, 0)).blockData());
    }

    @Test
    void aRecordedArenaSurvivesARestart(@TempDir Path folder) throws Exception {
        Path file = folder.resolve("restore.json");
        ArenaRestoreStore store = new ArenaRestoreStore(file);
        store.remember(DUEL, WORLD, "world", new ArenaRestoreStore.Snapshot(
                1, 2, 3, "minecraft:chest[facing=north]", "AAAA"));
        assertTrue(store.flush());

        ArenaRestoreStore reopened = new ArenaRestoreStore(file);
        ArenaRestoreStore.ArenaEdits edits = reopened.find(DUEL).orElseThrow();
        assertEquals(WORLD, edits.worldId());
        assertEquals("world", edits.worldName());
        ArenaRestoreStore.Snapshot snapshot = edits.blocks()
                .get(ArenaRestoreStore.key(1, 2, 3));
        assertEquals("minecraft:chest[facing=north]", snapshot.blockData());
        // A container's items live in the tile entity, not in the block data.
        assertEquals("AAAA", snapshot.contents());
    }

    /** An idle flush loop runs every two seconds; it must not rewrite the file. */
    @Test
    void flushingWithoutChangesWritesNothing(@TempDir Path folder) throws Exception {
        ArenaRestoreStore store = new ArenaRestoreStore(folder.resolve("restore.json"));
        assertFalse(store.flush());
        store.remember(DUEL, WORLD, "world", at(0, "minecraft:stone"));
        assertTrue(store.flush());
        assertFalse(store.flush());
        // Recording the same position again is not a change.
        store.remember(DUEL, WORLD, "world", at(0, "minecraft:granite"));
        assertFalse(store.flush());
    }

    @Test
    void aRestoredArenaIsForgottenSoItIsNeverReplayed(@TempDir Path folder) throws Exception {
        Path file = folder.resolve("restore.json");
        ArenaRestoreStore store = new ArenaRestoreStore(file);
        store.remember(DUEL, WORLD, "world", at(5, "minecraft:sand"));
        store.flush();
        store.forget(DUEL);
        store.flush();

        assertTrue(new ArenaRestoreStore(file).all().isEmpty());
        assertTrue(Files.exists(file));
    }

    @Test
    void twoFightsRunningAtOnceKeepSeparateRecords(@TempDir Path folder) throws Exception {
        UUID other = UUID.randomUUID();
        ArenaRestoreStore store = new ArenaRestoreStore(folder.resolve("restore.json"));
        store.remember(DUEL, WORLD, "world", at(1, "minecraft:stone"));
        store.remember(other, WORLD, "world", at(1, "minecraft:dirt"));

        assertEquals(1, store.size(DUEL));
        assertEquals(1, store.size(other));
        store.forget(DUEL);
        assertEquals(0, store.size(DUEL));
        assertEquals(1, store.size(other));
    }
}
