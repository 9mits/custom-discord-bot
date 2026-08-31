package bot.mgx.accessbridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Material;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What an owner has added to, or taken out of, the built-in catalogues.
 *
 * <p>Weights were always adjustable; the catalogue itself was not. Deciding a crate
 * should hand out copper, or that it should stop handing out netherite, meant editing
 * Java and shipping a build — which is the one thing the live-configuration work was
 * meant to remove.
 *
 * <p>Removing a built-in <em>disables</em> it rather than deleting it, so the decision is
 * always reversible; removing something an owner added deletes it, because there is
 * nothing underneath to fall back to. Only existence lives here. What a reward is worth
 * stays in {@link GameVariableStore}, so a custom entry gets the same validation,
 * history and rollback as a built-in one.
 */
final class CustomCatalogStore {
    /** A reward an owner added to one crate. */
    record CrateAddition(
            String id,
            String displayName,
            String category,
            String material,
            int amount,
            int weight,
            String description
    ) { }

    /** A material an owner added to the shared Airdrop loot table. */
    record LootAddition(
            String material,
            int minimumAmount,
            int maximumAmount,
            Map<String, Integer> weights
    ) { }

    private final Path file;
    private final Map<String, List<CrateAddition>> addedRewards = new LinkedHashMap<>();
    private final Map<String, Set<String>> disabledRewards = new LinkedHashMap<>();
    private final List<LootAddition> addedLoot = new ArrayList<>();
    private final Set<String> disabledLoot = new LinkedHashSet<>();
    private final List<Runnable> observers = new ArrayList<>();

    CustomCatalogStore(Path file) throws IOException {
        this.file = file;
        Files.createDirectories(file.getParent());
        load();
    }

    synchronized void onChange(Runnable observer) {
        if (observer != null) observers.add(observer);
    }

    synchronized List<CrateAddition> addedRewards(String kind) {
        return List.copyOf(addedRewards.getOrDefault(kind, List.of()));
    }

    synchronized Set<String> disabledRewards(String kind) {
        return Set.copyOf(disabledRewards.getOrDefault(kind, Set.of()));
    }

    synchronized List<LootAddition> addedLoot() {
        return List.copyOf(addedLoot);
    }

    synchronized Set<String> disabledLoot() {
        return Set.copyOf(disabledLoot);
    }

    /**
     * Adds a reward to one crate.
     *
     * <p>The identifier is checked against the built-ins as well as the additions: two
     * rewards sharing one id would share a weight variable, so editing either would
     * silently move both.
     */
    synchronized CrateAddition addReward(
            CrateKind kind, String rawId, String displayName, String category,
            String material, int amount, int weight, String description
    ) {
        String id = normalizeId(rawId);
        if (CrateCatalog.builtInIds(kind).contains(id)) {
            throw new IllegalArgumentException(
                    "The " + kind.displayName() + " already has a built-in reward called '" + id
                            + "'. Pick another name."
            );
        }
        if (addedRewards(kind.key()).stream().anyMatch(entry -> entry.id().equals(id))) {
            throw new IllegalArgumentException("You have already added a reward called '" + id + "'.");
        }
        CrateAddition addition = new CrateAddition(
                id,
                requireText(displayName, "A reward needs a name players will see."),
                normalizeCategory(category),
                requireItemMaterial(material),
                requireRange(amount, 1, 64, "Amount"),
                requireRange(weight, 1, 10_000_000, "Weight"),
                description == null ? "" : description.strip()
        );
        addedRewards.computeIfAbsent(kind.key(), ignored -> new ArrayList<>()).add(addition);
        saveAndNotify();
        return addition;
    }

