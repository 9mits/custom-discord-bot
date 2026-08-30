package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BroadcastDisplayServiceTest {
    @Test
    void everyGlobalAliasBuildsAGlobalBanner() {
        for (String alias : List.of(
                "broadcast", "bc", "ebc", "bcast", "ebcast", "ebroadcast", "shout", "eshout"
        )) {
            BroadcastDisplayService.Parsed parsed = BroadcastDisplayService.parse("/" + alias + " Server event");

            assertFalse(parsed.worldOnly(), alias);
            assertEquals("Server event", parsed.message(), alias);
            assertEquals("essentials.broadcast", parsed.permission(), alias);
        }
    }

    @Test
    void everyWorldAliasTargetsTheNamedWorld() {
        for (String alias : List.of(
                "broadcastworld", "bcw", "ebcw", "bcastw", "ebcastw", "ebroadcastworld",
                "shoutworld", "eshoutworld"
        )) {
            BroadcastDisplayService.Parsed parsed = BroadcastDisplayService.parse(
                    "/" + alias + " world_nether Nether event"
            );

            assertTrue(parsed.worldOnly(), alias);
            assertEquals("world_nether", parsed.worldName(), alias);
            assertEquals("Nether event", parsed.message(), alias);
            assertEquals("essentials.broadcastworld", parsed.permission(), alias);
        }
    }

    @Test
    void namespacedWorldCommandIsRecognised() {
        BroadcastDisplayService.Parsed parsed = BroadcastDisplayService.parse(
                "/essentials:broadcastworld world Event starting now"
        );

        assertTrue(parsed.worldOnly());
        assertEquals("world", parsed.worldName());
        assertEquals("Event starting now", parsed.message());
    }

    @Test
    void missingWorldMessageCanBeReportedByTheCommandHandler() {
        BroadcastDisplayService.Parsed parsed = BroadcastDisplayService.parse("broadcastworld world");

        assertTrue(parsed.worldOnly());
        assertEquals("world", parsed.worldName());
        assertEquals("", parsed.message());
    }

    @Test
    void unrelatedCommandIsIgnored() {
        assertNull(BroadcastDisplayService.parse("/msg player hello"));
    }

    @Test
    void discordBroadcastUsesTheDisplayServiceInsteadOfEssentialsDispatch() throws Exception {
        String plugin = Files.readString(Path.of(
                "src/main/java/bot/mgx/accessbridge/MGXAccessBridge.java"
        ));

        assertTrue(plugin.contains("toolKey.equals(\"broadcast\")"));
        assertTrue(plugin.contains("broadcastDisplayService.showGlobal"));
    }
}
