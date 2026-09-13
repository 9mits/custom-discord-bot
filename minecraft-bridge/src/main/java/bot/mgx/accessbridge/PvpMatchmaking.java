package bot.mgx.accessbridge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;

/** Pure queue-selection rules; Bukkit owns the clock and player validation around them. */
final class PvpMatchmaking {
    record Entry(
            UUID id,
            List<UUID> members,
            boolean fill,
            long joinedAt,
            long averageRating,
            UUID clanId,
            int teamSize,
            int targetPlayers
    ) {
        Entry {
            members = List.copyOf(members);
            teamSize = Math.max(1, teamSize);
            targetPlayers = Math.max(2, targetPlayers);
        }
    }

    record Plan(List<Entry> firstEntries, List<Entry> secondEntries) {
        Plan {
            firstEntries = List.copyOf(firstEntries);
            secondEntries = List.copyOf(secondEntries);
        }

        List<UUID> firstTeam() {
            return members(firstEntries);
        }

        List<UUID> secondTeam() {
            return members(secondEntries);
        }

        private static List<UUID> members(List<Entry> entries) {
            List<UUID> result = new ArrayList<>();
            entries.forEach(entry -> result.addAll(entry.members()));
            return List.copyOf(result);
        }
    }

    private PvpMatchmaking() {
    }

    static long ratingWindow(
            long waitedMillis, long baseRange, long widenPerSecond, long maximumRange
    ) {
        long seconds = Math.max(0L, waitedMillis) / 1_000L;
        long widened;
        try {
            widened = Math.addExact(Math.max(0L, baseRange),
                    Math.multiplyExact(seconds, Math.max(0L, widenPerSecond)));
        } catch (ArithmeticException overflow) {
            widened = Long.MAX_VALUE;
        }
        return Math.min(Math.max(0L, maximumRange), widened);
    }

    static boolean ratingCompatible(
            Entry first,
            Entry second,
            long now,
            long baseRange,
            long widenPerSecond,
            long maximumRange
    ) {
        long waited = Math.max(now - first.joinedAt(), now - second.joinedAt());
        long window = ratingWindow(waited, baseRange, widenPerSecond, maximumRange);
        return Math.abs(first.averageRating() - second.averageRating()) <= window;
    }

    static java.util.Optional<Plan> teams(
            List<Entry> source,
            int teamSize,
            long now,
            long baseRange,
            long widenPerSecond,
            long maximumRange
    ) {
        return teams(source, teamSize, now, baseRange, widenPerSecond, maximumRange,
                (first, second) -> true);
    }

    static java.util.Optional<Plan> teams(
            List<Entry> source,
            int teamSize,
            long now,
            long baseRange,
            long widenPerSecond,
            long maximumRange,
            BiPredicate<List<UUID>, List<UUID>> opponents
    ) {
        if (teamSize <= 0) return java.util.Optional.empty();
        List<Entry> entries = source.stream()
                .sorted(Comparator.comparingLong(Entry::joinedAt).thenComparing(Entry::id))
                .toList();
        List<List<Entry>> candidates = candidateTeams(entries, teamSize);
        candidates.sort(Comparator.comparingLong(PvpMatchmaking::oldest)
                .thenComparing(team -> team.get(0).id()));
        for (List<Entry> first : candidates) {
            Set<UUID> used = ids(first);
            long firstRating = average(first);
            List<List<Entry>> opponentsByFit = candidates.stream()
                    .filter(second -> ids(second).stream().noneMatch(used::contains))
                    .sorted(Comparator.comparingLong(second ->
                            Math.abs(firstRating - average(second))))
                    .toList();
            for (List<Entry> second : opponentsByFit) {
                Entry firstSummary = new Entry(first.get(0).id(), List.of(), true,
                        oldest(first), firstRating, null, teamSize, teamSize * 2);
                Entry secondSummary = new Entry(second.get(0).id(), List.of(), true,
                        oldest(second), average(second), null, teamSize, teamSize * 2);
                if (ratingCompatible(firstSummary, secondSummary, now,
                        baseRange, widenPerSecond, maximumRange)
                        && opponents.test(Plan.members(first), Plan.members(second))) {
                    return java.util.Optional.of(new Plan(first, second));
                }
            }
        }
        return java.util.Optional.empty();
    }

    private static List<List<Entry>> candidateTeams(List<Entry> entries, int teamSize) {
        List<List<Entry>> result = new ArrayList<>();
        collectTeams(entries, teamSize, 0, new ArrayList<>(), 0, result);
        return result;
    }

    private static void collectTeams(
            List<Entry> entries,
            int teamSize,
            int start,
            List<Entry> held,
            int playerCount,
            List<List<Entry>> result
    ) {
        if (playerCount == teamSize) {
            result.add(List.copyOf(held));
            return;
        }
        // Queues are tiny in practice, but a hard ceiling prevents a maliciously
        // large waiting room turning three-player combinations into a tick spike.
        if (result.size() >= 256) return;
        for (int index = start; index < entries.size(); index++) {
            Entry candidate = entries.get(index);
            int nextCount = playerCount + candidate.members().size();
            if (nextCount > teamSize) continue;
            if (!candidate.fill() && (!held.isEmpty() || nextCount != teamSize)) continue;
            held.add(candidate);
            collectTeams(entries, teamSize, index + 1, held, nextCount, result);
            held.removeLast();
            if (result.size() >= 256) return;
        }
    }

    private static int size(List<Entry> entries) {
        return entries.stream().mapToInt(entry -> entry.members().size()).sum();
    }

    private static Set<UUID> ids(List<Entry> entries) {
        Set<UUID> ids = new LinkedHashSet<>();
        entries.forEach(entry -> ids.add(entry.id()));
        return ids;
    }

    private static long average(List<Entry> entries) {
        long players = 0L;
        long total = 0L;
        for (Entry entry : entries) {
            int count = entry.members().size();
            players += count;
            total += entry.averageRating() * count;
        }
        return players == 0L ? 0L : total / players;
    }

    private static long oldest(List<Entry> entries) {
        return entries.stream().mapToLong(Entry::joinedAt).min().orElse(0L);
    }
}
