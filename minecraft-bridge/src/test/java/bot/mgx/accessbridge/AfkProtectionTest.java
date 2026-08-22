package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AfkProtectionTest {
    @Test
    void blocksEnvironmentalDamageWhileAfk() {
        assertEquals(
                AfkProtection.Decision.BLOCK,
                AfkProtection.decide(true, true, false, false)
        );
    }

    @Test
    void ignoresDamageWhenNotAfk() {
        assertEquals(
                AfkProtection.Decision.IGNORE,
                AfkProtection.decide(true, false, false, false)
        );
    }

    @Test
    void ignoresDamageWhenDisabled() {
        assertEquals(
                AfkProtection.Decision.IGNORE,
                AfkProtection.decide(false, true, false, false)
        );
    }

    @Test
    void aPlayerHitWakesRatherThanBounces() {
        assertEquals(
                AfkProtection.Decision.WAKE,
                AfkProtection.decide(true, true, true, false)
        );
    }

    @Test
    void aPlayerHitInTheVoidStillWakes() {
        assertEquals(
                AfkProtection.Decision.WAKE,
                AfkProtection.decide(true, true, true, true)
        );
    }

    @Test
    void theVoidStillKillsAnAfkPlayer() {
        assertEquals(
                AfkProtection.Decision.IGNORE,
                AfkProtection.decide(true, true, false, true)
        );
    }
}
