package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AfkProtectionTest {
    @Test
    void afkPlayersCannotBePushedAndCollisionReturnsWhenTheyWake() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/bot/mgx/accessbridge/AfkService.java"
        ));
        assertEquals(1, occurrences(source, "player.setCollidable(false)"));
        // Wake, quit/join safety, and plugin shutdown all restore ordinary collision.
        org.junit.jupiter.api.Assertions.assertTrue(
                occurrences(source, "setCollidable(true)") >= 4
        );
    }

    private static int occurrences(String text, String needle) {
        return (text.length() - text.replace(needle, "").length()) / needle.length();
    }

    @Test
    void blocksEnvironmentalDamageWhileAfk() {
        assertEquals(
                AfkProtection.Decision.BLOCK,
                AfkProtection.decide(true, true, false, false)
        );
    }

    @Test
    void ignoresDamageWhenNotAfk() {
        assertEquals(
                AfkProtection.Decision.IGNORE,
                AfkProtection.decide(true, false, false, false)
        );
    }

    @Test
    void ignoresDamageWhenDisabled() {
        assertEquals(
                AfkProtection.Decision.IGNORE,
                AfkProtection.decide(false, true, false, false)
        );
    }

    @Test
    void aPlayerHitWakesRatherThanBounces() {
        assertEquals(
                AfkProtection.Decision.WAKE,
                AfkProtection.decide(true, true, true, false)
        );
    }

    @Test
    void aPlayerHitInTheVoidStillWakes() {
        assertEquals(
                AfkProtection.Decision.WAKE,
                AfkProtection.decide(true, true, true, true)
        );
    }

    @Test
    void theVoidStillKillsAnAfkPlayer() {
        assertEquals(
                AfkProtection.Decision.IGNORE,
                AfkProtection.decide(true, true, false, true)
        );
    }
}
