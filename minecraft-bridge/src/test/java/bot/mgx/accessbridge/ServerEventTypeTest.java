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
    void everyTypeAdvertisesItselfAsTwoTimes() {
        // The boss bar, the banner and the server list all say 2x. If the
        // constant ever moved, the advertising would start lying.
        assertEquals(2, ServerEventType.MULTIPLIER);
        for (ServerEventType type : ServerEventType.values()) {
            assertTrue(type.displayName().startsWith("2x"), type.id() + " is not named 2x");
            assertTrue(type.motdLabel().contains("2X"), type.id() + " MOTD label is not 2X");
            assertFalse(type.motdLabel().isBlank());
        }
    }
}
