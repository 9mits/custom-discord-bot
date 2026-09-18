package bot.mgx.accessbridge;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Daily and weekly quests, the community goal, Rallies and catch-up: the parts of the
 * Season Pass that exist to make the server busier rather than to keep players busy.
 *
 * <p>Every mechanism here answers one measured problem on the live server (August 27 to
 * September 15, 2026 logs):
 * <ul>
 *   <li><b>Players do not come back.</b> 14 of 25 new players never played a second day,
 *       and daily players fell from about 30 to about 10. Daily and weekly boards give a
 *       reason to return tomorrow, and the weekly return quest asks for days played, not
 *       hours ground.</li>
 *   <li><b>Players play alone.</b> Every board carries a Together quest that only
 *       progresses with other people nearby or a clanmate online, and a Rally raises
 *       Season XP while the server is busy.</li>
 *   <li><b>Multiplayer features sit unused.</b> Ranked PvP, server events and the auction
 *       house get a fraction of the traffic of /shop. The Try Something slot deals the one
 *       a player has used least.</li>
 *   <li><b>Falling behind feels final.</b> A player below the season's pace earns quest XP
 *       faster until they catch up, which is what a new or returning player needs to stay.</li>
 * </ul>
 *
 * <p>Free of Bukkit so every choice is unit tested. Boards are dealt from a seed of the
 * player and the period, so a restart deals the same board and nobody can reroll one.
 */
final class SeasonQuestRules {
    /** Why a slot is on the board. Shown to players as the slot's name. */
    enum Goal {
        YOUR_GAME("Your Game"),
        TOGETHER("Together"),
        TRY_SOMETHING("Try Something"),
        COME_BACK("Come Back");

        final String label;

        Goal(String label) {
            this.label = label;
        }
    }

    enum Kind {
        /** What a player already does; dealt as Your Game and sized to their own pace. */
        GRIND,
        /** Only progresses with other players; the reason to log in when others are on. */
        SOCIAL,
        /** A multiplayer feature worth trying; dealt as Try Something. */
        FEATURE,
        /** Days played; the weekly return quest. */
        RETURN
    }

    /**
     * Everything a board quest can ask for.
     *
     * <p>Daily targets are about half an hour of focused play for a typical active player;
     * {@code perHour} is that pace, used to measure which activities a player favours.
     * Fishing and crate opening are deliberately absent: almost nobody fishes (5 of 49
     * regulars), and players hold keys by the hundred thousand, so neither quest would
     * change what anybody does.
     */
    enum Objective {
        KILL_MOBS("Defeat %s hostile mobs", "item/iron_sword", Kind.GRIND, 25, 120, 60),
        MINE_ORES("Mine %s ores", "item/iron_pickaxe", Kind.GRIND, 15, 70, 40),
        HARVEST_CROPS("Harvest %s fully grown crops", "item/wheat", Kind.GRIND, 40, 180, 120),
        TOGETHER_MINUTES("Play %s active minutes near other players", "item/cake", Kind.SOCIAL, 12, 60, 0),
        CLAN_MINUTES("Play %s active minutes with a clanmate online", "item/name_tag", Kind.SOCIAL, 15, 75, 0),
        JOIN_EVENT("Take part in %s server events", "item/amethyst_shard", Kind.FEATURE, 1, 2, 2),
        PLAY_PVP("Finish %s ranked PvP matches", "item/netherite_sword", Kind.FEATURE, 1, 3, 2),
        BUY_AUCTION("Buy %s items from players on /ah", "item/gold_ingot", Kind.FEATURE, 1, 3, 2),
        PLAY_DAYS("Play on %s different days", "item/clock_00", Kind.RETURN, 0, 2, 0);

        private final String template;
        final String sprite;
        final Kind kind;
        final long daily;
        final long weekly;
        final long perHour;

        Objective(String template, String sprite, Kind kind, long daily, long weekly, long perHour) {
            this.template = template;
            this.sprite = sprite;
            this.kind = kind;
            this.daily = daily;
            this.weekly = weekly;
            this.perHour = perHour;
        }

