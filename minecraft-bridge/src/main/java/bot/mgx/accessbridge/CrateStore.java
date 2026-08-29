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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Hourly key credit and crash-safe rewards selected before an animation starts. */
final class CrateStore {
    static final long HOURLY_KEY_MILLIS = Duration.ofHours(1).toMillis();

    record Pending(UUID spinId, String rewardId, CrateKind crateKind, long reservedAt) {
    }

    record KeyCredit(int earned, int banked, long millisUntilNext) {
    }

    private final Path file;
    private final LinkedHashMap<UUID, Pending> pending = new LinkedHashMap<>();
    private final LinkedHashMap<UUID, Long> onlineProgress = new LinkedHashMap<>();
    private final LinkedHashMap<UUID, Integer> bankedKeys = new LinkedHashMap<>();

    static CrateStore open(Path dataFolder) throws IOException {
        Path crateFile = dataFolder.resolve("crates.json");
        Path legacyLootboxFile = dataFolder.resolve("lootboxes.json");
        Files.createDirectories(dataFolder);
        if (!Files.exists(crateFile) && Files.isRegularFile(legacyLootboxFile)) {
            Files.move(legacyLootboxFile, crateFile);
        }
        return new CrateStore(crateFile);
    }

    CrateStore(Path file) throws IOException {
        this.file = file;
        Files.createDirectories(file.getParent());
        if (!Files.isRegularFile(file) || Files.size(file) == 0L) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            JsonObject savedPending = object(root, "pending");
            for (Map.Entry<String, JsonElement> entry : savedPending.entrySet()) {
                JsonObject value = entry.getValue().getAsJsonObject();
                pending.put(UUID.fromString(entry.getKey()), new Pending(
                        UUID.fromString(value.get("spin_id").getAsString()),
                        value.get("reward_id").getAsString(),
                        savedKind(value),
                        value.get("reserved_at").getAsLong()
                ));
            }
            JsonObject savedProgress = object(root, "online_progress_millis");
            for (Map.Entry<String, JsonElement> entry : savedProgress.entrySet()) {
                long progress = Math.max(0L, entry.getValue().getAsLong()) % HOURLY_KEY_MILLIS;
                if (progress > 0L) {
                    onlineProgress.put(UUID.fromString(entry.getKey()), progress);
                }
            }
            JsonObject savedBanked = object(root, "banked_keys");
            for (Map.Entry<String, JsonElement> entry : savedBanked.entrySet()) {
                int count = Math.max(0, entry.getValue().getAsInt());
                if (count > 0) {
                    bankedKeys.put(UUID.fromString(entry.getKey()), count);
                }
            }
        } catch (RuntimeException exception) {
            throw new IOException("Crate store is unreadable", exception);
        }
    }

    synchronized Pending reserve(
            UUID playerId, UUID spinId, String rewardId, CrateKind crateKind, long now
    ) {
        if (pending.containsKey(playerId)) {
            throw new IllegalStateException("A reward is already waiting for this player.");
        }
        LinkedHashMap<UUID, Pending> pendingBefore = new LinkedHashMap<>(pending);
        Pending reservation = new Pending(spinId, rewardId, crateKind, now);
        pending.put(playerId, reservation);
        try {
            save();
        } catch (RuntimeException exception) {
            restore(pendingBefore);
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

    synchronized Map<UUID, KeyCredit> creditOnline(Map<UUID, Long> elapsedByPlayer) {
        return creditOnline(elapsedByPlayer, Map.of());
    }

    /**
     * Banks one key for each completed hour, multiplied by the player's current rate.
     * The elapsed-time remainder is unchanged by the rate so becoming or ceasing to be
     * a booster never discards part of an hour.
     */
    synchronized Map<UUID, KeyCredit> creditOnline(
            Map<UUID, Long> elapsedByPlayer, Map<UUID, Integer> keysPerHour
    ) {
        LinkedHashMap<UUID, Long> progressBefore = new LinkedHashMap<>(onlineProgress);
        LinkedHashMap<UUID, Integer> bankedBefore = new LinkedHashMap<>(bankedKeys);
        LinkedHashMap<UUID, KeyCredit> credits = new LinkedHashMap<>();
        boolean changed = false;
        try {
            for (Map.Entry<UUID, Long> entry : elapsedByPlayer.entrySet()) {
                UUID playerId = entry.getKey();
                long elapsed = entry.getValue() == null ? 0L : entry.getValue();
                if (playerId == null || elapsed <= 0L) {
                    continue;
                }
                changed = true;
                long total = Math.addExact(onlineProgress.getOrDefault(playerId, 0L), elapsed);
                long earnedLong = total / HOURLY_KEY_MILLIS;
                if (earnedLong > Integer.MAX_VALUE) {
                    throw new IllegalArgumentException("Online-time credit is too large.");
                }
                int rate = Math.max(1, keysPerHour.getOrDefault(playerId, 1));
                int earned = Math.multiplyExact((int) earnedLong, rate);
                long remainder = total % HOURLY_KEY_MILLIS;
                if (remainder == 0L) {
                    onlineProgress.remove(playerId);
                } else {
                    onlineProgress.put(playerId, remainder);
                }
                int banked = bankedKeys.getOrDefault(playerId, 0);
                if (earned > 0) {
                    banked = Math.addExact(banked, earned);
                    bankedKeys.put(playerId, banked);
                }
                credits.put(playerId, new KeyCredit(
                        earned,
                        banked,
                        HOURLY_KEY_MILLIS - remainder
                ));
            }
            if (!changed) {
                return Map.copyOf(credits);
            }
            save();
        } catch (RuntimeException exception) {
            onlineProgress.clear();
            onlineProgress.putAll(progressBefore);
            bankedKeys.clear();
            bankedKeys.putAll(bankedBefore);
            throw exception;
        }
        return Map.copyOf(credits);
    }

    synchronized int bankedKeys(UUID playerId) {
        return bankedKeys.getOrDefault(playerId, 0);
    }

    synchronized long millisUntilNextKey(UUID playerId) {
        return HOURLY_KEY_MILLIS - onlineProgress.getOrDefault(playerId, 0L);
    }

    synchronized int claimBankedKeys(UUID playerId, int requested) {
        if (requested <= 0) {
            return 0;
        }
        int before = bankedKeys.getOrDefault(playerId, 0);
        int claimed = Math.min(before, requested);
        if (claimed == 0) {
            return 0;
        }
        int after = before - claimed;
        if (after == 0) {
            bankedKeys.remove(playerId);
        } else {
            bankedKeys.put(playerId, after);
        }
        try {
            save();
        } catch (RuntimeException exception) {
            bankedKeys.put(playerId, before);
            throw exception;
        }
        return claimed;
    }

    synchronized int clearAll() {
        int cleared = pending.size() + onlineProgress.size() + bankedKeys.size();
        if (cleared == 0) {
            return 0;
        }
        LinkedHashMap<UUID, Pending> pendingBefore = new LinkedHashMap<>(pending);
        LinkedHashMap<UUID, Long> progressBefore = new LinkedHashMap<>(onlineProgress);
        LinkedHashMap<UUID, Integer> bankedBefore = new LinkedHashMap<>(bankedKeys);
        pending.clear();
        onlineProgress.clear();
        bankedKeys.clear();
        try {
            save();
        } catch (RuntimeException exception) {
            restore(pendingBefore, progressBefore, bankedBefore);
            throw exception;
        }
        return cleared;
    }

    private void save() {
        JsonObject root = new JsonObject();
        JsonObject savedPending = new JsonObject();
        pending.forEach((playerId, reward) -> {
            JsonObject value = new JsonObject();
            value.addProperty("spin_id", reward.spinId().toString());
            value.addProperty("reward_id", reward.rewardId());
            value.addProperty("crate_kind", reward.crateKind().key());
            value.addProperty("reserved_at", reward.reservedAt());
            savedPending.add(playerId.toString(), value);
        });
        root.add("pending", savedPending);
        JsonObject savedProgress = new JsonObject();
        onlineProgress.forEach((playerId, millis) ->
                savedProgress.addProperty(playerId.toString(), millis));
        root.add("online_progress_millis", savedProgress);
        JsonObject savedBanked = new JsonObject();
        bankedKeys.forEach((playerId, count) ->
                savedBanked.addProperty(playerId.toString(), count));
        root.add("banked_keys", savedBanked);
        writeAtomically(root.toString());
    }

    private void restore(LinkedHashMap<UUID, Pending> savedPending) {
        pending.clear();
        pending.putAll(savedPending);
    }

    private void restore(
            LinkedHashMap<UUID, Pending> savedPending,
            LinkedHashMap<UUID, Long> savedProgress,
            LinkedHashMap<UUID, Integer> savedBanked
    ) {
        restore(savedPending);
        onlineProgress.clear();
        onlineProgress.putAll(savedProgress);
        bankedKeys.clear();
        bankedKeys.putAll(savedBanked);
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

    /** Older reservations predate crate-kind persistence; infer their only event crate safely. */
    private static CrateKind savedKind(JsonObject value) {
        JsonElement raw = value.get("crate_kind");
        if (raw != null && raw.isJsonPrimitive()) {
            Optional<CrateKind> parsed = CrateKind.from(raw.getAsString());
            if (parsed.isPresent()) {
                return parsed.get();
            }
        }
        return CrateCatalog.find(value.get("reward_id").getAsString())
                .filter(CrateCatalog::isAmethyst)
                .map(ignored -> CrateKind.AMETHYST)
                .orElse(CrateKind.DEFAULT);
    }
}
