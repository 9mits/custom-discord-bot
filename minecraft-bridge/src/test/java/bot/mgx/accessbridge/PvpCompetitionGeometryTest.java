package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PvpCompetitionGeometryTest {
    @Test
    void threePlayerTeamsReceiveSeparatedRowsInsideTheirLargerRing() {
        List<int[]> positions = PvpDuelService.competitiveSpawnPositions(
                100, -50, 240, PvpMode.TRIPLES, 3, 3);

        assertEquals(6, positions.size());
        for (int index = 0; index < 3; index++) {
            assertTrue(positions.get(index)[0] < 100);
            assertTrue(positions.get(index + 3)[0] > 100);
            assertTrue(Math.abs(positions.get(index)[1] + 50) < 120);
            assertTrue(Math.abs(positions.get(index + 3)[1] + 50) < 120);
        }
    }

    @Test
    void fullFfaDistributesEveryPlayerAroundTheRealWorldRing() {
        List<int[]> positions = PvpDuelService.competitiveSpawnPositions(
                0, 0, 288, PvpMode.FFA, 12, 0);

        assertEquals(12, positions.size());
        assertEquals(12, positions.stream()
                .map(at -> at[0] + ":" + at[1]).distinct().count());
        for (int[] position : positions) {
            assertTrue(Math.abs(position[0]) < 144);
            assertTrue(Math.abs(position[1]) < 144);
        }
    }
}
