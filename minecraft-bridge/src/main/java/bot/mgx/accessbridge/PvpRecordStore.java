package bot.mgx.accessbridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Durable aggregate and mode-specific competitive PvP statistics. */
final class PvpRecordStore {
    record ModeRecord(
            long matches,
            long wins,
            long losses,
            long draws,
            long kills,
            long deaths,
            long streak,
            long bestStreak
    ) {
        static final ModeRecord EMPTY = new ModeRecord(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L);

        ModeRecord won(int matchKills) {
            long next = streak + 1L;
            return new ModeRecord(matches + 1L, wins + 1L, losses, draws,
                    kills + Math.max(0, matchKills), deaths, next, Math.max(bestStreak, next));
        }

        ModeRecord lost(boolean died, int matchKills) {
            return new ModeRecord(matches + 1L, wins, losses + 1L, draws,
                    kills + Math.max(0, matchKills), deaths + (died ? 1L : 0L), 0L, bestStreak);
        }

        ModeRecord drew(int matchKills, boolean died) {
            return new ModeRecord(matches + 1L, wins, losses, draws + 1L,
                    kills + Math.max(0, matchKills), deaths + (died ? 1L : 0L),
                    streak, bestStreak);
        }

        double winRate() {
            long decided = wins + losses;
            return decided == 0L ? 0d : (double) wins / decided;
        }
    }

    record Record(
            long kills,
            long deaths,
            long wins,
            long losses,
            long draws,
            long streak,
            long bestStreak,
            long rating,
            PvpRank bestRank,
            Map<String, ModeRecord> modes
    ) {
        static final Record EMPTY = new Record(
                0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, PvpRank.BRONZE_I, Map.of());

        Record(
                long kills, long deaths, long wins, long losses, long draws,
                long streak, long bestStreak, long rating, PvpRank bestRank
        ) {
            this(kills, deaths, wins, losses, draws, streak, bestStreak,
                    rating, bestRank, Map.of());
        }

        Record {
            modes = modes == null ? Map.of() : Map.copyOf(modes);
        }

        PvpRank rank() {
            return PvpRank.of(rating);
        }

        ModeRecord mode(PvpMode mode) {
            return modes.getOrDefault(mode.key(), ModeRecord.EMPTY);
        }

        long matchesPlayed() {
            return wins + losses + draws;
        }

        /**
         * Matches that may place a player on the rating board. Records written before
         * mode statistics existed are treated as legacy ranked records, while new
         * private/casual fights explicitly carry their mode and cannot award a Scythe.
         */
        long rankedMatches() {
            if (modes.isEmpty()) return matchesPlayed();
            return mode(PvpMode.RANKED_DUEL).matches()
                    + mode(PvpMode.DOUBLES).matches()
                    + mode(PvpMode.TRIPLES).matches();
        }

        long rankedWins() {
            if (modes.isEmpty()) return wins;
            return mode(PvpMode.RANKED_DUEL).wins()
                    + mode(PvpMode.DOUBLES).wins()
                    + mode(PvpMode.TRIPLES).wins();
        }

        long rankedLosses() {
            if (modes.isEmpty()) return losses;
            return mode(PvpMode.RANKED_DUEL).losses()
                    + mode(PvpMode.DOUBLES).losses()
                    + mode(PvpMode.TRIPLES).losses();
        }

        long rankedDraws() {
            if (modes.isEmpty()) return draws;
            return mode(PvpMode.RANKED_DUEL).draws()
                    + mode(PvpMode.DOUBLES).draws()
                    + mode(PvpMode.TRIPLES).draws();
        }

        long rankedKills() {
            if (modes.isEmpty()) return kills;
            return mode(PvpMode.RANKED_DUEL).kills()
                    + mode(PvpMode.DOUBLES).kills()
                    + mode(PvpMode.TRIPLES).kills();
        }

        double rankedWinRate() {
            long decided = rankedWins() + rankedLosses();
            return decided == 0L ? 0d : (double) rankedWins() / decided;
        }

        Record won(PvpMode mode, int matchKills, int ratingChange, boolean rated) {
            long nextStreak = streak + 1L;
            long updated = rated ? PvpRank.apply(rating, ratingChange, bestRank) : rating;
            Map<String, ModeRecord> nextModes = new LinkedHashMap<>(modes);
            nextModes.put(mode.key(), mode(mode).won(matchKills));
            return new Record(
                    kills + Math.max(0, matchKills), deaths, wins + 1L, losses, draws,
                    nextStreak, Math.max(bestStreak, nextStreak), updated,
                    PvpRank.higher(bestRank, PvpRank.of(updated)), nextModes
            );
        }

        Record lost(PvpMode mode, boolean died, int matchKills, int ratingChange, boolean rated) {
            long updated = rated ? PvpRank.apply(rating, ratingChange, bestRank) : rating;
            Map<String, ModeRecord> nextModes = new LinkedHashMap<>(modes);
            nextModes.put(mode.key(), mode(mode).lost(died, matchKills));
            return new Record(
                    kills + Math.max(0, matchKills), deaths + (died ? 1L : 0L),
                    wins, losses + 1L, draws, 0L, bestStreak,
                    updated, bestRank, nextModes
            );
        }

        Record drew(PvpMode mode, int matchKills, boolean died, int ratingChange, boolean rated) {
            long updated = rated ? PvpRank.apply(rating, ratingChange, bestRank) : rating;
            Map<String, ModeRecord> nextModes = new LinkedHashMap<>(modes);
            nextModes.put(mode.key(), mode(mode).drew(matchKills, died));
            return new Record(kills + Math.max(0, matchKills), deaths + (died ? 1L : 0L),
                    wins, losses, draws + 1L, streak, bestStreak, updated,
                    PvpRank.higher(bestRank, PvpRank.of(updated)), nextModes);
        }

        boolean isEmpty() {
            return kills == 0L && deaths == 0L && wins == 0L
                    && losses == 0L && draws == 0L && rating == 0L;
        }

        double winRate() {
            long decided = wins + losses;
            return decided == 0L ? 0d : (double) wins / decided;
        }
    }

