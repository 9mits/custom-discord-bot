package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hearts are transient attribute modifiers, and respawning copies only base attribute
 * values across to the new player entity. Without a re-apply the extra hearts from
 * Discord levels, Nitro boosting and clan level silently vanish on death, and nothing
 * else sends them again until the player relogs or a Discord role changes.
 */
class PlayerPerkRespawnTest {
    private static final Path SOURCE =
            Path.of("src/main/java/bot/mgx/accessbridge/PlayerPerkService.java");

    @Test
    void perksAreReappliedAfterRespawn() throws Exception {
        String source = Files.readString(SOURCE);
        assertTrue(
                source.contains("public void onPlayerPostRespawn(PlayerPostRespawnEvent event)"),
                "a respawn must re-apply the perks the new player entity lost"
        );
        assertTrue(
                source.contains("applyHearts(player, HEART_MODIFIER_KEY, profile.totalExtraHearts())")
                        && source.contains("applyHearts(player, CLAN_HEART_KEY, perks.extraHearts())"),
                "both the Discord hearts and the clan hearts must come back"
        );
        assertTrue(
                source.contains("applyScalar(player, Attribute.MOVEMENT_SPEED, CLAN_SPEED_KEY, perks.speed())")
                        && source.contains("applyScalar(player, Attribute.BLOCK_BREAK_SPEED, CLAN_DIG_KEY, perks.diggingSpeed())"),
                "the clan speed and digging modifiers are transient too"
        );
    }

    @Test
    void respawnFillsTheBarToTheRestoredMaximum() throws Exception {
        String source = Files.readString(SOURCE);
        int respawn = source.indexOf("onPlayerPostRespawn");
        assertTrue(respawn > 0, "the respawn handler must exist");
        String handler = source.substring(respawn);
        assertTrue(
                handler.contains("player.getHealth() < health.getValue()")
                        && handler.contains("player.setHealth(health.getValue())"),
                "vanilla fills the bar before the modifiers exist, so 20 of 24 must be topped up"
        );
    }

    @Test
    void cachedProfileSurvivesDeathAndIsOnlyClearedOnQuit() throws Exception {
        String source = Files.readString(SOURCE);
        assertTrue(
                source.contains("profiles.remove(event.getPlayer().getUniqueId())")
                        && source.contains("onPlayerQuit(PlayerQuitEvent event)"),
                "the cache the respawn handler reads must only be cleared when the player leaves"
        );
    }
}
