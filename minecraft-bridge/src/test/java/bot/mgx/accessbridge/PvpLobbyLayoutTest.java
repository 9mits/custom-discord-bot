package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
 *
 * <p>The second failure was not rounding at all. Three arches were left on bearings
 * chosen for six, the consoles sat on the diagonals, and the records lived on a
 * separate circle — so no two things on the island shared a bearing and the whole
 * plan read as scattered. These tests pin the plan itself: one ring, one spacing.
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

    /** Every structure on the island: three arches, six records walls, the way home. */
    private static List<int[]> ring() {
        List<int[]> spots = new ArrayList<>();
        for (PvpMode mode : PvpMode.values()) {
            int[] at = PvpLobbyBuilder.gatePosition(mode);
            if (at != null) spots.add(at);
        }
        for (PvpLobbyBuilder.LeaderboardBoard board
                : PvpLobbyBuilder.LeaderboardBoard.values()) {
            spots.add(PvpLobbyBuilder.leaderboardPosition(board));
        }
        spots.add(PvpLobbyBuilder.returnGatePosition());
        return spots;
    }

    /**
     * Ten structures, one circle, one spacing.
     *
     * <p>This is the whole organising idea of the island, and it is the thing that
     * cannot be seen in a diff: a bearing that is not a multiple of the ring step
     * compiles perfectly and looks, from the middle, like the builder gave up.
     */
    @Test
    void everyStructureStandsOnTheOneRingAtTheOneSpacing() {
        List<int[]> spots = ring();
        assertEquals(10, spots.size(), "the ring is ten structures");
        Set<String> seen = new HashSet<>();
        for (int[] at : spots) {
            assertTrue(seen.add(at[0] + ":" + at[1]),
                    "two structures share the spot " + at[0] + "," + at[1]);
            double radius = Math.hypot(at[0], at[1]);
            // Rounding to whole blocks can move a point half a block off the circle.
            assertTrue(Math.abs(radius - PvpLobbyBuilder.gateRing()) <= 0.75d,
                    "a structure sits at radius " + radius + ", off the ring");
        }
        double step = 360d / spots.size();
        for (int[] at : spots) {
            double bearing = (Math.toDegrees(Math.atan2(at[0], -at[1])) + 360d) % 360d;
            double off = Math.min(bearing % step, step - bearing % step);
            assertTrue(off <= 2.5d,
                    "a structure sits on bearing " + bearing + ", off the " + step + " step");
        }
    }

    /** A plan that is not mirrored is the one thing a player notices from the middle. */
    @Test
    void theRingMirrorsAcrossTheNorthSouthAxis() {
        Set<String> spots = new HashSet<>();
        for (int[] at : ring()) spots.add(at[0] + ":" + at[1]);
        for (int[] at : ring()) {
            assertTrue(spots.contains((-at[0]) + ":" + at[1]),
                    "nothing mirrors " + at[0] + "," + at[1] + " across the axis");
        }
    }

    /** Due south is the walk home, and due north the arch an arriving player faces. */
    @Test
    void theWayHomeOwnsTheSouthAndAnArchOwnsTheNorth() {
        int[] home = PvpLobbyBuilder.returnGatePosition();
        assertEquals(0, home[0], "the way home is not on the axis");
        assertTrue(home[1] > 0, "the way home is not due south");
        int[] ranked = PvpLobbyBuilder.gatePosition(PvpMode.RANKED_DUEL);
        assertEquals(0, ranked[0], "the ranked arch is not on the axis");
        assertTrue(ranked[1] < 0, "the ranked arch is not due north");
        for (PvpMode mode : PvpMode.values()) {
            int[] at = PvpLobbyBuilder.gatePosition(mode);
            if (at == null) continue;
            assertTrue(!(Math.abs(at[0]) <= 4 && at[1] > 0),
                    mode + " blocks the southern approach to the way home");
        }
    }

    @Test
    void everyPvpLeaderboardHasItsOwnProtectedWall() {
        Set<String> seen = new HashSet<>();
        for (PvpLobbyBuilder.LeaderboardBoard board
                : PvpLobbyBuilder.LeaderboardBoard.values()) {
            int[] at = PvpLobbyBuilder.leaderboardPosition(board);
            assertTrue(seen.add(at[0] + ":" + at[1]), board + " shares a leaderboard frame");
            assertTrue(Math.hypot(at[0], at[1] - 12.5d) < PvpLobbyBuilder.PROTECTED_RADIUS,
                    board + " lies outside lobby protection");
            // The northern arc belongs to the arches and due south to the way home.
            assertTrue(!(Math.abs(at[0]) <= 4),
                    board + " stands on the axis, which the arches and the way home own");
        }
        assertEquals(6, seen.size());
    }

    @Test
    void leaderboardWallsMirrorAcrossTheAxis() {
        for (List<PvpLobbyBuilder.LeaderboardBoard> pair : List.of(
                List.of(PvpLobbyBuilder.LeaderboardBoard.RATING,
                        PvpLobbyBuilder.LeaderboardBoard.STREAK),
                List.of(PvpLobbyBuilder.LeaderboardBoard.WINS,
                        PvpLobbyBuilder.LeaderboardBoard.CLAN_WINS),
                List.of(PvpLobbyBuilder.LeaderboardBoard.KILLS,
                        PvpLobbyBuilder.LeaderboardBoard.CLAN_KILLS))) {
            int[] left = PvpLobbyBuilder.leaderboardPosition(pair.get(0));
            int[] right = PvpLobbyBuilder.leaderboardPosition(pair.get(1));
            assertEquals(-left[0], right[0], pair + " are not mirrored across x");
            assertEquals(left[1], right[1], pair + " do not share one curved wall");
        }
    }

    /**
     * The records came off their own island, so nothing that built it may remain.
     *
     * <p>A leftover gallery builder would still run and still generate a second
     * circle forty blocks north, which is the exact emptiness this rebuild removed.
     */
    @Test
    void theSeparateRecordsIslandIsGoneRatherThanUnused() {
        String lobby = source();
        for (String remnant : List.of("buildLeaderboardGallery", "buildGalleryMoat",
                "buildSharedConcourse", "buildGalleryColonnade", "buildGalleryGardens",
                "buildGalleryMonument", "GALLERY_CENTRE_Z", "galleryBridge")) {
            assertTrue(!lobby.contains(remnant),
                    remnant + " still exists; the records island was not actually removed");
        }
        assertTrue(lobby.contains("buildCourt(world)"));
        assertTrue(lobby.contains("buildLeaderboardFrame(world, board)"));
    }

    /**
     * One billboard for every label in the lobby.
     *
     * <p>A label whose plane is fixed to the structure behind it reads as painted onto
     * that structure while its neighbours turn to follow the reader, which is exactly
     * how one arch's sign came to look like a solid object.
     */
    @Test
    void everyLabelTurnsToFaceTheReader() {
        String lobby = source();
        assertTrue(!lobby.contains("Display.Billboard.FIXED"),
                "a fixed-plane label reads as painted on, not as a sign");
        assertTrue(lobby.contains("Display.Billboard.VERTICAL"));
        assertTrue(!lobby.contains("fixedHologram"),
                "the fixed-plane helper should be gone, not merely unused");
    }

    /**
     * The queue island stopped pointing at a leaderboard it does not hold.
     *
     * <p>The records now stand on the same ring as the arches, so a console whose only
     * message was "follow the north concourse" has nothing left to say.
     */
    @Test
    void theConsoleThatPointedAtTheBoardsNowConfiguresAFight() {
        String lobby = source();
        assertTrue(lobby.contains("PavilionAction.PLAY"),
                "the fourth console is now where a fight is configured");
        assertTrue(!lobby.contains("PavilionAction.RATINGS"),
                "the ratings console is replaced, not merely relabelled");
        assertTrue(!lobby.contains("FOLLOW THE NORTH CONCOURSE"),
                "no console should exist only to send players to another island");
    }

    private static String source() {
        try {
            return Files.readString(
                    Path.of("src/main/java/bot/mgx/accessbridge/PvpLobbyBuilder.java"),
                    StandardCharsets.UTF_8);
        } catch (java.io.IOException error) {
            throw new java.io.UncheckedIOException(error);
        }
    }
}
