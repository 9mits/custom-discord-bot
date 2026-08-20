package bot.mgx.accessbridge;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BridgeConfigTest {
    @Test
    void validConfigurationLoadsDocumentedLeaderboardInterval() {
        YamlConfiguration config = validConfiguration();

        assertEquals(6_000, BridgeConfig.load(config).leaderboardRefreshTicks());
    }

    @Test
    void invalidLeaderboardIntervalIsReportedAsConfigurationError() {
        YamlConfiguration config = validConfiguration();
        config.set("leaderboard.refresh-ticks", 20);

        assertThrows(IllegalArgumentException.class, () -> BridgeConfig.load(config));
    }

    private static YamlConfiguration validConfiguration() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("server-id", "test-server");
        config.set("bridge-url", "wss://example.com/minecraft-bridge");
        config.set("bridge-secret", "00".repeat(32));
        config.set("leaderboard.refresh-ticks", 6_000);
        return config;
    }
}
