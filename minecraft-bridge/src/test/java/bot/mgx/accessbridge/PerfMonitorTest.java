package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PerfMonitorTest {
    private static PerfMonitor.Row row(java.util.List<PerfMonitor.Row> rows, String name) {
        return rows.stream().filter(candidate -> candidate.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void aTrackedTaskIsCountedAndAWindowResetKeepsTheLifetimeTotal() {
        AtomicInteger ran = new AtomicInteger();
        Runnable task = PerfMonitor.track("test.counted", ran::incrementAndGet);
        task.run();
        task.run();

        assertEquals(2, ran.get());
        assertEquals(2L, row(PerfMonitor.drain(), "test.counted").runs());
        assertEquals(0L, row(PerfMonitor.drain(), "test.counted").runs(), "a window starts empty");
        assertEquals(2L, row(PerfMonitor.snapshot(), "test.counted").runs(), "lifetime totals survive");
    }

    @Test
    void aFailingTaskStillThrowsAndIsCountedAsFailed() {
        Runnable task = PerfMonitor.track("test.failing", () -> {
            throw new IllegalStateException("boom");
        });
        assertThrows(IllegalStateException.class, task::run);

        PerfMonitor.Row failed = row(PerfMonitor.snapshot(), "test.failing");
        assertEquals(1L, failed.runs());
        assertEquals(1L, failed.failures());
        assertTrue(failed.nanos() >= 0L);
    }
}
