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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * What every player has done in {@code /pvp}, kept for good.
 *
 * <p>Vanilla's {@code PLAYER_KILLS} counts anybody killed anywhere, which on this
 * server is mostly a record of who caught whom off guard rather than who can fight.
 * A duel is consensual, both sides staked something, and the arena is identical every
 * time — so this is the number worth ranking, and it is the number the Kills boards
 * now read.
 *
 * <p>Kills and wins are deliberately separate. Somebody who surrenders is beaten but
 * not killed, and a board built on kills should not reward a fight nobody finished.
 */
final class PvpRecordStore {
    /**
     * One player's duelling history.
     *
     * @param kills fights won by putting the opponent down
     * @param wins fights won by any means, surrender and disconnect included
     * @param streak wins since the last loss
     */
    record Record(
            long kills,
            long deaths,
            long wins,
            long losses,
            long draws,
            long streak,
            long bestStreak,
            long rating,
            PvpRank bestRank
    ) {
        static final Record EMPTY =
                new Record(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, PvpRank.BRONZE_I);

        PvpRank rank() {
            return PvpRank.of(rating);
        }

        Record won(boolean byKill, int ratingChange) {
            long nextStreak = streak + 1L;
            long updated = PvpRank.apply(rating, ratingChange, bestRank);
            return new Record(
                    byKill ? kills + 1L : kills, deaths, wins + 1L, losses, draws,
                    nextStreak, Math.max(bestStreak, nextStreak),
                    updated, PvpRank.higher(bestRank, PvpRank.of(updated))
            );
        }

        Record lost(boolean byDeath, int ratingChange) {
            long updated = PvpRank.apply(rating, ratingChange, bestRank);
            return new Record(
                    kills, byDeath ? deaths + 1L : deaths, wins, losses + 1L, draws,
                    0L, bestStreak, updated, bestRank
            );
        }

        Record drew(int ratingChange) {
            // A draw settles nothing, so it does not end a streak either.
            long updated = PvpRank.apply(rating, ratingChange, bestRank);
            return new Record(kills, deaths, wins, losses, draws + 1L, streak, bestStreak,
                    updated, PvpRank.higher(bestRank, PvpRank.of(updated)));
        }

        boolean isEmpty() {
            return kills == 0L && deaths == 0L && wins == 0L
                    && losses == 0L && draws == 0L && rating == 0L;
        }

        /** Wins as a share of decided fights; a draw is not a fight anybody lost. */
        double winRate() {
            long decided = wins + losses;
            return decided == 0L ? 0d : (double) wins / decided;
        }
    }

    private final Path file;
    private final Map<UUID, Record> records = new LinkedHashMap<>();
    private Runnable changeListener = () -> { };

    PvpRecordStore(Path file) throws IOException {
        this.file = file;
        Files.createDirectories(file.getParent());
        if (!Files.isRegularFile(file) || Files.size(file) == 0L) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                JsonObject value = entry.getValue().getAsJsonObject();
                records.put(UUID.fromString(entry.getKey()), new Record(
                        value.get("kills").getAsLong(),
                        value.get("deaths").getAsLong(),
                        value.get("wins").getAsLong(),
                        value.get("losses").getAsLong(),
                        value.has("draws") ? value.get("draws").getAsLong() : 0L,
                        value.has("streak") ? value.get("streak").getAsLong() : 0L,
                        value.has("best_streak") ? value.get("best_streak").getAsLong() : 0L,
                        value.has("rating") ? value.get("rating").getAsLong() : 0L,
                        readRank(value)
                ));
            }
        } catch (RuntimeException exception) {
            throw new IOException("PvP record store is unreadable", exception);
        }
    }

    /** An unknown rank name is a downgrade, not a crash: the floor is always safe. */
    private static PvpRank readRank(JsonObject value) {
        if (!value.has("best_rank")) {
            return PvpRank.BRONZE_I;
        }
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

    /** What one fight did to one player's standing, for the screen that reports it. */
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

    /**
     * Both halves of one fight, written together so a board can never see half of it.
     *
     * <p>Both ratings are read before either is written, so the pair is scored against
     * each other rather than the winner being rated against an opponent who has
     * already lost points to them.
     */
    synchronized Map<UUID, RatingChange> settle(
            UUID winnerId, UUID loserId, boolean byKill
    ) {
        Map<UUID, Record> before = new LinkedHashMap<>(records);
        Record winner = of(winnerId);
        Record loser = of(loserId);
        int winnerChange = PvpRank.change(winner.rating(), loser.rating(), 1d);
        int loserChange = PvpRank.change(loser.rating(), winner.rating(), 0d);
        records.put(winnerId, winner.won(byKill, winnerChange));
        records.put(loserId, loser.lost(byKill, loserChange));
        commit(before);
        return Map.of(
                winnerId, changeOf(winner, records.get(winnerId)),
                loserId, changeOf(loser, records.get(loserId))
        );
    }

    synchronized Map<UUID, RatingChange> drew(UUID first, UUID second) {
        Map<UUID, Record> before = new LinkedHashMap<>(records);
        Record one = of(first);
        Record two = of(second);
        int oneChange = PvpRank.change(one.rating(), two.rating(), 0.5d);
        int twoChange = PvpRank.change(two.rating(), one.rating(), 0.5d);
        records.put(first, one.drew(oneChange));
        records.put(second, two.drew(twoChange));
        commit(before);
        return Map.of(
                first, changeOf(one, records.get(first)),
                second, changeOf(two, records.get(second))
        );
    }

    private static RatingChange changeOf(Record before, Record after) {
        return new RatingChange(before.rating(), after.rating(),
                before.rank(), after.rank());
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
            if (record.isEmpty()) {
                return;
            }
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
