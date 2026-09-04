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
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The opens, rare hits and expected hits behind {@link CrateOddsBalance}, per crate kind.
 *
 * <p>Persisted because the correction is only meaningful over hundreds of opens: a reset
 * on every restart would leave the balancer permanently below its minimum sample on a
 * server that restarts daily, which is the same as not having it.
 *
 * <p>Each crate also keeps how many of its counted opens came from each player, because a
 * table steered by whoever opens the most is not a table that describes the server. A
 * player at their quota still rolls, still wins and still has their reward announced —
 * their opens simply stop being evidence until the window decays.
 */
final class CrateOddsStore {
    /**
     * The most one account may contribute to a full window.
     *
     * <p>A fifth. It takes five distinct players to fill a window, and a whale grinding a
     * banked key stack cannot be more than a fifth of the evidence the table is read from.
     */
    static final long PLAYER_WINDOW_CAP = CrateOddsBalance.WINDOW_OPENS / 5L;

    record Counts(long opens, long rareHits, double expectedHits) {
        Counts {
            opens = Math.max(0L, opens);
            rareHits = Math.max(0L, Math.min(opens, rareHits));
            expectedHits = Double.isFinite(expectedHits) ? Math.max(0d, expectedHits) : 0d;
        }

        Counts plus(boolean rare, double expectedRate) {
            double rate = Double.isFinite(expectedRate) ? Math.max(0d, expectedRate) : 0d;
            return new Counts(opens + 1L, rareHits + (rare ? 1L : 0L), expectedHits + rate);
        }

        /** Halves all three, so the window follows recent play rather than all of history. */
        Counts decayed() {
            return new Counts(opens / 2L, rareHits / 2L, expectedHits / 2d);
        }
    }

    private final Path file;
    private final Map<CrateKind, Counts> counts = new EnumMap<>(CrateKind.class);
    private final Map<CrateKind, Map<UUID, Long>> contributions = new EnumMap<>(CrateKind.class);

    CrateOddsStore(Path file) throws IOException {
        this.file = file;
        Files.createDirectories(file.getParent());
        if (!Files.isRegularFile(file) || Files.size(file) == 0L) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                CrateKind kind = kindOf(entry.getKey());
                if (kind == null) {
                    continue;
                }
                JsonObject value = entry.getValue().getAsJsonObject();
                // A window written before opens carried their own expectation cannot be
                // compared against one that does, and there is no honest way to infer what
                // luck those opens were made under. Dropping it costs one window once.
                if (!value.has("expected")) {
                    continue;
                }
                counts.put(kind, new Counts(
                        value.has("opens") ? value.get("opens").getAsLong() : 0L,
                        value.has("rare") ? value.get("rare").getAsLong() : 0L,
                        value.get("expected").getAsDouble()
                ));
                contributions.put(kind, readContributions(value));
            }
        } catch (RuntimeException malformed) {
            // A hand-edited or truncated file is no counters at all, never a crash on boot.
            counts.clear();
            contributions.clear();
        }
    }

    private static Map<UUID, Long> readContributions(JsonObject value) {
        Map<UUID, Long> loaded = new LinkedHashMap<>();
        if (!value.has("players") || !value.get("players").isJsonObject()) {
            return loaded;
        }
        for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject("players").entrySet()) {
            try {
                long opens = entry.getValue().getAsLong();
                if (opens > 0L) {
                    loaded.put(UUID.fromString(entry.getKey()), opens);
                }
            } catch (RuntimeException ignored) {
                // One unreadable player is one player's quota, never the whole window.
            }
        }
        return loaded;
    }

    private static CrateKind kindOf(String name) {
        for (CrateKind kind : CrateKind.values()) {
            if (kind.name().equals(name)) {
                return kind;
            }
        }
        return null;
    }

    synchronized Counts counts(CrateKind kind) {
        return counts.getOrDefault(kind, new Counts(0L, 0L, 0d));
    }

    /** How many of this crate's counted opens came from one player. */
    synchronized long contribution(CrateKind kind, UUID playerId) {
        return contributions.getOrDefault(kind, Map.of()).getOrDefault(playerId, 0L);
    }

    /**
     * Records one open and returns the counters that now apply.
     *
     * @param expectedRate the rare rate of the table this open actually rolled on, which is
     *                     the published rate only when no luck and no correction were in play
     */
    synchronized Counts record(CrateKind kind, UUID playerId, boolean rare, double expectedRate) {
        Map<UUID, Long> players =
                contributions.computeIfAbsent(kind, ignored -> new LinkedHashMap<>());
        long already = players.getOrDefault(playerId, 0L);
        if (already >= PLAYER_WINDOW_CAP) {
            // At quota. The open happened and was paid out; it is simply not evidence.
            return counts(kind);
        }
        players.put(playerId, already + 1L);

        Counts updated = counts(kind).plus(rare, expectedRate);
        if (CrateOddsBalance.shouldDecay(updated.opens())) {
            updated = updated.decayed();
            decayContributions(players);
        }
        counts.put(kind, updated);
        save();
        return updated;
    }

    /** Quotas halve with the window, or a player at their cap would never contribute again. */
    private static void decayContributions(Map<UUID, Long> players) {
        // Halve first, then drop what reached zero. Writing through the entry inside
        // removeIf works on this map only because it is a LinkedHashMap; the same shape
        // on a ConcurrentHashMap throws and aborts the pass, which is what broke
        // /autobuy. Not worth leaving as a trap for whoever changes the map type.
        players.replaceAll((id, contributed) -> contributed / 2L);
        players.values().removeIf(remaining -> remaining <= 0L);
    }

    synchronized void reset() {
        counts.clear();
        contributions.clear();
        save();
    }

    private void save() {
        JsonObject root = new JsonObject();
        counts.forEach((kind, value) -> {
            JsonObject entry = new JsonObject();
            entry.addProperty("opens", value.opens());
            entry.addProperty("rare", value.rareHits());
            entry.addProperty("expected", value.expectedHits());
            JsonObject players = new JsonObject();
            contributions.getOrDefault(kind, Map.of())
                    .forEach((playerId, opens) -> players.addProperty(playerId.toString(), opens));
            entry.add("players", players);
            root.add(kind.name(), entry);
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
