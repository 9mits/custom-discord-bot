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
import java.util.Map;

/**
 * The opens and rare hits behind {@link CrateOddsBalance}, per crate kind.
 *
 * <p>Persisted because the correction is only meaningful over hundreds of opens: a reset
 * on every restart would leave the balancer permanently below its minimum sample on a
 * server that restarts daily, which is the same as not having it.
 */
final class CrateOddsStore {
    record Counts(long opens, long rareHits) {
        Counts {
            opens = Math.max(0L, opens);
            rareHits = Math.max(0L, Math.min(opens, rareHits));
        }

        Counts plus(boolean rare) {
            return new Counts(opens + 1L, rareHits + (rare ? 1L : 0L));
        }

        /** Halves both, so the window follows recent play rather than all of history. */
        Counts decayed() {
            return new Counts(opens / 2L, rareHits / 2L);
        }
    }

    private final Path file;
    private final Map<CrateKind, Counts> counts = new EnumMap<>(CrateKind.class);

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
                counts.put(kind, new Counts(
                        value.has("opens") ? value.get("opens").getAsLong() : 0L,
                        value.has("rare") ? value.get("rare").getAsLong() : 0L
                ));
            }
        } catch (RuntimeException malformed) {
            // A hand-edited or truncated file is no counters at all, never a crash on boot.
            counts.clear();
        }
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
        return counts.getOrDefault(kind, new Counts(0L, 0L));
    }

    /** Records one open and returns the counters that now apply. */
    synchronized Counts record(CrateKind kind, boolean rare) {
        Counts updated = counts(kind).plus(rare);
        if (CrateOddsBalance.shouldDecay(updated.opens())) {
            updated = updated.decayed();
        }
        counts.put(kind, updated);
        save();
        return updated;
    }

    synchronized void reset() {
        counts.clear();
        save();
    }

    private void save() {
        JsonObject root = new JsonObject();
        counts.forEach((kind, value) -> {
            JsonObject entry = new JsonObject();
            entry.addProperty("opens", value.opens());
            entry.addProperty("rare", value.rareHits());
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
