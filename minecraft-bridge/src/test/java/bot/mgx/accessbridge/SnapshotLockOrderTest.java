package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The snapshot must not reach into another store while holding its own lock.
 *
 * <p>This deadlocked a live server. {@code snapshot()} was synchronised and gathered the
 * metrics and the catalogue from inside it; both reach into the auction and clan stores,
 * and those read their own limits back through {@code variables.integer(...)}, which
 * locks {@link GameVariableStore}. Against any game thread doing the reverse the order
 * inverted and both parked forever — the bridge stopped answering and the scheduler
 * stopped spawning Airdrops and Huge Amethyst blocks, with nothing in the log to say why.
 *
 * <p>There is no unit test that can catch a lock-order inversion by running it; it is a
 * race that needs two threads to interleave. So this reads the shape instead: whatever
 * calls out to another store has to happen before the lock is taken.
 */
final class SnapshotLockOrderTest {
    private static String source() throws Exception {
        return Files.readString(
                Path.of("src/main/java/bot/mgx/accessbridge/GameVariableStore.java"),
                StandardCharsets.UTF_8);
    }

    @Test
    void theOuterSnapshotIsNotSynchronised() throws Exception {
        String store = source();
        assertTrue(store.contains("\n    JsonObject snapshot() {"),
                "snapshot() must stay the unsynchronised entry point");
        assertFalse(store.contains("synchronized JsonObject snapshot()"),
                "snapshot() must not hold this lock while gathering from other stores");
    }

    @Test
    void everyCrossStoreReadHappensBeforeTheLock() throws Exception {
        String store = source();
        int outer = store.indexOf("\n    JsonObject snapshot() {");
        int inner = store.indexOf("private synchronized JsonObject snapshotWith(");
        assertTrue(outer > 0 && inner > outer, "expected snapshot() then snapshotWith()");
        String unlocked = store.substring(outer, inner);
        for (String call : new String[] {"metricsSupplier.get()", "custom.snapshot()"}) {
            assertTrue(unlocked.contains(call),
                    call + " reaches into another store, so it must run before the lock");
        }
        String locked = store.substring(inner);
        for (String call : new String[] {"metricsSupplier.get()", "custom.snapshot()"}) {
            assertFalse(locked.contains(call),
                    call + " must not be called while this object's lock is held");
        }
    }
}
