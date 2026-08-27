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
        String prompt = serializer.serialize(VerificationLobbyService.VERIFY_PROMPT);
        String action = serializer.serialize(VerificationLobbyService.VERIFY_ACTION);

        assertTrue(prompt.contains("Step 1 of 2"));
        assertTrue(prompt.contains("/verify <your Discord username>"));
        assertTrue(action.contains("STEP 1 OF 2"));
        assertTrue(action.contains("/verify <Discord username>"));
    }
}
