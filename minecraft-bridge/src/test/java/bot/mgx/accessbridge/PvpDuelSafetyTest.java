package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
        assertTrue(source.contains("onBucketEmpty"));
        assertTrue(source.contains("onIgnite"));
        assertTrue(source.contains("onEntityPlace"));
        assertTrue(source.contains("onCreatureSpawn"));
    }

    /**
     * The arena is destructible, and destructible is only safe while every change is
     * written down first.
     *
     * <p>This is the invariant the whole feature rests on: {@code remember} answers
     * whether a block's original state is known, and <em>every</em> caller refuses
     * the change when the answer is no. One caller that ignores it is one scar in
     * somebody's world, 2,000 blocks from spawn, that nothing will ever go and find.
     */
    @Test
    void nothingChangesInTheArenaThatWasNotWrittenDownFirst() throws Exception {
        String source = source();

        // Every handler that lets a block change asks first and gives up on a no.
        for (String handler : new String[] {
                "onBreak", "onPlace", "onEntityChangeBlock", "onFlow", "onForm",
                "onSpread", "onFade", "onBurn", "onLeavesDecay", "onPhysics", "onIgnite"
        }) {
            int at = source.indexOf("public void " + handler + "(");
            assertTrue(at > 0, handler + " is gone");
            String body = source.substring(at, source.indexOf("\n    }", at));
            assertTrue(body.contains("remember("), handler + " stopped recording");
            assertTrue(body.contains("setCancelled(true)"),
                    handler + " no longer refuses a change it could not record");
        }

        // A blast drops the blocks it could not record rather than taking them.
        String contain = source.substring(
                source.indexOf("private boolean containExplosion(List<Block> blocks)"),
                source.indexOf("public void onIgnite("));
        assertTrue(contain.contains("if (!remember(fight, block)) {"));
        assertTrue(contain.contains("blast.remove()"));
        // And a duel is still not a quarry.
        assertTrue(source.contains("event.setYield(0f)"));
        assertTrue(source.contains("event.setDropItems(false)"));

        // Nothing a fight sets off reaches ground the revert will not visit.
        String flow = source.substring(
                source.indexOf("public void onFlow(BlockFromToEvent event)"),
                source.indexOf("public void onForm("));
        assertTrue(flow.contains("fightAt(event.getBlock().getLocation()) != null"));

        // The budget is a ceiling on damage, not on the fight.
        assertTrue(source.contains("arenaRestore.size(fight.id) >= maximumArenaEdits()"));
        assertTrue(source.contains("warnArenaFull(fight)"));
    }

    /** Explosives are the point of a destructible arena, so they have to hurt. */
    @Test
    void anExplosionIsCreditedToWhoeverSetItOff() throws Exception {
        String source = source();
        String resolver = source.substring(
                source.indexOf("private static UUID fightingSource("),
                source.indexOf("private static boolean samePosition"));

        assertTrue(resolver.contains("TNTPrimed tnt"));
        // Bukkit records no placer for an end crystal, so the arena's own tag is it.
        assertTrue(resolver.contains("arenaEntityOwner(source)"));
        assertTrue(source.contains("tagArenaEntity(event.getEntity(), player)"));
        // And what a fight brought with it does not outlive the fight.
        String left = source.substring(
                source.indexOf("private static boolean leftBehindByTheFight(Entity entity)"),
                source.indexOf("private boolean insideArena"));
        assertTrue(left.contains("arenaEntityOwner(entity) != null"));
        assertTrue(left.contains("EnderCrystal"));
        assertTrue(left.contains("TNTPrimed"));
    }

    @Test
    void openWorldPvpUsesItsOwnToggleAndDeathSideEffectsOptOut() throws Exception {
        String source = source();
        assertTrue(source.contains("PvP is disabled. Use /pvp to fight."));
        assertTrue(source.contains("plugin.openWorldPvpEnabled()"));
        assertTrue(source.contains("source instanceof Tameable"));
        assertTrue(source.contains("fightingSource(byEntity)"));
        // A third party's arrow is still refused; your own crystal is not.
        assertTrue(source.contains("!victim.getUniqueId().equals(source)"));
        for (String service : new String[] {
                "BountyService.java", "TrophyHeadService.java", "WardrobeService.java"
        }) {
            String other = Files.readString(SOURCE.getParent().resolve(service),
                    StandardCharsets.UTF_8);
            assertTrue(other.contains("plugin.inPvpDuel(victim)"), service);
        }
    }

    @Test
    void privateFightStatsUseFinalBalancedDamage() throws Exception {
        String source = source();
        int resolved = source.indexOf("public void onResolvedDuelDamage(");
        assertTrue(resolved > 0);
        String handler = source.substring(source.lastIndexOf("@EventHandler", resolved),
                source.indexOf("\n    }", resolved));
        assertTrue(handler.contains("EventPriority.MONITOR"));
        assertTrue(handler.contains("event.getFinalDamage()"));
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

    /**
     * Back owns the middle of a board's bottom row and is drawn last, so anything
     * written to that slot is painted over and can never be clicked. Send Challenge
     * sat on slot 22 of a 27-slot board, which is exactly that slot: the challenge
     * screen had no way to send a challenge.
     */
    @Test
    void chestBoardsKeepEveryActionOffTheSlotBackOwns() {
        int back = MenuPaging.backSlot(PvpDuelService.SETUP_BOARD_SIZE);
        for (int slot : new int[] {
                PvpDuelService.SETUP_SEND_SLOT, PvpDuelService.SETUP_WAGER_SLOT,
                PvpDuelService.WAGER_MONEY_SLOT, PvpDuelService.WAGER_ITEMS_SLOT,
                PvpDuelService.WAGER_COSMETICS_SLOT, PvpDuelService.WAGER_CLEAR_SLOT,
                PvpDuelService.WAGER_DONE_SLOT, PvpDuelService.ACCEPT_CONFIRM_SLOT,
                PvpDuelService.ACCEPT_WAGER_SLOT, PvpDuelService.ACCEPT_DECLINE_SLOT
        }) {
            assertNotEquals(back, slot);
        }
    }

    @Test
    void theCashWagerIsAFieldOnTheScreenRatherThanAChatPrompt() throws Exception {
        String source = source();
        assertTrue(source.contains("DialogInput.text(MONEY_INPUT"));
        assertTrue(source.contains("response.getText(MONEY_INPUT)"));
        assertTrue(source.contains("forms.prompt(player, \"Money Wager\""));
        // Blank and zero are "items only", which the shared parser rejects because
        // every other amount in the economy has to be worth at least a dollar.
        String reader = source.substring(
                source.indexOf("private static String applyMoney"),
                source.indexOf("private void openMoneyPrompt"));
        assertTrue(reader.contains("text.isEmpty() || text.equals(\"0\")"));
    }

    @Test
    void aFighterCanAlwaysReopenTheScreenThatGivesUp() throws Exception {
        String source = source();
        String gate = source.substring(
                source.indexOf("public void onCommand(PlayerCommandPreprocessEvent"),
                source.indexOf("public void onBreak(BlockBreakEvent"));
        assertTrue(gate.contains("typed.length == 1"));
        assertTrue(gate.contains("givingUp(typed[1])"));
        assertFalse(gate.contains("typed.equals(\"/pvp forfeit\")"));
        assertTrue(source.contains("case \"forfeit\", \"surrender\", \"giveup\", \"ff\" -> true;"));
    }

    /** A fight nobody engages in otherwise holds an arena and two escrows forever. */
    @Test
    void theFightLengthIsCappedInCodeNotOnlyInConfig() throws Exception {
        assertEquals(15, PvpDuelService.MAXIMUM_DURATION_MINUTES);
        String source = source();
        String reader = source.substring(
                source.indexOf("private int durationMinutes()"),
                source.indexOf("private int returnSeconds()"));
        assertTrue(reader.contains("Math.min(MAXIMUM_DURATION_MINUTES"));
    }

    /**
     * The hold after a result must not shorten the crash guarantee. The recovery row
     * is what takes a player home, so it may only be dropped once they are there.
     */
    @Test
    void theReturnHoldKeepsItsRecoveryRowUntilThePlayerIsBack() throws Exception {
        String source = source();
        String settle = source.substring(
                source.indexOf("Fight fight, UUID winnerId, String result, boolean immediate, Ending ending"),
                source.indexOf("private void beginAftermath"));
        assertTrue(settle.contains("fight.settled.add(playerId)"));
        assertFalse(settle.contains("safeRemoveRecovery"));

        String finish = source.substring(
                source.indexOf("private void finishReturn"),
                source.indexOf("private void recordResults"));
        assertTrue(finish.contains("restoreAtEnd(player, fight.states.get(playerId))"));
        assertTrue(finish.contains("fight.settled.contains(playerId)"));
        assertTrue(finish.contains("safeRemoveRecovery(playerId)"));
        // A disable cannot schedule, and the throw would take the shutdown with it.
        assertTrue(finish.contains("plugin.isEnabled()"));
    }

    @Test
    void everyTeleportOutOfAFightIsRefusedTwice() throws Exception {
        String source = source();
        assertTrue(source.contains("public void onTeleportMonitor(PlayerTeleportEvent event)"));
        assertTrue(source.contains("public void onProjectileLaunch(ProjectileLaunchEvent event)"));
        assertTrue(source.contains("public void onConsume(PlayerItemConsumeEvent event)"));
        assertTrue(source.contains("public void onPortalCreate(PortalCreateEvent event)"));
        String dragon = Files.readString(SOURCE.getParent().resolve("AmethystDragonService.java"),
                StandardCharsets.UTF_8);
        assertTrue(dragon.contains("public void onArenaExitMonitor(PlayerTeleportEvent event)"));
        assertTrue(dragon.contains("public void onArenaPortalCreate(PortalCreateEvent event)"));
    }

    /** A dead loser is on the respawn screen; their bed is outside the arena bounds. */
    @Test
    void respawningDuringTheHoldSeatsThePlayerBackInTheRing() throws Exception {
        String source = source();
        String respawn = source.substring(
                source.indexOf("public void onRespawn(PlayerRespawnEvent event)"),
                source.indexOf("private static void restoreSpectatorInventory"));
        assertTrue(respawn.contains("held.phase == Phase.AFTERMATH"));
        assertTrue(respawn.contains("held.arena.first()"));
    }

    /**
     * Digging is allowed, so the world has to come back. The recorded set is only
     * complete because every other way a block can change is refused outright.
     */
    @Test
    void everyBlockChangeInsideAnArenaIsEitherRecordedOrRefused() throws Exception {
        String source = source();
        String breaking = source.substring(
                source.indexOf("public void onBreak(BlockBreakEvent event)"),
                source.indexOf("public void onPlace(BlockPlaceEvent event)"));
        assertTrue(breaking.contains("remember(fight, event.getBlock())"));
        // Fresh terrain nobody has mined would otherwise be the cheapest ore run.
        assertTrue(breaking.contains("event.setDropItems(false)"));
        assertTrue(breaking.contains("event.setExpToDrop(0)"));

        String placing = source.substring(
                source.indexOf("public void onPlace(BlockPlaceEvent event)"),
                source.indexOf("public void onEntityChangeBlock"));
        assertTrue(placing.contains("BlockMultiPlaceEvent multi"));
        assertTrue(placing.contains("instanceof Container"));

        for (String refused : new String[] {
                "public void onFlow(BlockFromToEvent event)",
                "public void onForm(BlockFormEvent event)",
                "public void onSpread(BlockSpreadEvent event)",
                "public void onFade(BlockFadeEvent event)",
                "public void onLeavesDecay(LeavesDecayEvent event)",
                "public void onPhysics(BlockPhysicsEvent event)"
        }) {
            assertTrue(source.contains(refused), refused);
        }
        // A falling block leaves one position and arrives at another; both count.
        assertTrue(source.contains("public void onEntityChangeBlock(EntityChangeBlockEvent event)"));
        // Physics is the hottest of these by far and must cost nothing when idle.
        String physics = source.substring(
                source.indexOf("public void onPhysics(BlockPhysicsEvent event)"),
                source.indexOf("private boolean insideArena"));
        assertTrue(physics.indexOf("fights.isEmpty()") < physics.indexOf("fightAt("));
    }

    @Test
    void theArenaIsPutBackAndWhatWasPlacedIsPaidBack() throws Exception {
        String source = source();
        String finish = source.substring(
                source.indexOf("private void finishReturn"),
                source.indexOf("private void recordResults"));
        assertTrue(finish.contains("returnPlacedBlocks(playerId)"));
        assertTrue(finish.contains("sweepArena(fight, true)"));
        assertTrue(finish.contains("restoreArena(fight.id)"));
        assertTrue(finish.indexOf("returnPlacedBlocks") < finish.indexOf("restoreAtEnd"));
        assertTrue(finish.indexOf("restoreArena") > finish.indexOf("restoreAtEnd"));
        // Physics on would start the same landslide the revert is undoing.
        assertTrue(source.contains("block.setBlockData(Bukkit.createBlockData("
                + "snapshot.blockData()), false)"));
        assertTrue(source.contains("RESTORE_BLOCKS_PER_TICK"));
    }

    @Test
    void nothingAliveSharesTheRingWithTheFighters() throws Exception {
        String source = source();
        assertTrue(source.contains("insideAnyArena(event.getLocation())) event.setCancelled(true)"));
        String sweep = source.substring(
                source.indexOf("private void sweepArena(Fight fight, boolean teardown)"),
                source.indexOf("private boolean insideArena"));
        assertTrue(sweep.contains("entity instanceof org.bukkit.entity.Item"));
        assertTrue(sweep.contains("entity instanceof Player"));
        assertTrue(sweep.contains("tameable.getOwner() != null"));
        assertTrue(source.contains("fight.sweepTask = plugin.getServer().getScheduler()"));
    }

    /**
     * A non-pausing dialog draws no backdrop, so its text sits straight on the sky.
     * Grey was picked for tooltips, which the game gives a dark panel; on a bright
     * midday sky the same grey is close to unreadable.
     */
    @Test
    void screenBodyTextIsReadableAgainstTheWorldBehindIt() throws Exception {
        String menuText = Files.readString(SOURCE.getParent().resolve("MenuText.java"),
                StandardCharsets.UTF_8);
        String body = menuText.substring(
                menuText.indexOf("static Component body(String text)"),
                menuText.indexOf("static Component muted(String text)"));
        assertTrue(body.contains("BODY"));
        assertFalse(body.contains("LABEL"));
        assertTrue(menuText.contains("static final TextColor BODY = NamedTextColor.WHITE;"));
        // A stat's label sits in the same body text and had the same problem.
        String stat = menuText.substring(
                menuText.indexOf("static Component stat(String label, String value)"),
                menuText.indexOf("static Component rule("));
        assertFalse(stat.contains("LABEL"));
    }

    /** A draw is a result worth playing towards, and unplayable without a clock. */
    @Test
    void aRunningFightShowsOnlyItsClockAndRestoresOrdinaryBarsAfterward() throws Exception {
        String source = source();
        assertTrue(source.contains("startClock(fight)"));
        assertTrue(source.contains("plugin.bossBars().suppress(player)"));
        assertTrue(source.contains("plugin.bossBars().restore(player)"));
        String settle = source.substring(
                source.indexOf("Fight fight, UUID winnerId, String result, boolean immediate, Ending ending"),
                source.indexOf("private void beginAftermath"));
        assertTrue(settle.contains("stopClock(fight)"));
        String stop = source.substring(
                source.indexOf("private void stopClock(Fight fight)"),
                source.indexOf("private void openFightStatus"));
        assertTrue(stop.contains("hideExclusive"));
        // A spectator who leaves early keeps the bar otherwise.
        assertTrue(source.contains("hideExclusive(player, spectator.fight().clock)"));

        String display = Files.readString(SOURCE.getParent().resolve("BossBarDisplay.java"),
                StandardCharsets.UTF_8);
        assertTrue(display.contains("if (!suppressed.contains(player.getUniqueId()))"));
        assertTrue(display.contains("for (BossBar bar : wanted.getOrDefault"));

        // Every plugin-owned bar has to pass through the tracker or a bar created
        // during the duel could appear over the clock and never be restored.
        try (var files = Files.list(SOURCE.getParent())) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                if (file.getFileName().toString().equals("BossBarDisplay.java")) continue;
                String candidate = Files.readString(file, StandardCharsets.UTF_8);
                if (file.getFileName().toString().equals("PvpCompetitionService.java")) {
                    assertTrue(candidate.contains("plugin.bossBars().showExclusive"));
                    assertTrue(candidate.contains("plugin.bossBars().hideExclusive"));
                }
                assertFalse(candidate.contains(".showBossBar("), file + " bypasses the boss-bar gate");
                assertFalse(candidate.contains(".hideBossBar("), file + " bypasses the boss-bar gate");
            }
        }
    }

    @Test
    void howItWorksDoesNotPublishAntiFarmingThresholds() throws Exception {
        String source = source();
        String rules = source.substring(
                source.indexOf("private void openRules(Player player)"),
                source.indexOf("private void openTargets(Player player)"));
        assertTrue(rules.contains("Anti-farming checks protect records, wagers and rematches."));
        assertFalse(rules.contains("linked accounts"));
        assertFalse(rules.contains("repeatOpponentLimit()"));
        assertFalse(rules.contains("repeatOpponentRestMillis()"));
    }

    @Test
    void theResultScreenOffersARematchThatCannotRefuseItself() throws Exception {
        String source = source();
        String rematch = source.substring(
                source.indexOf("private void rematch(Player player, UUID opponentId, long money)"),
                source.indexOf("/** The same numbers as chat lines"));
        // Every reason the challenge could be turned down, asked before a screen is
        // drawn rather than by the screen itself.
        assertTrue(rematch.contains("challengeProblem(player, opponent)"));
        assertTrue(rematch.contains("acceptingProblem(opponent)"));
        // And answered on the screen the button lives on. A dialog covers the chat
        // box, so a refusal sent to chat is a Rematch button that did nothing.
        assertTrue(rematch.contains("openResultScreen(player, problem)"));
        // Carrying a stake they can no longer cover would open a screen that refuses.
        assertTrue(rematch.contains(
                "economy.balance(player.getUniqueId()) >= money ? money : 0L"));
    }

    /**
     * The rematch itself has to reach the other player, who is looking at a screen
     * drawn over their chat box at the exact moment the challenge is sent.
     */
    @Test
    void aChallengeSentToSomebodyReadingTheirResultIsPutInFrontOfThem() throws Exception {
        String source = source();
        String send = source.substring(
                source.indexOf("private String sendChallenge("),
                source.indexOf("private void openIncoming(Player player)"));

        assertTrue(send.contains("resultViewers.contains(targetId)"));
        assertTrue(send.contains("openInvitation(viewer, invitation)"));
        // And the screen stops counting as open the moment they leave it, so an
        // ordinary challenge never yanks somebody out of what they are doing.
        assertTrue(source.contains("resultViewers.add(player.getUniqueId())"));
        assertTrue(source.contains("resultViewers.remove(target.getUniqueId())"));
    }

    /**
     * Two accounts owned by one person cannot hand each other wins, and two people
     * cannot spend an evening taking turns to die.
     */
    @Test
    void farmingTheSameOpponentIsRefusedRatherThanRated() throws Exception {
        String source = source();
        String problem = source.substring(
                source.indexOf("private String challengeProblem(Player challenger, Player target)"),
                source.indexOf("private boolean acceptingChallenges("));

        assertTrue(problem.contains(
                "identities.sameOwner(challenger.getUniqueId(), target.getUniqueId())"));
        assertTrue(problem.contains("farmGuard.restRemaining("));
        // Recorded once per finished fight, whoever won it.
        assertTrue(source.contains("noteRepeatOpponent(fight)"));
        // Somebody who cannot be challenged is not offered as a target either.
        String targets = source.substring(
                source.indexOf("private List<Player> targets(Player viewer)"),
                source.indexOf("private boolean insideAnyArena("));
        assertTrue(targets.contains("identities.sameOwner("));
        assertTrue(targets.contains("farmGuard.restRemaining("));
    }

    /**
     * An arena is chosen in terrain nothing has generated yet, so it has to be built
     * before anybody is standing in it rather than around them while they fight.
     */
    @Test
    void theArenaIsGeneratedAndPinnedBeforeEitherFighterArrives() throws Exception {
        String source = source();
        String prepare = source.substring(
                source.indexOf("private void loadArenaChunks("),
                source.indexOf("private void releaseArenaChunks(List<Chunk> held)"));

        assertTrue(prepare.contains("getChunkAtAsync"));
        // A plain load is undone the moment nobody stands in the chunk.
        assertTrue(prepare.contains("addPluginChunkTicket(plugin)"));
        // The wait is told, not endured in silence.
        assertTrue(source.contains("\"Getting things ready...\""));
        assertTrue(prepare.contains("\"You will be teleported shortly...\""));
        // And it is all given back when the arena is.
        assertTrue(source.contains("releaseArenaChunks(fight.id)"));
    }

    /**
     * Vanilla's PLAYER_KILLS counts anybody killed anywhere. A duel is consensual,
     * staked and fought in an identical arena, which is what makes it rankable.
     */
    @Test
    void theKillsBoardsCountDuelsRatherThanVanillaKills() throws Exception {
        String stats = Files.readString(SOURCE.getParent().resolve("PlayerStats.java"),
                StandardCharsets.UTF_8);
        assertTrue(stats.contains("case KILLS -> duelKills;"));

        String service = Files.readString(SOURCE.getParent().resolve("PlayerStatsService.java"),
                StandardCharsets.UTF_8);
        assertTrue(service.contains("withDuelRecord(duels.of(uuid))"));

        // The board used to refresh off a vanilla statistic that no longer feeds it.
        String board = Files.readString(SOURCE.getParent().resolve("LeaderboardService.java"),
                StandardCharsets.UTF_8);
        assertFalse(board.contains("PlayerStatisticIncrementEvent"));
        assertTrue(board.contains("PvP Kills"));

        String source = source();
        assertTrue(source.contains("duelRecords.settleCasual(winnerId, fight.opponent(winnerId),"));
        assertTrue(source.contains("duelRecords.drewCasual(fight.first, fight.second)"));
        // A surrender is a win, not a kill.
        assertTrue(source.contains("ending == Ending.KILL"));
        assertTrue(source.contains("player.getName() + \" gave up\", Ending.SURRENDER"));
    }

    /**
     * A ladder you climb by duelling the worst player online is a ladder whose top
     * means nothing, so what a win is worth depends on who it was against.
     */
    @Test
    void theRankLadderIsRatedAgainstTheOpponent() throws Exception {
        String rank = Files.readString(SOURCE.getParent().resolve("PvpRank.java"),
                StandardCharsets.UTF_8);
        assertTrue(rank.contains("Math.pow(10d, (opponentRating - rating) / 400d)"));

        String store = Files.readString(SOURCE.getParent().resolve("PvpRecordStore.java"),
                StandardCharsets.UTF_8);
        String settle = store.substring(
                store.indexOf("synchronized Map<UUID, RatingChange> settleMatch("),
                store.indexOf("synchronized Map<UUID, RatingChange> drawMatch("));
        // Both ratings are read before either is written, or every result inflates.
        assertTrue(settle.indexOf("long winnerAverage = averageRating(winners)")
                < settle.indexOf("records.put(winner"));
        assertTrue(settle.indexOf("long loserAverage = averageRating(losers)")
                < settle.indexOf("records.put(winner"));
        assertTrue(settle.contains("rated ? PvpRank.change"));

        String source = source();
        assertTrue(source.contains("rankChangeEffect(player, result.rating())"));
        assertTrue(source.contains("rating.promoted()"));
        assertTrue(source.contains("rating.demoted()"));
    }

    @Test
    void challengesRespectPrivacyAndDoNotForceOpenARecipientsMenu() throws Exception {
        String source = source();
        assertTrue(source.contains("PlayerSettingsStore.Setting.DUEL_REQUESTS"));
        assertTrue(source.contains("pvp-duels.challenge-cooldown-seconds"));
        String sender = source.substring(
                source.indexOf("private String sendChallenge"),
                source.indexOf("private void openIncoming")
        );
        assertFalse(sender.contains("openInvitation(target, invitation);"));
    }
}
