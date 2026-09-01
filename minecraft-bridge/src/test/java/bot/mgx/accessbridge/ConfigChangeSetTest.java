package bot.mgx.accessbridge;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The publish contract: a change set is judged and applied as a whole, or not at all.
 *
 * <p>Everything here exists because judging one field at a time cannot see the shape of
 * the change. Two of these cases were reachable from the control panel and would have
 * been felt in game rather than at the point of editing.
 */
final class ConfigChangeSetTest {
    @TempDir
    Path temporary;

    private GameVariableStore store() throws Exception {
        return new GameVariableStore(
                temporary.resolve("game-variables.json"), new YamlConfiguration());
    }

    /**
     * The bug this replaces. Airdrop weights may each legitimately be zero, so nothing
     * stopped the last positive one going too — and an empty table is not a balance
     * decision, it is an exception the next time an Airdrop tries to spawn.
     */
    @Test
    void aDistributionCannotBeEmptied() throws Exception {
        GameVariableStore variables = store();
        List<GameVariableStore.Edit> zeroEveryRarity = new ArrayList<>();
        for (AirdropCatalog.Rarity rarity : AirdropCatalog.Rarity.values()) {
            zeroEveryRarity.add(GameVariableStore.Edit.set(
                    "airdrop.rarity." + rarity.name().toLowerCase(java.util.Locale.ROOT) + ".weight",
                    "0"
            ));
        }
        List<GameVariableStore.Finding> findings = variables.validate(zeroEveryRarity);
        assertEquals(1, findings.size(), "expected exactly one finding: " + findings);
        assertTrue(findings.get(0).message().contains("airdrop.rarity"), findings.toString());

        assertThrows(GameVariableStore.InvalidChangeSet.class,
                () -> variables.apply(zeroEveryRarity, "owner"));
        // Nothing was written, so the table still has something to draw from.
        assertTrue(variables.rarityWeight(AirdropCatalog.Rarity.COMMON) > 0);
    }

    /** Zeroing all but one is a real balance choice and must still be allowed. */
    @Test
    void aDistributionMayBeReducedToASingleWinner() throws Exception {
        GameVariableStore variables = store();
        List<GameVariableStore.Edit> edits = new ArrayList<>();
        for (AirdropCatalog.Rarity rarity : AirdropCatalog.Rarity.values()) {
            if (rarity != AirdropCatalog.Rarity.MYTHIC) {
                edits.add(GameVariableStore.Edit.set(
                        "airdrop.rarity." + rarity.name().toLowerCase(java.util.Locale.ROOT) + ".weight",
                        "0"
                ));
            }
        }
        assertTrue(variables.validate(edits).isEmpty(), "expected a valid change set");
        assertEquals(3, variables.apply(edits, "owner").size());
        assertEquals(0, variables.rarityWeight(AirdropCatalog.Rarity.COMMON));
        assertTrue(variables.rarityWeight(AirdropCatalog.Rarity.MYTHIC) > 0);
    }

    /**
     * A range can be moved past where it currently sits, as long as both halves move
     * together. One key at a time could never express this: whichever went first was
     * measured against the other's old value and refused.
     */
    @Test
    void aRangeCanLeapfrogItselfWhenBothHalvesMoveTogether() throws Exception {
        GameVariableStore variables = store();
        String minimum = "airdrop.rarity-radius.common.minimum";
        String maximum = "airdrop.rarity-radius.common.maximum";
        assertEquals(1_000, variables.integer(minimum));
        assertEquals(2_000, variables.integer(maximum));

        // The old single-key path refuses this, because 5000 > the stored maximum.
        assertThrows(IllegalArgumentException.class, () -> variables.set(minimum, "5000"));

        List<GameVariableStore.Edit> together = List.of(
                GameVariableStore.Edit.set(minimum, "5000"),
                GameVariableStore.Edit.set(maximum, "9000")
        );
        assertTrue(variables.validate(together).isEmpty(), "expected a valid change set");
        variables.apply(together, "owner");
        assertEquals(5_000, variables.integer(minimum));
        assertEquals(9_000, variables.integer(maximum));
    }

