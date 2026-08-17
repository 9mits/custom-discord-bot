package bot.mgx.accessbridge;

import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Something a player did in game, on its way to the Discord activity log.
 *
 * <p>Discord already logs everything that happens through its own commands and panels.
 * This is the other half: the same actions taken from inside Minecraft, which
 * previously left no trace anywhere.
 *
 * <p>Deliberately free of Bukkit imports so it can be unit tested, and deliberately
 * carries a rendered {@code summary} rather than leaving the bot to reassemble one —
 * the server knows what happened, so it says so once and the bot just prints it.
 */
record ServerEvent(
        String event,
        String category,
        UUID actor,
        String actorName,
        String summary,
        Map<String, String> details,
        long occurredAt
) {
    static final String CATEGORY_CLAN = "clan";
    static final String CATEGORY_STAFF = "staff";
    static final String CATEGORY_ADMIN = "admin";
    static final String CATEGORY_ECONOMY = "economy";

    private static final int SUMMARY_LIMIT = 300;
    private static final int DETAIL_LIMIT = 200;
    private static final int DETAIL_COUNT_LIMIT = 10;

    ServerEvent {
        event = trim(event, 64);
        category = trim(category, 32);
        actorName = trim(actorName, 32);
        summary = trim(summary, SUMMARY_LIMIT);
        details = details == null ? Map.of() : Map.copyOf(details);
        occurredAt = occurredAt <= 0 ? System.currentTimeMillis() / 1000L : occurredAt;
    }

    static Builder of(
            String event, String category, UUID actor, String actorName, Consumer<ServerEvent> sink
    ) {
        return new Builder(event, category, actor, actorName, sink);
    }

    JsonObject toPayload() {
        JsonObject payload = new JsonObject();
        payload.addProperty("event", event);
        payload.addProperty("category", category);
        payload.addProperty("actor_uuid", actor == null ? "" : actor.toString());
        payload.addProperty("actor_name", actorName);
        payload.addProperty("summary", summary);
        payload.addProperty("occurred_at", occurredAt);
        JsonObject detail = new JsonObject();
        int written = 0;
        for (Map.Entry<String, String> entry : details.entrySet()) {
            if (written++ >= DETAIL_COUNT_LIMIT) {
                break;
            }
            detail.addProperty(trim(entry.getKey(), 32), trim(entry.getValue(), DETAIL_LIMIT));
        }
        payload.add("details", detail);
        return payload;
    }

    private static String trim(String value, int limit) {
        if (value == null) {
            return "";
        }
        String cleaned = value.strip();
        return cleaned.length() <= limit ? cleaned : cleaned.substring(0, limit);
    }

    /**
     * Keeps call sites to one readable line instead of a seven-argument constructor.
     *
     * <p>{@link #record()} is the terminal call: a builder that is never recorded sends
     * nothing, so every chain has to end in one.
     */
    static final class Builder {
        private final String event;
        private final String category;
        private final UUID actor;
        private final String actorName;
        private final Consumer<ServerEvent> sink;
        private final Map<String, String> details = new LinkedHashMap<>();
        private String summary = "";

        private Builder(
                String event, String category, UUID actor, String actorName, Consumer<ServerEvent> sink
        ) {
            this.event = event;
            this.category = category;
            this.actor = actor;
            this.actorName = actorName;
            this.sink = sink;
        }

        Builder summary(String value) {
            this.summary = value;
            return this;
        }

        Builder detail(String name, String value) {
            if (name != null && !name.isBlank() && value != null && !value.isBlank()) {
                details.put(name, value);
            }
            return this;
        }

        Builder detail(String name, long value) {
            return detail(name, String.format("%,d", value));
        }

        ServerEvent build() {
            return new ServerEvent(
                    event, category, actor, actorName, summary, details,
                    System.currentTimeMillis() / 1000L
            );
        }

        void record() {
            sink.accept(build());
        }
    }
}
