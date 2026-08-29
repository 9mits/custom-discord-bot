package bot.mgx.accessbridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
        for (CosmeticCatalog.Definition definition : CosmeticCatalog.visualEntries()) {
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
    void everyPodiumRankHasOneUntradeableEffectInEveryCategory() {
        assertEquals(9, CosmeticCatalog.leaderboardRewards().size());
        for (int rank = 1; rank <= 3; rank++) {
            for (CosmeticCatalog.Category category : CosmeticCatalog.Category.values()) {
                CosmeticCatalog.Definition reward = CosmeticCatalog
                        .leaderboardReward(rank, category).orElseThrow();
                assertTrue(reward.leaderboardOnly());
                assertTrue(reward.description().contains("Only obtainable by holding #" + rank));
                assertTrue(reward.description().length() <= 115);
                assertFalse(CosmeticCatalog.all().contains(reward));
                assertTrue(CosmeticCatalog.find(reward.id()).isPresent());
            }
        }
    }

    @Test
    void clanBattleChampionAuraIsExclusiveAndPermanent() {
        CosmeticCatalog.Definition aura = CosmeticCatalog.find("galactic_conquest").orElseThrow();

        assertEquals(CosmeticCatalog.Category.AURA, aura.category());
        assertTrue(aura.clanBattleOnly());
        assertFalse(aura.leaderboardOnly());
        assertFalse(CosmeticCatalog.all().contains(aura));
        assertEquals("Clan Battle Champion", aura.rarityDisplay());
        assertTrue(aura.description().contains("Only obtainable from the Crates Clan Battle"));
    }

    @Test
    void everyEntryHasPlayerFacingMetadata() {
        for (CosmeticCatalog.Definition definition : CosmeticCatalog.visualEntries()) {
            assertFalse(definition.displayName().isBlank());
            assertFalse(definition.description().isBlank());
            assertFalse(definition.category().displayName().isBlank());
            assertFalse(definition.rarityDisplay().isBlank());
        }
        assertEquals("Rare", CosmeticCatalog.find("ember_trail").orElseThrow().rarityDisplay());
        assertEquals("Mythic", CosmeticCatalog.find("prismatic_trail").orElseThrow().rarityDisplay());
        assertEquals("Exotic", CosmeticCatalog.find("event_horizon").orElseThrow().rarityDisplay());
        assertEquals("Secret", CosmeticCatalog.find("iridescent_imperium")
                .orElseThrow().rarityDisplay());
    }

    @Test
    void crateCosmeticsExposeReciprocalOddsButPodiumRewardsDoNotPretendToBeRandom() {
        CosmeticCatalog.Definition common = CosmeticCatalog.find("ember_trail").orElseThrow();
        CosmeticCatalog.Definition secret = CosmeticCatalog.find("event_horizon").orElseThrow();
        CosmeticCatalog.Definition podium = CosmeticCatalog
                .leaderboardReward(1, CosmeticCatalog.Category.AURA).orElseThrow();

        assertEquals("1 in 20", common.oneInDisplay(false));
        assertEquals("1 in 33,333", secret.oneInDisplay(false));
        assertEquals("1 in ???", secret.oneInDisplay(true));
        assertTrue(podium.leaderboardOnly());
    }

    @Test
    void longLeaderboardDescriptionsWrapIntoACompactTooltip() {
        CosmeticCatalog.Definition reward = CosmeticCatalog
                .leaderboardReward(1, CosmeticCatalog.Category.AURA).orElseThrow();

        List<String> lines = CosmeticItems.wrapDescription(reward.description());

        assertTrue(lines.size() >= 3);
        assertEquals(reward.description(), String.join(" ", lines));
        assertTrue(lines.stream().allMatch(line -> line.length() <= 46));
    }

    @Test
    void wearablePreviewMaterialsDoNotLeakVanillaAttributes() {
        assertTrue(List.of(CosmeticItems.previewFlags()).contains(ItemFlag.HIDE_ATTRIBUTES));
    }

    @Test
    void firstThreeSerialNumbersUsePodiumMetalColours() {
        assertEquals(TextColor.color(0xFFD700), CosmeticItems.serialLine(1).children().getFirst().color());
        assertEquals(TextColor.color(0xC0C0C0), CosmeticItems.serialLine(2).children().getFirst().color());
        assertEquals(TextColor.color(0xCD7F32), CosmeticItems.serialLine(3).children().getFirst().color());
        assertEquals("Serial #1", PlainTextComponentSerializer.plainText()
                .serialize(CosmeticItems.serialLine(1)));
    }

    @Test
    void imperiumItemNameIsRainbowAndSecretPreviewsAreGlitched() {
        CosmeticCatalog.Definition imperium = CosmeticCatalog
                .find(CosmeticCatalog.HIDDEN_AMETHYST_COSMETIC_ID).orElseThrow();

        Component revealed = CosmeticItems.itemName(imperium, false);
        Component masked = CosmeticItems.itemName(imperium, true);

        assertEquals("Iridescent Imperium",
                PlainTextComponentSerializer.plainText().serialize(revealed));
        assertTrue(revealed.children().stream().map(Component::color).distinct().count() >= 5);
        assertEquals(TextDecoration.State.TRUE, masked.decoration(TextDecoration.OBFUSCATED));
    }

    /**
     * A trail is invisible while its owner stands still and a kill effect is invisible
     * until somebody dies, so neither may put a permanent line over a player's head.
     */
    @Test
    void onlyAurasEarnAnOddsTag() {
        List<CosmeticCatalog.Definition> tagged = CosmeticCatalog.visualEntries().stream()
                .filter(CosmeticCatalog.Definition::nameplateWorthy)
                .toList();

        assertFalse(tagged.isEmpty());
        for (CosmeticCatalog.Definition definition : tagged) {
            assertEquals(CosmeticCatalog.Category.AURA, definition.category(), definition.id());
        }
        // Rare enough to have qualified on rarity alone: a Secret trail and a Mythic
        // kill effect. Neither is what the line is reporting.
        assertFalse(CosmeticCatalog.find("galaxy_wake").orElseThrow().nameplateWorthy());
        assertFalse(CosmeticCatalog.find("soul_requiem").orElseThrow().nameplateWorthy());
        assertTrue(CosmeticCatalog.find("astral_sovereign").orElseThrow().nameplateWorthy());
    }

    /**
     * The fallback family exists so an unassigned cosmetic still renders, not so new
     * ones can quietly skip the decision. Every cosmetic that actually gets a floating
     * odds tag has to name the family its particles belong to.
     */
    @Test
    void oddsFamiliesCoverEveryTaggedCosmetic() {
        List<String> unassigned = CosmeticCatalog.visualEntries().stream()
                .filter(CosmeticCatalog.Definition::nameplateWorthy)
                .filter(definition -> definition.oddsFamily() == CosmeticCatalog.OddsFamily.ROYAL)
                .map(CosmeticCatalog.Definition::id)
                .toList();

        assertEquals(List.of(), unassigned);
    }

    @Test
    void everyOddsFamilyHasEnoughColoursForItsMotion() {
        for (CosmeticCatalog.OddsFamily family : CosmeticCatalog.OddsFamily.values()) {
            int[] colours = family.colours();
            assertTrue(colours.length >= 4, family + " has " + colours.length + " colours");
            for (int colour : colours) {
                assertTrue(colour >= 0 && colour <= 0xFFFFFF, family + " colour out of range");
            }
            assertFalse(family.glyph().isBlank(), family + " has no glyph");
        }
    }

    @Test
    void tagsOfDifferentFamiliesDoNotLookAlike() {
        CosmeticCatalog.Definition crown = CosmeticCatalog.find("celestial_crown").orElseThrow();
        CosmeticCatalog.Definition orbit = CosmeticCatalog.find("amethyst_orbit").orElseThrow();
        CosmeticCatalog.Definition imperium = CosmeticCatalog
                .find(CosmeticCatalog.HIDDEN_AMETHYST_COSMETIC_ID).orElseThrow();

        assertEquals(CosmeticCatalog.OddsFamily.CELESTIAL, crown.oddsFamily());
        assertEquals(CosmeticCatalog.OddsFamily.AMETHYST, orbit.oddsFamily());
        assertEquals(CosmeticCatalog.OddsFamily.GENUINE, imperium.oddsFamily());

        Set<TextColor> crownColours = coloursOf(CosmeticEffectService.rarityNameplate(crown, 0L));
        Set<TextColor> orbitColours = coloursOf(CosmeticEffectService.rarityNameplate(orbit, 0L));
        assertTrue(java.util.Collections.disjoint(crownColours, orbitColours));

        assertEquals("\u2605 1 IN 6,250 \u2605", PlainTextComponentSerializer.plainText()
                .serialize(CosmeticEffectService.rarityNameplate(crown, 0L)));
    }

    @Test
    void pulsingTagsShareOneColourPerFrameAndScrollingOnesDoNot() {
        CosmeticCatalog.Definition dominion = CosmeticCatalog
                .find("infernal_dominion").orElseThrow();
        CosmeticCatalog.Definition prismatic = CosmeticCatalog
                .find("prismatic_trail").orElseThrow();

        assertEquals(1, coloursOf(CosmeticEffectService.rarityNameplate(dominion, 4L)).size());
        assertTrue(coloursOf(CosmeticEffectService.rarityNameplate(prismatic, 4L)).size() > 1);
        assertNotEquals(
                coloursOf(CosmeticEffectService.rarityNameplate(dominion, 4L)),
                coloursOf(CosmeticEffectService.rarityNameplate(dominion, 12L))
        );
    }

    private static Set<TextColor> coloursOf(Component nameplate) {
        return nameplate.children().stream()
                .map(Component::color)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private static long in(CosmeticCatalog.Category category) {
        return CosmeticCatalog.all().stream()
                .filter(definition -> definition.category() == category)
                .count();
    }
}
