package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PvpModeTest {
    @Test
    void everyAdvertisedModeResolvesToARealImplementation() {
        assertSame(PvpMode.RANKED_DUEL, PvpMode.from("casual").orElseThrow());
        assertSame(PvpMode.RANKED_DUEL, PvpMode.from("1v1").orElseThrow());
        assertSame(PvpMode.DOUBLES, PvpMode.from("doubles").orElseThrow());
        assertSame(PvpMode.TRIPLES, PvpMode.from("3v3").orElseThrow());
        assertSame(PvpMode.CLAN_BATTLE, PvpMode.from("clan-vs-clan").orElseThrow());
        assertSame(PvpMode.FFA, PvpMode.from("last-player-standing").orElseThrow());
        assertTrue(PvpMode.from("bedwars").isEmpty());
    }

    @Test
    void teamsAndArenaCapacityAreExplicit() {
        assertEquals(1, PvpMode.RANKED_DUEL.teamSize());
        assertEquals(2, PvpMode.DOUBLES.teamSize());
        assertEquals(4, PvpMode.DOUBLES.maximumPlayers());
        assertEquals(3, PvpMode.TRIPLES.teamSize());
        assertEquals(6, PvpMode.CLAN_BATTLE.maximumPlayers());
        assertEquals(12, PvpMode.FFA.maximumPlayers());
        assertEquals(3, PvpMode.FFA.minimumPlayers(3));
        assertEquals(12, PvpMode.FFA.minimumPlayers(99));
        assertFalse(PvpMode.PRIVATE_DUEL.queueable());
        assertFalse(PvpMode.CASUAL_DUEL.queueable());
        assertEquals(Set.of(PvpMode.RANKED_DUEL, PvpMode.CLAN_BATTLE, PvpMode.FFA),
                java.util.Arrays.stream(PvpMode.values()).filter(PvpMode::lobbyCategory)
                        .collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.of(PvpMode.RANKED_DUEL, PvpMode.DOUBLES, PvpMode.TRIPLES),
                java.util.Arrays.stream(PvpMode.values()).filter(PvpMode::rated)
                        .collect(java.util.stream.Collectors.toSet()));
    }
}
