package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChaosCatalogTest {
    @Test
    void everyIdAndAliasIsUnique() {
        Set<String> seen = new HashSet<>();
        for (ChaosCatalog effect : ChaosCatalog.values()) {
            assertTrue(seen.add(effect.id()), "duplicate id " + effect.id());
            for (String alias : effect.aliases()) {
                assertTrue(seen.add(alias), "duplicate alias " + alias);
            }
        }
    }

    @Test
    void resolvesByIdAliasAndCase() {
        assertEquals(ChaosCatalog.LAVAFLOOR, ChaosCatalog.resolve("lavafloor").orElseThrow());
        assertEquals(ChaosCatalog.LAVAFLOOR, ChaosCatalog.resolve("LAVA").orElseThrow());
        assertEquals(ChaosCatalog.STOP, ChaosCatalog.resolve("  Reset ").orElseThrow());
        assertTrue(ChaosCatalog.resolve("definitely-not-an-effect").isEmpty());
        assertTrue(ChaosCatalog.resolve(null).isEmpty());
    }

    @Test
    void omittedDurationFallsBackToTheDefault() {
        assertEquals(ChaosCatalog.DISCO.defaultSeconds(), ChaosCatalog.DISCO.secondsOrThrow(null));
        assertEquals(ChaosCatalog.DISCO.defaultSeconds(), ChaosCatalog.DISCO.secondsOrThrow("  "));
    }

    @Test
    void durationIsClampedToTheEffectsRail() {
        assertEquals(30, ChaosCatalog.DISCO.secondsOrThrow("30"));
        assertThrows(IllegalArgumentException.class, () -> ChaosCatalog.DISCO.secondsOrThrow("1"));
        assertThrows(IllegalArgumentException.class, () -> ChaosCatalog.DISCO.secondsOrThrow("99999"));
        assertThrows(IllegalArgumentException.class, () -> ChaosCatalog.DISCO.secondsOrThrow("soon"));
    }

    @Test
    void oneShotEffectsRefuseADuration() {
        assertFalse(ChaosCatalog.CONFETTI.timed());
        assertThrows(IllegalArgumentException.class, () -> ChaosCatalog.CONFETTI.secondsOrThrow("10"));
        assertEquals(0, ChaosCatalog.CONFETTI.secondsOrThrow(null));
    }

    @Test
    void everyTimedEffectHasASaneRail() {
        for (ChaosCatalog effect : ChaosCatalog.values()) {
            if (!effect.timed()) {
                continue;
            }
            // The default must itself be a legal request, or the bare command fails.
            assertEquals(effect.defaultSeconds(),
                    effect.secondsOrThrow(String.valueOf(effect.defaultSeconds())),
                    effect.id() + " default is outside its own bounds");
        }
    }

    @Test
    void payoutEventsAreNeverInTheChaosPool() {
        // Chaos fires five effects at once. Rolling a jackpot into that would
        // hand out four payouts nobody asked for.
        for (ChaosCatalog payout : ChaosCatalog.payouts()) {
            assertFalse(ChaosCatalog.chaosPool().contains(payout),
                    payout.id() + " would pay out inside chaos");
        }
    }

    @Test
    void theNewLiveEventsAreReachableAndNamed() {
        assertEquals(ChaosCatalog.AIRDROP, ChaosCatalog.resolve("supply").orElseThrow());
        assertEquals(ChaosCatalog.PINATA, ChaosCatalog.resolve("boss").orElseThrow());
        assertEquals(ChaosCatalog.JACKPOT, ChaosCatalog.resolve("roll").orElseThrow());
        assertTrue(ChaosCatalog.PINATA.timed());
        assertFalse(ChaosCatalog.AIRDROP.timed());
        assertFalse(ChaosCatalog.JACKPOT.timed());
    }

    @Test
    void theChaosPoolNeverRecursesOrTurnsItselfOff() {
        assertFalse(ChaosCatalog.chaosPool().contains(ChaosCatalog.CHAOS));
        assertFalse(ChaosCatalog.chaosPool().contains(ChaosCatalog.STOP));
        assertFalse(ChaosCatalog.chaosPool().isEmpty());
    }

    @Test
    void everyEffectIsReachableFromTheMenu() {
        assertEquals(ChaosCatalog.values().length, ChaosCatalog.menu().size());
        for (ChaosCatalog effect : ChaosCatalog.menu()) {
            assertFalse(effect.blurb().isBlank(), effect.id() + " has no description");
        }
    }
}
