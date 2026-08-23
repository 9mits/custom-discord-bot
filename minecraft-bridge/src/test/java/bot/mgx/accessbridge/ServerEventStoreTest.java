package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerEventStoreTest {
    private static final long NOW = 1_000_000L;

    private ServerEventStore open(Path directory) throws IOException {
        return new ServerEventStore(directory.resolve("server-events.json"));
    }

    @Test
    void nothingRunsByDefault(@TempDir Path directory) throws IOException {
        ServerEventStore store = open(directory);
        for (ServerEventType type : ServerEventType.values()) {
            assertFalse(store.active(type, NOW));
            assertEquals(1, store.multiplier(type, NOW));
        }
    }

    @Test
    void anOpenEndedEventStaysOnForever(@TempDir Path directory) throws IOException {
        ServerEventStore store = open(directory);
        assertTrue(store.set(ServerEventType.MONEY, true, ServerEventStore.NO_DEADLINE, NOW));
        assertTrue(store.active(ServerEventType.MONEY, NOW));
        assertTrue(store.active(ServerEventType.MONEY, NOW + 999_999_999L));
        assertEquals(2, store.multiplier(ServerEventType.MONEY, NOW));
    }

    @Test
    void aTimedEventExpiresOnItsOwnWithoutATimer(@TempDir Path directory) throws IOException {
        ServerEventStore store = open(directory);
        store.set(ServerEventType.KEY, true, NOW + 5_000L, NOW);
        assertTrue(store.active(ServerEventType.KEY, NOW + 4_999L));
        assertFalse(store.active(ServerEventType.KEY, NOW + 5_000L));
        assertEquals(1, store.multiplier(ServerEventType.KEY, NOW + 5_000L));
    }

    @Test
    void eventsRunIndependently(@TempDir Path directory) throws IOException {
        ServerEventStore store = open(directory);
        store.set(ServerEventType.MONEY, true, ServerEventStore.NO_DEADLINE, NOW);
        store.set(ServerEventType.FORTUNE, true, NOW + 1_000L, NOW);
        assertEquals(2, store.snapshot(NOW).size());
        assertEquals(1, store.snapshot(NOW + 2_000L).size());
        assertTrue(store.active(ServerEventType.MONEY, NOW + 2_000L));
        assertFalse(store.active(ServerEventType.FORTUNE, NOW + 2_000L));
    }

    @Test
    void stateSurvivesAReopen(@TempDir Path directory) throws IOException {
        open(directory).set(ServerEventType.CRATE_LUCK, true, NOW + 60_000L, NOW);
        // The whole reason this persists: Paper serves sales and crate openings
        // before the bridge connects, so a forgotten event pays the wrong rate.
        ServerEventStore reopened = open(directory);
        assertTrue(reopened.active(ServerEventType.CRATE_LUCK, NOW));
        assertFalse(reopened.active(ServerEventType.CRATE_LUCK, NOW + 60_000L));
    }

    @Test
    void anUnreadableFileReadsAsNoEvents(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("server-events.json");
        Files.writeString(file, "{ this is not json");
        ServerEventStore store = new ServerEventStore(file);
        // Never assume a multiplier nobody set — that silently inflates the economy.
        assertFalse(store.active(ServerEventType.MONEY, NOW));
    }

    @Test
    void turningOffAnEventThatIsOffChangesNothing(@TempDir Path directory) throws IOException {
        ServerEventStore store = open(directory);
        assertFalse(store.set(ServerEventType.MONEY, false, ServerEventStore.NO_DEADLINE, NOW));
    }

    @Test
    void pruneDropsOnlyDeadlinesAlreadyPassed(@TempDir Path directory) throws IOException {
        ServerEventStore store = open(directory);
        store.set(ServerEventType.MONEY, true, ServerEventStore.NO_DEADLINE, NOW);
        store.set(ServerEventType.KEY, true, NOW + 1_000L, NOW);
        assertTrue(store.prune(NOW + 2_000L));
        assertTrue(store.active(ServerEventType.MONEY, NOW + 2_000L));
        assertFalse(store.prune(NOW + 3_000L));
    }
}