    /**
     * Takes a reward out of a crate.
     *
     * <p>Refuses to remove the last one standing: an empty crate is not a balance
     * decision, it is an exception the next time somebody opens it.
     */
    synchronized void removeReward(CrateKind kind, String rawId) {
        String id = normalizeId(rawId);
        if (CrateCatalog.effectiveRewards(kind, this).size() <= 1) {
            throw new IllegalArgumentException(
                    "That is the only reward left in the " + kind.displayName()
                            + ". Add another before removing this one."
            );
        }
        List<CrateAddition> added = addedRewards.get(kind.key());
        if (added != null && added.removeIf(entry -> entry.id().equals(id))) {
            saveAndNotify();
            return;
        }
        if (!CrateCatalog.builtInIds(kind).contains(id)) {
            throw new IllegalArgumentException("The " + kind.displayName()
                    + " has no reward called '" + id + "'.");
        }
        disabledRewards.computeIfAbsent(kind.key(), ignored -> new LinkedHashSet<>()).add(id);
        saveAndNotify();
    }

    /** Puts a disabled built-in reward back. */
    synchronized void restoreReward(CrateKind kind, String rawId) {
        String id = normalizeId(rawId);
        Set<String> disabled = disabledRewards.get(kind.key());
        if (disabled == null || !disabled.remove(id)) {
            throw new IllegalArgumentException("'" + id + "' is not currently removed.");
        }
        saveAndNotify();
    }

    synchronized LootAddition addLoot(
            String material, int minimumAmount, int maximumAmount, Map<String, Integer> weights
    ) {
        String name = requireItemMaterial(material);
        if (AirdropCatalog.builtInLootMaterials().contains(name)
                || addedLoot.stream().anyMatch(entry -> entry.material().equals(name))) {
            throw new IllegalArgumentException(
                    name + " is already in the Airdrop loot table."
            );
        }
        if (minimumAmount > maximumAmount) {
            throw new IllegalArgumentException("The smallest amount cannot exceed the largest.");
        }
        Map<String, Integer> checked = new LinkedHashMap<>();
        for (AirdropCatalog.Rarity rarity : AirdropCatalog.Rarity.values()) {
            String key = rarity.name().toLowerCase(Locale.ROOT);
            checked.put(key, requireRange(
                    weights.getOrDefault(key, 0), 0, 10_000_000, rarity.displayName() + " weight"
            ));
        }
        if (checked.values().stream().allMatch(weight -> weight == 0)) {
            throw new IllegalArgumentException(
                    "Give this material a weight above zero in at least one rarity, or it can"
                            + " never be drawn."
            );
        }
        LootAddition addition = new LootAddition(
                name,
                requireRange(minimumAmount, 1, 1_000, "Smallest amount"),
                requireRange(maximumAmount, 1, 1_000, "Largest amount"),
                Map.copyOf(checked)
        );
        addedLoot.add(addition);
        saveAndNotify();
        return addition;
    }

    synchronized void removeLoot(String material) {
        String name = String.valueOf(material).strip().toUpperCase(Locale.ROOT);
        if (AirdropCatalog.effectiveLoot(this).size() <= 1) {
            throw new IllegalArgumentException(
                    "That is the only material left in the Airdrop loot table."
            );
        }
        if (addedLoot.removeIf(entry -> entry.material().equals(name))) {
            saveAndNotify();
            return;
        }
        if (!AirdropCatalog.builtInLootMaterials().contains(name)) {
            throw new IllegalArgumentException(name + " is not in the Airdrop loot table.");
        }
        disabledLoot.add(name);
        saveAndNotify();
    }

    synchronized void restoreLoot(String material) {
        String name = String.valueOf(material).strip().toUpperCase(Locale.ROOT);
        if (!disabledLoot.remove(name)) {
            throw new IllegalArgumentException(name + " is not currently removed.");
        }
        saveAndNotify();
    }

