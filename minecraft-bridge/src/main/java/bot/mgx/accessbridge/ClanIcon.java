package bot.mgx.accessbridge;

import org.bukkit.Material;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The genuine Minecraft item icons a clan may use everywhere it is shown.
 *
 * <p>Every id here names a real {@code item/<id>.png} in the client jar, which is what
 * makes {@link #sprite()} safe to derive rather than write out per entry. That is not a
 * rule about Minecraft -- a compass is animated and a shield is an entity model, so
 * neither has a flat item texture and neither is in this list. SpriteExistsTest checks
 * the whole set against a real client jar, so an addition that breaks the assumption
 * fails the build instead of shipping a magenta square.
 */
enum ClanIcon {
    AMETHYST_SHARD("amethyst_shard", "Amethyst Shard", Material.AMETHYST_SHARD),
    DIAMOND("diamond", "Diamond", Material.DIAMOND),
    EMERALD("emerald", "Emerald", Material.EMERALD),
    GOLD_INGOT("gold_ingot", "Gold Ingot", Material.GOLD_INGOT),
    NETHERITE_INGOT("netherite_ingot", "Netherite Ingot", Material.NETHERITE_INGOT),
    NETHER_STAR("nether_star", "Nether Star", Material.NETHER_STAR),
    ENDER_PEARL("ender_pearl", "Ender Pearl", Material.ENDER_PEARL),
    HEART_OF_THE_SEA("heart_of_the_sea", "Heart Of The Sea", Material.HEART_OF_THE_SEA),
    BLAZE_POWDER("blaze_powder", "Blaze Powder", Material.BLAZE_POWDER),
    ECHO_SHARD("echo_shard", "Echo Shard", Material.ECHO_SHARD),
    TOTEM_OF_UNDYING("totem_of_undying", "Totem Of Undying", Material.TOTEM_OF_UNDYING),
    GOLDEN_APPLE("golden_apple", "Golden Apple", Material.GOLDEN_APPLE),
    APPLE("apple", "Apple", Material.APPLE),
    ARMADILLO_SCUTE("armadillo_scute", "Armadillo Scute", Material.ARMADILLO_SCUTE),
    ARROW("arrow", "Arrow", Material.ARROW),
    BEETROOT("beetroot", "Beetroot", Material.BEETROOT),
    BLAZE_ROD("blaze_rod", "Blaze Rod", Material.BLAZE_ROD),
    BONE("bone", "Bone", Material.BONE),
    BOOK("book", "Book", Material.BOOK),
    BOW("bow", "Bow", Material.BOW),
    BREAD("bread", "Bread", Material.BREAD),
    BREEZE_ROD("breeze_rod", "Breeze Rod", Material.BREEZE_ROD),
    BRUSH("brush", "Brush", Material.BRUSH),
    BUCKET("bucket", "Bucket", Material.BUCKET),
    CAKE("cake", "Cake", Material.CAKE),
    CARROT("carrot", "Carrot", Material.CARROT),
    CHORUS_FRUIT("chorus_fruit", "Chorus Fruit", Material.CHORUS_FRUIT),
    COOKIE("cookie", "Cookie", Material.COOKIE),
    COPPER_INGOT("copper_ingot", "Copper Ingot", Material.COPPER_INGOT),
    DISC_FRAGMENT_5("disc_fragment_5", "Disc Fragment 5", Material.DISC_FRAGMENT_5),
    DRAGON_BREATH("dragon_breath", "Dragon Breath", Material.DRAGON_BREATH),
    EGG("egg", "Egg", Material.EGG),
    ELYTRA("elytra", "Elytra", Material.ELYTRA),
    ENCHANTED_BOOK("enchanted_book", "Enchanted Book", Material.ENCHANTED_BOOK),
    ENDER_EYE("ender_eye", "Ender Eye", Material.ENDER_EYE),
    EXPERIENCE_BOTTLE("experience_bottle", "Experience Bottle", Material.EXPERIENCE_BOTTLE),
    FEATHER("feather", "Feather", Material.FEATHER),
    FERMENTED_SPIDER_EYE("fermented_spider_eye", "Fermented Spider Eye", Material.FERMENTED_SPIDER_EYE),
    FILLED_MAP("filled_map", "Filled Map", Material.FILLED_MAP),
    FIRE_CHARGE("fire_charge", "Fire Charge", Material.FIRE_CHARGE),
    FIREWORK_ROCKET("firework_rocket", "Firework Rocket", Material.FIREWORK_ROCKET),
    FIREWORK_STAR("firework_star", "Firework Star", Material.FIREWORK_STAR),
    FLINT("flint", "Flint", Material.FLINT),
    FLINT_AND_STEEL("flint_and_steel", "Flint And Steel", Material.FLINT_AND_STEEL),
    GHAST_TEAR("ghast_tear", "Ghast Tear", Material.GHAST_TEAR),
    GLOW_BERRIES("glow_berries", "Glow Berries", Material.GLOW_BERRIES),
    GLOW_INK_SAC("glow_ink_sac", "Glow Ink Sac", Material.GLOW_INK_SAC),
    GLOWSTONE_DUST("glowstone_dust", "Glowstone Dust", Material.GLOWSTONE_DUST),
    GOAT_HORN("goat_horn", "Goat Horn", Material.GOAT_HORN),
    GUNPOWDER("gunpowder", "Gunpowder", Material.GUNPOWDER),
    HONEY_BOTTLE("honey_bottle", "Honey Bottle", Material.HONEY_BOTTLE),
    HONEYCOMB("honeycomb", "Honeycomb", Material.HONEYCOMB),
    INK_SAC("ink_sac", "Ink Sac", Material.INK_SAC),
    IRON_INGOT("iron_ingot", "Iron Ingot", Material.IRON_INGOT),
    LAPIS_LAZULI("lapis_lazuli", "Lapis Lazuli", Material.LAPIS_LAZULI),
    LAVA_BUCKET("lava_bucket", "Lava Bucket", Material.LAVA_BUCKET),
    LEAD("lead", "Lead", Material.LEAD),
    LEATHER("leather", "Leather", Material.LEATHER),
    MACE("mace", "Mace", Material.MACE),
    MAGMA_CREAM("magma_cream", "Magma Cream", Material.MAGMA_CREAM),
    MAP("map", "Map", Material.MAP),
    MILK_BUCKET("milk_bucket", "Milk Bucket", Material.MILK_BUCKET),
    MUSIC_DISC_OTHERSIDE("music_disc_otherside", "Music Disc Otherside", Material.MUSIC_DISC_OTHERSIDE),
    MUSIC_DISC_PIGSTEP("music_disc_pigstep", "Music Disc Pigstep", Material.MUSIC_DISC_PIGSTEP),
    NAME_TAG("name_tag", "Name Tag", Material.NAME_TAG),
    NAUTILUS_SHELL("nautilus_shell", "Nautilus Shell", Material.NAUTILUS_SHELL),
    NETHERITE_SCRAP("netherite_scrap", "Netherite Scrap", Material.NETHERITE_SCRAP),
    OMINOUS_BOTTLE("ominous_bottle", "Ominous Bottle", Material.OMINOUS_BOTTLE),
    OMINOUS_TRIAL_KEY("ominous_trial_key", "Ominous Trial Key", Material.OMINOUS_TRIAL_KEY),
    PAPER("paper", "Paper", Material.PAPER),
    PHANTOM_MEMBRANE("phantom_membrane", "Phantom Membrane", Material.PHANTOM_MEMBRANE),
    POPPED_CHORUS_FRUIT("popped_chorus_fruit", "Popped Chorus Fruit", Material.POPPED_CHORUS_FRUIT),
    POTATO("potato", "Potato", Material.POTATO),
    POWDER_SNOW_BUCKET("powder_snow_bucket", "Powder Snow Bucket", Material.POWDER_SNOW_BUCKET),
    PRISMARINE_CRYSTALS("prismarine_crystals", "Prismarine Crystals", Material.PRISMARINE_CRYSTALS),
    PRISMARINE_SHARD("prismarine_shard", "Prismarine Shard", Material.PRISMARINE_SHARD),
    PUMPKIN_PIE("pumpkin_pie", "Pumpkin Pie", Material.PUMPKIN_PIE),
    QUARTZ("quartz", "Quartz", Material.QUARTZ),
    RABBIT_FOOT("rabbit_foot", "Rabbit Foot", Material.RABBIT_FOOT),
    RABBIT_HIDE("rabbit_hide", "Rabbit Hide", Material.RABBIT_HIDE),
    REDSTONE("redstone", "Redstone", Material.REDSTONE),
    ROTTEN_FLESH("rotten_flesh", "Rotten Flesh", Material.ROTTEN_FLESH),
    SADDLE("saddle", "Saddle", Material.SADDLE),
    SHEARS("shears", "Shears", Material.SHEARS),
    SLIME_BALL("slime_ball", "Slime Ball", Material.SLIME_BALL),
    SPECTRAL_ARROW("spectral_arrow", "Spectral Arrow", Material.SPECTRAL_ARROW),
    SPIDER_EYE("spider_eye", "Spider Eye", Material.SPIDER_EYE),
    SPYGLASS("spyglass", "Spyglass", Material.SPYGLASS),
    SUGAR("sugar", "Sugar", Material.SUGAR),
    SWEET_BERRIES("sweet_berries", "Sweet Berries", Material.SWEET_BERRIES),
    TRIAL_KEY("trial_key", "Trial Key", Material.TRIAL_KEY),
    TRIDENT("trident", "Trident", Material.TRIDENT),
    TURTLE_SCUTE("turtle_scute", "Turtle Scute", Material.TURTLE_SCUTE),
    WATER_BUCKET("water_bucket", "Water Bucket", Material.WATER_BUCKET),
    WHEAT("wheat", "Wheat", Material.WHEAT),
    WIND_CHARGE("wind_charge", "Wind Charge", Material.WIND_CHARGE),
    WRITABLE_BOOK("writable_book", "Writable Book", Material.WRITABLE_BOOK);

    static final ClanIcon DEFAULT = AMETHYST_SHARD;
    private static final List<ClanIcon> CHOICES = List.copyOf(Arrays.asList(values()));

    private final String id;
    private final String label;
    private final Material material;

    ClanIcon(String id, String label, Material material) {
        this.id = id;
        this.label = label;
        this.material = material;
    }

    String id() {
        return id;
    }

    String label() {
        return label;
    }

    Material material() {
        return material;
    }

    String sprite() {
        return "item/" + id;
    }

    static List<ClanIcon> choices() {
        return CHOICES;
    }

    /** Matches on the label a player reads, not the id underneath it. */
    static List<ClanIcon> search(String query) {
        if (query == null || query.isBlank()) {
            return CHOICES;
        }
        String needle = query.strip().toLowerCase(Locale.ROOT);
        return CHOICES.stream()
                .filter(icon -> icon.label.toLowerCase(Locale.ROOT).contains(needle))
                .toList();
    }

    static Optional<ClanIcon> find(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String wanted = raw.strip();
        return CHOICES.stream()
                .filter(icon -> icon.id.equalsIgnoreCase(wanted)
                        || icon.name().equalsIgnoreCase(wanted))
                .findFirst();
    }

    static ClanIcon resolve(String raw) {
        return find(raw).orElse(DEFAULT);
    }
}
