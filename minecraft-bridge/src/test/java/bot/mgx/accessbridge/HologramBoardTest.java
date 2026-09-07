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
        assertEquals(
                HologramService.Board.PVP_RANKS,
                HologramService.Board.fromKey("pvp-ranks")
        );
        // The name a placement is saved under has to come back as the same board, or
        // a restart abandons the stands it already put in the world.
        assertEquals(
                HologramService.Board.PVP_RANKS,
                HologramService.Board.fromKey(
                        HologramService.Board.PVP_RANKS.name()
                                .toLowerCase(java.util.Locale.ROOT).replace('_', '-'))
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
        assertTrue(failure.getMessage().contains("pvp-ranks"));
    }
}
