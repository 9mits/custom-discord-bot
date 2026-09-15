package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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

    @Test
    void mythicItemIdsAreCompleteAndStableForAdminTesting() {
        assertEquals(List.of("riftcleaver", "worldcarver", "fatebound_idol"),
                GiftbagCatalog.mythicItemIds());
        assertTrue(GiftbagCatalog.mythicItemIds().stream().allMatch(id ->
                GiftbagCatalog.find(id).orElseThrow().rarity() == GiftbagCatalog.Rarity.MYTHIC_ITEM));
    }

    @Test
    void giftbagPresentationUsesPlainEnglishVanillaStyle() throws Exception {
        for (String file : List.of("GiftbagCatalog.java", "GiftbagService.java",
                "MythicGiftItemService.java", "SeasonPassMenu.java", "SeasonPassService.java")) {
            String source = Files.readString(Path.of("src/main/java/bot/mgx/accessbridge", file));
            assertTrue(!source.contains("幻"), file + " contains the discarded placeholder character");
            assertTrue(!source.contains("IMPOSSIBLE"), file + " still labels a Giftbag item as impossible");
        }
    }
}
