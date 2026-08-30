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

    static Bundle completionBundle(RandomGenerator random) {
        return new Bundle(
                random.nextInt(8, 13),
                random.nextInt(3, 7),
                random.nextInt(5, 10),
                random.nextInt(8, 17),
                random.nextInt(SHARD_ROLL) == 0 ? 1 : 0
        );
    }

    /** Everyone gets the common bundles; mining adds this key bonus on top. */
    static int contributionKeys(UUID player, Map<UUID, Double> damage) {
        double total = damage.values().stream().mapToDouble(Double::doubleValue).sum();
        double dealt = damage.getOrDefault(player, 0d);
        if (total <= 0d || dealt <= 0d) {
            return 0;
        }
        return 5 + (int) Math.floor(45d * dealt / total);
    }
}
