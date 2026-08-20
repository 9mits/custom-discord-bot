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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * What Discord rank sync has actually granted, and who it must leave alone.
 *
 * <p>Two things live here because they answer the same question — may this group be
 * taken away? — from opposite ends:
 *
 * <ul>
 *   <li><b>Applied ranks.</b> The group the bridge last gave a player. Sync removes
 *       only this one, so a LuckPerms group added by hand is never collateral damage
 *       of somebody's Discord roles changing.
 *   <li><b>Holds.</b> Players the bridge does not touch at all. This is the escape
 *       hatch for giving somebody a rank in game that they do not have in Discord;
 *       without it, sync would keep putting the Discord answer back.
 * </ul>
 *
 * <p>Free of Bukkit imports so it can be unit tested.
 */
final class RankSyncStore {
    private final Path file;
    private final Map<UUID, String> applied = new LinkedHashMap<>();
    private final Set<UUID> held = new LinkedHashSet<>();
    /** Last known name per held player, kept only so the hold list reads legibly. */
    private final Map<UUID, String> heldNames = new LinkedHashMap<>();

    RankSyncStore(Path file) throws IOException {
        this.file = file;
        Files.createDirectories(file.getParent());
        if (!Files.isRegularFile(file) || Files.size(file) == 0) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            JsonObject grants = root.getAsJsonObject("applied");
            if (grants != null) {
                for (Map.Entry<String, JsonElement> entry : grants.entrySet()) {
                    applied.put(UUID.fromString(entry.getKey()), entry.getValue().getAsString());
                }
            }
            JsonArray holds = root.getAsJsonArray("held");
            if (holds != null) {
                for (JsonElement element : holds) {
                    JsonObject entry = element.getAsJsonObject();
                    UUID playerId = UUID.fromString(entry.get("uuid").getAsString());
                    held.add(playerId);
                    JsonElement name = entry.get("name");
                    if (name != null && !name.isJsonNull()) {
                        heldNames.put(playerId, name.getAsString());
                    }
                }
            }
        } catch (RuntimeException exception) {
            throw new IOException("Rank sync store is unreadable", exception);
        }
    }

    /** Whether Discord rank sync must leave this player's LuckPerms groups alone. */
    synchronized boolean isHeld(UUID playerId) {
        return held.contains(playerId);
    }

    /** @return true when the player was not already held. */
    synchronized boolean hold(UUID playerId, String name) {
        StateSnapshot before = snapshot();
        boolean added = held.add(playerId);
        boolean renamed = false;
        if (name != null && !name.isBlank()) {
            String previous = heldNames.put(playerId, name);
            renamed = !name.equals(previous);
        }
        // The bridge no longer owns anything it gave them: releasing later must not
        // retroactively strip a group an admin has since set by hand.
        boolean grantRemoved = applied.remove(playerId) != null;
        if (added || renamed || grantRemoved) {
            saveOrRollback(before);
        }
        return added;
    }

    /** @return true when the player was actually held. */
    synchronized boolean release(UUID playerId) {
        StateSnapshot before = snapshot();
        boolean removed = held.remove(playerId);
        boolean nameRemoved = heldNames.remove(playerId) != null;
        if (removed || nameRemoved) {
            saveOrRollback(before);
        }
        return removed;
    }

    /** Everyone currently held, as uuid to last known name. */
    synchronized Map<UUID, String> holds() {
        LinkedHashMap<UUID, String> snapshot = new LinkedHashMap<>();
        for (UUID playerId : held) {
            snapshot.put(playerId, heldNames.getOrDefault(playerId, playerId.toString()));
        }
        return snapshot;
    }

    /**
     * The group sync last gave this player, if any.
     *
     * <p>Empty means the bridge has granted them nothing it may take back — either it
     * has never synced them, or the rank it gave has since been cleared.
     */
    synchronized Optional<String> appliedRank(UUID playerId) {
        return Optional.ofNullable(applied.get(playerId)).filter(group -> !group.isEmpty());
    }

    /** Records the group sync just granted; an empty group records that it granted none. */
    synchronized void recordApplied(UUID playerId, String group) {
        StateSnapshot before = snapshot();
        String previous;
        if (group == null || group.isBlank()) {
            previous = applied.remove(playerId);
        } else {
            previous = applied.put(playerId, group.trim());
        }
        String current = applied.get(playerId);
        if (previous == null ? current != null : !previous.equals(current)) {
            saveOrRollback(before);
        }
    }

    /** Forgets every applied-rank record, used when player data is reset. */
    synchronized int clearApplied() {
        int cleared = applied.size();
        if (cleared > 0) {
            StateSnapshot before = snapshot();
            applied.clear();
            saveOrRollback(before);
        }
        return cleared;
    }

    private StateSnapshot snapshot() {
        return new StateSnapshot(
                new LinkedHashMap<>(applied),
                new LinkedHashSet<>(held),
                new LinkedHashMap<>(heldNames)
        );
    }

    private void saveOrRollback(StateSnapshot before) {
        try {
            save();
        } catch (RuntimeException failure) {
            applied.clear();
            applied.putAll(before.applied());
            held.clear();
            held.addAll(before.held());
            heldNames.clear();
            heldNames.putAll(before.heldNames());
            throw failure;
        }
    }

    private record StateSnapshot(
            Map<UUID, String> applied,
            Set<UUID> held,
            Map<UUID, String> heldNames
    ) {
    }

    private void save() {
        JsonObject root = new JsonObject();
        JsonObject grants = new JsonObject();
        applied.forEach((playerId, group) -> grants.addProperty(playerId.toString(), group));
        root.add("applied", grants);
        JsonArray holds = new JsonArray();
        for (UUID playerId : held) {
            JsonObject entry = new JsonObject();
            entry.addProperty("uuid", playerId.toString());
            String name = heldNames.get(playerId);
            if (name != null) {
                entry.addProperty("name", name);
            }
            holds.add(entry);
        }
        root.add("held", holds);
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
