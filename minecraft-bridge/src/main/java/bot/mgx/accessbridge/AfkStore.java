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
import java.util.UUID;

/**
 * How long each player has actually spent AFK.
 *
 * <p>Nothing recorded this before. {@link AfkService} held its state in maps that were
 * cleared on every restart, so the server could say who is AFK right now and never how
 * much AFK there had been — which made the question "is AFK worth rewarding" unanswerable
 * except by inference from vanilla statistics, and those are lifetime totals that cannot
 * separate an idle hour from an active one inside the same account.
 *
 * <p>Free of Bukkit so the arithmetic is unit tested. Sessions are closed by the service;
 * this only owns the totals and the file they live in.
 */
final class AfkStore {
    /** One player's lifetime AFK, and how many separate stretches it took. */
    record Totals(long afkMillis, long sessions) {
        Totals {
            afkMillis = Math.max(0L, afkMillis);
            sessions = Math.max(0L, sessions);
        }

        Totals plus(long millis) {
            return new Totals(afkMillis + Math.max(0L, millis), sessions + 1L);
        }

        long afkSeconds() {
            return afkMillis / 1_000L;
        }
    }

    private final Path file;
    private final Map<UUID, Totals> totals = new LinkedHashMap<>();

    AfkStore(Path file) throws IOException {
        this.file = file;
        Files.createDirectories(file.getParent());
        if (!Files.isRegularFile(file) || Files.size(file) == 0L) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                JsonObject value = entry.getValue().getAsJsonObject();
                totals.put(UUID.fromString(entry.getKey()), new Totals(
                        value.has("millis") ? value.get("millis").getAsLong() : 0L,
                        value.has("sessions") ? value.get("sessions").getAsLong() : 0L
                ));
            }
        } catch (RuntimeException malformed) {
            // A hand-edited or truncated file is no history at all, never a crash on boot.
            totals.clear();
        }
    }

    synchronized Totals totals(UUID playerId) {
        return totals.getOrDefault(playerId, new Totals(0L, 0L));
    }

    /** Adds one finished AFK stretch and returns the player's new lifetime figure. */
    synchronized Totals record(UUID playerId, long millis) {
        if (millis <= 0L) {
            return totals(playerId);
        }
        Totals updated = totals(playerId).plus(millis);
        totals.put(playerId, updated);
        save();
        return updated;
    }

    synchronized Map<UUID, Totals> all() {
        return Map.copyOf(totals);
    }

    /** Every player's AFK added together, for the one figure a snapshot wants. */
    synchronized long totalMillis() {
        return totals.values().stream().mapToLong(Totals::afkMillis).sum();
    }

    synchronized void reset() {
        totals.clear();
        save();
    }

    private void save() {
        JsonObject root = new JsonObject();
        totals.forEach((playerId, value) -> {
            JsonObject entry = new JsonObject();
            entry.addProperty("millis", value.afkMillis());
            entry.addProperty("sessions", value.sessions());
            root.add(playerId.toString(), entry);
        });
        try {
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, root.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
