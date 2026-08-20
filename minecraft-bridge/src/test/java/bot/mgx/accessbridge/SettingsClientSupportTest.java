package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsClientSupportTest {
    @Test
    void bedrockAlwaysUsesTheInventoryFallback() {
        assertFalse(SettingsClientSupport.supportsDialogsFor(true, false, null, 774));
        assertFalse(SettingsClientSupport.supportsDialogsFor(true, true, 774, 774));
    }

    @Test
    void viaVersionsOriginalClientProtocolWins() {
        assertFalse(SettingsClientSupport.supportsDialogsFor(false, true, 770, 774));
        assertTrue(SettingsClientSupport.supportsDialogsFor(false, true, 771, 774));
    }

    @Test
    void anUnreadableViaProtocolFallsBackConservatively() {
        assertFalse(SettingsClientSupport.supportsDialogsFor(false, true, null, 774));
    }

    @Test
    void paperProtocolIsUsedWhenViaIsAbsent() {
        assertFalse(SettingsClientSupport.supportsDialogsFor(false, false, null, 770));
        assertTrue(SettingsClientSupport.supportsDialogsFor(false, false, null, 771));
    }
}
