package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessedActionStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void actionResultsRemainIdempotentAcrossReload() throws Exception {
        Executor direct = Runnable::run;
        Path path = temporaryDirectory.resolve("processed-actions.properties");
        ProcessedActionStore first = new ProcessedActionStore(path, direct);
        first.put("application:1:approve", new ProcessedActionStore.Result(true, "")).join();

        ProcessedActionStore reloaded = new ProcessedActionStore(path, direct);
        ProcessedActionStore.Result result = reloaded.get("application:1:approve").orElseThrow();

        assertTrue(result.success());
        assertEquals("", result.error());
    }

    @Test
    void reserveClaimsAKeyOnlyOnce() throws Exception {
        ProcessedActionStore store = new ProcessedActionStore(
                temporaryDirectory.resolve("processed-actions.properties"),
                Runnable::run
        );

        assertTrue(store.reserve("application:1:approve"));
        assertTrue(store.get("application:1:approve").isEmpty());
        assertTrue(!store.reserve("application:1:approve"));
    }
}
