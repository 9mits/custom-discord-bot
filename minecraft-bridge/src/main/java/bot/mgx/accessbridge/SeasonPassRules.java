package bot.mgx.accessbridge;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * The Season Pass: XP into tiers, rewards per tier, and the daily and weekly quests that
 * earn most of the XP.
 *
 * <p>Free of Bukkit so every boundary is unit tested. Quests are the same for everybody
 * on a given UTC day or week, picked deterministically from the day, so players can talk
 * about "today's quests" the way they would on a large network.
 */
final class SeasonPassRules {
    /** What a quest counts. */
    enum QuestType {
        PLAY_MINUTES("Play %s active minutes", "item/clock_00", 30, 150, 300, 800),
        KILL_MOBS("Defeat %s hostile mobs", "item/iron_sword", 40, 200, 400, 1_000),
        MINE_ORES("Mine %s ores", "item/iron_pickaxe", 60, 200, 600, 1_000),
        HARVEST_CROPS("Harvest %s fully grown crops", "item/wheat", 100, 150, 1_000, 800),
        CATCH_FISH("Catch %s fish", "item/cod", 15, 150, 120, 800),
        OPEN_CRATES("Open %s crates", "item/trial_key", 10, 200, 80, 1_000),
        SELL_MONEY("Earn %s from /sell", "item/gold_ingot", 10_000, 200, 100_000, 1_000),
        PLAY_PVP("Play %s competitive PvP matches", "item/diamond_sword", 3, 200, 15, 1_000),
        WIN_PVP("Win %s competitive PvP matches", "item/netherite_sword", 2, 300, 10, 1_200);

        private final String template;
        private final String sprite;
        private final int dailyTarget;
        private final int dailyXp;
        private final int weeklyTarget;
        private final int weeklyXp;

        QuestType(String template, String sprite, int dailyTarget, int dailyXp,
                int weeklyTarget, int weeklyXp) {
            this.template = template;
            this.sprite = sprite;
            this.dailyTarget = dailyTarget;
            this.dailyXp = dailyXp;
            this.weeklyTarget = weeklyTarget;
            this.weeklyXp = weeklyXp;
        }

        String sprite() {
            return sprite;
        }

        boolean pvp() {
            return this == PLAY_PVP || this == WIN_PVP;
        }

        String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** One quest on today's or this week's board. */
    record Quest(QuestType type, int target, int xp, boolean weekly) {
        String id() {
            return (weekly ? "weekly:" : "daily:") + type.key();
        }

        String label() {
            String amount = type == QuestType.SELL_MONEY
                    ? EconomyFormat.dollars(target) : String.format(Locale.ROOT, "%,d", target);
            return String.format(Locale.ROOT, type.template, amount);
        }
    }

    /** One thing a tier pays. */
    record Grant(String kind, long amount, String id) { }

    static final int DAILY_QUESTS = 3;
    static final int WEEKLY_QUESTS = 3;

    private SeasonPassRules() {
    }

    /** The tier a total of XP has reached, 0 until the first tier is earned. */
    static int tier(long xp, int xpPerTier, int maximumTier) {
        if (xp <= 0L || xpPerTier <= 0) return 0;
        return (int) Math.min(maximumTier, xp / xpPerTier);
    }

    /** XP into the current tier, for the progress line. */
    static long xpIntoTier(long xp, int xpPerTier, int maximumTier) {
        if (tier(xp, xpPerTier, maximumTier) >= maximumTier) return xpPerTier;
        return Math.max(0L, xp) % Math.max(1, xpPerTier);
    }

    /** The UTC epoch day a week starts on: Monday. */
    static long weekStart(long epochDay) {
        // 1970-01-01 was a Thursday, so Monday is three days behind day zero's offset.
        return epochDay - Math.floorMod(epochDay + 3L, 7L);
    }

    /**
     * Today's or this week's quests. At most one PvP quest per board, because on a quiet
     * server a board that can only be finished with two opponents cannot be finished.
     */
    static List<Quest> quests(long period, boolean weekly, int count) {
        List<QuestType> pool = new ArrayList<>(List.of(QuestType.values()));
        java.util.Collections.shuffle(pool, new Random(period * (weekly ? 7_919L : 104_729L) + 17L));
        List<Quest> chosen = new ArrayList<>();
        boolean pvp = false;
        for (QuestType type : pool) {
            if (chosen.size() >= count) break;
            if (type.pvp() && pvp) continue;
            pvp |= type.pvp();
            chosen.add(new Quest(type, weekly ? type.weeklyTarget : type.dailyTarget,
                    weekly ? type.weeklyXp : type.dailyXp, weekly));
        }
        return List.copyOf(chosen);
    }

    /**
     * Parses a tier reward such as {@code keys:3;shards:1;reward:crate_luck_ii;cosmetic:ender_trail}.
     * Unknown or malformed parts are skipped rather than failing the whole tier. Money is
     * deliberately not a reward kind: its value moves with every economy change.
     */
    static List<Grant> parse(String spec) {
        List<Grant> grants = new ArrayList<>();
        if (spec == null) return grants;
        for (String part : spec.split(";")) {
            String[] pieces = part.strip().split(":", 2);
            if (pieces.length != 2) continue;
            String kind = pieces[0].strip().toLowerCase(Locale.ROOT);
            String value = pieces[1].strip();
            switch (kind) {
                case "keys", "shards" -> {
                    try {
                        long amount = Long.parseLong(value.replace(",", "").replace("_", ""));
                        if (amount > 0L) grants.add(new Grant(kind, amount, ""));
                    } catch (NumberFormatException ignored) {
                        // skipped
                    }
                }
                case "reward", "cosmetic" -> {
                    if (!value.isBlank()) grants.add(new Grant(kind, 1L, value.toLowerCase(Locale.ROOT)));
                }
                default -> {
                    // skipped
                }
            }
        }
        return List.copyOf(grants);
    }

    /** Which reward setting a tier uses: its own override, a milestone, or odd/even. */
    static String rewardKey(int tier, java.util.function.Predicate<String> hasOverride) {
        String own = "season.reward.tier-" + tier;
        if (hasOverride.test(own)) return own;
        if (tier % 10 == 0) return "season.reward.every-10";
        if (tier % 5 == 0) return "season.reward.every-5";
        return tier % 2 == 0 ? "season.reward.even" : "season.reward.odd";
    }
}
