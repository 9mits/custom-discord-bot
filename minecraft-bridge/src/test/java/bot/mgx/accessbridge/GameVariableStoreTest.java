package bot.mgx.accessbridge;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Duration;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GameVariableStoreTest {
    @TempDir
    Path temporary;

    private GameVariableStore store() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.set("amethyst-events.minimum-delay-minutes", 30);
        config.set("amethyst-events.maximum-delay-minutes", 90);
        return new GameVariableStore(temporary.resolve("game-variables.json"), config);
    }

    @Test
    void overridesPersistAndResetToTheCatalogDefault() throws Exception {
        GameVariableStore variables = store();
        assertEquals(2, variables.keyCost(CrateKind.AMETHYST));
        variables.set("crate.amethyst.key-cost", "7");
        assertEquals(7, variables.keyCost(CrateKind.AMETHYST));

        GameVariableStore reopened = store();
        assertEquals(7, reopened.keyCost(CrateKind.AMETHYST));
        reopened.reset("crate.amethyst.key-cost");
        assertEquals(2, reopened.keyCost(CrateKind.AMETHYST));
    }

    @Test
    void questLaddersAreSettingsAndRejectABrokenList() throws Exception {
        GameVariableStore variables = store();
        for (SeasonPassRules.QuestType type : SeasonPassRules.QuestType.values()) {
            String key = "season.quest." + type.key() + ".targets";
            assertEquals(SeasonPassRules.ladderText(type.defaultTargets()), variables.string(key));
        }
        variables.set("season.quest.kill_mobs.targets", "50, 500, 5000");
        assertEquals("50, 500, 5000", variables.string("season.quest.kill_mobs.targets"));
        // The undo trail used to cast every value to a number and threw on any text edit.
        GameVariableStore reopened = store();
        assertEquals("50, 500, 5000", reopened.string("season.quest.kill_mobs.targets"));
        assertEquals("50, 500, 5000", reopened.history().recent(1).getFirst().changes().getFirst().after());
        assertThrows(IllegalArgumentException.class,
                () -> variables.set("season.quest.kill_mobs.targets", "500, 50"));
        assertThrows(IllegalArgumentException.class,
                () -> variables.set("season.quest.level-xp", "lots"));
    }

    @Test
    void dragonCountdownAndSummoningSafetyAreConfigurable() throws Exception {
        GameVariableStore variables = store();
        assertEquals(45, variables.integer("dragon-event.summoning-timeout-seconds"));
        assertEquals(5d, variables.decimal("dragon-event.egg-sound-volume"));
        assertEquals(1.4d, variables.decimal("dragon-event.egg-beacon-pitch"));
        assertEquals("AMETHYST DRAGON AWAKENS IN <time>",
                variables.string("dragon-event.countdown-bossbar-text"));
        assertEquals("PURPLE", variables.string("dragon-event.countdown-bossbar-color"));
        assertEquals("END", variables.string("dragon-event.sky-style"));
        assertEquals(20, variables.integer("dragon-event.void-rescue-y"));
        assertEquals(6000, variables.integer("dragon-event.bright-sky-time"));
        assertEquals(18000, variables.integer("dragon-event.fight-sky-time"));
        assertEquals(true, variables.bool("dragon-event.pillar-lightning-enabled"));
        assertEquals(30, variables.integer("dragon-event.minions-per-wave"));
        assertEquals(80, variables.integer("dragon-event.minion-maximum-alive"));
        assertEquals(2, variables.integer("dragon-event.aggressive-attack-seconds"));
        assertEquals(32, variables.integer("dragon-event.perch-interval-seconds"));
        assertEquals(12, variables.integer("dragon-event.perch-duration-seconds"));
        assertEquals(25d, variables.decimal("dragon-event.rage-health-step-percent"));
        assertEquals(8, variables.integer("dragon-event.chaos-interval-seconds"));
        assertEquals(48, variables.integer("dragon-event.reward-beacon-height"));
        assertEquals(2, variables.integer("dragon-event.reward-beacon-spacing"));
        assertEquals(3, variables.integer("dragon-event.reward-beacon-ring-count"));
        assertEquals(1200, variables.integer("dragon-event.reward-crate-arrival-particles"));
        assertTrue(variables.bool("dragon-event.reward-crate-arrival-lightning"));
        assertEquals(8, variables.integer("dragon-event.reward-crate-arrival-lightning-count"));
        assertEquals(108, variables.integer("dragon-event.crystal-key-effect-count"));
        assertEquals(108, variables.integer("dragon-event.wave-key-effect-count"));
        assertEquals(336, variables.integer("dragon-event.death-key-effect-count"));
        assertEquals(6, variables.integer("dragon-event.key-effect-waves"));
        assertEquals("AMETHYST DRAGON — EVENT REPORT",
                variables.string("dragon-event.stats-header"));
    }

    @Test
    void pvpLobbyPresentationAndRegisteredPortalAreRuntimeConfigurable() throws Exception {
        GameVariableStore variables = store();
        assertEquals(2.4d, variables.decimal("pvp-competitive.lobby-title-scale"));
        assertEquals(2.0d, variables.decimal("pvp-competitive.lobby-gate-title-scale"));
        assertEquals(1.8d, variables.decimal("pvp-competitive.lobby-leaderboard-title-scale"));
        assertEquals(1.25d, variables.decimal("pvp-competitive.lobby-line-scale"));
        assertEquals(24d, variables.decimal("pvp-competitive.lobby-label-view-distance"));
        assertEquals(12d, variables.decimal("pvp-competitive.lobby-board-view-distance"));
        assertEquals(34d, variables.decimal("pvp-competitive.lobby-leaderboard-view-distance"));
        assertEquals(8, variables.integer("pvp-competitive.lobby-portal-particle-count"));
        assertEquals(3.5d,
                variables.decimal("pvp-competitive.lobby-gate-exit-distance"));
        assertEquals(6,
                variables.integer("pvp-competitive.lobby-gate-menu-delay-ticks"));
        assertEquals(48, variables.integer("pvp-competitive.portal-selection-distance"));
        assertEquals(2.5d, variables.decimal("pvp-competitive.portal-display-height"));
        assertEquals("PVP LOBBY PORTAL", variables.string("pvp-competitive.portal-title"));
        assertEquals("WALK THROUGH • CHOOSE A FIGHT",
                variables.string("pvp-competitive.portal-status"));
        assertTrue(variables.bool("pvp-competitive.portal-effects-enabled"));
        assertEquals(12, variables.integer("pvp-competitive.portal-particle-count"));
        assertEquals(60, variables.integer("pvp-competitive.portal-suppression-ticks"));
    }

    @Test
    void pvpQueueAndLastStandingFeedbackAreRuntimeConfigurable() throws Exception {
        GameVariableStore variables = store();
        assertTrue(variables.bool("pvp-competitive.queue-boss-bar"));
        assertEquals(3,
                variables.integer("pvp-competitive.queue-actionbar-interval-seconds"));
        // Last Standing is compact and uses Minecraft's own world-border wall, so the
        // particle wall and its action-bar guidance are gone for good.
        assertEquals(80, variables.integer("pvp-competitive.ffa-arena-diameter"));
        assertEquals(12, variables.integer("pvp-competitive.ffa-diameter-per-player"));
        assertEquals(16d, variables.decimal("pvp-competitive.ffa-minimum-border"));
        assertEquals(8, variables.integer("pvp-competitive.ffa-shrink-steps"));
        assertFalse(variables.find("pvp-competitive.ffa-border-particles").isPresent());
        assertFalse(variables.find("pvp-competitive.ffa-border-guidance").isPresent());
    }

    @Test
    void everyAmethystArmorPieceUsesItsRealEquipmentSlot() {
        assertEquals(EquipmentSlot.HEAD, AmethystItemService.armorSlot("helmet"));
        assertEquals(EquipmentSlot.CHEST, AmethystItemService.armorSlot("chestplate"));
        assertEquals(EquipmentSlot.LEGS, AmethystItemService.armorSlot("leggings"));
        assertEquals(EquipmentSlot.FEET, AmethystItemService.armorSlot("boots"));
    }

    /**
     * The ceiling on Airdrops standing at once. Each one holds chunk tickets and a live
     * garrison, so this is a server-load figure staff can turn down, not a gameplay one -
     * and it must never reach zero, which would make the staff command permanently
     * refuse.
     */
    @Test
    void airdropCapacityIsAdjustableAndNeverZero() throws Exception {
        GameVariableStore variables = store();
        assertEquals(5, variables.integer("airdrop.maximum-active"));
        variables.set("airdrop.maximum-active", "12");
        assertEquals(12, variables.integer("airdrop.maximum-active"));
        assertThrows(IllegalArgumentException.class,
                () -> variables.set("airdrop.maximum-active", "0"));
        assertThrows(IllegalArgumentException.class,
                () -> variables.set("airdrop.maximum-active", "21"));
    }

    @Test
    void airdropDistanceBandsAreLiveAndCannotInvert() throws Exception {
        GameVariableStore variables = store();
        assertEquals(1_000, variables.integer("airdrop.rarity-radius.common.minimum"));
        assertEquals(2_000, variables.integer("airdrop.rarity-radius.common.maximum"));
        assertEquals(10_000, variables.integer("airdrop.rarity-radius.mythic.minimum"));
        assertEquals(25_000, variables.integer("airdrop.rarity-radius.mythic.maximum"));

        variables.set("airdrop.rarity-radius.common.minimum", "1,500");
        assertEquals(1_500, variables.integer("airdrop.rarity-radius.common.minimum"));
        assertThrows(IllegalArgumentException.class,
                () -> variables.set("airdrop.rarity-radius.common.maximum", "1499"));
    }

    @Test
    void invalidRangesCannotReachTheLiveTable() throws Exception {
        GameVariableStore variables = store();
        assertThrows(IllegalArgumentException.class,
                () -> variables.set("crate.default.key-cost", "0"));
        assertThrows(IllegalArgumentException.class,
                () -> variables.set("amethyst-events.minimum-delay-minutes", "91"));
        assertThrows(IllegalArgumentException.class,
                () -> variables.set("airdrop.rarity.common.minimum-keys", "97"));
        assertThrows(IllegalArgumentException.class,
                () -> variables.set("not-a-variable", "1"));
    }

    @Test
    void changedCrateWeightControlsTheNextRollImmediately() throws Exception {
        GameVariableStore variables = store();
        CrateCatalog.Reward wanted = CrateKind.DEFAULT.rewards().getFirst();
        for (CrateCatalog.Reward reward : CrateKind.DEFAULT.rewards()) {
            variables.set(
                    "crate.default.reward." + reward.id() + ".weight",
                    reward == wanted ? "10000000" : "1"
            );
        }
        int hits = 0;
        RandomGenerator random = new java.util.Random(19);
        for (int index = 0; index < 100; index++) {
            if (variables.randomReward(CrateKind.DEFAULT, 100, random) == wanted) hits++;
        }
        assertTrue(hits >= 99);
    }

    @Test
    void snapshotMarksOnlyRealOverridesAndIncludesCurrentChance() throws Exception {
        GameVariableStore variables = store();
        variables.set("crate.keys-per-hour", "9");
        var rows = variables.snapshot().getAsJsonArray("variables");
        var hourly = rows.asList().stream().map(value -> value.getAsJsonObject())
                .filter(row -> row.get("key").getAsString().equals("crate.keys-per-hour"))
                .findFirst().orElseThrow();
        var reward = rows.asList().stream().map(value -> value.getAsJsonObject())
                .filter(row -> row.get("key").getAsString().startsWith("crate.default.reward."))
                .findFirst().orElseThrow();
        assertTrue(hourly.get("overridden").getAsBoolean());
        assertEquals(9, hourly.get("value").getAsInt());
        assertTrue(reward.has("chance_percent"));
        assertTrue(reward.get("chance_percent").getAsJsonPrimitive().isString());
        assertTrue(Double.parseDouble(reward.get("chance_percent").getAsString()) > 0d);
    }

    @Test
    void onlineTiersEscalateAndOnlinePlayersAddCappedKeys() throws Exception {
        GameVariableStore variables = store();

        assertEquals(1, variables.onlineRewardTier(0).number());
        assertEquals(2, variables.onlineRewardTier(Duration.ofHours(3).toSeconds()).number());
        assertEquals(3, variables.nextOnlineRewardTier(
                Duration.ofHours(3).toSeconds()
        ).orElseThrow().number());
        assertEquals(5, variables.onlineRewardTier(Duration.ofHours(71).toSeconds()).number());
        GameVariableStore.OnlineRewardTier rare = variables.onlineRewardTier(
                Duration.ofHours(72).toSeconds()
        );
        assertEquals(6, rare.number());
        // Rebalanced so an AFK session is no longer the best income on the server.
        assertEquals(3, rare.bonusKeys());
        assertEquals(2, rare.afkOpenings(), "the top tier pays two AFK Crate openings an interval");
        assertEquals(25, variables.integer("online-rewards.afk-key-percent"));
        assertTrue(variables.find("online-rewards.tier.6.shard-one-in").isEmpty(),
                "Shards and ores no longer roll straight into the inventory");
        assertTrue(variables.nextOnlineRewardTier(Duration.ofHours(72).toSeconds()).isEmpty());

        assertEquals(0, variables.onlinePopulationBonusKeys(4));
        assertEquals(1, variables.onlinePopulationBonusKeys(5));
        assertEquals(1, variables.onlinePopulationBonusKeys(9));
        assertEquals(2, variables.onlinePopulationBonusKeys(10));
        assertEquals(4, variables.onlinePopulationBonusKeys(100));
    }

    @Test
    void onlineTierThresholdsMustRemainStrictlyOrdered() throws Exception {
        GameVariableStore variables = store();
        assertThrows(IllegalArgumentException.class,
                () -> variables.set("online-rewards.tier.2.minimum-hours", "1"));
        assertThrows(IllegalArgumentException.class,
                () -> variables.set("online-rewards.tier.5.minimum-hours", "72"));
        variables.set("online-rewards.tier.6.minimum-hours", "100");
        assertEquals(5, variables.onlineRewardTier(Duration.ofHours(80).toSeconds()).number());
        assertThrows(IllegalArgumentException.class,
                () -> variables.set("huge-amethyst.wave.2.health-percent", "80"));
    }

    @Test
    void oldAfkRewardOverridesMigrateToTheOnlineNamespace() throws Exception {
        Path file = temporary.resolve("game-variables.json");
        Files.writeString(file, "{\"afk-rewards.enabled\":false,"
                + "\"afk-rewards.online.maximum-bonus-keys\":9}");

        GameVariableStore variables = store();

        assertFalse(variables.bool("online-rewards.enabled"));
        assertEquals(9, variables.integer("online-rewards.population.maximum-bonus-keys"));
        assertFalse(variables.bool("afk-rewards.enabled"));
        String migrated = Files.readString(file);
        assertTrue(migrated.contains("online-rewards.enabled"));
        assertTrue(migrated.contains("online-rewards.population.maximum-bonus-keys"));
        assertFalse(migrated.contains("afk-rewards"));
    }

    @Test
    void shippedScytheBonusesMigrateButCustomBalanceSurvives() throws Exception {
        Path file = temporary.resolve("game-variables.json");
        Files.writeString(file, "{\"pvp-rank-rewards.first-bonus-damage\":1.5,"
                + "\"pvp-rank-rewards.second-bonus-damage\":0.42,"
                + "\"pvp-rank-rewards.third-bonus-damage\":0.5}");

        GameVariableStore variables = store();

        assertEquals(0.35d, variables.decimal("pvp-rank-rewards.first-bonus-damage"));
        assertEquals(0.42d, variables.decimal("pvp-rank-rewards.second-bonus-damage"));
        assertEquals(0.10d, variables.decimal("pvp-rank-rewards.third-bonus-damage"));
    }

    @Test
    void shippedDragonPortalClockMigratesToTheLiveCountdown() throws Exception {
        Path file = temporary.resolve("game-variables.json");
        Files.writeString(file, "{\"dragon-event.portal-open-status\":\"OPEN UNTIL <until>\"}");

        GameVariableStore variables = store();

        assertEquals("OPEN • CLOSES IN <time>",
                variables.string("dragon-event.portal-open-status"));
        String migrated = Files.readString(file);
        assertTrue(migrated.contains("OPEN • CLOSES IN <time>"));
        assertFalse(migrated.contains("OPEN UNTIL <until>"));
    }

    @Test
    void shippedSeasonQuestLaddersMigrateButOwnerProgressionSurvives() throws Exception {
        Path file = temporary.resolve("game-variables.json");
        Files.writeString(file, "{"
                + "\"season.quest.kill_mobs.targets\":\"100, 300, 750, 1500, 3000, 6000, 12000, 25000\","
                + "\"season.quest.mine_ores.targets\":\"50, 150, 400, 800, 1500, 3000, 6000\","
                + "\"season.quest.harvest_crops.targets\":\"100, 300, 750, 1500, 3000, 6000, 12000\","
                + "\"season.quest.open_crates.targets\":\"50, 200, 500, 1000, 2500, 5000\","
                + "\"season.quest.sell_money.targets\":\"100000, 500000, 1000000, 2500000, 5000000, 10000000, 25000000\","
                + "\"season.quest.play_minutes.targets\":\"120, 480, 1200, 2400, 4800, 9600\","
                + "\"season.quest.win_pvp.targets\":\"1, 7, 30, 70, 150\"}");

        GameVariableStore variables = store();

        assertEquals("5, 25, 75, 200, 400, 800, 1500, 3000",
                variables.string("season.quest.kill_mobs.targets"));
        assertEquals("3, 15, 40, 100, 200, 400, 800",
                variables.string("season.quest.mine_ores.targets"));
        assertEquals("10, 50, 150, 400, 800, 1500, 3000",
                variables.string("season.quest.harvest_crops.targets"));
        assertEquals("1, 3, 10, 25, 60, 150",
                variables.string("season.quest.open_crates.targets"));
        assertEquals("2500, 10000, 50000, 200000, 1000000, 3000000, 10000000",
                variables.string("season.quest.sell_money.targets"));
        assertEquals("10, 30, 90, 240, 600, 1200",
                variables.string("season.quest.play_minutes.targets"));
        assertEquals("1, 7, 30, 70, 150", variables.string("season.quest.win_pvp.targets"),
                "an owner-custom ladder must not be replaced");
    }

    @Test
    void shippedPvpEntranceLabelMigratesAboveThePortal() throws Exception {
        Path file = temporary.resolve("game-variables.json");
        Files.writeString(file, "{\"pvp-competitive.portal-display-height\":0.65,"
                + "\"pvp-competitive.portal-title\":\"✦ PVP LOBBY ✦\"}");

        GameVariableStore variables = store();

        assertEquals(2.5d, variables.decimal("pvp-competitive.portal-display-height"));
        assertEquals("PVP LOBBY PORTAL", variables.string("pvp-competitive.portal-title"));
        String migrated = Files.readString(file);
        assertTrue(migrated.contains("2.5"));
        assertFalse(migrated.contains("✦ PVP LOBBY ✦"));
    }

    @Test
    void incorrectAmethystDeadlineMigratesToSundayMidnightJst() throws Exception {
        Path file = temporary.resolve("game-variables.json");
        Files.writeString(file, "{\"amethyst-events.ends-at\":1789192800}");

        GameVariableStore variables = store();

        assertEquals(1_789_225_200L, variables.integer("amethyst-events.ends-at"));
        assertTrue(Files.readString(file).contains("1789225200"));
    }

    @Test
    void airdropAndHugeAmethystPayoutsAreFullyVariableDriven() throws Exception {
        GameVariableStore variables = store();
        variables.set("airdrop.rarity.common.minimum-keys", "80");
        variables.set("airdrop.rarity.common.maximum-keys", "80");
        variables.set("airdrop.rarity.common.loot-rolls", "0");
        variables.set("airdrop.bonus-loot-rolls", "0");
        variables.set("airdrop.shard-one-in", "1");
        variables.set("airdrop.shard-amount", "3");
        AirdropCatalog.Contents airdrop = AirdropCatalog.roll(
                AirdropCatalog.Rarity.COMMON, new java.util.Random(3), variables
        );
        assertEquals(80, airdrop.keys());
        assertEquals(0, airdrop.materialLoot().size());
        assertEquals(3, airdrop.shards());

        variables.set("huge-amethyst.milestone.maximum-keys", "77");
        variables.set("huge-amethyst.milestone.minimum-keys", "77");
        variables.set("huge-amethyst.shard-one-in", "1");
        variables.set("huge-amethyst.shard-amount", "2");
        AmethystBlockRewards.Bundle wave = AmethystBlockRewards.rollMilestone(
                new java.util.Random(4), variables
        );
        assertEquals(77, wave.keys());
        assertEquals(2, wave.shards());
        assertEquals(40, variables.integer("chaos.supply-drop.keys"));
        assertEquals(60, variables.integer("chaos.alfredo.keys"));
    }
}
