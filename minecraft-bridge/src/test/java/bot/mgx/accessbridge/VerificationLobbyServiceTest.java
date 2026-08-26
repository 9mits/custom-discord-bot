package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerificationLobbyServiceTest {
    @Test
    void onlyReservedVerificationWorldIsRecognised() {
        assertTrue(VerificationLobbyService.isLobbyWorldName("mgx_verification"));
        assertFalse(VerificationLobbyService.isLobbyWorldName("world"));
        assertFalse(VerificationLobbyService.isLobbyWorldName("MGX_VERIFICATION"));
        assertFalse(VerificationLobbyService.isLobbyWorldName(null));
    }
}
