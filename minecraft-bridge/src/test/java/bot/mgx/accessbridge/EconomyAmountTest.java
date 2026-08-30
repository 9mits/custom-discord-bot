package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EconomyAmountTest {
    private static long parse(String raw) {
        return EconomyFormat.parseAmount(raw);
    }

    private static String rejects(String raw) {
        return assertThrows(IllegalArgumentException.class, () -> parse(raw)).getMessage();
    }

    @Test
    void plainNumbersStillWork() {
        assertEquals(500L, parse("500"));
        assertEquals(100_000_000L, parse("100000000"));
        assertEquals(1_500L, parse("1,500"));
        assertEquals(2_000L, parse("$2000"));
        assertEquals(750L, parse("  750  "));
    }

    @Test
    void theAbbreviationsPlayersAskedFor() {
        assertEquals(1_000L, parse("1k"));
        assertEquals(200_000L, parse("200k"));
        assertEquals(1_500L, parse("1.5k"));
        assertEquals(1_000_000L, parse("1m"));
        assertEquals(2_000_000L, parse("2m"));
        assertEquals(1_400_000L, parse("1.4m"));
    }

    @Test
    void caseDoesNotMatterAndBiggerUnitsWork() {
        assertEquals(1_000L, parse("1K"));
        assertEquals(1_400_000L, parse("1.4M"));
        assertEquals(2_000_000_000L, parse("2B"));
        assertEquals(1_500_000_000_000L, parse("1.5t"));
    }

    /** What the game prints must be what a player can type back. */
    @Test
    void everyCompactFigureTheGamePrintsCanBeTypedBackIn() {
        for (long amount : new long[]{999L, 1_000L, 1_500L, 92_200L, 5_000_000L, 1_400_000_000L}) {
            String shown = EconomyFormat.compactDollars(amount);
            long round = parse(shown);
            assertTrue(round > 0L, shown + " must parse");
            // compactDollars rounds to one decimal, so the round trip is the shown figure
            // rather than the original -- but it must never be unreadable.
            assertEquals(parse(shown.replace("$", "")), round);
        }
        assertEquals(5_000_000L, parse(EconomyFormat.compactDollars(5_000_000L)));
        assertEquals(1_000L, parse(EconomyFormat.compactDollars(1_000L)));
    }

    /** Rounding money silently is picking a different number than the one typed. */
    @Test
    void aFractionalResultIsRefusedRatherThanRounded() {
        String message = rejects("1.2345k");
        assertTrue(message.contains("whole dollars"), message);
        rejects("1.5");
        rejects("0.5");
    }

    @Test
    void halfUnitsThatLandWholeAreFine() {
        assertEquals(500L, parse("0.5k"));
        assertEquals(1_250L, parse("1.25k"));
        assertEquals(100L, parse("0.1k"));
    }

    @Test
    void nonsenseIsRefusedWithTheAbbreviationsInTheMessage() {
        for (String bad : new String[]{"", "   ", "abc", "k", "m", "1kk", "1k2", "--5", "1e5"}) {
            String message = rejects(bad);
            assertTrue(message.contains("2.5k") || message.contains("whole dollars"),
                    "the error for " + bad + " should teach the format: " + message);
        }
        assertEquals(EconomyFormat.AMOUNT_HELP, rejects(null));
    }

    @Test
    void zeroAndNegativeAreRefused() {
        assertTrue(rejects("0").contains("at least $1"));
        assertTrue(rejects("0k").contains("at least $1"));
        assertTrue(rejects("-5").contains("at least $1"));
        assertTrue(rejects("-1m").contains("at least $1"));
    }

    /** A long overflow must be a message, never a wrapped-around balance. */
    @Test
    void anAmountTooBigToHoldIsRefused() {
        assertTrue(rejects("999999999t").contains("too large"));
        rejects("999999999999999999999999999999");
    }
}
