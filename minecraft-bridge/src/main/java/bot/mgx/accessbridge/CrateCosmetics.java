package bot.mgx.accessbridge;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The cosmetics that only the key-free crates roll: Dawnbreak in the Daily Crate and
 * Dreamdrift in the AFK Crate.
 *
 * <p>Their shapes are their own — a twin-gem orbit, a spiralling helix and a rising
 * shockwave — so they never read as a Season exclusive, and each set wears its crate's
 * palette. Free of Bukkit so ids, names and palettes are unit tested.
 */
final class CrateCosmetics {
    /** One crate's set. Colours are 0xRRGGBB: shadow, primary, highlight. */
    record Theme(
            String key, String name, int shadow, int primary, int highlight,
            String auraMaterial, String trailMaterial, String killMaterial, String mood
    ) { }

    static final Theme DAWNBREAK = new Theme("dawnbreak", "Dawnbreak", 0xC2185B, 0xFF8A3D, 0xFFE39A,
            "ORANGE_TULIP", "HONEYCOMB", "COPPER_INGOT", "a sunrise of coral and warm gold");
    static final Theme DREAMDRIFT = new Theme("dreamdrift", "Dreamdrift", 0x3B2A8C, 0x9D8CFF, 0xB8FFF4,
            "PHANTOM_MEMBRANE", "FEATHER", "ENDER_PEARL", "midnight violet and drifting starlight");
    static final List<Theme> THEMES = List.of(DAWNBREAK, DREAMDRIFT);

    /** Which existing icon each slot is re-coloured from, for the pack build. */
    static final String AURA_ICON_SOURCE = "amethyst_orbit";
    static final String TRAIL_ICON_SOURCE = "frost_trail";
    static final String KILL_ICON_SOURCE = "blood_burst";

    private static final List<CosmeticCatalog.Definition> DEFINITIONS = build();

    private CrateCosmetics() {
    }

    static String id(Theme theme, CosmeticCatalog.Category category) {
        return theme.key() + "_" + switch (category) {
            case AURA -> "aura";
            case TRAIL -> "trail";
            case KILL_EFFECT -> "kill";
        };
    }

    static List<CosmeticCatalog.Definition> definitions() {
        return DEFINITIONS;
    }

    static List<CosmeticCatalog.Definition> of(Theme theme) {
        return DEFINITIONS.stream().filter(definition -> definition.id().startsWith(theme.key() + "_")).toList();
    }

    static Optional<Theme> themeOf(String cosmeticId) {
        if (cosmeticId == null) return Optional.empty();
        return THEMES.stream()
                .filter(theme -> cosmeticId.equals(id(theme, CosmeticCatalog.Category.AURA))
                        || cosmeticId.equals(id(theme, CosmeticCatalog.Category.TRAIL))
                        || cosmeticId.equals(id(theme, CosmeticCatalog.Category.KILL_EFFECT)))
                .findFirst();
    }

    private static List<CosmeticCatalog.Definition> build() {
        List<CosmeticCatalog.Definition> definitions = new ArrayList<>();
        for (Theme theme : THEMES) {
            String source = theme == DAWNBREAK ? "Only from the Daily Crate. " : "Only from the AFK Crate. ";
            // The Daily Crate rolls once a day and the AFK Crate every hour, so the same set
            // would be forty times commoner in the AFK Crate at equal weights.
            boolean daily = theme == DAWNBREAK;
            definitions.add(definition(theme, CosmeticCatalog.Category.AURA, theme.name() + " Orbit",
                    theme.auraMaterial(), daily ? 500 : 60, source + "Twin gems circle you through " + theme.mood() + "."));
            definitions.add(definition(theme, CosmeticCatalog.Category.TRAIL, theme.name() + " Helix",
                    theme.trailMaterial(), daily ? 800 : 100, source + "A double helix of " + theme.mood() + " follows you."));
            definitions.add(definition(theme, CosmeticCatalog.Category.KILL_EFFECT, theme.name() + " Shockwave",
                    theme.killMaterial(), daily ? 800 : 100, source + "A ring of " + theme.mood() + " bursts from the kill."));
        }
        return List.copyOf(definitions);
    }

    private static CosmeticCatalog.Definition definition(
            Theme theme, CosmeticCatalog.Category category, String name, String material, int weight, String description
    ) {
        String id = id(theme, category);
        return new CosmeticCatalog.Definition(id, name, category, weight, false, material,
                "mgx:cosmetic/" + id, description, 0);
    }
}
