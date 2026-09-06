package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Structural guardrails for the safety promises player-facing screens make. */
final class PvpDuelSafetyTest {
    private static final Path SOURCE = Path.of(
            "src/main/java/bot/mgx/accessbridge/PvpDuelService.java");

    private String source() throws Exception {
        return Files.readString(SOURCE, StandardCharsets.UTF_8);
    }

    @Test
    void deathAlwaysKeepsInventoryAndLevels() throws Exception {
        String source = source();
        assertTrue(source.contains("event.setKeepInventory(true)"));
        assertTrue(source.contains("event.setKeepLevel(true)"));
        assertTrue(source.contains("event.getDrops().clear()"));
        assertTrue(source.contains("event.setDroppedExp(0)"));
        assertTrue(source.indexOf("winner.saveData()") < source.indexOf(
                "store.settle(playerId, economy.balance(playerId))"));
    }

    @Test
    void spectatorsAreAnchoredWithoutFreeRoamSpectatorMode() throws Exception {
        String source = source();
        assertTrue(source.contains("player.setGameMode(GameMode.ADVENTURE)"));
        assertTrue(source.contains("spectators.containsKey(victim.getUniqueId())"));
        assertTrue(source.contains("restoreSpectatorInventory"));
        assertFalse(source.contains("setGameMode(GameMode.SPECTATOR)"));
    }

    @Test
    void arenaIsUntouchedBoundedAndWorldSafe() throws Exception {
        String source = source();
        assertTrue(source.contains("world.isChunkGenerated"));
        assertTrue(source.contains("getInhabitedTime() > 0L"));
        assertTrue(source.contains("player.setWorldBorder(personalBorder"));
        assertTrue(source.contains("event.blockList().clear()"));
        assertTrue(source.contains("onBucketEmpty"));
        assertTrue(source.contains("onIgnite"));
        assertTrue(source.contains("onEntityPlace"));
        assertTrue(source.contains("onCreatureSpawn"));
    }

    @Test
    void openWorldPvpUsesItsOwnToggleAndDeathSideEffectsOptOut() throws Exception {
        String source = source();
        assertTrue(source.contains("PvP is disabled. Use /pvp to fight."));
        assertTrue(source.contains("plugin.openWorldPvpEnabled()"));
        assertTrue(source.contains("event.setCancelled(!opponent)"));
        assertTrue(source.contains("source instanceof Tameable"));
        assertTrue(source.contains("isOpponentAttack"));
        for (String service : new String[] {
                "BountyService.java", "TrophyHeadService.java", "WardrobeService.java"
        }) {
            String other = Files.readString(SOURCE.getParent().resolve(service),
                    StandardCharsets.UTF_8);
            assertTrue(other.contains("plugin.inPvpDuel(victim)"), service);
        }
    }

    @Test
    void openWorldToggleDoesNotDisableArrangedFights() throws Exception {
        String plugin = Files.readString(SOURCE.getParent().resolve("MGXAccessBridge.java"),
                StandardCharsets.UTF_8);
        String forcePvp = plugin.substring(
                plugin.indexOf("void forcePvp(boolean enabled)"),
                plugin.indexOf("boolean inScreenshotMode")
        );
        assertFalse(forcePvp.contains("pvpDuels.pauseAll"));

        String challengeChecks = source().substring(
                source().indexOf("private boolean canChallenge"),
                source().indexOf("private boolean acceptingChallenges")
        );
        assertFalse(challengeChecks.contains("openWorldPvpEnabled"));
    }

    @Test
    void everyClientHasItemCosmeticAndAcceptancePaths() throws Exception {
        String source = source();
        assertTrue(source.contains("openChestSetup"));
        assertTrue(source.contains("openChestAccept"));
        assertTrue(source.contains("openItemWager"));
        assertTrue(source.contains("openCosmeticWager"));
        assertTrue(source.contains("restoreEscrow"));
        assertFalse(source.contains("Both players must hold the stack"));
        assertFalse(source.contains("maximum-money-wager"));
    }

    @Test
    void challengesRespectPrivacyAndDoNotForceOpenARecipientsMenu() throws Exception {
        String source = source();
        assertTrue(source.contains("PlayerSettingsStore.Setting.DUEL_REQUESTS"));
        assertTrue(source.contains("pvp-duels.challenge-cooldown-seconds"));
        String sender = source.substring(
                source.indexOf("private void sendChallenge"),
                source.indexOf("private void openIncoming")
        );
        assertFalse(sender.contains("openInvitation(target, invitation);"));
    }
}
