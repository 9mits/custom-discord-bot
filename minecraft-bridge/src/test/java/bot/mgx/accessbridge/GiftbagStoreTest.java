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
}
