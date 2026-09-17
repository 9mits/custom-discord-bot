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
        assertEquals(0, SeasonPassRules.levelFor(SeasonPassRules.QuestType.KILL_MOBS, 4));
        assertEquals(1, SeasonPassRules.levelFor(SeasonPassRules.QuestType.KILL_MOBS, 5));
        assertEquals(3, SeasonPassRules.levelFor(SeasonPassRules.QuestType.KILL_MOBS, 100));
        assertEquals(8, SeasonPassRules.levelFor(SeasonPassRules.QuestType.KILL_MOBS, 1_000_000));
    }

    @Test
    void everyQuestLineStartsWithAFirstSessionGoal() {
        assertEquals(5, firstTarget(SeasonPassRules.QuestType.KILL_MOBS));
        assertEquals(3, firstTarget(SeasonPassRules.QuestType.MINE_ORES));
        assertEquals(10, firstTarget(SeasonPassRules.QuestType.HARVEST_CROPS));
        assertEquals(1, firstTarget(SeasonPassRules.QuestType.OPEN_CRATES));
        assertEquals(2_500, firstTarget(SeasonPassRules.QuestType.SELL_MONEY));
        assertEquals(10, firstTarget(SeasonPassRules.QuestType.PLAY_MINUTES));
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
    void everyLadderFitsInsideTheThreeDaysASeasonRuns() throws Exception {
        // At the live server's own rates — about 50 hostile kills and 12 ores an hour —
        // three days of real play is a few hundred kills and a couple of hundred ores. The
        // last rung is meant to be a stretch, not a six-week grind left permanently unfinished.
        String store = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/bot/mgx/accessbridge/GameVariableStore.java"));
        String length = store.substring(store.indexOf("integer(\"season.length-days\""));
        assertTrue(length.substring(0, length.indexOf(");")).contains("3, 1, 365"),
                "a season runs three days, so the ladders below are sized for three days");
        assertTrue(SeasonPassRules.quest(SeasonPassRules.QuestType.KILL_MOBS, 7).orElseThrow().target() <= 3_000);
        assertTrue(SeasonPassRules.quest(SeasonPassRules.QuestType.MINE_ORES, 6).orElseThrow().target() <= 800);
        assertTrue(SeasonPassRules.quest(SeasonPassRules.QuestType.PLAY_MINUTES, 5).orElseThrow().target()
                <= 3 * 24 * 60, "nobody can play more minutes than the season has");
        assertTrue(SeasonPassRules.quest(SeasonPassRules.QuestType.SELL_MONEY, 6).orElseThrow().target() <= 10_000_000);
        // The first rung of every line is still one session's work.
        for (SeasonPassRules.QuestType type : SeasonPassRules.QuestType.values()) {
            assertTrue(SeasonPassRules.quest(type, 0).orElseThrow().xp() > 0, type + " pays for its first rung");
        }
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

    /**
     * The owner's rules for the track (September 2026): every tier pays something a player
     * spends or wears, never a collectable (discs, sponges, trims, skulls) and never a season
     * consumable; consumables may return only at a larger amount; and every reward has a real
     * icon in the dialog rows, never a flat block face or a missing sprite.
     */
    @Test
    void theDefaultTrackPaysOnlyUsefulRewardsWithRealIcons() throws Exception {
        String store = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/bot/mgx/accessbridge/GameVariableStore.java"));
        String block = store.substring(store.indexOf("text(\"season.reward.track\""),
                store.indexOf("text(\"season.reward.fallback\""));
        String track = block.substring(block.indexOf("reward:fortune_potion_i:2"), block.lastIndexOf("\", 4000);"))
                .replaceAll("\"\\s*\\+\\s*\"", "");
        String shop = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/bot/mgx/accessbridge/ShopCatalog.java"));
        java.nio.file.Path textures = java.nio.file.Path.of("../assets/resourcepack/src/assets/mgx/textures");
        Set<String> usefulVanilla = Set.of("netherite_ingot", "totem_of_undying", "trident", "nether_star");
        Set<String> usefulBooks = Set.of("mending");
        assertEquals(50, track.split("\\|", -1).length, "one entry per tier");

        long shards = 0;
        long hearts = 0;
        int giftbagTier = 0;
        Set<String> books = new HashSet<>();
        Map<String, Long> repeated = new HashMap<>();
        Set<String> exclusives = new HashSet<>();
        Set<String> gear = new HashSet<>();
        for (int tier = 1; tier <= 50; tier++) {
            String entry = SeasonPassRules.trackEntry(track, tier);
            assertFalse(entry.contains("music_disc") || entry.contains("smithing_template") || entry.contains("sponge"),
                    "tier " + tier + " pays a collectable nobody uses: " + entry);
            List<SeasonPassRules.Grant> grants = SeasonPassRules.parse(entry);
            assertEquals(entry.split(";").length, grants.size(), "tier " + tier + " has a part that does not parse");
            assertFalse(grants.isEmpty(), "tier " + tier + " pays nothing");
            for (SeasonPassRules.Grant grant : grants) {
                switch (grant.kind()) {
                    case "vanilla" -> {
                        assertTrue(usefulVanilla.contains(grant.id()), grant.id() + " is not a reward players use");
                        escalates(repeated, "vanilla:" + grant.id(), grant.amount());
                        org.bukkit.Material material = org.bukkit.Material.matchMaterial(grant.id());
                        assertTrue(material != null, grant.id() + " is not a real item");
                        assertFalse(shop.contains("\"" + material.name() + "\""), grant.id() + " is sold in /shop");
                        assertTrue(SeasonPassMenu.FLAT_ITEM_TEXTURES.contains(grant.id()),
                                grant.id() + " has no inventory texture for its dialog icon");
                        assertEquals("item/" + grant.id(), SeasonPassMenu.vanillaSprite(grant.id()));
                    }
                    case "book" -> {
                        assertTrue(usefulBooks.contains(grant.id()), grant.id() + " is a niche book");
                        assertTrue(books.add(grant.id()), grant.id() + " book is paid twice");
                    }
                    case "reward" -> {
                        CrateCatalog.Reward reward = CrateCatalog.find(grant.id())
                                .orElseThrow(() -> new AssertionError(grant.id() + " is not a crate reward"));
                        assertFalse(reward.cosmetic(), grant.id() + " should be a season exclusive instead");
                        assertTrue(SeasonPassMenu.knownRewardRarity(grant.id()).isPresent(),
                                grant.id() + " has no pass rarity");
                        escalates(repeated, "reward:" + grant.id(), grant.amount());
                        String sprite = SeasonPassMenu.spriteOf(reward);
                        if (sprite.startsWith("mgx:item/")) {
                            assertTrue(java.nio.file.Files.isRegularFile(textures.resolve(
                                    sprite.substring("mgx:".length()) + ".png")), sprite + " has no texture");
                        } else {
                            assertEquals("item/enchanted_book", sprite, grant.id() + " has no real icon");
                        }
                    }
                    case "shards" -> shards += grant.amount();
                    case "hearts" -> hearts += grant.amount();
                    case "giftbag" -> {
                        assertEquals(0, giftbagTier, "only one tier pays a Mythic Giftbag");
                        giftbagTier = tier;
                    }
                    case "season_cosmetic" -> assertTrue(exclusives.add(grant.id()), "exclusive paid twice");
                    case "season_gear" -> assertTrue(gear.add(grant.id()), "gear paid twice");
                    default -> throw new AssertionError("tier " + tier + " pays " + grant.kind());
                }
            }
        }
        assertEquals(Set.of("AURA", "TRAIL", "KILL_EFFECT"), exclusives);
        assertEquals(java.util.Arrays.stream(SeasonGear.Piece.values()).map(Enum::name)
                .collect(java.util.stream.Collectors.toSet()), gear, "every gear piece is on the track once");
        assertEquals(2, hearts, "Season Hearts affect PvP, so a full pass pays two");
        assertEquals(50, giftbagTier, "the final tier pays the Mythic Giftbag");
        assertTrue(shards >= 24 && shards <= 36, "a full track pays " + shards + " Shards");
        assertTrue(java.nio.file.Files.isRegularFile(textures.resolve(
                SeasonPassMenu.HEART_SPRITE.substring("mgx:".length()) + ".png")), "the heart icon ships");
        assertEquals("item/golden_apple", SeasonPassMenu.vanillaSprite("enchanted_golden_apple"));
        assertEquals("item/nether_star", SeasonPassMenu.vanillaSprite("beacon"),
                "a block has no flat icon, so it gets a stand-in rather than its face texture");
    }

    private static void escalates(Map<String, Long> seen, String key, long amount) {
        Long previous = seen.put(key, amount);
        assertTrue(previous == null || amount > previous, key + " must pay more when it returns later in the pass");
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
