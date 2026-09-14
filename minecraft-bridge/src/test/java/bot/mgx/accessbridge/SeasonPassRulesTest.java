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
    void questLaddersClimbWithoutTimeLimits() {
        for (SeasonPassRules.QuestType type : SeasonPassRules.QuestType.values()) {
            long previousTarget = 0;
            long previousXp = 0;
            for (int level = 0; level < type.levels(); level++) {
                SeasonPassRules.Quest quest = SeasonPassRules.quest(type, level).orElseThrow();
                assertTrue(quest.target() > previousTarget, type + " level " + level + " must be harder");
                assertTrue(quest.xp() > previousXp, type + " level " + level + " must pay more");
                previousTarget = quest.target();
                previousXp = quest.xp();
            }
            assertTrue(SeasonPassRules.quest(type, type.levels()).isEmpty(), "a finished ladder has no next rung");
            assertTrue(type.levels() >= 5, type + " needs a real ladder");
        }
        assertEquals(0, SeasonPassRules.levelFor(SeasonPassRules.QuestType.KILL_MOBS, 99));
        assertEquals(2, SeasonPassRules.levelFor(SeasonPassRules.QuestType.KILL_MOBS, 300));
        assertEquals(8, SeasonPassRules.levelFor(SeasonPassRules.QuestType.KILL_MOBS, 1_000_000));
    }

    @Test
    void laddersAreEditableAndABadListNeverBreaksTheQuests() {
        try {
            SeasonPassRules.ladderSource(
                    type -> type == SeasonPassRules.QuestType.KILL_MOBS ? "10, 20, 1_000" : "not a ladder",
                    () -> "5, 10");
            assertEquals(3, SeasonPassRules.QuestType.KILL_MOBS.levels());
            assertEquals(1_000, SeasonPassRules.quest(SeasonPassRules.QuestType.KILL_MOBS, 2).orElseThrow().target());
            assertEquals(10, SeasonPassRules.quest(SeasonPassRules.QuestType.KILL_MOBS, 2).orElseThrow().xp(),
                    "a ladder longer than the XP list reuses its last value");
            assertEquals(SeasonPassRules.QuestType.MINE_ORES.defaultTargets().length,
                    SeasonPassRules.QuestType.MINE_ORES.levels(), "an invalid list falls back to the default");
        } finally {
            SeasonPassRules.ladderSource(null, null);
        }
        assertTrue(SeasonPassRules.parseLadder("100 300 750").isPresent());
        assertTrue(SeasonPassRules.parseLadder("300, 100").isEmpty(), "each level must be harder");
        assertTrue(SeasonPassRules.parseLadder("0, 5").isEmpty());
        assertTrue(SeasonPassRules.parseLadder("1,000,000").isEmpty(), "thousands separators are refused");
    }

    @Test
    void theHardestRungsMatchTheLiveServer() {
        // September 2026: top-tenth players have 12,000+ mob kills and 3,000+ ores, and the
        // richest balances run to hundreds of millions. The last rungs must be a stretch.
        assertTrue(SeasonPassRules.quest(SeasonPassRules.QuestType.KILL_MOBS, 7).orElseThrow().target() >= 12_000);
        assertTrue(SeasonPassRules.quest(SeasonPassRules.QuestType.MINE_ORES, 6).orElseThrow().target() >= 3_000);
        assertTrue(SeasonPassRules.quest(SeasonPassRules.QuestType.SELL_MONEY, 6).orElseThrow().target() >= 10_000_000);
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
    void heartsAndSeasonExclusivesParse() {
        List<SeasonPassRules.Grant> grants = SeasonPassRules.parse(
                "hearts:1;hearts:99;cosmetic:season:aura;cosmetic:season:Kill;cosmetic:season:hat");
        assertEquals(new SeasonPassRules.Grant("hearts", 1, ""), grants.get(0));
        assertEquals(SeasonPassRules.MAX_HEARTS_PER_TIER, grants.get(1).amount(), "one tier cannot grant a pile");
        assertEquals(new SeasonPassRules.Grant("season_cosmetic", 1, "AURA"), grants.get(2));
        assertEquals("KILL_EFFECT", grants.get(3).id());
        assertEquals(4, grants.size(), "an unknown exclusive category is skipped");

        List<SeasonPassRules.Grant> gear = SeasonPassRules.parse(
                "gear:Scythe;gear:elytra;gear:sword;reward:ancient_debris:3;reward:mace:999;reward:totem:x");
        assertEquals(new SeasonPassRules.Grant("season_gear", 1, "SCYTHE"), gear.get(0));
        assertEquals("WINGS", gear.get(1).id());
        assertEquals(new SeasonPassRules.Grant("reward", 3, "ancient_debris"), gear.get(2));
        assertEquals(SeasonPassRules.MAX_REWARD_COUNT, gear.get(3).amount());
        assertEquals(4, gear.size(), "unknown gear and a bad count are skipped");
    }

    /**
     * Every default tier must pay something real, nothing but the scarce kinds, and the
     * whole track must stay inside the Shard and heart budget the economy can absorb.
     */
    @Test
    void theDefaultTrackIsScarceRealAndOnBudget() throws Exception {
        String store = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/bot/mgx/accessbridge/GameVariableStore.java"));
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "text\\(\"(season\\.reward\\.[a-z0-9-]+)\"[\\s\\S]*?,\\s*\"([^\"]*)\",\\s*200\\);").matcher(store);
        java.util.Map<String, String> specs = new java.util.HashMap<>();
        while (matcher.find()) specs.put(matcher.group(1), matcher.group(2));
        assertTrue(specs.size() >= 9, "found " + specs.keySet());
        long shards = 0;
        long hearts = 0;
        Set<String> exclusives = new HashSet<>();
        Set<String> gear = new HashSet<>();
        for (int tier = 1; tier <= 50; tier++) {
            String spec = specs.get(SeasonPassRules.rewardKey(tier, specs::containsKey));
            List<SeasonPassRules.Grant> grants = SeasonPassRules.parse(spec);
            assertTrue(!grants.isEmpty(), "tier " + tier + " pays nothing");
            for (SeasonPassRules.Grant grant : grants) {
                switch (grant.kind()) {
                    case "shards" -> shards += grant.amount();
                    case "hearts" -> hearts += grant.amount();
                    case "season_cosmetic" -> assertTrue(exclusives.add(grant.id()), "exclusive paid twice");
                    case "season_gear" -> assertTrue(gear.add(grant.id()), "gear paid twice");
                    case "reward" -> assertTrue(CrateCatalog.find(grant.id()).isPresent(),
                            grant.id() + " is not a registered crate reward");
                    case "cosmetic" -> assertTrue(CosmeticCatalog.find(grant.id()).isPresent(), grant.id());
                    default -> throw new AssertionError("tier " + tier + " pays " + grant.kind()
                            + ", which is minted too freely to mean anything");
                }
            }
        }
        assertEquals(Set.of("AURA", "TRAIL", "KILL_EFFECT"), exclusives);
        assertEquals(Set.of("SCYTHE", "PICKAXE", "AXE", "WINGS"), gear);
        assertEquals(3, hearts);
        assertTrue(shards >= 5 && shards <= 12, "a full track pays " + shards + " Shards");
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
