package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WhitelistDirectoryTest {
    @Test
    void matchesTheDiscordApprovedMinecraftUuid() {
        UUID approved = UUID.randomUUID();
        WhitelistDirectory directory = directory(new WhitelistDirectory.Entry(
                "ApprovedPlayer", "JAVA", approved, "discord_user"
        ));

        assertTrue(directory.contains(approved, "RenamedPlayer", MinecraftEdition.JAVA));
        assertFalse(directory.contains(UUID.randomUUID(), "Stranger", MinecraftEdition.JAVA));
        assertFalse(directory.contains(UUID.randomUUID(), "ApprovedPlayer", MinecraftEdition.JAVA));
    }

    @Test
    void usernameFallbackIsEditionAwareAndNormalizesBedrockNames() {
        WhitelistDirectory directory = directory(new WhitelistDirectory.Entry(
                "Bedrock Player", "BEDROCK", null, "discord_user"
        ));

        assertTrue(directory.contains(null, ".Bedrock_Player", MinecraftEdition.BEDROCK));
        assertFalse(directory.contains(null, "Bedrock_Player", MinecraftEdition.JAVA));
    }

    private static WhitelistDirectory directory(WhitelistDirectory.Entry entry) {
        WhitelistDirectory directory = new WhitelistDirectory();
        directory.replace(List.of(entry));
        return directory;
    }
}
