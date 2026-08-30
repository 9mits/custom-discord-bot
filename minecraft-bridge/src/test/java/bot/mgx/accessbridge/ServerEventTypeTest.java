package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerEventTypeTest {
    @Test
    void everyIdAndAliasIsUnique() {
        Set<String> seen = new HashSet<>();
        for (ServerEventType type : ServerEventType.values()) {
            assertTrue(seen.add(type.id()), "duplicate id " + type.id());
            for (String alias : type.aliases()) {
                assertTrue(seen.add(alias), "duplicate alias " + alias);
            }
        }
    }

    @Test
    void resolvesByIdAliasAndCase() {
        assertEquals(ServerEventType.MONEY, ServerEventType.resolve("money").orElseThrow());
        assertEquals(ServerEventType.MONEY, ServerEventType.resolve("  CASH ").orElseThrow());
        assertEquals(ServerEventType.CRATE_LUCK, ServerEventType.resolve("luck").orElseThrow());
        assertTrue(ServerEventType.resolve("nonsense").isEmpty());
        assertTrue(ServerEventType.resolve(null).isEmpty());
    }

    @Test
    void anOmittedDurationMeansUntilTurnedOff() {
        assertEquals(0L, ServerEventType.secondsOrThrow(null));
        assertEquals(0L, ServerEventType.secondsOrThrow("   "));
    }

    @Test
    void durationIsHeldToTheRail() {
        assertEquals(3_600L, ServerEventType.secondsOrThrow("3600"));
        assertThrows(IllegalArgumentException.class, () -> ServerEventType.secondsOrThrow("30"));
        assertThrows(IllegalArgumentException.class,
                () -> ServerEventType.secondsOrThrow("99999999"));
        assertThrows(IllegalArgumentException.class, () -> ServerEventType.secondsOrThrow("soon"));
    }

    @Test
    void everyTypeAdvertisesTheFactorItActuallyPays() {
        // The boss bar, the banner and the server list all name the factor. These used to
        // be uniformly 2x against a shared constant; 4x Keys made the factor per-type, so
        // the name is now checked against the number instead of being true by
        // construction. ServerEventTypeMultiplierTest covers the same rule in full.
        for (ServerEventType type : ServerEventType.values()) {
            assertTrue(type.displayName().startsWith(type.multiplier() + "x"),
                    type.id() + " is not named " + type.multiplier() + "x");
            assertTrue(type.motdLabel().contains(type.multiplier() + "X"),
                    type.id() + " MOTD label is not " + type.multiplier() + "X");
            assertFalse(type.motdLabel().isBlank());
        }
    }
}
