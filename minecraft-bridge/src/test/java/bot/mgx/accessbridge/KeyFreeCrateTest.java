package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class KeyFreeCrateTest {
    @TempDir
    Path directory;

    @Test
    void theKeyFreeCratesUseOpeningsNotItems() {
        assertEquals(CrateKind.Currency.DAILY, CrateKind.DAILY.currency());
        assertEquals(CrateKind.Currency.AFK, CrateKind.AFK.currency());
        assertTrue(CrateKind.DAILY.currency().pass() && CrateKind.AFK.currency().pass());
        assertFalse(CrateKind.DEFAULT.currency().pass());
        assertFalse(CrateKind.DAILY.limited(), "the Daily Crate never closes");
    }

    @Test
    void bothTablesTotalExactlyOneHundredPercentWithUniqueIds() {
        for (CrateKind kind : new CrateKind[]{CrateKind.DAILY, CrateKind.AFK}) {
            assertEquals(CrateCatalog.TOTAL_WEIGHT,
                    kind.rewards().stream().mapToInt(CrateCatalog.Reward::weight).sum(), kind.name());
            Set<String> ids = new HashSet<>();
            for (CrateCatalog.Reward reward : kind.rewards()) {
                assertTrue(ids.add(reward.id()), reward.id());
                assertTrue(CrateCatalog.find(reward.id()).isPresent(), reward.id() + " must be findable");
            }
            for (RelicCatalog.Relic relic : RelicCatalog.all()) {
                assertTrue(kind.rewards().stream().anyMatch(reward -> reward.sourceId().equals(relic.id)),
                        kind + " is missing " + relic.id);
            }
        }
    }

    @Test
    void theDailyCrateIsRicherThanTheAfkCrate() {
        double daily = CrateKind.DAILY.advertisedRareRate();
        double afk = CrateKind.AFK.advertisedRareRate();
        assertTrue(daily > afk, "daily " + daily + " should beat afk " + afk);
        assertTrue(afk > 0.02 && daily < 0.12, "rare rates stay sensible: " + afk + " / " + daily);
    }

    @Test
    void eachKeyFreeCrateOwnsItsCosmeticSet() {
        for (CosmeticCatalog.Definition definition : CrateCosmetics.of(CrateCosmetics.DAWNBREAK)) {
            assertTrue(CrateKind.DAILY.rewards().stream().anyMatch(reward -> definition.id().equals(reward.cosmeticId())));
            assertFalse(CrateKind.AFK.rewards().stream().anyMatch(reward -> definition.id().equals(reward.cosmeticId())));
            assertTrue(CosmeticCatalog.find(definition.id()).isPresent());
        }
        assertEquals(3, CrateCosmetics.of(CrateCosmetics.DREAMDRIFT).size());
    }

    @Test
    void openingsBankUpToTheCapAndSurviveARestart() throws Exception {
        Path file = directory.resolve("crate-openings.json");
        CratePassStore store = new CratePassStore(file);
        UUID player = UUID.randomUUID();
        assertEquals(3, store.add(player, CratePassStore.Pass.DAILY, 3, 7));
        assertEquals(4, store.add(player, CratePassStore.Pass.DAILY, 9, 7), "only up to the cap");
        assertEquals(0, store.count(player, CratePassStore.Pass.AFK), "the two passes are separate");
        assertEquals(2, store.take(player, CratePassStore.Pass.DAILY, 2));
        store.refund(player, CratePassStore.Pass.DAILY, 5);
        assertEquals(10, new CratePassStore(file).count(player, CratePassStore.Pass.DAILY),
                "a refund is never refused by the cap, and everything is saved");
        assertEquals(10, store.take(player, CratePassStore.Pass.DAILY, 99));
    }

    @Test
    void relicsAreDistinctAndFindable() {
        Set<String> models = new HashSet<>();
        for (RelicCatalog.Relic relic : RelicCatalog.all()) {
            assertTrue(models.add(relic.modelKey()));
            assertEquals(relic, RelicCatalog.find(relic.id.toUpperCase()).orElseThrow());
            assertTrue(relic.material.startsWith("DIAMOND_") || relic.material.equals("BOW"),
                    "relics stay a step below netherite Eternal gear");
        }
        assertEquals(7, RelicCatalog.all().size());
    }
}
