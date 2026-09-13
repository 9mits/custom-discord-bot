package bot.mgx.accessbridge;

import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Who may be paid for bringing a player to the server.
 *
 * <p>Free of Bukkit so every refusal is unit tested. The rewards are Shards, which buy
 * Shard Crates, so every rule here exists because an alt account is the cheapest way
 * to mint them: a second account on the same Discord link, a second account on the
 * same connection, an account created yesterday to invite itself, or two people
 * taking turns inviting each other back.
 */
final class ReferralRules {
    static final long DAY_MILLIS = 86_400_000L;

    enum Kind {
        NEW,
        RETURNING
    }

    /** What the server knows about one Minecraft account. */
    record Account(
            UUID id, String discordOwner, long firstSeen, long playMinutes, Set<String> addresses
    ) {
        Account {
            addresses = addresses == null ? Set.of() : Set.copyOf(addresses);
        }

        String ownerKey() {
            return ReferralRules.ownerKey(id, discordOwner);
        }
    }

    /** One accepted referral, paid or still waiting on the invited player's playtime. */
    record Referral(String referrerOwner, String refereeOwner, Kind kind, long at) {
    }

    record Settings(
            long referrerMinimumDays,
            long referrerMinimumPlayMinutes,
            boolean blockSharedAddress
    ) {
    }

    private ReferralRules() {
    }

    /**
     * One identity per person: the linked Discord account when there is one, the
     * Minecraft account otherwise. Java and Bedrock accounts on one link are one person.
     */
    static String ownerKey(UUID id, String discordOwner) {
        return discordOwner == null || discordOwner.isBlank()
                ? "minecraft:" + id
                : "discord:" + discordOwner.toLowerCase(Locale.ROOT);
    }

    static boolean returning(
            long lastSeen, long now, long absenceDays, long lifetimePlayMinutes,
            long minimumPlayMinutes
    ) {
        return lastSeen > 0L
                && now - lastSeen >= absenceDays * DAY_MILLIS
                && lifetimePlayMinutes >= minimumPlayMinutes;
    }

    /** Whether this person, on any linked account, has been paid for returning recently. */
    static boolean returnCooldownOver(long lastReturnRewardAt, long now, long cooldownDays) {
        return lastReturnRewardAt <= 0L || now - lastReturnRewardAt >= cooldownDays * DAY_MILLIS;
    }

    /**
     * A new account only counts as a new person when no other account on the server
     * shares its Discord link — otherwise it is somebody's second edition or an alt.
     */
    static boolean genuinelyNew(Account account, Collection<Account> known) {
        for (Account other : known) {
            if (!other.id().equals(account.id()) && other.ownerKey().equals(account.ownerKey())) {
                return false;
            }
        }
        return true;
    }

    /** Null when the referral may be accepted, otherwise the reason shown to the player. */
    static String refusal(
            Account referee, Kind kind, boolean refereeGenuinelyNew, Account referrer,
            Collection<Referral> history, Settings settings, long now
    ) {
        if (referee.id().equals(referrer.id())
                || referee.ownerKey().equals(referrer.ownerKey())) {
            return "You cannot refer yourself or another account on your own Discord link.";
        }
        if (settings.blockSharedAddress() && shareAddress(referee, referrer)) {
            return "Accounts that have joined from the same connection cannot refer each other.";
        }
        if (kind == Kind.NEW && !refereeGenuinelyNew) {
            return "Your Discord account already has another Minecraft account here, so this"
                    + " is not a new player referral.";
        }
        if (referrer.firstSeen() <= 0L
                || now - referrer.firstSeen() < settings.referrerMinimumDays() * DAY_MILLIS) {
            return "That player has not been on the server long enough to earn referrals yet.";
        }
        if (referrer.playMinutes() < settings.referrerMinimumPlayMinutes()) {
            return "That player has not played long enough to earn referrals yet.";
        }
        String referrerOwner = referrer.ownerKey();
        String refereeOwner = referee.ownerKey();
        for (Referral past : history) {
            if (kind == Kind.NEW && past.kind() == Kind.NEW
                    && past.refereeOwner().equals(refereeOwner)) {
                return "You have already been referred as a new player.";
            }
            // Two people taking turns bringing each other "back" is a Shard printer.
            if (past.referrerOwner().equals(refereeOwner)
                    && past.refereeOwner().equals(referrerOwner)) {
                return "You and that player have already referred each other.";
            }
        }
        return null;
    }

    private static boolean shareAddress(Account first, Account second) {
        for (String address : first.addresses()) {
            if (second.addresses().contains(address)) {
                return true;
            }
        }
        return false;
    }
}
