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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Last known wealth per player, persisted to disk.
 *
 * <p>Wealth is the only leaderboard figure the server does not record itself: it is
 * measured while a player is online. Keeping it in memory alone meant every plugin
 * restart zeroed everyone, emptying the default board until people logged back in.
 */
final class WealthStore {
    private final Path file;
    private final ConcurrentHashMap<UUID, Long> wealth = new ConcurrentHashMap<>();
    private volatile boolean dirty;

    WealthStore(Path file) throws IOException {
        this.file = file;
        Files.createDirectories(file.getParent());
        if (!Files.isRegularFile(file) || Files.size(file) == 0) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                wealth.put(UUID.fromString(entry.getKey()), entry.getValue().getAsLong());
            }
        } catch (RuntimeException exception) {
            throw new IOException("Wealth store is unreadable", exception);
        }
    }

    Map<UUID, Long> snapshots() {
        return wealth;
    }

    void record(UUID playerId, long value) {
        Long previous = wealth.put(playerId, Math.max(0L, value));
        if (previous == null || previous != value) {
            dirty = true;
        }
    }

    /** Writes only when something changed, since this runs on every publish. */
    void saveIfChanged() {
        if (!dirty) {
            return;
        }
        dirty = false;
        JsonObject root = new JsonObject();
        wealth.forEach((playerId, value) -> root.addProperty(playerId.toString(), value));
        try {
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, root.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(
                        temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            dirty = true;
            throw new UncheckedIOException(exception);
        }
    }
}
