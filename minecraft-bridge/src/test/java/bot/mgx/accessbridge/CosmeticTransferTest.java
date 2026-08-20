package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CosmeticTransferTest {
    @Test
    void everyEquippedCosmeticChangesHandsOnce(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("cosmetics.json");
        CosmeticStore store = new CosmeticStore(file);
        UUID victim = UUID.randomUUID();
        UUID killer = UUID.randomUUID();
        UUID aura = UUID.randomUUID();
        UUID trail = UUID.randomUUID();
        store.mint(victim, "amethyst_orbit", aura);
        store.mint(victim, "ember_trail", trail);
        store.equip(victim, CosmeticCatalog.Category.AURA.name(), aura);
        store.equip(victim, CosmeticCatalog.Category.TRAIL.name(), trail);

        List<CosmeticStore.Token> moved = store.transferEquipped(victim, killer);

        assertEquals(2, moved.size());
        assertTrue(store.isStoredBy(killer, aura));
        assertTrue(store.isStoredBy(killer, trail));
        assertTrue(store.equipped(victim, CosmeticCatalog.Category.AURA.name()).isEmpty());
        // The tokens are unique, so the loser must not keep a copy of either.
        assertEquals(1, store.inExistence("amethyst_orbit"));
        assertEquals(1, store.inExistence("ember_trail"));
    }

    @Test
    void unequippedCosmeticsStayWithTheirOwner(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("cosmetics.json");
        CosmeticStore store = new CosmeticStore(file);
        UUID victim = UUID.randomUUID();
        UUID killer = UUID.randomUUID();
        UUID spare = UUID.randomUUID();
        store.mint(victim, "ember_trail", spare);

        assertTrue(store.transferEquipped(victim, killer).isEmpty());
        assertTrue(store.isStoredBy(victim, spare));
    }

    @Test
    void transferSurvivesAReload(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("cosmetics.json");
        CosmeticStore store = new CosmeticStore(file);
        UUID victim = UUID.randomUUID();
        UUID killer = UUID.randomUUID();
        UUID serial = UUID.randomUUID();
        store.mint(victim, "ember_trail", serial);
        store.equip(victim, CosmeticCatalog.Category.TRAIL.name(), serial);
        store.transferEquipped(victim, killer);

        CosmeticStore reloaded = new CosmeticStore(file);
        assertTrue(reloaded.isStoredBy(killer, serial));
        assertTrue(reloaded.equipped(victim, CosmeticCatalog.Category.TRAIL.name()).isEmpty());
    }
}
