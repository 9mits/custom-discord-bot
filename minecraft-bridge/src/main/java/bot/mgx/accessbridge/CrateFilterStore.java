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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Crate rewards a player never wants to receive.
 *
 * <p>Written for the unattended case: a player with hundreds of keys leaves an auto
 * run going, and 8 Raw Iron arriving four hundred times fills every slot they own long
 * before the keys run out. A listed reward is rolled, recorded and announced exactly as
 * before — only the item is never created, so the odds and the statistics stay honest.
 *
 * <p>Only reward IDs are stored, so a reward that leaves the catalog simply stops
 * matching. Free of Bukkit imports so it can be unit tested.
 */
final class CrateFilterStore {
    private final Path file;
    private final Map<UUID, Set<String>> discarded = new LinkedHashMap<>();

    CrateFilterStore(Path file) throws IOException {
        this.file = file;
        Files.createDirectories(file.getParent());
        if (!Files.isRegularFile(file) || Files.size(file) == 0L) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                try {
                    Set<String> rewards = new LinkedHashSet<>();
                    for (JsonElement value : entry.getValue().getAsJsonArray()) {
                        String rewardId = normalise(value.getAsString());
                        if (!rewardId.isEmpty()) {
                            rewards.add(rewardId);
                        }
                    }
                    if (!rewards.isEmpty()) {
                        discarded.put(UUID.fromString(entry.getKey()), rewards);
                    }
                } catch (RuntimeException ignored) {
                    // One unreadable row must not cost everybody else their filter.
                }
            }
        } catch (RuntimeException exception) {
            discarded.clear();
        }
    }

    synchronized boolean discards(UUID playerId, String rewardId) {
        Set<String> rewards = discarded.get(playerId);
        return rewards != null && rewards.contains(normalise(rewardId));
    }

    synchronized Set<String> all(UUID playerId) {
        Set<String> rewards = discarded.get(playerId);
        return rewards == null ? Set.of() : Set.copyOf(rewards);
    }

    synchronized int count(UUID playerId) {
        Set<String> rewards = discarded.get(playerId);
        return rewards == null ? 0 : rewards.size();
    }

    /** @return true when the reward is now being discarded */
    synchronized boolean toggle(UUID playerId, String rewardId) {
        String id = normalise(rewardId);
        if (id.isEmpty()) {
            throw new IllegalArgumentException("A crate filter needs a reward.");
        }
        Set<String> rewards = discarded.computeIfAbsent(
                playerId, ignored -> new LinkedHashSet<>()
        );
        boolean nowDiscarded = rewards.add(id);
        if (!nowDiscarded) {
            rewards.remove(id);
        }
        if (rewards.isEmpty()) {
            discarded.remove(playerId);
        }
        try {
            persist();
        } catch (RuntimeException failure) {
            if (nowDiscarded) {
                rewards.remove(id);
                if (rewards.isEmpty()) {
                    discarded.remove(playerId);
                }
            } else {
                discarded.computeIfAbsent(playerId, ignored -> new LinkedHashSet<>()).add(id);
            }
            throw failure;
        }
        return nowDiscarded;
    }

    /** @return how many rewards were being discarded before the clear */
    synchronized int clear(UUID playerId) {
        Set<String> previous = discarded.remove(playerId);
        if (previous == null) {
            return 0;
        }
        try {
            persist();
        } catch (RuntimeException failure) {
            discarded.put(playerId, previous);
            throw failure;
        }
        return previous.size();
    }

    /** @return how many players had a filter before the wipe */
    synchronized int clearAll() {
        int cleared = discarded.size();
        if (cleared == 0) {
            return 0;
        }
        Map<UUID, Set<String>> before = new LinkedHashMap<>(discarded);
        discarded.clear();
        try {
            persist();
        } catch (RuntimeException failure) {
            discarded.putAll(before);
            throw failure;
        }
        return cleared;
    }

    private static String normalise(String rewardId) {
        return rewardId == null ? "" : rewardId.strip().toLowerCase(Locale.ROOT);
    }

    private void persist() {
        JsonObject root = new JsonObject();
        discarded.forEach((playerId, rewards) -> {
            JsonArray values = new JsonArray();
            rewards.forEach(values::add);
            root.add(playerId.toString(), values);
        });
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.writeString(temporary, root.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not save crate filters", exception);
        }
    }
}
