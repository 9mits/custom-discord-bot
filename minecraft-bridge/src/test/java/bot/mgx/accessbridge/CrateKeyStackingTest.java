package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class CrateKeyStackingTest {
    @Test
    void keysStayInOneBundleThroughNineHundredNinetyNine() {
        assertEquals(List.of(999L), CrateItems.keyPortions(999L, 999));
    }

    @Test
    void theThousandthKeyStartsTheNextBundleWithoutLosingValue() {
        assertEquals(List.of(999L, 1L), CrateItems.keyPortions(1_000L, 999));
        assertEquals(10_000L, CrateItems.keyPortions(10_000L, 999)
                .stream().mapToLong(Long::longValue).sum());
    }

    @Test
    void invalidAmountsCannotCreateCorruptBundles() {
        assertEquals(List.of(), CrateItems.keyPortions(0L, 999));
        assertThrows(IllegalArgumentException.class, () -> CrateItems.keyPortions(-1L, 999));
        assertThrows(IllegalArgumentException.class, () -> CrateItems.keyPortions(1L, 0));
    }
}
