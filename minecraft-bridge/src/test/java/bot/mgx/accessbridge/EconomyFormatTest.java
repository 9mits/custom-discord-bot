package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EconomyFormatTest {
    @Test
    void moneyUsesADollarSignAndThousandsSeparators() {
        assertEquals("$0", EconomyFormat.dollars(0));
        assertEquals("$1,234", EconomyFormat.dollars(1_234));
    }

    @Test
    void remainingTimeUsesTheLargestUsefulUnit() {
        assertEquals("expired", EconomyFormat.remaining(0));
        assertEquals("5m", EconomyFormat.remaining(5 * 60_000L));
        assertEquals("2h 3m", EconomyFormat.remaining((2 * 60L + 3L) * 60_000L));
        assertEquals("1d 2h", EconomyFormat.remaining((26L * 60L) * 60_000L));
    }

    @Test
    void amountsCanBeTypedWithCommasOrADollarSign() {
        assertEquals(250L, EconomyFormat.parseAmount("$250"));
        assertEquals(1_000L, EconomyFormat.parseAmount("1,000"));
        assertThrows(IllegalArgumentException.class, () -> EconomyFormat.parseAmount("0"));
        assertThrows(IllegalArgumentException.class, () -> EconomyFormat.parseAmount("nope"));
    }
}
