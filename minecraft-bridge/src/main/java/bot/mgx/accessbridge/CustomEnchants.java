package bot.mgx.accessbridge;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The beyond-vanilla enchantments a crate book carries through an anvil.
 *
 * <p>A book records its own levels because vanilla will not: Unbreaking stops at III,
 * Fortune at III, Protection at IV, and the anvil clamps anything above that on sight.
 * One book used to carry one mark, so combining two of them handed the pair to vanilla
 * and a IV came back out as a III.
 *
 * <p>Free of Bukkit imports so the merge arithmetic is unit tested.
 */
final class CustomEnchants {
    /** Every mark a book may carry, and how high the crate lets it go. */
    static final Map<String, Integer> MAX_LEVEL = Map.of(
            "unbreaking", 5,
            "protection", 5,
            "fortune", 5,
            "excavation", 1
    );

    private CustomEnchants() {
    }

    /** Reads {@code "unbreaking:4,excavation:1"}, and the single-mark form that came first. */
    static Map<String, Integer> parse(String raw) {
        Map<String, Integer> parsed = new TreeMap<>();
        if (raw == null || raw.isBlank()) {
            return parsed;
        }
        for (String part : raw.split(",")) {
            String[] halves = part.trim().split(":", 2);
            if (halves.length != 2) {
                continue;
            }
            String id = halves[0].trim().toLowerCase(Locale.ROOT);
            Integer cap = MAX_LEVEL.get(id);
            if (cap == null) {
                continue;
            }
            try {
                int level = Integer.parseInt(halves[1].trim());
                if (level > 0) {
                    parsed.merge(id, Math.min(level, cap), Math::max);
                }
            } catch (NumberFormatException ignored) {
                // A mark nobody can read is a mark the anvil ignores.
            }
        }
        return parsed;
    }

    /** Sorted, so the same set of marks always writes the same string. */
    static String format(Map<String, Integer> enchants) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, Integer> entry : new TreeMap<>(enchants).entrySet()) {
            if (out.length() > 0) {
                out.append(',');
            }
            out.append(entry.getKey()).append(':').append(entry.getValue());
        }
        return out.toString();
    }

    /**
     * Vanilla's own rule, without vanilla's ceiling: two of the same level make the next
     * one up, anything else keeps the better of the two, and the crate's cap holds.
     */
    static Map<String, Integer> merge(Map<String, Integer> base, Map<String, Integer> addition) {
        Map<String, Integer> merged = new LinkedHashMap<>(new TreeMap<>(base));
        for (Map.Entry<String, Integer> entry : new TreeMap<>(addition).entrySet()) {
            String id = entry.getKey();
            int cap = MAX_LEVEL.getOrDefault(id, 1);
            Integer existing = merged.get(id);
            int level = existing == null
                    ? entry.getValue()
                    : (existing.equals(entry.getValue())
                            ? existing + 1
                            : Math.max(existing, entry.getValue()));
            merged.put(id, Math.max(1, Math.min(level, cap)));
        }
        return merged;
    }
}
