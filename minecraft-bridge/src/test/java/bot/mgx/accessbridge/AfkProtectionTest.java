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

    /**
     * The hold restores an AFK player's horizontal position every tick, and waking used
     * to need a whole block of travel. The hold therefore cancelled the very movement
     * that would have released it: the only way out was to out-run a per-tick teleport.
     */
    @Test
    void beingHeldInPlaceCannotAlsoBeTheThingThatPreventsWakingUp() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/bot/mgx/accessbridge/AfkService.java"
        ));
        int move = source.indexOf("public void onMove(PlayerMoveEvent event)");
        org.junit.jupiter.api.Assertions.assertTrue(move > 0, "onMove moved");
        int end = source.indexOf("@EventHandler", move);
        String body = source.substring(move, end);
        int afkBranch = body.indexOf("afk.contains(");
        int blockCheck = body.indexOf("differentBlock(");
        org.junit.jupiter.api.Assertions.assertTrue(afkBranch > 0 && blockCheck > afkBranch,
                "a held player must be handled before the block-travel check");
        org.junit.jupiter.api.Assertions.assertTrue(
                body.contains("AfkProtection.turnedEnough("),
                "the release while held has to be a look change, not travel");
    }

    /** Jitter must not wake a Bedrock player; a real look must wake anyone. */
    @Test
    void onlyADeliberateLookCountsAsComingBack() {
        org.junit.jupiter.api.Assertions.assertFalse(
                AfkProtection.turnedEnough(90f, 0f, 91f, 0.5f), "client jitter woke a player");
        org.junit.jupiter.api.Assertions.assertTrue(
                AfkProtection.turnedEnough(90f, 0f, 104f, 0f), "a real turn did not wake");
        org.junit.jupiter.api.Assertions.assertTrue(
                AfkProtection.turnedEnough(0f, 0f, 0f, 20f), "looking up did not wake");
        // Yaw wraps, so 359 to 1 is two degrees rather than three hundred and fifty-eight.
        org.junit.jupiter.api.Assertions.assertFalse(
                AfkProtection.turnedEnough(359f, 0f, 1f, 0f), "the yaw wrap woke a player");
        org.junit.jupiter.api.Assertions.assertTrue(
                AfkProtection.turnedEnough(359f, 0f, 12f, 0f), "a turn across zero did not wake");
    }

    /** Input a shove cannot imitate has to release the hold immediately. */
    @Test
    void deliberateInputReleasesTheHold() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/bot/mgx/accessbridge/AfkService.java"
        ));
        for (String event : new String[]{
                "PlayerToggleSneakEvent", "PlayerJumpEvent", "PlayerAnimationEvent",
                "PlayerSwapHandItemsEvent", "PlayerDropItemEvent", "PlayerItemHeldEvent"}) {
            org.junit.jupiter.api.Assertions.assertTrue(
                    source.contains(event), event + " does not wake a held player");
        }
    }
}
