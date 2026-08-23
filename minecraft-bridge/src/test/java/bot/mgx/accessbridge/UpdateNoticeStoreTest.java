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
    void theFirstSightingNeverAnnounces(@TempDir Path directory) throws IOException {
        UpdateNoticeStore store = new UpdateNoticeStore(directory.resolve("update-notices.json"));
        // The server has only just learned which post is newest. Everybody has
        // almost certainly already seen it, so a banner would be a lie.
        assertFalse(store.publishIfPostChanged("update-3"));
        assertFalse(store.active());
    }

    @Test
    void aNewPostAnnouncesAndTheSameOneDoesNot(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("update-notices.json");
        new UpdateNoticeStore(file).publishIfPostChanged("update-3");

        UpdateNoticeStore store = new UpdateNoticeStore(file);
        assertFalse(store.publishIfPostChanged("update-3"), "same post, nothing new to read");
        assertTrue(store.publishIfPostChanged("update-4"), "a new post is an update");
        assertTrue(store.active());
        assertFalse(store.publishIfPostChanged("update-4"), "still the same post");
    }

    @Test
    void anEmptyOrMissingSlugIsIgnored(@TempDir Path directory) throws IOException {
        UpdateNoticeStore store = new UpdateNoticeStore(directory.resolve("update-notices.json"));
        // A site that is half-built or briefly broken must not clear the record
        // and then announce the same post all over again.
        assertFalse(store.publishIfPostChanged(null));
        assertFalse(store.publishIfPostChanged("   "));
        assertFalse(store.active());
    }

}
