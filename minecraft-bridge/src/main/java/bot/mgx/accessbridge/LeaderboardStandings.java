package bot.mgx.accessbridge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Pure ranking logic shared by nameplates and the virtual podium cosmetics. */
final class LeaderboardStandings {
    record Standing(LeaderboardType type, int placement, long value) {
        Standing {
            if (type == null || placement < 1 || value <= 0) {
                throw new IllegalArgumentException("A standing needs a positive rank and value");
            }
        }
    }

    private LeaderboardStandings() {
    }

    static Map<UUID, Standing> bestByPlayer(List<PlayerStats> players) {
        Map<UUID, Standing> best = new HashMap<>();
        for (LeaderboardType type : LeaderboardType.values()) {
            if (!type.published()) {
                continue;
            }
            List<PlayerStats> ranked = new ArrayList<>(players);
            ranked.sort(Comparator
                    .comparingLong((PlayerStats row) -> row.value(type)).reversed()
                    .thenComparing(PlayerStats::username, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(row -> row.minecraftUuid().toString()));
            int placement = 0;
            for (PlayerStats row : ranked) {
                if (row.value(type) <= 0) {
                    continue;
                }
                placement++;
                Standing candidate = new Standing(type, placement, row.value(type));
                best.merge(row.minecraftUuid(), candidate, LeaderboardStandings::better);
            }
        }
        return Map.copyOf(best);
    }

    private static Standing better(Standing current, Standing candidate) {
        if (candidate.placement() != current.placement()) {
            return candidate.placement() < current.placement() ? candidate : current;
        }
        // At an equal placement, combat is the more recognizable nameplate badge.
        if (candidate.type() == LeaderboardType.KILLS) {
            return candidate;
        }
        return current;
    }
}
