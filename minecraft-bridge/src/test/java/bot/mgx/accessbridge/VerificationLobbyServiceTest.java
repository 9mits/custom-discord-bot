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
    void initialPromptMakesTheFirstActionUnmissable() {
        PlainTextComponentSerializer serializer = PlainTextComponentSerializer.plainText();
        // Rendered on demand now rather than held as constants, so that an owner can
        // reword them; with no message store wired the built-in text is what comes back.
        String prompt = serializer.serialize(VerificationLobbyService.verifyPrompt());
        String action = serializer.serialize(VerificationLobbyService.verifyAction());

        assertTrue(prompt.contains("Step 1 of 2"));
        assertTrue(prompt.contains("/verify <your Discord username>"));
        assertTrue(action.contains("STEP 1 OF 2"));
        assertTrue(action.contains("/verify <Discord username>"));
    }
}
