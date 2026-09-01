package bot.mgx.accessbridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * What has happened in game lately, kept so it can be read from outside the game.
 *
 * <p>Every in-game action already reports itself, but only outward: each one is written
 * to a Discord channel and then forgotten. Nothing held onto them, so there was no way to
 * ask what had been going on without scrolling several channels — and no way at all if
 * the bridge had been down when it happened.
 *
 * <p>Deliberately bounded and in memory. This is a window on the last little while, not
 * an archive: the durable record is still the Discord log, and a server should not carry
 * a file it never prunes for the sake of a panel.
 */
final class ActivityFeed {
    /** Entries kept before the oldest is dropped. */
    static final int RETAINED = 300;
    /** Live tuning; the constants above stay the defaults and stand alone in tests. */
    private static volatile java.util.function.ToDoubleFunction<String> tuning = key -> Double.NaN;

    static void tuningSource(java.util.function.ToDoubleFunction<String> source) {
        if (source != null) {
            tuning = source;
        }
    }

    private static double tuned(String key, double fallback) {
        double value = tuning.applyAsDouble(key);
        return Double.isNaN(value) ? fallback : value;
    }

    private static int retained() {
        return (int) tuned("activity-feed.retained", RETAINED);
    }


    private final Deque<ServerEvent> entries = new ArrayDeque<>();

    synchronized void record(ServerEvent event) {
        if (event == null) {
            return;
        }
        entries.addFirst(event);
        while (entries.size() > retained()) {
            entries.removeLast();
        }
    }

    /** Every category seen so far, so the panel offers only filters that match something. */
    synchronized Set<String> categories() {
        Set<String> seen = new LinkedHashSet<>();
        for (ServerEvent event : entries) {
            String category = event.category();
            if (category != null && !category.isBlank()) {
                seen.add(category.toLowerCase(Locale.ROOT));
            }
        }
        return seen;
    }

    synchronized JsonObject snapshot() {
        JsonObject root = new JsonObject();
        JsonArray rows = new JsonArray();
        for (ServerEvent event : entries) {
            JsonObject row = new JsonObject();
            row.addProperty("event", event.event());
            row.addProperty("category", event.category() == null
                    ? "" : event.category().toLowerCase(Locale.ROOT));
            row.addProperty("actor", event.actorName() == null ? "" : event.actorName());
            row.addProperty("summary", event.summary());
            row.addProperty("at", event.occurredAt());
            if (event.details() != null && !event.details().isEmpty()) {
                JsonObject details = new JsonObject();
                event.details().forEach(details::addProperty);
                row.add("details", details);
            }
            rows.add(row);
        }
        root.add("entries", rows);
        JsonArray categories = new JsonArray();
        categories().forEach(categories::add);
        root.add("categories", categories);
        root.addProperty("retained", retained());
        return root;
    }
}
