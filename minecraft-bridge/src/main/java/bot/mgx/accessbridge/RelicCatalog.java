package bot.mgx.accessbridge;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Relics: crate-only tools and armour with one unusual ability each.
 *
 * <p>Deliberately a step below Eternal Amethyst gear. A relic is diamond rather than
 * netherite, wears out like any tool (it can be mended), and does one clever thing instead
 * of several: follow a vein of ore, pull drops straight into the pack, drink blood, freeze
 * what it shoots, replant what it reaps, light the dark underground, or walk off a cliff.
 *
 * <p>Free of Bukkit so names, models and lore are unit tested. IDs and model keys are
 * persisted on items, so they never change once shipped.
 */
final class RelicCatalog {
    enum Relic {
        VEINSEEKER_PICKAXE("veinseeker_pickaxe", "Veinseeker Pickaxe", "DIAMOND_PICKAXE",
                "Vein Seeker", "Breaks up to 16 connected ores of the same kind", 0x3FD8C6),
        MAGNETITE_SHOVEL("magnetite_shovel", "Magnetite Shovel", "DIAMOND_SHOVEL",
                "Magnetised", "Everything it digs flies straight into your inventory", 0xC9CED6),
        BLOODTHIRST_BLADE("bloodthirst_blade", "Bloodthirst Blade", "DIAMOND_SWORD",
                "Bloodthirst", "Heals you for a fifth of the damage it deals", 0xE0303F),
        FROSTBITE_BOW("frostbite_bow", "Frostbite Bow", "BOW",
                "Frostbite", "Arrows slow and chill whatever they hit", 0x8FD8FF),
        VERDANT_SICKLE("verdant_sickle", "Verdant Sickle", "DIAMOND_HOE",
                "Green Thumb", "Replants every crop it reaps, sometimes twice as rich", 0x62E06A),
        LANTERN_HELM("lantern_helm", "Lantern Helm", "DIAMOND_HELMET",
                "Lantern Light", "Night vision while you are underground", 0xFFC247),
        CLOUDSTRIDER_BOOTS("cloudstrider_boots", "Cloudstrider Boots", "DIAMOND_BOOTS",
                "Cloudstride", "You never take fall damage", 0xBFD9FF);

        final String id;
        final String displayName;
        final String material;
        final String ability;
        final String detail;
        final int colour;

        Relic(String id, String displayName, String material, String ability, String detail, int colour) {
            this.id = id;
            this.displayName = displayName;
            this.material = material;
            this.ability = ability;
            this.detail = detail;
            this.colour = colour;
        }

        String modelKey() {
            return "mgx:" + id;
        }

        boolean armour() {
            return this == LANTERN_HELM || this == CLOUDSTRIDER_BOOTS;
        }
    }

    private RelicCatalog() {
    }

    static Optional<Relic> find(String id) {
        if (id == null) return Optional.empty();
        String wanted = id.strip().toLowerCase(Locale.ROOT);
        for (Relic relic : Relic.values()) {
            if (relic.id.equals(wanted)) return Optional.of(relic);
        }
        return Optional.empty();
    }

    static List<Relic> all() {
        return List.of(Relic.values());
    }
}
