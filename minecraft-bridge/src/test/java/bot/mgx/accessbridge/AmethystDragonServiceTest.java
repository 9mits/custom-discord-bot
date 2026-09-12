package bot.mgx.accessbridge;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class AmethystDragonServiceTest {
    @Test
    void dragonArenaRescuesPlayersBeforeVanillaVoidDamage() {
        assertEquals(true, AmethystDragonService.belowVoidRescueHeight(19.99, 20));
        assertEquals(false, AmethystDragonService.belowVoidRescueHeight(20, 20));
        assertEquals(false, AmethystDragonService.belowVoidRescueHeight(80, 20));
    }

    @Test
    void aPlayerCaughtInARisingPillarIsPutDownOutsideIt() {
        // The layers are placed with setType, so anyone still inside the footprint is
        // sealed into a solid obsidian column that cannot be broken.
        for (int width = 1; width <= 6; width++) {
            assertEquals(true, AmethystDragonService.insidePillarFootprint(0, width),
                    "dead centre is in the way");
            assertEquals(true, AmethystDragonService.insidePillarFootprint(width, width),
                    "the edge is in the way");
            // The bug this guards: ejecting somebody to a spot still inside the column.
            assertEquals(false, AmethystDragonService.insidePillarFootprint(
                    AmethystDragonService.pillarLandingDistance(width), width),
                    "where they land must be outside the footprint");
        }
    }

    @Test
    void dragonMinionsUseTheirVanillaMovementSpeeds() {
        assertEquals(0.23d, AmethystDragonService.normalMinionSpeed(EntityType.HUSK));
        assertEquals(0.25d, AmethystDragonService.normalMinionSpeed(EntityType.STRAY));
        assertEquals(0.25d, AmethystDragonService.normalMinionSpeed(EntityType.IRON_GOLEM));
        assertThrows(IllegalArgumentException.class,
                () -> AmethystDragonService.normalMinionSpeed(EntityType.ZOMBIE));
    }

    @Test
    void dragonDifficultyScalesWithLockedAttendanceAndKeepsItsCap() {
        assertEquals(1200, AmethystDragonService.scaledDragonHealth(1200, 600, 6000, 0));
        assertEquals(1200, AmethystDragonService.scaledDragonHealth(1200, 600, 6000, 1));
        assertEquals(1800, AmethystDragonService.scaledDragonHealth(1200, 600, 6000, 2));
        assertEquals(3000, AmethystDragonService.scaledDragonHealth(1200, 600, 6000, 4));
        assertEquals(6000, AmethystDragonService.scaledDragonHealth(1200, 600, 6000, 9));
        assertEquals(6000, AmethystDragonService.scaledDragonHealth(1200, 600, 6000, 40));

        assertEquals(6, AmethystDragonService.scaledEncounterAmount(30, 0.2d));
        assertEquals(15, AmethystDragonService.scaledEncounterAmount(30, 0.5d));
        assertEquals(30, AmethystDragonService.scaledEncounterAmount(30, 1d));
        assertEquals(0, AmethystDragonService.scaledEncounterAmount(0, 0.2d));
        assertEquals(1, AmethystDragonService.scaledEncounterAmount(1, 0.2d));
    }

    @Test
    void vanillaExitFountainMaterialsAreRemovedFromTheArenaCentre() {
        for (Material material : new Material[]{
                Material.END_PORTAL, Material.END_GATEWAY, Material.END_PORTAL_FRAME,
                Material.BEDROCK, Material.END_STONE, Material.END_STONE_BRICKS,
                Material.TORCH, Material.WALL_TORCH
        }) {
            assertEquals(true, AmethystDragonService.isVanillaExitPortalBlock(material));
        }
        assertEquals(false, AmethystDragonService.isVanillaExitPortalBlock(Material.NETHER_PORTAL));
        assertEquals(false, AmethystDragonService.isVanillaExitPortalBlock(Material.OBSIDIAN));
    }

    @Test
    void phasedSkyIsBrightOutsideCombatAndDarkDuringTheFight() {
        for (AmethystDragonService.Phase phase : AmethystDragonService.Phase.values()) {
            long expected = phase == AmethystDragonService.Phase.FIGHT ? 18_000L : 6_000L;
            assertEquals(expected,
                    AmethystDragonService.arenaSkyTime("PHASED", phase, 6_000L, 18_000L));
        }
        assertEquals(18_000L, AmethystDragonService.arenaSkyTime(
                "END", AmethystDragonService.Phase.PORTAL_OPEN, 6_000L, 18_000L));
    }

    @Test
    void everyActiveDragonPhaseReturnsVoidFallsToTheIsland() {
        for (AmethystDragonService.Phase phase : AmethystDragonService.Phase.values()) {
            assertEquals(phase != AmethystDragonService.Phase.WAITING,
                    AmethystDragonService.returnsToIsland(phase));
        }
    }

    /**
     * The open gateway has to keep saying so. One announcement at the start reached
     * nobody who joined during the window, which is what "it closed immediately" was.
     */
    @Test
    void theOpenPortalRemindsOnTheIntervalThenEverySecondAtTheEnd() {
        assertTrue(AmethystDragonService.portalReminderDue(240L, 60, 10));
        assertTrue(AmethystDragonService.portalReminderDue(60L, 60, 10));
        assertFalse(AmethystDragonService.portalReminderDue(59L, 60, 10));
        assertFalse(AmethystDragonService.portalReminderDue(11L, 60, 10));
        assertTrue(AmethystDragonService.portalReminderDue(10L, 60, 10));
        assertTrue(AmethystDragonService.portalReminderDue(1L, 60, 10));
        // Nothing is due once the gateway has sealed.
        assertFalse(AmethystDragonService.portalReminderDue(0L, 60, 10));
        // Both repeats are switchable from the control panel.
        assertFalse(AmethystDragonService.portalReminderDue(120L, 0, 0));
        assertTrue(AmethystDragonService.portalReminderDue(120L, 0, 600));
    }

    @Test
    void portalClosingCounterRunsFromFiveMinutesAndRoundsUp() {
        assertEquals("05:00", AmethystDragonService.portalCountdown(
                Duration.ofMinutes(5).toMillis()));
        assertEquals("05:00", AmethystDragonService.portalCountdown(299_001L));
        assertEquals("04:59", AmethystDragonService.portalCountdown(299_000L));
        assertEquals("00:01", AmethystDragonService.portalCountdown(1L));
        assertEquals("00:00", AmethystDragonService.portalCountdown(0L));
    }

    @Test
    void visualKeyFountainDistributesEveryItemAcrossItsWaves() {
        int total = 0;
        for (int wave = 0; wave < 6; wave++) {
            int count = AmethystDragonService.visualKeyWaveCount(108, 6, wave);
            assertEquals(18, count);
            total += count;
        }
        assertEquals(108, total);

        assertEquals(4, AmethystDragonService.visualKeyWaveCount(20, 6, 0));
        assertEquals(3, AmethystDragonService.visualKeyWaveCount(20, 6, 5));
        assertEquals(0, AmethystDragonService.visualKeyWaveCount(20, 6, 6));
    }

    @Test
    void distantPresentationPathsUseThePayloadSafeForcedEmitter() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/bot/mgx/accessbridge/AmethystDragonService.java"));

        String summoning = method(source, "private void dragonEntranceClimax()",
                "private void cancelSummoningTask()");
        assertTrue(summoning.contains(
                "spawnPresentationParticle(Particle.DRAGON_BREATH"));
        assertFalse(summoning.contains(
                "arena.spawnParticle(Particle.DRAGON_BREATH"));

        String death = method(source, "private void animateDragonDeath(Location deathAt)",
                "private void beginRewardPhase(Location deathAt)");
        assertTrue(death.contains("spawnPresentationParticle(Particle.DUST"));
        assertTrue(death.contains("playToArena("));

        String egg = method(source, "private void eggBeacons()", "private void tick()");
        assertTrue(egg.contains("spawnPresentationParticle(Particle.END_ROD"));
        assertTrue(egg.contains("playFromEgg("));

        String emitter = method(source, "private void spawnPresentationParticle(",
                "/** Keeps the egg cue directional");
        assertTrue(emitter.contains("CosmeticEffectService.particleData(particle, data)"));
        assertTrue(emitter.contains(", true)"));
    }

    @Test
    void fightDrivesTheNativeDragonBarAfterPaperTracksIt() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/bot/mgx/accessbridge/AmethystDragonService.java"));

        String tickEnd = method(source, "public void onServerTickEnd(ServerTickEndEvent event)",
                "private void rescueFallenPlayers()");
        int refresh = tickEnd.indexOf("if (phase == Phase.FIGHT) updateDragonBar()");
        assertTrue(refresh >= 0);
        assertTrue(refresh < tickEnd.indexOf("rescueFallenPlayers()"));

        String update = method(source, "private void updateDragonBar()",
                "/**\n     * The gateway's own bar");
        assertTrue(update.contains("org.bukkit.boss.BossBar nativeBar = nativeDragonBar()"));
        assertTrue(update.contains("nativeBar.setTitle(\"§d§l\" + title)"));
        assertTrue(update.contains("nativeBar.setProgress(progress)"));
        assertTrue(update.contains("nativeBar.addPlayer(player)"));
        assertTrue(update.contains("nativeBar.setVisible(true)"));
        assertTrue(update.contains("if (dragonBar == null)"),
                "worlds without a native Dragon bar still need the custom fallback");
    }

    private static String method(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        assertTrue(from >= 0, start);
        assertTrue(to > from, end);
        return source.substring(from, to);
    }
}
