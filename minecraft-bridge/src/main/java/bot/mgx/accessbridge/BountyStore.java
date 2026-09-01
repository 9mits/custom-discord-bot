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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Money on a player's head. Stacks; paid to whoever lands the kill. */
final class BountyStore {
    static final long MIN_BOUNTY = 100L;
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


    record Entry(UUID target, long amount) {
    }

    private final Path file;
    private final ConcurrentHashMap<UUID, Long> bounties = new ConcurrentHashMap<>();

    BountyStore(Path file) throws IOException {
        this.file = file;
        Files.createDirectories(file.getParent());
        if (!Files.isRegularFile(file) || Files.size(file) == 0) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                long amount = Math.max(0L, entry.getValue().getAsLong());
                if (amount > 0L) {
                    bounties.put(UUID.fromString(entry.getKey()), amount);
                }
            }
        } catch (RuntimeException exception) {
            throw new IOException("Bounty store is unreadable", exception);
        }
    }

    long amountOn(UUID target) {
        return bounties.getOrDefault(target, 0L);
    }

    synchronized long add(UUID target, long amount) {
        return add(target, amount, true);
    }

    synchronized long add(UUID target, long amount, boolean enforceFloor) {
        if (amount <= 0L) {
            throw new IllegalArgumentException("The amount must be at least $1.");
        }
        long floor = (long) tuned("bounty.minimum", MIN_BOUNTY);
        if (enforceFloor && amount < floor) {
            throw new IllegalArgumentException(
                    "Bounties start at " + EconomyFormat.dollars(floor) + "."
            );
        }
        long before = amountOn(target);
        long total;
        try {
            total = Math.addExact(before, amount);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("That bounty has reached the value limit.", exception);
        }
        bounties.put(target, total);
        try {
            persist();
        } catch (RuntimeException failure) {
            restore(target, before);
            throw failure;
        }
        return total;
    }

    synchronized boolean canAdd(UUID target, long amount) {
        if (amount <= 0L) {
            return false;
        }
        try {
            Math.addExact(amountOn(target), amount);
            return true;
        } catch (ArithmeticException ignored) {
            return false;
        }
    }

    synchronized long collect(UUID target) {
        Long amount = bounties.remove(target);
        if (amount == null || amount <= 0L) {
            return 0L;
        }
        try {
            persist();
        } catch (RuntimeException failure) {
            bounties.put(target, amount);
            throw failure;
        }
        return amount;
    }

    synchronized List<Entry> ranked() {
        List<Entry> rows = new ArrayList<>();
        bounties.forEach((target, amount) -> {
            if (amount > 0L) {
                rows.add(new Entry(target, amount));
            }
        });
        rows.sort(Comparator.comparingLong(Entry::amount).reversed());
        return rows;
    }

    synchronized int clearAll() {
        int cleared = bounties.size();
        if (cleared == 0) {
            return 0;
        }
        Map<UUID, Long> before = Map.copyOf(bounties);
        bounties.clear();
        try {
            persist();
        } catch (RuntimeException failure) {
            bounties.putAll(before);
            throw failure;
        }
        return cleared;
    }

    private void restore(UUID target, long amount) {
        if (amount <= 0L) {
            bounties.remove(target);
        } else {
            bounties.put(target, amount);
        }
    }

    private void persist() {
        JsonObject root = new JsonObject();
        bounties.forEach((target, amount) -> {
            if (amount > 0L) {
                root.addProperty(target.toString(), amount);
            }
        });
        try {
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, root.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
