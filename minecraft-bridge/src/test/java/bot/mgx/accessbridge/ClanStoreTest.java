package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
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

        ClanStore.ClanView created = store.create(leader, "Leader", "EMBER");
        store.invite(leader, member, "Member", 1_000);
        ClanStore.ClanView joined = store.accept(member, "Member", 1_001);
        store.setStaff(leader, member, true);
        store.rename(leader, "ORANGE");

        ClanStore reloaded = new ClanStore(path);
        ClanStore.ClanView clan = reloaded.clanOf(member).orElseThrow();

        assertEquals(created.id(), joined.id());
        assertEquals("ORANGE", clan.name());
        assertEquals(2, clan.members().size());
        assertTrue(clan.staff().contains(member));
        assertFalse(clan.friendlyFire());
    }

    @Test
    void onlyLeaderCanPromoteRenameAndTransfer() throws Exception {
        ClanStore store = new ClanStore(temporaryDirectory.resolve("clans.json"));
        UUID leader = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        store.create(leader, "Leader", "FOUND");
        store.invite(leader, member, "Member", 1_000);
        store.accept(member, "Member", 1_001);

        assertThrows(ClanStore.ClanException.class, () -> store.rename(member, "NOPE"));
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
        store.create(firstLeader, "First", "FIRST");
        store.create(secondLeader, "Second", "SECOND");
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
        store.create(leader, "Leader", "PERM");

        assertThrows(ClanStore.ClanException.class, () -> store.leave(leader));

        assertEquals("PERM", store.disband(leader));
        assertTrue(store.clanOf(leader).isEmpty());
        assertTrue(store.list().isEmpty());
    }

    @Test
    void namesAreValidatedAndUniqueIgnoringCase() throws Exception {
        ClanStore store = new ClanStore(temporaryDirectory.resolve("clans.json"));
        store.create(UUID.randomUUID(), "First", "LUCKY");

        assertThrows(
                ClanStore.ClanException.class,
                () -> store.create(UUID.randomUUID(), "Second", "lucky")
        );
        assertThrows(
                ClanStore.ClanException.class,
                () -> store.create(UUID.randomUUID(), "Third", "<>bad")
        );
        assertThrows(
                ClanStore.ClanException.class,
                () -> store.create(UUID.randomUUID(), "Fourth", "TOOLONG")
        );
    }

    @Test
    void clanNameIsTheOnlyIdentityAndRenameUpdatesIt() throws Exception {
        ClanStore store = new ClanStore(temporaryDirectory.resolve("clans.json"));
        UUID lucky = UUID.randomUUID();
        ClanStore.ClanView created = store.create(lucky, "Lucky", "lucky");

        ClanStore.ClanView updated = store.rename(lucky, "ember");

        assertEquals("LUCKY", created.name());
        assertEquals("EMBER", updated.name());
        assertEquals(updated.id(), store.findClan("ember").orElseThrow().id());
        assertTrue(store.findClan("lucky").isEmpty());
    }

    @Test
    void separateLegacyTagsMigrateIntoTheSingleClanName() throws Exception {
        Path path = temporaryDirectory.resolve("clans.json");
        UUID clanId = UUID.randomUUID();
        UUID leader = UUID.randomUUID();
        Files.writeString(path, """
                {
                  "version": 1,
                  "clans": [{
                    "id": "%s",
                    "name": "Lucky Legends",
                    "tag": "LUCKY",
                    "leader": "%s",
                    "members": {"%s": "Leader"},
                    "staff": [],
                    "friendlyFire": false
                  }],
                  "invites": {}
                }
                """.formatted(clanId, leader, leader));

        ClanStore store = new ClanStore(path);

        assertEquals("LUCKY", store.clanOf(leader).orElseThrow().name());
        assertFalse(Files.readString(path).contains("\"tag\""));
    }
}