        String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        String label(long target) {
            String text = String.format(Locale.ROOT, template, String.format(Locale.ROOT, "%,d", target));
            // "Take part in 1 server events" reads as a typo on the one board it appears most.
            return target == 1 ? text.replace(" events", " event").replace(" matches", " match")
                    .replace(" items", " item").replace(" days", " day") : text;
        }

        static java.util.Optional<Objective> of(String key) {
            for (Objective objective : values()) {
                if (objective.key().equals(key)) return java.util.Optional.of(objective);
            }
            return java.util.Optional.empty();
        }
    }

    /** The activity key for minutes played, kept beside the objective totals. */
    static final String ACTIVE_MINUTES = "active_minutes";
    /** Minutes of history before a board trusts a player's own pace over the default. */
    static final long MINUTES_BEFORE_PERSONAL = 120L;
    static final double PERSONAL_MINIMUM = 0.6d;
    static final double PERSONAL_MAXIMUM = 2.5d;
    /** Community goals rotate through work every player can contribute to. */
    static final List<Objective> COMMUNITY = List.of(
            Objective.TOGETHER_MINUTES, Objective.KILL_MOBS, Objective.MINE_ORES, Objective.HARVEST_CROPS);

    /**
     * What a board is dealt from.
     *
     * @param activity lifetime totals by objective key, plus {@link #ACTIVE_MINUTES}
     * @param inClan   whether a clanmate exists to play with
     * @param pvpOpen  whether ranked PvP can be played at all
     */
    record Profile(Map<String, Long> activity, boolean inClan, boolean pvpOpen) {
        Profile {
            activity = activity == null ? Map.of() : Map.copyOf(activity);
        }

        long minutes() {
            return activity.getOrDefault(ACTIVE_MINUTES, 0L);
        }

        /** How much of this a player does against the typical pace; 0 for none. */
        double affinity(Objective objective) {
            if (objective.perHour <= 0L) return 0d;
            double hours = Math.max(1L, minutes()) / 60d;
            return activity.getOrDefault(objective.key(), 0L) / (objective.perHour * hours);
        }
    }

    private SeasonQuestRules() {
    }

    // ------------------------------------------------------------------ periods

    /** Boards reset at 00:00 UTC; the day number is the period. */
    static long dailyPeriod(long epochDay) {
        return epochDay;
    }

    /** Weeks start Monday 00:00 UTC. Epoch day 0 was a Thursday. */
    static long weeklyPeriod(long epochDay) {
        return Math.floorDiv(epochDay + 3L, 7L);
    }

    /** The first epoch day of the week after {@code week}, for "resets in". */
    static long nextWeekStartDay(long week) {
        return (week + 1L) * 7L - 3L;
    }

    // ------------------------------------------------------------------ boards

    /**
     * Today's three: the player's own game sized to their pace, a Together quest, and the
     * multiplayer feature they have tried least.
     */
    static List<SeasonStore.Slot> dealDaily(UUID playerId, long day, Profile profile) {
        Random random = seeded(playerId, day, 0x5EA50DL);
        List<SeasonStore.Slot> slots = new ArrayList<>();
        Objective game = yourGame(profile, random);
        slots.add(slot(game, Goal.YOUR_GAME, personalTarget(game, game.daily, profile)));
        Objective social = together(profile);
        slots.add(slot(social, Goal.TOGETHER, social.daily));
        Objective feature = leastTried(profile, random);
        slots.add(slot(feature, Goal.TRY_SOMETHING, feature.daily));
        return slots;
    }

    /**
     * This week's three: come back on several days, play together, and a multiplayer
     * feature. None of them rewards one long sitting over returning.
     */
    static List<SeasonStore.Slot> dealWeekly(UUID playerId, long week, Profile profile, long playDays) {
        Random random = seeded(playerId, week, 0x3EE1L);
        List<SeasonStore.Slot> slots = new ArrayList<>();
        slots.add(slot(Objective.PLAY_DAYS, Goal.COME_BACK, Math.max(1L, Math.min(7L, playDays))));
        Objective social = together(profile);
        slots.add(slot(social, Goal.TOGETHER, social.weekly));
        List<Objective> features = features(profile);
        Objective feature = features.get(random.nextInt(features.size()));
        slots.add(slot(feature, Goal.TRY_SOMETHING, feature.weekly));
        return slots;
    }

