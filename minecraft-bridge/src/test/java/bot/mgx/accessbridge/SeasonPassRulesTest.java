package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
            long previousWork = 0;
            long previousXp = 0;
            for (int level = 0; level < type.levels(); level++) {
                SeasonPassRules.Quest quest = SeasonPassRules.quest(type, level).orElseThrow();
                assertTrue(quest.target() > previousTarget, type + " level " + level + " must be harder");
                long newWork = quest.target() - previousTarget;
                assertTrue(newWork > previousWork,
                        type + " level " + level + " must require more new work than the previous level");
                assertTrue(quest.xp() > previousXp, type + " level " + level + " must pay more");
                previousWork = newWork;
                previousTarget = quest.target();
                previousXp = quest.xp();
            }
            assertTrue(SeasonPassRules.quest(type, type.levels()).isEmpty(), "a finished ladder has no next rung");
            assertTrue(type.levels() >= 5, type + " needs a real ladder");
        }
        assertEquals(0, SeasonPassRules.levelFor(SeasonPassRules.QuestType.KILL_MOBS, 9));
        assertEquals(1, SeasonPassRules.levelFor(SeasonPassRules.QuestType.KILL_MOBS, 10));
        assertEquals(3, SeasonPassRules.levelFor(SeasonPassRules.QuestType.KILL_MOBS, 300));
        assertEquals(8, SeasonPassRules.levelFor(SeasonPassRules.QuestType.KILL_MOBS, 1_000_000));
    }

    @Test
    void everyQuestLineStartsWithAFirstSessionGoal() {
        assertEquals(10, firstTarget(SeasonPassRules.QuestType.KILL_MOBS));
        assertEquals(5, firstTarget(SeasonPassRules.QuestType.MINE_ORES));
        assertEquals(20, firstTarget(SeasonPassRules.QuestType.HARVEST_CROPS));
        assertEquals(1, firstTarget(SeasonPassRules.QuestType.OPEN_CRATES));
        assertEquals(5_000, firstTarget(SeasonPassRules.QuestType.SELL_MONEY));
        assertEquals(15, firstTarget(SeasonPassRules.QuestType.PLAY_MINUTES));
        assertEquals(1, firstTarget(SeasonPassRules.QuestType.WIN_PVP));
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

    private static long firstTarget(SeasonPassRules.QuestType type) {
        return SeasonPassRules.quest(type, 0).orElseThrow().target();
    }

    @Test
    void rewardSpecsParseAndSkipNonsense() {
        List<SeasonPassRules.Grant> grants = SeasonPassRules.parse(
                "keys:3; shards:1 ;money:20,000;cosmetic:Prismatic_Trail;bogus:5;keys:-1;"
                        + "reward:crate_luck_v;giftbag:99");
        assertEquals(5, grants.size());
        assertEquals(new SeasonPassRules.Grant("shards", 1, ""), grants.get(1));
        assertEquals("prismatic_trail", grants.get(2).id());
        assertTrue(grants.stream().noneMatch(grant -> grant.kind().equals("money")),
                "money is never a reward: its value moves with the economy");
        assertEquals(SeasonPassRules.MAX_GIFTBAGS_PER_TIER, grants.get(4).amount(),
                "even a mistuned tier cannot flood Giftbags");
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

    @Test
    void eachTierReadsItsOwnTrackEntry() {
        String track = "a:1 | b:2 ;c:3|  | d:4";
        assertEquals("a:1", SeasonPassRules.trackEntry(track, 1));
        assertEquals("b:2 ;c:3", SeasonPassRules.trackEntry(track, 2));
        assertEquals("", SeasonPassRules.trackEntry(track, 3), "an empty entry pays the fallback");
        assertEquals("d:4", SeasonPassRules.trackEntry(track, 4));
        assertEquals("", SeasonPassRules.trackEntry(track, 5), "past the end pays the fallback");
        assertEquals("", SeasonPassRules.trackEntry(track, 0));
    }

    @Test
    void vanillaItemsAndBooksParse() {
        List<SeasonPassRules.Grant> grants = SeasonPassRules.parse(
                "vanilla:Nether_Star;vanilla:wind_charge:16;vanilla:diamond:999;vanilla:not an item;vanilla:echo_shard:x;"
                        + "book:mending;book:swift_sneak:3;book:sharpness:99;book:bad-name");
        assertEquals(new SeasonPassRules.Grant("vanilla", 1, "nether_star"), grants.get(0));
        assertEquals(16, grants.get(1).amount());
        assertEquals(SeasonPassRules.MAX_REWARD_COUNT, grants.get(2).amount());
        assertEquals(new SeasonPassRules.Grant("book", 1, "mending"), grants.get(3));
        assertEquals(new SeasonPassRules.Grant("book", 3, "swift_sneak"), grants.get(4));
        assertEquals(SeasonPassRules.MAX_BOOK_LEVEL, grants.get(5).amount(), "never past any vanilla maximum");
        assertEquals(6, grants.size(), "malformed ids and counts are skipped");
        assertEquals("Silence Armor Trim", SeasonPassService.vanillaName("silence_armor_trim_smithing_template"));
        assertEquals("Music Disc Pigstep", SeasonPassService.vanillaName("music_disc_pigstep"));
    }

    @Test
    void theRallyHornNeverDecidesAFight() {
        List<SeasonPassRules.Grant> grants = SeasonPassRules.parse("item:Rally_Horn:2;item:miners_tonic");
        assertEquals(List.of(new SeasonPassRules.Grant("season_item", 2, "rally_horn")), grants,
                "only the horn is a season consumable");
        for (SeasonItemCatalog.Item item : SeasonItemCatalog.Item.values()) {
            for (SeasonItemCatalog.Effect effect : item.effects) {
                assertFalse(SeasonItemCatalog.COMBAT_EFFECTS.contains(effect.type()),
                        item.id + " grants " + effect.type() + ", which wins fights");
                assertTrue(effect.seconds() <= 300 && effect.amplifier() <= 1, item.id + " is too strong: " + effect);
            }
        }
        assertEquals("Haste II for 5 min", SeasonItemCatalog.Item.RALLY_HORN.effects.get(0).describe());
    }

    /**
     * The owner's rules for the track (September 2026): vanilla first, custom items only at
     * milestones from tier 10, no copyable trim-template filler, nothing the shop sells or the
     * End gives, and useful consumables may repeat only at a larger amount later in the pass.
     */
    @Test
    void theDefaultTrackIsVanillaFirstValuableRealAndOnBudget() throws Exception {
        String store = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/bot/mgx/accessbridge/GameVariableStore.java"));
        String block = store.substring(store.indexOf("text(\"season.reward.track\""),
                store.indexOf("text(\"season.reward.fallback\""));
        String track = block.substring(block.indexOf("vanilla:golden_apple:4"), block.lastIndexOf("\", 4000);"))
                .replaceAll("\"\\s*\\+\\s*\"", "");
        String shop = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/bot/mgx/accessbridge/ShopCatalog.java"));
        Set<String> end = Set.of("elytra", "shulker_shell", "shulker_box", "dragon_egg", "dragon_head",
                "dragon_breath", "end_crystal", "chorus_fruit", "spire_armor_trim_smithing_template");
        Set<String> gameBreaking = Set.of("spawner", "budding_amethyst", "trial_spawner", "vault", "mace");
        assertEquals(50, track.split("\\|", -1).length, "one entry per tier");

        long shards = 0;
        long hearts = 0;
        long giftbags = 0;
        Set<String> items = new HashSet<>();
        Map<String, Integer> repeatedVanilla = new HashMap<>();
        Set<String> exclusives = new HashSet<>();
        Set<String> gear = new HashSet<>();
        for (int tier = 1; tier <= 50; tier++) {
            String entry = SeasonPassRules.trackEntry(track, tier);
            List<SeasonPassRules.Grant> grants = SeasonPassRules.parse(entry);
            assertEquals(entry.split(";").length, grants.size(), "tier " + tier + " has a part that does not parse");
            assertFalse(grants.isEmpty(), "tier " + tier + " pays nothing");
            for (SeasonPassRules.Grant grant : grants) {
                boolean custom = !grant.kind().equals("vanilla") && !grant.kind().equals("book")
                        && !grant.kind().equals("shards") && !grant.kind().equals("hearts");
                assertFalse(custom && tier < 10, "tier " + tier + " pays a custom item before tier 10");
                switch (grant.kind()) {
                    case "vanilla" -> {
                        assertFalse(grant.id().endsWith("_smithing_template"),
                                grant.id() + " is copyable filler, not a lasting tier reward");
                        int previous = repeatedVanilla.getOrDefault(grant.id(), 0);
                        if (previous > 0) {
                            assertTrue(Set.of("netherite_ingot", "totem_of_undying",
                                            "enchanted_golden_apple", "netherite_block").contains(grant.id()),
                                    grant.id() + " repeats without being a deliberately escalating consumable");
                            assertTrue(grant.amount() > previous,
                                    grant.id() + " must pay more when it returns later in the pass");
                        }
                        repeatedVanilla.put(grant.id(), (int) grant.amount());
                        org.bukkit.Material material = org.bukkit.Material.matchMaterial(grant.id());
                        assertTrue(material != null, grant.id() + " is not a real item");
                        assertFalse(shop.contains("\"" + material.name() + "\""), grant.id() + " is sold in /shop");
                        assertFalse(end.contains(grant.id()), grant.id() + " comes from the End");
                        assertFalse(gameBreaking.contains(grant.id()), grant.id() + " breaks the game");
                    }
                    case "book" -> assertTrue(items.add("book:" + grant.id()), grant.id() + " book is paid twice");
                    case "shards" -> shards += grant.amount();
                    case "hearts" -> hearts += grant.amount();
                    case "giftbag" -> giftbags += grant.amount();
                    case "season_cosmetic" -> assertTrue(exclusives.add(grant.id()), "exclusive paid twice");
                    case "season_gear" -> assertTrue(gear.add(grant.id()), "gear paid twice");
                    case "season_item" -> assertTrue(items.add(grant.id()), grant.id() + " is paid twice");
                    default -> throw new AssertionError("tier " + tier + " pays " + grant.kind());
                }
            }
        }
        assertEquals(Set.of("AURA", "TRAIL", "KILL_EFFECT"), exclusives);
        assertEquals(java.util.Arrays.stream(SeasonGear.Piece.values()).map(Enum::name)
                .collect(java.util.stream.Collectors.toSet()), gear, "every gear piece is on the track once");
        assertEquals(2, hearts, "Season Hearts affect PvP, so a full pass pays two");
        assertEquals(1, giftbags, "only the final tier pays the Mythic Giftbag");
        assertTrue(shards >= 24 && shards <= 36, "a full track pays " + shards + " Shards");
        for (int tier = 41; tier <= 50; tier++) {
            assertFalse(SeasonPassRules.parse(SeasonPassRules.trackEntry(track, tier)).isEmpty(),
                    "late tier " + tier + " must stay rewarding");
        }
        for (String sprite : List.of("nether_star", "beacon", "conduit", "sniffer_egg", "heavy_core", "sponge")) {
            assertTrue(SeasonPassMenu.vanillaSprite(sprite).startsWith("item/")
                    || SeasonPassMenu.vanillaSprite(sprite).startsWith("block/"));
        }
        assertEquals(SeasonPassMenu.Rarity.LEGENDARY, SeasonPassMenu.vanillaRarity("nether_star"));
        assertEquals("item/golden_apple", SeasonPassMenu.vanillaSprite("enchanted_golden_apple"));
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
