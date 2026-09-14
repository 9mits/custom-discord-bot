package bot.mgx.accessbridge;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The cosmetics only one season's pass ever pays out.
 *
 * <p>Each season has a theme: a name, a palette, and one aura, trail and kill effect
 * worn in that palette. The shapes are shared between seasons and the colours are not,
 * which is what lets a Season 1 crown and a Season 3 crown be told apart across a spawn
 * while every set still ships in a single build. A theme is never reused, so a season's
 * set can only ever be earned during that season: past sets are what veterans show off.
 *
 * <p>Free of Bukkit so the ids, names and palettes are unit tested. IDs and model keys
 * are persisted on items, so they never change once shipped.
 */
final class SeasonCosmetics {
    /**
     * One season's look.
     *
     * @param primary the dominant colour, as 0xRRGGBB
     * @param secondary the shadow colour
     * @param highlight the bright accent
     */
    record Theme(
            int season, String name, int primary, int secondary, int highlight,
            String auraMaterial, String trailMaterial, String killMaterial,
            String auraName, String trailName, String killName, String mood
    ) { }

    /** Shipped themes, in season order. A season past the last one pays no cosmetic. */
    static final List<Theme> THEMES = List.of(
            new Theme(1, "Solstice", 0xFFB22E, 0xD9480F, 0xFFF1B8,
                    "SUNFLOWER", "BLAZE_POWDER", "GOLDEN_SWORD",
                    "Solstice Crown", "Solstice Wake", "Solstice Verdict",
                    "molten gold and white-hot sunlight"),
            new Theme(2, "Frostbound", 0x5CC4FF, 0x1F4FB8, 0xDDF4FF,
                    "BLUE_ICE", "SNOWBALL", "DIAMOND_SWORD",
                    "Frostbound Crown", "Frostbound Wake", "Frostbound Verdict",
                    "glacier blue and frozen starlight"),
            new Theme(3, "Verdant", 0x4FE08A, 0x167A47, 0xE4FFB8,
                    "EMERALD", "SLIME_BALL", "IRON_SWORD",
                    "Verdant Crown", "Verdant Wake", "Verdant Verdict",
                    "emerald growth and spring light"),
            new Theme(4, "Eclipse", 0xF03A5F, 0x4A1640, 0xFFB3C6,
                    "CRIMSON_FUNGUS", "REDSTONE", "NETHERITE_SWORD",
                    "Eclipse Crown", "Eclipse Wake", "Eclipse Verdict",
                    "blood-moon crimson and a black corona")
    );

    private static final List<CosmeticCatalog.Definition> DEFINITIONS = buildDefinitions();

    private SeasonCosmetics() {
    }

    static String id(int season, CosmeticCatalog.Category category) {
        return "season_" + season + "_" + switch (category) {
            case AURA -> "aura";
            case TRAIL -> "trail";
            case KILL_EFFECT -> "kill";
        };
    }

    static List<CosmeticCatalog.Definition> definitions() {
        return DEFINITIONS;
    }

    static Optional<Theme> theme(int season) {
        return THEMES.stream().filter(theme -> theme.season() == season).findFirst();
    }

    /** The theme a season cosmetic belongs to, or empty for every other cosmetic. */
    static Optional<Theme> themeOf(String cosmeticId) {
        if (cosmeticId == null || !cosmeticId.startsWith("season_")) return Optional.empty();
        String[] parts = cosmeticId.split("_");
        if (parts.length != 3) return Optional.empty();
        try {
            return theme(Integer.parseInt(parts[1]));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    static boolean isSeasonExclusive(String cosmeticId) {
        return themeOf(cosmeticId).isPresent();
    }

    /** This season's cosmetic in one category, if the season has a theme. */
    static Optional<CosmeticCatalog.Definition> forSeason(int season, CosmeticCatalog.Category category) {
        String wanted = id(season, category);
        return DEFINITIONS.stream().filter(definition -> definition.id().equals(wanted)).findFirst();
    }

    /** Reads {@code aura}, {@code trail}, {@code kill} and their longer spellings. */
    static Optional<CosmeticCatalog.Category> category(String raw) {
        if (raw == null) return Optional.empty();
        return switch (raw.strip().toLowerCase(Locale.ROOT).replace('-', '_')) {
            case "aura", "auras" -> Optional.of(CosmeticCatalog.Category.AURA);
            case "trail", "trails" -> Optional.of(CosmeticCatalog.Category.TRAIL);
            case "kill", "kill_effect", "kills" -> Optional.of(CosmeticCatalog.Category.KILL_EFFECT);
            default -> Optional.empty();
        };
    }

    private static List<CosmeticCatalog.Definition> buildDefinitions() {
        List<CosmeticCatalog.Definition> definitions = new ArrayList<>();
        for (Theme theme : THEMES) {
            String source = "Season " + theme.season() + " exclusive. ";
            definitions.add(definition(theme, CosmeticCatalog.Category.AURA, theme.auraName(),
                    theme.auraMaterial(), source + "Earned only at the final tier of the Season "
                            + theme.season() + " Pass. A star crown of " + theme.mood() + "."));
            definitions.add(definition(theme, CosmeticCatalog.Category.TRAIL, theme.trailName(),
                    theme.trailMaterial(), source + "Earned only from the Season " + theme.season()
                            + " Pass. Rifts of " + theme.mood() + " open behind every step."));
            definitions.add(definition(theme, CosmeticCatalog.Category.KILL_EFFECT, theme.killName(),
                    theme.killMaterial(), source + "Earned only from the Season " + theme.season()
                            + " Pass. Rays of " + theme.mood() + " crown the final blow."));
        }
        return List.copyOf(definitions);
    }

    private static CosmeticCatalog.Definition definition(
            Theme theme, CosmeticCatalog.Category category, String name, String material, String description
    ) {
        String id = id(theme.season(), category);
        return new CosmeticCatalog.Definition(id, name, category, 1, false, material,
                "mgx:cosmetic/" + id, description, 0);
    }
}
