package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
}
