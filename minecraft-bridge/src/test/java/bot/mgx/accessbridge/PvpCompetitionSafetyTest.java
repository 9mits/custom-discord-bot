package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Structural guardrails around the isolation and release wiring of competitive PvP. */
final class PvpCompetitionSafetyTest {
    private static final Path SOURCE = Path.of(
            "src/main/java/bot/mgx/accessbridge/PvpCompetitionService.java");

    @Test
    void inventoryAndIdentityAreDurableBeforeAPlayerIsStripped() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);
        assertTrue(source.indexOf("recovery.putAll(snapshots)")
                < source.indexOf("prepareFighter(match, player, start)"));
        assertTrue(source.contains("PvpStateCodec.encodeItems(player.getInventory().getContents())"));
        assertTrue(source.contains("PvpStateCodec.encodeEffects(player.getActivePotionEffects())"));
        assertTrue(source.contains("player.setTotalExperience(saved.totalExperience())"));
        assertTrue(source.contains("safeRemoveRecovery(player.getUniqueId())"));
    }

    @Test
    void fairLoadoutAndArenaEscapeProtectionsAreReal() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);
        assertTrue(source.contains("Material.NETHERITE_SWORD"));
        assertTrue(source.contains("Material.NETHERITE_CHESTPLATE"));
        assertTrue(source.contains("perks.suspendForCompetitive(player)"));
        assertTrue(source.contains("clearEffects(player)"));
        assertTrue(source.contains("public void onTeleport(PlayerTeleportEvent event)"));
        assertTrue(source.contains("public void onCommand(PlayerCommandPreprocessEvent event)"));
        assertTrue(source.contains("public void onDrop(PlayerDropItemEvent event)"));
        assertTrue(source.contains("!match.arena.contains(event.getTo())"));
        assertTrue(source.contains("event.setUseInteractedBlock(Event.Result.DENY)"));
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
        int restore = source.indexOf("restore(player, false);",
                source.indexOf("private void leaveSpectator"));
        int remove = source.indexOf("viewing.remove(player.getUniqueId())", restore);
        assertTrue(restore > 0 && restore < remove,
                "the viewing match must still exist while its prior border is restored");
        assertTrue(source.contains("PvpLobbyBuilder.WORLD_NAME.equals(world.getName())"));
        assertTrue(source.contains("inLobbyArea(event.getBlock().getLocation())"));
        assertTrue(source.contains("inLobbyArea(victim.getLocation())"));
    }
}
