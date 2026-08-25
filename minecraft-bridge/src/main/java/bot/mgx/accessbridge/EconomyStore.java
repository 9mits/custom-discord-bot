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
import java.util.Objects;
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
    private volatile Runnable changeListener = () -> {
    };

    EconomyStore(Path file) throws IOException {
        this.file = file;
        Files.createDirectories(file.getParent());
        if (!Files.isRegularFile(file) || Files.size(file) == 0) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                long balance = Math.max(0L, entry.getValue().getAsLong());
                if (balance > 0L) {
                    balances.put(UUID.fromString(entry.getKey()), balance);
                }
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

    void onChange(Runnable listener) {
        changeListener = Objects.requireNonNull(listener, "listener");
    }

    long totalOf(Iterable<UUID> players) {
        long total = 0L;
        for (UUID playerId : players) {
            total = Math.addExact(total, balance(playerId));
        }
        return total;
    }

    synchronized void deposit(UUID playerId, long amount) {
        if (amount <= 0L) {
            throw new IllegalArgumentException("Deposit must be a positive amount.");
        }
        long before = balance(playerId);
        long after = checkedAdd(before, amount);
        balances.put(playerId, after);
        try {
            persist();
        } catch (RuntimeException failure) {
            restoreBalance(playerId, before);
            throw failure;
        }
        changed();
    }

    synchronized boolean tryWithdraw(UUID playerId, long amount) {
        if (amount <= 0L) {
            throw new IllegalArgumentException("Withdrawal must be a positive amount.");
        }
        long current = balance(playerId);
        if (current < amount) {
            return false;
        }
        restoreBalance(playerId, current - amount);
        try {
            persist();
        } catch (RuntimeException failure) {
            restoreBalance(playerId, current);
            throw failure;
        }
        changed();
        return true;
    }

    synchronized boolean transfer(UUID from, UUID to, long amount) {
        if (from.equals(to)) {
            throw new IllegalArgumentException("You cannot pay yourself.");
        }
        return tryPayment(from, to, amount, amount);
    }

    /** Charges one wallet and credits another in one persisted economy transaction. */
    synchronized boolean tryPayment(UUID from, UUID to, long charged, long credited) {
        if (from.equals(to)) {
            throw new IllegalArgumentException("The payer and recipient must be different.");
        }
        if (charged <= 0L || credited < 0L || credited > charged) {
            throw new IllegalArgumentException("Payment amounts are invalid.");
        }
        long fromBefore = balance(from);
        if (fromBefore < charged) {
            return false;
        }
        long toBefore = balance(to);
        long toAfter = checkedAdd(toBefore, credited);
        restoreBalance(from, fromBefore - charged);
        restoreBalance(to, toAfter);
        try {
            persist();
        } catch (RuntimeException failure) {
            restoreBalance(from, fromBefore);
            restoreBalance(to, toBefore);
            throw failure;
        }
        changed();
        return true;
    }

    synchronized boolean canDeposit(UUID playerId, long amount) {
        if (amount <= 0L) {
            return false;
        }
        try {
            Math.addExact(balance(playerId), amount);
            return true;
        } catch (ArithmeticException ignored) {
            return false;
        }
    }

    synchronized void set(UUID playerId, long amount) {
        if (amount < 0L) {
            throw new IllegalArgumentException("A balance cannot be negative.");
        }
        long before = balance(playerId);
        restoreBalance(playerId, amount);
        try {
            persist();
        } catch (RuntimeException failure) {
            restoreBalance(playerId, before);
            throw failure;
        }
        changed();
    }

    synchronized int clearAll() {
        int cleared = balances.size();
        if (cleared == 0) {
            return 0;
        }
        Map<UUID, Long> before = Map.copyOf(balances);
        balances.clear();
        try {
            persist();
        } catch (RuntimeException failure) {
            balances.putAll(before);
            throw failure;
        }
        changed();
        return cleared;
    }

    private static long checkedAdd(long current, long amount) {
        try {
            return Math.addExact(current, amount);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("That wallet has reached the balance limit.", exception);
        }
    }

    private void restoreBalance(UUID playerId, long amount) {
        if (amount == 0L) {
            balances.remove(playerId);
        } else {
            balances.put(playerId, amount);
        }
    }

    private void changed() {
        try {
            changeListener.run();
        } catch (RuntimeException ignored) {
            // A leaderboard refresh is advisory and must never roll back saved money.
        }
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
