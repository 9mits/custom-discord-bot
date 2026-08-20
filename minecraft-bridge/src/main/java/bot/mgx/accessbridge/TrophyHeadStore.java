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
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Prevents one killer and victim from printing unlimited trophy heads together. */
final class TrophyHeadStore {
    static final long PAIR_COOLDOWN_MILLIS = Duration.ofHours(24).toMillis();

    private record Pair(UUID killer, UUID victim) {
        String key() {
            return killer + ":" + victim;
        }

        static Pair parse(String raw) {
            String[] halves = raw.split(":", 2);
            return new Pair(UUID.fromString(halves[0]), UUID.fromString(halves[1]));
        }
    }

    private final Path file;
    private final LinkedHashMap<Pair, Long> awards = new LinkedHashMap<>();

    TrophyHeadStore(Path file) throws IOException {
        this.file = file;
        Files.createDirectories(file.getParent());
        if (!Files.isRegularFile(file) || Files.size(file) == 0L) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                awards.put(Pair.parse(entry.getKey()), entry.getValue().getAsLong());
            }
        } catch (RuntimeException exception) {
            throw new IOException("Trophy-head store is unreadable", exception);
        }
    }

    synchronized boolean claim(UUID killer, UUID victim, long now) {
        if (killer.equals(victim)) {
            return false;
        }
        LinkedHashMap<Pair, Long> before = new LinkedHashMap<>(awards);
        prune(now);
        Pair pair = new Pair(killer, victim);
        Long previous = awards.get(pair);
        if (previous != null && now - previous < PAIR_COOLDOWN_MILLIS) {
            return false;
        }
        awards.put(pair, now);
        try {
            save();
        } catch (RuntimeException exception) {
            awards.clear();
            awards.putAll(before);
            throw exception;
        }
        return true;
    }

    synchronized int clearAll() {
        int cleared = awards.size();
        if (cleared == 0) {
            return 0;
        }
        LinkedHashMap<Pair, Long> before = new LinkedHashMap<>(awards);
        awards.clear();
        try {
            save();
        } catch (RuntimeException exception) {
            awards.putAll(before);
            throw exception;
        }
        return cleared;
    }

    private void prune(long now) {
        Iterator<Map.Entry<Pair, Long>> iterator = awards.entrySet().iterator();
        while (iterator.hasNext()) {
            if (now - iterator.next().getValue() >= PAIR_COOLDOWN_MILLIS) {
                iterator.remove();
            }
        }
    }

    private void save() {
        JsonObject root = new JsonObject();
        awards.forEach((pair, timestamp) -> root.addProperty(pair.key(), timestamp));
        try {
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, root.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
