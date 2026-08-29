package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ClanBattleStoreTest {
    private static final long DEADLINE = 900_000L;

    @TempDir
    Path directory;

    @Test
    void crateScoresFollowOnlyTheCurrentUnbrokenClanMembership() throws Exception {
        ClanStore clans = new ClanStore(directory.resolve("clans.json"));
        ClanBattleStore battles = new ClanBattleStore(directory.resolve("battles.json"));
        UUID alphaLeader = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        UUID betaLeader = UUID.randomUUID();
        ClanStore.ClanView alpha = clans.create(alphaLeader, "AlphaLead", "ALPHA");
        clans.invite(alphaLeader, player, "Runner", 1_000L);
        clans.accept(player, "Runner", 1_001L);
        clans.create(betaLeader, "BetaLead", "BETA");

        battles.start(ClanBattleStore.Kind.CRATES, 2_000L, DEADLINE, clans);
        assertEquals(1L, battles.recordCrate(player, 5_000L, clans));
        assertEquals(2L, battles.recordCrate(player, 5_000L, clans));
        assertEquals(2L, battles.active(clans).orElseThrow().standings().getFirst().score());

        clans.kick(alphaLeader, player);
        assertTrue(battles.active(clans).orElseThrow().standings().isEmpty());

        clans.invite(betaLeader, player, "Runner", 3_000L);
        clans.accept(player, "Runner", 3_001L);
        assertEquals(1L, battles.recordCrate(player, 5_000L, clans));
        ClanBattleStore.Standing current = battles.active(clans).orElseThrow()
                .standings().getFirst();
        assertEquals("BETA", current.clanName());
        assertEquals(1L, current.score());

        clans.leave(player);
        clans.invite(betaLeader, player, "Runner", 4_000L);
        clans.accept(player, "Runner", 4_001L);
        assertEquals(1L, battles.recordCrate(player, 5_000L, clans),
                "rejoining the same clan must not restore the old contribution");
        assertFalse(alpha.id().equals(current.clanId()));
    }

    @Test
    void endingSnapshotsMembersAndStacksClanBadgesAndShardGrants() throws Exception {
        ClanStore clans = new ClanStore(directory.resolve("clans.json"));
        ClanBattleStore battles = new ClanBattleStore(directory.resolve("battles.json"));
        UUID goldLeader = UUID.randomUUID();
        UUID goldMember = UUID.randomUUID();
        UUID silverLeader = UUID.randomUUID();
        ClanStore.ClanView gold = clans.create(goldLeader, "Gold", "GOLD");
        ClanStore.ClanView silver = clans.create(silverLeader, "Silver", "SILVER");
        clans.invite(goldLeader, goldMember, "Member", 100L);
        clans.accept(goldMember, "Member", 101L);

        for (int i = 0; i < 3; i++) {
            battles.start(ClanBattleStore.Kind.CRATES, 1_000L + i, DEADLINE, clans);
            battles.recordCrate(goldLeader, 5_000L, clans);
            battles.recordCrate(goldMember, 5_000L, clans);
            battles.recordCrate(silverLeader, 5_000L, clans);
            ClanBattleStore.CompletedView completed = battles.end(clans, 2_000L + i);
            assertEquals(List.of(1, 2), completed.winners().stream()
                    .map(ClanBattleStore.Standing::rank).toList());
        }

        assertEquals(new ClanBattleStore.Badges(3, 0, 0), battles.badges(gold.id()));
        assertEquals(new ClanBattleStore.Badges(0, 3, 0), battles.badges(silver.id()));
        assertEquals(List.of(10, 10, 10), battles.shardGrants(goldMember).stream()
                .map(ClanBattleStore.ShardGrant::amount).toList());
        assertEquals(List.of(5, 5, 5), battles.shardGrants(silverLeader).stream()
                .map(ClanBattleStore.ShardGrant::amount).toList());

        ClanBattleStore reloaded = new ClanBattleStore(directory.resolve("battles.json"));
        assertEquals(new ClanBattleStore.Badges(3, 0, 0), reloaded.badges(gold.id()));
        ClanBattleStore.ShardGrant first = reloaded.shardGrants(goldLeader).getFirst();
        assertTrue(reloaded.completeShardGrant(goldLeader, first.grantId()));
        assertEquals(2, reloaded.shardGrants(goldLeader).size());
        assertFalse(reloaded.completeShardGrant(goldLeader, first.grantId()));
    }

    @Test
    void tiesSharePlacementAndSkipTheFollowingPlace() throws Exception {
        ClanStore clans = new ClanStore(directory.resolve("clans.json"));
        ClanBattleStore battles = new ClanBattleStore(directory.resolve("battles.json"));
        UUID one = UUID.randomUUID();
        UUID two = UUID.randomUUID();
        UUID three = UUID.randomUUID();
        clans.create(one, "One", "ONE");
        clans.create(two, "Two", "TWO");
        clans.create(three, "Three", "THREE");

        battles.start(ClanBattleStore.Kind.CRATES, 10L, DEADLINE, clans);
        battles.recordCrate(one, 5_000L, clans);
        battles.recordCrate(two, 5_000L, clans);
        battles.recordCrate(three, 5_000L, clans);
        battles.recordCrate(three, 5_000L, clans);

        assertEquals(List.of(1, 2, 2), battles.active(clans).orElseThrow().standings()
                .stream().map(ClanBattleStore.Standing::rank).toList());
        assertThrows(IllegalArgumentException.class,
                () -> battles.start(ClanBattleStore.Kind.CRATES, 11L, DEADLINE, clans));
    }

    @Test
    void theDeadlineIsTheAmethystCrateCloseAndStopsScoringOnceItPasses() throws Exception {
        ClanStore clans = new ClanStore(directory.resolve("clans.json"));
        ClanBattleStore battles = new ClanBattleStore(directory.resolve("battles.json"));
        UUID leader = UUID.randomUUID();
        clans.create(leader, "Leader", "LATE");

        ClanBattleStore.ActiveView active =
                battles.start(ClanBattleStore.Kind.CRATES, 1_000L, 5_000L, clans);
        assertEquals(5_000L, active.endsAt());
        assertFalse(active.expired(4_999L));
        assertTrue(active.expired(5_000L));

        assertEquals(1L, battles.recordCrate(leader, 4_999L, clans));
        assertEquals(0L, battles.recordCrate(leader, 5_000L, clans),
                "an opening at or after the deadline must not score");
        assertEquals(1L, battles.active(clans).orElseThrow().standings().getFirst().score());

        assertThrows(IllegalArgumentException.class,
                () -> new ClanBattleStore(directory.resolve("other.json"))
                        .start(ClanBattleStore.Kind.CRATES, 5_000L, 5_000L, clans));
    }

    @Test
    void theDeadlineSurvivesAReload() throws Exception {
        ClanStore clans = new ClanStore(directory.resolve("clans.json"));
        ClanBattleStore battles = new ClanBattleStore(directory.resolve("battles.json"));
        clans.create(UUID.randomUUID(), "Leader", "KEEP");
        battles.start(ClanBattleStore.Kind.CRATES, 1_000L, 8_640_000L, clans);

        ClanBattleStore reloaded = new ClanBattleStore(directory.resolve("battles.json"));
        assertEquals(8_640_000L, reloaded.active(clans).orElseThrow().endsAt());
    }
}