    record RatingChange(long before, long after, PvpRank rankBefore, PvpRank rankAfter) {
        int delta() {
            return (int) (after - before);
        }

        boolean promoted() {
            return rankAfter.ordinal() > rankBefore.ordinal();
        }

        boolean demoted() {
            return rankAfter.ordinal() < rankBefore.ordinal();
        }
    }

    private final Path file;
    private final Map<UUID, Record> records = new LinkedHashMap<>();
    private Runnable changeListener = () -> { };

    PvpRecordStore(Path file) throws IOException {
        this.file = file;
        Files.createDirectories(file.getParent());
        if (!Files.isRegularFile(file) || Files.size(file) == 0L) return;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                records.put(UUID.fromString(entry.getKey()), readRecord(entry.getValue().getAsJsonObject()));
            }
        } catch (RuntimeException exception) {
            throw new IOException("PvP record store is unreadable", exception);
        }
    }

    private static Record readRecord(JsonObject value) {
        Map<String, ModeRecord> modes = new LinkedHashMap<>();
        if (value.has("modes") && value.get("modes").isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject("modes").entrySet()) {
                if (!entry.getValue().isJsonObject()) continue;
                JsonObject row = entry.getValue().getAsJsonObject();
                modes.put(entry.getKey(), new ModeRecord(
                        number(row, "matches"), number(row, "wins"), number(row, "losses"),
                        number(row, "draws"), number(row, "kills"), number(row, "deaths"),
                        number(row, "streak"), number(row, "best_streak")
                ));
            }
        }
        long kills = number(value, "kills");
        long deaths = number(value, "deaths");
        long wins = number(value, "wins");
        long losses = number(value, "losses");
        long draws = number(value, "draws");
        long streak = number(value, "streak");
        long bestStreak = number(value, "best_streak");
        // Before mode records existed, every accepted duel changed Elo. Preserve
        // those standings as legacy ranked 1v1 history so playing one new private
        // match cannot make a long-held rank or Scythe disappear.
        if (modes.isEmpty() && wins + losses + draws > 0L) {
            modes.put(PvpMode.RANKED_DUEL.key(), new ModeRecord(
                    wins + losses + draws, wins, losses, draws, kills, deaths,
                    streak, bestStreak));
        }
        return new Record(
                kills, deaths, wins, losses, draws, streak, bestStreak,
                number(value, "rating"), readRank(value), modes
        );
    }

    private static long number(JsonObject value, String key) {
        return value.has(key) ? value.get(key).getAsLong() : 0L;
    }

    private static PvpRank readRank(JsonObject value) {
        if (!value.has("best_rank")) return PvpRank.BRONZE_I;
        try {
            return PvpRank.valueOf(value.get("best_rank").getAsString());
        } catch (IllegalArgumentException unknown) {
            return PvpRank.BRONZE_I;
        }
    }

    void onChange(Runnable listener) {
        changeListener = Objects.requireNonNull(listener, "listener");
    }

    synchronized Record of(UUID playerId) {
        return records.getOrDefault(playerId, Record.EMPTY);
    }

    synchronized Map<UUID, Record> all() {
        return Map.copyOf(records);
    }

    /** Compatibility path for historical callers: a rated private 1v1. */
    synchronized Map<UUID, RatingChange> settle(UUID winnerId, UUID loserId, boolean byKill) {
        return settleMatch(PvpMode.RANKED_DUEL, List.of(winnerId), List.of(loserId),
                byKill ? Map.of(winnerId, 1) : Map.of(),
                byKill ? Set.of(loserId) : Set.of(), true);
    }

    synchronized Map<UUID, RatingChange> settleCasual(
            UUID winnerId, UUID loserId, boolean byKill
    ) {
        return settleMatch(PvpMode.PRIVATE_DUEL, List.of(winnerId), List.of(loserId),
                byKill ? Map.of(winnerId, 1) : Map.of(),
                byKill ? Set.of(loserId) : Set.of(), false);
    }

    synchronized Map<UUID, RatingChange> drew(UUID first, UUID second) {
        return drawMatch(PvpMode.RANKED_DUEL, List.of(first, second), Map.of(), Set.of(), true);
    }

    synchronized Map<UUID, RatingChange> drewCasual(UUID first, UUID second) {
        return drawMatch(PvpMode.PRIVATE_DUEL, List.of(first, second), Map.of(), Set.of(), false);
    }

    synchronized Map<UUID, RatingChange> settleMatch(
            PvpMode mode,
            List<UUID> winners,
            List<UUID> losers,
            Map<UUID, Integer> kills,
            Set<UUID> deaths,
            boolean rated
    ) {
        requireSides(winners, losers);
        Map<UUID, Record> before = new LinkedHashMap<>(records);
        long winnerAverage = averageRating(winners);
        long loserAverage = averageRating(losers);
        Map<UUID, RatingChange> changes = new LinkedHashMap<>();
        for (UUID winner : winners) {
            Record prior = of(winner);
            int delta = rated ? PvpRank.change(prior.rating(), loserAverage, 1d) : 0;
            Record after = prior.won(mode, kills.getOrDefault(winner, 0), delta, rated);
            records.put(winner, after);
            changes.put(winner, changeOf(prior, after));
        }
        for (UUID loser : losers) {
            Record prior = of(loser);
            int delta = rated ? PvpRank.change(prior.rating(), winnerAverage, 0d) : 0;
            Record after = prior.lost(mode, deaths.contains(loser),
                    kills.getOrDefault(loser, 0), delta, rated);
            records.put(loser, after);
            changes.put(loser, changeOf(prior, after));
        }
        commit(before);
        return Map.copyOf(changes);
    }

    synchronized Map<UUID, RatingChange> drawMatch(
            PvpMode mode,
            List<UUID> players,
            Map<UUID, Integer> kills,
            Set<UUID> deaths,
            boolean rated
    ) {
        if (players == null || players.isEmpty()) return Map.of();
        List<UUID> unique = new ArrayList<>(new LinkedHashSet<>(players));
        Map<UUID, Record> before = new LinkedHashMap<>(records);
        Map<UUID, RatingChange> changes = new LinkedHashMap<>();
        long average = averageRating(unique);
        for (UUID player : unique) {
            Record prior = of(player);
            int delta = rated ? PvpRank.change(prior.rating(), average, 0.5d) : 0;
            Record after = prior.drew(mode, kills.getOrDefault(player, 0),
                    deaths.contains(player), delta, rated);
            records.put(player, after);
            changes.put(player, changeOf(prior, after));
        }
        commit(before);
        return Map.copyOf(changes);
    }

    private static void requireSides(List<UUID> winners, List<UUID> losers) {
        if (winners == null || losers == null || winners.isEmpty() || losers.isEmpty()) {
            throw new IllegalArgumentException("A decided match needs both sides.");
        }
        Set<UUID> all = new LinkedHashSet<>();
        for (UUID player : winners) {
            if (player == null || !all.add(player)) {
                throw new IllegalArgumentException("Duplicate PvP player.");
            }
        }
        for (UUID player : losers) {
            if (player == null || !all.add(player)) {
                throw new IllegalArgumentException("PvP sides overlap.");
            }
        }
    }

    private long averageRating(List<UUID> players) {
        if (players.isEmpty()) return 0L;
        long total = 0L;
        for (UUID player : players) total += of(player).rating();
        return total / players.size();
    }

    private static RatingChange changeOf(Record before, Record after) {
        return new RatingChange(before.rating(), after.rating(), before.rank(), after.rank());
    }

    private void commit(Map<UUID, Record> before) {
        try {
            save();
        } catch (RuntimeException failure) {
            records.clear();
            records.putAll(before);
            throw failure;
        }
        changeListener.run();
    }

    private void save() {
        JsonObject root = new JsonObject();
        records.forEach((playerId, record) -> {
            if (record.isEmpty()) return;
            JsonObject value = new JsonObject();
            value.addProperty("kills", record.kills());
            value.addProperty("deaths", record.deaths());
            value.addProperty("wins", record.wins());
            value.addProperty("losses", record.losses());
            value.addProperty("draws", record.draws());
            value.addProperty("streak", record.streak());
            value.addProperty("best_streak", record.bestStreak());
            value.addProperty("rating", record.rating());
            value.addProperty("best_rank", record.bestRank().name());
            JsonObject modes = new JsonObject();
            record.modes().forEach((key, row) -> {
                JsonObject mode = new JsonObject();
                mode.addProperty("matches", row.matches());
                mode.addProperty("wins", row.wins());
                mode.addProperty("losses", row.losses());
                mode.addProperty("draws", row.draws());
                mode.addProperty("kills", row.kills());
                mode.addProperty("deaths", row.deaths());
                mode.addProperty("streak", row.streak());
                mode.addProperty("best_streak", row.bestStreak());
                modes.add(key, mode);
            });
            value.add("modes", modes);
            root.add(playerId.toString(), value);
        });
        try {
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, root.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException fallback) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not save PvP records", exception);
        }
    }
}
