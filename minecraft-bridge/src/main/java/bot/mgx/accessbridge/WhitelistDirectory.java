package bot.mgx.accessbridge;

import java.util.List;

/**
 * The whitelist as the Discord bot last reported it: every approved player with
 * their linked Discord name. Held in memory only — the bot pushes a fresh
 * snapshot on connect, after every approval or revocation, and on a timer, so a
 * restart is at worst a few minutes behind.
 */
final class WhitelistDirectory {
    record Entry(String username, String edition, String discordUsername) {
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
}
