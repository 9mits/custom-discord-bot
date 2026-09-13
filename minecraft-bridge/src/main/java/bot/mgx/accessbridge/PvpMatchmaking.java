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

    /** When the configured minimum compatible FFA population first became ready. */
    static java.util.OptionalLong ffaReadyAt(
            List<Entry> source, int teamSize, int targetPlayers, int minimumPlayers
    ) {
        int minimum = Math.max(2, Math.min(targetPlayers, minimumPlayers));
        int found = 0;
        List<Entry> compatible = source.stream()
                .filter(entry -> entry.teamSize() == teamSize
                        && entry.targetPlayers() == targetPlayers)
                .sorted(Comparator.comparingLong(Entry::joinedAt))
                .toList();
        for (Entry entry : compatible) {
            found += entry.members().size();
            if (found >= minimum) return java.util.OptionalLong.of(entry.joinedAt());
        }
        return java.util.OptionalLong.empty();
    }

    static java.util.Optional<Plan> teams(List<Entry> source, int teamSize) {
        return teams(source, teamSize, (first, second) -> true);
    }

    static java.util.Optional<Plan> teams(
            List<Entry> source,
            int teamSize,
            BiPredicate<List<UUID>, List<UUID>> opponents
    ) {
        if (teamSize <= 0) return java.util.Optional.empty();
        List<Entry> entries = source.stream()
                .sorted(Comparator.comparingLong(Entry::joinedAt).thenComparing(Entry::id))
                .toList();
        List<List<Entry>> candidates = candidateTeams(entries, teamSize);
        candidates.sort(Comparator.comparingLong(PvpMatchmaking::oldest)
                .thenComparing(team -> team.get(0).id()));
        if (candidates.isEmpty()) return java.util.Optional.empty();
        Entry oldestQueued = entries.get(0);
        Plan best = null;
        long bestRatingDifference = Long.MAX_VALUE;
        for (List<Entry> first : candidates) {
            Set<UUID> used = ids(first);
            long firstRating = average(first);
            List<List<Entry>> opponentsByFit = candidates.stream()
                    .filter(second -> ids(second).stream().noneMatch(used::contains))
                    .sorted(Comparator.comparingLong(second ->
                            Math.abs(firstRating - average(second))))
                    .toList();
            for (List<Entry> second : opponentsByFit) {
                // Ratings choose the fairest split among players who are already here;
                // they never prevent a match. Requiring the oldest entry in the chosen
                // plan prevents repeated arrivals from starving somebody already waiting.
                if (!first.contains(oldestQueued) && !second.contains(oldestQueued)) continue;
                if (!opponents.test(Plan.members(first), Plan.members(second))) continue;
                long secondRating = average(second);
                long difference = firstRating >= secondRating
                        ? firstRating - secondRating : secondRating - firstRating;
                if (difference < bestRatingDifference) {
                    best = new Plan(first, second);
                    bestRatingDifference = difference;
                }
            }
        }
        return java.util.Optional.ofNullable(best);
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
