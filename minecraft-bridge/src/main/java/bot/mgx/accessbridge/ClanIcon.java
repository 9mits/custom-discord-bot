package bot.mgx.accessbridge;

import org.bukkit.Material;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** The genuine Minecraft item icons a clan may use everywhere it is shown. */
enum ClanIcon {
    AMETHYST_SHARD("amethyst_shard", "Amethyst Shard", Material.AMETHYST_SHARD),
    DIAMOND("diamond", "Diamond", Material.DIAMOND),
    EMERALD("emerald", "Emerald", Material.EMERALD),
    GOLD_INGOT("gold_ingot", "Gold Ingot", Material.GOLD_INGOT),
    NETHERITE_INGOT("netherite_ingot", "Netherite Ingot", Material.NETHERITE_INGOT),
    NETHER_STAR("nether_star", "Nether Star", Material.NETHER_STAR),
    ENDER_PEARL("ender_pearl", "Ender Pearl", Material.ENDER_PEARL),
    HEART_OF_THE_SEA("heart_of_the_sea", "Heart of the Sea", Material.HEART_OF_THE_SEA),
    BLAZE_POWDER("blaze_powder", "Blaze Powder", Material.BLAZE_POWDER),
    ECHO_SHARD("echo_shard", "Echo Shard", Material.ECHO_SHARD),
    TOTEM_OF_UNDYING("totem_of_undying", "Totem of Undying", Material.TOTEM_OF_UNDYING),
    GOLDEN_APPLE("golden_apple", "Golden Apple", Material.GOLDEN_APPLE);

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
