package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoPayStoreTest {
    private static final UUID PAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TARGET = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private static AutoPayStore.Plan plan(long amount, int interval, boolean all) {
        return new AutoPayStore.Plan(TARGET, "Alt", amount, interval, all);
    }

    private AutoPayStore open(Path directory) throws IOException {
        return new AutoPayStore(directory.resolve("autopay.json"));
    }

    @Test
    void nobodyPaysByDefault(@TempDir Path directory) throws IOException {
        assertTrue(open(directory).plan(PAYER).isEmpty());
    }

    @Test
    void aPlanSurvivesAReopen(@TempDir Path directory) throws IOException {
        open(directory).set(PAYER, plan(500L, 30, false));
        AutoPayStore.Plan reloaded = open(directory).plan(PAYER).orElseThrow();
        assertEquals(TARGET, reloaded.target());
        assertEquals(500L, reloaded.amount());
        assertEquals(30, reloaded.intervalSeconds());
        assertFalse(reloaded.sendAll());
    }

    @Test
    void payingYourselfIsRefused(@TempDir Path directory) throws IOException {
        AutoPayStore store = open(directory);
        assertThrows(IllegalArgumentException.class, () -> store.set(
                PAYER, new AutoPayStore.Plan(PAYER, "Me", 100L, 30, false)
        ));
    }

    @Test
    void theIntervalIsHeldToItsRail() {
        assertThrows(IllegalArgumentException.class, () -> plan(100L, 1, false));
        assertThrows(IllegalArgumentException.class, () -> plan(100L, 99_999, false));
        assertEquals(
                AutoPayStore.MINIMUM_INTERVAL_SECONDS,
                plan(100L, AutoPayStore.MINIMUM_INTERVAL_SECONDS, false).intervalSeconds()
        );
    }

    @Test
    void aFixedAmountMustBeWorthSending() {
        assertThrows(IllegalArgumentException.class, () -> plan(0L, 30, false));
        // Send-all ignores the amount, so zero is fine there.
        assertEquals(0L, plan(0L, 30, true).amount());
    }

    @Test
    void clearReportsWhetherThereWasAnythingToStop(@TempDir Path directory) throws IOException {
        AutoPayStore store = open(directory);
        assertFalse(store.clear(PAYER));
        store.set(PAYER, plan(100L, 30, false));
        assertTrue(store.clear(PAYER));
        assertTrue(store.plan(PAYER).isEmpty());
    }

    @Test
    void oneBadRowDoesNotCostEverybodyElseTheirSetup(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("autopay.json");
        Files.writeString(file, "{"
                + "\"" + PAYER + "\":{\"target\":\"" + TARGET + "\",\"interval\":30,\"amount\":5},"
                + "\"not-a-uuid\":{\"target\":\"also-bad\",\"interval\":30}"
                + "}");
        AutoPayStore store = new AutoPayStore(file);
        assertTrue(store.plan(PAYER).isPresent());
        assertEquals(1, store.all().size());
    }

    @Test
    void intervalCycleWrapsAround() {
        assertEquals(30, AutoPayService.nextInterval(5));
        assertEquals(5, AutoPayService.nextInterval(300));
        // Anything off the cycle lands on the default rather than sticking.
        assertEquals(AutoPayStore.DEFAULT_INTERVAL_SECONDS, AutoPayService.nextInterval(47));
    }
}
