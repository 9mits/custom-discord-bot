package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PvpMatchmakingTest {
    @Test
    void rankedRangeWidensButNeverPastTheConfiguredLimit() {
        assertEquals(100, PvpMatchmaking.ratingWindow(0, 100, 5, 300));
        assertEquals(150, PvpMatchmaking.ratingWindow(10_000, 100, 5, 300));
        assertEquals(300, PvpMatchmaking.ratingWindow(999_000, 100, 5, 300));
    }

    @Test
    void soloPlayersFillTwoCompleteDoublesTeams() {
        long now = 100_000L;
        List<PvpMatchmaking.Entry> queue = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            UUID player = UUID.randomUUID();
            queue.add(new PvpMatchmaking.Entry(player, List.of(player), true,
                    now - index * 1_000L, 400 + index * 5L, null, 2, 4));
        }
        PvpMatchmaking.Plan plan = PvpMatchmaking.teams(
                queue, 2, now, 100, 1, 500).orElseThrow();
        assertEquals(2, plan.firstTeam().size());
        assertEquals(2, plan.secondTeam().size());
        assertEquals(4, java.util.stream.Stream.concat(
                plan.firstTeam().stream(), plan.secondTeam().stream()).distinct().count());
    }

    @Test
    void noFillWaitsForACompletePremadeAndOpponentPolicyIsHonoured() {
        long now = 100_000L;
        UUID solo = UUID.randomUUID();
        PvpMatchmaking.Entry incomplete = new PvpMatchmaking.Entry(
                solo, List.of(solo), false, now, 0, null, 2, 4);
        assertTrue(PvpMatchmaking.teams(List.of(incomplete), 2, now,
                1_000, 0, 1_000).isEmpty());

        UUID firstA = UUID.randomUUID();
        UUID firstB = UUID.randomUUID();
        UUID secondA = UUID.randomUUID();
        UUID secondB = UUID.randomUUID();
        List<PvpMatchmaking.Entry> premades = List.of(
                new PvpMatchmaking.Entry(firstA, List.of(firstA, firstB), false,
                        now, 500, null, 2, 4),
                new PvpMatchmaking.Entry(secondA, List.of(secondA, secondB), false,
                        now, 500, null, 2, 4)
        );
        assertFalse(PvpMatchmaking.teams(premades, 2, now, 100, 0, 100,
                (left, right) -> false).isPresent());
        assertTrue(PvpMatchmaking.teams(premades, 2, now, 100, 0, 100,
                (left, right) -> true).isPresent());
    }

    @Test
    void fillCanBalanceMixedRatingsInsteadOfGettingStuckOnQueueOrder() {
        long now = 100_000L;
        List<PvpMatchmaking.Entry> queue = new ArrayList<>();
        long[] ratings = {1_000, 1_000, 0, 0};
        for (int index = 0; index < ratings.length; index++) {
            UUID player = UUID.randomUUID();
            queue.add(new PvpMatchmaking.Entry(player, List.of(player), true,
                    now - (ratings.length - index) * 1_000L, ratings[index], null, 2, 4));
        }
        PvpMatchmaking.Plan plan = PvpMatchmaking.teams(
                queue, 2, now, 100, 0, 100).orElseThrow();
        long first = plan.firstEntries().stream().mapToLong(PvpMatchmaking.Entry::averageRating).sum();
        long second = plan.secondEntries().stream().mapToLong(PvpMatchmaking.Entry::averageRating).sum();
        assertEquals(first, second);
    }
}
