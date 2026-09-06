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
            long bestStreak
    ) {
        static final Record EMPTY = new Record(0L, 0L, 0L, 0L, 0L, 0L, 0L);

        Record won(boolean byKill) {
            long nextStreak = streak + 1L;
            return new Record(
                    byKill ? kills + 1L : kills, deaths, wins + 1L, losses, draws,
                    nextStreak, Math.max(bestStreak, nextStreak)
            );
        }

        Record lost(boolean byDeath) {
            return new Record(
                    kills, byDeath ? deaths + 1L : deaths, wins, losses + 1L, draws,
                    0L, bestStreak
            );
        }

        Record drew() {
            // A draw settles nothing, so it does not end a streak either.
            return new Record(kills, deaths, wins, losses, draws + 1L, streak, bestStreak);
        }

        boolean isEmpty() {
            return kills == 0L && deaths == 0L && wins == 0L
                    && losses == 0L && draws == 0L;
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
                        value.has("best_streak") ? value.get("best_streak").getAsLong() : 0L
                ));
            }
        } catch (RuntimeException exception) {
            throw new IOException("PvP record store is unreadable", exception);
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

    /** Both halves of one fight, written together so a board can never see half of it. */
    synchronized void settle(UUID winnerId, UUID loserId, boolean byKill) {
        Map<UUID, Record> before = new LinkedHashMap<>(records);
        records.put(winnerId, of(winnerId).won(byKill));
        records.put(loserId, of(loserId).lost(byKill));
        commit(before);
    }

    synchronized void drew(UUID first, UUID second) {
        Map<UUID, Record> before = new LinkedHashMap<>(records);
        records.put(first, of(first).drew());
        records.put(second, of(second).drew());
        commit(before);
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
