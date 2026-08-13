package bot.mgx.accessbridge;

import org.bukkit.configuration.file.FileConfiguration;

import java.net.URI;
import java.util.HexFormat;
import java.util.Set;

record BridgeConfig(
        String serverId,
        URI bridgeUri,
        byte[] secret,
        byte[] certificateSha256,
        boolean allowInsecureLocalhost,
        int verificationExpirySeconds,
        int reconnectMaxSeconds,
        String scoreboardFooter,
        int scoreboardUpdateTicks,
        int leaderboardRefreshTicks,
        boolean debug
) {
    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "::1", "[::1]");

    static BridgeConfig load(FileConfiguration config) {
        String serverId = config.getString("server-id", "").trim();
        String bridgeUrl = config.getString("bridge-url", "").trim();
        String secretText = config.getString("bridge-secret", "").trim();
        String certificatePinText = config.getString("bridge-certificate-sha256", "").trim();
        boolean allowInsecure = config.getBoolean("allow-insecure-localhost", false);
        int expiry = config.getInt("verification-expiry-seconds", 600);
        int reconnectMax = config.getInt("reconnect-max-seconds", 60);
        String scoreboardFooter = config.getString("scoreboard.footer", "discord.gg/mgx").trim();
        int scoreboardUpdateTicks = config.getInt("scoreboard.update-ticks", 20);
        // Minecraft statistics move slowly and each pass reads every player's file,
        // so five minutes keeps the board live without hammering disk or Discord.
        int leaderboardRefreshTicks = config.getInt("leaderboard.refresh-ticks", 6_000);
        boolean debug = config.getBoolean("debug", false);

        if (serverId.isEmpty() || serverId.length() > 64) {
            throw new IllegalArgumentException("server-id must contain 1-64 characters");
        }
        URI uri;
        try {
            uri = URI.create(bridgeUrl);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("bridge-url is not a valid URI", exception);
        }
        String host = uri.getHost();
        boolean secure = "wss".equalsIgnoreCase(uri.getScheme()) && host != null;
        boolean localDevelopment = allowInsecure
                && "ws".equalsIgnoreCase(uri.getScheme())
                && host != null
                && LOOPBACK_HOSTS.contains(host.toLowerCase());
        if (!secure && !localDevelopment) {
            throw new IllegalArgumentException(
                    "bridge-url must use wss://; ws:// is allowed only for explicit localhost development"
            );
        }
        byte[] secret;
        try {
            secret = HexFormat.of().parseHex(secretText);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("bridge-secret must be 64 hexadecimal characters", exception);
        }
        if (secret.length != 32) {
            throw new IllegalArgumentException("bridge-secret must decode to exactly 32 bytes");
        }
        byte[] certificateSha256 = BridgeTls.decodeSha256Fingerprint(certificatePinText);
        if (expiry < 60 || expiry > 3600) {
            throw new IllegalArgumentException("verification-expiry-seconds must be between 60 and 3600");
        }
        if (reconnectMax < 5 || reconnectMax > 300) {
            throw new IllegalArgumentException("reconnect-max-seconds must be between 5 and 300");
        }
        if (scoreboardFooter.isEmpty() || scoreboardFooter.length() > 32) {
            throw new IllegalArgumentException("scoreboard.footer must contain 1-32 characters");
        }
        if (leaderboardRefreshTicks < 1_200 || leaderboardRefreshTicks > 72_000) {
            throw new IllegalStateException(
                    "leaderboard.refresh-ticks must be between 1200 (1m) and 72000 (1h)"
            );
        }
        if (scoreboardUpdateTicks < 10 || scoreboardUpdateTicks > 200) {
            throw new IllegalArgumentException("scoreboard.update-ticks must be between 10 and 200");
        }
        return new BridgeConfig(
                serverId,
                uri,
                secret,
                certificateSha256,
                allowInsecure,
                expiry,
                reconnectMax,
                scoreboardFooter,
                scoreboardUpdateTicks,
                leaderboardRefreshTicks,
                debug
        );
    }
}
