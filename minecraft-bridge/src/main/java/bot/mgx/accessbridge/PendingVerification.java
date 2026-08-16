package bot.mgx.accessbridge;

enum MinecraftEdition {
    JAVA,
    BEDROCK,
    AUTO
}
record PendingVerification(
        long applicationId,
        MinecraftEdition edition,
        String claimedUsername,
        String normalizedUsername,
        long expiresAt
) {
    boolean matches(MinecraftEdition actualEdition, String actualUsername, long now) {
        return (edition == MinecraftEdition.AUTO || edition == actualEdition)
                && expiresAt > now
                && normalizedUsername.equals(normalize(actualUsername));
    }

    static String normalize(String username) {
        if (username == null) {
            return "";
        }
        // Floodgate turns Bedrock spaces into underscores and prefixes a dot.
        String folded = VerificationIdentity.bedrockUsername(username)
                .replace('_', ' ')
                .strip()
                .replaceAll("\\s+", " ");
        return folded.toLowerCase(java.util.Locale.ROOT);
    }
}
