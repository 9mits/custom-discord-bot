package bot.mgx.accessbridge;

import java.util.Map;
import java.util.UUID;
import java.util.random.RandomGenerator;

/** Pure balancing and reward rules for the cooperative Amethyst Block event. */
final class AmethystBlockRewards {
    static final double MAX_HEALTH = 2_750d;
    static final int[] REWARD_HEALTH_PERCENTAGES = {80, 60, 40, 20};
    private static final int SHARD_ROLL = 2_500;

    record Bundle(int keys, int diamonds, int emeralds, int gold, int shards) {
    }

    private AmethystBlockRewards() {
    }

    /**
     * Group mining is intentionally subject to a gentle coordination penalty. It
     * keeps five maxed miners near five minutes while two take near ten minutes.
     */
    static double groupDamagePerSecond(double rawDamagePerSecond, int activeMiners) {
        if (rawDamagePerSecond <= 0d || activeMiners <= 0) {
            return 0d;
        }
        return rawDamagePerSecond / (1d + (activeMiners - 1d) / 11d);
    }

    static Bundle rollMilestone(RandomGenerator random) {
        return new Bundle(
                random.nextInt(3, 6),
                random.nextInt(1, 4),
                random.nextInt(2, 6),
                random.nextInt(4, 9),
                random.nextInt(SHARD_ROLL) == 0 ? 1 : 0
        );
    }

    static Bundle rollMilestone(RandomGenerator random, GameVariableStore variables) {
        return rollBundle(random, variables, "milestone");
    }

    static Bundle completionBundle(RandomGenerator random) {
        return new Bundle(
                random.nextInt(8, 13),
                random.nextInt(3, 7),
                random.nextInt(5, 10),
                random.nextInt(8, 17),
                random.nextInt(SHARD_ROLL) == 0 ? 1 : 0
        );
    }

    static Bundle completionBundle(RandomGenerator random, GameVariableStore variables) {
        return rollBundle(random, variables, "completion");
    }

    private static Bundle rollBundle(
            RandomGenerator random, GameVariableStore variables, String kind
    ) {
        String base = "huge-amethyst." + kind + ".";
        return new Bundle(
                between(random, variables.integer(base + "minimum-keys"),
                        variables.integer(base + "maximum-keys")),
                between(random, variables.integer(base + "minimum-diamonds"),
                        variables.integer(base + "maximum-diamonds")),
                between(random, variables.integer(base + "minimum-emeralds"),
                        variables.integer(base + "maximum-emeralds")),
                between(random, variables.integer(base + "minimum-gold"),
                        variables.integer(base + "maximum-gold")),
                random.nextInt(variables.integer("huge-amethyst.shard-one-in")) == 0
                        ? variables.integer("huge-amethyst.shard-amount") : 0
        );
    }

    private static int between(RandomGenerator random, int minimum, int maximum) {
        return minimum == maximum ? minimum : random.nextInt(minimum, maximum + 1);
    }

    /** Everyone gets the common bundles; mining adds this key bonus on top. */
    static int contributionKeys(UUID player, Map<UUID, Double> damage) {
        return contributionKeys(player, damage, 5, 45);
    }

    static int contributionKeys(
            UUID player, Map<UUID, Double> damage, int baseKeys, int poolKeys
    ) {
        double total = damage.values().stream().mapToDouble(Double::doubleValue).sum();
        double dealt = damage.getOrDefault(player, 0d);
        if (total <= 0d || dealt <= 0d) {
            return 0;
        }
        return Math.max(0, baseKeys) + (int) Math.floor(Math.max(0, poolKeys) * dealt / total);
    }
}
