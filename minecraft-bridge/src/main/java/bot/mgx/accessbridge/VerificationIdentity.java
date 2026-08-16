package bot.mgx.accessbridge;

import java.util.UUID;

/**
 * Who is connecting, for account verification.
 *
 * <p>Floodgate's player object is often missing at {@code AsyncPlayerPreLoginEvent},
 * which is the event a held server actually consults. Guessing Java then uses the
 * dotted login name and misses the pending Bedrock row. The UUID prefix and a
 * leading {@code .} are enough to classify Bedrock without that object.
 *
 * <p>Free of Bukkit and Floodgate so it can be unit tested.
 */
final class VerificationIdentity {
    static final String FLOODGATE_UUID_PREFIX = "00000000-0000-0000-0009-";

    private VerificationIdentity() {
    }

    record Resolved(MinecraftEdition edition, String username, String xuid) {
    }

    static boolean isFloodgateUuid(UUID uuid) {
        return uuid != null && uuid.toString().regionMatches(
                true, 0, FLOODGATE_UUID_PREFIX, 0, FLOODGATE_UUID_PREFIX.length()
        );
    }

    static String bedrockUsername(String loginName) {
        String name = loginName == null ? "" : loginName.strip();
        while (name.startsWith(".")) {
            name = name.substring(1).strip();
        }
        return name;
    }

    static String xuidFromFloodgateUuid(UUID uuid) {
        if (!isFloodgateUuid(uuid)) {
            return null;
        }
        String hex = uuid.toString().substring(FLOODGATE_UUID_PREFIX.length()).replace("-", "");
        try {
            return Long.toUnsignedString(Long.parseUnsignedLong(hex, 16));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    static Resolved resolve(UUID uuid, String loginName) {
        if (isFloodgateUuid(uuid) || (loginName != null && loginName.startsWith("."))) {
            return new Resolved(
                    MinecraftEdition.BEDROCK,
                    bedrockUsername(loginName),
                    xuidFromFloodgateUuid(uuid)
            );
        }
        return new Resolved(MinecraftEdition.JAVA, loginName == null ? "" : loginName, null);
    }

    static Resolved overlayFloodgate(Resolved fallback, String username, String xuid) {
        String name = username == null || username.isBlank() ? fallback.username() : username;
        String resolvedXuid = xuid == null || xuid.isBlank() ? fallback.xuid() : xuid;
        return new Resolved(MinecraftEdition.BEDROCK, bedrockUsername(name), resolvedXuid);
    }
}
