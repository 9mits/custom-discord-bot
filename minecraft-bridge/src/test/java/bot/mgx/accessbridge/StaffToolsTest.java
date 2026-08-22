package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaffToolsTest {
    private StaffTools.StaffTool tool(String key) {
        return StaffTools.find(key).orElseThrow();
    }

    @Test
    void everyKeyIsUnique() {
        long distinctKeys = StaffTools.ALL.stream().map(StaffTools.StaffTool::key).distinct().count();

        assertEquals(StaffTools.ALL.size(), distinctKeys);
    }

    @Test
    void investigativeToolsHaveNoRemoteCommand() {
        for (String key : new String[]{"inspect", "lookup", "rollback", "restore", "god", "invsee", "vanish", "alerts"}) {
            assertTrue(tool(key).remote().isEmpty(), key + " should not be remotely dispatchable");
        }
    }

    @Test
    void kickBuildsWithAnOptionalReason() throws Exception {
        StaffTools.RemoteCommand kick = tool("kick").remote().orElseThrow();

        assertEquals("kick mits", kick.build("mits", "", ""));
        assertEquals("kick mits Spamming", kick.build("mits", "Spamming", ""));
    }

    @Test
    void muteIncludesDurationOnlyWhenGiven() throws Exception {
        StaffTools.RemoteCommand mute = tool("mute").remote().orElseThrow();

        assertEquals("mute mits", mute.build("mits", "", ""));
        assertEquals("mute mits 10m", mute.build("mits", "", "10m"));
        assertEquals("mute mits 10m Spamming", mute.build("mits", "Spamming", "10m"));
    }

    @Test
    void tempbanRequiresADuration() {
        StaffTools.RemoteCommand tempban = tool("tempban").remote().orElseThrow();

        assertThrows(IllegalArgumentException.class, () -> tempban.build("mits", "", ""));
    }

    @Test
    void tempbanBuildsOnceADurationIsGiven() throws Exception {
        StaffTools.RemoteCommand tempban = tool("tempban").remote().orElseThrow();

        assertEquals("tempban mits 1d", tempban.build("mits", "", "1d"));
    }

    @Test
    void broadcastUsesTheReasonAsTheMessageAndIgnoresTarget() throws Exception {
        StaffTools.RemoteCommand broadcast = tool("broadcast").remote().orElseThrow();

        assertEquals("broadcast Event starting soon", broadcast.build("unused", "Event starting soon", ""));
    }

    @Test
    void broadcastRequiresAMessage() {
        StaffTools.RemoteCommand broadcast = tool("broadcast").remote().orElseThrow();

        assertThrows(IllegalArgumentException.class, () -> broadcast.build("unused", "", ""));
    }

    @Test
    void broadcastAndUpdateSkipTargetValidation() {
        for (StaffTools.StaffTool tool : StaffTools.ALL) {
            if (tool.remote().isEmpty()) {
                continue;
            }
            boolean untargeted = tool.key().equals("broadcast") || tool.key().equals("update");
            assertEquals(!untargeted, tool.needsTarget(), tool.key() + " target requirement");
        }
    }

    @Test
    void confirmationsReadAsSentencesNotCommands() {
        assertEquals("mits has been kicked from the server.",
                StaffTools.confirmation("kick", "mits", "Spamming", ""));
        assertEquals("mits has been muted until further notice.",
                StaffTools.confirmation("mute", "mits", "", ""));
        assertEquals("mits has been muted for 10m.",
                StaffTools.confirmation("mute", "mits", "", "10m"));
        assertEquals("mits has been banned for 1d.",
                StaffTools.confirmation("tempban", "mits", "", "1d"));
        assertEquals("The ban on mits has been lifted.",
                StaffTools.confirmation("unban", "mits", "", ""));
        assertEquals("Announced to every online player: \"Event starting soon\"",
                StaffTools.confirmation("broadcast", "", "Event starting soon", ""));
        assertEquals("The NEW UPDATE notice is live. Players see it on their next join.",
                StaffTools.confirmation("update", "", "", ""));
        assertEquals("The action was completed on the server.",
                StaffTools.confirmation("something-new", "mits", "", ""));
    }

    @Test
    void unknownKeysAreNotFound() {
        assertFalse(StaffTools.find("nonsense").isPresent());
    }
}
