package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeaderboardStandingsTest {
    @Test
    void keepsEachPlayersBestPublishedPlacement() {
        UUID combat = UUID.randomUUID();
        UUID wealthy = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        List<PlayerStats> players = List.of(
                stats(combat, "Combat", 50, 20),
                stats(wealthy, "Wealthy", 10, 500),
                stats(third, "Third", 5, 1)
        );

        Map<UUID, LeaderboardStandings.Standing> standings =
                LeaderboardStandings.bestByPlayer(players);

        assertEquals(LeaderboardType.KILLS, standings.get(combat).type());
        assertEquals(1, standings.get(combat).placement());
        assertEquals(LeaderboardType.WEALTH, standings.get(wealthy).type());
        assertEquals(1, standings.get(wealthy).placement());
        assertEquals(3, standings.get(third).placement());
    }

    @Test
    void ignoresZeroScoresAndUnpublishedBoards() {
        UUID player = UUID.randomUUID();
        PlayerStats onlyWalking = new PlayerStats(player, "Walker", 0, 0, 200, 8, 900, 0);

        assertEquals(Map.of(), LeaderboardStandings.bestByPlayer(List.of(onlyWalking)));
    }

    @Test
    void tracksPlacementsSeparatelyForEveryPublishedBoard() {
        UUID combat = UUID.randomUUID();
        UUID wealthy = UUID.randomUUID();
        List<PlayerStats> players = List.of(
                stats(combat, "Combat", 50, 20),
                stats(wealthy, "Wealthy", 10, 500)
        );

        Map<LeaderboardStandings.BoardPlayer, LeaderboardStandings.Standing> standings =
                LeaderboardStandings.individualByBoard(players);

        assertEquals(
                1,
                standings.get(new LeaderboardStandings.BoardPlayer(
                        LeaderboardType.KILLS, combat
                )).placement()
        );
        assertEquals(
                2,
                standings.get(new LeaderboardStandings.BoardPlayer(
                        LeaderboardType.WEALTH, combat
                )).placement()
        );
    }

    @Test
    void detectsOnlyRealPlacementIncreases() {
        LeaderboardStandings.Standing fourth =
                new LeaderboardStandings.Standing(LeaderboardType.KILLS, 4, 20);
        LeaderboardStandings.Standing third =
                new LeaderboardStandings.Standing(LeaderboardType.KILLS, 3, 25);
        LeaderboardStandings.Standing fifth =
                new LeaderboardStandings.Standing(LeaderboardType.KILLS, 5, 15);

        assertEquals(
                3,
                LeaderboardService.improvement(fourth, third).orElseThrow()
                        .current().placement()
        );
        assertTrue(LeaderboardService.improvement(third, fifth).isEmpty());
        assertTrue(LeaderboardService.improvement(third, third).isEmpty());
    }

    private static PlayerStats stats(UUID id, String name, long kills, long wealth) {
        return new PlayerStats(id, name, kills, 0, 0, 0, 0, wealth);
    }
}
