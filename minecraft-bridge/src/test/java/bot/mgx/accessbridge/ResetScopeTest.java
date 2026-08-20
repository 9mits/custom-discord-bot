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

    @Test
    void allCoversEveryTraceIncludingTheOnesAddedLater() {
        // "all" is what an operator reaches for to start a season over. A scope added
        // later that is not swept in by it would leave exactly the kind of leftover
        // this command exists to remove.
        Set<ResetScope> all = ResetScope.parse(List.of("all"));

        assertTrue(all.contains(ResetScope.IDENTITIES));
        assertTrue(all.contains(ResetScope.SETTINGS));
        assertTrue(all.contains(ResetScope.CRATES));
        assertEquals(ResetScope.CRATES, ResetScope.fromKey("lootboxes").orElseThrow());
        assertTrue(all.contains(ResetScope.COSMETICS));
        assertTrue(all.contains(ResetScope.TROPHIES));
        assertTrue(all.contains(ResetScope.RANKS));
        assertTrue(all.contains(ResetScope.ACCESS));
        assertTrue(all.contains(ResetScope.USERCACHE));
        assertEquals(ResetScope.values().length, all.size());
    }

    @Test
    void onlyClearingAccessLocksPlayersOut() {
        // Surfaced separately in the confirmation, so it is worth pinning down which
        // scope carries the consequence.
        assertTrue(ResetScope.ACCESS.revokesAccess());
        for (ResetScope scope : ResetScope.values()) {
            if (scope != ResetScope.ACCESS) {
                assertEquals(false, scope.revokesAccess(), scope.key() + " should not revoke access");
            }
        }
    }

    @Test
    void everyScopeHasADistinctKeyAndADescription() {
        Set<String> keys = new java.util.HashSet<>(ResetScope.keys());

        assertEquals(ResetScope.values().length, keys.size());
        for (ResetScope scope : ResetScope.values()) {
            assertTrue(!scope.description().isBlank(), scope.key() + " needs a description");
            // "all" is a parse-time alias; a scope named that would be unreachable.
            assertEquals(false, scope.key().equals("all"));
        }
    }
}
