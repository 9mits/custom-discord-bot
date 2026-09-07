package bot.mgx.accessbridge;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where the Scythe's idle trail is drawn.
 *
 * <p>It was anchored to {@code getEyeLocation().getDirection()}, which is the
 * crosshair rather than the body. A held item does not move when you look at your
 * feet, and the side vector derived from a pitched look vector is not even level —
 * between them the trail rendered about a metre off the weapon.
 */
final class ScytheTrailFrameTest {
    private static final double EXACT = 1e-9;

    @Test
    void yawZeroFacesSouthAndPutsTheRightHandWest() {
        Vector forward = PvpRankRewardService.forwardOf(0f);
        assertEquals(0d, forward.getX(), EXACT);
        assertEquals(1d, forward.getZ(), EXACT);

        Vector side = PvpRankRewardService.sideOf(0f, true);
        assertEquals(-1d, side.getX(), EXACT);
        assertEquals(0d, side.getZ(), EXACT);
    }

    @Test
    void theFrameIsAlwaysLevelWhateverTheYaw() {
        // The bug: a side vector crossed out of a pitched look vector tilts, so the
        // blade tipped out of the model as soon as the holder looked up or down.
        for (float yaw = -360f; yaw <= 360f; yaw += 7.5f) {
            assertEquals(0d, PvpRankRewardService.forwardOf(yaw).getY(), EXACT, "yaw " + yaw);
            assertEquals(0d, PvpRankRewardService.sideOf(yaw, true).getY(), EXACT, "yaw " + yaw);
        }
    }

    @Test
    void theFrameStaysUnitLengthAndSquare() {
        for (float yaw = -180f; yaw <= 180f; yaw += 15f) {
            Vector forward = PvpRankRewardService.forwardOf(yaw);
            Vector side = PvpRankRewardService.sideOf(yaw, true);

            assertEquals(1d, forward.length(), EXACT, "yaw " + yaw);
            assertEquals(1d, side.length(), EXACT, "yaw " + yaw);
            assertEquals(0d, forward.dot(side), EXACT, "yaw " + yaw);
        }
    }

    @Test
    void aLeftHandedPlayerCarriesItOnTheOtherSide() {
        for (float yaw : new float[] {0f, 45f, 90f, 180f, -137f}) {
            Vector right = PvpRankRewardService.sideOf(yaw, true);
            Vector left = PvpRankRewardService.sideOf(yaw, false);

            assertEquals(-1d, right.dot(left), EXACT, "yaw " + yaw);
        }
    }

    @Test
    void theBladeStaysWithinArmsLengthOfTheHolder() {
        // Whatever the offsets are tuned to, they have to keep the effect on the
        // weapon. A standing player's eyes are 1.62 above their feet; anything
        // approaching a block from there is the cloud beside the player this replaced.
        double eyeHeight = 1.62d;
        double gripReach = Math.sqrt(
                PvpRankRewardService.HAND_OUT * PvpRankRewardService.HAND_OUT
                        + PvpRankRewardService.HAND_FORWARD * PvpRankRewardService.HAND_FORWARD
                        + Math.pow(PvpRankRewardService.HAND_HEIGHT - eyeHeight, 2));
        double tipReach = Math.sqrt(
                PvpRankRewardService.HAND_OUT * PvpRankRewardService.HAND_OUT
                        + PvpRankRewardService.HAND_FORWARD * PvpRankRewardService.HAND_FORWARD
                        + Math.pow(PvpRankRewardService.HAND_HEIGHT
                                + PvpRankRewardService.BLADE_HEIGHT - eyeHeight, 2));

        assertTrue(gripReach < 0.75d, "the grip drifted away from the hand: " + gripReach);
        assertTrue(tipReach < 0.75d, "the blade tip drifted off the model: " + tipReach);
        // And it must stay in front of the camera rather than inside it.
        assertTrue(gripReach > 0.2d, "the grip is inside the holder's own head");
    }
}
