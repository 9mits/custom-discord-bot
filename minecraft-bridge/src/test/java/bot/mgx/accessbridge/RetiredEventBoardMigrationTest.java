package bot.mgx.accessbridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A server that ran the Amethyst event still has its two individual event holograms
 * placed. Those boards no longer exist and used to be dropped on load, which does not
 * take down the armour stands already standing in the world — it abandons them.
 */
final class RetiredEventBoardMigrationTest {
    /** The live production placement file, reduced to the part this migration touches. */
    private static final String LIVE = """
            {"placements":[
            {"board":"clans-wealth","world":"8c62de51-d502-4020-94f8-6c0e6b942c4d","x":-5.49,"y":72.8,"z":6.48},
            {"board":"players-kills","world":"8c62de51-d502-4020-94f8-6c0e6b942c4d","x":-5.50,"y":72.6,"z":-5.47},
            {"board":"players-wealth","world":"8c62de51-d502-4020-94f8-6c0e6b942c4d","x":6.46,"y":72.8,"z":-5.51},
            {"board":"amethyst-airdrops","world":"8c62de51-d502-4020-94f8-6c0e6b942c4d","x":7.55,"y":72.4,"z":-48.54},
            {"board":"amethyst-crates","world":"8c62de51-d502-4020-94f8-6c0e6b942c4d","x":-6.44,"y":72.4,"z":-48.58},
            {"board":"clan-battle","world":"8c62de51-d502-4020-94f8-6c0e6b942c4d","x":0.48,"y":72.4,"z":-59.45}
            ]}""";

    private static String boardAt(Path file, double x) throws Exception {
        JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        for (var element : root.getAsJsonArray("placements")) {
            JsonObject row = element.getAsJsonObject();
            if (Math.abs(row.get("x").getAsDouble() - x) < 0.001) {
                return row.get("board").getAsString();
            }
        }
        return null;
    }

    @Test
    void retiredEventBoardsBecomeThisEventsAndKeepTheirPlaces(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("holograms.json");
        Files.writeString(file, LIVE);
        HologramService service = new HologramService(file, null, null, null);

        assertTrue(service.migratedRetiredBoards(), "the retired boards were not migrated");
        String rewritten = Files.readString(file);
        assertFalse(rewritten.contains("amethyst-crates"), "a retired board survived");
        assertFalse(rewritten.contains("amethyst-airdrops"), "a retired board survived");
        // Re-pointed where they already stood, so an operator's placement is kept.
        assertEquals("dragon-damage", boardAt(file, -6.44));
        assertEquals("dragon-crystals", boardAt(file, 7.55));
        // Nothing else is touched.
        assertEquals("players-kills", boardAt(file, -5.50));
        assertEquals("players-wealth", boardAt(file, 6.46));
        assertEquals("clan-battle", boardAt(file, 0.48));

        // The trigger is the stale key, so rewriting it is what stops this repeating.
        assertFalse(new HologramService(file, null, null, null).migratedRetiredBoards(),
                "the migration would run again on the next restart");
    }

    @Test
    void clearingTheRetiredEventKeepsThisEventsProgressAndLeavesABackup(@TempDir Path dir)
            throws Exception {
        Path file = dir.resolve("amethyst-event-progress.json");
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        Files.writeString(file, "{\"" + a + "\":{\"amethyst_crates_opened\":116744,"
                + "\"amethyst_airdrops_opened\":59,\"dragon_damage\":4200},"
                + "\"" + b + "\":{\"amethyst_crates_opened\":12}}");
        AmethystProgressStore store = new AmethystProgressStore(file);

        assertEquals(2, store.clearRetiredEventProgress());

        // The retired counters go; this event's totals are not collateral.
        assertEquals(0L, store.counts(a).cratesOpened());
        assertEquals(0L, store.counts(a).airdropsOpened());
        assertEquals(4200L, store.counts(a).dragonDamage(), "the Dragon total was destroyed");
        // A player with nothing left is dropped rather than kept as a row of zeroes.
        assertTrue(store.counts(b).empty());

        assertTrue(Files.isRegularFile(
                        file.resolveSibling("amethyst-event-progress.json.before-dragon-event")),
                "no backup was left of progress that cannot be recomputed");
        assertEquals(0, store.clearRetiredEventProgress(), "a second run should be a no-op");
    }
}
