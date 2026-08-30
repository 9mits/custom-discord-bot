package bot.mgx.accessbridge;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void afkTiersEscalateAndOnlinePlayersAddCappedKeys() throws Exception {
        GameVariableStore variables = store();

        assertEquals(1, variables.afkRewardTier(0).number());
        assertEquals(2, variables.afkRewardTier(Duration.ofHours(3).toSeconds()).number());
        assertEquals(5, variables.afkRewardTier(Duration.ofHours(71).toSeconds()).number());
        GameVariableStore.AfkRewardTier rare = variables.afkRewardTier(
                Duration.ofHours(72).toSeconds()
        );
        assertEquals(6, rare.number());
        assertEquals(10, rare.bonusKeys());
        assertEquals(1, rare.shards());
        assertEquals(5_000, rare.shardOneIn());

        assertEquals(0, variables.afkOnlineBonusKeys(4));
        assertEquals(1, variables.afkOnlineBonusKeys(5));
        assertEquals(1, variables.afkOnlineBonusKeys(9));
        assertEquals(2, variables.afkOnlineBonusKeys(10));
        assertEquals(4, variables.afkOnlineBonusKeys(100));
    }

    @Test
    void afkTierThresholdsMustRemainStrictlyOrdered() throws Exception {
        GameVariableStore variables = store();
        assertThrows(IllegalArgumentException.class,
                () -> variables.set("afk-rewards.tier.2.minimum-hours", "1"));
        assertThrows(IllegalArgumentException.class,
                () -> variables.set("afk-rewards.tier.5.minimum-hours", "72"));
        variables.set("afk-rewards.tier.6.minimum-hours", "100");
        assertEquals(5, variables.afkRewardTier(Duration.ofHours(80).toSeconds()).number());
        assertThrows(IllegalArgumentException.class,
                () -> variables.set("huge-amethyst.wave.2.health-percent", "80"));
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