    /** A clan quest for a player with a clan, otherwise playing near anyone. */
    static Objective together(Profile profile) {
        return profile.inClan() ? Objective.CLAN_MINUTES : Objective.TOGETHER_MINUTES;
    }

    /**
     * The activity a player favours, relative to its typical pace. A newcomer with no
     * history gets one of the two most common activities on the server.
     */
    static Objective yourGame(Profile profile, Random random) {
        List<Objective> grind = List.of(Objective.KILL_MOBS, Objective.MINE_ORES, Objective.HARVEST_CROPS);
        List<Objective> ranked = grind.stream()
                .filter(objective -> profile.affinity(objective) > 0d)
                .sorted(Comparator.comparingDouble(profile::affinity).reversed())
                .toList();
        if (profile.minutes() < MINUTES_BEFORE_PERSONAL || ranked.isEmpty()) {
            return random.nextBoolean() ? Objective.KILL_MOBS : Objective.MINE_ORES;
        }
        // The top two, so a miner still sees a mob day now and then.
        return ranked.size() == 1 ? ranked.get(0) : ranked.get(random.nextInt(4) == 0 ? 1 : 0);
    }

    static List<Objective> features(Profile profile) {
        List<Objective> features = new ArrayList<>();
        for (Objective objective : Objective.values()) {
            if (objective.kind != Kind.FEATURE) continue;
            if (objective == Objective.PLAY_PVP && !profile.pvpOpen()) continue;
            features.add(objective);
        }
        return features;
    }

    /** The multiplayer feature a player has used least, ties broken by the seed. */
    static Objective leastTried(Profile profile, Random random) {
        List<Objective> features = new ArrayList<>(features(profile));
        java.util.Collections.shuffle(features, random);
        features.sort(Comparator.comparingDouble(profile::affinity));
        // Two lowest, so a player who ignores both PvP and the auction house sees each.
        return features.size() > 1 && random.nextInt(3) == 0 ? features.get(1) : features.get(0);
    }

    /**
     * A Your Game target sized to how fast this player actually does it: an efficient
     * grinder gets a real day's work, a slow one a goal they can still finish.
     */
    static long personalTarget(Objective objective, long base, Profile profile) {
        if (objective.kind != Kind.GRIND || profile.minutes() < MINUTES_BEFORE_PERSONAL) return base;
        double scale = Math.max(PERSONAL_MINIMUM, Math.min(PERSONAL_MAXIMUM, profile.affinity(objective)));
        return roundTarget(Math.round(base * scale));
    }

    /** Targets people read: 5s under a hundred, then 10s, 25s and 50s. */
    static long roundTarget(long value) {
        if (value <= 5L) return Math.max(1L, value);
        long step = value < 100L ? 5L : value < 250L ? 10L : value < 1_000L ? 25L : 50L;
        return Math.max(step, Math.round((double) value / step) * step);
    }

    private static SeasonStore.Slot slot(Objective objective, Goal goal, long target) {
        SeasonStore.Slot slot = new SeasonStore.Slot();
        slot.objective = objective.key();
        slot.goal = goal.name();
        slot.target = Math.max(1L, target);
        return slot;
    }

    private static Random seeded(UUID playerId, long period, long salt) {
        long seed = playerId.getMostSignificantBits() * 31L + playerId.getLeastSignificantBits();
        return new Random(seed ^ (period * 0x9E3779B97F4A7C15L) ^ salt);
    }

    /**
     * Adds progress to every unfinished slot for this objective and returns the slots it
     * finished. Progress never passes the target, so a board reads exactly done.
     */
    static List<SeasonStore.Slot> advance(SeasonStore.Board board, Objective objective, long amount) {
        List<SeasonStore.Slot> finished = new ArrayList<>();
        if (board == null || board.slots == null || amount <= 0L) return finished;
        for (SeasonStore.Slot slot : board.slots) {
            if (slot.done || !objective.key().equals(slot.objective)) continue;
            slot.progress = Math.min(slot.target, slot.progress + amount);
            if (slot.progress >= slot.target) {
                slot.done = true;
                finished.add(slot);
            }
        }
        return finished;
    }

