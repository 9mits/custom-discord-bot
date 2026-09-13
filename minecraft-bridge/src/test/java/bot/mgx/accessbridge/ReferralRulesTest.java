package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static bot.mgx.accessbridge.ReferralRules.DAY_MILLIS;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReferralRulesTest {
    private static final long NOW = 1_800_000_000_000L;
    private static final ReferralRules.Settings SETTINGS = new ReferralRules.Settings(7, 60, 3, true);

    private static ReferralRules.Account veteran(String owner, String... addresses) {
        return new ReferralRules.Account(UUID.randomUUID(), owner, NOW - 30 * DAY_MILLIS, 600,
                Set.of(addresses));
    }

    private static ReferralRules.Account newcomer(String owner, String... addresses) {
        return new ReferralRules.Account(UUID.randomUUID(), owner, NOW, 0, Set.of(addresses));
    }

    @Test
    void anHonestNewPlayerReferralIsAccepted() {
        assertNull(ReferralRules.refusal(newcomer("newbie", "b"), ReferralRules.Kind.NEW, true,
                veteran("inviter", "a"), List.of(), SETTINGS, NOW));
    }

    @Test
    void linkedAltsAndSharedConnectionsAreRefused() {
        ReferralRules.Account inviter = veteran("owner", "home");
        assertNotNull(ReferralRules.refusal(newcomer("OWNER"), ReferralRules.Kind.NEW, true,
                inviter, List.of(), SETTINGS, NOW), "Discord names compare case-insensitively");
        assertNotNull(ReferralRules.refusal(newcomer("friend", "home"), ReferralRules.Kind.NEW,
                true, inviter, List.of(), SETTINGS, NOW));
        assertNull(ReferralRules.refusal(newcomer("friend", "home"), ReferralRules.Kind.NEW, true,
                inviter, List.of(), new ReferralRules.Settings(7, 60, 3, false), NOW));
    }

    @Test
    void aSecondAccountOnAKnownDiscordLinkIsNotNew() {
        ReferralRules.Account java = veteran("player");
        ReferralRules.Account bedrock = newcomer("player");
        assertFalse(ReferralRules.genuinelyNew(bedrock, List.of(java, bedrock)));
        assertTrue(ReferralRules.genuinelyNew(newcomer(null), List.of(java)));
        assertNotNull(ReferralRules.refusal(bedrock, ReferralRules.Kind.NEW, false,
                veteran("inviter"), List.of(), SETTINGS, NOW));
    }

    @Test
    void freshOrBarelyPlayedInvitersEarnNothing() {
        ReferralRules.Account young = new ReferralRules.Account(UUID.randomUUID(), "young",
                NOW - 2 * DAY_MILLIS, 600, Set.of());
        ReferralRules.Account idle = new ReferralRules.Account(UUID.randomUUID(), "idle",
                NOW - 30 * DAY_MILLIS, 10, Set.of());
        assertNotNull(ReferralRules.refusal(newcomer("a"), ReferralRules.Kind.NEW, true, young,
                List.of(), SETTINGS, NOW));
        assertNotNull(ReferralRules.refusal(newcomer("a"), ReferralRules.Kind.NEW, true, idle,
                List.of(), SETTINGS, NOW));
    }

    @Test
    void limitsReciprocityAndRepeatNewReferralsAreEnforced() {
        ReferralRules.Account inviter = veteran("inviter");
        ReferralRules.Account friend = veteran("friend");
        String inviterKey = inviter.ownerKey();
        List<ReferralRules.Referral> full = List.of(
                new ReferralRules.Referral(inviterKey, "discord:x", ReferralRules.Kind.NEW, NOW - DAY_MILLIS),
                new ReferralRules.Referral(inviterKey, "discord:y", ReferralRules.Kind.NEW, NOW - DAY_MILLIS),
                new ReferralRules.Referral(inviterKey, "discord:z", ReferralRules.Kind.RETURNING, NOW - DAY_MILLIS));
        assertNotNull(ReferralRules.refusal(friend, ReferralRules.Kind.RETURNING, true, inviter,
                full, SETTINGS, NOW));
        List<ReferralRules.Referral> old = full.stream()
                .map(row -> new ReferralRules.Referral(row.referrerOwner(), row.refereeOwner(),
                        row.kind(), NOW - 40 * DAY_MILLIS))
                .toList();
        assertNull(ReferralRules.refusal(friend, ReferralRules.Kind.RETURNING, true, inviter,
                old, SETTINGS, NOW), "the limit is a rolling 30 days");

        List<ReferralRules.Referral> turnAbout = List.of(new ReferralRules.Referral(
                friend.ownerKey(), inviterKey, ReferralRules.Kind.RETURNING, NOW - 90 * DAY_MILLIS));
        assertNotNull(ReferralRules.refusal(friend, ReferralRules.Kind.RETURNING, true, inviter,
                turnAbout, SETTINGS, NOW));

        ReferralRules.Account newbie = newcomer("newbie");
        List<ReferralRules.Referral> already = List.of(new ReferralRules.Referral(
                "discord:someone", newbie.ownerKey(), ReferralRules.Kind.NEW, NOW - 50 * DAY_MILLIS));
        assertNotNull(ReferralRules.refusal(newbie, ReferralRules.Kind.NEW, true, inviter,
                already, SETTINGS, NOW));
    }

    @Test
    void returningNeedsTheAbsenceRealHistoryAndACooldown() {
        assertTrue(ReferralRules.returning(NOW - 10 * DAY_MILLIS, NOW, 10, 120, 60));
        assertFalse(ReferralRules.returning(NOW - 9 * DAY_MILLIS, NOW, 10, 120, 60));
        assertFalse(ReferralRules.returning(NOW - 20 * DAY_MILLIS, NOW, 10, 5, 60),
                "an alt that joined once for a minute is not a returning player");
        assertFalse(ReferralRules.returning(0L, NOW, 10, 500, 60));
        assertTrue(ReferralRules.returnCooldownOver(0L, NOW, 30));
        assertFalse(ReferralRules.returnCooldownOver(NOW - 12 * DAY_MILLIS, NOW, 30));
        assertTrue(ReferralRules.returnCooldownOver(NOW - 31 * DAY_MILLIS, NOW, 30));
    }
}
