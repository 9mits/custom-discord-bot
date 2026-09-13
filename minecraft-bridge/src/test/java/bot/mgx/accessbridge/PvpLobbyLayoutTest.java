package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lobby's radial plan, which is entirely rounding and entirely invisible in code.
 *
 * <p>The first build put one gateway a block nearer the middle than the one opposite
 * it, because {@code sin(30°)} is 0.49999999999999994 while {@code sin(330°)} is
 * -0.5000000000000004 and the two round in different directions. Nothing failed; it
 * just looked wrong from the middle of the island, which is the only place anybody
 * stands.
 */
final class PvpLobbyLayoutTest {
    @Test
    void everyQueueHasAGatewayAndPrivateDuelsDoNot() {
        for (PvpMode mode : PvpMode.values()) {
            if (mode.queueable()) {
                assertNotNull(PvpLobbyBuilder.gatePosition(mode),
                        mode + " is queueable but has no gateway to stand in");
            } else {
                assertNull(PvpLobbyBuilder.gatePosition(mode),
                        mode + " is not queued for and must not have a gateway");
            }
        }
    }

    @Test
    void oppositeGatewaysAreExactMirrors() {
        for (List<PvpMode> pair : List.of(
                List.of(PvpMode.RANKED_DUEL, PvpMode.CASUAL_DUEL),
                List.of(PvpMode.DOUBLES, PvpMode.TRIPLES),
                List.of(PvpMode.CLAN_BATTLE, PvpMode.FFA))) {
            int[] left = PvpLobbyBuilder.gatePosition(pair.get(0));
            int[] right = PvpLobbyBuilder.gatePosition(pair.get(1));
            assertEquals(Math.abs(left[0]), Math.abs(right[0]),
                    pair + " are not mirrored across x");
            assertEquals(Math.abs(left[1]), Math.abs(right[1]),
                    pair + " are not mirrored across z");
        }
    }

    @Test
    void everyGatewaySitsOnTheRingAndNoTwoShareASpot() {
        Set<String> seen = new HashSet<>();
        for (PvpMode mode : PvpMode.values()) {
            int[] at = PvpLobbyBuilder.gatePosition(mode);
            if (at == null) continue;
            assertTrue(seen.add(at[0] + ":" + at[1]), mode + " shares a spot with another gateway");
            double radius = Math.hypot(at[0], at[1]);
            // Rounding to whole blocks can move a point half a block off the circle.
            assertTrue(Math.abs(radius - PvpLobbyBuilder.gateRing()) <= 0.75d,
                    mode + " sits at radius " + radius + ", off the gateway ring");
        }
    }

    /** Due south is kept clear so the walk home is not crowded by a queue arch. */
    @Test
    void nothingStandsBetweenArrivalAndTheWayHome() {
        for (PvpMode mode : PvpMode.values()) {
            int[] at = PvpLobbyBuilder.gatePosition(mode);
            if (at == null) continue;
            boolean dueSouth = Math.abs(at[0]) <= 4 && at[1] > 0;
            assertTrue(!dueSouth, mode + " blocks the southern approach to the return gate");
        }
    }

    @Test
    void everyPvpLeaderboardHasItsOwnProtectedGalleryBoard() {
        Set<String> seen = new HashSet<>();
        for (PvpLobbyBuilder.LeaderboardBoard board
                : PvpLobbyBuilder.LeaderboardBoard.values()) {
            int[] at = PvpLobbyBuilder.leaderboardPosition(board);
            assertTrue(seen.add(at[0] + ":" + at[1]), board + " shares a leaderboard frame");
            assertTrue(Math.hypot(at[0], at[1] - 12.5d) < PvpLobbyBuilder.PROTECTED_RADIUS,
                    board + " lies outside lobby protection");
            double galleryRadius = Math.hypot(at[0],
                    at[1] - PvpLobbyBuilder.galleryCentreZ());
            assertTrue(Math.abs(galleryRadius - 14.8d) <= 1d,
                    board + " is detached from the circular gallery wall");
            assertTrue(!(Math.abs(at[0]) <= 4
                            && at[1] > PvpLobbyBuilder.galleryCentreZ()),
                    board + " blocks the gallery entrance");
        }
        assertEquals(6, seen.size());
        assertEquals(-40, PvpLobbyBuilder.galleryCentreZ());
        assertEquals(16, PvpLobbyBuilder.galleryRadius());
    }

    @Test
    void leaderboardWallsMirrorAcrossTheSharedConcourse() {
        for (List<PvpLobbyBuilder.LeaderboardBoard> pair : List.of(
                List.of(PvpLobbyBuilder.LeaderboardBoard.RATING,
                        PvpLobbyBuilder.LeaderboardBoard.CLAN_KILLS),
                List.of(PvpLobbyBuilder.LeaderboardBoard.WINS,
                        PvpLobbyBuilder.LeaderboardBoard.CLAN_WINS),
                List.of(PvpLobbyBuilder.LeaderboardBoard.KILLS,
                        PvpLobbyBuilder.LeaderboardBoard.STREAK))) {
            int[] left = PvpLobbyBuilder.leaderboardPosition(pair.get(0));
            int[] right = PvpLobbyBuilder.leaderboardPosition(pair.get(1));
            assertEquals(-left[0], right[0], pair + " are not mirrored across x");
            assertEquals(left[1], right[1], pair + " do not share one curved wall");
        }
    }
}
