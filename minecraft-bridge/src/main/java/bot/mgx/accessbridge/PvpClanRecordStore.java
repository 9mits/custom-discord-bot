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
import java.util.UUID;

/** Small, independent Clan-v-Clan record; clan membership itself remains in {@link ClanStore}. */
final class PvpClanRecordStore {
    record Record(long wins, long losses, long kills, long streak, long bestStreak) {
        static final Record EMPTY = new Record(0L, 0L, 0L, 0L, 0L);

        Record won(int matchKills) {
            long next = streak + 1L;
            return new Record(wins + 1L, losses, kills + Math.max(0, matchKills), next,
                    Math.max(bestStreak, next));
        }

        Record lost(int matchKills) {
            return new Record(wins, losses + 1L, kills + Math.max(0, matchKills), 0L,
                    bestStreak);
        }
    }

    private final Path file;
    private final Map<UUID, Record> records = new LinkedHashMap<>();

    PvpClanRecordStore(Path file) throws IOException {
        this.file = file;
        Files.createDirectories(file.getParent());
        if (!Files.isRegularFile(file) || Files.size(file) == 0L) return;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> row : root.entrySet()) {
                JsonObject value = row.getValue().getAsJsonObject();
                records.put(UUID.fromString(row.getKey()), new Record(
                        value.get("wins").getAsLong(),
                        value.get("losses").getAsLong(),
                        value.has("kills") ? value.get("kills").getAsLong() : 0L,
                        value.has("streak") ? value.get("streak").getAsLong() : 0L,
                        value.has("best_streak") ? value.get("best_streak").getAsLong() : 0L
                ));
            }
        } catch (RuntimeException malformed) {
            throw new IOException("PvP clan records are unreadable", malformed);
        }
    }

    synchronized Record of(UUID clanId) {
        return records.getOrDefault(clanId, Record.EMPTY);
    }

    synchronized Map<UUID, Record> all() {
        return Map.copyOf(records);
    }

    synchronized void settle(UUID winner, UUID loser, int winnerKills, int loserKills) {
        Map<UUID, Record> before = new LinkedHashMap<>(records);
        records.put(winner, of(winner).won(winnerKills));
        records.put(loser, of(loser).lost(loserKills));
        try {
            save();
        } catch (RuntimeException failure) {
            records.clear();
            records.putAll(before);
            throw failure;
        }
    }

    private void save() {
        JsonObject root = new JsonObject();
        records.forEach((id, record) -> {
            JsonObject value = new JsonObject();
            value.addProperty("wins", record.wins());
            value.addProperty("losses", record.losses());
            value.addProperty("kills", record.kills());
            value.addProperty("streak", record.streak());
            value.addProperty("best_streak", record.bestStreak());
            root.add(id.toString(), value);
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
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not save PvP clan records", failure);
        }
    }
}
