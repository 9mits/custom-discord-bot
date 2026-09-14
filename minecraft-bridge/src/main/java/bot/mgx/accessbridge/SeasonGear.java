package bot.mgx.accessbridge;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The permanent gear only one season's pass ever pays: a Scythe, a Pickaxe, an Axe and
 * Wings in that season's palette.
 *
 * <p>Each piece is an Eternal item underneath. Every Amethyst ability is keyed on the
 * item's kind, so a Season Pickaxe mines 3x3 and smelts, a Season Axe fells whole trees,
 * a Season Scythe carries the heavy sword bonus and Wings glide faster — with nothing
 * duplicated that could drift from the originals. On top of that each piece is netherite
 * where the Eternal is diamond, carries extra enchantments, and never comes back once its
 * season ends.
 *
 * <p>Free of Bukkit so names, models and parsing are unit tested. Model keys are
 * persisted on items, so they never change once shipped.
 */
final class SeasonGear {
    enum Piece {
        SCYTHE("scythe", "Scythe", "sword", "NETHERITE_SWORD",
                "Reaper's Edge", "Heavy bonus damage, lightning and a season sweep"),
        PICKAXE("pickaxe", "Pickaxe", "pickaxe", "NETHERITE_PICKAXE",
                "3x3 Mining", "Smelts what it breaks, with Fortune III"),
        AXE("axe", "Axe", "axe", "NETHERITE_AXE",
                "Timber", "Fells up to 256 connected logs"),
        WINGS("wings", "Wings", "elytra", "ELYTRA",
                "Lightning Speed", "Glides 50% faster");

        final String key;
        final String label;
        /** The Amethyst kind whose abilities this piece carries. */
        final String kind;
        final String material;
        final String ability;
        final String detail;

        Piece(String key, String label, String kind, String material, String ability, String detail) {
            this.key = key;
            this.label = label;
            this.kind = kind;
            this.material = material;
            this.ability = ability;
            this.detail = detail;
        }

        static Optional<Piece> parse(String raw) {
            if (raw == null) return Optional.empty();
            String wanted = raw.strip().toLowerCase(Locale.ROOT);
            for (Piece piece : values()) {
                if (piece.key.equals(wanted) || (piece == WINGS && wanted.equals("elytra"))) {
                    return Optional.of(piece);
                }
            }
            return Optional.empty();
        }
    }

    private SeasonGear() {
    }

    /** The item model, and for Wings also the equipment asset other players see worn. */
    static String modelKey(int season, Piece piece) {
        return "mgx:season_" + season + "_" + piece.key;
    }

    static String displayName(SeasonCosmetics.Theme theme, Piece piece) {
        return theme.name() + " " + piece.label;
    }

    /** Every shipped model key, for the resource-pack tests. */
    static List<String> modelKeys() {
        List<String> keys = new ArrayList<>();
        for (SeasonCosmetics.Theme theme : SeasonCosmetics.THEMES) {
            for (Piece piece : Piece.values()) keys.add(modelKey(theme.season(), piece));
        }
        return List.copyOf(keys);
    }
}
