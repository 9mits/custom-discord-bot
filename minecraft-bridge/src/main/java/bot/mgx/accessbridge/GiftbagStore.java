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
 * The Giftbag ledger: one crash-safe reward per player mid-reveal, the bags owed to
 * players who were offline when they earned one, and who has already taken the welcome
 * gift — a bag each player may claim exactly once, however many times they log in.
 */
final class GiftbagStore {
    record Pending(UUID spinId, String rewardId, int season, long reservedAt) { }

    private final Path file;
    private final LinkedHashMap<UUID, Pending> pending = new LinkedHashMap<>();
    private final LinkedHashMap<UUID, Integer> owed = new LinkedHashMap<>();
    private final java.util.LinkedHashSet<UUID> welcomed = new java.util.LinkedHashSet<>();

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
            JsonObject debts = root.has("owed") ? root.getAsJsonObject("owed") : new JsonObject();
            for (Map.Entry<String, JsonElement> entry : debts.entrySet()) {
                owed.put(UUID.fromString(entry.getKey()), entry.getValue().getAsInt());
            }
            if (root.has("welcomed")) {
                root.getAsJsonArray("welcomed").forEach(id -> welcomed.add(UUID.fromString(id.getAsString())));
            }
        } catch (RuntimeException exception) {
            throw new IOException("Giftbag store is unreadable", exception);
        }
    }

    /** Records a bag earned while its owner was away; it arrives at their next join. */
    synchronized void owe(UUID playerId, int bags) {
        if (bags <= 0) return;
        owed.merge(playerId, bags, Integer::sum);
        save();
    }

    /** Takes everything owed to a player, to be handed over now. */
    synchronized int takeOwed(UUID playerId) {
        Integer waiting = owed.remove(playerId);
        if (waiting == null || waiting <= 0) return 0;
        try {
            save();
        } catch (RuntimeException failure) {
            owed.put(playerId, waiting);
            throw failure;
        }
        return waiting;
    }

    /**
     * Claims the one welcome gift a player ever gets.
     *
     * @return whether this call is the one that claimed it
     */
    synchronized boolean claimWelcome(UUID playerId) {
        if (!welcomed.add(playerId)) return false;
        try {
            save();
        } catch (RuntimeException failure) {
            welcomed.remove(playerId);
            throw failure;
        }
        return true;
    }

    synchronized boolean welcomed(UUID playerId) {
        return welcomed.contains(playerId);
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
        JsonObject debts = new JsonObject();
        owed.forEach((playerId, bags) -> debts.addProperty(playerId.toString(), bags));
        root.add("owed", debts);
        com.google.gson.JsonArray claimed = new com.google.gson.JsonArray();
        welcomed.forEach(playerId -> claimed.add(playerId.toString()));
        root.add("welcomed", claimed);
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
