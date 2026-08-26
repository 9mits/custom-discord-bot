package bot.mgx.accessbridge;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BridgeConfigTest {
    @Test
    void validConfigurationLoadsDocumentedLeaderboardInterval() {
        YamlConfiguration config = validConfiguration();

        assertEquals(6_000, BridgeConfig.load(config).leaderboardRefreshTicks());
    }

    @Test
    void verificationIsRequiredByDefault() {
        assertTrue(BridgeConfig.load(validConfiguration()).verificationRequired());
    }

    @Test
    void localConfigurationCanExplicitlyDisableVerification() {
        YamlConfiguration config = validConfiguration();
        config.set("bridge-url", "ws://127.0.0.1:8765/minecraft-bridge");
        config.set("allow-insecure-localhost", true);
        config.set("verification-required", false);

        assertFalse(BridgeConfig.load(config).verificationRequired());
    }

    @Test
    void productionConfigurationCannotDisableVerification() {
        YamlConfiguration config = validConfiguration();
        config.set("verification-required", false);

        assertThrows(IllegalArgumentException.class, () -> BridgeConfig.load(config));
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
