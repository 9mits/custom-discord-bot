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
    void tierRewardsSitMirroredAboutTheCentreSlot() {
        for (int count = 1; count <= 7; count++) {
            int[] slots = SeasonPassMenu.rewardSlots(count);
            assertEquals(count, slots.length);
            for (int index = 0; index < count; index++) {
                assertTrue(slots[index] >= 9 && slots[index] <= 17, "rewards stay on the middle row");
                assertEquals(26, slots[index] + slots[count - 1 - index], count + " rewards lean to one side");
            }
        }
        assertEquals(7, SeasonPassMenu.rewardSlots(12).length, "the row holds at most seven");
    }

    @Test
    void passRaritiesFollowWhatARewardIsNotItsCrateWeight() {
        assertEquals(SeasonPassMenu.Rarity.RARE, SeasonPassMenu.knownRewardRarity("fortune_potion_i").orElseThrow());
        assertEquals(SeasonPassMenu.Rarity.LEGENDARY, SeasonPassMenu.knownRewardRarity("crate_luck_v").orElseThrow());
        assertEquals(SeasonPassMenu.Rarity.EPIC, SeasonPassMenu.knownRewardRarity("enchant_fortune_iv").orElseThrow());
        assertEquals(SeasonPassMenu.Rarity.EPIC, SeasonPassMenu.knownRewardRarity("daily_lantern_helm").orElseThrow());
        assertTrue(SeasonPassMenu.knownRewardRarity("daily_diamonds").isEmpty());
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
