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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Player cash. The richest boards read this store. {@link WealthStore} is leftover
 * item-value data and is no longer what those boards rank.
 *
 * <p>Every mutation is written immediately. A crash mid-trade must not reprint money.
 */
final class EconomyStore {
    private final Path file;
    private final ConcurrentHashMap<UUID, Long> balances = new ConcurrentHashMap<>();

    EconomyStore(Path file) throws IOException {
        this.file = file;
        Files.createDirectories(file.getParent());
        if (!Files.isRegularFile(file) || Files.size(file) == 0) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                balances.put(UUID.fromString(entry.getKey()), Math.max(0L, entry.getValue().getAsLong()));
            }
        } catch (RuntimeException exception) {
            throw new IOException("Economy store is unreadable", exception);
        }
    }

    long balance(UUID playerId) {
        return balances.getOrDefault(playerId, 0L);
    }

    /** A copy of every non-zero wallet, for the richest-player board. */
    Map<UUID, Long> snapshots() {
        return Map.copyOf(balances);
    }

    long totalOf(Iterable<UUID> players) {
        long total = 0L;
        for (UUID playerId : players) {
            total += balance(playerId);
        }
        return total;
    }

    synchronized void deposit(UUID playerId, long amount) {
        if (amount <= 0L) {
            throw new IllegalArgumentException("Deposit must be a positive amount.");
        }
        balances.merge(playerId, amount, Long::sum);
        persist();
    }

    synchronized boolean tryWithdraw(UUID playerId, long amount) {
        if (amount <= 0L) {
            throw new IllegalArgumentException("Withdrawal must be a positive amount.");
        }
        long current = balance(playerId);
        if (current < amount) {
            return false;
        }
        balances.put(playerId, current - amount);
        persist();
        return true;
    }

    synchronized boolean transfer(UUID from, UUID to, long amount) {
        if (from.equals(to)) {
            throw new IllegalArgumentException("You cannot pay yourself.");
        }
        if (!tryWithdrawUnlocked(from, amount)) {
            return false;
        }
        balances.merge(to, amount, Long::sum);
        persist();
        return true;
    }

    synchronized void set(UUID playerId, long amount) {
        if (amount < 0L) {
            throw new IllegalArgumentException("A balance cannot be negative.");
        }
        if (amount == 0L) {
            balances.remove(playerId);
        } else {
            balances.put(playerId, amount);
        }
        persist();
    }

    synchronized int clearAll() {
        int cleared = balances.size();
        if (cleared == 0) {
            return 0;
        }
        balances.clear();
        persist();
        return cleared;
    }

    private boolean tryWithdrawUnlocked(UUID playerId, long amount) {
        long current = balance(playerId);
        if (current < amount) {
            return false;
        }
        balances.put(playerId, current - amount);
        return true;
    }

    private void persist() {
        JsonObject root = new JsonObject();
        balances.forEach((playerId, value) -> {
            if (value > 0L) {
                root.addProperty(playerId.toString(), value);
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
