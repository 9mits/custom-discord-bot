package bot.mgx.accessbridge;

import org.bukkit.Material;

/**
 * The {@code Material} face of {@link WealthValues}.
 *
 * <p>The figures themselves live there, keyed by name, so the clan vault can be
 * valued and unit-tested without Bukkit on the classpath.
 */
final class WealthTable {
    private WealthTable() {
    }

    static int valueOf(Material material) {
        return WealthValues.valueOf(material.name());
    }

    /** Every dyed variant of a shulker box counts as a plain one. */
    static int valueOfIncludingVariants(Material material) {
        return WealthValues.valueOfIncludingVariants(material.name());
    }
}
