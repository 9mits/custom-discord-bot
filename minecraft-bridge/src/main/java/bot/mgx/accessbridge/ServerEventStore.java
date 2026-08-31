package bot.mgx.accessbridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.EnumMap;
import java.util.Map;

/**
 * Which multiplier events are running, and until when.
 *
 * <p>Persisted for the same reason {@link MaintenanceStore} is: Paper accepts
 * logins, sales and crate openings from the moment it finishes starting, which
 * is before the bridge connects. An event that forgot itself across a restart
 * would keep advertising 2x on the server list while quietly paying 1x, and
 * nobody would notice until a player did the arithmetic.
 *
 * <p>Expiry is evaluated on read rather than by a timer. A timer that did not
 * fire — because the server was down when the event should have ended — would
 * leave it running; a deadline that is simply in the past cannot.
 *
 * <p>Free of Bukkit imports so it can be unit tested.
 */
final class ServerEventStore {
    /** Deadline meaning "until somebody turns it off". */
    static final long NO_DEADLINE = 0L;

    private final Path file;
    private final Map<ServerEventType, Long> deadlines = new EnumMap<>(ServerEventType.class);

    ServerEventStore(Path file) throws IOException {
        this.file = file;
        Files.createDirectories(file.getParent());
        if (!Files.isRegularFile(file) || Files.size(file) == 0L) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            for (ServerEventType type : ServerEventType.values()) {
                if (root.has(type.id())) {
                    deadlines.put(type, root.get(type.id()).getAsLong());
                }
            }
        } catch (RuntimeException exception) {
            // Unreadable reads as "no events". The alternative — assuming a
            // payout multiplier nobody set — quietly inflates the economy.
            deadlines.clear();
        }
    }

    /** @param now the caller's clock, so tests do not have to wait */
    synchronized boolean active(ServerEventType type, long now) {
        Long deadline = deadlines.get(type);
        if (deadline == null) {
            return false;
        }
        return deadline == NO_DEADLINE || now < deadline;
    }

    /**
     * Where each event's factor comes from.
     *
     * <p>Set once the live registry exists so an owner can change 2x Keys into 3x
     * without a build. Until then, and in tests, the catalogue figure stands.
     */
    private volatile java.util.function.ToIntFunction<ServerEventType> factors =
            ServerEventType::baseMultiplier;

    void factorSource(java.util.function.ToIntFunction<ServerEventType> source) {
        if (source != null) {
            this.factors = source;
        }
    }

    /** The factor this event carries, running or not. */
    int factor(ServerEventType type) {
        return Math.max(1, factors.applyAsInt(type));
    }

    /** What to multiply by right now: the event's factor, or 1 when it is off. */
    synchronized int multiplier(ServerEventType type, long now) {
        return active(type, now) ? factor(type) : 1;
    }

    /** Milliseconds left, or 0 when this event is off or runs until turned off. */
    synchronized long remainingMillis(ServerEventType type, long now) {
        Long deadline = deadlines.get(type);
        if (deadline == null || deadline == NO_DEADLINE || !active(type, now)) {
            return 0L;
        }
        return Math.max(0L, deadline - now);
    }

    synchronized Map<ServerEventType, Long> snapshot(long now) {
        Map<ServerEventType, Long> live = new EnumMap<>(ServerEventType.class);
        for (ServerEventType type : ServerEventType.values()) {
            if (active(type, now)) {
                live.put(type, deadlines.get(type));
            }
        }
        return live;
    }

    /**
     * Starts or stops one event.
     *
     * @param deadline epoch millis to stop at, or {@link #NO_DEADLINE} for open-ended
     * @return true when this actually changed anything
     */
    synchronized boolean set(ServerEventType type, boolean enabled, long deadline, long now) {
        boolean was = active(type, now);
        Long previous = deadlines.get(type);
        if (enabled) {
            deadlines.put(type, deadline);
        } else {
            deadlines.remove(type);
        }
        if (was == active(type, now) && java.util.Objects.equals(previous, deadlines.get(type))) {
            return false;
        }
        try {
            persist();
        } catch (RuntimeException failure) {
            if (previous == null) {
                deadlines.remove(type);
            } else {
                deadlines.put(type, previous);
            }
            throw failure;
        }
        return true;
    }

    /** Drops deadlines that have already passed, so the file does not grow stale. */
    synchronized boolean prune(long now) {
        boolean changed = deadlines.entrySet().removeIf(
                entry -> entry.getValue() != NO_DEADLINE && now >= entry.getValue()
        );
        if (changed) {
            persist();
        }
        return changed;
    }

    private void persist() {
        JsonObject root = new JsonObject();
        deadlines.forEach((type, deadline) -> root.addProperty(type.id(), deadline));
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
            throw new UncheckedIOException("Could not save server events", exception);
        }
    }
}
