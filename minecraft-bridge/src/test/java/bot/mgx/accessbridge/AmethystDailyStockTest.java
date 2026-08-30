package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Set;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AmethystDailyStockTest {
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");

    @TempDir
    Path temporary;

    /**
     * The one failure this class can have that nothing else would catch: an ID that
     * does not name a real crate reward produces a listing with no item behind it,
     * and the shelf would simply draw nothing where the rare stock should be.
     */
    @Test
    void everyEligibleIdNamesARealAmethystExclusive() {
        for (String id : AmethystDailyStock.REWARD_IDS) {
            CrateCatalog.Reward reward = CrateCatalog.find(id).orElse(null);
            assertNotEquals(null, reward, id + " is not in the crate catalog");
            assertTrue(CrateCatalog.isExclusiveAmethyst(reward), id + " is not amethyst-exclusive");
            assertFalse(reward.cosmetic(), id + " is a cosmetic");
        }
    }

    /** Cosmetics are the crate's own chase; the shop must never be a way around one. */
    @Test
    void noCosmeticIsEverEligible() {
        for (CrateCatalog.Reward reward : CrateCatalog.amethystAdminRewards()) {
            if (reward.cosmetic()) {
                assertFalse(AmethystDailyStock.REWARD_IDS.contains(reward.id()), reward.id());
            }
        }
    }

    @Test
    void aRollAlwaysStocksTwoOrThreeOfOneEligibleItem() {
        RandomGenerator random = RandomGenerator.getDefault();
        long now = Instant.parse("2026-08-30T04:15:00Z").toEpochMilli();
        Set<String> seen = new HashSet<>();
        for (int attempt = 0; attempt < 400; attempt++) {
            AmethystDailyStock stock = AmethystDailyStock.roll(now, TOKYO, random);
            assertTrue(AmethystDailyStock.REWARD_IDS.contains(stock.rewardId()), stock.rewardId());
            assertTrue(stock.stock() >= AmethystDailyStock.MINIMUM_STOCK, "stock " + stock.stock());
            assertTrue(stock.stock() <= AmethystDailyStock.MAXIMUM_STOCK, "stock " + stock.stock());
            assertFalse(stock.soldOut());
            seen.add(stock.rewardId());
        }
        assertEquals(Set.copyOf(AmethystDailyStock.REWARD_IDS), seen,
                "every eligible item must be reachable");
    }

    /**
     * "Once a day at a random time" is two claims, and this is the one that can drift:
     * the next roll has to land inside the following calendar day in the server's own
     * zone, not a fixed twenty-four hours after this one.
     */
    @Test
    void theNextRollLandsSomewhereInsideTheFollowingDay() {
        RandomGenerator random = RandomGenerator.getDefault();
        long now = Instant.parse("2026-08-30T14:40:00Z").toEpochMilli();
        LocalDate tomorrow = Instant.ofEpochMilli(now).atZone(TOKYO).toLocalDate().plusDays(1);
        Set<Long> distinct = new HashSet<>();
        for (int attempt = 0; attempt < 200; attempt++) {
            long next = AmethystDailyStock.nextRollAt(now, TOKYO, random);
            assertEquals(tomorrow, Instant.ofEpochMilli(next).atZone(TOKYO).toLocalDate(),
                    "roll landed outside the next day");
            assertTrue(next > now);
            distinct.add(next);
        }
        assertTrue(distinct.size() > 100, "the time of day is meant to be unpredictable");
    }

    @Test
    void sellingCountsDownAndAFailedSaleGoesBackOnTheShelf() {
        AmethystDailyStock stock = new AmethystDailyStock("amethyst_totem", 2, 10L, 20L);
        assertEquals(1, stock.sold().stock());
        assertTrue(stock.sold().sold().soldOut());
        assertEquals(2, stock.sold().returned().stock());
        assertFalse(stock.soldOut());
        assertEquals("Amethyst Totem", stock.displayName());
    }

    @Test
    void aListingIsDueOnlyOnceItsMomentHasPassed() {
        AmethystDailyStock stock = new AmethystDailyStock("amethyst_axe", 3, 10L, 100L);
        assertFalse(stock.due(99L));
        assertTrue(stock.due(100L));
        assertTrue(stock.due(101L));
    }

    /** A restart must not reroll the listing, nor forget what has already been sold. */
    @Test
    void theListingSurvivesARestartWithItsRemainingStock() throws Exception {
        Path file = temporary.resolve("amethyst-daily-stock.json");
        AmethystDailyStockStore store = new AmethystDailyStockStore(file);
        assertTrue(store.current().isEmpty());

        AmethystDailyStock rolled = new AmethystDailyStock("amethyst_shield", 3, 5L, 500L);
        store.put(rolled);
        store.put(store.current().orElseThrow().sold());

        AmethystDailyStockStore reloaded = new AmethystDailyStockStore(file);
        AmethystDailyStock loaded = reloaded.current().orElseThrow();
        assertEquals("amethyst_shield", loaded.rewardId());
        assertEquals(2, loaded.stock());
        assertEquals(5L, loaded.rolledAt());
        assertEquals(500L, loaded.nextRollAt());
    }
}
