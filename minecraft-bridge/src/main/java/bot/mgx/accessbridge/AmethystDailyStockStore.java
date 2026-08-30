package bot.mgx.accessbridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * What the Amethyst shelf is selling today, kept across restarts.
 *
 * <p>Persisted for two reasons rather than one: a restart must not reroll the listing
 * - that would let anybody who missed the totem restart their way to another one -
 * and it must not forget how many have already been sold.
 */
final class AmethystDailyStockStore {
    private final Path file;
    private AmethystDailyStock stock;

    AmethystDailyStockStore(Path file) throws IOException {
        this.file = file;
        Files.createDirectories(file.getParent());
        if (!Files.isRegularFile(file) || Files.size(file) == 0L) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (!root.has("reward_id")) {
                return;
            }
            stock = new AmethystDailyStock(
                    root.get("reward_id").getAsString(),
                    root.has("stock") ? root.get("stock").getAsInt() : 0,
                    root.has("rolled_at") ? root.get("rolled_at").getAsLong() : 0L,
                    root.has("next_roll_at") ? root.get("next_roll_at").getAsLong() : 0L
            );
        } catch (RuntimeException exception) {
            throw new IOException("Amethyst daily stock store is unreadable", exception);
        }
    }

    synchronized Optional<AmethystDailyStock> current() {
        return Optional.ofNullable(stock);
    }

    synchronized void put(AmethystDailyStock replacement) {
        AmethystDailyStock before = stock;
        stock = replacement;
        try {
            save();
        } catch (RuntimeException exception) {
            stock = before;
            throw exception;
        }
    }

    private void save() {
        JsonObject root = new JsonObject();
        if (stock != null) {
            root.addProperty("reward_id", stock.rewardId());
            root.addProperty("stock", stock.stock());
            root.addProperty("rolled_at", stock.rolledAt());
            root.addProperty("next_roll_at", stock.nextRollAt());
        }
        try {
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, root.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(
                        temporary, file,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
