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
        if (amount < MIN_BOUNTY) {
            throw new IllegalArgumentException(
                    "Bounties start at " + EconomyFormat.dollars(MIN_BOUNTY) + "."
            );
        }
        long total = bounties.merge(target, amount, Long::sum);
        persist();
        return total;
    }

    synchronized long collect(UUID target) {
        Long amount = bounties.remove(target);
        if (amount == null || amount <= 0L) {
            return 0L;
        }
        persist();
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
        bounties.clear();
        persist();
        return cleared;
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
