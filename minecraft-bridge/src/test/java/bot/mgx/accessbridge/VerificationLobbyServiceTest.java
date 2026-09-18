package bot.mgx.accessbridge;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
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

    @Test
    void theFirstThingAskedForIsJoiningTheDiscord() {
        PlainTextComponentSerializer serializer = PlainTextComponentSerializer.plainText();
        // Rendered on demand now rather than held as constants, so that an owner can
        // reword them; with no message store wired the built-in text is what comes back.
        String prompt = serializer.serialize(VerificationLobbyService.verifyPrompt());
        String action = serializer.serialize(VerificationLobbyService.verifyAction());

        // A bot cannot DM somebody it shares no server with, so /verify is not the first
        // step and must not be presented as one: joining is, and /joined is the gate.
        String invite = GuideService.inviteDisplay();
        assertTrue(prompt.contains(invite), prompt);
        assertTrue(prompt.contains("/joined"), prompt);
        assertTrue(action.contains("JOIN"), action);
        assertTrue(action.contains(invite), action);
        assertTrue(action.contains("/joined"), action);
        assertFalse(action.contains("/verify"), "the action bar must not jump to step two");
    }
}
