package bot.mgx.accessbridge;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Consumables only the Season Pass pays: strong for a while, then gone.
 *
 * <p>Permanent power is what breaks a server, so these are the pass's way of feeling
 * overpowered without being so: effects past what vanilla potions reach, but on timers,
 * and none of them helps win a fight. Each also has a job. The Rally Horn buffs everyone
 * near the player who sounds it, so it is worth saving for when friends are online; the
 * tonics make a long mining, building or ocean session feel fast.
 *
 * <p>Free of Bukkit so effects and names are unit tested. Ids are persisted on items, so
 * they never change once shipped.
 */
final class SeasonItemCatalog {
    /** One effect: its Bukkit registry name, the 0-based amplifier, and how long it lasts. */
    record Effect(String type, int amplifier, int seconds) {
        String describe() {
            String name = java.util.Arrays.stream(type.split("_"))
                    .map(word -> word.charAt(0) + word.substring(1).toLowerCase(Locale.ROOT))
                    .collect(java.util.stream.Collectors.joining(" "));
            String[] numerals = {"", "II", "III", "IV", "V"};
            String level = amplifier <= 0 ? "" : " " + (amplifier < numerals.length ? numerals[amplifier] : amplifier + 1);
            return name + level + " for " + (seconds >= 60 ? seconds / 60 + " min" : seconds + "s");
        }
    }

    enum Item {
        RALLY_HORN("rally_horn", "Rally Horn", "GOAT_HORN", "item/goat_horn", 0xFF9900, true,
                "Sound it to buff every player within 48 blocks.",
                List.of(new Effect("HASTE", 1, 300), new Effect("SPEED", 0, 300),
                        new Effect("REGENERATION", 0, 30))),
        MINERS_TONIC("miners_tonic", "Miner's Tonic", "POTION", "item/honey_bottle", 0xF2C14E, false,
                "Haste past what any beacon gives, for a long dig.",
                List.of(new Effect("HASTE", 2, 600), new Effect("NIGHT_VISION", 0, 600))),
        SKYWARD_TONIC("skyward_tonic", "Skyward Tonic", "POTION", "item/phantom_membrane", 0xBFE3FF, false,
                "Leap high and float down, for building and exploring.",
                List.of(new Effect("JUMP_BOOST", 1, 600), new Effect("SLOW_FALLING", 0, 600))),
        DEEPDIVER_TONIC("deepdiver_tonic", "Deepdiver Tonic", "POTION", "item/nautilus_shell", 0x2FB6C9, false,
                "Breathe, see, swim and dig underwater like a conduit.",
                List.of(new Effect("WATER_BREATHING", 0, 1_200), new Effect("CONDUIT_POWER", 0, 1_200),
                        new Effect("DOLPHINS_GRACE", 0, 600), new Effect("NIGHT_VISION", 0, 1_200)));

        /** Blocks around a sounded Rally Horn whose players share its effects. */
        static final double RALLY_RADIUS = 48.0;

        final String id;
        final String displayName;
        final String material;
        final String sprite;
        final int colour;
        /** Shared with nearby players rather than drunk alone. */
        final boolean shared;
        final String detail;
        final List<Effect> effects;

        Item(String id, String displayName, String material, String sprite, int colour, boolean shared,
                String detail, List<Effect> effects) {
            this.id = id;
            this.displayName = displayName;
            this.material = material;
            this.sprite = sprite;
            this.colour = colour;
            this.shared = shared;
            this.detail = detail;
            this.effects = effects;
        }
    }

    /** Effects a season consumable may never grant: each one decides fights. */
    static final List<String> COMBAT_EFFECTS = List.of(
            "STRENGTH", "RESISTANCE", "ABSORPTION", "HEALTH_BOOST", "INVISIBILITY", "INSTANT_HEALTH");

    private SeasonItemCatalog() {
    }

    static Optional<Item> find(String id) {
        if (id == null) return Optional.empty();
        String wanted = id.strip().toLowerCase(Locale.ROOT);
        for (Item item : Item.values()) {
            if (item.id.equals(wanted)) return Optional.of(item);
        }
        return Optional.empty();
    }
}
