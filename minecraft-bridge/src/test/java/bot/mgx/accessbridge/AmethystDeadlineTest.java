package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One clock ends the whole Amethyst expansion.
 *
 * <p>The limited crate, the Dragon, the Airdrop and Amethyst Block rotation, the Dragon
 * leaderboards and the Dragon Egg clan battle all read the same variable. A system that
 * quietly kept running past it would keep paying event currency into an economy the
 * event had already left.
 */
class AmethystDeadlineTest {
    private static final long ENDS_AT = 1_789_797_600L;

    private static String source(String name) throws Exception {
        return Files.readString(Path.of("src/main/java/bot/mgx/accessbridge/" + name));
    }

    @Test
    void theDeadlineIsSaturdayThreePmJst() {
        ZonedDateTime utc = Instant.ofEpochSecond(ENDS_AT).atZone(ZoneOffset.UTC);
        assertEquals(2026, utc.getYear());
        assertEquals(9, utc.getMonthValue());
        assertEquals(19, utc.getDayOfMonth());
        assertEquals(6, utc.getHour());
        assertEquals(0, utc.getMinute());
        // 06:00 UTC is 15:00 in JST, which is where the deadline was actually chosen.
        assertEquals(15, Instant.ofEpochSecond(ENDS_AT)
                .atZone(ZoneOffset.ofHours(9)).getHour());
    }

    @Test
    void theShippedDefaultIsThatDeadline() throws Exception {
        String store = source("GameVariableStore.java");
        int at = store.indexOf("\"amethyst-events.ends-at\"");
        assertTrue(at > 0, "the shared deadline variable must exist");
        assertTrue(
                store.substring(at, at + 600).contains("1_789_797_600L"),
                "the shipped deadline must be 2026-09-19 06:00 UTC"
        );
    }

    @Test
    void everyAmethystSystemReadsTheOneDeadline() throws Exception {
        // The crate, the Dragon, the leaderboards and the clan battle already did.
        assertTrue(source("MGXAccessBridge.java").contains("CrateKind.eventEndSource("),
                "the limited crate must close on the shared deadline");
        assertTrue(source("AmethystDragonService.java").contains("amethyst-events.ends-at"),
                "the Dragon must stop scheduling at the shared deadline");
        assertTrue(source("LeaderboardService.java").contains("amethyst-events.ends-at"),
                "the Dragon leaderboards must settle on the shared deadline");
        assertTrue(source("MGXAccessBridge.java").contains("ensureDragonEggBattle("),
                "the clan battle must be built against the shared deadline");
        // This one did not, and is what this change adds.
        String coordinator = source("AmethystEventCoordinator.java");
        assertTrue(coordinator.contains("amethyst-events.ends-at"),
                "the Airdrop and Amethyst Block rotation must stop at the deadline too");
        assertTrue(coordinator.contains("eventOver(System.currentTimeMillis())"),
                "the rotation must check the deadline before starting an event");
    }

    @Test
    void theRotationRetriesRatherThanCancellingSoTheDeadlineCanMoveOut() throws Exception {
        String coordinator = source("AmethystEventCoordinator.java");
        int gate = coordinator.indexOf("if (eventOver(System.currentTimeMillis()))");
        assertTrue(gate > 0, "the deadline gate must exist");
        assertTrue(
                coordinator.substring(gate, gate + 200).contains("schedule(RETRY_MILLIS"),
                "an extended deadline must restart the rotation without a restart"
        );
    }

    @Test
    void theSidebarCarriesTheLiveCountdown() throws Exception {
        String sidebar = source("SidebarService.java");
        assertTrue(sidebar.contains("AMETHYST EVENT"), "the board must name the event");
        assertTrue(
                sidebar.contains("CrateKind.AMETHYST.remaining(now)"),
                "the board refreshes every five seconds, so it shows the coarse countdown"
        );
        assertTrue(
                sidebar.contains("CrateKind.AMETHYST.available(now)"),
                "the countdown must disappear once the event is over"
        );
    }
}
