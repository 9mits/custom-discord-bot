package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RankSyncStoreTest {
    @TempDir
    Path directory;

    private RankSyncStore store() throws IOException {
        return new RankSyncStore(directory.resolve("rank-sync.json"));
    }

    @Test
    void nobodyIsHeldToBeginWith() throws IOException {
        assertFalse(store().isHeld(UUID.randomUUID()));
    }

    @Test
    void holdingIsRememberedAcrossRestarts() throws IOException {
        UUID player = UUID.randomUUID();
        assertTrue(store().hold(player, "Steve"));

        RankSyncStore reopened = store();

        assertTrue(reopened.isHeld(player));
        assertEquals("Steve", reopened.holds().get(player));
    }

    @Test
    void holdingTwiceReportsNoChange() throws IOException {
        RankSyncStore store = store();
        UUID player = UUID.randomUUID();

        assertTrue(store.hold(player, "Steve"));
        assertFalse(store.hold(player, "Steve"));
        assertFalse(store.hold(player, "Alex"));
        assertEquals("Alex", store().holds().get(player));
    }

    @Test
    void releasingRestoresSyncAndReportsWhetherItDidAnything() throws IOException {
        RankSyncStore store = store();
        UUID player = UUID.randomUUID();
        store.hold(player, "Steve");

        assertTrue(store.release(player));
        assertFalse(store.isHeld(player));
        assertFalse(store.release(player));
    }

    @Test
    void holdingDropsWhatSyncHadGranted() throws IOException {
        // Otherwise releasing them later would strip a group an admin set by hand in
        // the meantime, which is the whole thing holding exists to prevent.
        RankSyncStore store = store();
        UUID player = UUID.randomUUID();
        store.recordApplied(player, "staff");

        store.hold(player, "Steve");

        assertTrue(store.appliedRank(player).isEmpty());
    }

    @Test
    void appliedRanksAreRecordedAndSurviveReopening() throws IOException {
        UUID player = UUID.randomUUID();
        store().recordApplied(player, "legend");

        assertEquals("legend", store().appliedRank(player).orElseThrow());
    }

    @Test
    void anEmptyRankMeansSyncOwnsNothing() throws IOException {
        RankSyncStore store = store();
        UUID player = UUID.randomUUID();
        store.recordApplied(player, "legend");

        store.recordApplied(player, "");

        assertTrue(store.appliedRank(player).isEmpty());
    }

    @Test
    void clearingForgetsEveryGrant() throws IOException {
        RankSyncStore store = store();
        store.recordApplied(UUID.randomUUID(), "og");
        store.recordApplied(UUID.randomUUID(), "supporter");

        assertEquals(2, store.clearApplied());
        assertEquals(0, store.clearApplied());
    }

    @Test
    void clearingGrantsLeavesHoldsAlone() throws IOException {
        RankSyncStore store = store();
        UUID player = UUID.randomUUID();
        store.hold(player, "Steve");

        store.clearApplied();

        assertTrue(store.isHeld(player));
    }
}
