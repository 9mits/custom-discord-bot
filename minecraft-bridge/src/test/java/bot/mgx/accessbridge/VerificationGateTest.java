package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerificationGateTest {
    @Test
    void unknownAccountIsRefusedEvenWhenPaperInitiallyAllowsIt() {
        assertTrue(VerificationGate.shouldRefuse(true, false, false));
    }

    @Test
    void unknownAccountGetsOurInstructionsInsteadOfVanillaWhitelistText() {
        assertTrue(VerificationGate.shouldRefuse(false, true, false));
    }

    @Test
    void verifiedAccountCanOverrideAStaleWhitelistRefusal() {
        assertFalse(VerificationGate.shouldRefuse(false, true, true));
    }

    @Test
    void BanAndFullServerRefusalsAreNotRewritten() {
        assertFalse(VerificationGate.shouldRefuse(false, false, false));
    }

    @Test
    void joinFallbackRetriesAfterFloodgateFinishesSpawning() {
        assertTrue(VerificationGate.JOIN_KICK_TICKS.length > 1);
        assertTrue(VerificationGate.JOIN_KICK_TICKS[0] == 0L);
        assertTrue(VerificationGate.JOIN_KICK_TICKS[
                VerificationGate.JOIN_KICK_TICKS.length - 1
        ] >= 20L);
    }
}
