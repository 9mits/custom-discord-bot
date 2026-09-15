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

    /**
     * Everyone who plays a season can earn this gear, so it must never out-muscle the
     * rare Eternal set or give an edge in a fight. Checked against the source because
     * building an item needs a running server.
     */
    @Test
    void seasonGearStaysBelowEternalGearAndNeverHelpsInPvp() throws Exception {
        String service = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/bot/mgx/accessbridge/AmethystItemService.java"));
        String factory = service.substring(service.indexOf("ItemStack createSeasonGear("),
                service.indexOf("private Optional<SeasonGear.Piece> seasonPiece("));
        assertFalse(factory.contains("createTimed(") || factory.contains("kindKey"),
                "season gear must not inherit Amethyst abilities");
        String withoutWings = factory.replace(
                "if (piece == SeasonGear.Piece.WINGS) {\n            meta.setUnbreakable(true);", "");
        assertFalse(withoutWings.contains("setUnbreakable(true)"),
                "only the Wings never break; the tools wear out like normal gear");
        assertFalse(factory.contains("SHARPNESS, 6") || factory.contains("SHARPNESS, 7"),
                "no enchantment above the vanilla maximum");
        String scythe = service.substring(service.indexOf("public void onSeasonScythe("),
                service.indexOf("public void onSeasonSmelt("));
        assertTrue(scythe.contains("event.getEntity() instanceof Player"), "the mob bonus must skip players");
        assertTrue(SeasonGear.Piece.MOB_DAMAGE_BONUS <= 0.25);
        assertTrue(SeasonGear.Piece.TIMBER_LIMIT < 256, "smaller than the Amethyst Axe");
        String arrow = service.substring(service.indexOf("public void onSeasonArrow("),
                service.indexOf("/** Forge Touch"));
        assertTrue(arrow.contains("event.getEntity() instanceof Player") && arrow.contains("instanceof org.bukkit.entity.Enemy")
                        && arrow.contains("!(nearby instanceof Player)"),
                "Starfall must skip players, both the target and the burst");
        String fall = service.substring(service.indexOf("public void onSeasonFall("),
                service.indexOf("private void applySeasonHelmet("));
        assertTrue(fall.contains("inPvpDuel(player)") && fall.contains("inCombat(player)"),
                "Featherstep must stop working in a fight");
        assertTrue(SeasonGear.Piece.HARVEST_RADIUS < 3, "no bigger than a 5x5");
        String harvest = service.substring(service.indexOf("public void onSeasonHarvest("),
                service.indexOf("private static void replant("));
        assertFalse(harvest.contains(".breakNaturally("), "breakBlock keeps region protection in force");
    }

    @Test
    void relicCombatIsGentlerAgainstPlayers() {
        assertTrue(RelicItemService.PLAYER_LIFESTEAL < RelicItemService.MOB_LIFESTEAL);
        assertTrue(RelicItemService.LIFESTEAL_CAP <= 2.0, "never more than a heart per hit");
    }
}
