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
 * <p>The Daily Crate opens once per UTC day, for anyone. The AFK Crate is opened with
 * openings that time online pays; they normally roll straight away, and only bank when a
 * player is busy or has turned auto roll off. Neither is ever an item, so neither can be
 * traded, stored, duplicated or sold.
 */
final class CratePassStore {
    enum Pass { DAILY, AFK }

    private static final class Row {
        int daily;
        int afk;
        /** The UTC day the Daily Crate was last opened, or -1. */
        long dailyDay = -1L;
        long previousDailyDay = -1L;
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

    synchronized boolean dailyReady(UUID player, long today) {
        Row row = rows.get(player.toString());
        return row == null || row.dailyDay != today;
    }

    /** Spends today's Daily Crate opening; false when it is already spent. */
    synchronized boolean claimDaily(UUID player, long today) {
        Row row = rows.computeIfAbsent(player.toString(), ignored -> new Row());
        if (row.dailyDay == today) return false;
        row.previousDailyDay = row.dailyDay;
        row.dailyDay = today;
        persist();
        return true;
    }

    /** Gives today's opening back after a failed spend. */
    synchronized void undoDaily(UUID player, long today) {
        Row row = rows.get(player.toString());
        if (row == null || row.dailyDay != today) return;
        row.dailyDay = row.previousDailyDay;
        persist();
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
