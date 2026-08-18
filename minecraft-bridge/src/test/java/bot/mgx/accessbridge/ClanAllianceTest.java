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

class ClanAllianceTest {
    @TempDir
    Path temporaryDirectory;

    private record Founded(ClanStore store, UUID a, UUID b, UUID c, UUID d) {
    }

    /** Four clans of one member each, which is all the alliance rules need. */
    private Founded founded(Path path) throws Exception {
        ClanStore store = new ClanStore(path);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        UUID d = UUID.randomUUID();
        store.create(a, "Ana", "ALPHA");
        store.create(b, "Ben", "BETA");
        store.create(c, "Cai", "GAMMA");
        store.create(d, "Dee", "DELTA");
        return new Founded(store, a, b, c, d);
    }

    @Test
    void oneOfferIsNotAnAllianceAndTwoAre() throws Exception {
        Founded f = founded(temporaryDirectory.resolve("clans.json"));

        ClanStore.AllyResult offered = f.store().ally(f.a(), "BETA", 1_000);
        assertFalse(offered.formed());
        assertTrue(offered.own().allyNames().isEmpty());
        // The truce must not apply until both clans have said yes.
        assertFalse(f.store().pvpBlocked(f.a(), f.b()));

        ClanStore.AllyResult formed = f.store().ally(f.b(), "ALPHA", 2_000);
        assertTrue(formed.formed());
        assertTrue(f.store().pvpBlocked(f.a(), f.b()));
        assertTrue(f.store().pvpBlocked(f.b(), f.a()));
        assertEquals(java.util.List.of("BETA"), f.store().clanOf(f.a()).orElseThrow().allyNames());
        assertEquals(java.util.List.of("ALPHA"), f.store().clanOf(f.b()).orElseThrow().allyNames());
    }

    @Test
    void anExpiredOfferDoesNotSilentlyFormAnAllianceLater() throws Exception {
        Founded f = founded(temporaryDirectory.resolve("clans.json"));
        f.store().ally(f.a(), "BETA", 0);

        // Answering after the window re-offers rather than accepting: a clan should
        // never find itself in a truce it agreed to hours earlier and forgot.
        ClanStore.AllyResult late = f.store().ally(f.b(), "ALPHA", ClanStore.ALLY_OFFER_TTL_MILLIS + 1);
        assertFalse(late.formed());
        assertFalse(f.store().pvpBlocked(f.a(), f.b()));
    }

    @Test
    void breakingAnAllianceNeedsOnlyOneSide() throws Exception {
        Founded f = founded(temporaryDirectory.resolve("clans.json"));
        f.store().ally(f.a(), "BETA", 1_000);
        f.store().ally(f.b(), "ALPHA", 1_001);

        f.store().unally(f.b(), "ALPHA");
        assertFalse(f.store().pvpBlocked(f.a(), f.b()));
        assertFalse(f.store().pvpBlocked(f.b(), f.a()));
        assertTrue(f.store().clanOf(f.a()).orElseThrow().allyNames().isEmpty());
    }

    @Test
    void aClanCannotAllyItselfOrTwiceOrPastTheCap() throws Exception {
        Founded f = founded(temporaryDirectory.resolve("clans.json"));
        assertThrows(ClanStore.ClanException.class, () -> f.store().ally(f.a(), "ALPHA", 1_000));
        assertThrows(ClanStore.ClanException.class, () -> f.store().ally(f.a(), "NOPE", 1_000));

        f.store().ally(f.a(), "BETA", 1_000);
        f.store().ally(f.b(), "ALPHA", 1_001);
        assertThrows(ClanStore.ClanException.class, () -> f.store().ally(f.a(), "BETA", 1_002));
        assertThrows(ClanStore.ClanException.class, () -> f.store().unally(f.a(), "GAMMA"));
    }

