package bot.mgx.accessbridge;

enum MinecraftEdition {
    JAVA,
    BEDROCK
}
record PendingVerification(
        long applicationId,
        MinecraftEdition edition,
        String claimedUsername,
        String normalizedUsername,
        long expiresAt
) {
    boolean matches(MinecraftEdition actualEdition, String actualUsername, long now) {
        return edition == actualEdition
                && expiresAt > now
                && normalizedUsername.equals(normalize(actualUsername));
    }

    static String normalize(String username) {
        return username.strip().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
    }
}
