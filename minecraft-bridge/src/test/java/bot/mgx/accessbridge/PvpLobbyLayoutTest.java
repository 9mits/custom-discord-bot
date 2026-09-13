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

    /** The inner ring: three arches and the way home. */
    private static List<int[]> gateRing() {
        List<int[]> spots = new ArrayList<>();
        for (PvpMode mode : PvpMode.values()) {
            int[] at = PvpLobbyBuilder.gatePosition(mode);
            if (at != null) spots.add(at);
        }
        spots.add(PvpLobbyBuilder.returnGatePosition());
        return spots;
    }

    /** The outer ring: the six records walls. */
    private static List<int[]> boardRing() {
        List<int[]> spots = new ArrayList<>();
        for (PvpLobbyBuilder.LeaderboardBoard board
                : PvpLobbyBuilder.LeaderboardBoard.values()) {
            spots.add(PvpLobbyBuilder.leaderboardPosition(board));
        }
        return spots;
    }

    private static List<int[]> ring() {
        List<int[]> spots = new ArrayList<>(gateRing());
        spots.addAll(boardRing());
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
    void everyStructureStandsOnItsOwnRingAtTheOneSpacing() {
        assertEquals(4, gateRing().size(), "the inner ring is three arches and the way home");
        assertEquals(6, boardRing().size(), "the outer ring is six records walls");
        Set<String> seen = new HashSet<>();
        for (int[] at : ring()) {
            assertTrue(seen.add(at[0] + ":" + at[1]),
                    "two structures share the spot " + at[0] + "," + at[1]);
        }
        assertOnRing(gateRing(), PvpLobbyBuilder.gateRing());
        assertOnRing(boardRing(), PvpLobbyBuilder.boardRing());
        // Both rings step in the same ten bearings, which is what ties them together.
        double step = 36d;
        for (int[] at : ring()) {
            double bearing = (Math.toDegrees(Math.atan2(at[0], -at[1])) + 360d) % 360d;
            double off = Math.min(bearing % step, step - bearing % step);
            assertTrue(off <= 2.5d,
                    "a structure sits on bearing " + bearing + ", off the " + step + " step");
        }
    }

    private static void assertOnRing(List<int[]> spots, int radius) {
        for (int[] at : spots) {
            double found = Math.hypot(at[0], at[1]);
            // Rounding to whole blocks can move a point half a block off the circle.
            assertTrue(Math.abs(found - radius) <= 0.75d,
                    "a structure sits at radius " + found + ", off the ring at " + radius);
        }
    }

    /**
     * The records walls need arc, and the gateways need to be close.
     *
     * <p>Six sets of five result rows standing five blocks clear of their own backing
     * overlap each other long before the walls do, which is why the two rings exist at
     * all. If the outer ring is ever pulled in to the inner one, filled leaderboards
     * become unreadable again.
     */
    @Test
    void theRecordsRingHasTheArcSixFillingBoardsNeed() {
        int radius = PvpLobbyBuilder.boardRing();
        assertTrue(radius > PvpLobbyBuilder.gateRing() + 6,
                "the records ring must stand well outside the gateway ring");
        double labelRadius = radius - 5d;
        double arcBetweenBoards = 2d * Math.PI * labelRadius * (36d / 360d);
        assertTrue(arcBetweenBoards >= 11d,
                "adjacent result rows have only " + arcBetweenBoards + " blocks between them");
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
            assertTrue(Math.abs(Math.hypot(at[0], at[1]) - PvpLobbyBuilder.boardRing()) <= 0.75d,
                    board + " is not on the records ring");
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

    /**
     * A console page is a destination, not a step in a journey.
     *
     * <p>Back on a page opened from a block in the world has nowhere to return to, and
     * the framework's fallback sends the player to the server's main menu — out of the
     * page the console exists to show, and into something unrelated to PvP.
     */
    @Test
    void consolePagesOfferCloseRatherThanBack() {
        String service = service();
        assertTrue(service.contains("case LADDER -> openLadder(player, STANDALONE)"));
        assertTrue(service.contains("case RULES -> openRules(player, STANDALONE)"));
        assertTrue(service.contains("case PLAY -> openModes(player, STANDALONE)"));
        assertTrue(service.contains("case LIVE -> openLive(player, STANDALONE)"));
        assertTrue(service.contains("Screens.showStandalone(player, title, page, buttons, columns)"));
        String screens = read("Screens.java");
        assertTrue(screens.contains("static void showStandalone("),
                "the framework needs a no-parent page, not a disabled Back");
    }

    /**
     * The queue page cannot be read while its reader is stood in a live portal.
     *
     * <p>The client throws its own dialog away when portal travel begins, so the page
     * only survives if the player is not in the arch. Stepping them out once was not
     * enough: a player still holding forward walks back into it a tick later, and if
     * the step-out sits behind the same cooldown as the page, that second entry leaves
     * them standing in the portal with the page already open. Observed directly — the
     * screen opened as "Ranked 1v1" and was gone on the next poll.
     */
    @Test
    void everyTouchOfAnArchStepsYouOutEvenWhenThePageDoesNotReopen() {
        String service = service();
        // The move path steps out unconditionally and only then consults the gate.
        int step = service.indexOf("if (approach != null) event.setTo(approach);");
        int gate = service.indexOf("queueTouched(player, queue);", step);
        assertTrue(step > 0 && gate > step,
                "the step-out must happen on every touch, not behind the page cooldown");
        assertTrue(service.contains("private void queueTouched(Player player, PvpMode mode)"),
                "one gate, so whichever path sees the touch opens the page");
        // And the page itself must not be drawn in the same tick as that move.
        assertTrue(service.contains("private void openQueuePage(Player player, PvpMode mode)"));
        assertTrue(service.contains("else openMode(player, mode, STANDALONE);"));
        assertTrue(source().contains("static Location gateApproach("));
        // A player who stops moving inside an arch fires no further move event.
        String sweep = service.substring(
                service.indexOf("private void suppressCustomPortalTravel()"),
                service.indexOf("private void installStarterLobby()"));
        assertTrue(sweep.contains("gateApproach(lobby, queue)"),
                "the sweep must also get a stationary player out of an arch");
        assertTrue(sweep.contains("queueTouched(player, queue);"),
                "a sweep that steps a player out must not swallow their queue");
    }

    /**
     * Arriving in the lobby shows the hub, a couple of ticks after the teleport.
     *
     * <p>Drawn in the same tick as the world change it is discarded along with the
     * loading screen, which is why entering the PvP dimension showed no menu at all.
     */
    @Test
    void enteringTheLobbyOpensTheHubOnceTheTeleportHasSettled() {
        String service = service();
        String enter = service.substring(service.indexOf("void enterLobby(Player player)"),
                service.indexOf("private void teleportToLobbyIfReady("));
        int teleport = enter.indexOf("teleport(player, lobby);");
        int open = enter.indexOf("openHub(player)", teleport);
        assertTrue(teleport > 0 && open > teleport);
        assertTrue(enter.contains("runTaskLater("),
                "the hub must not be drawn in the same tick as the teleport");
    }

    /**
     * A finished fight puts you back where you started it.
     *
     * <p>Which is the lobby for anyone who queued at an arch — being dropped into the
     * middle of survival after every match means walking back to the entrance portal
     * to fight twice. Someone who started from the ordinary world is the exception and
     * is put back exactly where they were.
     */
    @Test
    void aFinishedFightReturnsYouToWhereYouStartedIt() {
        String service = service();
        assertTrue(service.contains("private Location returnDestination("));
        assertTrue(service.contains("Location destination = returnDestination(saved);"),
                "restore must use the rule, not the raw snapshot position");
        String rule = service.substring(service.indexOf("private Location returnDestination("),
                service.indexOf("private PvpDuelStore.Recovery snapshot("));
        assertTrue(rule.contains("if (!isPvpWorld(started.getWorld())) return started;"),
                "a fight started in the ordinary world returns there");
        assertTrue(rule.contains("lobbyStore.lobby()"),
                "a fight started in the lobby returns to the lobby");
    }

    /**
     * /pvp is permanent and is not part of any running event.
     *
     * <p>Naming the lobby after a limited-time event tells players the whole mode is
     * temporary, and leaves the name wrong the moment the event ends.
     */
    @Test
    void theLobbyIsNotNamedAfterATemporaryEvent() {
        String lobby = source();
        assertTrue(!lobby.contains("AMETHYST TERRACE"),
                "the lobby must not be named after the Amethyst event");
        assertTrue(lobby.contains("THE PROVING GROUNDS"));
        assertTrue(!lobby.contains("NOTHING LOST"),
                "the centre title carries no motto under it");
    }

    /**
     * Explanations are skimmable rows, not paragraphs.
     *
     * <p>How PvP Works set the shape: an icon to anchor each idea, a bold heading to
     * skim and muted detail to read. The competitive screens were blocks of prose in
     * the same dialog frame, which nobody reads.
     */
    @Test
    void pagesThatExplainSomethingUseRowsRatherThanProse() {
        String service = service();
        assertTrue(service.contains("MenuText.rule("),
                "an explanation should be icon-led rows, like How PvP Works");
        assertTrue(service.contains("MenuText.stat("),
                "a readout should be labelled stats, not a run-on line");
        // The rules page in particular was one paragraph in a dialog.
        String rules = service.substring(service.indexOf("private void openRules("),
                service.indexOf("void openLive(Player player)"));
        assertTrue(rules.contains("showRules(player, \"PvP Rules & Fair Play\""));
        assertTrue(!rules.contains("\\n\\n"),
                "the rules page should no longer be assembling a paragraph");
    }

    /**
     * The one block a player is meant to click must not look like scenery.
     *
     * <p>The consoles were a stone stem with a lantern and a crystal on top, which is
     * exactly what the lamp standards around them are — and a lamp on the inner walk
     * stood directly between the plaza and the console it was hiding.
     */
    @Test
    void aConsoleLooksLikeSomethingYouClick() {
        String lobby = source();
        String console = lobby.substring(lobby.indexOf("private static void buildPavilions("),
                lobby.indexOf("private static List<Pavilion> pavilions()"));
        assertTrue(console.contains("Material.SEA_LANTERN") && console.contains("CUT_COPPER"),
                "a console needs a lit screen in a surround nothing else on the island has");
        assertTrue(console.contains("DEEPSLATE_BRICK_SLAB"), "and a desk to walk up to");
        assertTrue(!console.contains("AMETHYST_CLUSTER"),
                "a crystal on a stem is what made it read as a lamp standard");
        assertTrue(lobby.contains("if (!consoleBearing(between)) lamp(world, between, INNER_WALK)"),
                "no lamp may stand between the plaza and a console");
        assertTrue(lobby.contains("RIGHT-CLICK TO OPEN"),
                "the prompt should say what to do");
    }

    private static String service() {
        return read("PvpCompetitionService.java");
    }

    private static String read(String name) {
        try {
            return Files.readString(
                    Path.of("src/main/java/bot/mgx/accessbridge/" + name),
                    StandardCharsets.UTF_8);
        } catch (java.io.IOException error) {
            throw new java.io.UncheckedIOException(error);
        }
    }

    private static String source() {
        return read("PvpLobbyBuilder.java");
    }
}
