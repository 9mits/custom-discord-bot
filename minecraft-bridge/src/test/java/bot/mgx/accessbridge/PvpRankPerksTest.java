package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PvpRankPerksTest {
    private static final Map<String, Long> DEFAULTS = Map.of(
            PvpRankPerks.SPEED_KEY, 10L,
            PvpRankPerks.STRENGTH_KEY, 10L,
            PvpRankPerks.MINING_KEY, 15L,
            PvpRankPerks.REGENERATION_KEY, 15L,
            PvpRankPerks.MONEY_KEY, 15L,
            PvpRankPerks.LUCK_KEY, 15L);

    private static PvpRankPerks at(PvpRank rank) {
        return PvpRankPerks.of(rank, DEFAULTS::get);
    }

    @Test
    void unrealCarriesTheFullSmallBoostAndBronzeNone() {
        PvpRankPerks unreal = at(PvpRank.UNREAL);
        assertEquals(0.10d, unreal.speed(), 1e-9);
        assertEquals(0.10d, unreal.strength(), 1e-9);
        assertEquals(0.15d, unreal.money(), 1e-9);
        assertEquals(0.15d, unreal.luck(), 1e-9);
        assertEquals("Speed +10% • Strength +10% • Mining +15% • Regen +15% • Money +15% • Luck +15%",
                unreal.summary());
        assertTrue(at(PvpRank.BRONZE_III).isNone());
        assertEquals("No personal boosts yet", at(PvpRank.BRONZE_I).summary());
    }

    @Test
    void tiersClimbInWholePercentsAndDivisionsShareTheirTier() {
        assertEquals(at(PvpRank.GOLD_I), new PvpRankPerks(PvpRank.GOLD_I,
                at(PvpRank.GOLD_III).speed(), at(PvpRank.GOLD_III).strength(),
                at(PvpRank.GOLD_III).diggingSpeed(), at(PvpRank.GOLD_III).regeneration(),
                at(PvpRank.GOLD_III).money(), at(PvpRank.GOLD_III).luck()));
        double previous = -1d;
        for (PvpRank rank : PvpRank.values()) {
            PvpRankPerks perks = at(rank);
            assertTrue(perks.luck() >= previous, rank + " must not lose luck");
            previous = perks.luck();
            double percent = perks.luck() * 100d;
            assertEquals(Math.rint(percent), percent, 1e-9, rank + " luck is a whole percent");
        }
        assertEquals("Speed +1% • Strength +1% • Mining +2% • Regen +2% • Money +2% • Luck +2%",
                at(PvpRank.SILVER_II).summary());
    }
}
