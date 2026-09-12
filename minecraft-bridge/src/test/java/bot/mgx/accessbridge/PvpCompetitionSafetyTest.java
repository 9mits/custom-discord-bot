package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Structural guardrails around the shared survival-arena competitive PvP wiring. */
final class PvpCompetitionSafetyTest {
    private static final Path SOURCE = Path.of(
            "src/main/java/bot/mgx/accessbridge/PvpCompetitionService.java");

    @Test
    void fighterOriginIsDurableButTheirSurvivalInventoryIsNeverReplaced() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);
        assertTrue(source.indexOf("recovery.putAll(snapshots)")
                < source.indexOf("prepareFighter(match, player, start)"));
        assertTrue(source.contains("role == PvpDuelStore.Role.SPECTATOR"));
        assertTrue(source.contains("? PvpStateCodec.encodeItems(player.getInventory().getContents()) : \"\""));
        assertTrue(source.contains("PvpStateCodec.encodeEffects(player.getActivePotionEffects())"));
        assertTrue(source.contains("player.setTotalExperience(saved.totalExperience())"));
        assertTrue(source.contains("safeRemoveRecovery(player.getUniqueId())"));
        String prepare = source.substring(source.indexOf("private boolean prepareFighter("),
                source.indexOf("private void startCountdown("));
        assertTrue(!prepare.contains("getInventory().clear()"));
        assertTrue(!prepare.contains("installKit"));
        assertTrue(prepare.contains("your own survival loadout"));
    }

    @Test
    void competitiveModesUseTheOriginalRealWorldArenaProtections() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);
        String duel = Files.readString(SOURCE.getParent().resolve("PvpDuelService.java"),
                StandardCharsets.UTF_8);
        assertTrue(source.contains("duels.prepareCompetitiveArena"));
        assertTrue(source.contains("duels.activateCompetitiveArena"));
        assertTrue(source.contains("duels.releaseCompetitiveArena"));
        assertTrue(duel.contains("competitiveArenaByPlayer"));
        assertTrue(duel.contains("playerArena(event.getPlayer().getUniqueId())"));
        assertTrue(duel.contains("arenaRestore.remember(fight.arenaId()"));
        assertTrue(duel.contains("returnPlacedBlocks(player)"));
        assertTrue(source.contains("duels.maintainCompetitiveArena(match.arena.id())"));
        assertTrue(duel.contains("fights.isEmpty() && competitiveArenas.isEmpty()"));
        assertTrue(source.contains("PvpDuelService.fightingSource(event)"));
        assertTrue(source.contains("public void onTeleport(PlayerTeleportEvent event)"));
        assertTrue(source.contains("public void onTeleportMonitor(PlayerTeleportEvent event)"));
        assertTrue(source.contains("public void onCommand(PlayerCommandPreprocessEvent event)"));
        assertTrue(source.contains("public void onDrop(PlayerDropItemEvent event)"));
        assertTrue(source.contains("!insideArena(match, event.getTo())"));
    }

    @Test
    void weaponCapRunsAfterCustomDamageAndBeforeMatchResolution() throws Exception {
        String plugin = Files.readString(SOURCE.getParent().resolve("MGXAccessBridge.java"),
                StandardCharsets.UTF_8);
        int custom = plugin.indexOf("registerEvents(amethystItems, this)");
        int balance = plugin.indexOf("new PvpCombatBalanceService(gameVariables)");
        int competition = plugin.indexOf("registerEvents(pvpCompetition, this)");
        assertTrue(custom < balance && balance < competition);

        String cap = Files.readString(SOURCE.getParent().resolve("PvpCombatBalanceService.java"),
                StandardCharsets.UTF_8);
        assertTrue(cap.contains("event.getFinalDamage() <= cap"));
        assertTrue(cap.contains("event.setDamage(middle)"));
        assertTrue(cap.contains("pvp-combat.armored-max-final-damage"));
    }

    @Test
    void ffaActuallyShrinksAndDisconnectsCannotAvoidAResult() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);
        assertTrue(source.contains("private void shrinkBorder(Match match, long now)"));
        assertTrue(source.contains("Border shrinks in \" + warningSeconds + \" seconds!"));
        assertTrue(source.contains("border.changeSize(next, 100L)"));
        assertTrue(source.contains("disconnected and forfeited"));
        assertTrue(source.contains("was eliminated for inactivity"));
        assertTrue(source.contains("Every remaining player was inactive — draw."));
        assertTrue(source.contains("identities.sameOwner"));
        assertTrue(source.contains("farmGuard.restRemaining"));
    }

    @Test
    void spectatorAndLobbyCleanupAreScoped() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);
        int restore = source.indexOf("restore(player);",
                source.indexOf("private void leaveSpectator"));
        int remove = source.indexOf("viewing.remove(player.getUniqueId())", restore);
        assertTrue(restore > 0 && restore < remove,
                "the viewing match must still exist while its prior border is restored");
        assertTrue(source.contains("PvpLobbyBuilder.WORLD_NAME.equals(world.getName())"));
        assertTrue(source.contains("inLobbyArea(event.getBlock().getLocation())"));
        assertTrue(source.contains("inLobbyArea(victim.getLocation())"));
        String lobby = Files.readString(SOURCE.getParent().resolve("PvpLobbyBuilder.java"),
                StandardCharsets.UTF_8);
        assertTrue(lobby.contains("ArmorStand.class"));
        assertTrue(lobby.contains("WALK THROUGH TO CHOOSE"));
        assertTrue(!lobby.contains("Material.OAK_SIGN"));
    }

    @Test
    void leaveQueueOnlyAppearsInTheQueuedContext() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);
        String queueStatus = source.substring(source.indexOf("private void openQueueStatus("),
                source.indexOf("int liveMatchCount()"));
        assertTrue(queueStatus.contains("Leave Queue"));

        String duel = Files.readString(SOURCE.getParent().resolve("PvpDuelService.java"),
                StandardCharsets.UTF_8);
        String hub = duel.substring(duel.indexOf("private void openHub(Player player)"),
                duel.indexOf("private void openRankLeaderboard(Player player)"));
        assertFalse(hub.contains("Leave Queue"));
        assertTrue(source.contains("queuedPlayers.containsKey(player.getUniqueId())"));
    }
}
