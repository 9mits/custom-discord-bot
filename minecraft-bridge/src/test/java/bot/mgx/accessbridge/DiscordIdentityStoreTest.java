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
    void aLinkedNameIsAlwaysVisible() throws Exception {
        Path file = temporaryDirectory.resolve("discord-identities.json");
        UUID minecraftUuid = UUID.randomUUID();
        DiscordIdentityStore store = new DiscordIdentityStore(file);

        store.sync(minecraftUuid, "hellomits");
        assertEquals("hellomits", store.visibleUsername(minecraftUuid).orElseThrow());

        // Players could once hide this, which made a verified account look exactly like
        // an unverified one. The stored flag survives in the file and is ignored.
        DiscordIdentityStore reloaded = new DiscordIdentityStore(file);
        assertEquals("hellomits", reloaded.visibleUsername(minecraftUuid).orElseThrow());
    }

    @Test
    void usernameRefreshReplacesTheStoredName() throws Exception {
        DiscordIdentityStore store = new DiscordIdentityStore(
                temporaryDirectory.resolve("discord-identities.json")
        );
        UUID minecraftUuid = UUID.randomUUID();
        store.sync(minecraftUuid, "oldname");

        store.sync(minecraftUuid, "newname");

        assertEquals("newname", store.visibleUsername(minecraftUuid).orElseThrow());
    }
}
