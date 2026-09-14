package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SeasonPassMenuTest {
    @Test
    void pagesHoldSevenTiersAndOpenOnTheNextReward() {
        assertEquals(8, SeasonPassMenu.pageCount(50), "50 tiers across 8 pages");
        assertEquals(0, SeasonPassMenu.pageOf(1));
        assertEquals(0, SeasonPassMenu.pageOf(7));
        assertEquals(1, SeasonPassMenu.pageOf(8));
        assertEquals(0, SeasonPassMenu.homePage(0, 50), "a new player opens on tier 1");
        assertEquals(1, SeasonPassMenu.homePage(7, 50), "tier 7 done shows tier 8 next");
        assertEquals(7, SeasonPassMenu.homePage(50, 50), "a finished pass stays on the last page");
    }

    @Test
    void exclusivesOutrankEverythingOnTheirTier() {
        var gear = new SeasonPassRules.Grant("season_gear", 1, "SCYTHE");
        var hearts = new SeasonPassRules.Grant("hearts", 1, "");
        var mace = new SeasonPassRules.Grant("reward", 1, "mace");
        var apples = new SeasonPassRules.Grant("reward", 2, "golden_apple");
        assertEquals(SeasonPassMenu.Rarity.EXCLUSIVE, SeasonPassMenu.rarity(gear, Optional.empty()));
        assertEquals(SeasonPassMenu.Rarity.LEGENDARY, SeasonPassMenu.rarity(hearts, Optional.empty()));
        assertTrue(SeasonPassMenu.rarity(mace, CrateCatalog.find("mace")).order
                < SeasonPassMenu.rarity(apples, CrateCatalog.find("golden_apple")).order,
                "a Mace is shown before golden apples");
    }

    @Test
    void menuPreviewsNeverCreditTheSentinelMintPool() throws Exception {
        String menu = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/bot/mgx/accessbridge/SeasonPassMenu.java"));
        assertTrue(menu.contains("SentinelHub.quietly(() -> preview(grant, reward))"),
                "a redrawn tile must not explain away a real duplication");
    }
}
