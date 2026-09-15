package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SeasonStoreTest {
    @TempDir
    Path directory;

    @Test
    void seasonHeartsRespectTheCapAndExpireWithTheSeason() throws Exception {
        Path file = directory.resolve("season-pass.json");
        SeasonStore store = new SeasonStore(file);
        UUID player = UUID.randomUUID();
        store.startSeason(1, 100, 142);

        assertEquals(2, store.addHearts(player, 2, 3));
        assertEquals(1, store.addHearts(player, 5, 3), "only up to the cap is added");
        assertEquals(0, store.addHearts(player, 1, 3), "a capped player gets nothing more");
        store.persist();
        assertEquals(3, new SeasonStore(file).hearts(player), "hearts survive a restart inside the season");

        store.startSeason(2, 142, 184);
        assertEquals(0, store.hearts(player), "a new season starts everybody on zero hearts");
        assertEquals(1, store.addHearts(player, 1, 3), "and the new season can pay them again");
    }

    @Test
    void boardsActivityAndTheCommunityGoalSurviveARestart() throws Exception {
        Path file = directory.resolve("season-pass.json");
        SeasonStore store = new SeasonStore(file);
        UUID player = UUID.randomUUID();
        SeasonStore.Row row = store.row(player);
        row.activity.put("mine_ores", 40L);
        row.daily = new SeasonStore.Board();
        row.daily.period = 20_712L;
        row.daily.slots = SeasonQuestRules.dealDaily(player, 20_712L,
                new SeasonQuestRules.Profile(row.activity, false, true));
        row.daily.slots.get(0).progress = 7;
        row.owedXp = 1_000;
        row.owedGiftbagSeasons.add(1);
        SeasonStore.Community goal = new SeasonStore.Community();
        goal.week = 2_958L;
        goal.objective = "kill_mobs";
        goal.target = 2_000;
        goal.contributions.put(player.toString(), 55L);
        store.community(goal);
        store.persist();

        SeasonStore reopened = new SeasonStore(file);
        SeasonStore.Row back = reopened.row(player);
        assertEquals(40L, back.activity.get("mine_ores"));
        assertEquals(20_712L, back.daily.period);
        assertEquals(7, back.daily.slots.get(0).progress);
        assertEquals(row.daily.slots.get(2).objective, back.daily.slots.get(2).objective);
        assertEquals(1_000, back.owedXp);
        assertEquals(List.of(1), back.owedGiftbagSeasons);
        assertEquals(55L, reopened.community().contributions.get(player.toString()));
    }

    @Test
    void anOldStoreWithoutQuestBoardsStillLoads() throws Exception {
        Path file = directory.resolve("season-pass.json");
        java.nio.file.Files.writeString(file, "{\"season\":1,\"startedDay\":1,\"endsDay\":43,"
                + "\"players\":{\"" + UUID.nameUUIDFromBytes(new byte[] {1}) + "\":{\"xp\":5}}}");
        SeasonStore store = new SeasonStore(file);
        SeasonStore.Row row = store.row(UUID.nameUUIDFromBytes(new byte[] {1}));
        assertEquals(5, row.xp);
        assertTrue(row.activity.isEmpty());
        assertTrue(row.owedGiftbagSeasons.isEmpty(), "old saves migrate the Giftbag queue");
        assertEquals(Long.MIN_VALUE, store.community().week);
        assertTrue(store.hourlyPeaks().isEmpty());
    }

    @Test
    void weeklyTotalsOnlyCarryIntoTheNextConsecutiveWeek() throws Exception {
        SeasonStore store = new SeasonStore(directory.resolve("season-pass.json"));
        assertTrue(store.rollWeek(10));
        assertFalse(store.rollWeek(10), "the same week never rolls twice");
        store.addWeekTotal("mine_ores", 300);
        store.markWeekPlayer(UUID.randomUUID());
        store.markWeekPlayer(UUID.randomUUID());
        assertTrue(store.rollWeek(11));
        assertEquals(300, store.lastWeekTotal("mine_ores"));
        assertEquals(2, store.lastWeekPlayers());
        store.addWeekTotal("mine_ores", 900);
        assertTrue(store.rollWeek(14));
        assertEquals(0, store.lastWeekTotal("mine_ores"), "a gap of weeks is not last week");
    }

    @Test
    void hourlyPeaksKeepOneWeek() throws Exception {
        SeasonStore store = new SeasonStore(directory.resolve("season-pass.json"));
        store.recordActive(1_000, 3);
        store.recordActive(1_000, 8);
        store.recordActive(1_000, 5);
        assertEquals(List.of(8), store.hourlyPeaks(), "an hour keeps its peak");
        for (long hour = 1_001; hour <= 1_200; hour++) store.recordActive(hour, 1);
        assertEquals(168, store.hourlyPeaks().size());
    }
}
