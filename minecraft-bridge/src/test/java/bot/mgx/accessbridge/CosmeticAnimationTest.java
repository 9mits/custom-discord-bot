package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CosmeticAnimationTest {
    @Test
    void cycleProgressWrapsWithoutFreezingOnOneFrame() {
        assertEquals(0d, CosmeticAnimation.progress(0, 80));
        assertEquals(0.5d, CosmeticAnimation.progress(40, 80));
        assertEquals(0d, CosmeticAnimation.progress(80, 80));
        assertEquals(0.9875d, CosmeticAnimation.progress(-1, 80));
    }

    @Test
    void choreographyCurvesActuallyMoveBetweenFrames() {
        assertEquals(0d, CosmeticAnimation.smooth(0d));
        assertEquals(1d, CosmeticAnimation.smooth(1d));
        assertNotEquals(
                CosmeticAnimation.smooth(0.2d),
                CosmeticAnimation.smooth(0.8d)
        );
        assertTrue(CosmeticAnimation.easeOutBack(0.8d) > 1d);
        assertEquals(0.5d, CosmeticAnimation.pingPong(0.25d));
        assertEquals(0.5d, CosmeticAnimation.pingPong(0.75d));
    }

    @Test
    void phaseProgressClampsBeforeAndAfterItsAct() {
        assertEquals(0d, CosmeticAnimation.phaseProgress(4, 10, 20));
        assertEquals(0.5d, CosmeticAnimation.phaseProgress(15, 10, 20));
        assertEquals(1d, CosmeticAnimation.phaseProgress(25, 10, 20));
        assertThrows(
                IllegalArgumentException.class,
                () -> CosmeticAnimation.phaseProgress(1, 3, 3)
        );
    }

    @Test
    void playersReceiveStableAnimationOffsets() {
        UUID player = UUID.fromString("11111111-2222-3333-4444-555555555555");
        long offset = CosmeticAnimation.playerOffset(player, 80);

        assertEquals(offset, CosmeticAnimation.playerOffset(player, 80));
        assertTrue(offset >= 0L && offset < 80L);
    }
}
