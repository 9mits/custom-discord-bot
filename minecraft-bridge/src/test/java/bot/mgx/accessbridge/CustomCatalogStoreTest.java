package bot.mgx.accessbridge;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Adding to and removing from the catalogues, without a build or a restart.
 *
 * <p>Weights were always adjustable and the catalogue was not, so deciding a crate
 * should hand out copper meant editing Java. These hold the parts of that which are
 * easy to get wrong: that a custom entry is a first-class member of its distribution
 * rather than a special case, that removing a built-in is reversible, and that neither
 * can leave a table nothing can be drawn from.
 */
final class CustomCatalogStoreTest {
    @TempDir
    Path temporary;

    private CustomCatalogStore catalog() throws Exception {
        return new CustomCatalogStore(temporary.resolve("custom-catalog.json"));
    }

    private GameVariableStore variables(CustomCatalogStore custom) throws Exception {
        return new GameVariableStore(
                temporary.resolve("game-variables.json"), new YamlConfiguration(), custom);
    }

    @Test
    void anAddedRewardBecomesPartOfTheCrateAndItsDistribution() throws Exception {
        CustomCatalogStore custom = catalog();
        GameVariableStore variables = variables(custom);
        int before = variables.rewards(CrateKind.DEFAULT).size();

        custom.addReward(
                CrateKind.DEFAULT, "copper_haul", "Copper Haul", "RESOURCE",
                "COPPER_INGOT", 32, 500, "A pile of copper."
        );
        variables.rebuildCatalogue();

        assertEquals(before + 1, variables.rewards(CrateKind.DEFAULT).size());
        // It is a real variable, not a special case: it has a weight that can be tuned,
        // validated, published and rolled back like any other.
        assertEquals(500, variables.integer("crate.default.reward.copper_haul.weight"));
        variables.set("crate.default.reward.copper_haul.weight", "900");
        assertEquals(900, variables.integer("crate.default.reward.copper_haul.weight"));
        assertTrue(
                variables.snapshot().getAsJsonArray("variables").asList().stream()
                        .map(com.google.gson.JsonElement::getAsJsonObject)
                        .anyMatch(row -> row.get("key").getAsString()
                                .equals("crate.default.reward.copper_haul.weight")),
                "an added reward must appear in the control panel like any other"
        );
    }

    @Test
    void removingABuiltInIsReversibleAndKeepsItsTuning() throws Exception {
        CustomCatalogStore custom = catalog();
        GameVariableStore variables = variables(custom);
        CrateCatalog.Reward victim = variables.rewards(CrateKind.DEFAULT).get(0);
        String key = "crate.default.reward." + victim.id() + ".weight";
        variables.set(key, "4321");

        custom.removeReward(CrateKind.DEFAULT, victim.id());
        variables.rebuildCatalogue();
        assertTrue(variables.rewards(CrateKind.DEFAULT).stream()
                .noneMatch(reward -> reward.id().equals(victim.id())));
        assertThrows(IllegalArgumentException.class, () -> variables.integer(key));

        custom.restoreReward(CrateKind.DEFAULT, victim.id());
        variables.rebuildCatalogue();
        assertEquals(4321, variables.integer(key),
                "restoring a reward must bring back the weight it had, not the default");
    }

    /** The parked value has to survive a restart, or "reversible" only holds for a session. */
    @Test
    void tuningOfARemovedRewardSurvivesAReopen() throws Exception {
        CustomCatalogStore custom = catalog();
        GameVariableStore variables = variables(custom);
        CrateCatalog.Reward victim = variables.rewards(CrateKind.DEFAULT).get(0);
        String key = "crate.default.reward." + victim.id() + ".weight";
        variables.set(key, "777");
        custom.removeReward(CrateKind.DEFAULT, victim.id());
        variables.rebuildCatalogue();

        CustomCatalogStore reopenedCatalog = catalog();
        GameVariableStore reopened = variables(reopenedCatalog);
        reopenedCatalog.restoreReward(CrateKind.DEFAULT, victim.id());
        reopened.rebuildCatalogue();
        assertEquals(777, reopened.integer(key));
    }

