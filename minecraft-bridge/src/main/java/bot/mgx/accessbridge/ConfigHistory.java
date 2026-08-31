package bot.mgx.accessbridge;

import com.google.gson.JsonArray;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What each publish changed, so a change can be read back and undone.
 *
 * <p>The audit trail records that a value moved and what it became. That is enough to
 * review a decision and not enough to reverse one — restoring a rebalance by hand means
 * remembering every number it touched. This keeps the values a publish replaced, which
 * is the whole of what rollback needs.
 *
 * <p>Kept beside the overrides rather than inside them: the override file is a flat
 * key-to-value map that the store rewrites wholesale on every save, and history is
 * neither flat nor rewritten. Bounded, because this is an operations aid and not an
 * archive — the server should not carry an unbounded file it never prunes.
 */
final class ConfigHistory {
    /** Publishes kept before the oldest is dropped. */
    static final int RETAINED_PUBLISHES = 50;

    /** One publish: every key it moved, and what each was before. */
    record Publish(String id, long at, String actor, List<GameVariableStore.Change> changes) { }

    private final Path file;
    private final Deque<Publish> publishes = new ArrayDeque<>();

    ConfigHistory(Path file) throws IOException {
        this.file = file;
        Files.createDirectories(file.getParent());
        load();
    }

    /** Records a publish. Newest first, so the panel reads it in the order it shows it. */
    synchronized Publish record(String actor, List<GameVariableStore.Change> changes) {
        Publish publish = new Publish(
                UUID.randomUUID().toString(), System.currentTimeMillis(),
                actor == null ? "" : actor, List.copyOf(changes)
        );
        publishes.addFirst(publish);
        while (publishes.size() > RETAINED_PUBLISHES) {
            publishes.removeLast();
        }
        save();
        return publish;
    }

    synchronized List<Publish> recent(int limit) {
        return publishes.stream().limit(Math.max(0, limit)).toList();
    }

    synchronized Optional<List<GameVariableStore.Change>> changesOf(String publishId) {
        return publishes.stream()
                .filter(publish -> publish.id().equals(publishId))
                .findFirst()
                .map(Publish::changes);
    }

    /** The trail as the panel renders it: newest first, each change with both values. */
    synchronized JsonArray snapshot(int limit) {
        JsonArray entries = new JsonArray();
        for (Publish publish : recent(limit)) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", publish.id());
            entry.addProperty("at", publish.at());
            entry.addProperty("actor", publish.actor());
            JsonArray changes = new JsonArray();
            for (GameVariableStore.Change change : publish.changes()) {
                JsonObject row = new JsonObject();
                row.addProperty("key", change.key());
                addValue(row, "before", change.before());
                addValue(row, "after", change.after());
                changes.add(row);
            }
            entry.add("changes", changes);
            entry.addProperty("change_count", publish.changes().size());
            entries.add(entry);
        }
        return entries;
    }

    private static void addValue(JsonObject object, String name, Object value) {
        if (value instanceof Boolean flag) {
            object.addProperty(name, flag);
        } else {
            object.addProperty(name, ((Number) value).longValue());
        }
    }

    private void load() throws IOException {
        if (!Files.isRegularFile(file) || Files.size(file) == 0) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            for (JsonElement element : root.getAsJsonArray("publishes")) {
                JsonObject entry = element.getAsJsonObject();
                List<GameVariableStore.Change> changes = new ArrayList<>();
                for (JsonElement raw : entry.getAsJsonArray("changes")) {
                    JsonObject row = raw.getAsJsonObject();
                    changes.add(new GameVariableStore.Change(
                            row.get("key").getAsString(),
                            readValue(row.get("before")),
                            readValue(row.get("after"))
                    ));
                }
                publishes.addLast(new Publish(
                        entry.get("id").getAsString(),
                        entry.get("at").getAsLong(),
                        entry.has("actor") ? entry.get("actor").getAsString() : "",
                        List.copyOf(changes)
                ));
            }
        } catch (RuntimeException malformed) {
            // An unreadable trail must not stop the server configuring itself. Losing the
            // ability to undo is recoverable; refusing to start over it is not.
            publishes.clear();
        }
    }

    private static Object readValue(JsonElement element) {
        return element.getAsJsonPrimitive().isBoolean()
                ? element.getAsBoolean()
                : element.getAsLong();
    }

    private void save() {
        JsonObject root = new JsonObject();
        root.add("publishes", snapshot(RETAINED_PUBLISHES));
        try {
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, root.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
