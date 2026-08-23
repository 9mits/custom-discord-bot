package bot.mgx.accessbridge;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CosmeticCatalogTest {
    private static final Map<String, Integer> EXPECTED_WEIGHTS = Map.ofEntries(
            Map.entry("blood_burst", 2_500),
            Map.entry("frozen_shatter", 1_000),
            Map.entry("shining_light", 275),
            Map.entry("void_collapse", 82),
            Map.entry("soul_requiem", 28),
            Map.entry("solar_orbit", 2_000),
            Map.entry("crimson_orbit", 413),
            Map.entry("emerald_orbit", 220),
            Map.entry("amethyst_orbit", 82),
            Map.entry("celestial_crown", 16),
            Map.entry("ember_trail", 5_000),
            Map.entry("blood_trail", 1_000),
            Map.entry("frost_trail", 413),
            Map.entry("cherry_blossom_trail", 275),
            Map.entry("drool_trail", 220),
            Map.entry("ender_trail", 82),
            Map.entry("prismatic_trail", 8),
            Map.entry("event_horizon", 3),
            Map.entry("reapers_verdict", 3),
            Map.entry("divine_rupture", 3),
            Map.entry("astral_sovereign", 3),
            Map.entry("infernal_dominion", 3),
            Map.entry("abyssal_seraph", 3),
            Map.entry("galaxy_wake", 3),
            Map.entry("phantom_chains", 3),
            Map.entry("reality_fracture", 3)
    );

    @Test
    void exactCosmeticOddsArePinned() {
        Map<String, Integer> actual = new LinkedHashMap<>();
        CosmeticCatalog.all().forEach(definition -> actual.put(definition.id(), definition.weight()));

        assertEquals(EXPECTED_WEIGHTS, actual);
        assertEquals(13_641, actual.values().stream().mapToInt(Integer::intValue).sum());
    }

    @Test
    void everyPublicCosmeticIsBetweenFiveAndPointZeroZeroEightPercent() {
        for (CosmeticCatalog.Definition definition : CosmeticCatalog.publicEntries()) {
            assertTrue(definition.weight() >= 8, definition.id() + " is below 0.008%");
            assertTrue(definition.weight() <= 5_000, definition.id() + " is above 5.000%");
            assertFalse(definition.secret());
            assertTrue(definition.displayedChance().endsWith("%"));
        }
    }

    @Test
    void everyEffectCategoryHasThreeSecretsAtTheHiddenWeight() {
        List<CosmeticCatalog.Definition> secrets = CosmeticCatalog.all().stream()
                .filter(CosmeticCatalog.Definition::secret)
                .toList();

        assertEquals(9, secrets.size());
        assertEquals("???", CosmeticCatalog.MASKED_NAME);
        for (CosmeticCatalog.Definition secret : secrets) {
            assertEquals("???", secret.displayedChance());
            assertEquals(CosmeticCatalog.SECRET_WEIGHT, secret.weight());
            assertEquals("mgx:cosmetic/" + secret.id(), secret.modelKey());
            assertFalse(CosmeticCatalog.publicEntries().contains(secret));
            assertEquals("0.003%", CosmeticCatalog.percentage(secret.weight()));
        }
        for (CosmeticCatalog.Category category : List.of(
                CosmeticCatalog.Category.KILL_EFFECT,
                CosmeticCatalog.Category.AURA,
                CosmeticCatalog.Category.TRAIL
        )) {
            assertEquals(3, secrets.stream()
                    .filter(secret -> secret.category() == category)
                    .count(), category.name());
        }
    }

    @Test
    void aSecretIconIsMaskedOnlyUntilTheRewardIsRevealed() {
        CosmeticCatalog.Definition secret = CosmeticCatalog.find("event_horizon").orElseThrow();
        CosmeticCatalog.Definition publicCosmetic = CosmeticCatalog.find("blood_burst").orElseThrow();

        assertTrue(CosmeticItems.masksSecret(secret, true));
        assertEquals(CosmeticCatalog.MASKED_MODEL_KEY, CosmeticItems.previewModelKey(secret, true));
        assertEquals("BLACK_DYE", CosmeticItems.previewMaterialName(secret, true));

        assertFalse(CosmeticItems.masksSecret(secret, false));
        assertEquals(secret.modelKey(), CosmeticItems.previewModelKey(secret, false));
        assertEquals(secret.materialName(), CosmeticItems.previewMaterialName(secret, false));

        assertFalse(CosmeticItems.masksSecret(publicCosmetic, true));
        assertEquals(publicCosmetic.modelKey(), CosmeticItems.previewModelKey(publicCosmetic, true));
    }

    @Test
    void idsAndItemModelsAreUniqueAndStable() {
        Set<String> ids = CosmeticCatalog.all().stream()
                .map(CosmeticCatalog.Definition::id)
                .collect(Collectors.toSet());
        Set<String> models = CosmeticCatalog.all().stream()
                .map(CosmeticCatalog.Definition::modelKey)
                .collect(Collectors.toSet());

        assertEquals(EXPECTED_WEIGHTS.keySet(), ids);
        assertEquals(CosmeticCatalog.all().size(), models.size());
        for (CosmeticCatalog.Definition definition : CosmeticCatalog.all()) {
            assertEquals("mgx:cosmetic/" + definition.id(), definition.modelKey());
        }
    }

    @Test
    void everyIconMaterialExistsInTheTargetMinecraftApi() {
        for (CosmeticCatalog.Definition definition : CosmeticCatalog.all()) {
            Material material = Material.getMaterial(definition.materialName());
            assertTrue(material != null, definition.id() + " has no material");
            assertFalse(material.isLegacy(), definition.id() + " uses a legacy material");
        }
    }

    @Test
    void everyNormalCategoryHasSeveralChoices() {
        assertEquals(Set.of(
                CosmeticCatalog.Category.KILL_EFFECT,
                CosmeticCatalog.Category.AURA,
                CosmeticCatalog.Category.TRAIL
        ), Set.of(CosmeticCatalog.Category.values()));
        assertEquals(8, in(CosmeticCatalog.Category.KILL_EFFECT));
        assertEquals(8, in(CosmeticCatalog.Category.AURA));
        assertEquals(10, in(CosmeticCatalog.Category.TRAIL));
        assertEquals(17, CosmeticCatalog.publicEntries().size());
    }

    @Test
    void lookupIsStableButFriendlyToCommandInput() {
        CosmeticCatalog.Definition blood = CosmeticCatalog.find("blood_burst").orElseThrow();

        assertSame(blood, CosmeticCatalog.find("  BLOOD_BURST  ").orElseThrow());
        assertTrue(CosmeticCatalog.find(null).isEmpty());
        assertTrue(CosmeticCatalog.find("unknown").isEmpty());
    }

    @Test
    void everyEntryHasPlayerFacingMetadata() {
        for (CosmeticCatalog.Definition definition : CosmeticCatalog.all()) {
            assertFalse(definition.displayName().isBlank());
            assertFalse(definition.description().isBlank());
            assertFalse(definition.category().displayName().isBlank());
            assertFalse(definition.rarityDisplay().isBlank());
        }
        assertEquals("Rare", CosmeticCatalog.find("ember_trail").orElseThrow().rarityDisplay());
        assertEquals("Mythic", CosmeticCatalog.find("prismatic_trail").orElseThrow().rarityDisplay());
        assertEquals("Secret", CosmeticCatalog.find("event_horizon").orElseThrow().rarityDisplay());
    }

    private static long in(CosmeticCatalog.Category category) {
        return CosmeticCatalog.all().stream()
                .filter(definition -> definition.category() == category)
                .count();
    }
}
