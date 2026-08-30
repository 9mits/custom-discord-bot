package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AdminEventCommandTest {
    @Test
    void oneEventFamilyExposesEveryWorldEventControl() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/bot/mgx/accessbridge/AdminCommandService.java"));

        assertTrue(source.contains("/mgxadmin event multiplier <type> <on|off> [seconds]"));
        assertTrue(source.contains("/mgxadmin event airdrop <start [rarity]|status|end|expire>"));
        assertTrue(source.contains("/mgxadmin event airdrop distance <rarity> <minimum> <maximum>"));
        assertTrue(source.contains("/mgxadmin event amethyst-block <start|status|damage [hp]|finish|end|expire>"));
        assertTrue(source.contains("/mgxadmin event schedule <status|set <minimum> <maximum>|reset>"));

        String rootSuggestions = source.substring(
                source.indexOf("private static final List<String> SUBCOMMANDS"),
                source.indexOf("private static final List<String> CRATE_REVEAL_TIERS")
        );
        assertTrue(rootSuggestions.contains("\"event\""));
        assertFalse(rootSuggestions.contains("\"airdrop\""));
        assertFalse(rootSuggestions.contains("\"abuse\""));
    }
}
