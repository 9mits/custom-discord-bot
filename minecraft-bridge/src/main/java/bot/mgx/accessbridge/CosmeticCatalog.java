package bot.mgx.accessbridge;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The cosmetics a physical token can represent.
 *
 * <p>IDs and model keys are persisted on items, so changing either one is a data
 * migration rather than a copy edit. Chance weights use the same 100,000-part scale
 * as {@link CrateCatalog}: one point is {@code 0.001%}.
 */
final class CosmeticCatalog {
    static final int SECRET_WEIGHT = 5;

    enum Category {
        KILL_EFFECT("Kill Effects"),
        AURA("Auras"),
        TRAIL("Trails"),
        SECRET("Secret");

        private final String displayName;

        Category(String displayName) {
            this.displayName = displayName;
        }

        String displayName() {
            return displayName;
        }
    }

    record Definition(
            String id,
            String displayName,
            Category category,
            int weight,
            boolean secret,
            String materialName,
            String modelKey,
            String description
    ) {
        Definition {
            id = requireText(id, "Cosmetic ID").toLowerCase(Locale.ROOT);
            displayName = requireText(displayName, "Cosmetic display name");
            if (category == null) {
                throw new IllegalArgumentException("Cosmetic category is required");
            }
            if (weight <= 0) {
                throw new IllegalArgumentException("Cosmetic weight must be positive");
            }
            materialName = requireText(materialName, "Cosmetic material")
                    .toUpperCase(Locale.ROOT);
            modelKey = requireText(modelKey, "Cosmetic model key").toLowerCase(Locale.ROOT);
            description = requireText(description, "Cosmetic description");
            if (secret != (category == Category.SECRET)) {
                throw new IllegalArgumentException(
                        "Only the secret cosmetic may use the secret category"
                );
            }
        }

        String displayedChance() {
            return secret ? "???" : percentage(weight);
        }

        String rarityDisplay() {
            if (secret) {
                return "Secret";
            }
            if (weight >= 2_000) {
                return "Rare";
            }
            if (weight >= 500) {
                return "Epic";
            }
            if (weight >= 100) {
                return "Legendary";
            }
            return "Mythic";
        }
    }

    private static final List<Definition> DEFINITIONS = List.of(
            cosmetic(
                    "blood_burst", "Blood Burst", Category.KILL_EFFECT, 2_500,
                    "RED_DYE", "A crimson spray erupts where your opponent falls."
            ),
            cosmetic(
                    "frozen_shatter", "Frozen Shatter", Category.KILL_EFFECT, 1_000,
                    "LIGHT_BLUE_DYE", "Ice crystals burst outward with a brittle crack."
            ),
            cosmetic(
                    "shining_light", "Shining Light", Category.KILL_EFFECT, 500,
                    "GLOWSTONE_DUST", "White-gold rays and sparks mark the final hit."
            ),
            cosmetic(
                    "void_collapse", "Void Collapse", Category.KILL_EFFECT, 150,
                    "ENDER_EYE", "A dark portal folds in on the defeated player."
            ),
            cosmetic(
                    "soul_requiem", "Soul Requiem", Category.KILL_EFFECT, 50,
                    "SOUL_LANTERN", "Blue souls spiral upward and fade into silence."
            ),
            cosmetic(
                    "solar_orbit", "Solar Orbit", Category.AURA, 2_000,
                    "BLAZE_POWDER", "Warm gold motes circle the player like a small sun."
            ),
            cosmetic(
                    "crimson_orbit", "Crimson Orbit", Category.AURA, 750,
                    "REDSTONE", "Three crimson lights trace a steady orbit."
            ),
            cosmetic(
                    "emerald_orbit", "Emerald Orbit", Category.AURA, 400,
                    "EMERALD", "Green sparks weave around the player in two rings."
            ),
            cosmetic(
                    "amethyst_orbit", "Amethyst Orbit", Category.AURA, 150,
                    "AMETHYST_SHARD", "Violet shards glimmer along a slow helix."
            ),
            cosmetic(
                    "celestial_crown", "Celestial Crown", Category.AURA, 30,
                    "NETHER_STAR", "A crown of cold starlight turns above the player."
            ),
            cosmetic(
                    "ember_trail", "Ember Trail", Category.TRAIL, 5_000,
                    "BLAZE_POWDER", "Small orange embers linger behind every step."
            ),
            cosmetic(
                    "blood_trail", "Blood Trail", Category.TRAIL, 1_000,
                    "RED_DYE", "Dark red droplets briefly mark the path behind you."
            ),
            cosmetic(
                    "frost_trail", "Frost Trail", Category.TRAIL, 750,
                    "SNOWBALL", "Pale snow and ice dust follow your movement."
            ),
            cosmetic(
                    "cherry_blossom_trail", "Cherry Blossom Trail", Category.TRAIL, 500,
                    "PINK_PETALS", "Pink petals drift and settle behind you."
            ),
            cosmetic(
                    "drool_trail", "Drool Trail", Category.TRAIL, 400,
                    "SLIME_BALL", "Glossy aqua droplets inspired by Mysterious Girlfriend X."
            ),
            cosmetic(
                    "ender_trail", "Ender Trail", Category.TRAIL, 150,
                    "ENDER_PEARL", "Purple motes blink in and out along your path."
            ),
            cosmetic(
                    "prismatic_trail", "Prismatic Trail", Category.TRAIL, 15,
                    "PRISMARINE_CRYSTALS", "A shifting ribbon cycles through the full spectrum."
            ),
            new Definition(
                    "event_horizon",
                    "???",
                    Category.SECRET,
                    SECRET_WEIGHT,
                    true,
                    "BLACK_DYE",
                    "mgx:cosmetic/event_horizon",
                    "A black silhouette conceals its true effect."
            )
    );
    private static final Map<String, Definition> BY_ID = indexDefinitions();

    private CosmeticCatalog() {
    }

    static Optional<Definition> find(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_ID.get(id.strip().toLowerCase(Locale.ROOT)));
    }

    static List<Definition> all() {
        return DEFINITIONS;
    }

    /** The normal wardrobe index; the secret is represented separately by a silhouette. */
    static List<Definition> publicEntries() {
        return DEFINITIONS.stream().filter(definition -> !definition.secret()).toList();
    }

    static String percentage(int weight) {
        return String.format(Locale.ROOT, "%.3f%%", weight / 1_000.0d);
    }

    private static Definition cosmetic(
            String id,
            String displayName,
            Category category,
            int weight,
            String material,
            String description
    ) {
        return new Definition(
                id,
                displayName,
                category,
                weight,
                false,
                material,
                "mgx:cosmetic/" + id,
                description
        );
    }

    private static Map<String, Definition> indexDefinitions() {
        Map<String, Definition> indexed = new LinkedHashMap<>();
        for (Definition definition : DEFINITIONS) {
            if (indexed.putIfAbsent(definition.id(), definition) != null) {
                throw new IllegalStateException("Duplicate cosmetic ID " + definition.id());
            }
        }
        return Map.copyOf(indexed);
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.strip();
    }
}
