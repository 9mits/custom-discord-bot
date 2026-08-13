package bot.mgx.accessbridge;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

/** A small fsynced queue: an event is removed only after Discord acknowledges it. */
final class VerificationEventStore {
    private static final Gson GSON = new Gson();
    private final Path file;
    private final LinkedHashMap<String, JsonObject> events = new LinkedHashMap<>();

    VerificationEventStore(Path file) throws IOException {
        this.file = file;
        Files.createDirectories(file.getParent());
        if (Files.exists(file) && Files.size(file) > 0) {
            try {
                JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                for (Map.Entry<String, com.google.gson.JsonElement> entry : root.entrySet()) {
                    events.put(entry.getKey(), entry.getValue().getAsJsonObject());
                }
            } catch (RuntimeException exception) {
                throw new IOException("Verification event queue is unreadable", exception);
            }
        }
    }

    synchronized void put(String key, JsonObject payload) {
        if (events.containsKey(key)) {
            return;
        }
        events.put(key, payload.deepCopy());
        persistOrRollback(key, null);
    }

    synchronized JsonObject remove(String key) {
        JsonObject removed = events.remove(key);
        if (removed != null) {
            persistOrRollback(key, removed);
        }
        return removed;
    }

    synchronized Map<String, JsonObject> snapshot() {
        LinkedHashMap<String, JsonObject> copy = new LinkedHashMap<>();
        events.forEach((key, value) -> copy.put(key, value.deepCopy()));
        return copy;
    }

    private void persistOrRollback(String key, JsonObject previous) {
        try {
            persist();
        } catch (IOException exception) {
            if (previous == null) {
                events.remove(key);
            } else {
                events.put(key, previous);
            }
            throw new UncheckedIOException(exception);
        }
    }

    private void persist() throws IOException {
        JsonObject root = new JsonObject();
        events.forEach(root::add);
        byte[] bytes = GSON.toJson(root).getBytes(StandardCharsets.UTF_8);
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try (FileChannel channel = FileChannel.open(
                temporary,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        )) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
        try {
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
        try (FileChannel directory = FileChannel.open(file.getParent(), StandardOpenOption.READ)) {
            directory.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {
            // The file itself is already durable on filesystems that cannot fsync directories.
        }
    }
}