    @Test
    void anInvertedRangeIsStillRefusedWhenBothHalvesMove() throws Exception {
        GameVariableStore variables = store();
        List<GameVariableStore.Finding> findings = variables.validate(List.of(
                GameVariableStore.Edit.set("airdrop.rarity-radius.common.minimum", "9000"),
                GameVariableStore.Edit.set("airdrop.rarity-radius.common.maximum", "5000")
        ));
        assertFalse(findings.isEmpty(), "an inverted range must not validate");
    }

    /** Half a rebalance is worse than none of it. */
    @Test
    void aRejectedChangeSetWritesNothing() throws Exception {
        GameVariableStore variables = store();
        int before = variables.integer("crate.default.key-cost");
        assertThrows(GameVariableStore.InvalidChangeSet.class, () -> variables.apply(List.of(
                GameVariableStore.Edit.set("crate.default.key-cost", "9"),
                GameVariableStore.Edit.set("crate.amethyst.key-cost", "not a number")
        ), "owner"));
        assertEquals(before, variables.integer("crate.default.key-cost"),
                "the valid half of a rejected set must not be applied");

        GameVariableStore reopened = new GameVariableStore(
                temporary.resolve("game-variables.json"), new YamlConfiguration());
        assertEquals(before, reopened.integer("crate.default.key-cost"));
    }

    @Test
    void everyFindingInASetIsReportedNotJustTheFirst() throws Exception {
        List<GameVariableStore.Finding> findings = store().validate(List.of(
                GameVariableStore.Edit.set("crate.default.key-cost", "999"),
                GameVariableStore.Edit.set("crate.amethyst.key-cost", "0")
        ));
        assertEquals(2, findings.size(), "expected both range failures: " + findings);
    }

    @Test
    void aPublishIsRecordedWithTheValuesItReplaced() throws Exception {
        GameVariableStore variables = store();
        int before = variables.integer("crate.default.key-cost");
        variables.apply(List.of(GameVariableStore.Edit.set("crate.default.key-cost", "7")), "mits");

        List<ConfigHistory.Publish> recent = variables.history().recent(5);
        assertEquals(1, recent.size());
        assertEquals("mits", recent.get(0).actor());
        assertEquals(1, recent.get(0).changes().size());
        GameVariableStore.Change change = recent.get(0).changes().get(0);
        assertEquals("crate.default.key-cost", change.key());
        assertEquals((long) before, ((Number) change.before()).longValue());
        assertEquals(7L, ((Number) change.after()).longValue());
    }

    @Test
    void rollbackRestoresEveryValueAPublishTouched() throws Exception {
        GameVariableStore variables = store();
        int keyCost = variables.integer("crate.default.key-cost");
        int perHour = variables.integer("crate.keys-per-hour");

        variables.apply(List.of(
                GameVariableStore.Edit.set("crate.default.key-cost", "9"),
                GameVariableStore.Edit.set("crate.keys-per-hour", "40")
        ), "mits");
        assertEquals(9, variables.integer("crate.default.key-cost"));
        assertEquals(40, variables.integer("crate.keys-per-hour"));

        String publishId = variables.history().recent(1).get(0).id();
        variables.rollback(publishId, "mits");
        assertEquals(keyCost, variables.integer("crate.default.key-cost"));
        assertEquals(perHour, variables.integer("crate.keys-per-hour"));
    }

    /** Undoing a change that had cleared an override must leave no override behind. */
    @Test
    void rollbackToADefaultClearsTheOverrideRatherThanStoringIt() throws Exception {
        GameVariableStore variables = store();
        variables.apply(List.of(GameVariableStore.Edit.set("crate.default.key-cost", "9")), "mits");
        String publishId = variables.history().recent(1).get(0).id();
        variables.rollback(publishId, "mits");

        GameVariableStore reopened = new GameVariableStore(
                temporary.resolve("game-variables.json"), new YamlConfiguration());
        assertFalse(
                reopened.snapshot().getAsJsonArray("variables").asList().stream()
                        .map(com.google.gson.JsonElement::getAsJsonObject)
                        .filter(row -> row.get("key").getAsString().equals("crate.default.key-cost"))
                        .findFirst().orElseThrow()
                        .get("overridden").getAsBoolean(),
                "rolling back to the default must clear the override, not persist the default"
        );
    }

