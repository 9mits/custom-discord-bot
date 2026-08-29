package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ClanWarpMetaStoreTest {
    @TempDir
    Path directory;

    @Test
    void anUnconfiguredWarpIsOpenToTheWholeClan() throws Exception {
        ClanWarpMetaStore store = new ClanWarpMetaStore(directory.resolve("warps.json"));
        UUID clan = UUID.randomUUID();

        // Nobody has decided yet, which is not the same as deciding on nobody.
        assertTrue(store.allowed(clan, "base").isEmpty());
        assertTrue(store.mayUse(clan, "base", UUID.randomUUID()));
    }

    @Test
    void choosingMembersShutsOutEveryoneElseAndClearingReopensIt() throws Exception {
        ClanWarpMetaStore store = new ClanWarpMetaStore(directory.resolve("warps.json"));
        UUID clan = UUID.randomUUID();
        UUID chosen = UUID.randomUUID();
        UUID other = UUID.randomUUID();

        store.toggleAllowed(clan, "base", chosen);
        assertTrue(store.mayUse(clan, "base", chosen));
        assertFalse(store.mayUse(clan, "base", other));

        store.allowEveryone(clan, "base");
        assertTrue(store.mayUse(clan, "base", other),
                "clearing the list restores the everyone default");

        // Toggling the last member off must not leave the warp locked to nobody.
        store.toggleAllowed(clan, "base", chosen);
        store.toggleAllowed(clan, "base", chosen);
        assertTrue(store.mayUse(clan, "base", other));
    }

    @Test
    void renamingCarriesTheIconAndGuestListAndDeletingForgetsThem() throws Exception {
        Path file = directory.resolve("warps.json");
        ClanWarpMetaStore store = new ClanWarpMetaStore(file);
        UUID clan = UUID.randomUUID();
        UUID chosen = UUID.randomUUID();

        store.setIcon(clan, "base", "item/diamond");
        store.toggleAllowed(clan, "base", chosen);
        store.rename(clan, "base", "home");

        assertEquals("item/diamond", store.iconOf(clan, "home"));
        assertTrue(store.mayUse(clan, "home", chosen));
        assertFalse(store.mayUse(clan, "home", UUID.randomUUID()));

        ClanWarpMetaStore reloaded = new ClanWarpMetaStore(file);
        assertEquals("item/diamond", reloaded.iconOf(clan, "home"));

        reloaded.forget(clan, "home");
        assertTrue(reloaded.allowed(clan, "home").isEmpty());
    }

    @Test
    void anIconOutsideTheCatalogueIsRefused() throws Exception {
        ClanWarpMetaStore store = new ClanWarpMetaStore(directory.resolve("warps.json"));

        // A sprite naming no texture would draw a magenta square with no way to tell why.
        assertThrows(IllegalArgumentException.class,
                () -> store.setIcon(UUID.randomUUID(), "base", "item/not_a_thing"));
    }
}
