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

    @Test
    void totalOfSumsEveryListedWallet() throws Exception {
        EconomyStore store = new EconomyStore(temporaryDirectory.resolve("balances.json"));
        UUID one = UUID.randomUUID();
        UUID two = UUID.randomUUID();
        store.deposit(one, 40);
        store.deposit(two, 15);

        assertEquals(55L, store.totalOf(List.of(one, two)));
        assertEquals(40L, store.totalOf(List.of(one, UUID.randomUUID())));
    }

    @Test
    void invalidOrOverflowingTransfersNeverChangeEitherWallet() throws Exception {
        EconomyStore store = new EconomyStore(temporaryDirectory.resolve("balances.json"));
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();
        store.deposit(from, 20);
        store.set(to, Long.MAX_VALUE);

        assertThrows(IllegalArgumentException.class, () -> store.transfer(from, to, -1));
        assertThrows(IllegalArgumentException.class, () -> store.transfer(from, to, 1));
        assertEquals(20L, store.balance(from));
        assertEquals(Long.MAX_VALUE, store.balance(to));
        assertFalse(store.canDeposit(to, 1));
    }

    @Test
    void failedPersistenceRollsBackEveryWalletMutation() throws Exception {
        Path path = temporaryDirectory.resolve("balances.json");
        EconomyStore store = new EconomyStore(path);
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();
        store.deposit(from, 40);
        store.deposit(to, 10);
        Files.createDirectory(path.resolveSibling("balances.json.tmp"));

        assertThrows(UncheckedIOException.class, () -> store.deposit(from, 5));
        assertThrows(UncheckedIOException.class, () -> store.tryWithdraw(from, 5));
        assertThrows(UncheckedIOException.class, () -> store.transfer(from, to, 5));
        assertThrows(UncheckedIOException.class, store::clearAll);

        assertEquals(40L, store.balance(from));
        assertEquals(10L, store.balance(to));
    }
}
