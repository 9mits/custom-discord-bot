package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DiscordIdentityStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void linkedNamesAreVisibleByDefaultAndCanBeHidden() throws Exception {
        Path file = temporaryDirectory.resolve("discord-identities.json");
        UUID minecraftUuid = UUID.randomUUID();
        DiscordIdentityStore store = new DiscordIdentityStore(file);

        store.sync(minecraftUuid, "hellomits");
        assertEquals("hellomits", store.visibleUsername(minecraftUuid).orElseThrow());

        DiscordIdentityStore.Identity hidden = store.toggle(minecraftUuid);
        assertFalse(hidden.visible());
        assertTrue(store.visibleUsername(minecraftUuid).isEmpty());

        DiscordIdentityStore reloaded = new DiscordIdentityStore(file);
        assertFalse(reloaded.identity(minecraftUuid).orElseThrow().visible());
    }

    @Test
    void usernameRefreshPreservesThePlayersVisibilityChoice() throws Exception {
        DiscordIdentityStore store = new DiscordIdentityStore(
                temporaryDirectory.resolve("discord-identities.json")
        );
        UUID minecraftUuid = UUID.randomUUID();
        store.sync(minecraftUuid, "oldname");
        store.toggle(minecraftUuid);

        store.sync(minecraftUuid, "newname");

        DiscordIdentityStore.Identity identity = store.identity(minecraftUuid).orElseThrow();
        assertEquals("newname", identity.username());
        assertFalse(identity.visible());
    }
}
