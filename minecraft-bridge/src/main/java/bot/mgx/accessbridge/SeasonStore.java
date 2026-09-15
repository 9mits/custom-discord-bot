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
        /** Quest line key to this season's running total; levels are read from it. */
        Map<String, Long> quests = new LinkedHashMap<>();
        /** Quest line key to how many of its levels have been paid, so a retuned ladder pays exactly once. */
        Map<String, Integer> questPaid = new LinkedHashMap<>();
        int owedShards;
        /** Season numbers of podium Giftbags waiting for an offline winner. */
        List<Integer> owedGiftbagSeasons = new ArrayList<>();
        /** Lifetime totals by quest objective plus active minutes: how boards learn what a player does. */
        Map<String, Long> activity = new LinkedHashMap<>();
        Board daily;
        Board weekly;
        /** The UTC day being counted toward the weekly return quest, and its active minutes so far. */
        long activeDay = -1L;
        int activeDayMinutes;
        /** Season XP earned while offline, such as a community goal or a referral, paid at the next join. */
        long owedXp;
    }

    /** One quest on a daily or weekly board. */
    static final class Slot {
        String objective = "";
        String goal = "";
        long target;
        long progress;
        boolean done;
    }

    /** A daily or weekly board, dealt for one period. */
    static final class Board {
        long period = Long.MIN_VALUE;
        List<Slot> slots = new ArrayList<>();
        boolean swept;
    }

    /** The server-wide goal for one week. */
    static final class Community {
        long week = Long.MIN_VALUE;
        String objective = "";
        long target;
        long progress;
        boolean completed;
        /** The highest quarter already announced, so each milestone is said once. */
        int announcedQuarter;
        Map<String, Long> contributions = new LinkedHashMap<>();
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
        /** Most active players seen in each recent UTC hour, keyed by epoch hour; sizes the Rally. */
        Map<String, Integer> hourlyPeaks = new LinkedHashMap<>();
        /** Server totals by objective for the week being counted, and the one before it. */
        long totalsWeek = Long.MIN_VALUE;
        Map<String, Long> weekTotals = new LinkedHashMap<>();
        Map<String, Long> lastWeekTotals = new LinkedHashMap<>();
        List<String> weekPlayers = new ArrayList<>();
        int lastWeekPlayers;
        Community community = new Community();
        long lastRallyPingAt;
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
        if (data.hourlyPeaks == null) data.hourlyPeaks = new LinkedHashMap<>();
        if (data.weekTotals == null) data.weekTotals = new LinkedHashMap<>();
        if (data.lastWeekTotals == null) data.lastWeekTotals = new LinkedHashMap<>();
        if (data.weekPlayers == null) data.weekPlayers = new ArrayList<>();
        if (data.community == null) data.community = new Community();
        if (data.community.contributions == null) data.community.contributions = new LinkedHashMap<>();
        for (Row row : data.players.values()) {
            if (row.activity == null) row.activity = new LinkedHashMap<>();
            if (row.quests == null) row.quests = new LinkedHashMap<>();
            if (row.questPaid == null) row.questPaid = new LinkedHashMap<>();
            if (row.owedGiftbagSeasons == null) row.owedGiftbagSeasons = new ArrayList<>();
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
            row.quests.clear();
            row.questPaid.clear();
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

    /** Keeps one peak per UTC hour for the last week. */
    synchronized void recordActive(long epochHour, int active) {
        String key = Long.toString(epochHour);
        data.hourlyPeaks.merge(key, active, Math::max);
        data.hourlyPeaks.keySet().removeIf(hour -> {
            try {
                return Long.parseLong(hour) <= epochHour - 168L;
            } catch (NumberFormatException invalid) {
                return true;
            }
        });
    }

    synchronized List<Integer> hourlyPeaks() {
        return List.copyOf(data.hourlyPeaks.values());
    }

    /**
     * Rolls the server's weekly totals when a new week begins. Returns whether it rolled,
     * so the caller can set the week's community goal from the totals just archived.
     */
    synchronized boolean rollWeek(long week) {
        if (data.totalsWeek == week) return false;
        // Only a consecutive week is "last week"; after a long gap there is no recent history.
        boolean consecutive = data.totalsWeek == week - 1L;
        data.lastWeekTotals = consecutive ? new LinkedHashMap<>(data.weekTotals) : new LinkedHashMap<>();
        data.lastWeekPlayers = consecutive ? data.weekPlayers.size() : 0;
        data.weekTotals.clear();
        data.weekPlayers.clear();
        data.totalsWeek = week;
        return true;
    }

    synchronized void addWeekTotal(String objective, long amount) {
        data.weekTotals.merge(objective, amount, Long::sum);
    }

    synchronized void markWeekPlayer(UUID playerId) {
        String id = playerId.toString();
        if (!data.weekPlayers.contains(id)) data.weekPlayers.add(id);
    }

    synchronized long lastWeekTotal(String objective) {
        return data.lastWeekTotals.getOrDefault(objective, 0L);
    }

    synchronized int lastWeekPlayers() {
        return data.lastWeekPlayers;
    }

    synchronized Community community() {
        return data.community;
    }

    synchronized void community(Community community) {
        data.community = community;
    }

    synchronized long lastRallyPingAt() {
        return data.lastRallyPingAt;
    }

    synchronized void lastRallyPingAt(long at) {
        data.lastRallyPingAt = at;
    }

    synchronized Map<UUID, Row> rows() {
        Map<UUID, Row> rows = new LinkedHashMap<>();
        data.players.forEach((id, row) -> {
            try {
                rows.put(UUID.fromString(id), row);
            } catch (IllegalArgumentException invalid) {
                // skipped
            }
        });
        return rows;
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
