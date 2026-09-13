package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
    void onlyTheThreeConfigurableCategoriesHaveGateways() {
        for (PvpMode mode : PvpMode.values()) {
            if (mode.lobbyCategory()) {
                assertNotNull(PvpLobbyBuilder.gatePosition(mode),
                        mode + " is a lobby category but has no gateway to stand in");
            } else {
                assertNull(PvpLobbyBuilder.gatePosition(mode),
                        mode + " is a size variant or private mode and must not have a gateway");
            }
        }
    }

    @Test
    void gatewaySetDoesNotFragmentThePopulation() {
        Set<PvpMode> physical = java.util.Arrays.stream(PvpMode.values())
                .filter(mode -> PvpLobbyBuilder.gatePosition(mode) != null)
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of(PvpMode.RANKED_DUEL, PvpMode.CLAN_BATTLE, PvpMode.FFA), physical);
        assertNull(PvpLobbyBuilder.gatePosition(PvpMode.CASUAL_DUEL));
        assertNull(PvpLobbyBuilder.gatePosition(PvpMode.DOUBLES));
        assertNull(PvpLobbyBuilder.gatePosition(PvpMode.TRIPLES));
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

    /**
     * The moat wall is the queue island's edge, and the boulevard is its one opening.
     *
     * <p>The wall ring is drawn before the concourse, so the route north is only clear
     * because due north is a crossing and the concourse clears the headroom it left.
     * Drop either and the two islands are rejoined by a wall a player cannot walk
     * through, which is exactly how the first joined lobby shipped.
     */
    @Test
    void theBoulevardOpensTheWallRingItCrosses() throws Exception {
        String lobby = source();
        assertTrue(lobby.contains("CROSSINGS = {0d,"),
                "due north must be a crossing or the boulevard ends at the moat wall");
        String concourse = lobby.substring(lobby.indexOf("private static void buildSharedConcourse("),
                lobby.indexOf("private static void buildGalleryMoat("));
        assertTrue(concourse.contains("setType(Material.AIR, false)"),
                "the concourse must clear the wall course it runs through");
    }

    /**
     * The records court is composed, not just floored.
     *
     * <p>Six boards on a bare disc read as an annex of the queue island rather than
     * the other half of one lobby, which is the whole reason the two were joined.
     */
    @Test
    void theRecordsCourtIsComposedAndPlantedBeforeItsColonnade() throws Exception {
        String lobby = source();
        assertTrue(lobby.contains("buildGalleryMonument(world)"));
        assertTrue(lobby.contains("buildGalleryGardens(world)"));
        assertTrue(lobby.contains("buildGalleryColonnade(world)"));
        // The colonnade lays a stone collar over the planting, so it has to run second.
        assertTrue(lobby.indexOf("buildGalleryGardens(world);")
                        < lobby.indexOf("buildGalleryColonnade(world);"),
                "the colonnade must be built after the gardens it stands in");
    }

    /**
     * The queue island stopped pointing at a leaderboard it does not hold.
     *
     * <p>A whole island is dedicated to the boards and it is signposted at its own
     * entrance, so a pavilion on the queue island that only said "follow the north
     * concourse" spent a build slot telling players to walk away. That slot is now
     * where a fight is actually configured.
     */
    @Test
    void theQueueIslandConfiguresFightsWhereItUsedToPointAtTheBoards() throws Exception {
        String lobby = source();
        assertTrue(lobby.contains("PavilionAction.PLAY"),
                "the fourth pavilion is now where a fight is configured");
        assertTrue(!lobby.contains("PavilionAction.RATINGS"),
                "the ratings pavilion is replaced, not merely relabelled");
        assertTrue(!lobby.contains("FOLLOW THE NORTH CONCOURSE"),
                "no pavilion should exist only to send players to the other island");
    }

    private static String source() throws Exception {
        return Files.readString(
                Path.of("src/main/java/bot/mgx/accessbridge/PvpLobbyBuilder.java"),
                StandardCharsets.UTF_8);
    }
}
