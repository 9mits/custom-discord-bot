package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerSettingsStoreTest {
    @Test
    void whatYouCanSeeIsShownUntilAPlayerSaysOtherwise(@TempDir Path directory) throws IOException {
        PlayerSettingsStore store = new PlayerSettingsStore(directory.resolve("settings.json"));

        assertTrue(store.isEnabled(UUID.randomUUID(), PlayerSettingsStore.Setting.CLAN_TAGS));
        assertTrue(store.isEnabled(UUID.randomUUID(), PlayerSettingsStore.Setting.DISCORD_CHAT));
    }

    /**
     * Auto sell empties an inventory into the shop every couple of seconds. Under one
     * shared "everything starts enabled" rule it was on for everybody who had never
     * heard of it, so ore went to cash on the way out of the ground. Nothing that
     * spends or destroys a player's things may default to on.
     */
    @Test
    void anythingThatActsOnItemsStaysOffUntilAskedFor(@TempDir Path directory) throws IOException {
        PlayerSettingsStore store = new PlayerSettingsStore(directory.resolve("settings.json"));
        UUID player = UUID.randomUUID();

        assertFalse(store.isEnabled(player, PlayerSettingsStore.Setting.AUTO_SELL));
        assertTrue(store.toggle(player, PlayerSettingsStore.Setting.AUTO_SELL));
        assertTrue(store.isEnabled(player, PlayerSettingsStore.Setting.AUTO_SELL));
        assertFalse(store.toggle(player, PlayerSettingsStore.Setting.AUTO_SELL));
        assertFalse(store.isEnabled(player, PlayerSettingsStore.Setting.AUTO_SELL));
    }

    /**
     * The file records deviations from the default, so the same key cannot mean "off"
     * for one setting and "on" for another. Auto sell was written as {@code auto_sell}
     * meaning off; under the opt-in default that name would now read as on and switch
     * it on for exactly the people who had turned it off, which is why it moved key.
     */
    @Test
    void theOldAutoSellKeyDoesNotSurviveAsAnOptIn(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("settings.json");
        UUID player = UUID.randomUUID();
        Files.writeString(file, "{\"" + player + "\":[\"auto_sell\",\"clan_tags\"]}");

        PlayerSettingsStore store = new PlayerSettingsStore(file);

        assertFalse(store.isEnabled(player, PlayerSettingsStore.Setting.AUTO_SELL));
        assertFalse(store.isEnabled(player, PlayerSettingsStore.Setting.CLAN_TAGS));
    }

    /** Auto sell belongs on the sell screen; /settings is for presentation choices. */
    @Test
    void onlyPresentationTogglesAreOfferedBySettings() {
        assertTrue(PlayerSettingsStore.Setting.CLAN_TAGS.inSettingsPanel());
        assertTrue(PlayerSettingsStore.Setting.DISCORD_CHAT.inSettingsPanel());
        assertFalse(PlayerSettingsStore.Setting.AUTO_SELL.inSettingsPanel());
    }

    @Test
    void settingsCategoriesKeepThePlayerFacingOrder() {
        assertIterableEquals(List.of(
                PlayerSettingsStore.Category.CHAT,
                PlayerSettingsStore.Category.NOTIFICATIONS,
                PlayerSettingsStore.Category.PVP,
                PlayerSettingsStore.Category.VISUALS,
                PlayerSettingsStore.Category.PRIVACY,
                PlayerSettingsStore.Category.SCOREBOARD,
                PlayerSettingsStore.Category.GENERAL
        ), List.of(PlayerSettingsStore.Category.values()));
    }

    @Test
    void eachStoredPanelSettingBelongsToItsCategory() {
        for (PlayerSettingsStore.Category category : PlayerSettingsStore.Category.values()) {
            for (PlayerSettingsStore.Setting setting : category.settings()) {
                assertTrue(setting.inSettingsPanel());
                assertEquals(category, setting.category());
            }
        }
        assertTrue(PlayerSettingsStore.Category.PRIVACY.settings().isEmpty());
    }

    @Test
    void newPresentationSettingsHaveSafeVisibleDefaults(@TempDir Path directory) throws IOException {
        PlayerSettingsStore store = new PlayerSettingsStore(directory.resolve("settings.json"));
        UUID player = UUID.randomUUID();

        for (PlayerSettingsStore.Setting setting : PlayerSettingsStore.Setting.values()) {
            if (setting.inSettingsPanel()) {
                assertTrue(store.isEnabled(player, setting), setting.key());
            }
        }
    }

    @Test
    void explicitDialogValuesAreIdempotentAndPersist(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("settings.json");
        UUID player = UUID.randomUUID();
        PlayerSettingsStore store = new PlayerSettingsStore(file);

        assertFalse(store.setEnabled(
                player, PlayerSettingsStore.Setting.SCOREBOARD_ENABLED, false));
        String once = Files.readString(file);
        assertFalse(store.setEnabled(
                player, PlayerSettingsStore.Setting.SCOREBOARD_ENABLED, false));
        assertEquals(once, Files.readString(file));
        assertFalse(new PlayerSettingsStore(file).isEnabled(
                player, PlayerSettingsStore.Setting.SCOREBOARD_ENABLED));

        assertTrue(store.setEnabled(
                player, PlayerSettingsStore.Setting.SCOREBOARD_ENABLED, true));
        assertEquals("{}", Files.readString(file));
    }

    @Test
    void oneDialogSubmissionPersistsTogether(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("settings.json");
        UUID player = UUID.randomUUID();
        PlayerSettingsStore store = new PlayerSettingsStore(file);

        store.setEnabled(player, Map.of(
                PlayerSettingsStore.Setting.SCOREBOARD_PROFILE, false,
                PlayerSettingsStore.Setting.SCOREBOARD_STATS, false,
                PlayerSettingsStore.Setting.SCOREBOARD_ECONOMY, true
        ));

        PlayerSettingsStore reloaded = new PlayerSettingsStore(file);
        assertFalse(reloaded.isEnabled(player, PlayerSettingsStore.Setting.SCOREBOARD_PROFILE));
        assertFalse(reloaded.isEnabled(player, PlayerSettingsStore.Setting.SCOREBOARD_STATS));
        assertTrue(reloaded.isEnabled(player, PlayerSettingsStore.Setting.SCOREBOARD_ECONOMY));
    }

    @Test
    void failedDialogSaveRollsBackTheWholeSubmission(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("settings.json");
        PlayerSettingsStore store = new PlayerSettingsStore(file);
        UUID player = UUID.randomUUID();
        Files.createDirectory(directory.resolve("settings.json.tmp"));

        assertThrows(UncheckedIOException.class, () -> store.setEnabled(player, Map.of(
                PlayerSettingsStore.Setting.SCOREBOARD_PROFILE, false,
                PlayerSettingsStore.Setting.SCOREBOARD_STATS, false
        )));

        assertTrue(store.isEnabled(player, PlayerSettingsStore.Setting.SCOREBOARD_PROFILE));
        assertTrue(store.isEnabled(player, PlayerSettingsStore.Setting.SCOREBOARD_STATS));
    }

    @Test
    void clearingPutsEveryoneBackOnTheDefaults(@TempDir Path directory) throws IOException {
        PlayerSettingsStore store = new PlayerSettingsStore(directory.resolve("settings.json"));
        UUID player = UUID.randomUUID();

        store.toggle(player, PlayerSettingsStore.Setting.AUTO_SELL);
        store.toggle(player, PlayerSettingsStore.Setting.CLAN_TAGS);
        assertEquals(1, store.clearAll());

        assertFalse(store.isEnabled(player, PlayerSettingsStore.Setting.AUTO_SELL));
        assertTrue(store.isEnabled(player, PlayerSettingsStore.Setting.CLAN_TAGS));
    }

    @Test
    void togglingFlipsAndReportsTheNewState(@TempDir Path directory) throws IOException {
        PlayerSettingsStore store = new PlayerSettingsStore(directory.resolve("settings.json"));
        UUID player = UUID.randomUUID();

        assertFalse(store.toggle(player, PlayerSettingsStore.Setting.CLAN_TAGS));
        assertFalse(store.isEnabled(player, PlayerSettingsStore.Setting.CLAN_TAGS));
        assertTrue(store.toggle(player, PlayerSettingsStore.Setting.CLAN_TAGS));
        assertTrue(store.isEnabled(player, PlayerSettingsStore.Setting.CLAN_TAGS));
    }

    @Test
    void oneToggleDoesNotDisturbTheOther(@TempDir Path directory) throws IOException {
        PlayerSettingsStore store = new PlayerSettingsStore(directory.resolve("settings.json"));
        UUID player = UUID.randomUUID();

        store.toggle(player, PlayerSettingsStore.Setting.DISCORD_CHAT);

        assertFalse(store.isEnabled(player, PlayerSettingsStore.Setting.DISCORD_CHAT));
        assertTrue(store.isEnabled(player, PlayerSettingsStore.Setting.CLAN_TAGS));
    }

    @Test
    void choicesSurviveARestart(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("settings.json");
        UUID player = UUID.randomUUID();
        new PlayerSettingsStore(file).toggle(player, PlayerSettingsStore.Setting.DISCORD_CHAT);

        PlayerSettingsStore reloaded = new PlayerSettingsStore(file);

        assertFalse(reloaded.isEnabled(player, PlayerSettingsStore.Setting.DISCORD_CHAT));
    }

    @Test
    void aPlayerBackAtDefaultsLeavesNothingBehind(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("settings.json");
        PlayerSettingsStore store = new PlayerSettingsStore(file);
        UUID player = UUID.randomUUID();

        store.toggle(player, PlayerSettingsStore.Setting.CLAN_TAGS);
        store.toggle(player, PlayerSettingsStore.Setting.CLAN_TAGS);

        assertEquals("{}", Files.readString(file));
    }

    @Test
    void unknownKeysAreRejectedRatherThanGuessed() {
        assertTrue(PlayerSettingsStore.Setting.fromKey("nonsense").isEmpty());
        assertTrue(PlayerSettingsStore.Setting.fromKey(null).isEmpty());
        assertEquals(
                PlayerSettingsStore.Setting.CLAN_TAGS,
                PlayerSettingsStore.Setting.fromKey("  Clan_Tags  ").orElseThrow()
        );
    }
}
