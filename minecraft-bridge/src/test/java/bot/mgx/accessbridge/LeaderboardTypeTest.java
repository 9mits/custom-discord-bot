package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeaderboardTypeTest {
    @Test
    void keysRoundTrip() {
        for (LeaderboardType type : LeaderboardType.values()) {
            assertEquals(type, LeaderboardType.fromKey(type.key()).orElseThrow());
        }
    }

    @Test
    void unknownKeysAreRejectedRatherThanGuessed() {
        assertTrue(LeaderboardType.fromKey("nonsense").isEmpty());
        assertTrue(LeaderboardType.fromKey(null).isEmpty());
        assertTrue(LeaderboardType.fromKey("  ").isEmpty());
    }

    @Test
    void keyLookupToleratesPaddingAndCase() {
        assertEquals(LeaderboardType.WEALTH, LeaderboardType.fromKey("  WeAlTh  ").orElseThrow());
    }

    @Test
    void perPlayerTotalsAreNotOfferedForClans() {
        // Summing these across a clan would rank clan size, not achievement.
        assertFalse(LeaderboardType.BLOCKS_MINED.clanEligible());
        assertFalse(LeaderboardType.BLOCKS_WALKED.clanEligible());
        assertTrue(LeaderboardType.WEALTH.clanEligible());
        assertTrue(LeaderboardType.KILLS.clanEligible());
        assertTrue(LeaderboardType.PLAYTIME.clanEligible());
        assertFalse(LeaderboardType.AMETHYST_CRATES.clanEligible());
        assertFalse(LeaderboardType.AMETHYST_AIRDROPS.clanEligible());
        assertTrue(LeaderboardType.WEALTH.published());
        assertTrue(LeaderboardType.KILLS.published());
        assertFalse(LeaderboardType.AMETHYST_CRATES.published());
        assertFalse(LeaderboardType.AMETHYST_AIRDROPS.published());
        assertTrue(LeaderboardType.DRAGON_DAMAGE.published());
        assertTrue(LeaderboardType.DRAGON_CRYSTALS.published());
        assertFalse(LeaderboardType.PLAYTIME.published());
        assertFalse(LeaderboardType.BLOCKS_MINED.published());
    }

    @Test
    void statsReportTheValueForEachType() {
        PlayerStats stats = new PlayerStats(
                UUID.randomUUID(), "mits", 7, 2, 72_000, 500, 12_345, 999, 14, 3
        );

        assertEquals(7, stats.value(LeaderboardType.KILLS));
        assertEquals(999, stats.value(LeaderboardType.WEALTH));
        assertEquals(72_000, stats.value(LeaderboardType.PLAYTIME));
        assertEquals(500, stats.value(LeaderboardType.BLOCKS_MINED));
        assertEquals(12_345, stats.value(LeaderboardType.BLOCKS_WALKED));
        assertEquals(14, stats.value(LeaderboardType.AMETHYST_CRATES));
        assertEquals(3, stats.value(LeaderboardType.AMETHYST_AIRDROPS));
    }

    @Test
    void wealthIsNeverNegative() {
        PlayerStats stats = PlayerStats.empty(UUID.randomUUID(), "mits").withWealth(-50);

        assertEquals(0, stats.wealth());
    }

    @Test
    void playtimeRendersAsHoursAndMinutes() {
        // 72000 ticks is one hour; 1200 is one minute.
        assertEquals("1h 30m", LeaderboardType.PLAYTIME.describe(72_000 + 30 * 1_200));
    }

    @Test
    void walkedDistanceRendersInBlocksNotCentimetres() {
        assertEquals("123 blocks", LeaderboardType.BLOCKS_WALKED.describe(12_345));
    }

    @Test
    void wealthRendersAsDollars() {
        assertEquals("$1,234", LeaderboardType.WEALTH.describe(1_234));
    }
}