    @Test
    void theCapHoldsAndIsCountedOnBothSides() throws Exception {
        Path path = temporaryDirectory.resolve("clans.json");
        ClanStore store = new ClanStore(path);
        UUID hub = UUID.randomUUID();
        store.create(hub, "Hub", "HUB");
        for (int index = 0; index < ClanStore.MAX_ALLIES; index++) {
            UUID leader = UUID.randomUUID();
            String name = "ALLY" + index;
            store.create(leader, "Leader" + index, name);
            store.ally(hub, name, 1_000);
            store.ally(leader, "HUB", 1_001);
        }
        assertEquals(ClanStore.MAX_ALLIES, store.clanOf(hub).orElseThrow().allyNames().size());

        UUID extra = UUID.randomUUID();
        store.create(extra, "Extra", "EXTRA");
        assertThrows(ClanStore.ClanException.class, () -> store.ally(hub, "EXTRA", 2_000));
        // The offer may still be made from the other side; accepting it must not be
        // the thing that pushes a clan past the limit.
        store.ally(extra, "HUB", 2_000);
        assertThrows(ClanStore.ClanException.class, () -> store.ally(hub, "EXTRA", 2_001));
    }

    @Test
    void disbandingReleasesEveryoneItWasAlliedTo() throws Exception {
        Path path = temporaryDirectory.resolve("clans.json");
        Founded f = founded(path);
        f.store().ally(f.a(), "BETA", 1_000);
        f.store().ally(f.b(), "ALPHA", 1_001);

        f.store().disband(f.a());
        assertTrue(f.store().clanOf(f.b()).orElseThrow().allyNames().isEmpty());
        // A disbanded clan must not leave its former ally unhittable by its members.
        assertFalse(f.store().pvpBlocked(f.b(), f.a()));

        ClanStore reloaded = new ClanStore(path);
        assertTrue(reloaded.clanOf(f.b()).orElseThrow().allyNames().isEmpty());
    }

    @Test
    void alliancesSurviveAReload() throws Exception {
        Path path = temporaryDirectory.resolve("clans.json");
        Founded f = founded(path);
        f.store().ally(f.a(), "BETA", 1_000);
        f.store().ally(f.b(), "ALPHA", 1_001);

        ClanStore reloaded = new ClanStore(path);
        assertTrue(reloaded.pvpBlocked(f.a(), f.b()));
        assertEquals(java.util.List.of("BETA"), reloaded.clanOf(f.a()).orElseThrow().allyNames());
    }

    @Test
    void aHalfWrittenAllianceIsDroppedRatherThanHonoured() throws Exception {
        Path path = temporaryDirectory.resolve("clans.json");
        Founded f = founded(path);
        f.store().ally(f.a(), "BETA", 1_000);
        f.store().ally(f.b(), "ALPHA", 1_001);

        // Simulate a crash between the two writes by deleting one half on disk.
        String saved = Files.readString(path);
        String betaId = f.store().clanOf(f.b()).orElseThrow().id().toString();
        String oneSided = saved.replaceFirst("(?s)\"allies\": \\[\\s*\"" + betaId + "\"\\s*\\]", "\"allies\": []");
        Files.writeString(path, oneSided);

        ClanStore reloaded = new ClanStore(path);
        // Neither direction may be protected: a truce nobody can see is worse than none.
        assertFalse(reloaded.pvpBlocked(f.a(), f.b()));
        assertFalse(reloaded.pvpBlocked(f.b(), f.a()));
        assertTrue(reloaded.clanOf(f.a()).orElseThrow().allyNames().isEmpty());
        assertTrue(reloaded.clanOf(f.b()).orElseThrow().allyNames().isEmpty());
    }

    @Test
    void unrelatedClansAreStillFairGame() throws Exception {
        Founded f = founded(temporaryDirectory.resolve("clans.json"));
        f.store().ally(f.a(), "BETA", 1_000);
        f.store().ally(f.b(), "ALPHA", 1_001);

        assertFalse(f.store().pvpBlocked(f.a(), f.c()));
        assertFalse(f.store().pvpBlocked(f.c(), f.d()));
        // An alliance is not transitive: allying with a clan does not ally you with
        // everyone they have allied.
        f.store().ally(f.b(), "GAMMA", 2_000);
        f.store().ally(f.c(), "BETA", 2_001);
        assertTrue(f.store().pvpBlocked(f.b(), f.c()));
        assertFalse(f.store().pvpBlocked(f.a(), f.c()));
    }

    @Test
    void clanlessPlayersAreNeverProtected() throws Exception {
        Founded f = founded(temporaryDirectory.resolve("clans.json"));
        UUID drifter = UUID.randomUUID();
        assertFalse(f.store().pvpBlocked(drifter, f.a()));
        assertFalse(f.store().pvpBlocked(f.a(), drifter));
        assertFalse(f.store().pvpBlocked(drifter, drifter));
    }
}
