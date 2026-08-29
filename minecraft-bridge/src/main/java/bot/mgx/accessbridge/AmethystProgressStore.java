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

/** Persistent Amethyst Event counters used by the two event leaderboards. */
final class AmethystProgressStore {
    record Counts(long cratesOpened, long airdropsOpened) {
        Counts {
            cratesOpened = Math.max(0L, cratesOpened);
            airdropsOpened = Math.max(0L, airdropsOpened);
        }

        boolean empty() {
            return cratesOpened == 0L && airdropsOpened == 0L;
        }
    }

    private final Path file;
    private final LinkedHashMap<UUID, Counts> counts = new LinkedHashMap<>();
    private Runnable observer = () -> { };

    AmethystProgressStore(Path file) throws IOException {
        this.file = file;
        Files.createDirectories(file.getParent());
        if (!Files.isRegularFile(file) || Files.size(file) == 0L) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                JsonObject value = entry.getValue().getAsJsonObject();
                Counts loaded = new Counts(
                        number(value, "amethyst_crates_opened"),
                        number(value, "amethyst_airdrops_opened")
                );
                if (!loaded.empty()) {
                    counts.put(UUID.fromString(entry.getKey()), loaded);
                }
            }
        } catch (RuntimeException exception) {
            throw new IOException("Amethyst progress store is unreadable", exception);
        }
    }

    synchronized void onChange(Runnable observer) {
        this.observer = observer == null ? () -> { } : observer;
    }

    synchronized Counts counts(UUID playerId) {
        return counts.getOrDefault(playerId, new Counts(0L, 0L));
    }

    synchronized Map<UUID, Counts> snapshots() {
        return Map.copyOf(counts);
    }

    synchronized long recordCratesOpened(UUID playerId, int amount) {
        if (amount <= 0) {
            return counts(playerId).cratesOpened();
        }
        Counts before = counts(playerId);
        Counts after = new Counts(
                Math.addExact(before.cratesOpened(), amount), before.airdropsOpened()
        );
        update(playerId, before, after);
        return after.cratesOpened();
    }

    synchronized long recordAirdropOpened(UUID playerId) {
        Counts before = counts(playerId);
        Counts after = new Counts(
                before.cratesOpened(), Math.addExact(before.airdropsOpened(), 1L)
        );
        update(playerId, before, after);
        return after.airdropsOpened();
    }

    synchronized int clearAll() {
        int cleared = counts.size();
        if (cleared == 0) {
            return 0;
        }
        LinkedHashMap<UUID, Counts> before = new LinkedHashMap<>(counts);
        counts.clear();
        try {
            save();
        } catch (RuntimeException exception) {
            counts.putAll(before);
            throw exception;
        }
        observer.run();
        return cleared;
    }

    private void update(UUID playerId, Counts before, Counts after) {
        counts.put(playerId, after);
        try {
            save();
        } catch (RuntimeException exception) {
            if (before.empty()) {
                counts.remove(playerId);
            } else {
                counts.put(playerId, before);
            }
            throw exception;
        }
        observer.run();
    }

    private void save() {
        JsonObject root = new JsonObject();
        counts.forEach((playerId, progress) -> {
            JsonObject value = new JsonObject();
            value.addProperty("amethyst_crates_opened", progress.cratesOpened());
            value.addProperty("amethyst_airdrops_opened", progress.airdropsOpened());
            root.add(playerId.toString(), value);
        });
        try {
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, root.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(
                        temporary, file,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static long number(JsonObject value, String key) {
        return value.has(key) ? Math.max(0L, value.get(key).getAsLong()) : 0L;
    }
}