    @Test
    void aCrateCannotBeEmptied() throws Exception {
        CustomCatalogStore custom = catalog();
        List<CrateCatalog.Reward> rewards = CrateCatalog.effectiveRewards(CrateKind.SHARD, custom);
        for (int index = 0; index < rewards.size() - 1; index++) {
            custom.removeReward(CrateKind.SHARD, rewards.get(index).id());
        }
        assertEquals(1, CrateCatalog.effectiveRewards(CrateKind.SHARD, custom).size());
        IllegalArgumentException refused = assertThrows(
                IllegalArgumentException.class,
                () -> custom.removeReward(
                        CrateKind.SHARD,
                        CrateCatalog.effectiveRewards(CrateKind.SHARD, custom).get(0).id())
        );
        assertTrue(refused.getMessage().contains("only reward left"), refused.getMessage());
    }

    @Test
    void anIdAlreadyInUseIsRefused() throws Exception {
        CustomCatalogStore custom = catalog();
        String existing = CrateCatalog.builtIn(CrateKind.DEFAULT).get(0).id();
        // Two rewards sharing an id would share one weight variable, so tuning either
        // would silently move both.
        assertThrows(IllegalArgumentException.class, () -> custom.addReward(
                CrateKind.DEFAULT, existing, "Clash", "RESOURCE", "STONE", 1, 10, ""));
        custom.addReward(CrateKind.DEFAULT, "mine", "Mine", "RESOURCE", "STONE", 1, 10, "");
        assertThrows(IllegalArgumentException.class, () -> custom.addReward(
                CrateKind.DEFAULT, "mine", "Mine again", "RESOURCE", "STONE", 1, 10, ""));
    }

    /**
     * The half of material validation that does not need a running server.
     *
     * <p>Whether a material is an <em>item</em> rather than a block-only state such as
     * WATER goes through the registry, which exists only on a live server, so that half
     * cannot be exercised here. It runs where an owner actually adds a reward.
     */
    @Test
    void aMaterialThatIsNotRealIsRefused() throws Exception {
        CustomCatalogStore custom = catalog();
        for (String material : List.of("NOT_A_REAL_ITEM", "", "diamond block", "123")) {
            assertThrows(IllegalArgumentException.class, () -> custom.addReward(
                    CrateKind.DEFAULT, "probe_" + Math.abs(material.hashCode()), "Probe",
                    "RESOURCE", material, 1, 10, ""
            ), "'" + material + "' should not be addable");
        }
    }

    @Test
    void aRealMaterialIsNormalisedRatherThanRejected() throws Exception {
        CustomCatalogStore custom = catalog();
        CustomCatalogStore.CrateAddition added = custom.addReward(
                CrateKind.DEFAULT, "copper", "Copper", "RESOURCE", "  copper_ingot  ", 8, 10, "");
        assertEquals("COPPER_INGOT", added.material());
    }

    @Test
    void theHiddenExoticTierCannotBeChosenByHand() throws Exception {
        CustomCatalogStore custom = catalog();
        assertThrows(IllegalArgumentException.class, () -> custom.addReward(
                CrateKind.DEFAULT, "sneaky", "Sneaky", "SECRET", "DIAMOND", 1, 10, ""));
    }

    @Test
    void addedAirdropLootIsDrawnFromAndControllable() throws Exception {
        CustomCatalogStore custom = catalog();
        GameVariableStore variables = variables(custom);
        int before = variables.loot().size();

        custom.addLoot("COPPER_INGOT", 4, 12, Map.of(
                "common", 40, "rare", 20, "legendary", 5, "mythic", 0));
        variables.rebuildCatalogue();

        assertEquals(before + 1, variables.loot().size());
        assertEquals(40, variables.integer("airdrop.loot.copper_ingot.common-weight"));
        assertEquals(4, variables.integer("airdrop.loot.copper_ingot.minimum-amount"));
        assertEquals(0, variables.integer("airdrop.loot.copper_ingot.mythic-weight"));
    }

    @Test
    void lootThatCanNeverBeDrawnIsRefused() throws Exception {
        CustomCatalogStore custom = catalog();
        IllegalArgumentException refused = assertThrows(
                IllegalArgumentException.class,
                () -> custom.addLoot("COPPER_INGOT", 1, 2, Map.of(
                        "common", 0, "rare", 0, "legendary", 0, "mythic", 0))
        );
        assertTrue(refused.getMessage().contains("never be drawn"), refused.getMessage());
    }

