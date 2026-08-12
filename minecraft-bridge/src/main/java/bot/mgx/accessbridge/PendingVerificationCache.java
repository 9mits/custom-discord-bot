package bot.mgx.accessbridge;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
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
        long now = Instant.now().getEpochSecond();
        entries.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now);
        return entries.values().stream()
                .filter(entry -> entry.matches(edition, username, now))
                .min(Comparator.comparingLong(PendingVerification::applicationId));
    }

    int size() {
        return entries.size();
    }
}
