package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The store is the half of screenshot mode that can lose somebody's inventory,
 * so it is the half that is tested. The service around it needs a live server.
 */
class DevBlogStoreTest {
    @TempDir
    Path temporaryDirectory;

    private static DevBlogStore.Session session(String contents) {
        return new DevBlogStore.Session(contents, "armour", "SURVIVAL", false, 1_000L);
    }

    @Test
    void aSessionSurvivesARestart() throws Exception {
        Path path = temporaryDirectory.resolve("devblog-sessions.json");
        UUID player = UUID.randomUUID();

        DevBlogStore before = new DevBlogStore(path);
        before.open(player, session("stashed"));
        assertTrue(before.isActive(player));

        // The whole point: a crash between open and close must not lose items.
        DevBlogStore after = new DevBlogStore(path);
        assertTrue(after.isActive(player));
        assertEquals("stashed", after.find(player).orElseThrow().encodedContents());
        assertEquals("SURVIVAL", after.find(player).orElseThrow().previousGameMode());
    }

    @Test
    void closingReturnsTheStashExactlyOnce() throws Exception {
        Path path = temporaryDirectory.resolve("devblog-sessions.json");
        UUID player = UUID.randomUUID();
        DevBlogStore store = new DevBlogStore(path);
        store.open(player, session("stashed"));

        assertEquals("stashed", store.close(player).orElseThrow().encodedContents());
        // A second restore must not hand out a duplicate set of items.
        assertTrue(store.close(player).isEmpty());
        assertFalse(store.isActive(player));
    }

    @Test
    void closingIsPersistedSoItDoesNotComeBack() throws Exception {
        Path path = temporaryDirectory.resolve("devblog-sessions.json");
        UUID player = UUID.randomUUID();
        DevBlogStore store = new DevBlogStore(path);
        store.open(player, session("stashed"));
        store.close(player);

        assertFalse(new DevBlogStore(path).isActive(player));
    }

    @Test
    void everyoneListsOpenSessionsForShutdown() throws Exception {
        Path path = temporaryDirectory.resolve("devblog-sessions.json");
        DevBlogStore store = new DevBlogStore(path);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        store.open(first, session("a"));
        store.open(second, session("b"));

        assertEquals(2, store.everyone().size());
        assertTrue(store.everyone().contains(first));
        assertTrue(store.everyone().contains(second));
    }

    @Test
    void keptArmourIsRemembered() throws Exception {
        Path path = temporaryDirectory.resolve("devblog-sessions.json");
        UUID player = UUID.randomUUID();
        DevBlogStore store = new DevBlogStore(path);
        store.open(player, new DevBlogStore.Session("c", "", "CREATIVE", true, 5L));

        DevBlogStore reloaded = new DevBlogStore(path);
        DevBlogStore.Session found = reloaded.find(player).orElseThrow();
        assertTrue(found.keptArmour());
        assertEquals("CREATIVE", found.previousGameMode());
    }

    @Test
    void anUnreadableStoreRefusesToStart() throws Exception {
        // Starting with an empty stash would present the items as gone, so
        // failing loudly is the only safe answer.
        Path path = temporaryDirectory.resolve("devblog-sessions.json");
        Files.createDirectories(path.getParent());
        Files.writeString(path, "{ this is not json");
        assertThrows(IOException.class, () -> new DevBlogStore(path));
    }

    @Test
    void anAbsentStoreIsSimplyEmpty() throws Exception {
        DevBlogStore store = new DevBlogStore(temporaryDirectory.resolve("nothing-here.json"));
        assertTrue(store.everyone().isEmpty());
        assertFalse(store.isActive(UUID.randomUUID()));
    }

    @Test
    void bytesSurviveTheRoundTrip() {
        byte[] raw = {0, 1, 2, -1, -128, 127};
        assertArrayEquals(raw, DevBlogStore.decode(DevBlogStore.encode(raw)));
    }
}
