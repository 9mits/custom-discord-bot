package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SeasonQuestRulesTest {
    private static final UUID PLAYER = UUID.fromString("32e387e9-9488-4071-85d5-48f2325bf8a2");
    private static final SeasonQuestRules.Profile NEWCOMER =
            new SeasonQuestRules.Profile(Map.of(), false, true);

    @Test
    void boardsResetAtUtcMidnightAndWeeksOnMonday() {
        long monday = LocalDate.of(2026, 9, 14).toEpochDay();
        assertEquals(DayOfWeek.MONDAY, LocalDate.ofEpochDay(monday).getDayOfWeek());
        long week = SeasonQuestRules.weeklyPeriod(monday);
        assertEquals(week, SeasonQuestRules.weeklyPeriod(monday + 6), "Sunday is the same week");
        assertEquals(week - 1, SeasonQuestRules.weeklyPeriod(monday - 1), "the Sunday before is the last week");
        assertEquals(monday + 7, SeasonQuestRules.nextWeekStartDay(week));
    }

    @Test
    void aBoardIsTheSameEveryTimeItIsDealtSoItCannotBeRerolled() {
        long day = 20_712L;
        List<String> first = describe(SeasonQuestRules.dealDaily(PLAYER, day, NEWCOMER));
        assertEquals(first, describe(SeasonQuestRules.dealDaily(PLAYER, day, NEWCOMER)));
        boolean changes = false;
        for (long later = day + 1; later < day + 10; later++) {
            changes |= !first.equals(describe(SeasonQuestRules.dealDaily(PLAYER, later, NEWCOMER)));
        }
        assertTrue(changes, "the board must change from day to day");
    }

    @Test
    void everyDailyBoardHasOneQuestPerPurpose() {
        for (long day = 0; day < 60; day++) {
            List<SeasonStore.Slot> slots = SeasonQuestRules.dealDaily(PLAYER, day, NEWCOMER);
            assertEquals(List.of("YOUR_GAME", "TOGETHER", "TRY_SOMETHING"),
                    slots.stream().map(slot -> slot.goal).toList());
            assertEquals(SeasonQuestRules.Kind.GRIND, objective(slots.get(0)).kind);
            assertEquals(SeasonQuestRules.Objective.TOGETHER_MINUTES, objective(slots.get(1)),
                    "a player without a clan plays near anybody");
            assertEquals(SeasonQuestRules.Kind.FEATURE, objective(slots.get(2)).kind);
            assertTrue(slots.stream().allMatch(slot -> slot.target > 0 && slot.progress == 0 && !slot.done));
        }
    }

    @Test
    void clanPlayersAreAskedToPlayWithTheirClan() {
        SeasonQuestRules.Profile clan = new SeasonQuestRules.Profile(Map.of(), true, true);
        assertEquals(SeasonQuestRules.Objective.CLAN_MINUTES,
                objective(SeasonQuestRules.dealDaily(PLAYER, 1, clan).get(1)));
        assertEquals(SeasonQuestRules.Objective.CLAN_MINUTES,
                objective(SeasonQuestRules.dealWeekly(PLAYER, 1, clan, 4).get(1)));
    }

    @Test
    void closedPvpIsNeverDealt() {
        SeasonQuestRules.Profile closed = new SeasonQuestRules.Profile(Map.of(), false, false);
        for (long period = 0; period < 200; period++) {
            assertNotEquals(SeasonQuestRules.Objective.PLAY_PVP,
                    objective(SeasonQuestRules.dealDaily(PLAYER, period, closed).get(2)));
            assertNotEquals(SeasonQuestRules.Objective.PLAY_PVP,
                    objective(SeasonQuestRules.dealWeekly(PLAYER, period, closed, 4).get(2)));
        }
    }

    @Test
    void tryingSomethingDealsTheFeatureAPlayerUsesLeast() {
        // Plays PvP and trades a lot, has never touched a server event.
        SeasonQuestRules.Profile profile = new SeasonQuestRules.Profile(Map.of(
                SeasonQuestRules.ACTIVE_MINUTES, 6_000L, "play_pvp", 400L, "buy_auction", 300L), false, true);
        Map<SeasonQuestRules.Objective, Integer> dealt = new EnumMap<>(SeasonQuestRules.Objective.class);
        for (long day = 0; day < 300; day++) {
            dealt.merge(objective(SeasonQuestRules.dealDaily(PLAYER, day, profile).get(2)), 1, Integer::sum);
        }
        assertTrue(dealt.getOrDefault(SeasonQuestRules.Objective.JOIN_EVENT, 0) > 150, dealt.toString());
        assertEquals(0, dealt.getOrDefault(SeasonQuestRules.Objective.PLAY_PVP, 0),
                "the feature they use most is never the one to try: " + dealt);
    }

    @Test
    void yourGameFollowsWhatAPlayerActuallyDoes() {
        SeasonQuestRules.Profile miner = new SeasonQuestRules.Profile(Map.of(
                SeasonQuestRules.ACTIVE_MINUTES, 3_000L, "mine_ores", 5_000L, "kill_mobs", 200L), false, true);
        Map<SeasonQuestRules.Objective, Integer> dealt = new EnumMap<>(SeasonQuestRules.Objective.class);
        for (long day = 0; day < 200; day++) {
            dealt.merge(objective(SeasonQuestRules.dealDaily(PLAYER, day, miner).get(0)), 1, Integer::sum);
        }
        assertTrue(dealt.get(SeasonQuestRules.Objective.MINE_ORES) > 120, dealt.toString());
        assertTrue(dealt.getOrDefault(SeasonQuestRules.Objective.KILL_MOBS, 0) > 0,
                "a miner still sees their second activity now and then: " + dealt);
        assertFalse(dealt.containsKey(SeasonQuestRules.Objective.HARVEST_CROPS), "they never farm");

        for (long day = 0; day < 50; day++) {
            SeasonQuestRules.Objective first = objective(SeasonQuestRules.dealDaily(PLAYER, day, NEWCOMER).get(0));
            assertTrue(first == SeasonQuestRules.Objective.KILL_MOBS || first == SeasonQuestRules.Objective.MINE_ORES,
                    "a newcomer starts with the server's two most common activities");
        }
    }

    @Test
    void yourGameTargetsMatchThePlayersOwnPace() {
        SeasonQuestRules.Objective kills = SeasonQuestRules.Objective.KILL_MOBS;
        assertEquals(30, SeasonQuestRules.personalTarget(kills, 30, NEWCOMER), "no history, no guess");
        SeasonQuestRules.Profile grinder = new SeasonQuestRules.Profile(Map.of(
                SeasonQuestRules.ACTIVE_MINUTES, 600L, "kill_mobs", 5_000L), false, true);
        assertEquals(75, SeasonQuestRules.personalTarget(kills, 30, grinder), "capped at 2.5x the default");
        SeasonQuestRules.Profile slow = new SeasonQuestRules.Profile(Map.of(
                SeasonQuestRules.ACTIVE_MINUTES, 600L, "kill_mobs", 10L), false, true);
        assertEquals(20, SeasonQuestRules.personalTarget(kills, 30, slow), "never below 0.6x");
        assertEquals(1, SeasonQuestRules.personalTarget(SeasonQuestRules.Objective.JOIN_EVENT, 1, grinder),
                "only grind quests scale");
    }

    @Test
    void targetsAreNumbersPeopleRead() {
        assertEquals(1, SeasonQuestRules.roundTarget(0));
        assertEquals(4, SeasonQuestRules.roundTarget(4));
        assertEquals(35, SeasonQuestRules.roundTarget(37));
        assertEquals(130, SeasonQuestRules.roundTarget(126));
        assertEquals(475, SeasonQuestRules.roundTarget(481));
        assertEquals(2_400, SeasonQuestRules.roundTarget(2_390));
    }

    @Test
    void weeklyBoardsRewardComingBackNotOneLongSitting() {
        List<SeasonStore.Slot> week = SeasonQuestRules.dealWeekly(PLAYER, 3, NEWCOMER, 4);
        assertEquals(SeasonQuestRules.Objective.PLAY_DAYS, objective(week.get(0)));
        assertEquals("COME_BACK", week.get(0).goal);
        assertEquals(4, week.get(0).target);
        assertEquals(1, SeasonQuestRules.dealWeekly(PLAYER, 3, NEWCOMER, 0).get(0).target);
        assertEquals(7, SeasonQuestRules.dealWeekly(PLAYER, 3, NEWCOMER, 99).get(0).target);
        assertTrue(week.stream().noneMatch(slot -> objective(slot).kind == SeasonQuestRules.Kind.GRIND));
    }

    @Test
    void progressFinishesASlotOnceAndTheSweepPaysOnce() {
        SeasonStore.Board board = new SeasonStore.Board();
        board.slots = SeasonQuestRules.dealWeekly(PLAYER, 3, NEWCOMER, 2);
        SeasonStore.Slot days = board.slots.get(0);
        assertTrue(SeasonQuestRules.advance(board, SeasonQuestRules.Objective.PLAY_DAYS, 1).isEmpty());
        assertEquals(List.of(days), SeasonQuestRules.advance(board, SeasonQuestRules.Objective.PLAY_DAYS, 5));
        assertEquals(2, days.progress, "progress stops at the target");
        assertTrue(SeasonQuestRules.advance(board, SeasonQuestRules.Objective.PLAY_DAYS, 1).isEmpty(),
                "a finished slot is never finished again");
        assertFalse(SeasonQuestRules.takeSweep(board));
        for (SeasonStore.Slot slot : board.slots) {
            SeasonQuestRules.advance(board, objective(slot), slot.target);
        }
        assertEquals(3, SeasonQuestRules.done(board));
        assertTrue(SeasonQuestRules.takeSweep(board));
        assertFalse(SeasonQuestRules.takeSweep(board), "the board bonus pays once");
    }

    @Test
    void aDayCountsOnceAfterRealPlayNotALoginAndLogout() {
        SeasonStore.Row row = new SeasonStore.Row();
        for (int minute = 1; minute < 10; minute++) assertFalse(SeasonQuestRules.countActiveMinute(row, 100, 10));
        assertTrue(SeasonQuestRules.countActiveMinute(row, 100, 10));
        assertFalse(SeasonQuestRules.countActiveMinute(row, 100, 10), "counted once per day");
        assertFalse(SeasonQuestRules.countActiveMinute(row, 101, 10), "a new day starts from zero");
        assertEquals(1, row.activeDayMinutes);
    }

    @Test
    void catchUpLiftsPlayersBelowTheSeasonsPace() {
        assertEquals(0, SeasonQuestRules.paceTier(100, 100, 142, 50, 50), "nobody is behind on day one");
        assertEquals(12, SeasonQuestRules.paceTier(121, 100, 142, 50, 50), "halfway through: a quarter of the pass");
        assertEquals(25, SeasonQuestRules.paceTier(200, 100, 142, 50, 50), "the line stops at the season's end");
        assertEquals(0, SeasonQuestRules.paceTier(121, 100, 142, 50, 0), "0 turns catch-up off");
        assertTrue(SeasonQuestRules.catchingUp(3, 12));
        assertFalse(SeasonQuestRules.catchingUp(12, 12));
        assertEquals(450, SeasonQuestRules.boosted(150, 200, 150), "boosts multiply");
        assertEquals(150, SeasonQuestRules.boosted(150, 100, 40), "a boost never lowers XP");
    }

    @Test
    void theRallyFollowsTheServersBusiestHoursAndDoesNotFlicker() {
        assertEquals(6, SeasonQuestRules.rallyThreshold(List.of(9, 9, 9), 6), "too little history uses the minimum");
        // The last week on the live server: quiet nights, 8-9 player evenings.
        List<Integer> week = Stream.of(1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 7, 8, 8, 9, 0, 0)
                .collect(Collectors.toList());
        assertEquals(7, SeasonQuestRules.rallyThreshold(week, 6), "the busiest quarter of hours");
        assertEquals(10, SeasonQuestRules.rallyThreshold(week, 10), "never below the owner's minimum");
        assertEquals(2, SeasonQuestRules.rallyThreshold(List.of(), 1), "a Rally always needs company");

        assertFalse(SeasonQuestRules.rallyLive(false, 6, 7));
        assertTrue(SeasonQuestRules.rallyLive(false, 7, 7));
        assertTrue(SeasonQuestRules.rallyLive(true, 6, 7), "one player leaving does not end it");
        assertFalse(SeasonQuestRules.rallyLive(true, 5, 7));
    }

    @Test
    void theCommunityGoalAsksForALittleMoreThanLastWeek() {
        SeasonQuestRules.Objective ores = SeasonQuestRules.Objective.MINE_ORES;
        assertEquals(5_500, SeasonQuestRules.communityTarget(ores, 5_000, 20, 10));
        assertEquals(2_000, SeasonQuestRules.communityTarget(ores, 2_000, 0, 0),
                "no growth means last week again");
        assertEquals(1_500, SeasonQuestRules.communityTarget(ores, 0, 20, 10),
                "no history: half a weekly quest for each of last week's players");
        assertEquals(450, SeasonQuestRules.communityTarget(ores, 10, 1, 10), "never below three weekly quests");
        assertEquals(20, SeasonQuestRules.contributorMinimum(ores, 5_500), "a day's quest counts as helping");
        assertEquals(9, SeasonQuestRules.contributorMinimum(ores, 450), "or 2% of a small goal");
        assertEquals(SeasonQuestRules.COMMUNITY.size(), java.util.stream.LongStream.range(0, 8)
                .mapToObj(SeasonQuestRules::communityObjective).distinct().count(), "goals rotate");
    }

    @Test
    void labelsReadNaturallyForOne() {
        assertEquals("Take part in 1 server event", SeasonQuestRules.Objective.JOIN_EVENT.label(1));
        assertEquals("Finish 1 ranked PvP match", SeasonQuestRules.Objective.PLAY_PVP.label(1));
        assertEquals("Defeat 1,250 hostile mobs", SeasonQuestRules.Objective.KILL_MOBS.label(1_250));
    }

    /**
     * An objective with no hook deals a quest nobody can finish. Every objective must be
     * recorded somewhere outside the rules, or mirrored from a season ladder.
     */
    @Test
    void everyObjectiveIsCountedSomewhereInTheGame() throws Exception {
        Map<String, String> sources = new HashMap<>();
        try (Stream<Path> files = Files.list(Path.of("src/main/java/bot/mgx/accessbridge"))) {
            for (Path file : files.toList()) {
                if (!file.getFileName().toString().equals("SeasonQuestRules.java")) {
                    sources.put(file.getFileName().toString(), Files.readString(file));
                }
            }
        }
        String all = String.join("\n", sources.values());
        for (SeasonQuestRules.Objective objective : SeasonQuestRules.Objective.values()) {
            String reference = "SeasonQuestRules.Objective." + objective.name();
            assertTrue(all.contains(reference), objective + " is dealt but never counted");
        }
        String service = sources.get("SeasonPassService.java");
        assertTrue(service.contains("private static java.util.Optional<SeasonQuestRules.Objective> mirror("),
                "grind objectives are counted through their season ladders");
    }

    private static SeasonQuestRules.Objective objective(SeasonStore.Slot slot) {
        return SeasonQuestRules.Objective.of(slot.objective).orElseThrow();
    }

    private static List<String> describe(List<SeasonStore.Slot> slots) {
        return slots.stream().map(slot -> slot.goal + ":" + slot.objective + ":" + slot.target).toList();
    }
}
