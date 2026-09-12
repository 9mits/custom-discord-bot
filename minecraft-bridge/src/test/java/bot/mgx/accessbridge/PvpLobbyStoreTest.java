package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PvpLobbyStoreTest {
    @TempDir
    Path temporary;

    @Test
    void lobbyAndPortalPersistWithoutAnyPremadeArenaDefinitions() throws Exception {
        Path file = temporary.resolve("pvp-lobby.json");
        PvpLobbyStore store = new PvpLobbyStore(file);
        assertTrue(store.needsLobbyBuild());
        PvpLobbyStore.Point lobby = point(PvpLobbyBuilder.WORLD_NAME, 1, 81, 9);
        PvpLobbyStore.Point portal = point("world", 40, 70, -20);
        store.installGenerated(lobby);
        store.setPortal(portal, 3.5d);

        PvpLobbyStore reopened = new PvpLobbyStore(file);
        assertEquals(lobby, reopened.lobby().orElseThrow());
        assertEquals(portal, reopened.portal().orElseThrow());
        assertEquals(3.5d, reopened.portalRadius());
        assertTrue(!reopened.needsLobbyBuild());
        assertTrue(!Files.readString(file).contains("arenas"));
    }

    @Test
    void anOldArenaFileMigratesByKeepingOnlyLobbyAndPortal() throws Exception {
        Path file = temporary.resolve("old.json");
        PvpLobbyStore.Point lobby = point("lobby", 0, 81, 0);
        Files.writeString(file, "{\"version\":1,\"lobby\":{"
                + "\"worldId\":\"" + lobby.worldId() + "\",\"worldName\":\"lobby\","
                + "\"x\":0,\"y\":81,\"z\":0,\"yaw\":0,\"pitch\":0},"
                + "\"arenas\":[{\"id\":\"obsolete\"}]}");
        PvpLobbyStore migrated = new PvpLobbyStore(file);
        assertTrue(migrated.lobby().isPresent());
        assertTrue(migrated.needsLobbyBuild());
        migrated.installGenerated(lobby);
        assertTrue(!Files.readString(file).contains("arenas"));
    }

    private static PvpLobbyStore.Point point(String world, double x, double y, double z) {
        return new PvpLobbyStore.Point(UUID.randomUUID().toString(), world, x, y, z, 0f, 0f);
    }
}
