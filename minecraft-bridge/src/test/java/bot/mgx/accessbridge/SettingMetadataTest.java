package bot.mgx.accessbridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Holds the presentation schema total.
 *
 * <p>The point of deriving metadata rather than declaring it at every call site is that
 * a newly added variable inherits the standards automatically. That only holds while
 * derivation covers everything, so these tests fail the moment a value appears that the
 * panel would not know how to draw, group, or reload.
 */
final class SettingMetadataTest {
    @TempDir
    Path temporary;

    private GameVariableStore store() throws Exception {
        return new GameVariableStore(
                temporary.resolve("game-variables.json"), new YamlConfiguration());
    }

    private List<JsonObject> rows() throws Exception {
        List<JsonObject> rows = new ArrayList<>();
        for (JsonElement element : store().snapshot().getAsJsonArray("variables")) {
            rows.add(element.getAsJsonObject());
        }
        return rows;
    }

    @Test
    void everyVariableIsDrawableGroupedAndClassified() throws Exception {
        List<String> incomplete = new ArrayList<>();
        List<String> unclassified = new ArrayList<>();
        for (JsonObject row : rows()) {
            if (!row.has("control") || !row.has("group") || !row.has("reload")) {
                incomplete.add(row.get("key").getAsString());
            } else if (row.get("group").getAsString().equals("unclassified")) {
                unclassified.add(row.get("key").getAsString());
            }
        }
        assertTrue(incomplete.isEmpty(), "variables with no panel metadata: " + incomplete);
        // The bucket exists so an unmapped prefix degrades instead of taking the whole
        // snapshot down. Nothing is allowed to sit in it: give the prefix a group.
        assertTrue(unclassified.isEmpty(),
                "variables with no panel page: " + unclassified
                        + " — add the prefix to SettingMetadata.group");
    }

    /**
     * A bare number box was the whole problem. Nothing may fall back to one by having no
     * more specific control than "some quantity" when its unit says otherwise.
     */
    @Test
    void controlsCoverEveryUnitTheStoreDeclares() throws Exception {
        // Keyed by kind and unit together, not unit alone. A choice and a flag both
        // declare no unit and are told apart by their type, so grouping on unit alone
        // reported that pair as an ambiguity when it is the rule working correctly.
        Map<String, Set<String>> controlsPerUnit = new TreeMap<>();
        for (JsonObject row : rows()) {
            controlsPerUnit
                    .computeIfAbsent(
                            row.get("type").getAsString() + "/" + row.get("unit").getAsString(),
                            ignored -> new java.util.TreeSet<>())
                    .add(row.get("control").getAsString());
        }
        // One kind and unit must never resolve to two different controls; that would
        // mean the derivation is guessing rather than deciding.
        List<String> ambiguous = controlsPerUnit.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                // "weight" legitimately splits: a share of a table, or a rate per 10,000.
                .filter(entry -> !entry.getKey().endsWith("/weight"))
                .map(entry -> entry.getKey() + " -> " + entry.getValue())
                .toList();
        assertTrue(ambiguous.isEmpty(), "units resolving to more than one control: " + ambiguous);
    }

    @Test
    void everyWeightBelongsToExactlyOneDistribution() throws Exception {
        Map<String, Integer> tables = new TreeMap<>();
        List<String> stranded = new ArrayList<>();
        for (JsonObject row : rows()) {
            if (!row.get("control").getAsString().equals("weight_row")) {
                continue;
            }
            if (!row.has("table")) {
                stranded.add(row.get("key").getAsString());
                continue;
            }
            tables.merge(row.get("table").getAsString(), 1, Integer::sum);
        }
        assertTrue(stranded.isEmpty(), "weights with no distribution to belong to: " + stranded);

        // Every distribution an owner edits, and how many rows each holds.
        Map<String, Integer> expected = new LinkedHashMap<>();
        expected.put("crate.default", 64);
        expected.put("crate.amethyst", 62);
        expected.put("crate.shard", 56);
        expected.put("crate.dragon", 31);
        expected.put("amethyst-block.tier", 3);
        expected.put("airdrop.rarity", 4);
        expected.put("airdrop.loot.common", 13);
        expected.put("airdrop.loot.rare", 13);
        expected.put("airdrop.loot.legendary", 13);
        expected.put("airdrop.loot.mythic", 13);
        assertEquals(new TreeMap<>(expected), tables,
                "the loot tables changed shape — the panel's editors are built per table");
    }

