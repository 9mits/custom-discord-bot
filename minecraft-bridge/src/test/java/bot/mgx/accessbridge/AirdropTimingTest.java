package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AirdropTimingTest {
    @Test
    void defaultsUseFreshRandomDelaysAndThirtyMinuteExpiry() {
        long minimum = AirdropService.DEFAULT_MINIMUM_DELAY_MILLIS;
        long maximum = AirdropService.DEFAULT_MAXIMUM_DELAY_MILLIS;
        Set<Long> observed = new HashSet<>();
        Random random = new Random(82L);
        for (int index = 0; index < 200; index++) {
            long delay = AirdropService.randomDelayMillis(random, minimum, maximum);
            assertTrue(delay >= minimum);
            assertTrue(delay <= maximum);
            observed.add(delay);
        }

        assertEquals(Duration.ofMinutes(30).toMillis(), minimum);
        assertEquals(Duration.ofMinutes(90).toMillis(), maximum);
        assertEquals(Duration.ofMinutes(30).toMillis(), AirdropService.DEFAULT_LIFETIME_MILLIS);
        assertTrue(observed.size() > 190, "each interval should be independently randomized");
    }
}
