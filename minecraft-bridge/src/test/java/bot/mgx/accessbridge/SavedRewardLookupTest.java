package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every reward a crate can roll must be readable back from a saved reservation.
 *
 * <p>The hidden Amethyst Dragon Ascendant is rolled outside every published table, so the
 * fixed index never held it. A player who won it on production was told on every crate
 * open that their saved reward needed an administrator, while the win sat undeliverable.
 */
final class SavedRewardLookupTest {
    @Test
    void theHiddenDragonSecretCanBeDeliveredFromASavedWin() {
        CrateCatalog.Reward secret = CrateCatalog.cosmetic(
                CosmeticCatalog.hiddenDragonRewards().getFirst());
        assertEquals("cosmetic_amethyst_dragon_ascendant", secret.id());
        CrateCatalog.Reward found = CrateCatalog.findSaved(secret.id(), List.of()).orElseThrow();
        assertTrue(found.cosmetic());
        assertEquals(CosmeticCatalog.DRAGON_SECRET_COSMETIC_ID, found.cosmeticId());
    }

    @Test
    void everyRollableRewardInEveryCrateResolves() {
        for (CrateKind kind : CrateKind.values()) {
            for (CrateCatalog.Reward reward : CrateCatalog.effectiveRewards(kind, null)) {
                assertTrue(CrateCatalog.findSaved(reward.id(), List.of()).isPresent(),
                        kind + " can roll " + reward.id() + " but could never deliver it");
            }
        }
        CrateCatalog.Reward hiddenAmethyst = CrateCatalog.hiddenAmethystAt(0).orElseThrow();
        assertTrue(CrateCatalog.findSaved(hiddenAmethyst.id(), List.of()).isPresent());
    }

    @Test
    void anOwnerAddedRewardResolvesFromTheLiveTables() {
        CrateCatalog.Reward added = CrateCatalog.cosmetic(
                CosmeticCatalog.hiddenDragonRewards().getFirst());
        // Only reachable through the live tables once the fixed and full lists miss it.
        assertTrue(CrateCatalog.findSaved(added.id().toUpperCase(java.util.Locale.ROOT),
                List.of(added)).isPresent());
        assertTrue(CrateCatalog.findSaved("", List.of(added)).isEmpty());
    }
}
