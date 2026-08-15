package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LuckPermsExplicitPermissionTest {
    @Test
    void anExplicitGrantCounts() {
        assertTrue(LuckPermsService.grants("mgxaccessbridge.admin", true, "mgxaccessbridge.admin"));
    }

    @Test
    void aGroupMembershipAloneDoesNotCount() {
        assertFalse(LuckPermsService.grants("group.admin", true, "mgxaccessbridge.admin"));
    }

    @Test
    void aNegatedNodeDoesNotGrant() {
        assertFalse(LuckPermsService.grants("mgxaccessbridge.admin", false, "mgxaccessbridge.admin"));
    }

    @Test
    void theBukkitDefaultIsNotANode() {
        assertFalse(LuckPermsService.grants("", false, "mgxaccessbridge.admin"));
    }
}
