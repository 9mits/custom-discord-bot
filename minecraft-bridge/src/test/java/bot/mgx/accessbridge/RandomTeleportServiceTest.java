package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RandomTeleportServiceTest {
    @Test
    void radiusStaysInsideTheConfiguredAnnulus() {
        assertEquals(500d, RandomTeleportService.radius(500d, 25_000d, 0d));
        assertTrue(RandomTeleportService.radius(500d, 25_000d, 1d) < 25_000d);
    }

    @Test
    void samplingIsUniformByArea() {
        double halfway = RandomTeleportService.radius(0d, 100d, 0.5d);

        assertEquals(Math.sqrt(5_000d), halfway, 0.0001d);
    }
}
