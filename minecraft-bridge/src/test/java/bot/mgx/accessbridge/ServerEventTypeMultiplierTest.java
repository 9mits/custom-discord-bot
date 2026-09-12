package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An event advertised as 2x must pay 2x. The factor moved from a shared constant to a
 * per-type field when 4x Keys arrived, so the name and the number are now checked against
 * each other rather than being true by construction.
 */
final class ServerEventTypeMultiplierTest {
    @Test
    void everyEventPaysWhatItsNameSays() {
        for (ServerEventType type : ServerEventType.values()) {
            String name = type.displayName().toLowerCase(Locale.ROOT);
            assertTrue(name.startsWith(type.multiplier() + "x"),
                    type + " is named \"" + type.displayName() + "\" but pays "
                            + type.multiplier() + "x");
            assertTrue(type.motdLabel().toUpperCase(Locale.ROOT)
                            .startsWith(type.multiplier() + "X"),
                    type + " server-list label disagrees with its factor");
        }
    }

    @Test
    void theNewEventsExistWithTheRequestedFactors() {
        assertEquals(2, ServerEventType.AIRDROP.multiplier());
        assertEquals(2, ServerEventType.AMETHYST_BLOCK.multiplier());
        assertEquals(4, ServerEventType.MEGA_KEY.multiplier());
        assertEquals(5, ServerEventType.AMETHYST_DRAGON.multiplier());
        assertEquals(ServerEventType.MEGA_KEY, ServerEventType.resolve("megakey").orElseThrow());
        assertEquals(ServerEventType.AIRDROP, ServerEventType.resolve("drops").orElseThrow());
        assertEquals(ServerEventType.AMETHYST_BLOCK,
                ServerEventType.resolve("amethyst").orElseThrow());
        assertEquals(ServerEventType.AMETHYST_DRAGON,
                ServerEventType.resolve("dragon").orElseThrow());
        assertEquals(ServerEventType.AMETHYST_DRAGON,
                ServerEventType.resolve(" AmethystDragon ").orElseThrow());
    }

    /** The Dragon event is announced exactly like the rest of the multiplier family. */
    @Test
    void theDragonEventIsAnnouncedLikeEveryOtherMultiplier() {
        assertEquals("5x Amethyst Dragon", ServerEventType.AMETHYST_DRAGON.displayName());
        assertEquals("5X AMETHYST DRAGON EVENT!", ServerEventType.AMETHYST_DRAGON.motdLabel());
        assertEquals("Amethyst Dragon", ServerEventType.AMETHYST_DRAGON.baseDisplayName());
    }

    /** The factor is editable, so every rendered name has to follow it. */
    @Test
    void anEditedFactorRenamesTheEventEverywhere() {
        assertEquals("8x Amethyst Dragon", ServerEventType.AMETHYST_DRAGON.displayName(8));
        assertEquals("8X AMETHYST DRAGON EVENT!", ServerEventType.AMETHYST_DRAGON.motdLabel(8));
        assertEquals(
                "8x Amethyst Dragon - 3x Money",
                ServerEventService.stackedTitle(
                        List.of(ServerEventType.AMETHYST_DRAGON, ServerEventType.MONEY),
                        type -> type == ServerEventType.AMETHYST_DRAGON ? 8 : 3
                )
        );
    }

    /** 2x and 4x keys must not compound into 8x behind a bar that promises 4x. */
    @Test
    void theLargestKeyEventWinsRatherThanStacking() {
        assertEquals(1, ServerEventType.keyMultiplier(1, 1));
        assertEquals(2, ServerEventType.keyMultiplier(2, 1));
        assertEquals(4, ServerEventType.keyMultiplier(1, 4));
        assertEquals(4, ServerEventType.keyMultiplier(2, 4));
    }

    @Test
    void everyEventIdAndAliasIsUnique() {
        long ids = java.util.Arrays.stream(ServerEventType.values())
                .flatMap(type -> java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(type.id()), type.aliases().stream()))
                .distinct().count();
        long total = java.util.Arrays.stream(ServerEventType.values())
                .mapToLong(type -> 1L + type.aliases().size()).sum();
        assertEquals(total, ids, "an id or alias is claimed by two events");
    }

    @Test
    void stackedEventsUseOneCompactBossBarLine() {
        assertEquals(
                "4x Keys - 2x Airdrops - 2x Money",
                ServerEventService.stackedTitle(List.of(
                        ServerEventType.MEGA_KEY,
                        ServerEventType.AIRDROP,
                        ServerEventType.MONEY
                ))
        );
    }
}