    /** Whether the whole board is done and its sweep bonus not yet paid; marks it paid. */
    static boolean takeSweep(SeasonStore.Board board) {
        if (board == null || board.swept || board.slots == null || board.slots.isEmpty()) return false;
        if (board.slots.stream().allMatch(slot -> slot.done)) {
            board.swept = true;
            return true;
        }
        return false;
    }

    static long done(SeasonStore.Board board) {
        return board == null || board.slots == null ? 0L : board.slots.stream().filter(slot -> slot.done).count();
    }

    /**
     * Counts a day played once a player has been active for {@code minutesToCount} minutes
     * of it, so logging in and straight out is not a day. Returns whether this minute is
     * the one that made the day count.
     */
    static boolean countActiveMinute(SeasonStore.Row row, long today, int minutesToCount) {
        if (row.activeDay != today) {
            row.activeDay = today;
            row.activeDayMinutes = 0;
        }
        row.activeDayMinutes++;
        return row.activeDayMinutes == Math.max(1, minutesToCount);
    }

    // ------------------------------------------------------------------ catch-up

    /**
     * The tier the season's pace has reached today: {@code pacePercent} of the pass, spread
     * evenly over the season. A player below it earns quest XP faster. 0 disables catch-up.
     */
    static int paceTier(long today, long startedDay, long endsDay, int maximumTier, int pacePercent) {
        long length = endsDay - startedDay;
        if (pacePercent <= 0 || length <= 0L || today <= startedDay) return 0;
        double elapsed = Math.min(1d, (double) (today - startedDay) / length);
        return (int) Math.floor(maximumTier * Math.min(100, pacePercent) / 100d * elapsed);
    }

    static boolean catchingUp(int tier, int paceTier) {
        return tier < paceTier;
    }

    /** Applies whole-percent boosts one after the other, rounding once at the end. */
    static long boosted(long xp, int... percents) {
        double value = xp;
        for (int percent : percents) value = value * Math.max(100, percent) / 100d;
        return Math.round(value);
    }

    // ------------------------------------------------------------------ rallies

    /** Hourly peaks needed before the Rally threshold follows the server rather than the minimum. */
    static final int RALLY_SAMPLES = 12;

    /**
     * How many active players start a Rally: the busiest quarter of recent hours, never
     * below {@code minimum}. It follows the population both ways, so a quiet week can
     * still reach one and a growing server always has a next number to beat.
     */
    static int rallyThreshold(Collection<Integer> hourlyPeaks, int minimum) {
        List<Integer> peaks = hourlyPeaks.stream().filter(peak -> peak != null && peak > 0).sorted().toList();
        int floor = Math.max(2, minimum);
        if (peaks.size() < RALLY_SAMPLES) return floor;
        int index = (int) Math.ceil(peaks.size() * 0.75d) - 1;
        return Math.max(floor, peaks.get(Math.max(0, index)));
    }

    /** A Rally starts at the threshold and survives one player leaving, so it does not flicker. */
    static boolean rallyLive(boolean live, int active, int threshold) {
        return live ? active >= threshold - 1 : active >= threshold;
    }

    // ------------------------------------------------------------------ community goal

    static Objective communityObjective(long week) {
        return COMMUNITY.get((int) Math.floorMod(week, COMMUNITY.size()));
    }

    /**
     * This week's server-wide target: last week's total for the same work plus
     * {@code growthPercent}, so the goal is always a little more than the server did.
     * With no history it assumes last week's players each do half a weekly quest.
     */
    static long communityTarget(Objective objective, long lastWeekTotal, int lastWeekPlayers, int growthPercent) {
        long floor = objective.weekly * 3L;
        if (lastWeekTotal <= 0L) {
            return roundTarget(Math.max(floor, objective.weekly * Math.max(5L, lastWeekPlayers) / 2L));
        }
        return roundTarget(Math.max(floor, lastWeekTotal * (100L + Math.max(0, growthPercent)) / 100L));
    }

    /** Enough to count as having helped: a day's quest or 2% of the goal, whichever is less. */
    static long contributorMinimum(Objective objective, long target) {
        return Math.max(1L, Math.min(Math.max(1L, objective.daily), target / 50L));
    }
}
