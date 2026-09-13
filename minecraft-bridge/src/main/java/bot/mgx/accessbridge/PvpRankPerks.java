package bot.mgx.accessbridge;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToLongFunction;

/**
 * Small, permanent personal bonuses earned by climbing the competitive PvP ladder.
 *
 * <p>Every value is a fraction (0.10 is +10%). The owner sets only the Unreal figure for
 * each boost; lower tiers carry {@link PvpRank#boostShare()} of it, rounded to a whole
 * percent so the ladder, tier guide and tab list never show a figure like 2.5%.
 */
record PvpRankPerks(
        PvpRank rank,
        double speed,
        double strength,
        double diggingSpeed,
        double regeneration,
        double money,
        double luck
) {
    static final PvpRankPerks NONE = new PvpRankPerks(
            PvpRank.BRONZE_I, 0d, 0d, 0d, 0d, 0d, 0d);

    static final String SPEED_KEY = "pvp-ranked.unreal-speed-percent";
    static final String STRENGTH_KEY = "pvp-ranked.unreal-strength-percent";
    static final String MINING_KEY = "pvp-ranked.unreal-mining-percent";
    static final String REGENERATION_KEY = "pvp-ranked.unreal-regeneration-percent";
    static final String MONEY_KEY = "pvp-ranked.unreal-money-percent";
    static final String LUCK_KEY = "pvp-ranked.unreal-luck-percent";

    static PvpRankPerks of(PvpRank rank, GameVariableStore variables) {
        return of(rank, variables::integer);
    }

    static PvpRankPerks of(PvpRank rank, ToLongFunction<String> unrealPercent) {
        PvpRank resolved = rank == null ? PvpRank.BRONZE_I : rank;
        double share = resolved.boostShare();
        return new PvpRankPerks(
                resolved,
                scaled(unrealPercent.applyAsLong(SPEED_KEY), share),
                scaled(unrealPercent.applyAsLong(STRENGTH_KEY), share),
                scaled(unrealPercent.applyAsLong(MINING_KEY), share),
                scaled(unrealPercent.applyAsLong(REGENERATION_KEY), share),
                scaled(unrealPercent.applyAsLong(MONEY_KEY), share),
                scaled(unrealPercent.applyAsLong(LUCK_KEY), share)
        );
    }

    private static double scaled(long unrealPercent, double share) {
        return Math.round(Math.max(0L, unrealPercent) * share) / 100d;
    }

    boolean isNone() {
        return speed <= 0d && strength <= 0d && diggingSpeed <= 0d && regeneration <= 0d
                && money <= 0d && luck <= 0d;
    }

    List<String> labels() {
        List<String> labels = new ArrayList<>();
        add(labels, speed, "Speed");
        add(labels, strength, "Strength");
        add(labels, diggingSpeed, "Mining");
        add(labels, regeneration, "Regen");
        add(labels, money, "Money");
        add(labels, luck, "Luck");
        return List.copyOf(labels);
    }

    String summary() {
        return labels().isEmpty() ? "No personal boosts yet" : String.join(" • ", labels());
    }

    /** One line per boost saying what it actually does, for the ladder and tier guide. */
    List<String> explained() {
        List<String> lines = new ArrayList<>();
        explain(lines, speed, "movement speed");
        explain(lines, strength, "direct melee damage");
        explain(lines, diggingSpeed, "block-breaking speed");
        explain(lines, regeneration, "natural health regeneration from food");
        explain(lines, money, "money from /sell and auto-sell");
        explain(lines, luck, "crate rare-reward luck and ore/crop drops");
        return List.copyOf(lines);
    }

    private static void add(List<String> labels, double fraction, String label) {
        if (fraction > 0d) labels.add(label + " +" + percent(fraction));
    }

    private static void explain(List<String> lines, double fraction, String what) {
        if (fraction > 0d) lines.add("+" + percent(fraction) + " " + what);
    }

    static String percent(double fraction) {
        return Math.round(Math.max(0d, fraction) * 100d) + "%";
    }
}