    @Test
    void everyDistributionHasAPositiveTotalToDivideBy() throws Exception {
        JsonObject snapshot = store().snapshot();
        List<String> empty = new ArrayList<>();
        int tables = 0;
        for (JsonElement element : snapshot.getAsJsonArray("tables")) {
            JsonObject entry = element.getAsJsonObject();
            tables++;
            if (entry.get("total_weight").getAsLong() <= 0) {
                empty.add(entry.get("table").getAsString());
            }
        }
        assertEquals(10, tables, "expected one summary per editable distribution");
        assertTrue(empty.isEmpty(), "distributions that cannot produce a chance: " + empty);
    }

    /**
     * The published summary has to hold exactly the rows that compete for the total.
     *
     * <p>Counting tables alone missed a real defect: {@code cosmetic-weight} sits beside
     * the rarity weights and ends in the same suffix, so it was being summed into their
     * total. That inflated every printed chance and, worse, left the table looking
     * non-empty when every actual weight had been zeroed.
     */
    @Test
    void publishedTableTotalsHoldOnlyTheirOwnRows() throws Exception {
        Map<String, Integer> entries = new TreeMap<>();
        for (JsonElement element : store().snapshot().getAsJsonArray("tables")) {
            JsonObject entry = element.getAsJsonObject();
            entries.put(entry.get("table").getAsString(), entry.get("entries").getAsInt());
        }
        Map<String, Integer> expected = new TreeMap<>();
        expected.put("crate.default", 64);
        expected.put("crate.amethyst", 62);
        expected.put("crate.shard", 56);
        expected.put("crate.dragon", 31);
        expected.put("amethyst-block.tier", 3);
        expected.put("airdrop.rarity", 4);
        expected.put("airdrop.loot.common", 13);
        expected.put("airdrop.loot.rare", 13);
        expected.put("airdrop.loot.legendary", 13);
        expected.put("airdrop.loot.mythic", 13);
        assertEquals(expected, entries, "a table gained or lost rows");
    }

    /** A rate expressed per fixed denominator is not a member of any distribution. */
    @Test
    void cosmeticRatesAreNotPartOfTheRarityDistribution() throws Exception {
        for (JsonObject row : rows()) {
            String key = row.get("key").getAsString();
            if (key.endsWith(".cosmetic-weight")) {
                assertEquals("rate", row.get("control").getAsString(), key);
                assertTrue(!row.has("table"),
                        key + " must not compete for a distribution total");
            }
        }
    }

    /** A range control needs both halves, and each half must point back at the other. */
    @Test
    void minimumAndMaximumPairsAreMutual() throws Exception {
        Map<String, String> partners = new LinkedHashMap<>();
        for (JsonObject row : rows()) {
            if (row.has("partner")) {
                partners.put(row.get("key").getAsString(), row.get("partner").getAsString());
            }
        }
        List<String> broken = new ArrayList<>();
        partners.forEach((key, partner) -> {
            if (!key.equals(partners.get(partner))) {
                broken.add(key + " -> " + partner + ", which points at " + partners.get(partner));
            }
        });
        assertTrue(broken.isEmpty(), "one-sided range pairs: " + broken);
        assertEquals(78, partners.size(),
                "expected 39 mutual minimum/maximum pairs across the catalogue");
    }

