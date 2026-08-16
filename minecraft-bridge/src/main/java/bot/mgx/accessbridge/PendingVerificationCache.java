package bot.mgx.accessbridge;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

final class PendingVerificationCache {
    private final ConcurrentHashMap<Long, PendingVerification> entries = new ConcurrentHashMap<>();

    void replace(Collection<PendingVerification> pending) {
        entries.clear();
        for (PendingVerification verification : pending) {
            entries.put(verification.applicationId(), verification);
        }
    }

    void put(PendingVerification pending) {
        entries.put(pending.applicationId(), pending);
    }

    void remove(long applicationId) {
        entries.remove(applicationId);
    }

    Optional<PendingVerification> match(MinecraftEdition edition, String username) {
        return unique(candidates(edition, username));
    }

    /**
     * Login names during a hold are the Floodgate-prefixed Bukkit name, and the
     * application edition is often JAVA/AUTO while the client is Bedrock. Ignore
     * both and match the stripped name only.
     */
    Optional<PendingVerification> matchLogin(String loginName) {
        String stripped = VerificationIdentity.bedrockUsername(loginName);
        java.util.LinkedHashSet<PendingVerification> found = new java.util.LinkedHashSet<>();
        match(MinecraftEdition.JAVA, loginName).ifPresent(found::add);
        match(MinecraftEdition.BEDROCK, loginName).ifPresent(found::add);
        match(MinecraftEdition.JAVA, stripped).ifPresent(found::add);
        match(MinecraftEdition.BEDROCK, stripped).ifPresent(found::add);
        match(MinecraftEdition.AUTO, stripped).ifPresent(found::add);
        if (found.size() == 1) {
            return Optional.of(found.iterator().next());
        }
        java.util.List<PendingVerification> loose = candidates(null, stripped);
        java.util.List<PendingVerification> dotted = candidates(null, loginName);
        java.util.LinkedHashSet<PendingVerification> combined = new java.util.LinkedHashSet<>();
        combined.addAll(loose);
        combined.addAll(dotted);
        return unique(combined.stream().toList());
    }

    int size() {
        return entries.size();
    }

    java.util.List<String> snapshotNames() {
        return entries.values().stream()
                .map(PendingVerification::claimedUsername)
                .toList();
    }

    private java.util.List<PendingVerification> candidates(MinecraftEdition edition, String username) {
        long now = Instant.now().getEpochSecond();
        entries.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now);
        String needle = PendingVerification.normalize(username);
        return entries.values().stream()
                .filter(entry -> entry.expiresAt() > now)
                .filter(entry -> {
                    if (edition != null && entry.edition() != MinecraftEdition.AUTO
                            && entry.edition() != edition) {
                        return false;
                    }
                    String stored = PendingVerification.normalize(entry.claimedUsername());
                    String storedFolded = PendingVerification.normalize(entry.normalizedUsername());
                    return stored.equals(needle) || storedFolded.equals(needle);
                })
                .toList();
    }

    private static Optional<PendingVerification> unique(java.util.List<PendingVerification> matches) {
        if (matches.size() == 1) {
            return Optional.of(matches.get(0));
        }
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        long distinct = matches.stream().map(PendingVerification::applicationId).distinct().count();
        if (distinct != 1) {
            return Optional.empty();
        }
        return Optional.of(matches.get(0));
    }
}
