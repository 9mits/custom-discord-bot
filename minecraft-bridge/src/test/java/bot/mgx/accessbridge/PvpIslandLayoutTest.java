package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The category islands and waiting halls: a plan that is all rounding, like the hub's,
 * and a flow that only works if every way out of a queue lands somewhere sensible.
 */
final class PvpIslandLayoutTest {
    @Test
    void everyPublicFormatHasItsOwnPortalOnItsCategoryIsland() {
        Set<String> formats = new HashSet<>();
        for (PvpIslandBuilder.Island island : PvpIslandBuilder.Island.values()) {
            for (PvpIslandBuilder.Station station : PvpIslandBuilder.stations(island)) {
                assertEquals(island, PvpIslandBuilder.Island.of(station.setup().mode()),
                        station.title() + " stands on the wrong island");
                assertEquals(PvpMatchSetup.Access.PUBLIC, station.setup().access());
                assertTrue(formats.add(station.setup().matchLabel()),
                        station.title() + " duplicates another portal's queue");
            }
        }
        assertTrue(formats.containsAll(List.of("Ranked 1v1", "Ranked 2v2", "Ranked 3v3",
                "2v2 Clan Battle", "3v3 Clan Battle")));
        assertEquals(3, PvpIslandBuilder.stations(PvpIslandBuilder.Island.LAST_STANDING).size());
    }

    @Test
    void noTwoPortalsShareASpotAndEveryArchStandsOnTheRing() {
        Set<String> seen = new HashSet<>();
        for (Map.Entry<String, int[]> row : PvpIslandBuilder.portalPositions().entrySet()) {
            assertTrue(seen.add(row.getValue()[0] + ":" + row.getValue()[1]),
                    row.getKey() + " shares a portal position");
        }
        for (PvpIslandBuilder.Island island : PvpIslandBuilder.Island.values()) {
            for (PvpIslandBuilder.Station station : PvpIslandBuilder.stations(island)) {
                double radius = Math.hypot(station.x() - island.x(), station.z() - island.z());
                assertTrue(Math.abs(radius - PvpIslandBuilder.gateRing()) <= 0.75d,
                        station.title() + " is off the arch ring");
                int rx = station.x() - island.x();
                int rz = station.z() - island.z();
                boolean wideX = Math.abs(rx) <= Math.abs(rz);
                for (int lateral : new int[]{-3, -2, 2, 3}) {
                    int depth = Math.abs(lateral) == 3 ? 1 : 2;
                    int[] decoration = PvpIslandBuilder.decoration(rx, rz, wideX, lateral, depth);
                    assertFalse(wideX ? decoration[1] == rz : decoration[0] == rx,
                            station.title() + " decoration lands on its portal plane");
                }
            }
        }
    }

    @Test
    void islandsAndHallsNeverOverlapTheHubOrEachOther() {
        List<int[]> centres = new java.util.ArrayList<>();
        for (PvpIslandBuilder.Island island : PvpIslandBuilder.Island.values()) {
            centres.add(new int[]{island.x(), island.z(), PvpIslandBuilder.islandRim()});
            centres.add(new int[]{island.hallX(), island.hallZ(), PvpIslandBuilder.hallWall()});
        }
        for (int[] centre : centres) {
            assertTrue(Math.hypot(centre[0], centre[1]) - centre[2]
                            > PvpLobbyBuilder.PROTECTED_RADIUS + 40d,
                    "a platform at " + centre[0] + "," + centre[1] + " crowds the hub");
            for (int[] other : centres) {
                if (other == centre) continue;
                assertTrue(Math.hypot(centre[0] - other[0], centre[1] - other[1])
                                > centre[2] + other[2] + 40d,
                        "two platforms are close enough to share a zone");
            }
        }
    }

    @Test
    void theSouthApproachToTheWayBackIsClear() {
        for (PvpIslandBuilder.Island island : PvpIslandBuilder.Island.values()) {
            for (int[] spot : PvpIslandBuilder.furniturePositions(island)) {
                assertFalse(Math.abs(spot[0]) <= 4 && spot[1] > 0 && spot[1] <= 16,
                        "furniture at " + spot[0] + "," + spot[1]
                                + " stands on the arrival pad or the way back");
            }
        }
    }

    @Test
    void queueingSendsPlayersToTheHallAndEveryExitIsHandled() {
        String service = read("PvpCompetitionService.java");
        String join = service.substring(service.indexOf("private void joinQueue("),
                service.indexOf("private boolean sendToHall("));
        assertTrue(join.contains("sendToHall(member, setup)"),
                "every queued member must be moved into the waiting hall");
        assertTrue(service.contains("releaseIdleHallPlayers(lobby);"),
                "a player who stops waiting must never be stranded in a hall");
        assertTrue(service.contains("PvpIslandBuilder.repairPortals(lobby)"));
        assertTrue(service.contains("case HALL_LEAVE ->"));
        assertTrue(service.contains("case ISLAND_RETURN ->"));
        String destination = service.substring(service.indexOf("private Location returnDestination("),
                service.indexOf("private PvpDuelStore.Recovery snapshot("));
        assertTrue(destination.contains("PvpIslandBuilder.islandSpawn(lobby, zone.island())"),
                "a fight queued from an island returns to that island");
        assertTrue(service.contains("case \"again\", \"requeue\" ->"),
                "Play Again repeats the exact setup");
        String lobby = read("PvpLobbyBuilder.java");
        assertTrue(lobby.contains("PvpIslandBuilder.buildAll(world, plugin.gameVariables());"),
                "the islands are generated in the same pass and format as the hub");
    }

    private static String read(String name) {
        try {
            return Files.readString(Path.of("src/main/java/bot/mgx/accessbridge/" + name),
                    StandardCharsets.UTF_8);
        } catch (java.io.IOException error) {
            throw new java.io.UncheckedIOException(error);
        }
    }
}
