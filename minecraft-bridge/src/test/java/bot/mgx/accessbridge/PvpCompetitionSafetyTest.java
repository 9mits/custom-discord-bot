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
        // Supported Java clients receive large TextDisplays while Bedrock and older
        // Java clients retain the armour-stand fallback. Both are nearby-only so the
        // opposite side of the terrace cannot overlay the station being read.
        assertTrue(lobby.contains("TextDisplay.class"));
        assertTrue(lobby.contains("CrateDisplayService.spawnStyledLabel"));
        assertTrue(lobby.contains("instanceof ArmorStand"));
        assertTrue(lobby.contains("setVisibleByDefault(false)"));
        assertTrue(lobby.contains("boolean correctType = useText"));
        assertTrue(source.contains("lobby-label-view-distance"));
        assertTrue(source.contains("lobby-board-view-distance"));
        assertTrue(source.contains("lobby-leaderboard-view-distance"));
        // Queue gates are honest portals and the open pavilions themselves are the
        // click targets; a lectern must not obstruct the live text or walkway.
        assertTrue(lobby.contains("Material.NETHER_PORTAL.createBlockData()"));
        assertTrue(lobby.contains("touchesPortal(at)"));
        assertFalse(lobby.contains("Material.LECTERN"));
        assertTrue(lobby.contains("RIGHT-CLICK CONSOLE"));
        assertFalse(lobby.contains("You fight with the gear you walked in with."));
        assertTrue(!lobby.contains("Material.OAK_SIGN"));
        assertTrue(source.contains("public void onPortal(PlayerPortalEvent event)"));
        assertTrue(source.contains("player.setPortalCooldown("));
        assertTrue(source.contains("suppressCustomPortalTravel()"));
        assertTrue(source.contains("else openMode(player, mode);"));
        assertTrue(source.contains("PvpLobbyBuilder.pavilionAction("));
        // Gate lettering is stacked above the lintel rather than hung in the portal
        // mouth or alongside the posts, which is the only placement a label that turns
        // to face the reader can occupy without sweeping into the arch.
        assertTrue(lobby.contains("FLOOR_Y + STRUCTURE_TOP + 4.8d"));
        assertTrue(lobby.contains("GATE_STATUS_Y = FLOOR_Y + STRUCTURE_TOP + 2.4d"));
        assertTrue(lobby.contains("Display.Billboard.VERTICAL"));
        assertFalse(lobby.contains("Display.Billboard.FIXED"));
        int lateClear = lobby.indexOf("clearLobbyLabels(world);", lobby.indexOf("plantGarden"));
        assertTrue(lateClear > 0 && lateClear < lobby.indexOf("buildHolograms(world", lateClear),
                "stale labels must be removed after geometry loads their chunks");
        assertTrue(lobby.contains("buildCourt(world)"));
        assertTrue(lobby.contains("buildLeaderboardFrame(world, board)"));
        assertTrue(lobby.contains("structurePlinth(world"));
        assertTrue(lobby.contains("clearLegacyFightingPlatforms(world)"));
        assertTrue(lobby.contains("{0, -180, 27}"));
        assertTrue(lobby.contains("{-180, -100, 46}"));
        assertTrue(lobby.contains("RECORDS_LABEL_TAG"));
        assertTrue(lobby.contains("CLAN_KILLS(\"CLAN KILLS\""));
    }

    @Test
    void theSmpEntranceUsesDragonStyleFrameSelectionAndAReadableMarker() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);
        assertTrue(source.contains("player.getTargetBlockExact("));
        assertTrue(source.contains("private boolean igniteEntranceFrame()"));
        assertTrue(source.contains("private Set<Location> fillEntranceFrame("));
        assertTrue(source.contains("private Set<Location> nearestEntrancePortalComponent()"));
        assertTrue(source.contains("pvp-competitive.portal-title"));
        assertTrue(source.contains("pvp-competitive.portal-status"));
        String variables = Files.readString(SOURCE.getParent().resolve("GameVariableStore.java"),
                StandardCharsets.UTF_8);
        assertTrue(variables.contains("WALK THROUGH • CHOOSE A FIGHT"));
        assertTrue(source.contains("Location anchor = portalTopCentre(blocks, registered)"));
        assertTrue(source.contains("display.setBillboard(Display.Billboard.CENTER)"));
        assertTrue(source.contains("display.setSeeThrough(true)"));
        assertTrue(source.contains("ENTRANCE_FALLBACK_TAG"));
        assertFalse(source.contains("Use /pvp portal set [radius]"));
    }

    @Test
    void threeFightCategoriesExposeSizeAccessInvitesAndOwnerStart() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);
        String setup = Files.readString(SOURCE.getParent().resolve("PvpMatchSetup.java"),
                StandardCharsets.UTF_8);
        assertTrue(source.contains("List.of(\n                PvpMode.RANKED_DUEL, PvpMode.CLAN_BATTLE, PvpMode.FFA)"));
        assertTrue(source.contains("\"Access: \" + setup.access().display()"));
        assertTrue(source.contains("Create Invite-Only Room"));
        assertTrue(source.contains("Invite Opponents"));
        assertTrue(source.contains("\"Force Start \" + roomScore(room)"));
        assertTrue(source.contains("roomStartable(room)"));
        assertTrue(source.contains("opponentsAllowed(first, second"));
        assertTrue(setup.contains("family == PvpMode.CLAN_BATTLE ? 2 : 1"));
        assertTrue(setup.contains("targetPlayers >= 12 ? 2 : targetPlayers + 1"));
        assertFalse(PvpMode.CASUAL_DUEL.queueable());
    }

    @Test
    void leaveQueueOnlyAppearsInTheQueuedContext() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);
        String queueStatus = source.substring(source.indexOf("private void openQueueStatus("),
                source.indexOf("int liveMatchCount()"));
        assertTrue(queueStatus.contains("Leave Queue"));

        String duel = Files.readString(SOURCE.getParent().resolve("PvpDuelService.java"),
                StandardCharsets.UTF_8);
        String hub = duel.substring(duel.indexOf("void openHub(Player player)"),
                duel.indexOf("private void openRankLeaderboard(Player player)"));
        assertFalse(hub.contains("Leave Queue"));
        assertTrue(source.contains("queuedPlayers.containsKey(player.getUniqueId())"));
    }

    @Test
    void nestedMenusReturnToTheirActualCallerAndQueuesNeverUseTheGlobalFallback()
            throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);
        assertTrue(source.contains("private void openStats(Player player, Consumer<Player> back)"));
        assertTrue(source.contains("private void openRankings(Player player, Consumer<Player> back)"));
        assertTrue(source.contains("private void openParty(Player player, Consumer<Player> back)"));
        assertTrue(source.contains("backViewer -> openMode(backViewer, family)"));
        assertTrue(source.contains("viewer -> openRankings(viewer, this::openLadder)"));
        String queueStatus = source.substring(source.indexOf("private void openQueueStatus("),
                source.indexOf("int liveMatchCount()"));
        assertTrue(queueStatus.contains("duels::openHub"));
        assertFalse(queueStatus.contains("), null);"));
    }

    @Test
    void competitiveRankAndMatchResultsNeverMintMoney() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);
        String variables = Files.readString(SOURCE.getParent().resolve("GameVariableStore.java"),
                StandardCharsets.UTF_8);
        assertFalse(source.contains("payRewards("));
        assertFalse(source.contains("economy.deposit("));
        assertFalse(source.contains("rewardBlocked"));
        assertFalse(variables.contains("pvp-competitive.participation-reward"));
        assertFalse(variables.contains("pvp-competitive.win-reward"));
        assertFalse(variables.contains("pvp-competitive.minimum-reward-seconds"));
        assertTrue(source.contains("Private-fight wagers remain optional"));
    }
}
