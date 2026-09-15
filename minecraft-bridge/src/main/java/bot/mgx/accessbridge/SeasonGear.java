package bot.mgx.accessbridge;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The gear only one season's pass pays, in that season's colours: a Scythe, Pickaxe, Axe,
 * Hoe and Bow for work and hunting, and Wings, a Helmet and Boots to wear.
 *
 * <p>Everyone who plays the season can earn these, so they are balanced as deserved
 * rewards rather than power: vanilla-maximum enchantments, normal durability (Mending
 * works), and one ability each that feels strong in the world but never adds an edge
 * against another player: bonuses land on mobs, crops and the environment, and the Boots
 * stop working the moment their wearer is in a fight.
 * They sit below the Eternal Amethyst gear, which stays the rare chase. What makes a
 * piece special is that its season's version never comes back.
 *
 * <p>Free of Bukkit so names, models and parsing are unit tested. Model keys are
 * persisted on items, so they never change once shipped.
 */
final class SeasonGear {
    enum Piece {
        SCYTHE("scythe", "Scythe", "sword", "NETHERITE_SWORD",
                "Reaper", "Deals 20% more damage to mobs"),
        PICKAXE("pickaxe", "Pickaxe", "pickaxe", "NETHERITE_PICKAXE",
                "Forge Touch", "Smelts the ores it mines"),
        AXE("axe", "Axe", "axe", "NETHERITE_AXE",
                "Timber", "Fells up to 32 connected logs"),
        WINGS("wings", "Wings", "elytra", "ELYTRA",
                "Never Breaks", "Shows this season's colours in flight"),
        BOOTS("boots", "Boots", "boots", "NETHERITE_BOOTS",
                "Featherstep", "No fall damage, except in a fight"),
        HOE("hoe", "Hoe", "hoe", "NETHERITE_HOE",
                "Bountiful", "Harvests and replants a 5x5 of crops"),
        HELMET("helmet", "Helmet", "helmet", "NETHERITE_HELMET",
                "Deepsight", "Night vision and water breathing while worn"),
        BOW("bow", "Bow", "bow", "BOW",
                "Starfall", "Hits on mobs burst onto hostile mobs nearby");

        /** Logs one swing of a Season Axe can fell. */
        static final int TIMBER_LIMIT = 32;
        /** Extra damage a Season Scythe or Bow deals to mobs, never to players. */
        static final double MOB_DAMAGE_BONUS = 0.20;
        /** Blocks from the struck crop a Season Hoe harvests: 2 is a 5x5. */
        static final int HARVEST_RADIUS = 2;
        /** Share of a Season Bow hit that bursts onto other hostile mobs, and how far. */
        static final double STARFALL_SPLASH = 0.5;
        static final double STARFALL_RADIUS = 3.0;

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

    /** The worn look the Helmet and Boots share, one per season. */
    static String armourKey(int season) {
        return "mgx:season_" + season + "_armor";
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
