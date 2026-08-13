package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClanStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void clanLifecyclePersistsAcrossReloads() throws Exception {
        Path path = temporaryDirectory.resolve("clans.json");
        UUID leader = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        ClanStore store = new ClanStore(path);

        ClanStore.ClanView created = store.create(leader, "Leader", "Ember Guard");
        store.invite(leader, member, "Member", 1_000);
        ClanStore.ClanView joined = store.accept(member, "Member", 1_001);
        store.setStaff(leader, member, true);
        store.rename(leader, "Orange Guard");

        ClanStore reloaded = new ClanStore(path);
        ClanStore.ClanView clan = reloaded.clanOf(member).orElseThrow();

        assertEquals(created.id(), joined.id());
        assertEquals("Orange Guard", clan.name());
        assertEquals(2, clan.members().size());
        assertTrue(clan.staff().contains(member));
        assertFalse(clan.friendlyFire());
    }

    @Test
    void onlyLeaderCanPromoteRenameAndTransfer() throws Exception {
        ClanStore store = new ClanStore(temporaryDirectory.resolve("clans.json"));
        UUID leader = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        store.create(leader, "Leader", "Founders");
        store.invite(leader, member, "Member", 1_000);
        store.accept(member, "Member", 1_001);

        assertThrows(ClanStore.ClanException.class, () -> store.rename(member, "Nope Clan"));
        assertThrows(ClanStore.ClanException.class, () -> store.setStaff(member, leader, true));

        ClanStore.ClanView transferred = store.transfer(leader, member);

        assertEquals(member, transferred.leader());
        assertTrue(transferred.staff().contains(leader));
        assertFalse(transferred.staff().contains(member));
    }

    @Test
    void invitesExpireAndPlayersCannotJoinTwoClans() throws Exception {
        ClanStore store = new ClanStore(temporaryDirectory.resolve("clans.json"));
        UUID firstLeader = UUID.randomUUID();
        UUID secondLeader = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        store.create(firstLeader, "First", "First Clan");
        store.create(secondLeader, "Second", "Second Clan");
        store.invite(firstLeader, player, "Player", 1_000);

        assertThrows(
                ClanStore.ClanException.class,
                () -> store.accept(player, "Player", 1_000 + ClanStore.INVITE_TTL_MILLIS)
        );

        store.invite(firstLeader, player, "Player", 5_000_000);
        store.accept(player, "Player", 5_000_001);

        assertThrows(
                ClanStore.ClanException.class,
                () -> store.invite(secondLeader, player, "Player", 5_000_002)
        );
    }

    @Test
    void leaderMustTransferOrDisbandBeforeLeaving() throws Exception {
        ClanStore store = new ClanStore(temporaryDirectory.resolve("clans.json"));
        UUID leader = UUID.randomUUID();
        store.create(leader, "Leader", "Permanent Clan");

        assertThrows(ClanStore.ClanException.class, () -> store.leave(leader));

        assertEquals("Permanent Clan", store.disband(leader));
        assertTrue(store.clanOf(leader).isEmpty());
        assertTrue(store.list().isEmpty());
    }

    @Test
    void namesAreValidatedAndUniqueIgnoringCase() throws Exception {
        ClanStore store = new ClanStore(temporaryDirectory.resolve("clans.json"));
        store.create(UUID.randomUUID(), "First", "Orange Crew");

        assertThrows(
                ClanStore.ClanException.class,
                () -> store.create(UUID.randomUUID(), "Second", "orange crew")
        );
        assertThrows(
                ClanStore.ClanException.class,
                () -> store.create(UUID.randomUUID(), "Third", "<>bad")
        );
    }
}
