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

    /**
     * "It says join the new Discord but doesn't show the link" — the title said where to
     * go without saying where that was. Every surface that tells a player to join has to
     * carry the address, because each one is the only one somebody is looking at.
     */
    @Test
    void everySurfaceThatSaysJoinAlsoSaysWhere() throws Exception {
        String invite = GuideService.inviteDisplay();
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/bot/mgx/accessbridge/VerificationLobbyService.java"));

        // The title card, the action bar and the repeated chat line are built from the
        // live setting rather than spelling an address out, so they cannot drift from it.
        String title = source.substring(source.indexOf("private static LobbyTitle verifyTitle()"));
        assertTrue(title.substring(0, title.indexOf("}")).contains("GuideService.inviteDisplay()"),
                "the title names the invite");
        PlainTextComponentSerializer plain = PlainTextComponentSerializer.plainText();
        assertTrue(plain.serialize(VerificationLobbyService.verifyAction()).contains(invite));
        assertTrue(plain.serialize(VerificationLobbyService.verifyPrompt()).contains(invite));

        String bars = source.substring(source.indexOf("private void showLobbyBars"));
        assertTrue(bars.substring(0, bars.indexOf("lobbyBars.put")).contains("+ invite"),
                "one of the boss bars is the address itself");
        String refusal = source.substring(source.indexOf("private void refuseUntilJoined"));
        assertTrue(refusal.substring(0, refusal.indexOf("\n    }")).contains("inviteDisplay()"),
                "being refused tells you where to go");
    }
}
