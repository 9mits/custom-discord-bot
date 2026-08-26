package bot.mgx.accessbridge;

import java.util.List;
import java.util.UUID;

/**
 * The authoritative access directory as the Discord bot last reported it: every
 * approved Minecraft UUID, edition, username, and linked Discord name. Held in
 * memory only — the bot pushes a fresh snapshot on connect, after every approval
 * or revocation, and on a timer, so a restart is at worst a few minutes behind.
 */
final class WhitelistDirectory {
    record Entry(String username, String edition, UUID minecraftUuid, String discordUsername) {
    }

    private volatile List<Entry> entries = List.of();
    private volatile boolean synced;

    void replace(List<Entry> updated) {
        entries = List.copyOf(updated);
        synced = true;
    }

    List<Entry> entries() {
        return entries;
    }

    boolean synced() {
        return synced;
    }

    boolean contains(UUID minecraftUuid, String username, MinecraftEdition edition) {
        String normalizedUsername = PendingVerification.normalize(username);
        for (Entry entry : entries) {
            if (entry.minecraftUuid() != null) {
                if (minecraftUuid != null && minecraftUuid.equals(entry.minecraftUuid())) {
                    return true;
                }
                // A synced UUID is the account identity. Never let another UUID
                // inherit access merely by presenting the approved username.
                continue;
            }
            if (!entry.edition().equalsIgnoreCase(edition.name())) {
                continue;
            }
            if (!normalizedUsername.isBlank()
                    && normalizedUsername.equals(PendingVerification.normalize(entry.username()))) {
                return true;
            }
        }
        return false;
    }
}
