package bot.mgx.accessbridge;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
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
    }
}
