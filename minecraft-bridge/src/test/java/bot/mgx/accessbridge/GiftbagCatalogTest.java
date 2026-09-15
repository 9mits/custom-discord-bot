package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GiftbagCatalogTest {
    @Test
    void defaultOddsUseOneMillionExactWeightUnits() {
        assertEquals(GiftbagCatalog.DEFAULT_TOTAL_WEIGHT,
                GiftbagCatalog.all().stream().mapToLong(GiftbagCatalog.Entry::defaultWeight).sum());
        assertEquals("1 in 1,000", GiftbagService.chance(1_000, 1_000_000));
        assertEquals("1 in 5,000", GiftbagService.chance(200, 1_000_000));
    }

    @Test
    void everyRollReturnsOnePublishedReward() {
        Random random = new Random(9);
        for (int index = 0; index < 10_000; index++) {
            GiftbagCatalog.Entry rolled = GiftbagCatalog.roll(1,
                    GiftbagCatalog.Entry::defaultWeight, random);
            assertTrue(GiftbagCatalog.all().contains(rolled));
        }
    }

    @Test
    void anUnshippedFutureSeasonCannotMintNonexistentExclusives() {
        assertTrue(GiftbagCatalog.forSeason(999).stream().noneMatch(entry ->
                entry.kind() == GiftbagCatalog.Kind.SEASON_GEAR
                        || entry.kind() == GiftbagCatalog.Kind.SEASON_COSMETIC));
    }
}
