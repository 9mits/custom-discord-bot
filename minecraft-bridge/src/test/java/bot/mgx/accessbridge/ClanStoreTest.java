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

        assertEquals("PERM", store.disband(leader).name());
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

    @Test
    void clansSavedBeforeUpgradesLoadUnranked() throws Exception {
        // Clans written by 2.27.0 carry neither field; they must read as a level 0
        // clan with an empty vault rather than failing the whole file to load.
        Path path = temporaryDirectory.resolve("clans.json");
        UUID leader = UUID.randomUUID();
        Files.writeString(path, """
                {
                  "version": 1,
                  "clans": [{
                    "id": "%s",
                    "name": "OLDER",
                    "themeColor": 16750848,
                    "leader": "%s",
                    "members": {"%s": "Leader"},
                    "staff": []
                  }],
                  "invites": {}
                }
                """.formatted(UUID.randomUUID(), leader, leader));

        ClanStore.ClanView clan = new ClanStore(path).clanOf(leader).orElseThrow();

        assertEquals(0, clan.level());
        assertTrue(clan.vault().isEmpty());
        assertTrue(clan.perks().isNone());
    }

    @Test
    void anyMemberBanksButOnlyTheLeaderSpends() throws Exception {
        Path path = temporaryDirectory.resolve("clans.json");
        ClanStore store = new ClanStore(path);
        UUID leader = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        store.create(leader, "Leader", "VAULT");
        store.invite(leader, member, "Member", 1_000);
        store.accept(member, "Member", 1_001);

        store.deposit(member, "DIAMOND", 20);
        ClanStore.ClanView banked = store.deposit(leader, "diamond", 10);

        assertEquals(30, banked.vault().get("DIAMOND"));
        assertThrows(ClanStore.ClanException.class, () -> store.upgrade(member));
        assertThrows(ClanStore.ClanException.class, () -> store.withdraw(member, "DIAMOND", 1));

        ClanStore.ClanView upgraded = store.upgrade(leader);

        assertEquals(1, upgraded.level());
        // The price is debited, not merely checked.
        assertTrue(upgraded.vault().isEmpty());
        assertEquals(1, new ClanStore(path).clanOf(member).orElseThrow().level());
    }

    @Test
    void anUpgradeIsRefusedWhileTheVaultIsShort() throws Exception {
        ClanStore store = new ClanStore(temporaryDirectory.resolve("clans.json"));
        UUID leader = UUID.randomUUID();
        store.create(leader, "Leader", "SHORT");
        store.deposit(leader, "DIAMOND", 29);

        ClanStore.ClanException refused =
                assertThrows(ClanStore.ClanException.class, () -> store.upgrade(leader));

        assertTrue(refused.getMessage().contains("1x Diamond"), refused.getMessage());
        // Nothing was taken on the way to being refused.
        assertEquals(29, store.clanOf(leader).orElseThrow().vault().get("DIAMOND"));
        assertEquals(0, store.clanOf(leader).orElseThrow().level());
    }

    @Test
    void theVaultHoldsUpgradeMaterialsOnly() throws Exception {
        ClanStore store = new ClanStore(temporaryDirectory.resolve("clans.json"));
        UUID leader = UUID.randomUUID();
        store.create(leader, "Leader", "ONLY");

        assertThrows(ClanStore.ClanException.class, () -> store.deposit(leader, "DIRT", 64));
        assertThrows(ClanStore.ClanException.class, () -> store.deposit(leader, "DIAMOND", 0));
        assertThrows(ClanStore.ClanException.class, () -> store.deposit(leader, "DIAMOND", -5));
    }

    @Test
    void withdrawingReturnsExactlyWhatWasBanked() throws Exception {
        ClanStore store = new ClanStore(temporaryDirectory.resolve("clans.json"));
        UUID leader = UUID.randomUUID();
        store.create(leader, "Leader", "BACK");
        store.deposit(leader, "DIAMOND", 10);

        assertThrows(ClanStore.ClanException.class, () -> store.withdraw(leader, "DIAMOND", 11));
        assertThrows(ClanStore.ClanException.class, () -> store.withdraw(leader, "NETHER_STAR", 1));

        assertEquals(6, store.withdraw(leader, "DIAMOND", 4).vault().get("DIAMOND"));
        // Emptying a material drops it rather than leaving a zero behind.
        assertTrue(store.withdraw(leader, "DIAMOND", 6).vault().isEmpty());
    }

    @Test
    void disbandingHandsTheVaultBack() throws Exception {
        ClanStore store = new ClanStore(temporaryDirectory.resolve("clans.json"));
        UUID leader = UUID.randomUUID();
        store.create(leader, "Leader", "GONE");
        store.deposit(leader, "DIAMOND", 12);

        ClanStore.ClanView disbanded = store.disband(leader);

        assertEquals(12, disbanded.vault().get("DIAMOND"));
        assertTrue(store.clanOf(leader).isEmpty());
    }

    @Test
    void onlyOneClanEverHoldsTheSecretLevel() throws Exception {
        ClanStore store = new ClanStore(temporaryDirectory.resolve("clans.json"));
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        store.create(first, "First", "ONEUP");
        store.create(second, "Second", "TWOUP");

        climbToTop(store, first);
        climbToTop(store, second);

        assertEquals(ClanLevel.SECRET_LEVEL, store.clanOf(first).orElseThrow().level());
        assertEquals(ClanLevel.MAX_PUBLIC_LEVEL, store.clanOf(second).orElseThrow().level());

        store.deposit(second, "DRAGON_EGG", 1);
        ClanStore.ClanException refused =
                assertThrows(ClanStore.ClanException.class, () -> store.upgrade(second));
        assertTrue(refused.getMessage().contains("Only one"), refused.getMessage());
    }

    @Test
    void aSecondSecretLevelClanIsRejectedOnLoad() throws Exception {
        Path path = temporaryDirectory.resolve("clans.json");
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        Files.writeString(path, """
                {
                  "version": 1,
                  "clans": [
                    {"id": "%s", "name": "ONEUP", "themeColor": 16750848, "level": 6,
                     "leader": "%s", "members": {"%s": "One"}, "staff": []},
                    {"id": "%s", "name": "TWOUP", "themeColor": 16750848, "level": 6,
                     "leader": "%s", "members": {"%s": "Two"}, "staff": []}
                  ],
                  "invites": {}
                }
                """.formatted(
                UUID.randomUUID(), first, first,
                UUID.randomUUID(), second, second));

        assertThrows(java.io.IOException.class, () -> new ClanStore(path));
    }

    @Test
    void impossibleLevelsAndVaultsAreRejectedOnLoad() throws Exception {
        UUID leader = UUID.randomUUID();

        assertThrows(java.io.IOException.class, () -> new ClanStore(
                writeClan(temporaryDirectory.resolve("high.json"), leader, "\"level\": 9,")));
        assertThrows(java.io.IOException.class, () -> new ClanStore(
                writeClan(temporaryDirectory.resolve("negative.json"), leader,
                        "\"vault\": {\"DIAMOND\": -1},")));
        assertThrows(java.io.IOException.class, () -> new ClanStore(
                writeClan(temporaryDirectory.resolve("junk.json"), leader,
                        "\"vault\": {\"DIRT\": 64},")));
    }

    private Path writeClan(Path path, UUID leader, String extraFields) throws Exception {
        Files.writeString(path, """
                {
                  "version": 1,
                  "clans": [{
                    "id": "%s",
                    "name": "BROKE",
                    "themeColor": 16750848,
                    %s
                    "leader": "%s",
                    "members": {"%s": "Leader"},
                    "staff": []
                  }],
                  "invites": {}
                }
                """.formatted(UUID.randomUUID(), extraFields, leader, leader));
        return path;
    }

    /** Buys every level in turn, banking exactly what each one asks for. */
    private void climbToTop(ClanStore store, UUID leader) throws Exception {
        for (int level = 1; level <= ClanLevel.SECRET_LEVEL; level++) {
            for (ClanLevel.Cost cost : ClanLevel.costOf(level)) {
                store.deposit(leader, cost.material(), cost.amount());
            }
            try {
                store.upgrade(leader);
            } catch (ClanStore.ClanException taken) {
                return;
            }
        }
    }
}