    @Test
    void historySurvivesAReopenAndIsBounded() throws Exception {
        GameVariableStore variables = store();
        for (int cost = 1; cost <= ConfigHistory.RETAINED_PUBLISHES + 5; cost++) {
            variables.apply(List.of(GameVariableStore.Edit.set(
                    "crate.default.key-cost", String.valueOf((cost % 60) + 1)
            )), "mits");
        }
        assertEquals(ConfigHistory.RETAINED_PUBLISHES,
                variables.history().recent(1_000).size(), "history must stay bounded");

        GameVariableStore reopened = new GameVariableStore(
                temporary.resolve("game-variables.json"), new YamlConfiguration());
        assertEquals(ConfigHistory.RETAINED_PUBLISHES, reopened.history().recent(1_000).size(),
                "history must survive a restart, or rollback dies with the process");
    }

    /** A publish that changes nothing is not a publish. */
    @Test
    void reapplyingTheSameValuesRecordsNothing() throws Exception {
        GameVariableStore variables = store();
        int current = variables.integer("crate.default.key-cost");
        assertTrue(variables.apply(List.of(GameVariableStore.Edit.set(
                "crate.default.key-cost", String.valueOf(current)
        )), "mits").isEmpty());
        assertTrue(variables.history().recent(5).isEmpty());
    }

    @Test
    void onlineTierOrderingHoldsAcrossAMultiTierPublish() throws Exception {
        GameVariableStore variables = store();
        // Defaults are 1, 3, 6, 12, 24, 72. Raising tier 2 to 10 on its own is refused
        // because tier 3 still sits at 6; the whole ladder moving together is fine.
        assertFalse(variables.validate(List.of(
                GameVariableStore.Edit.set("online-rewards.tier.2.minimum-hours", "10")
        )).isEmpty(), "a lone tier overtaking the next one must be refused");

        List<GameVariableStore.Edit> ladder = List.of(
                GameVariableStore.Edit.set("online-rewards.tier.2.minimum-hours", "10"),
                GameVariableStore.Edit.set("online-rewards.tier.3.minimum-hours", "20"),
                GameVariableStore.Edit.set("online-rewards.tier.4.minimum-hours", "30"),
                GameVariableStore.Edit.set("online-rewards.tier.5.minimum-hours", "40")
        );
        assertTrue(variables.validate(ladder).isEmpty(), "expected a valid ladder: "
                + variables.validate(ladder));
        variables.apply(ladder, "mits");
        assertEquals(20, variables.integer("online-rewards.tier.3.minimum-hours"));

        assertFalse(variables.validate(List.of(
                GameVariableStore.Edit.set("online-rewards.tier.3.minimum-hours", "5")
        )).isEmpty(), "a tier that overtakes the one before it must be refused");
    }

    @Test
    void resetIsPartOfAChangeSetAndValidatesLikeAValue() throws Exception {
        GameVariableStore variables = store();
        variables.apply(List.of(
                GameVariableStore.Edit.set("airdrop.rarity-radius.common.minimum", "5000"),
                GameVariableStore.Edit.set("airdrop.rarity-radius.common.maximum", "9000")
        ), "mits");
        variables.apply(List.of(
                GameVariableStore.Edit.reset("airdrop.rarity-radius.common.minimum"),
                GameVariableStore.Edit.reset("airdrop.rarity-radius.common.maximum")
        ), "mits");
        assertEquals(1_000, variables.integer("airdrop.rarity-radius.common.minimum"));
        assertEquals(2_000, variables.integer("airdrop.rarity-radius.common.maximum"));

        // Resetting only the maximum would drop it below the minimum still in force.
        variables.apply(List.of(
                GameVariableStore.Edit.set("airdrop.rarity-radius.common.minimum", "5000"),
                GameVariableStore.Edit.set("airdrop.rarity-radius.common.maximum", "9000")
        ), "mits");
        assertFalse(variables.validate(List.of(
                GameVariableStore.Edit.reset("airdrop.rarity-radius.common.maximum")
        )).isEmpty(), "a reset that inverts a range must be refused like any other value");
    }
}
