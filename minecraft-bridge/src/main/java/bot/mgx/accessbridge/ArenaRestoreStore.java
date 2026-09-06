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
import java.util.Optional;
import java.util.UUID;

/**
 * Every block a duel changed, and what it was before.
 *
 * <p>Fighters may dig, and the terrain they dig is real world nobody has visited.
 * The promise is that the world is put back, which means the promise has to survive
 * a crash: an arena left scarred sits 2,000 to 90,000 blocks out where nothing will
 * ever go looking for it, so an in-memory-only record is a permanent scar the first
 * time Paper stops mid-fight.
 *
 * <p>Only the <em>first</em> state seen at a position is kept. A block broken, then
 * replaced, then broken again restores to what the world generated, and recording it
 * that way makes the replay order-independent — which matters, because a revert that
 * depends on replaying edits backwards is a revert that can be got wrong.
 *
 * <p>Writes are buffered and flushed by the caller rather than on every block, so a
 * player mining through a hill does not force a file rewrite per swing.
 */
final class ArenaRestoreStore {
    /**
     * One position's original contents.
     *
     * <p>{@code contents} is non-empty only for a container, whose items live in the
     * tile entity rather than in the block data.
     */
    record Snapshot(int x, int y, int z, String blockData, String contents) {
    }

    /** Everything one duel changed, in the world it changed it. */
    record ArenaEdits(
            UUID duelId,
            UUID worldId,
            String worldName,
            Map<String, Snapshot> blocks
    ) {
        ArenaEdits {
            blocks = new LinkedHashMap<>(blocks);
        }
    }

    static String key(int x, int y, int z) {
        return x + "," + y + "," + z;
    }

    private final Path file;
    private final Map<UUID, ArenaEdits> edits = new LinkedHashMap<>();
    private boolean dirty;

    ArenaRestoreStore(Path file) throws IOException {
        this.file = file;
        Files.createDirectories(file.getParent());
        if (!Files.isRegularFile(file) || Files.size(file) == 0L) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                JsonObject value = entry.getValue().getAsJsonObject();
                edits.put(UUID.fromString(entry.getKey()), read(
                        UUID.fromString(entry.getKey()), value));
            }
        } catch (RuntimeException exception) {
            throw new IOException("PvP arena restore store is unreadable", exception);
        }
    }

    synchronized Map<UUID, ArenaEdits> all() {
        return Map.copyOf(edits);
    }

    synchronized Optional<ArenaEdits> find(UUID duelId) {
        return Optional.ofNullable(edits.get(duelId));
    }

    synchronized int size(UUID duelId) {
        ArenaEdits row = edits.get(duelId);
        return row == null ? 0 : row.blocks().size();
    }

    /** Records a position's original state, keeping whatever was seen there first. */
    synchronized void remember(
            UUID duelId, UUID worldId, String worldName, Snapshot snapshot
    ) {
        ArenaEdits row = edits.computeIfAbsent(duelId, ignored ->
                new ArenaEdits(duelId, worldId, worldName, new LinkedHashMap<>()));
        if (row.blocks().putIfAbsent(
                key(snapshot.x(), snapshot.y(), snapshot.z()), snapshot) == null) {
            dirty = true;
        }
    }

    synchronized void forget(UUID duelId) {
        if (edits.remove(duelId) != null) {
            dirty = true;
        }
    }

    /** Writes only when something changed, so an idle flush loop costs nothing. */
    synchronized boolean flush() {
        if (!dirty) {
            return false;
        }
        save();
        dirty = false;
        return true;
    }

    private ArenaEdits read(UUID duelId, JsonObject value) {
        Map<String, Snapshot> blocks = new LinkedHashMap<>();
        JsonObject stored = value.getAsJsonObject("blocks");
        for (Map.Entry<String, JsonElement> entry : stored.entrySet()) {
            JsonObject block = entry.getValue().getAsJsonObject();
            blocks.put(entry.getKey(), new Snapshot(
                    block.get("x").getAsInt(),
                    block.get("y").getAsInt(),
                    block.get("z").getAsInt(),
                    block.get("data").getAsString(),
                    block.has("contents") ? block.get("contents").getAsString() : ""
            ));
        }
        return new ArenaEdits(
                duelId,
                UUID.fromString(value.get("world_id").getAsString()),
                value.get("world_name").getAsString(),
                blocks
        );
    }

    private void save() {
        JsonObject root = new JsonObject();
        edits.forEach((duelId, row) -> {
            JsonObject value = new JsonObject();
            value.addProperty("world_id", row.worldId().toString());
            value.addProperty("world_name", row.worldName());
            JsonObject blocks = new JsonObject();
            row.blocks().forEach((position, snapshot) -> {
                JsonObject block = new JsonObject();
                block.addProperty("x", snapshot.x());
                block.addProperty("y", snapshot.y());
                block.addProperty("z", snapshot.z());
                block.addProperty("data", snapshot.blockData());
                if (!snapshot.contents().isEmpty()) {
                    block.addProperty("contents", snapshot.contents());
                }
                blocks.add(position, block);
            });
            value.add("blocks", blocks);
            root.add(duelId.toString(), value);
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
            throw new UncheckedIOException("Could not save PvP arena restore", exception);
        }
    }
}
