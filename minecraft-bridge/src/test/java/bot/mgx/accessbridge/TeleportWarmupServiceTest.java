package bot.mgx.accessbridge;

import org.bukkit.event.player.PlayerTeleportEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeleportWarmupServiceTest {
    @Test
    void commandTeleportsWaitOnlyWhenATeleportActuallyExists() {
        assertTrue(TeleportWarmupService.shouldWarmup(PlayerTeleportEvent.TeleportCause.COMMAND));
        assertFalse(TeleportWarmupService.shouldWarmup(PlayerTeleportEvent.TeleportCause.PLUGIN));
        assertFalse(TeleportWarmupService.shouldWarmup(PlayerTeleportEvent.TeleportCause.NETHER_PORTAL));
    }
}
