package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResetScopeTest {
    @Test
    void allExpandsToEveryScope() {
        assertEquals(Set.of(ResetScope.values()), ResetScope.parse(List.of("all")));
    }

    @Test
    void namedScopesAreReadIndividually() {
        assertEquals(
                Set.of(ResetScope.STATS, ResetScope.ADVANCEMENTS),
                ResetScope.parse(List.of("stats", "advancements"))
        );
    }

    @Test
    void caseAndSurroundingSpaceAreIgnored() {
        assertEquals(Set.of(ResetScope.CLANS), ResetScope.parse(List.of("  ClAnS  ")));
    }

    @Test
    void nothingIsSelectedWithoutArguments() {
        assertTrue(ResetScope.parse(List.of()).isEmpty());
    }

    @Test
    void aTypoIsRejectedRatherThanSkipped() {
        // Silently ignoring an unreadable word on a destructive command would wipe
        // something other than what the operator asked for.
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> ResetScope.parse(List.of("stats", "acheivements"))
        );

        assertTrue(failure.getMessage().contains("acheivements"));
        assertTrue(failure.getMessage().contains("advancements"));
    }

    @Test
    void onlyPerPlayerScopesTouchPlayerFiles() {
        assertTrue(ResetScope.STATS.isPlayerData());
        assertTrue(ResetScope.ADVANCEMENTS.isPlayerData());
        assertTrue(ResetScope.INVENTORIES.isPlayerData());
        assertEquals(false, ResetScope.CLANS.isPlayerData());
        assertEquals(false, ResetScope.WEALTH.isPlayerData());
    }
}
