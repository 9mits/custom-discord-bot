package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeleportMenuServiceTest {
    @Test
    void bareCommandsOpenMenusButNamedDestinationsStayWithEssentials() {
        TeleportMenuService.CommandRequest warp = TeleportMenuService.request("/warp");
        TeleportMenuService.CommandRequest home = TeleportMenuService.request("/essentials:home base");

        assertEquals("warp", warp.label());
        assertFalse(warp.hasArguments());
        assertEquals("home", home.label());
        assertTrue(home.hasArguments());
    }

    @Test
    void chatAndEmptyCommandsAreIgnored() {
        assertNull(TeleportMenuService.request("warp"));
        assertNull(TeleportMenuService.request("/"));
    }
}
