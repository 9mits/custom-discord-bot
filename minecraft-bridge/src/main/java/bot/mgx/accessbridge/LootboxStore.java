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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Rolling opening limits and crash-safe rewards selected before an animation starts. */
final class LootboxStore {
    static final int OPENING_LIMIT = 3;
    static final long WINDOW_MILLIS = Duration.ofHours(24).toMillis();

    record Pending(UUID spinId, String rewardId, long reservedAt) {
    }

    static final class LimitReachedException extends IllegalStateException {
        private final long nextOpeningAt;

        LimitReachedException(long nextOpeningAt) {
            super("The rolling lootbox limit has been reached.");
            this.nextOpeningAt = nextOpeningAt;
        }

        long nextOpeningAt() {
            return nextOpeningAt;
        }
    }

    private final Path file;
    private final LinkedHashMap<UUID, List<Long>> openings = new LinkedHashMap<>();
    private final LinkedHashMap<UUID, Pending> pending = new LinkedHashMap<>();

    LootboxStore(Path file) throws IOException {
        this.file = file;
        Files.createDirectories(file.getParent());
        if (!Files.isRegularFile(file) || Files.size(file) == 0L) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            JsonObject savedOpenings = object(root, "openings");
            for (Map.Entry<String, JsonElement> entry : savedOpenings.entrySet()) {
                UUID playerId = UUID.fromString(entry.getKey());
                List<Long> times = new ArrayList<>();
                for (JsonElement value : entry.getValue().getAsJsonArray()) {
                    times.add(value.getAsLong());
                }
                times.sort(Comparator.naturalOrder());
                if (!times.isEmpty()) {
                    openings.put(playerId, times);
                }
            }
            JsonObject savedPending = object(root, "pending");
            for (Map.Entry<String, JsonElement> entry : savedPending.entrySet()) {
                JsonObject value = entry.getValue().getAsJsonObject();
                pending.put(UUID.fromString(entry.getKey()), new Pending(
                        UUID.fromString(value.get("spin_id").getAsString()),
                        value.get("reward_id").getAsString(),
                        value.get("reserved_at").getAsLong()
                ));
            }
        } catch (RuntimeException exception) {
            throw new IOException("Lootbox store is unreadable", exception);
        }
    }

    synchronized Pending reserve(UUID playerId, UUID spinId, String rewardId, long now) {
        if (pending.containsKey(playerId)) {
            throw new IllegalStateException("A reward is already waiting for this player.");
        }
        LinkedHashMap<UUID, List<Long>> openingsBefore = copyOpenings();
        LinkedHashMap<UUID, Pending> pendingBefore = new LinkedHashMap<>(pending);
        prune(playerId, now);
        List<Long> recent = openings.computeIfAbsent(playerId, ignored -> new ArrayList<>());
        if (recent.size() >= OPENING_LIMIT) {
            throw new LimitReachedException(recent.get(0) + WINDOW_MILLIS);
        }
        Pending reservation = new Pending(spinId, rewardId, now);
        recent.add(now);
        recent.sort(Comparator.naturalOrder());
        pending.put(playerId, reservation);
        try {
            save();
        } catch (RuntimeException exception) {
            restore(openingsBefore, pendingBefore);
            throw exception;
        }
        return reservation;
    }

    synchronized Optional<Pending> pending(UUID playerId) {
        return Optional.ofNullable(pending.get(playerId));
    }

    synchronized Map<UUID, Pending> pendingRewards() {
        return Map.copyOf(pending);
    }

    synchronized boolean complete(UUID playerId, UUID spinId) {
        Pending current = pending.get(playerId);
        if (current == null || !current.spinId().equals(spinId)) {
            return false;
        }
        pending.remove(playerId);
        try {
            save();
        } catch (RuntimeException exception) {
            pending.put(playerId, current);
            throw exception;
        }
        return true;
    }

    synchronized int remaining(UUID playerId, long now) {
        prune(playerId, now);
        return Math.max(0, OPENING_LIMIT - openings.getOrDefault(playerId, List.of()).size());
    }

    synchronized long nextOpeningAt(UUID playerId, long now) {
        prune(playerId, now);
        List<Long> recent = openings.getOrDefault(playerId, List.of());
        return recent.size() < OPENING_LIMIT ? now : recent.get(0) + WINDOW_MILLIS;
    }

    synchronized int clearAll() {
        int cleared = openings.size() + pending.size();
        if (cleared == 0) {
            return 0;
        }
        LinkedHashMap<UUID, List<Long>> openingsBefore = copyOpenings();
        LinkedHashMap<UUID, Pending> pendingBefore = new LinkedHashMap<>(pending);
        openings.clear();
        pending.clear();
        try {
            save();
        } catch (RuntimeException exception) {
            restore(openingsBefore, pendingBefore);
            throw exception;
        }
        return cleared;
    }

    private void prune(UUID playerId, long now) {
        List<Long> recent = openings.get(playerId);
        if (recent == null) {
            return;
        }
        long cutoff = now - WINDOW_MILLIS;
        recent.removeIf(timestamp -> timestamp <= cutoff);
        if (recent.isEmpty()) {
            openings.remove(playerId);
        }
    }

    private void save() {
        JsonObject root = new JsonObject();
        JsonObject savedOpenings = new JsonObject();
        openings.forEach((playerId, times) -> {
            JsonArray array = new JsonArray();
            times.forEach(array::add);
            savedOpenings.add(playerId.toString(), array);
        });
        root.add("openings", savedOpenings);
        JsonObject savedPending = new JsonObject();
        pending.forEach((playerId, reward) -> {
            JsonObject value = new JsonObject();
            value.addProperty("spin_id", reward.spinId().toString());
            value.addProperty("reward_id", reward.rewardId());
            value.addProperty("reserved_at", reward.reservedAt());
            savedPending.add(playerId.toString(), value);
        });
        root.add("pending", savedPending);
        writeAtomically(root.toString());
    }

    private LinkedHashMap<UUID, List<Long>> copyOpenings() {
        LinkedHashMap<UUID, List<Long>> copy = new LinkedHashMap<>();
        openings.forEach((playerId, times) -> copy.put(playerId, new ArrayList<>(times)));
        return copy;
    }

    private void restore(
            LinkedHashMap<UUID, List<Long>> savedOpenings,
            LinkedHashMap<UUID, Pending> savedPending
    ) {
        openings.clear();
        openings.putAll(savedOpenings);
        pending.clear();
        pending.putAll(savedPending);
    }

    private void writeAtomically(String json) {
        try {
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, json, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static JsonObject object(JsonObject parent, String key) {
        JsonElement value = parent.get(key);
        return value == null || !value.isJsonObject() ? new JsonObject() : value.getAsJsonObject();
    }
}
