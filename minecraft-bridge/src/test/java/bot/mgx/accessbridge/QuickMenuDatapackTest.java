package bot.mgx.accessbridge;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class QuickMenuDatapackTest {
    @TempDir
    Path world;

    @Test
    void theTagPointsAtTheMenuSoTheQuickActionKeyOpensIt() throws Exception {
        assertTrue(new QuickMenuDatapack().install(world));

        Path tag = world.resolve("datapacks/mgx_menu/data/minecraft/tags/dialog/quick_actions.json");
        String contents = Files.readString(tag, StandardCharsets.UTF_8);

        // A single element in this tag is what makes the key open the dialog directly
        // instead of a chooser listing every dialog in the tag.
        assertTrue(contents.contains("\"mgx:menu\""), contents);
        // Exactly one element: the key opens the dialog itself, where two or more would
        // make it open a chooser listing them.
        assertEquals(1, com.google.gson.JsonParser.parseString(contents)
                .getAsJsonObject().getAsJsonArray("values").size(), contents);
    }

    @Test
    void everyMenuButtonRunsARealCommand() {
        JsonObject dialog = new QuickMenuDatapack().menuDialog();

        assertEquals("minecraft:multi_action", dialog.get("type").getAsString());
        assertEquals(
                MainMenu.entries().size(),
                dialog.getAsJsonArray("actions").size()
        );
        List<String> commands = QuickMenuDatapack.commands();
        for (int index = 0; index < commands.size(); index++) {
            JsonObject action = dialog.getAsJsonArray("actions").get(index).getAsJsonObject()
                    .getAsJsonObject("action");
            assertEquals("minecraft:run_command", action.get("type").getAsString());
            assertEquals(commands.get(index), action.get("command").getAsString());
            // A leading slash here is sent as part of the command and never matches.
            assertFalse(action.get("command").getAsString().startsWith("/"));
        }
    }

    @Test
    void rewritingUnchangedFilesDoesNotAskForARestart() throws Exception {
        QuickMenuDatapack datapack = new QuickMenuDatapack();

        assertTrue(datapack.install(world), "the first write installs the pack");
        assertFalse(datapack.install(world), "an unchanged pack must not ask for a restart");
    }

    @Test
    void everyEntryIsDistinctAndSelfDescribing() {
        for (MainMenu entry : MainMenu.entries()) {
            assertFalse(entry.label().isBlank(), entry.name());
            assertFalse(entry.tooltip().isBlank(), entry.name());
            assertFalse(entry.command().isBlank(), entry.name());
        }
        assertEquals(
                MainMenu.entries().size(),
                MainMenu.entries().stream().map(MainMenu::command).distinct().count()
        );
    }

    @Test
    void packMetadataDeclaresTheFormatRangeTheServerDemands() {
        JsonObject pack = new QuickMenuDatapack().packMeta().getAsJsonObject("pack");

        // Above format 81 the server refuses metadata that lacks these two, and
        // pack_format no longer stands in for them. Getting this wrong logs one line
        // and silently leaves the menu key doing nothing.
        assertTrue(pack.has("min_format"), pack.toString());
        assertTrue(pack.has("max_format"), pack.toString());
        assertEquals(2, pack.getAsJsonArray("min_format").size(), "expected [major, minor]");
        assertEquals(2, pack.getAsJsonArray("max_format").size(), "expected [major, minor]");
        assertEquals(
                pack.getAsJsonArray("min_format").get(0).getAsInt(),
                pack.getAsJsonArray("max_format").get(0).getAsInt(),
                "a new major can change the dialog schema, so it is not accepted blind"
        );
    }

    @Test
    void theMenuOnlyOffersScreensAndNotPlainTextCommands() {
        List<String> commands = QuickMenuDatapack.commands();

        // These print a line and close; a button for them is a dead end, and the
        // screens they used to sit beside are what the menu is for.
        for (String removed : List.of("afk", "guide", "discord", "pay", "bal")) {
            assertFalse(commands.contains(removed), removed);
        }
        for (String expected : List.of("stats", "tpmenu", "homes", "leaderboard")) {
            assertTrue(commands.contains(expected), expected);
        }
    }
}
