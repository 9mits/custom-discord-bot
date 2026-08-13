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
        store.setThemeColor(leader, "12ABEF");

        ClanStore reloaded = new ClanStore(path);
        ClanStore.ClanView clan = reloaded.clanOf(member).orElseThrow();

        assertEquals(created.id(), joined.id());
        assertEquals("ORANGE", clan.name());
        assertEquals(2, clan.members().size());
        assertTrue(clan.staff().contains(member));
        assertEquals(0x12ABEF, clan.themeColor());
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
    void onlyLeaderCanSetAValidThemeColor() throws Exception {
        ClanStore store = new ClanStore(temporaryDirectory.resolve("clans.json"));
        UUID leader = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        store.create(leader, "Leader", "EMBER");
        store.invite(leader, member, "Member", 1_000);
        store.accept(member, "Member", 1_001);

        assertThrows(ClanStore.ClanException.class, () -> store.setThemeColor(member, "#55FFFF"));
        assertThrows(ClanStore.ClanException.class, () -> store.setThemeColor(leader, "not-a-color"));

        ClanStore.ClanView updated = store.setThemeColor(leader, "#55FFFF");

        assertEquals(0x55FFFF, updated.themeColor());
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
    void errorsDescribeThePlayersActualClanSituation() throws Exception {
        ClanStore store = new ClanStore(temporaryDirectory.resolve("clans.json"));
        UUID leader = UUID.randomUUID();
        UUID otherLeader = UUID.randomUUID();
        store.create(leader, "Leader", "EMBER");
        store.create(otherLeader, "Other", "OTHER");

        ClanStore.ClanException duplicateCreate = assertThrows(
                ClanStore.ClanException.class,
                () -> store.create(leader, "Leader", "THIRD")
        );
        ClanStore.ClanException duplicateAccept = assertThrows(
                ClanStore.ClanException.class,
                () -> store.accept(leader, "Leader", 1_000)
        );
        ClanStore.ClanException occupiedInvite = assertThrows(
                ClanStore.ClanException.class,
                () -> store.invite(otherLeader, leader, "Leader", 1_000)
        );
        ClanStore.ClanException selfKick = assertThrows(
                ClanStore.ClanException.class,
                () -> store.kick(leader, leader)
        );

        assertEquals("You already have a clan!", duplicateCreate.getMessage());
        assertEquals("You already have a clan!", duplicateAccept.getMessage());
        assertEquals("That player already has a clan.", occupiedInvite.getMessage());
        assertEquals("Transfer leadership or disband the clan before leaving.", selfKick.getMessage());
    }

    @Test
    void unchangedClanIdentityAndThemeReportNoChange() throws Exception {
        ClanStore store = new ClanStore(temporaryDirectory.resolve("clans.json"));
        UUID leader = UUID.randomUUID();
        store.create(leader, "Leader", "EMBER");

        ClanStore.ClanException sameName = assertThrows(
                ClanStore.ClanException.class,
                () -> store.rename(leader, "ember")
        );
        ClanStore.ClanException sameColor = assertThrows(
                ClanStore.ClanException.class,
                () -> store.setThemeColor(leader, "#FF9900")
        );

        assertEquals("Your clan already uses that name.", sameName.getMessage());
        assertEquals("Your clan already uses that theme color.", sameColor.getMessage());
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

        ClanStore.ClanView migrated = store.clanOf(leader).orElseThrow();
        assertEquals("LUCKY", migrated.name());
        assertEquals(ClanStore.DEFAULT_THEME_COLOR, migrated.themeColor());
        assertFalse(Files.readString(path).contains("\"tag\""));
    }
}
