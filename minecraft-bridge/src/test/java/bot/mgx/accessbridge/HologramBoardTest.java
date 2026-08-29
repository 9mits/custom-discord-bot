package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class HologramBoardTest {
    @Test
    void eventBoardsHaveStableAdminAliases() {
        assertEquals(
                HologramService.Board.AMETHYST_CRATES,
                HologramService.Board.fromKey("amethyst-crates")
        );
        assertEquals(
                HologramService.Board.AMETHYST_AIRDROPS,
                HologramService.Board.fromKey("amethyst_airdrops")
        );
        assertEquals(
                HologramService.Board.CLAN_BATTLE,
                HologramService.Board.fromKey("clanbattle")
        );
    }

    @Test
    void unknownBoardExplainsEveryNewChoice() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> HologramService.Board.fromKey("not-a-board")
        );

        assertTrue(failure.getMessage().contains("amethyst-crates"));
        assertTrue(failure.getMessage().contains("amethyst-airdrops"));
        assertTrue(failure.getMessage().contains("clan-battle"));
    }
}
