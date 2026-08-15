package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
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
    void anyMemberDonatesButOnlyTheLeaderSpends() throws Exception {
        Path path = temporaryDirectory.resolve("clans.json");
        ClanStore store = new ClanStore(path);
        UUID leader = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        store.create(leader, "Leader", "VAULT");
        store.invite(leader, member, "Member", 1_000);
        store.accept(member, "Member", 1_001);

        store.donate(member, Map.of("DIAMOND", 20));
        store.donate(leader, Map.of("diamond", 10));

        ClanStore.ClanView banked = store.clanOf(leader).orElseThrow();
        assertEquals(30, banked.vault().get("DIAMOND"));
        assertEquals(WealthValues.valueOf("DIAMOND") * 30L, banked.balance());
        assertThrows(ClanStore.ClanException.class, () -> store.upgrade(member));

        ClanStore.ClanView upgraded = store.upgrade(leader);

        assertEquals(1, upgraded.level());
        // The price is debited, not merely checked.
        assertTrue(upgraded.vault().isEmpty());
        assertEquals(1, new ClanStore(path).clanOf(member).orElseThrow().level());
    }

    @Test
    void theDonorLedgerSurvivesSpendingTheVault() throws Exception {
        Path path = temporaryDirectory.resolve("clans.json");
        ClanStore store = new ClanStore(path);
        UUID leader = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        store.create(leader, "Leader", "GIVE");
        store.invite(leader, member, "Member", 1_000);
        store.accept(member, "Member", 1_001);

        store.donate(member, Map.of("DIAMOND", 25));
        store.donate(leader, Map.of("DIAMOND", 5));
        store.upgrade(leader);

        ClanStore.ClanView clan = new ClanStore(path).clanOf(leader).orElseThrow();

        // Spending emptied the vault but must not rewrite who paid for it.
        assertTrue(clan.vault().isEmpty());
        assertEquals(WealthValues.valueOf("DIAMOND") * 25L, clan.donations().get(member));
        assertEquals(WealthValues.valueOf("DIAMOND") * 5L, clan.donations().get(leader));
        // Ranked largest first, which is the order the donor board shows.
        assertEquals(member, clan.rankedDonors().get(0).getKey());
        assertEquals(leader, clan.rankedDonors().get(1).getKey());
    }

    @Test
    void donationsTakeAnythingOfValueAndNothingElse() throws Exception {
        ClanStore store = new ClanStore(temporaryDirectory.resolve("clans.json"));
        UUID leader = UUID.randomUUID();
        store.create(leader, "Leader", "ONLY");

        assertThrows(ClanStore.ClanException.class, () -> store.donate(leader, Map.of("DIRT", 64)));
        assertThrows(ClanStore.ClanException.class, () -> store.donate(leader, Map.of()));
        assertThrows(ClanStore.ClanException.class, () -> store.donate(leader, Map.of("DIAMOND", 0)));

        // A mixed batch banks the valuable half and silently ignores the rest; the
        // caller hands the worthless items back rather than the store storing them.
        long value = store.donate(leader, Map.of("DIAMOND", 4, "DIRT", 64, "ELYTRA", 1));

        ClanStore.ClanView clan = store.clanOf(leader).orElseThrow();
        assertEquals(WealthValues.valueOf("DIAMOND") * 4L + WealthValues.valueOf("ELYTRA"), value);
        assertFalse(clan.vault().containsKey("DIRT"));
        assertEquals(1, clan.vault().get("ELYTRA"));
    }

    @Test
    void thereIsNoWayToTakeADonationBackOut() throws Exception {
        // Donations being one-way is the rule the whole feature rests on, so the
        // absence of a withdraw path is worth asserting rather than assuming.
        for (java.lang.reflect.Method method : ClanStore.class.getDeclaredMethods()) {
            assertFalse(method.getName().toLowerCase(java.util.Locale.ROOT).contains("withdraw"),
                    "ClanStore grew " + method.getName() + "; donations are meant to be one-way");
        }
    }

    @Test
    void disbandingDestroysTheBalance() throws Exception {
        ClanStore store = new ClanStore(temporaryDirectory.resolve("clans.json"));
        UUID leader = UUID.randomUUID();
        store.create(leader, "Leader", "GONE");
        store.donate(leader, Map.of("DIAMOND", 12));

        ClanStore.ClanView disbanded = store.disband(leader);

        // The view still carries the figure so the warning can name it, but the clan
        // and everything donated to it are gone.
        assertEquals(WealthValues.valueOf("DIAMOND") * 12L, disbanded.balance());
        assertTrue(store.clanOf(leader).isEmpty());
        assertTrue(store.list().isEmpty());
    }

    @Test
    void clansStartAtThreeSlotsAndBuyTheirWayUp() throws Exception {
        ClanStore store = new ClanStore(temporaryDirectory.resolve("clans.json"));
        UUID leader = UUID.randomUUID();
        store.create(leader, "Leader", "ROOM");
        ClanStore.ClanView clan = store.clanOf(leader).orElseThrow();
        assertEquals(ClanLevel.STARTING_MEMBER_SLOTS, clan.memberSlots());

        UUID[] joiners = {UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()};
        store.invite(leader, joiners[0], "One", 1_000);
        store.accept(joiners[0], "One", 1_001);
        store.invite(leader, joiners[1], "Two", 1_002);
        store.accept(joiners[1], "Two", 1_003);

        // Three of three: the fourth invite is refused until a slot is bought.
        ClanStore.ClanException full = assertThrows(ClanStore.ClanException.class,
                () -> store.invite(leader, joiners[2], "Three", 1_004));
        assertTrue(full.getMessage().contains("full"), full.getMessage());

        ClanLevel.MemberTier tier = ClanLevel.MEMBER_TIERS.get(0);
        store.donate(leader, Map.of(tier.cost().material(), tier.cost().amount()));
        ClanStore.ClanView roomier = store.upgradeMembers(leader);

        assertEquals(tier.slots(), roomier.memberSlots());
        store.invite(leader, joiners[2], "Three", 1_005);
        assertEquals(4, store.accept(joiners[2], "Three", 1_006).members().size());
    }

    @Test
    void aRosterUpgradeIsRefusedWhileTheVaultIsShort() throws Exception {
        ClanStore store = new ClanStore(temporaryDirectory.resolve("clans.json"));
        UUID leader = UUID.randomUUID();
        store.create(leader, "Leader", "TIGHT");
        // A vault with plenty of value in it, but none of the material the next slot
        // actually asks for — balance alone does not buy anything.
        store.donate(leader, Map.of("ELYTRA", 3));

        assertThrows(ClanStore.ClanException.class, () -> store.upgradeMembers(leader));

        ClanStore.ClanView clan = store.clanOf(leader).orElseThrow();
        assertEquals(ClanLevel.STARTING_MEMBER_SLOTS, clan.memberSlots());
        assertEquals(3, clan.vault().get("ELYTRA"));
        assertTrue(clan.balance() > 0);
    }

    @Test
    void clansSavedBeforeTheRosterLadderKeepTheirMembers() throws Exception {
        // A five-member clan written under the old flat cap must not load at three
        // slots, which would put it over its own roster the moment it opened.
        Path path = temporaryDirectory.resolve("clans.json");
        UUID leader = UUID.randomUUID();
        StringBuilder members = new StringBuilder("\"" + leader + "\": \"Leader\"");
        for (int index = 0; index < 4; index++) {
            members.append(", \"").append(UUID.randomUUID()).append("\": \"M").append(index).append("\"");
        }
        Files.writeString(path, """
                {
                  "version": 1,
                  "clans": [{
                    "id": "%s",
                    "name": "OLDER",
                    "themeColor": 16750848,
                    "leader": "%s",
                    "members": {%s},
                    "staff": []
                  }],
                  "invites": {}
                }
                """.formatted(UUID.randomUUID(), leader, members));

        ClanStore.ClanView clan = new ClanStore(path).clanOf(leader).orElseThrow();

        assertEquals(5, clan.members().size());
        assertEquals(5, clan.memberSlots());
        assertTrue(clan.donations().isEmpty());
    }

    @Test
    void aRosterSizeOffTheLadderIsRejectedOnLoad() throws Exception {
        UUID leader = UUID.randomUUID();
        assertThrows(java.io.IOException.class, () -> new ClanStore(
                writeClan(temporaryDirectory.resolve("over.json"), leader, "\"memberSlots\": 26,")));
        assertThrows(java.io.IOException.class, () -> new ClanStore(
                writeClan(temporaryDirectory.resolve("under.json"), leader, "\"memberSlots\": 2,")));
        assertThrows(java.io.IOException.class, () -> new ClanStore(
                writeClan(temporaryDirectory.resolve("negative-donor.json"), leader,
                        "\"donations\": {\"" + UUID.randomUUID() + "\": -5},")));
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

        store.donate(second, Map.of("DRAGON_EGG", 1));
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
                store.donate(leader, Map.of(cost.material(), cost.amount()));
            }
            try {
                store.upgrade(leader);
            } catch (ClanStore.ClanException taken) {
                return;
            }
        }
    }
}
