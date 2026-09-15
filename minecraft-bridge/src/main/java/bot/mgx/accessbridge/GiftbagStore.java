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

/** One crash-safe Giftbag reward per player, selected before its reveal begins. */
final class GiftbagStore {
    record Pending(UUID spinId, String rewardId, int season, long reservedAt) { }

    private final Path file;
    private final LinkedHashMap<UUID, Pending> pending = new LinkedHashMap<>();

    GiftbagStore(Path file) throws IOException {
        this.file = file;
        Files.createDirectories(file.getParent());
        if (!Files.isRegularFile(file) || Files.size(file) == 0L) return;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            JsonObject saved = root.has("pending") ? root.getAsJsonObject("pending") : new JsonObject();
            for (Map.Entry<String, JsonElement> entry : saved.entrySet()) {
                JsonObject value = entry.getValue().getAsJsonObject();
                pending.put(UUID.fromString(entry.getKey()), new Pending(
                        UUID.fromString(value.get("spin_id").getAsString()),
                        value.get("reward_id").getAsString(),
                        value.get("season").getAsInt(),
                        value.get("reserved_at").getAsLong()
                ));
            }
        } catch (RuntimeException exception) {
            throw new IOException("Giftbag store is unreadable", exception);
        }
    }

    synchronized Pending reserve(UUID playerId, UUID spinId, String rewardId, int season, long now) {
        if (pending.containsKey(playerId)) throw new IllegalStateException("A Giftbag reward is already waiting.");
        Pending reservation = new Pending(spinId, rewardId, season, now);
        pending.put(playerId, reservation);
        try {
            save();
        } catch (RuntimeException failure) {
            pending.remove(playerId);
            throw failure;
        }
        return reservation;
    }

    synchronized Optional<Pending> pending(UUID playerId) {
        return Optional.ofNullable(pending.get(playerId));
    }

    synchronized boolean complete(UUID playerId, UUID spinId) {
        Pending current = pending.get(playerId);
        if (current == null || !current.spinId.equals(spinId)) return false;
        pending.remove(playerId);
        try {
            save();
        } catch (RuntimeException failure) {
            pending.put(playerId, current);
            throw failure;
        }
        return true;
    }

    synchronized Map<UUID, Pending> all() {
        return Map.copyOf(pending);
    }

    private void save() {
        JsonObject root = new JsonObject();
        JsonObject saved = new JsonObject();
        pending.forEach((playerId, reward) -> {
            JsonObject value = new JsonObject();
            value.addProperty("spin_id", reward.spinId.toString());
            value.addProperty("reward_id", reward.rewardId);
            value.addProperty("season", reward.season);
            value.addProperty("reserved_at", reward.reservedAt);
            saved.add(playerId.toString(), value);
        });
        root.add("pending", saved);
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
