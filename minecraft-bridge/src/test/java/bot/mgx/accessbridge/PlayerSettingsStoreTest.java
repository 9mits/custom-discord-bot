package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerSettingsStoreTest {
    @Test
    void everythingIsShownUntilAPlayerSaysOtherwise(@TempDir Path directory) throws IOException {
        PlayerSettingsStore store = new PlayerSettingsStore(directory.resolve("settings.json"));

        for (PlayerSettingsStore.Setting setting : PlayerSettingsStore.Setting.values()) {
            assertTrue(store.isEnabled(UUID.randomUUID(), setting));
        }
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
