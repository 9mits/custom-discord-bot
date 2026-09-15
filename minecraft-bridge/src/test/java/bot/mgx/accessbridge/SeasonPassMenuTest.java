package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SeasonPassMenuTest {
    @Test
    void everyQuestSectionDividerIsTheSameWidth() {
        java.util.List<Integer> widths = java.util.stream.Stream.of(
                        "DAILY QUESTS", "WEEKLY QUESTS", "COMMUNITY GOAL", "SEASON MILESTONES")
                .map(title -> 2 * SeasonPassService.dividerSpaces(title) * SidebarText.SPACE_WIDTH
                        + SidebarText.textWidth("  " + title + "  ", true))
                .toList();
        int widest = widths.stream().max(Integer::compare).orElseThrow();
        int narrowest = widths.stream().min(Integer::compare).orElseThrow();
        assertTrue(widest - narrowest < 2 * SidebarText.SPACE_WIDTH, "dividers drift: " + widths);
        assertTrue(widest <= SeasonPassService.DIVIDER_WIDTH, "a divider must not wrap: " + widths);
    }

    @Test
    void theTierListPagesByTens() {
        assertEquals(0, SeasonPassMenu.listPageOf(1));
        assertEquals(0, SeasonPassMenu.listPageOf(10));
        assertEquals(1, SeasonPassMenu.listPageOf(11));
        assertEquals(4, SeasonPassMenu.listPageOf(50));
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
                "a Mace is listed before golden apples");
    }

    @Test
    void modelKeysBecomeTheirItemTextures() {
        assertEquals("mgx:item/season_1_scythe", SeasonPassMenu.textureOf("mgx:season_1_scythe"));
        assertEquals("mgx:item/cosmetic/season_1_aura", SeasonPassMenu.textureOf("mgx:cosmetic/season_1_aura"));
    }

    @Test
    void menuPreviewsNeverCreditTheSentinelMintPool() throws Exception {
        String menu = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/bot/mgx/accessbridge/SeasonPassMenu.java"));
        assertTrue(menu.contains("SentinelHub.quietly(() -> preview(grant, reward))"),
                "a redrawn tile must not explain away a real duplication");
    }
}
