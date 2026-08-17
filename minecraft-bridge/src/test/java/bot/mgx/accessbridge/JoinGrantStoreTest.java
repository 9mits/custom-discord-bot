package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JoinGrantStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void aPlayerIsGrantedOncePerToggle() throws Exception {
        JoinGrantStore store = new JoinGrantStore(temporaryDirectory.resolve("grants.json"));
        UUID player = UUID.randomUUID();
        store.enable(JoinGrantStore.Kind.MONEY, 500);

        assertTrue(store.tryClaim(JoinGrantStore.Kind.MONEY, player));
        assertFalse(store.tryClaim(JoinGrantStore.Kind.MONEY, player));
        assertEquals(500L, store.amount(JoinGrantStore.Kind.MONEY));
    }

    @Test
    void turningTheToggleOnAgainStartsANewPass() throws Exception {
        Path path = temporaryDirectory.resolve("grants.json");
        UUID player = UUID.randomUUID();
        JoinGrantStore store = new JoinGrantStore(path);
        store.enable(JoinGrantStore.Kind.BOUNTY, 100);
        assertTrue(store.tryClaim(JoinGrantStore.Kind.BOUNTY, player));
        store.disable(JoinGrantStore.Kind.BOUNTY);
        store.enable(JoinGrantStore.Kind.BOUNTY, 250);

        JoinGrantStore reloaded = new JoinGrantStore(path);
        assertTrue(reloaded.enabled(JoinGrantStore.Kind.BOUNTY));
        assertEquals(250L, reloaded.amount(JoinGrantStore.Kind.BOUNTY));
        assertTrue(reloaded.tryClaim(JoinGrantStore.Kind.BOUNTY, player));
    }

    @Test
    void aDisabledGrantPaysNobody() throws Exception {
        JoinGrantStore store = new JoinGrantStore(temporaryDirectory.resolve("grants.json"));
        store.enable(JoinGrantStore.Kind.MONEY, 10);
        store.disable(JoinGrantStore.Kind.MONEY);

        assertFalse(store.tryClaim(JoinGrantStore.Kind.MONEY, UUID.randomUUID()));
    }
}
