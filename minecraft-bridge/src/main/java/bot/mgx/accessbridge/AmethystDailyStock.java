package bot.mgx.accessbridge;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * The single rare Amethyst listing the shop puts out once a day.
 *
 * <p>Free of Bukkit so the roll can be unit tested. Everything here is a decision the
 * shelf has to survive a restart with: what is on sale, how many are left, and the
 * moment the next one lands. The service persists exactly this and nothing else.
 *
 * <p>Only the five Amethyst Crate exclusives are eligible - the tools, the shield and
 * the totem. Cosmetics are deliberately not: a cosmetic is the thing somebody kept a
 * crate open for, and a shop that sells it for money is a shop that ends the chase.
 */
record AmethystDailyStock(String rewardId, int stock, long rolledAt, long nextRollAt) {
    /** The crate rewards a day's listing may draw from, in catalog order. */
    static final List<String> REWARD_IDS = List.of(
            "amethyst_pickaxe",
            "amethyst_shovel",
            "amethyst_axe",
            "amethyst_shield",
            "amethyst_totem"
    );

    static final long PRICE = 5_000_000L;
    static final int MINIMUM_STOCK = 2;
    static final int MAXIMUM_STOCK = 3;
    /** Live tuning; the constants above stay the defaults and stand alone in tests. */
    private static volatile java.util.function.ToDoubleFunction<String> tuning = key -> Double.NaN;

    static void tuningSource(java.util.function.ToDoubleFunction<String> source) {
        if (source != null) {
            tuning = source;
        }
    }

    private static double tuned(String key, double fallback) {
        double value = tuning.applyAsDouble(key);
        return Double.isNaN(value) ? fallback : value;
    }


    AmethystDailyStock {
        rewardId = rewardId == null ? "" : rewardId.strip().toLowerCase(java.util.Locale.ROOT);
        stock = Math.max(0, stock);
    }

    /**
     * A fresh listing, and the moment the one after it lands.
     *
     * <p>The next roll is a random moment inside the next calendar day rather than a
     * fixed interval from this one. That is what "once a day, at a random time" means:
     * every day gets exactly one, and knowing today's does not tell anybody tomorrow's.
     */
    static AmethystDailyStock roll(long now, ZoneId zone, RandomGenerator random) {
        String rewardId = REWARD_IDS.get(random.nextInt(REWARD_IDS.size()));
        int lowest = (int) tuned("amethyst-shop.minimum-stock", MINIMUM_STOCK);
        int highest = Math.max(lowest, (int) tuned("amethyst-shop.maximum-stock", MAXIMUM_STOCK));
        int stock = lowest + random.nextInt(highest - lowest + 1);
        return new AmethystDailyStock(rewardId, stock, now, nextRollAt(now, zone, random));
    }

    /** A random instant inside the day after this one, honouring the zone's own day length. */
    static long nextRollAt(long now, ZoneId zone, RandomGenerator random) {
        ZonedDateTime tomorrow = Instant.ofEpochMilli(now)
                .atZone(zone)
                .toLocalDate()
                .plusDays(1)
                .atStartOfDay(zone);
        long dayMillis = Math.max(1L, Duration.between(tomorrow, tomorrow.plusDays(1)).toMillis());
        return tomorrow.toInstant().toEpochMilli() + random.nextLong(dayMillis);
    }

    boolean soldOut() {
        return stock <= 0;
    }

    boolean due(long now) {
        return now >= nextRollAt;
    }

    /** One sale. The caller has already taken the money, or is about to put it back. */
    AmethystDailyStock sold() {
        return new AmethystDailyStock(rewardId, stock - 1, rolledAt, nextRollAt);
    }


    Optional<CrateCatalog.Reward> reward() {
        return CrateCatalog.find(rewardId);
    }

    String displayName() {
        return reward().map(CrateCatalog.Reward::displayName).orElse("Amethyst item");
    }
}