    /** Everything an owner has changed about the catalogues, for the console. */
    synchronized JsonObject snapshot() {
        JsonObject root = new JsonObject();
        JsonArray crates = new JsonArray();
        for (CrateKind kind : CrateKind.values()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("kind", kind.key());
            entry.addProperty("label", kind.displayName());
            JsonArray added = new JsonArray();
            for (CrateAddition addition : addedRewards(kind.key())) {
                JsonObject row = new JsonObject();
                row.addProperty("id", addition.id());
                row.addProperty("display_name", addition.displayName());
                row.addProperty("category", addition.category());
                row.addProperty("material", addition.material());
                row.addProperty("amount", addition.amount());
                row.addProperty("description", addition.description());
                added.add(row);
            }
            entry.add("added", added);
            JsonArray removed = new JsonArray();
            for (String id : disabledRewards(kind.key())) {
                JsonObject row = new JsonObject();
                row.addProperty("id", id);
                CrateCatalog.builtInReward(kind, id).ifPresent(reward -> {
                    row.addProperty("display_name", reward.displayName());
                    row.addProperty("material", reward.materialName());
                });
                removed.add(row);
            }
            entry.add("removed", removed);
            crates.add(entry);
        }
        root.add("crates", crates);

        JsonArray loot = new JsonArray();
        for (LootAddition addition : addedLoot()) {
            JsonObject row = new JsonObject();
            row.addProperty("material", addition.material());
            row.addProperty("minimum_amount", addition.minimumAmount());
            row.addProperty("maximum_amount", addition.maximumAmount());
            loot.add(row);
        }
        root.add("airdrop_loot_added", loot);
        JsonArray removedLoot = new JsonArray();
        disabledLoot().forEach(removedLoot::add);
        root.add("airdrop_loot_removed", removedLoot);
        return root;
    }

    private static String normalizeId(String raw) {
        String id = String.valueOf(raw).strip().toLowerCase(Locale.ROOT).replace(' ', '_');
        if (!id.matches("[a-z0-9_]{2,48}")) {
            throw new IllegalArgumentException(
                    "Use 2-48 letters, numbers or underscores for the reward's internal name."
            );
        }
        return id;
    }

    private static String normalizeCategory(String raw) {
        String name = String.valueOf(raw).strip().toUpperCase(Locale.ROOT);
        for (CrateCatalog.Category category : CrateCatalog.Category.values()) {
            if (category.name().equals(name)) {
                // Exotic is the hidden tier the reveal treats specially; an owner adding
                // an ordinary reward should not land in it by accident.
                if (category == CrateCatalog.Category.SECRET) {
                    throw new IllegalArgumentException(
                            "Exotic is reserved for the hidden rewards. Pick another group."
                    );
                }
                return category.name();
            }
        }
        throw new IllegalArgumentException("Unknown reward group '" + raw + "'.");
    }

    /**
     * A material that can actually exist as an item.
     *
     * <p>Checked in two stages, because they are not both available in the same places.
     * The name and the legacy flag are enum data and always readable. Whether something
     * is an <em>item</em> rather than a block-only state such as {@code WATER} goes
     * through {@code asItemType()} and therefore the registry, which exists only on a
     * running server — so off-server (tests, tooling) the enum check is as far as this
     * can go. That is the right way round: the strict check runs where an owner actually
     * adds a reward, and it refuses there rather than silently handing out nothing when
     * the crate is next opened.
     */
    private static String requireItemMaterial(String raw) {
        String name = String.valueOf(raw).strip().toUpperCase(Locale.ROOT);
        final Material material;
        try {
            material = Material.valueOf(name);
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("'" + raw + "' is not a Minecraft material.");
        }
        if (material.isLegacy()) {
            throw new IllegalArgumentException("'" + raw + "' is a legacy material.");
        }
        if (org.bukkit.Bukkit.getServer() != null && !material.isItem()) {
            throw new IllegalArgumentException(
                    "'" + raw + "' is not an item this server can hand out."
            );
        }
        return material.name();
    }

    private static String requireText(String value, String message) {
        String text = String.valueOf(value == null ? "" : value).strip();
        if (text.isEmpty() || text.length() > 64) {
            throw new IllegalArgumentException(message);
        }
        return text;
    }

