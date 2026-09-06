package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PvpDuelStoreTest {
    @TempDir
    Path temporary;

    @Test
    void recoverySurvivesReloadAndSettlementDropsOnlyTheWager() throws Exception {
        Path file = temporary.resolve("pvp-duel-recovery.json");
        UUID player = UUID.randomUUID();
        UUID duel = UUID.randomUUID();
        UUID world = UUID.randomUUID();
        PvpDuelStore.Recovery recovery = new PvpDuelStore.Recovery(
                duel, PvpDuelStore.Role.FIGHTER, world, "world",
                12.5d, 70d, -40.5d, 90f, 4f, "SURVIVAL",
                false, false, false, 13d, 17, 2.5f, 0.4f,
                20, 1.5f, 280, 4, 7_500L, "wager-items", ""
        );
        PvpDuelStore first = new PvpDuelStore(file);
        first.putAll(Map.of(player, recovery));

        PvpDuelStore loaded = new PvpDuelStore(file);
        assertEquals(recovery, loaded.find(player).orElseThrow());

        loaded.settle(player, 9_000L);
        PvpDuelStore.Recovery settled = new PvpDuelStore(file).find(player).orElseThrow();
        assertEquals(9_000L, settled.balanceBefore());
        assertEquals("", settled.encodedStake());
        assertEquals("world", settled.worldName());

        loaded.remove(player);
        assertFalse(new PvpDuelStore(file).find(player).isPresent());
    }

    @Test
    void multipleParticipantsAreWrittenTogether() throws Exception {
        PvpDuelStore store = new PvpDuelStore(temporary.resolve("recovery.json"));
        UUID duel = UUID.randomUUID();
        UUID world = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        PvpDuelStore.Recovery fighter = recovery(duel, world, PvpDuelStore.Role.FIGHTER);
        PvpDuelStore.Recovery spectator = recovery(duel, world, PvpDuelStore.Role.SPECTATOR);
        store.putAll(Map.of(first, fighter, second, spectator));
        assertEquals(2, store.all().size());
        assertTrue(store.find(first).isPresent());
        assertEquals(PvpDuelStore.Role.SPECTATOR, store.find(second).orElseThrow().role());
    }

    private static PvpDuelStore.Recovery recovery(
            UUID duel, UUID world, PvpDuelStore.Role role
    ) {
        return new PvpDuelStore.Recovery(
                duel, role, world, "world", 0d, 64d, 0d, 0f, 0f,
                "SURVIVAL", false, false, false, 20d, 20, 5f, 0f,
                0, 0f, 300, 0, 100L, "", role == PvpDuelStore.Role.SPECTATOR
                ? "inventory" : ""
        );
    }
}
