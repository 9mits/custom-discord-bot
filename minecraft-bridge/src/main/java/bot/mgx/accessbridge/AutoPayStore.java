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
 * Standing instructions to forward money to another account.
 *
 * <p>Built for funding an alt that keeps a farm running, so the target is stored
 * by UUID and does not have to be online — unlike {@code /pay}, which requires a
 * player standing in front of you.
 *
 * <p>Free of Bukkit imports so it can be unit tested.
 */
final class AutoPayStore {
    /** Fast enough for a farm, slow enough not to be a transfer every tick. */
    static final int MINIMUM_INTERVAL_SECONDS = 5;
    static final int MAXIMUM_INTERVAL_SECONDS = 3_600;
    static final int DEFAULT_INTERVAL_SECONDS = 30;

    /** @param amount ignored when {@code sendAll} is set */
    record Plan(UUID target, String targetName, long amount, int intervalSeconds, boolean sendAll) {
        Plan {
            if (target == null) {
                throw new IllegalArgumentException("Autopay needs somebody to pay.");
            }
            if (intervalSeconds < MINIMUM_INTERVAL_SECONDS
                    || intervalSeconds > MAXIMUM_INTERVAL_SECONDS) {
                throw new IllegalArgumentException(
                        "Interval must be between " + MINIMUM_INTERVAL_SECONDS + " and "
                                + MAXIMUM_INTERVAL_SECONDS + " seconds."
                );
            }
            if (!sendAll && amount < 1L) {
                throw new IllegalArgumentException("Amount must be at least $1, or use 'all'.");
            }
        }

        long intervalMillis() {
            return intervalSeconds * 1_000L;
        }
    }

    private final Path file;
    private final Map<UUID, Plan> plans = new LinkedHashMap<>();

    AutoPayStore(Path file) throws IOException {
        this.file = file;
        Files.createDirectories(file.getParent());
        if (!Files.isRegularFile(file) || Files.size(file) == 0L) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                try {
                    JsonObject value = entry.getValue().getAsJsonObject();
                    plans.put(UUID.fromString(entry.getKey()), new Plan(
                            UUID.fromString(value.get("target").getAsString()),
                            value.has("target_name") ? value.get("target_name").getAsString() : "",
                            value.has("amount") ? value.get("amount").getAsLong() : 0L,
                            value.get("interval").getAsInt(),
                            value.has("send_all") && value.get("send_all").getAsBoolean()
                    ));
                } catch (RuntimeException ignored) {
                    // One bad row must not cost everybody else their setup.
                }
            }
        } catch (RuntimeException exception) {
            plans.clear();
        }
    }

    synchronized Optional<Plan> plan(UUID payer) {
        return Optional.ofNullable(plans.get(payer));
    }

    synchronized Map<UUID, Plan> all() {
        return Map.copyOf(plans);
    }

    synchronized void set(UUID payer, Plan plan) {
        if (payer.equals(plan.target())) {
            // Paying yourself in a loop is a busy no-op that looks like a bug.
            throw new IllegalArgumentException("You cannot pay yourself.");
        }
        Plan previous = plans.put(payer, plan);
        try {
            persist();
        } catch (RuntimeException failure) {
            if (previous == null) {
                plans.remove(payer);
            } else {
                plans.put(payer, previous);
            }
            throw failure;
        }
    }

    /** @return true when there was something to stop */
    synchronized boolean clear(UUID payer) {
        Plan previous = plans.remove(payer);
        if (previous == null) {
            return false;
        }
        try {
            persist();
        } catch (RuntimeException failure) {
            plans.put(payer, previous);
            throw failure;
        }
        return true;
    }

    private void persist() {
        JsonObject root = new JsonObject();
        plans.forEach((payer, plan) -> {
            JsonObject value = new JsonObject();
            value.addProperty("target", plan.target().toString());
            value.addProperty("target_name", plan.targetName());
            value.addProperty("amount", plan.amount());
            value.addProperty("interval", plan.intervalSeconds());
            value.addProperty("send_all", plan.sendAll());
            root.add(payer.toString(), value);
        });
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
            throw new UncheckedIOException("Could not save autopay", exception);
        }
    }
}