    @Test
    void anInvertedAmountRangeIsRefused() throws Exception {
        CustomCatalogStore custom = catalog();
        assertThrows(IllegalArgumentException.class, () -> custom.addLoot(
                "COPPER_INGOT", 12, 4, Map.of("common", 10, "rare", 0, "legendary", 0, "mythic", 0)));
    }

    @Test
    void catalogueChangesSurviveAReopen() throws Exception {
        CustomCatalogStore custom = catalog();
        custom.addReward(CrateKind.AMETHYST, "gift", "Gift", "TREASURE", "CAKE", 2, 250, "Cake.");
        custom.addLoot("COPPER_INGOT", 2, 6, Map.of(
                "common", 30, "rare", 10, "legendary", 0, "mythic", 0));
        String removed = CrateCatalog.builtIn(CrateKind.AMETHYST).get(0).id();
        custom.removeReward(CrateKind.AMETHYST, removed);

        CustomCatalogStore reopened = catalog();
        assertEquals(1, reopened.addedRewards("amethyst").size());
        assertEquals("Gift", reopened.addedRewards("amethyst").get(0).displayName());
        assertTrue(reopened.disabledRewards("amethyst").contains(removed));
        assertEquals(1, reopened.addedLoot().size());
        assertEquals("COPPER_INGOT", reopened.addedLoot().get(0).material());
    }

    @Test
    void aRemovedRewardIsNoLongerDrawnFromTheCrate() throws Exception {
        CustomCatalogStore custom = catalog();
        GameVariableStore variables = variables(custom);
        // Leave exactly one reward standing, then confirm the roll can only be that one.
        List<CrateCatalog.Reward> rewards = CrateCatalog.effectiveRewards(CrateKind.SHARD, custom);
        for (int index = 1; index < rewards.size(); index++) {
            custom.removeReward(CrateKind.SHARD, rewards.get(index).id());
        }
        variables.rebuildCatalogue();
        CrateCatalog.Reward survivor = variables.rewards(CrateKind.SHARD).get(0);
        java.util.random.RandomGenerator random = new java.util.Random(7);
        for (int roll = 0; roll < 40; roll++) {
            assertEquals(survivor.id(),
                    variables.randomReward(CrateKind.SHARD, 100, random).id());
        }
    }

    @Test
    void theSnapshotReportsWhatAnOwnerChanged() throws Exception {
        CustomCatalogStore custom = catalog();
        custom.addReward(CrateKind.DEFAULT, "copper_haul", "Copper Haul", "RESOURCE",
                "COPPER_INGOT", 32, 500, "A pile of copper.");
        String removed = CrateCatalog.builtIn(CrateKind.DEFAULT).get(0).id();
        custom.removeReward(CrateKind.DEFAULT, removed);

        com.google.gson.JsonObject snapshot = custom.snapshot();
        com.google.gson.JsonObject crate = snapshot.getAsJsonArray("crates").asList().stream()
                .map(com.google.gson.JsonElement::getAsJsonObject)
                .filter(entry -> entry.get("kind").getAsString().equals("default"))
                .findFirst().orElseThrow();
        assertEquals(1, crate.getAsJsonArray("added").size());
        assertEquals(1, crate.getAsJsonArray("removed").size());
        // The console shows what a removed built-in was, not just its identifier.
        assertTrue(crate.getAsJsonArray("removed").get(0).getAsJsonObject()
                .has("display_name"));
    }

    @Test
    void addingSomethingDoesNotDisturbTheRestOfTheTable() throws Exception {
        CustomCatalogStore custom = catalog();
        GameVariableStore variables = variables(custom);
        CrateCatalog.Reward sample = variables.rewards(CrateKind.DEFAULT).get(0);
        String key = "crate.default.reward." + sample.id() + ".weight";
        int weightBefore = variables.integer(key);

        custom.addReward(CrateKind.DEFAULT, "copper_haul", "Copper Haul", "RESOURCE",
                "COPPER_INGOT", 32, 500, "");
        variables.rebuildCatalogue();

        assertEquals(weightBefore, variables.integer(key),
                "adding a reward must not rewrite the weights already there");
        assertFalse(variables.displayedChance(CrateKind.DEFAULT, sample).isBlank());
    }
}
