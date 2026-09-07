package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PvpRankLeaderboardTest {
    @Test
    void ordersRatingThenWinsThenKillsAndKeepsNamesVisible() {
        UUID high = UUID.randomUUID();
        UUID moreWins = UUID.randomUUID();
        UUID moreKills = UUID.randomUUID();
        Map<UUID, PvpRecordStore.Record> records = new LinkedHashMap<>();
        records.put(moreKills, record(400, 4, 4, 1));
        records.put(high, record(700, 2, 2, 1));
        records.put(moreWins, record(400, 5, 1, 1));

        Map<UUID, String> names = Map.of(
                high, "Highest",
                moreWins, "Winner",
                moreKills, "Killer"
        );
        List<PvpRankLeaderboard.Row> ranked = PvpRankLeaderboard.top(
                records, names::get, 10
        );

        assertEquals(List.of("Highest", "Winner", "Killer"),
                ranked.stream().map(PvpRankLeaderboard.Row::username).toList());
        assertEquals(List.of(1, 2, 3),
                ranked.stream().map(PvpRankLeaderboard.Row::placement).toList());
    }

    @Test
    void includesAZeroRatedFighterButNotAnEmptyRecordAndCapsTheBoard() {
        Map<UUID, PvpRecordStore.Record> records = new LinkedHashMap<>();
        UUID zeroRated = UUID.randomUUID();
        records.put(zeroRated, record(0, 0, 0, 1));
        records.put(UUID.randomUUID(), PvpRecordStore.Record.EMPTY);
        for (int index = 1; index <= 12; index++) {
            records.put(UUID.randomUUID(), record(index * 10L, index, index, 0));
        }

        List<PvpRankLeaderboard.Row> all = PvpRankLeaderboard.top(
                records, id -> "Player-" + id.toString().substring(0, 4), 20
        );
        List<PvpRankLeaderboard.Row> topTen = PvpRankLeaderboard.top(
                records, id -> "Player", 10
        );

        assertEquals(13, all.size());
        assertEquals(zeroRated, all.get(12).playerId());
        assertEquals(10, topTen.size());
    }

    @Test
    void theRankLadderIsAnInWorldHologramBoardUnderEveryNameForIt() {
        // It used to be menu-only. It is a board people can stand in front of now, so
        // the names an operator would actually type all have to reach it.
        for (String typed : List.of("rank", "ranks", "pvp", "pvp-rank", "pvp-ranks")) {
            assertEquals(HologramService.Board.PVP_RANKS,
                    HologramService.Board.fromKey(typed), typed);
        }
        assertThrows(IllegalArgumentException.class,
                () -> HologramService.Board.fromKey("pvp-rating"));
    }

    @Test
    void onlyTheCurrentTopThreeReceivePlacementRewards() {
        Map<UUID, PvpRecordStore.Record> records = new LinkedHashMap<>();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        UUID fourth = UUID.randomUUID();
        records.put(first, record(900, 5, 5, 1));
        records.put(second, record(800, 5, 5, 1));
        records.put(third, record(700, 5, 5, 1));
        records.put(fourth, record(600, 5, 5, 1));

        List<PvpRankLeaderboard.Row> podium = PvpRankLeaderboard.top(
                records, id -> "Player", PvpRankRewardService.PODIUM);

        assertEquals(3, podium.size());
        assertEquals(first, podium.get(0).playerId());
        assertEquals(second, podium.get(1).playerId());
        assertEquals(third, podium.get(2).playerId());
        // Fourth holds no Scythe, whatever their rating: the reward is the placement.
        assertTrue(podium.stream().noneMatch(row -> row.playerId().equals(fourth)));
    }

    private static PvpRecordStore.Record record(
            long rating, long wins, long kills, long losses
    ) {
        return new PvpRecordStore.Record(
                kills, losses, wins, losses, 0, wins, wins,
                rating, PvpRank.of(rating)
        );
    }
}
