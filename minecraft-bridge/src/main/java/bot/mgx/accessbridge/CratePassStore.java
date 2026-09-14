package bot.mgx.accessbridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Openings for the crates nobody needs a key for: the Daily Crate and the AFK Crate.
 *
 * <p>An opening is a number on the player, never an item, so it cannot be traded, stored,
 * duplicated or sold. That is the point of both crates: the only way to open one is to
 * have logged in today or to have stayed on the server, yourself. Each kind has a banking
 * cap, so openings cannot be hoarded for a month and spent at once.
 */
final class CratePassStore {
    enum Pass { DAILY, AFK }

    private static final class Row {
        int daily;
        int afk;
    }

    private final Path file;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private Map<String, Row> rows = new LinkedHashMap<>();

    CratePassStore(Path file) throws IOException {
        this.file = file;
        Files.createDirectories(file.getParent());
        if (Files.isRegularFile(file) && Files.size(file) > 0) {
            try {
                Map<String, Row> loaded = gson.fromJson(Files.readString(file, StandardCharsets.UTF_8),
                        new com.google.gson.reflect.TypeToken<LinkedHashMap<String, Row>>() { }.getType());
                if (loaded != null) rows = loaded;
            } catch (RuntimeException exception) {
                throw new IOException("Crate openings are unreadable", exception);
            }
        }
    }

    synchronized int count(UUID player, Pass pass) {
        Row row = rows.get(player.toString());
        if (row == null) return 0;
        return Math.max(0, pass == Pass.DAILY ? row.daily : row.afk);
    }

    /** Adds openings up to {@code cap}; returns how many were actually added. */
    synchronized int add(UUID player, Pass pass, int amount, int cap) {
        int current = count(player, pass);
        int added = Math.max(0, Math.min(amount, Math.max(0, cap) - current));
        if (added > 0) set(player, pass, current + added);
        return added;
    }

    /** Takes up to {@code amount} openings; returns how many were taken. */
    synchronized int take(UUID player, Pass pass, int amount) {
        int current = count(player, pass);
        int taken = Math.max(0, Math.min(amount, current));
        if (taken > 0) set(player, pass, current - taken);
        return taken;
    }

    /** Hands openings back after a failed spend; never limited by the cap. */
    synchronized void refund(UUID player, Pass pass, int amount) {
        if (amount > 0) set(player, pass, count(player, pass) + amount);
    }

    private void set(UUID player, Pass pass, int value) {
        Row row = rows.computeIfAbsent(player.toString(), ignored -> new Row());
        if (pass == Pass.DAILY) row.daily = value;
        else row.afk = value;
        persist();
    }

    synchronized void persist() {
        try {
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, gson.toJson(rows), StandardCharsets.UTF_8);
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
