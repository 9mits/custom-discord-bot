package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SeasonPassRulesTest {
    @Test
    void xpBecomesTiersAndStopsAtTheLastOne() {
        assertEquals(0, SeasonPassRules.tier(999, 1_000, 50));
        assertEquals(1, SeasonPassRules.tier(1_000, 1_000, 50));
        assertEquals(50, SeasonPassRules.tier(9_999_999, 1_000, 50));
        assertEquals(250, SeasonPassRules.xpIntoTier(12_250, 1_000, 50));
        assertEquals(1_000, SeasonPassRules.xpIntoTier(80_000, 1_000, 50));
    }

    @Test
    void weeksStartOnMondayInUtc() {
        for (int offset = 0; offset < 14; offset++) {
            long day = LocalDate.of(2026, 9, 1).toEpochDay() + offset;
            assertEquals(DayOfWeek.MONDAY, LocalDate.ofEpochDay(SeasonPassRules.weekStart(day)).getDayOfWeek());
            assertTrue(day - SeasonPassRules.weekStart(day) < 7);
        }
    }

    @Test
    void everyoneGetsTheSameBoardWithAtMostOnePvpQuest() {
        for (long day = 20_000; day < 20_200; day++) {
            List<SeasonPassRules.Quest> daily = SeasonPassRules.quests(day, false, 3);
            assertEquals(daily, SeasonPassRules.quests(day, false, 3), "the board is deterministic");
            assertEquals(3, daily.size());
            assertTrue(daily.stream().filter(quest -> quest.type().pvp()).count() <= 1);
            Set<SeasonPassRules.QuestType> types = new HashSet<>();
            daily.forEach(quest -> assertTrue(types.add(quest.type()), "no repeated quest"));
        }
        SeasonPassRules.Quest weekly = SeasonPassRules.quests(20_000, true, 3).getFirst();
        assertTrue(weekly.weekly() && weekly.id().startsWith("weekly:"));
    }

    @Test
    void rewardSpecsParseAndSkipNonsense() {
        List<SeasonPassRules.Grant> grants = SeasonPassRules.parse(
                "keys:3; shards:1 ;money:20,000;cosmetic:Prismatic_Trail;bogus:5;keys:-1;reward:crate_luck_v");
        assertEquals(4, grants.size());
        assertEquals(new SeasonPassRules.Grant("shards", 1, ""), grants.get(1));
        assertEquals("prismatic_trail", grants.get(2).id());
        assertTrue(grants.stream().noneMatch(grant -> grant.kind().equals("money")),
                "money is never a reward: its value moves with the economy");
    }

    @Test
    void milestonesAndOverridesPickTheRightRewardSetting() {
        Set<String> overrides = Set.of("season.reward.tier-25", "season.reward.tier-50");
        assertEquals("season.reward.odd", SeasonPassRules.rewardKey(3, overrides::contains));
        assertEquals("season.reward.even", SeasonPassRules.rewardKey(4, overrides::contains));
        assertEquals("season.reward.every-5", SeasonPassRules.rewardKey(15, overrides::contains));
        assertEquals("season.reward.every-10", SeasonPassRules.rewardKey(30, overrides::contains));
        assertEquals("season.reward.tier-25", SeasonPassRules.rewardKey(25, overrides::contains));
        assertEquals("season.reward.tier-50", SeasonPassRules.rewardKey(50, overrides::contains));
    }

    @Test
    void aNewSeasonIsSavedTheMomentItStarts() throws Exception {
        String service = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/bot/mgx/accessbridge/SeasonPassService.java"));
        String start = service.substring(service.indexOf("void start()"),
                service.indexOf("private boolean enabled()"));
        assertTrue(start.contains("if (ensureSeason(today())) save();"),
                "an unsaved season restarts with a new end date on every server restart");
    }

    @Test
    void theDefaultChaseCosmeticsAreRealCatalogueEntries() {
        for (String id : List.of("ender_trail", "celestial_crown", "prismatic_trail")) {
            assertTrue(CosmeticCatalog.find(id).isPresent(), id + " is not a registered cosmetic");
        }
        for (String id : List.of("crate_luck_iii", "crate_luck_v", "fortune_potion_ii", "crate_luck_ii")) {
            assertTrue(CrateCatalog.find(id).isPresent(), id + " is not a registered crate reward");
        }
    }

    @Test
    void noStreakOrSeasonDefaultPaysMoney() throws Exception {
        String store = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/bot/mgx/accessbridge/GameVariableStore.java"));
        String block = store.substring(store.indexOf("bool(\"season.enabled\""),
                store.indexOf("bool(\"referrals.enabled\""));
        assertTrue(!java.util.regex.Pattern.compile("money:\\d").matcher(block).find()
                        && !block.contains("\"money\""),
                "streak and season rewards must never be dollars");
    }
}
