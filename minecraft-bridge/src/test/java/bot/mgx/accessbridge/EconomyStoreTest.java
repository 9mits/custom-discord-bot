package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EconomyStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void depositsSurviveReload() throws Exception {
        Path path = temporaryDirectory.resolve("balances.json");
        UUID player = UUID.fromString("11111111-1111-1111-1111-111111111111");
        EconomyStore first = new EconomyStore(path);
        first.deposit(player, 250);

        EconomyStore reloaded = new EconomyStore(path);
        assertEquals(250L, reloaded.balance(player));
    }

    @Test
    void aWithdrawThatWouldGoNegativeIsRefused() throws Exception {
        EconomyStore store = new EconomyStore(temporaryDirectory.resolve("balances.json"));
        UUID player = UUID.randomUUID();
        store.deposit(player, 10);

        assertFalse(store.tryWithdraw(player, 11));
        assertEquals(10L, store.balance(player));
        assertTrue(store.tryWithdraw(player, 10));
        assertEquals(0L, store.balance(player));
    }

    @Test
    void aTransferMovesTheWholeAmountOrNothing() throws Exception {
        EconomyStore store = new EconomyStore(temporaryDirectory.resolve("balances.json"));
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();
        store.deposit(from, 40);

        assertFalse(store.transfer(from, to, 41));
        assertEquals(40L, store.balance(from));
        assertEquals(0L, store.balance(to));
        assertTrue(store.transfer(from, to, 15));
        assertEquals(25L, store.balance(from));
        assertEquals(15L, store.balance(to));
    }
}