    /**
     * The audit's headline claim, kept honest: nothing in the catalogue needs a restart.
     * Anything that ever does must say why, in prose, on the control itself.
     */
    @Test
    void nothingRequiresARestartAndOnlySpawnCapturedValuesLag() throws Exception {
        List<String> nextEvent = new ArrayList<>();
        List<String> restart = new ArrayList<>();
        for (JsonObject row : rows()) {
            String reload = row.get("reload").getAsString();
            String key = row.get("key").getAsString();
            if (reload.equals("next_event")) {
                nextEvent.add(key);
            }
            if (reload.equals("restart")) {
                restart.add(key);
                assertTrue(row.has("restart_reason"), key + " requires a restart but says no why");
            }
        }
        assertTrue(restart.isEmpty(), "values that would need a Paper restart: " + restart);
        assertEquals(
                List.of(
                        "airdrop.lifetime-minutes",
                        "dragon-event.arena-radius",
                        "dragon-event.border-size",
                        "dragon-event.crystals",
                        "dragon-event.maximum-health",
                        "dragon-event.pillar-base-height",
                        "dragon-event.pillar-height-step",
                        "dragon-event.pillar-radius",
                        "dragon-event.sky-style",
                        "giant-amethyst.maximum-health",
                        "giant-amethyst.size",
                        "huge-amethyst.lifetime-minutes",
                        "huge-amethyst.maximum-health",
                        "huge-amethyst.size",
                        "humongous-amethyst.maximum-health",
                        "humongous-amethyst.size"
                ),
                nextEvent.stream().sorted().toList(),
                "the set of values copied into an event at spawn changed — verify the consumer"
        );
    }

    /** The rebuild lands later; until then the current panel must keep reading its fields. */
    @Test
    void snapshotStaysBackwardCompatible() throws Exception {
        for (JsonObject row : rows()) {
            for (String field : List.of(
                    "key", "label", "category", "description", "type", "value", "default",
                    "unit", "sensitive", "overridden"
            )) {
                assertTrue(row.has(field),
                        row.get("key").getAsString() + " lost the existing field '" + field + "'");
            }
        }
    }

    @Test
    void groupsSplitTheCatalogueIntoPanelPages() throws Exception {
        Map<String, Integer> perGroup = new TreeMap<>();
        for (JsonObject row : rows()) {
            perGroup.merge(row.get("group").getAsString(), 1, Integer::sum);
        }
        assertEquals(
                Map.ofEntries(
                        Map.entry("crates", 224),
                        Map.entry("airdrops", 119),
                        Map.entry("online_rewards", 67),
                        Map.entry("amethyst_blocks", 49),
                        Map.entry("dragon_event", 163),
                        Map.entry("permissions", 4),
                        Map.entry("shop", 21),
                        Map.entry("admin_events", 17),
                        Map.entry("world", 14),
                        Map.entry("potions", 13),
                        Map.entry("cosmetics", 13),
                        Map.entry("players", 12),
                        Map.entry("event_multipliers", 9),
                        Map.entry("crate_balance", 7),
                        Map.entry("amethyst_shop", 29),
                        Map.entry("clans", 4),
                        Map.entry("boss_bars", 4),
                        Map.entry("enchantments", 4),
                        Map.entry("amethyst_mobs", 3),
                        Map.entry("auction_house", 3),
                        Map.entry("perks", 3),
                        Map.entry("clan_battles", 6),
                        Map.entry("economy", 3),
                        Map.entry("event_schedule", 3),
                        Map.entry("presentation", 2),
                        Map.entry("launch", 2),
                        Map.entry("messages", 17)
                ),
                perGroup,
                "the catalogue moved between panel pages"
        );
        assertEquals(815, perGroup.values().stream().mapToInt(Integer::intValue).sum(),
                "group counts no longer add up to the catalogue");
    }

    @Test
    void aWeightAndItsTableMoveTogether() throws Exception {
        GameVariableStore variables = store();
        String key = "airdrop.rarity.mythic.weight";
        long before = tableTotal(variables, "airdrop.rarity");
        int mythic = variables.integer(key);
        variables.set(key, String.valueOf(mythic + 500));
        long after = tableTotal(variables, "airdrop.rarity");
        assertEquals(before + 500, after, "the table total must follow its rows");
        assertNotEquals(before, after);
    }

    private static long tableTotal(GameVariableStore variables, String table) {
        for (JsonElement element : variables.snapshot().getAsJsonArray("tables")) {
            JsonObject entry = element.getAsJsonObject();
            if (entry.get("table").getAsString().equals(table)) {
                return entry.get("total_weight").getAsLong();
            }
        }
        throw new AssertionError("no summary for " + table);
    }
}