    private static int requireRange(int value, int minimum, int maximum, String label) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    label + " must be between " + minimum + " and " + maximum + "."
            );
        }
        return value;
    }

    private void saveAndNotify() {
        save();
        observers.forEach(Runnable::run);
    }

    private void load() throws IOException {
        if (!Files.isRegularFile(file) || Files.size(file) == 0) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            for (JsonElement element : root.getAsJsonArray("crates")) {
                JsonObject entry = element.getAsJsonObject();
                String kind = entry.get("kind").getAsString();
                List<CrateAddition> added = new ArrayList<>();
                for (JsonElement raw : entry.getAsJsonArray("added")) {
                    JsonObject row = raw.getAsJsonObject();
                    added.add(new CrateAddition(
                            row.get("id").getAsString(),
                            row.get("display_name").getAsString(),
                            row.get("category").getAsString(),
                            row.get("material").getAsString(),
                            row.get("amount").getAsInt(),
                            row.get("weight").getAsInt(),
                            row.has("description") ? row.get("description").getAsString() : ""
                    ));
                }
                if (!added.isEmpty()) addedRewards.put(kind, added);
                Set<String> removed = new LinkedHashSet<>();
                for (JsonElement raw : entry.getAsJsonArray("removed")) {
                    removed.add(raw.getAsString());
                }
                if (!removed.isEmpty()) disabledRewards.put(kind, removed);
            }
            for (JsonElement element : root.getAsJsonArray("airdrop_loot_added")) {
                JsonObject row = element.getAsJsonObject();
                Map<String, Integer> weights = new LinkedHashMap<>();
                JsonObject stored = row.getAsJsonObject("weights");
                stored.keySet().forEach(key -> weights.put(key, stored.get(key).getAsInt()));
                addedLoot.add(new LootAddition(
                        row.get("material").getAsString(),
                        row.get("minimum_amount").getAsInt(),
                        row.get("maximum_amount").getAsInt(),
                        Map.copyOf(weights)
                ));
            }
            for (JsonElement element : root.getAsJsonArray("airdrop_loot_removed")) {
                disabledLoot.add(element.getAsString());
            }
        } catch (RuntimeException malformed) {
            throw new IOException("custom-catalog.json is unreadable", malformed);
        }
    }

    private void save() {
        JsonObject root = new JsonObject();
        JsonArray crates = new JsonArray();
        for (CrateKind kind : CrateKind.values()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("kind", kind.key());
            JsonArray added = new JsonArray();
            for (CrateAddition addition : addedRewards.getOrDefault(kind.key(), List.of())) {
                JsonObject row = new JsonObject();
                row.addProperty("id", addition.id());
                row.addProperty("display_name", addition.displayName());
                row.addProperty("category", addition.category());
                row.addProperty("material", addition.material());
                row.addProperty("amount", addition.amount());
                row.addProperty("weight", addition.weight());
                row.addProperty("description", addition.description());
                added.add(row);
            }
            entry.add("added", added);
            JsonArray removed = new JsonArray();
            disabledRewards.getOrDefault(kind.key(), Set.of()).forEach(removed::add);
            entry.add("removed", removed);
            crates.add(entry);
        }
        root.add("crates", crates);
        JsonArray loot = new JsonArray();
        for (LootAddition addition : addedLoot) {
            JsonObject row = new JsonObject();
            row.addProperty("material", addition.material());
            row.addProperty("minimum_amount", addition.minimumAmount());
            row.addProperty("maximum_amount", addition.maximumAmount());
            JsonObject weights = new JsonObject();
            addition.weights().forEach(weights::addProperty);
            row.add("weights", weights);
            loot.add(row);
        }
        root.add("airdrop_loot_added", loot);
        JsonArray removedLoot = new JsonArray();
        disabledLoot.forEach(removedLoot::add);
        root.add("airdrop_loot_removed", removedLoot);
        try {
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, root.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
