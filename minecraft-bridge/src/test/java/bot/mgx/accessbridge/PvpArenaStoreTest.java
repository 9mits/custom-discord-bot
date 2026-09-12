package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PvpArenaStoreTest {
    @TempDir
    Path temporary;

    @Test
    void anArenaIsNotMatchableUntilEveryRequiredPointExists() throws Exception {
        Path file = temporary.resolve("arenas.json");
        PvpArenaStore store = new PvpArenaStore(file);
        PvpArenaStore.Arena created = store.create("duel-one", PvpMode.RANKED_DUEL);
        assertFalse(created.readyFor(PvpMode.RANKED_DUEL));

        String world = UUID.randomUUID().toString();
        store.setCorner("duel-one", 1, point(world, 0, 64, 0));
        store.setCorner("duel-one", 2, point(world, 30, 80, 30));
        store.setSpectator("duel-one", point(world, 15, 75, 15));
        store.setSpawn("duel-one", 1, point(world, 3, 65, 3));
        store.setSpawn("duel-one", 2, point(world, 27, 65, 27));
        assertFalse(store.find("duel-one").orElseThrow().readyFor(PvpMode.RANKED_DUEL));
        store.setEnabled("duel-one", true);

        PvpArenaStore reopened = new PvpArenaStore(file);
        assertTrue(reopened.find("duel-one").orElseThrow().readyFor(PvpMode.RANKED_DUEL));
        assertEquals(1, reopened.ready(PvpMode.CASUAL_DUEL).size());
        assertEquals(0, reopened.ready(PvpMode.DOUBLES).size());
    }

    @Test
    void invalidIdsAndSkippedSpawnSlotsAreRefused() throws Exception {
        PvpArenaStore store = new PvpArenaStore(temporary.resolve("invalid.json"));
        assertThrows(IllegalArgumentException.class,
                () -> store.create("Bad Arena", PvpMode.RANKED_DUEL));
        store.create("team-a", PvpMode.DOUBLES);
        String world = UUID.randomUUID().toString();
        assertThrows(IllegalArgumentException.class,
                () -> store.setSpawn("team-a", 2, point(world, 0, 65, 0)));
    }

    private static PvpArenaStore.Point point(String world, double x, double y, double z) {
        return new PvpArenaStore.Point(world, "arena", x, y, z, 0f, 0f);
    }
}
