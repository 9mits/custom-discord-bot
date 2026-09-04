package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Discord usernames are the only personal data this plugin stores, so the rules for
 * removing them are worth pinning down.
 */
class DiscordIdentityClearTest {
    @TempDir
    Path directory;

    private DiscordIdentityStore store() throws IOException {
        return new DiscordIdentityStore(directory.resolve("discord-identities.json"));
    }

    @Test
    void clearingForgetsOnePlayerAndSurvivesReopening() throws IOException {
        DiscordIdentityStore store = store();
        UUID player = UUID.randomUUID();
        store.sync(player, "hellomits");

        assertTrue(store.clear(player));

        assertTrue(store().identity(player).isEmpty());
    }

    @Test
    void clearingSomebodyUnknownReportsNoChange() throws IOException {
        assertFalse(store().clear(UUID.randomUUID()));
    }

    @Test
    void clearingOnePlayerLeavesTheRestAlone() throws IOException {
        DiscordIdentityStore store = store();
        UUID kept = UUID.randomUUID();
        UUID removed = UUID.randomUUID();
        store.sync(kept, "someone");
        store.sync(removed, "hellomits");

        store.clear(removed);

        assertEquals("someone", store.visibleUsername(kept).orElseThrow());
        assertTrue(store.identity(removed).isEmpty());
    }

    @Test
    void clearingEverythingEmptiesTheStore() throws IOException {
        DiscordIdentityStore store = store();
        store.sync(UUID.randomUUID(), "one");
        store.sync(UUID.randomUUID(), "two");

        assertEquals(2, store.clearAll());
        assertEquals(0, store.clearAll());
        assertEquals(0, store().clearAll());
    }

    @Test
    void anEmptyUsernameNeverOverwritesAKnownOne() throws IOException {
        // The bridge decides whether an empty name means "unlinked" (clear) or "lookup
        // failed" (leave alone); the store must not guess by silently blanking it.
        DiscordIdentityStore store = store();
        UUID player = UUID.randomUUID();
        store.sync(player, "hellomits");

        store.sync(player, "");
        store.sync(player, "   ");

        assertEquals("hellomits", store.visibleUsername(player).orElseThrow());
    }

    @Test
    void aStoredNameIsClearedRatherThanLeftBehind() throws IOException {
        DiscordIdentityStore store = store();
        UUID player = UUID.randomUUID();
        store.sync(player, "considerationproud");

        assertEquals("considerationproud", store.visibleUsername(player).orElseThrow());
        assertEquals(1, store.clearAll());
        assertTrue(store().identity(player).isEmpty());
    }
}
