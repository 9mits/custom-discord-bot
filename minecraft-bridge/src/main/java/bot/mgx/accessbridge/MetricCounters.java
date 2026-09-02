package bot.mgx.accessbridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Running totals of things that happen, so they can be charted.
 *
 * <p>Balances and listings can be counted whenever somebody asks, because they are state.
 * Events are not: once a crate is opened nothing remembers it happened, so "crates opened
 * this week" cannot be derived after the fact. These are monotonic counters incremented
 * at the moment of the event and sampled by the panel, which turns them into a rate.
 *
 * <p>Deliberately cheap. A counter is a long in memory; the file is rewritten on a timer
 * rather than on every increment, because losing a few crate openings to a hard kill is a
 * rounding error and writing to disk on every crate open is not.
 */
final class MetricCounters {
    private final Path file;
    private final Map<String, Long> counters = new LinkedHashMap<>();
    private volatile boolean dirty;

    MetricCounters(Path file) {
        this.file = file;
        load();
    }

    synchronized void increment(String key, long amount) {
        if (key == null || key.isBlank() || amount == 0L) {
            return;
        }
        counters.merge(key, amount, Long::sum);
        dirty = true;
    }

    void increment(String key) {
        increment(key, 1L);
    }

    synchronized long value(String key) {
        return counters.getOrDefault(key, 0L);
    }

    synchronized Map<String, Long> all() {
        return Map.copyOf(counters);
    }

    /** Writes only when something changed, so an idle server does no disk work. */
    synchronized void flush() {
        if (!dirty) {
            return;
        }
        JsonObject root = new JsonObject();
        counters.forEach(root::addProperty);
        try {
            Files.writeString(file, root.toString(), StandardCharsets.UTF_8);
            dirty = false;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(
                    Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            root.entrySet().forEach(entry ->
                    counters.put(entry.getKey(), entry.getValue().getAsLong()));
        } catch (IOException | RuntimeException ignored) {
            // A corrupt counter file loses history, never the server. Starting from zero
            // is a gap in a chart; refusing to start is an outage.
            counters.clear();
        }
    }
}
