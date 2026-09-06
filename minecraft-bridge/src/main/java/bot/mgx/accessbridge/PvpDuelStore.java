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
 * Crash recovery for fighters and spectators.
 *
 * <p>A recovery is written before a player is moved, charged, or stripped for the
 * viewing stand. A hard stop can therefore be treated as a draw on the next join:
 * the origin and player state come back, wager balances are raised to at least their
 * pre-duel value, and any missing held-stack wager is returned.
 */
final class PvpDuelStore {
    enum Role { FIGHTER, SPECTATOR }

    record Recovery(
            UUID duelId,
            Role role,
            UUID worldId,
            String worldName,
            double x,
            double y,
            double z,
            float yaw,
            float pitch,
            String gameMode,
            boolean invulnerable,
            boolean allowFlight,
            boolean flying,
            double health,
            int food,
            float saturation,
            float exhaustion,
            int fireTicks,
            float fallDistance,
            int remainingAir,
            int heldSlot,
            long balanceBefore,
            String encodedStake,
            String encodedInventory
    ) {
        Recovery settled(long currentBalance) {
            return new Recovery(
                    duelId, role, worldId, worldName, x, y, z, yaw, pitch, gameMode,
                    invulnerable, allowFlight, flying, health, food, saturation,
                    exhaustion, fireTicks, fallDistance, remainingAir, heldSlot,
                    currentBalance, "", encodedInventory
            );
        }
    }

    private final Path file;
    private final Map<UUID, Recovery> recoveries = new LinkedHashMap<>();

    PvpDuelStore(Path file) throws IOException {
        this.file = file;
        Files.createDirectories(file.getParent());
        if (!Files.isRegularFile(file) || Files.size(file) == 0L) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                JsonObject value = entry.getValue().getAsJsonObject();
                recoveries.put(UUID.fromString(entry.getKey()), read(value));
            }
        } catch (RuntimeException exception) {
            throw new IOException("PvP duel recovery store is unreadable", exception);
        }
    }

    synchronized Map<UUID, Recovery> all() {
        return Map.copyOf(recoveries);
    }

    synchronized Optional<Recovery> find(UUID playerId) {
        return Optional.ofNullable(recoveries.get(playerId));
    }

    synchronized void putAll(Map<UUID, Recovery> added) {
        Map<UUID, Recovery> before = new LinkedHashMap<>(recoveries);
        recoveries.putAll(added);
        try {
            save();
        } catch (RuntimeException failure) {
            recoveries.clear();
            recoveries.putAll(before);
            throw failure;
        }
    }

    synchronized void settle(UUID playerId, long currentBalance) {
        Recovery before = recoveries.get(playerId);
        if (before == null) {
            return;
        }
        recoveries.put(playerId, before.settled(currentBalance));
        try {
            save();
        } catch (RuntimeException failure) {
            recoveries.put(playerId, before);
            throw failure;
        }
    }

    synchronized Optional<Recovery> remove(UUID playerId) {
        Recovery removed = recoveries.remove(playerId);
        if (removed == null) {
            return Optional.empty();
        }
        try {
            save();
        } catch (RuntimeException failure) {
            recoveries.put(playerId, removed);
            throw failure;
        }
        return Optional.of(removed);
    }

    private Recovery read(JsonObject value) {
        return new Recovery(
                UUID.fromString(value.get("duel").getAsString()),
                Role.valueOf(value.get("role").getAsString()),
                UUID.fromString(value.get("world_id").getAsString()),
                value.get("world_name").getAsString(),
                value.get("x").getAsDouble(),
                value.get("y").getAsDouble(),
                value.get("z").getAsDouble(),
                value.get("yaw").getAsFloat(),
                value.get("pitch").getAsFloat(),
                value.get("game_mode").getAsString(),
                value.get("invulnerable").getAsBoolean(),
                value.get("allow_flight").getAsBoolean(),
                value.get("flying").getAsBoolean(),
                value.get("health").getAsDouble(),
                value.get("food").getAsInt(),
                value.get("saturation").getAsFloat(),
                value.get("exhaustion").getAsFloat(),
                value.get("fire_ticks").getAsInt(),
                value.get("fall_distance").getAsFloat(),
                value.get("remaining_air").getAsInt(),
                value.get("held_slot").getAsInt(),
                value.get("balance_before").getAsLong(),
                value.has("stake") ? value.get("stake").getAsString() : "",
                value.has("inventory") ? value.get("inventory").getAsString() : ""
        );
    }

    private void save() {
        JsonObject root = new JsonObject();
        recoveries.forEach((playerId, recovery) -> {
            JsonObject value = new JsonObject();
            value.addProperty("duel", recovery.duelId().toString());
            value.addProperty("role", recovery.role().name());
            value.addProperty("world_id", recovery.worldId().toString());
            value.addProperty("world_name", recovery.worldName());
            value.addProperty("x", recovery.x());
            value.addProperty("y", recovery.y());
            value.addProperty("z", recovery.z());
            value.addProperty("yaw", recovery.yaw());
            value.addProperty("pitch", recovery.pitch());
            value.addProperty("game_mode", recovery.gameMode());
            value.addProperty("invulnerable", recovery.invulnerable());
            value.addProperty("allow_flight", recovery.allowFlight());
            value.addProperty("flying", recovery.flying());
            value.addProperty("health", recovery.health());
            value.addProperty("food", recovery.food());
            value.addProperty("saturation", recovery.saturation());
            value.addProperty("exhaustion", recovery.exhaustion());
            value.addProperty("fire_ticks", recovery.fireTicks());
            value.addProperty("fall_distance", recovery.fallDistance());
            value.addProperty("remaining_air", recovery.remainingAir());
            value.addProperty("held_slot", recovery.heldSlot());
            value.addProperty("balance_before", recovery.balanceBefore());
            value.addProperty("stake", recovery.encodedStake());
            value.addProperty("inventory", recovery.encodedInventory());
            root.add(playerId.toString(), value);
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
            throw new UncheckedIOException("Could not save PvP duel recovery", exception);
        }
    }
}
