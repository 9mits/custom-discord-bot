package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CosmeticJournalTest {
    @Test
    void everyChangeIsDurableBeforeTheSnapshotIsRewritten(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("cosmetics.json");
        CosmeticStore store = new CosmeticStore(file);
        store.flush();
        long snapshotBytes = Files.size(file);
        UUID owner = UUID.randomUUID();
        UUID serial = UUID.randomUUID();

        store.mint(owner, "ember_trail", serial);
        store.equip(owner, "TRAIL", serial);

        assertEquals(snapshotBytes, Files.size(file), "a mint must not rewrite the snapshot");
        assertTrue(Files.size(file.resolveSibling("cosmetics.json.journal")) > 0L);
        CosmeticStore reloaded = new CosmeticStore(file);
        assertTrue(reloaded.isStoredBy(owner, serial));
        assertEquals(serial, reloaded.equipped(owner, "TRAIL").orElseThrow());
        assertEquals(1, reloaded.inExistence("ember_trail"));
    }

    @Test
    void aJournalFromAnOlderSnapshotIsNeverReplayed(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("cosmetics.json");
        Path journal = file.resolveSibling("cosmetics.json.journal");
        UUID owner = UUID.randomUUID();
        UUID kept = UUID.randomUUID();
        UUID deleted = UUID.randomUUID();
        CosmeticStore store = new CosmeticStore(file);
        store.mint(owner, "ember_trail", kept);
        store.mint(owner, "ember_trail", deleted);
        byte[] staleJournal = Files.readAllBytes(journal);

        // The deletion rewrites the snapshot under a new epoch. Simulate a crash that
        // left the old journal behind, which would otherwise resurrect the token.
        assertTrue(store.deleteCopy(owner, deleted));
        Files.write(journal, staleJournal);

        CosmeticStore reloaded = new CosmeticStore(file);
        assertTrue(reloaded.token(deleted).isEmpty());
        assertEquals(1, reloaded.inExistence("ember_trail"));
        assertFalse(Files.exists(journal));
    }

    @Test
    void aTornFinalLineIsSkipped(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("cosmetics.json");
        Path journal = file.resolveSibling("cosmetics.json.journal");
        UUID owner = UUID.randomUUID();
        UUID serial = UUID.randomUUID();
        CosmeticStore store = new CosmeticStore(file);
        store.mint(owner, "ember_trail", serial);
        Files.writeString(journal, "{\"op\":\"token\",\"serial\":\"", StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);

        CosmeticStore reloaded = new CosmeticStore(file);
        assertTrue(reloaded.isStoredBy(owner, serial));
    }

    @Test
    void mintingKeepsSerialsAndCountsCurrentWithoutRescanning(@TempDir Path directory) throws Exception {
        CosmeticStore store = new CosmeticStore(directory.resolve("cosmetics.json"));
        UUID owner = UUID.randomUUID();
        for (int index = 1; index <= 5; index++) {
            assertEquals(index, store.mint(owner, "ember_trail", UUID.randomUUID()).serialNumber());
        }
        assertEquals(5, store.inExistence("ember_trail"));
        assertEquals(5, store.stored(owner).size());
        assertEquals(5, store.mintedCount());
        UUID withdrawn = store.stored(owner).get(0).serial();
        store.withdraw(owner, withdrawn).orElseThrow();
        assertEquals(4, store.stored(owner).size());
        assertEquals(5, store.inExistence("ember_trail"));
        assertEquals(6, store.mint(owner, "ember_trail", UUID.randomUUID()).serialNumber());
    }
}
