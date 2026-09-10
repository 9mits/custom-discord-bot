package bot.mgx.accessbridge;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ServerMetricsTest {
    @TempDir
    Path directory;

    @Test
    void totalBalanceSaturatesInsteadOfPublishingANegativeMetric() throws Exception {
        EconomyStore economy = new EconomyStore(directory.resolve("balances.json"));
        economy.set(UUID.randomUUID(), Long.MAX_VALUE);
        economy.set(UUID.randomUUID(), 1L);

        JsonObject metrics = ServerMetrics.gather(economy, null, null, null, null);

        assertEquals(Long.MAX_VALUE, metrics.get("economy.total_balance").getAsLong());
    }
}
