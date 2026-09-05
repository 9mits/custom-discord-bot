package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class HologramBoardTest {
    @Test
    void eventBoardsHaveStableAdminAliases() {
        assertEquals(
                HologramService.Board.DRAGON_DAMAGE,
                HologramService.Board.fromKey("dragon-damage")
        );
        assertEquals(
                HologramService.Board.DRAGON_CRYSTALS,
                HologramService.Board.fromKey("dragon_crystals")
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

        assertTrue(failure.getMessage().contains("dragon-damage"));
        assertTrue(failure.getMessage().contains("dragon-crystals"));
        assertTrue(failure.getMessage().contains("clan-battle"));
    }
}
