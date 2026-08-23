package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateNoticeStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void nothingIsShownUntilAnUpdateIsPublished() throws Exception {
        UpdateNoticeStore store = new UpdateNoticeStore(temporaryDirectory.resolve("notices.json"));

        assertFalse(store.active());
        assertFalse(store.tryClaim(UUID.randomUUID()));
    }

    @Test
    void aPlayerSeesEachPublishedUpdateOnce() throws Exception {
        Path path = temporaryDirectory.resolve("notices.json");
        UUID player = UUID.randomUUID();
        UpdateNoticeStore store = new UpdateNoticeStore(path);

        assertEquals(1, store.publish());
        assertTrue(store.tryClaim(player));
        assertFalse(store.tryClaim(player));

        UpdateNoticeStore reloaded = new UpdateNoticeStore(path);
        assertTrue(reloaded.active());
        assertEquals(1, reloaded.generation());
        assertFalse(reloaded.tryClaim(player));
    }

    @Test
    void publishingAgainShowsTheNoticeOnTheNextJoin() throws Exception {
        UpdateNoticeStore store = new UpdateNoticeStore(temporaryDirectory.resolve("notices.json"));
        UUID player = UUID.randomUUID();
        store.publish();
        assertTrue(store.tryClaim(player));

        assertEquals(2, store.publish());
        assertTrue(store.tryClaim(player));
    }

    @Test
    void playersAlreadyOnlineAreMarkedSeen() throws Exception {
        UpdateNoticeStore store = new UpdateNoticeStore(temporaryDirectory.resolve("notices.json"));
        UUID player = UUID.randomUUID();
        store.publish();
        store.markSeen(List.of(player));

        assertFalse(store.tryClaim(player));
    }

    @Test
    void aFailedWriteDoesNotConsumeTheNotice() throws Exception {
        Path path = temporaryDirectory.resolve("notices.json");
        UpdateNoticeStore store = new UpdateNoticeStore(path);
        store.publish();
        UUID player = UUID.randomUUID();
        Files.createDirectory(path.resolveSibling("notices.json.tmp"));

        assertThrows(UncheckedIOException.class, () -> store.tryClaim(player));
        Files.delete(path.resolveSibling("notices.json.tmp"));
        assertTrue(store.tryClaim(player));
    }

    @Test
    void featureVersionIgnoresThePatchNumber() {
        assertEquals("6.5", UpdateNoticeStore.featureVersion("6.5.1"));
        assertEquals("6.5", UpdateNoticeStore.featureVersion("6.5.0"));
        assertEquals("10.0", UpdateNoticeStore.featureVersion(" 10.0.3 "));
        assertEquals("", UpdateNoticeStore.featureVersion("6"));
        assertEquals("", UpdateNoticeStore.featureVersion(null));
    }

    @Test
    void theFirstRunNeverAnnounces(@TempDir Path directory) throws IOException {
        UpdateNoticeStore store = new UpdateNoticeStore(directory.resolve("update-notices.json"));
        // Creating the store is not an update: everybody online already has
        // this version, and a banner for it would be a lie.
        assertFalse(store.publishIfVersionChanged("6.5.0"));
        assertFalse(store.active());
    }

    @Test
    void aFeatureBumpAnnouncesButAPatchDoesNot(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("update-notices.json");
        new UpdateNoticeStore(file).publishIfVersionChanged("6.4.0");

        UpdateNoticeStore store = new UpdateNoticeStore(file);
        assertFalse(store.publishIfVersionChanged("6.4.9"), "a patch is not an update");
        assertTrue(store.publishIfVersionChanged("6.5.0"), "a feature bump is");
        assertTrue(store.active());
        assertFalse(store.publishIfVersionChanged("6.5.3"), "still the same feature version");
    }
}
