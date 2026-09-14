package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SeasonCosmeticsTest {
    @Test
    void everyThemedSeasonHasOneExclusiveInEveryCategory() {
        for (SeasonCosmetics.Theme theme : SeasonCosmetics.THEMES) {
            for (CosmeticCatalog.Category category : CosmeticCatalog.Category.values()) {
                var definition = SeasonCosmetics.forSeason(theme.season(), category);
                assertTrue(definition.isPresent(), theme.name() + " " + category);
                assertEquals(category, definition.get().category());
                assertTrue(definition.get().displayName().startsWith(theme.name()));
                assertTrue(CosmeticCatalog.find(definition.get().id()).isPresent(),
                        "the wardrobe must be able to find " + definition.get().id());
            }
        }
        assertEquals(SeasonCosmetics.THEMES.size() * 3, SeasonCosmetics.definitions().size());
        assertTrue(SeasonCosmetics.forSeason(SeasonCosmetics.THEMES.size() + 1,
                CosmeticCatalog.Category.AURA).isEmpty(), "an unthemed season has no set");
    }

    @Test
    void exclusivesAreNeverRolledOrPriced() {
        for (CosmeticCatalog.Definition definition : SeasonCosmetics.definitions()) {
            assertTrue(definition.seasonExclusive());
            assertTrue(definition.rarityDisplay().matches("Season \\d+ Exclusive"), definition.rarityDisplay());
            assertFalse(CosmeticItems.showsReciprocalOdds(definition));
            assertFalse(CosmeticItems.showsExactChance(definition, true));
            assertFalse(CosmeticCatalog.publicEntries().contains(definition), "never in a crate");
            assertFalse(definition.nameplateWorthy(), "no odds tag for something without odds");
        }
        assertFalse(SeasonCosmetics.isSeasonExclusive("celestial_crown"));
        assertFalse(SeasonCosmetics.isSeasonExclusive("season_x_aura"));
    }

    @Test
    void themesNeverRepeatAColourOrAName() {
        Set<Integer> colours = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (SeasonCosmetics.Theme theme : SeasonCosmetics.THEMES) {
            assertTrue(colours.add(theme.primary()), theme.name());
            assertTrue(names.add(theme.name()), theme.name());
        }
    }

    @Test
    void everySeasonHasEveryGearPieceWithADistinctModel() {
        assertEquals(SeasonCosmetics.THEMES.size() * SeasonGear.Piece.values().length,
                new HashSet<>(SeasonGear.modelKeys()).size());
        assertEquals(SeasonGear.Piece.WINGS, SeasonGear.Piece.parse("elytra").orElseThrow());
        assertEquals("sword", SeasonGear.Piece.SCYTHE.kind, "the scythe carries the sword abilities");
        assertEquals("Solstice Scythe", SeasonGear.displayName(SeasonCosmetics.THEMES.getFirst(),
                SeasonGear.Piece.SCYTHE));
    }
}
