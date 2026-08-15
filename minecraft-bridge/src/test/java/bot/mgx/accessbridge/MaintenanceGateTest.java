package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaintenanceGateTest {
    @Test
    void aHoldRefusesAnyoneWhoIsNotExempt() {
        assertTrue(MaintenanceGate.shouldRefuse(true, false));
    }

    @Test
    void anOpenServerRefusesNobody() {
        assertFalse(MaintenanceGate.shouldRefuse(false, false));
        assertFalse(MaintenanceGate.shouldRefuse(false, true));
    }

    @Test
    void anExemptPlayerIsNotRefused() {
        assertFalse(MaintenanceGate.shouldRefuse(true, true));
    }

    @Test
    void onlyAllowedAndWhitelistKicksAreRewritten() {
        assertTrue(MaintenanceGate.isRefusable(true, false));
        assertTrue(MaintenanceGate.isRefusable(false, true));
        assertFalse(MaintenanceGate.isRefusable(false, false));
    }

    @Test
    void theJoinKickRetriesAfterGeyserHasSpawned() {
        // Zero is the immediate attempt Java honours. The later ticks exist
        // because Geyser drops a kick issued during PlayerJoinEvent — a
        // never-seen Bedrock account was walking into a held server that way.
        assertTrue(MaintenanceGate.JOIN_KICK_TICKS.length > 1);
        assertTrue(MaintenanceGate.JOIN_KICK_TICKS[0] == 0L);
        long last = 0L;
        for (long delay : MaintenanceGate.JOIN_KICK_TICKS) {
            assertTrue(delay >= last);
            last = delay;
        }
        assertTrue(last >= 20L);
    }
}
