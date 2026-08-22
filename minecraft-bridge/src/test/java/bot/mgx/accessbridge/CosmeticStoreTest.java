package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CosmeticStoreTest {
    @Test
    void mintingTheSameSerialIsIdempotent(@TempDir Path directory) throws Exception {
        CosmeticStore store = new CosmeticStore(directory.resolve("cosmetics.json"));
        UUID owner = UUID.randomUUID();
        UUID serial = UUID.randomUUID();

        CosmeticStore.Token first = store.mint(owner, "blood_burst", serial);
        CosmeticStore.Token repeated = store.mint(owner, "blood_burst", serial);

        assertEquals(first, repeated);
        assertEquals(1, first.serialNumber());
        assertEquals(1, store.stored(owner).size());
        assertEquals(1, store.inExistence("blood_burst"));
        assertEquals(0, store.inExistence("frozen_shatter"));
        assertEquals(first, store.token(serial).orElseThrow());
        assertThrows(
                IllegalArgumentException.class,
                () -> store.mint(owner, "frozen_shatter", serial)
        );
        assertEquals("blood_burst", store.token(serial).orElseThrow().cosmeticId());
    }

    @Test
    void serialNumbersArePermanentAndCountPerCosmetic(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("cosmetics.json");
        UUID owner = UUID.randomUUID();
        CosmeticStore store = new CosmeticStore(file);

        CosmeticStore.Token first = store.mint(owner, "solar_orbit", UUID.randomUUID());
        CosmeticStore.Token second = store.mint(owner, "solar_orbit", UUID.randomUUID());
        CosmeticStore.Token other = store.mint(owner, "blood_burst", UUID.randomUUID());
        store.withdraw(owner, second.serial());
        store.deposit(UUID.randomUUID(), second.serial(), second.cosmeticId(), second.generation());

        assertEquals(1, first.serialNumber());
        assertEquals(2, second.serialNumber());
        assertEquals(1, other.serialNumber());
        assertEquals(2, new CosmeticStore(file).token(second.serial()).orElseThrow().serialNumber());
    }

    @Test
    void oneCosmeticCanBeRenumberedWithoutChangingOwnership(@TempDir Path directory)
            throws Exception {
        Path file = directory.resolve("cosmetics.json");
        UUID owner = UUID.randomUUID();
        CosmeticStore store = new CosmeticStore(file);
        CosmeticStore.Token first = store.mint(owner, "solar_orbit", UUID.randomUUID());
        CosmeticStore.Token second = store.mint(owner, "solar_orbit", UUID.randomUUID());
        CosmeticStore.Token other = store.mint(owner, "blood_burst", UUID.randomUUID());
        String saved = Files.readString(file)
                .replaceFirst("\\\"serial_number\\\":1", "\\\"serial_number\\\":9")
                .replaceFirst("\\\"serial_number\\\":2", "\\\"serial_number\\\":4");
        Files.writeString(file, saved);
        store = new CosmeticStore(file);

        assertEquals(2, store.resetSerials("solar_orbit"));
        assertEquals(0, store.resetSerials("not_real"));

        CosmeticStore reloaded = new CosmeticStore(file);
        assertEquals(2, reloaded.token(first.serial()).orElseThrow().serialNumber());
        assertEquals(1, reloaded.token(second.serial()).orElseThrow().serialNumber());
        assertEquals(1, reloaded.token(other.serial()).orElseThrow().serialNumber());
        assertEquals(owner, reloaded.token(first.serial()).orElseThrow().storedOwner());
    }

    @Test
    void legacyTokensReceiveStableSerialNumbersOnLoad(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("cosmetics.json");
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        Files.writeString(file, "{\"generation\":1,\"tokens\":{" +
                "\"" + first + "\":{\"cosmetic_id\":\"ember_trail\",\"generation\":1}," +
                "\"" + second + "\":{\"cosmetic_id\":\"ember_trail\",\"generation\":1}" +
                "},\"equipped\":{}}");

        CosmeticStore migrated = new CosmeticStore(file);

        assertEquals(1, migrated.token(first).orElseThrow().serialNumber());
        assertEquals(2, migrated.token(second).orElseThrow().serialNumber());
        assertTrue(Files.readString(file).contains("serial_number"));
        CosmeticStore reloaded = new CosmeticStore(file);
        assertEquals(1, reloaded.token(first).orElseThrow().serialNumber());
        assertEquals(2, reloaded.token(second).orElseThrow().serialNumber());
    }

    @Test
    void withdrawingAndDepositingTransfersCustody(@TempDir Path directory) throws Exception {
        CosmeticStore store = new CosmeticStore(directory.resolve("cosmetics.json"));
        UUID firstOwner = UUID.randomUUID();
        UUID nextOwner = UUID.randomUUID();
        UUID serial = UUID.randomUUID();
        CosmeticStore.Token minted = store.mint(firstOwner, "drool_trail", serial);

        CosmeticStore.Token physical = store.withdraw(firstOwner, serial).orElseThrow();
        assertFalse(physical.stored());
        assertFalse(store.isStoredBy(firstOwner, serial));
        assertTrue(store.withdraw(firstOwner, serial).isEmpty());

        assertTrue(store.deposit(
                nextOwner, serial, "drool_trail", minted.generation()
        ));
        assertTrue(store.isStoredBy(nextOwner, serial));
        assertFalse(store.isStoredBy(firstOwner, serial));
        assertEquals(serial, store.stored(nextOwner).get(0).serial());
    }

    @Test
    void alteredAndDuplicatePhysicalTokensAreRejected(@TempDir Path directory) throws Exception {
        CosmeticStore store = new CosmeticStore(directory.resolve("cosmetics.json"));
        UUID firstOwner = UUID.randomUUID();
        UUID nextOwner = UUID.randomUUID();
        UUID serial = UUID.randomUUID();
        CosmeticStore.Token token = store.mint(firstOwner, "solar_orbit", serial);
        store.withdraw(firstOwner, serial).orElseThrow();

        assertFalse(store.deposit(
                nextOwner, serial, "crimson_orbit", token.generation()
        ));
        assertFalse(store.deposit(
                nextOwner, serial, "solar_orbit", token.generation() + 1
        ));
        assertTrue(store.deposit(
                nextOwner, serial, "solar_orbit", token.generation()
        ));
        assertFalse(store.deposit(
                firstOwner, serial, "solar_orbit", token.generation()
        ));
        assertTrue(store.isStoredBy(nextOwner, serial));
    }

    @Test
    void equippedSelectionOnlyClearsWhenTheExpectedTokenMatches(@TempDir Path directory)
            throws Exception {
        CosmeticStore store = new CosmeticStore(directory.resolve("cosmetics.json"));
        UUID player = UUID.randomUUID();
        UUID kill = UUID.randomUUID();
        UUID trail = UUID.randomUUID();
        store.mint(player, "blood_burst", kill);
        store.mint(player, "ember_trail", trail);
        store.equip(player, "KILL_EFFECT", kill);
        store.equip(player, "TRAIL", trail);

        assertEquals(kill, store.equipped(player, "KILL_EFFECT").orElseThrow());
        assertFalse(store.clearEquipped(player, "KILL_EFFECT", UUID.randomUUID()));
        assertEquals(kill, store.equipped(player, "KILL_EFFECT").orElseThrow());
        assertTrue(store.clearEquipped(player, "KILL_EFFECT", kill));
        assertTrue(store.equipped(player, "KILL_EFFECT").isEmpty());
        assertEquals(trail, store.equipped(player, "TRAIL").orElseThrow());
        assertFalse(store.clearEquipped(player, "KILL_EFFECT", kill));
    }

    @Test
    void onePhysicalSerialCanOnlyBeEquippedByOnePlayerAndCategory(@TempDir Path directory)
            throws Exception {
        CosmeticStore store = new CosmeticStore(directory.resolve("cosmetics.json"));
        UUID firstPlayer = UUID.randomUUID();
        UUID nextPlayer = UUID.randomUUID();
        UUID serial = UUID.randomUUID();
        store.mint(firstPlayer, "event_horizon", serial);
        store.withdraw(firstPlayer, serial).orElseThrow();

        store.equip(firstPlayer, "AURA", serial);
        store.equip(nextPlayer, "SECRET", serial);

        assertTrue(store.equipped(firstPlayer, "AURA").isEmpty());
        assertEquals(serial, store.equipped(nextPlayer, "SECRET").orElseThrow());

        CosmeticStore reloaded = new CosmeticStore(directory.resolve("cosmetics.json"));
        assertTrue(reloaded.equipped(firstPlayer, "AURA").isEmpty());
        assertEquals(serial, reloaded.equipped(nextPlayer, "SECRET").orElseThrow());
    }

    @Test
    void failedLeaseTransferRestoresEverySelection(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("cosmetics.json");
        CosmeticStore store = new CosmeticStore(file);
        UUID firstPlayer = UUID.randomUUID();
        UUID nextPlayer = UUID.randomUUID();
        UUID serial = UUID.randomUUID();
        UUID unrelated = UUID.randomUUID();
        store.mint(firstPlayer, "event_horizon", serial);
        store.mint(firstPlayer, "ember_trail", unrelated);
        store.equip(firstPlayer, "SECRET", serial);
        store.equip(firstPlayer, "TRAIL", unrelated);
        Files.createDirectory(file.resolveSibling(file.getFileName() + ".tmp"));

        assertThrows(
                UncheckedIOException.class,
                () -> store.equip(nextPlayer, "SECRET", serial)
        );
        assertEquals(serial, store.equipped(firstPlayer, "SECRET").orElseThrow());
        assertEquals(unrelated, store.equipped(firstPlayer, "TRAIL").orElseThrow());
        assertTrue(store.equipped(nextPlayer, "SECRET").isEmpty());

        CosmeticStore reloaded = new CosmeticStore(file);
        assertEquals(serial, reloaded.equipped(firstPlayer, "SECRET").orElseThrow());
        assertEquals(unrelated, reloaded.equipped(firstPlayer, "TRAIL").orElseThrow());
        assertTrue(reloaded.equipped(nextPlayer, "SECRET").isEmpty());
    }

    @Test
    void tokensCustodyAndSelectionsSurviveReload(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("cosmetics.json");
        UUID owner = UUID.randomUUID();
        UUID physicalSerial = UUID.randomUUID();
        UUID storedSerial = UUID.randomUUID();
        CosmeticStore first = new CosmeticStore(file);
        first.mint(owner, "prismatic_trail", physicalSerial);
        first.withdraw(owner, physicalSerial).orElseThrow();
        first.mint(owner, "shining_light", storedSerial);
        first.equip(owner, "KILL_EFFECT", storedSerial);

        CosmeticStore reloaded = new CosmeticStore(file);

        assertEquals(1, reloaded.generation());
        assertFalse(reloaded.token(physicalSerial).orElseThrow().stored());
        assertTrue(reloaded.isStoredBy(owner, storedSerial));
        assertEquals(1, reloaded.stored(owner).size());
        assertEquals(
                storedSerial,
                reloaded.equipped(owner, "KILL_EFFECT").orElseThrow()
        );
    }

    @Test
    void resetAdvancesGenerationAndInvalidatesOldPhysicalTokens(@TempDir Path directory)
            throws Exception {
        Path file = directory.resolve("cosmetics.json");
        CosmeticStore store = new CosmeticStore(file);
        UUID owner = UUID.randomUUID();
        UUID serial = UUID.randomUUID();
        CosmeticStore.Token old = store.mint(owner, "event_horizon", serial);
        store.withdraw(owner, serial).orElseThrow();
        store.equip(owner, "SECRET", serial);

        assertEquals(2, store.clearAll());
        assertEquals(old.generation() + 1, store.generation());
        assertTrue(store.token(serial).isEmpty());
        assertTrue(store.equipped(owner, "SECRET").isEmpty());
        assertFalse(store.deposit(owner, serial, old.cosmeticId(), old.generation()));

        CosmeticStore reloaded = new CosmeticStore(file);
        assertEquals(old.generation() + 1, reloaded.generation());
        assertTrue(reloaded.token(serial).isEmpty());
        assertTrue(reloaded.stored(owner).isEmpty());

        int beforeEmptyReset = reloaded.generation();
        assertEquals(0, reloaded.clearAll());
        assertEquals(beforeEmptyReset + 1, reloaded.generation());
        assertEquals(beforeEmptyReset + 1, new CosmeticStore(file).generation());
    }
}
