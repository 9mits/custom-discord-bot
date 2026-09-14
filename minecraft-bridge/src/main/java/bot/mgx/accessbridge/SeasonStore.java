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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** The running season, every player's pass progress, and past season podiums, in {@code season-pass.json}. */
final class SeasonStore {
    static final class Row {
        String name = "";
        long xp;
        int grantedTier;
        long dailyPeriod = -1L;
        Map<String, Long> daily = new LinkedHashMap<>();
        Set<String> dailyDone = new LinkedHashSet<>();
        long weeklyPeriod = -1L;
        Map<String, Long> weekly = new LinkedHashMap<>();
        Set<String> weeklyDone = new LinkedHashSet<>();
        int owedShards;
    }

    static final class Podium {
        int season;
        long endedDay;
        List<String> names = new ArrayList<>();
        List<Long> xp = new ArrayList<>();
    }

    private static final class Data {
        int season;
        long startedDay = -1L;
        long endsDay = -1L;
        Map<String, Row> players = new LinkedHashMap<>();
        List<Podium> history = new ArrayList<>();
        /** Season Hearts earned this season. A new season clears them. */
        Map<String, Integer> hearts = new LinkedHashMap<>();
    }

    private final Path file;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private Data data = new Data();

    SeasonStore(Path file) throws IOException {
        this.file = file;
        Files.createDirectories(file.getParent());
        if (Files.isRegularFile(file) && Files.size(file) > 0) {
            try {
                Data loaded = gson.fromJson(Files.readString(file, StandardCharsets.UTF_8), Data.class);
                if (loaded != null) data = loaded;
            } catch (RuntimeException exception) {
                throw new IOException("Season pass store is unreadable", exception);
            }
        }
        if (data.players == null) data.players = new LinkedHashMap<>();
        if (data.history == null) data.history = new ArrayList<>();
        if (data.hearts == null) data.hearts = new LinkedHashMap<>();
        for (Row row : data.players.values()) {
            if (row.daily == null) row.daily = new LinkedHashMap<>();
            if (row.dailyDone == null) row.dailyDone = new LinkedHashSet<>();
            if (row.weekly == null) row.weekly = new LinkedHashMap<>();
            if (row.weeklyDone == null) row.weeklyDone = new LinkedHashSet<>();
            if (row.name == null) row.name = "";
        }
    }

    synchronized int season() {
        return data.season;
    }

    synchronized long startedDay() {
        return data.startedDay;
    }

    synchronized long endsDay() {
        return data.endsDay;
    }

    synchronized void startSeason(int season, long today, long endsDay) {
        data.season = season;
        data.startedDay = today;
        data.endsDay = endsDay;
        for (Row row : data.players.values()) {
            row.xp = 0L;
            row.grantedTier = 0;
        }
        // Hearts belong to the season that paid them, so everybody starts the next one level.
        data.hearts.clear();
    }

    synchronized int hearts(UUID playerId) {
        return Math.max(0, data.hearts.getOrDefault(playerId.toString(), 0));
    }

    /** Adds up to {@code amount} hearts without passing {@code cap}; returns how many were added. */
    synchronized int addHearts(UUID playerId, int amount, int cap) {
        int current = hearts(playerId);
        int added = Math.max(0, Math.min(amount, cap - current));
        if (added > 0) data.hearts.put(playerId.toString(), current + added);
        return added;
    }

    synchronized void setHearts(UUID playerId, int hearts) {
        if (hearts <= 0) data.hearts.remove(playerId.toString());
        else data.hearts.put(playerId.toString(), hearts);
    }

    synchronized Row row(UUID playerId) {
        return data.players.computeIfAbsent(playerId.toString(), ignored -> new Row());
    }

    /** Players by season XP, highest first, as (id, row). */
    synchronized List<Map.Entry<UUID, Row>> ranking(int limit) {
        return data.players.entrySet().stream()
                .filter(entry -> entry.getValue().xp > 0L)
                .sorted((left, right) -> Long.compare(right.getValue().xp, left.getValue().xp))
                .limit(limit)
                .map(entry -> Map.entry(UUID.fromString(entry.getKey()), entry.getValue()))
                .toList();
    }

    synchronized void archive(Podium podium) {
        data.history.add(podium);
        while (data.history.size() > 20) data.history.remove(0);
    }

    synchronized List<Podium> history() {
        return List.copyOf(data.history);
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
