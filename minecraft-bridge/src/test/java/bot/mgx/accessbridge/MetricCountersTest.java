package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class MetricCountersTest {
    @TempDir
    Path directory;

    @Test
    void countersPersistAtomicallyAndSaturateInsteadOfWrapping() throws Exception {
        Path file = directory.resolve("metrics.json");
        MetricCounters counters = new MetricCounters(file);
        counters.increment("crates.opened", Long.MAX_VALUE);
        counters.increment("crates.opened", 1L);

        counters.flush();

        assertEquals(Long.MAX_VALUE, new MetricCounters(file).value("crates.opened"));
        assertFalse(Files.exists(directory.resolve("metrics.json.tmp")));
    }
}
