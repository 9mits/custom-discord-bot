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

/** Daily login streaks in {@code login-streaks.json}. */
final class LoginStreakStore {
    static final class Row {
        int streak;
        int best;
        long lastClaimDay = -1L;
        int freezes;
        long activeDay = -1L;
        long activeMillis;
    }

    private static final class Data {
        Map<String, Row> players = new LinkedHashMap<>();
        /** The last UTC day each person, across linked accounts, claimed a reward. */
        Map<String, Long> ownerClaims = new LinkedHashMap<>();
    }

    private final Path file;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private Data data = new Data();

    LoginStreakStore(Path file) throws IOException {
        this.file = file;
        Files.createDirectories(file.getParent());
        if (Files.isRegularFile(file) && Files.size(file) > 0) {
            try {
                Data loaded = gson.fromJson(Files.readString(file, StandardCharsets.UTF_8), Data.class);
                if (loaded != null) data = loaded;
            } catch (RuntimeException exception) {
                throw new IOException("Login streak store is unreadable", exception);
            }
        }
        if (data.players == null) data.players = new LinkedHashMap<>();
        if (data.ownerClaims == null) data.ownerClaims = new LinkedHashMap<>();
    }

    synchronized Row row(UUID playerId) {
        return data.players.computeIfAbsent(playerId.toString(), ignored -> new Row());
    }

    synchronized LoginStreakRules.State state(UUID playerId) {
        Row row = row(playerId);
        return new LoginStreakRules.State(row.streak, row.best, row.lastClaimDay, row.freezes);
    }

    synchronized void save(UUID playerId, LoginStreakRules.State state) {
        Row row = row(playerId);
        row.streak = state.streak();
        row.best = state.best();
        row.lastClaimDay = state.lastClaimDay();
        row.freezes = state.freezes();
    }

    synchronized long ownerClaimDay(String ownerKey) {
        return data.ownerClaims.getOrDefault(ownerKey, -1L);
    }

    synchronized void claimForOwner(String ownerKey, long day) {
        data.ownerClaims.put(ownerKey, day);
    }

    synchronized void persist() {
        try {
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, gson.toJson(data), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
