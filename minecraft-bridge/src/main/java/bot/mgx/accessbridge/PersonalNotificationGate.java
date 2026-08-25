package bot.mgx.accessbridge;

import net.kyori.adventure.text.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Keeps ordinary action-bar alerts from replacing a higher-priority countdown. */
final class PersonalNotificationGate {
    private final Map<UUID, Integer> versions = new HashMap<>();
    private final Map<UUID, Integer> reservations = new HashMap<>();
    private final Map<UUID, Component> queued = new HashMap<>();

    int reserve(UUID playerId) {
        int version = versions.merge(playerId, 1, (current, ignored) ->
                current == Integer.MAX_VALUE ? 1 : current + 1
        );
        reservations.put(playerId, version);
        return version;
    }

    Optional<Component> offer(UUID playerId, Component message) {
        if (reservations.containsKey(playerId)) {
            queued.put(playerId, message);
            return Optional.empty();
        }
        return Optional.of(message);
    }

    Optional<Component> release(UUID playerId, int reservation) {
        if (!Integer.valueOf(reservation).equals(reservations.get(playerId))) {
            return Optional.empty();
        }
        reservations.remove(playerId);
        return Optional.ofNullable(queued.remove(playerId));
    }

    void clear(UUID playerId) {
        reservations.remove(playerId);
        queued.remove(playerId);
    }

    void clear() {
        reservations.clear();
        queued.clear();
    }
}
