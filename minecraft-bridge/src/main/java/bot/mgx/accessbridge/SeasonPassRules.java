package bot.mgx.accessbridge;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Season Pass: XP into tiers, rewards per tier, and the quest ladders that earn most
 * of the XP.
 *
 * <p>Free of Bukkit so every boundary is unit tested.
 */
final class SeasonPassRules {
    /**
     * What a quest counts, and its ladder.
     *
     * <p>Quests have no time limit. Each line is a season-long ladder of cumulative
     * targets: reach one and the next, harder level begins, paying more XP. Targets are
     * sized from the live server's own numbers (September 2026): a typical engaged
     * player kills about 50 hostile mobs and mines about 12 ores an hour, the top tenth
     * have 12,000+ kills and 3,000+ ores, and the richest balances run to hundreds of
     * millions. The first rungs are an evening; the last are a whole season of effort.
     */
    enum QuestType {
        KILL_MOBS("Mob Hunter", "Defeat %s hostile mobs", "item/iron_sword",
                100, 300, 750, 1_500, 3_000, 6_000, 12_000, 25_000),
        MINE_ORES("Deep Miner", "Mine %s ores", "item/iron_pickaxe",
                50, 150, 400, 800, 1_500, 3_000, 6_000),
        HARVEST_CROPS("Harvester", "Harvest %s fully grown crops", "item/wheat",
                100, 300, 750, 1_500, 3_000, 6_000, 12_000),
        CATCH_FISH("Angler", "Catch %s fish", "item/cod",
                10, 30, 75, 150, 300, 600),
        OPEN_CRATES("Crate Hunter", "Open %s crates", "item/trial_key",
                50, 200, 500, 1_000, 2_500, 5_000),
        SELL_MONEY("Merchant", "Earn %s from /sell", "item/gold_ingot",
                100_000, 500_000, 1_000_000, 2_500_000, 5_000_000, 10_000_000, 25_000_000),
        PLAY_MINUTES("Dedicated", "Play %s active minutes", "item/clock_00",
                120, 480, 1_200, 2_400, 4_800, 9_600),
        PLAY_PVP("Competitor", "Play %s competitive PvP matches", "item/diamond_sword",
                5, 15, 40, 80, 150),
        WIN_PVP("Champion", "Win %s competitive PvP matches", "item/netherite_sword",
                3, 10, 25, 50, 100);

        private final String title;
        private final String template;
        private final String sprite;
        private final long[] targets;

        QuestType(String title, String template, String sprite, long... targets) {
            this.title = title;
            this.template = template;
            this.sprite = sprite;
            this.targets = targets;
        }

        String title() {
            return title;
        }

        String sprite() {
            return sprite;
        }

        int levels() {
            return targets.length;
        }

        boolean pvp() {
            return this == PLAY_PVP || this == WIN_PVP;
        }

        String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** XP for completing each level of any ladder: later levels are worth far more. */
    static final int[] LEVEL_XP = {250, 500, 900, 1_400, 2_000, 2_800, 3_600, 4_500};

    /** One rung of a quest ladder. {@code level} counts from zero. */
    record Quest(QuestType type, int level, long target, int xp) {
        String label() {
            String amount = type == QuestType.SELL_MONEY
                    ? EconomyFormat.dollars(target) : String.format(Locale.ROOT, "%,d", target);
            return String.format(Locale.ROOT, type.template, amount);
        }
    }

    /** The rung a player is on, or empty once the whole ladder is done. */
    static java.util.Optional<Quest> quest(QuestType type, int level) {
        if (level < 0 || level >= type.targets.length) return java.util.Optional.empty();
        return java.util.Optional.of(new Quest(type, level, type.targets[level],
                LEVEL_XP[Math.min(level, LEVEL_XP.length - 1)]));
    }

    /** How many rungs a season total has already cleared. */
    static int levelFor(QuestType type, long total) {
        int level = 0;
        while (level < type.targets.length && total >= type.targets[level]) level++;
        return level;
    }

    /** One thing a tier pays. */
    record Grant(String kind, long amount, String id) { }

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

    /** Most Season Hearts one tier can grant, whatever a setting says. */
    static final int MAX_HEARTS_PER_TIER = 5;
    /** Most copies of one crate reward a tier can pay. */
    static final long MAX_REWARD_COUNT = 16L;

    /**
     * Parses a tier reward such as {@code hearts:1;shards:2;reward:ancient_debris:2;gear:scythe;cosmetic:season:aura}.
     *
     * <p>{@code cosmetic:season:<aura|trail|kill>} names this season's exclusive rather than
     * a fixed id, so one setting pays Season 1's crown in Season 1 and Season 2's in
     * Season 2, and {@code gear:<scythe|pickaxe|axe|wings>} does the same for season gear.
     * Unknown or malformed parts are skipped rather than failing the whole tier.
     * Money is deliberately not a reward kind: its value moves with every economy change.
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
                case "keys", "shards", "hearts" -> {
                    try {
                        long amount = Long.parseLong(value.replace(",", "").replace("_", ""));
                        if (kind.equals("hearts")) amount = Math.min(MAX_HEARTS_PER_TIER, amount);
                        if (amount > 0L) grants.add(new Grant(kind, amount, ""));
                    } catch (NumberFormatException ignored) {
                        // skipped
                    }
                }
                case "cosmetic" -> {
                    String lower = value.toLowerCase(Locale.ROOT);
                    if (lower.startsWith("season:")) {
                        SeasonCosmetics.category(lower.substring("season:".length())).ifPresent(category ->
                                grants.add(new Grant("season_cosmetic", 1L, category.name())));
                    } else if (!value.isBlank()) {
                        grants.add(new Grant(kind, 1L, lower));
                    }
                }
                case "gear" -> SeasonGear.Piece.parse(value).ifPresent(piece ->
                        grants.add(new Grant("season_gear", 1L, piece.name())));
                case "reward" -> {
                    // reward:<id> or reward:<id>:<count>, so a tier can pay a stack.
                    String[] reward = value.toLowerCase(Locale.ROOT).split(":", 2);
                    long count = 1L;
                    if (reward.length == 2) {
                        try {
                            count = Math.max(1L, Math.min(MAX_REWARD_COUNT, Long.parseLong(reward[1].strip())));
                        } catch (NumberFormatException ignored) {
                            continue;
                        }
                    }
                    if (!reward[0].isBlank()) grants.add(new Grant(kind, count, reward[0].strip()));
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
