package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GiftbagStoreTest {
    @TempDir Path directory;

    @Test
    void aSelectedRewardSurvivesRestartUntilExactlyThatSpinCompletes() throws Exception {
        Path file = directory.resolve("giftbags.json");
        GiftbagStore store = new GiftbagStore(file);
        UUID player = UUID.randomUUID();
        UUID spin = UUID.randomUUID();
        store.reserve(player, spin, "fatebound_idol", 3, 123L);

        GiftbagStore reopened = new GiftbagStore(file);
        GiftbagStore.Pending pending = reopened.pending(player).orElseThrow();
        assertEquals(spin, pending.spinId());
        assertEquals(3, pending.season());
        assertFalse(reopened.complete(player, UUID.randomUUID()), "another spin cannot erase the reward");
        assertEquals(1, reopened.all().size());
        assertThrows(IllegalStateException.class,
                () -> reopened.reserve(player, UUID.randomUUID(), "shards_4", 3, 124L));
        reopened.complete(player, spin);
        assertTrue(reopened.all().isEmpty());
    }

    @Test
    void theWelcomeGiftIsClaimedOnceHoweverOftenAPlayerLogsIn() throws Exception {
        Path file = directory.resolve("welcome.json");
        GiftbagStore store = new GiftbagStore(file);
        UUID player = UUID.randomUUID();
        UUID other = UUID.randomUUID();

        assertFalse(store.welcomed(player));
        assertTrue(store.claimWelcome(player), "the first claim is the one that counts");
        assertFalse(store.claimWelcome(player), "relogging cannot farm a second bag");
        assertTrue(store.welcomed(player));
        assertTrue(store.claimWelcome(other), "every player still gets their own");

        // A restart must not hand everybody a second one.
        GiftbagStore reopened = new GiftbagStore(file);
        assertTrue(reopened.welcomed(player));
        assertFalse(reopened.claimWelcome(player));
    }

    @Test
    void bagsEarnedWhileOfflineWaitInTheLedgerAndAreHandedOverOnce() throws Exception {
        Path file = directory.resolve("owed.json");
        GiftbagStore store = new GiftbagStore(file);
        UUID player = UUID.randomUUID();

        assertEquals(0, store.takeOwed(player), "nothing owed is nothing to hand over");
        store.owe(player, 1);
        store.owe(player, 2);

        GiftbagStore reopened = new GiftbagStore(file);
        assertEquals(3, reopened.takeOwed(player), "both referrals survived the restart");
        assertEquals(0, reopened.takeOwed(player), "and they are handed over exactly once");
    }
}
