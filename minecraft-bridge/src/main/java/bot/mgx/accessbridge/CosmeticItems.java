package bot.mgx.accessbridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.ItemRarity;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Creates and recognises the unique bearer item for a cosmetic. */
final class CosmeticItems {
    private static final TextColor ORANGE = TextColor.color(0xFF9900);

    record TokenInfo(UUID serial, String cosmeticId, int generation) {
    }

    private final NamespacedKey cosmeticIdKey;
    private final NamespacedKey serialKey;
    private final NamespacedKey generationKey;

    CosmeticItems(MGXAccessBridge plugin) {
        cosmeticIdKey = new NamespacedKey(plugin, "cosmetic_id");
        serialKey = new NamespacedKey(plugin, "cosmetic_serial");
        generationKey = new NamespacedKey(plugin, "cosmetic_generation");
    }

    ItemStack token(CosmeticCatalog.Definition definition, CosmeticStore.Token token) {
        ItemStack item = preview(definition, false);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            throw new IllegalStateException("Cosmetic carrier has no item metadata.");
        }
        PersistentDataContainer data = meta.getPersistentDataContainer();
        data.set(cosmeticIdKey, PersistentDataType.STRING, definition.id());
        data.set(serialKey, PersistentDataType.STRING, token.serial().toString());
        data.set(generationKey, PersistentDataType.INTEGER, token.generation());
        List<Component> lore = new ArrayList<>(meta.lore() == null ? List.of() : meta.lore());
        lore.add(Component.empty());
        lore.add(line("Token " + token.serial().toString().substring(0, 8)));
        meta.lore(lore);
        meta.setMaxStackSize(1);
        item.setItemMeta(meta);
        return item;
    }

    ItemStack preview(CosmeticCatalog.Definition definition, boolean oddsScreen) {
        Material material = Material.matchMaterial(definition.materialName());
        if (material == null) {
            material = Material.BARRIER;
        }
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.displayName(Component.text(definition.displayName(), colour(definition), TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line(definition.secret() ? "Unknown Cosmetic" : definition.category().displayName()));
        lore.add(line(definition.description()));
        lore.add(Component.empty());
        lore.add(line("Rarity: " + definition.rarityDisplay()));
        if (oddsScreen) {
            lore.add(line("Chance: " + definition.displayedChance()));
        }
        meta.lore(lore);
        NamespacedKey model = NamespacedKey.fromString(definition.modelKey());
        if (model != null) {
            meta.setItemModel(model);
        }
        meta.setEnchantmentGlintOverride(true);
        meta.setRarity(rarity(definition));
        item.setItemMeta(meta);
        return item;
    }

    Optional<TokenInfo> read(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return Optional.empty();
        }
        PersistentDataContainer data = item.getItemMeta().getPersistentDataContainer();
        String cosmeticId = data.get(cosmeticIdKey, PersistentDataType.STRING);
        String rawSerial = data.get(serialKey, PersistentDataType.STRING);
        Integer generation = data.get(generationKey, PersistentDataType.INTEGER);
        if (cosmeticId == null || rawSerial == null || generation == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(new TokenInfo(UUID.fromString(rawSerial), cosmeticId, generation));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    boolean carries(Player player, UUID serial) {
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (matches(item, serial)) {
                return true;
            }
        }
        return matches(player.getInventory().getItemInOffHand(), serial);
    }

    Optional<ItemStack> carried(Player player, UUID serial) {
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (matches(item, serial)) {
                return Optional.of(item);
            }
        }
        ItemStack offhand = player.getInventory().getItemInOffHand();
        return matches(offhand, serial) ? Optional.of(offhand) : Optional.empty();
    }

    List<TokenInfo> carried(Player player) {
        List<TokenInfo> found = new ArrayList<>();
        for (ItemStack item : player.getInventory().getStorageContents()) {
            read(item).ifPresent(found::add);
        }
        read(player.getInventory().getItemInOffHand()).ifPresent(found::add);
        return List.copyOf(found);
    }

    boolean removeOne(Player player, UUID serial) {
        ItemStack[] storage = player.getInventory().getStorageContents();
        for (int index = 0; index < storage.length; index++) {
            if (!matches(storage[index], serial)) {
                continue;
            }
            storage[index] = null;
            player.getInventory().setStorageContents(storage);
            return true;
        }
        if (matches(player.getInventory().getItemInOffHand(), serial)) {
            player.getInventory().setItemInOffHand(null);
            return true;
        }
        return false;
    }

    private boolean matches(ItemStack item, UUID serial) {
        return read(item).map(info -> info.serial().equals(serial)).orElse(false);
    }

    private static Component line(String text) {
        return Component.text(text, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false);
    }

    private static TextColor colour(CosmeticCatalog.Definition definition) {
        if (definition.secret()) {
            return NamedTextColor.DARK_PURPLE;
        }
        return switch (definition.category()) {
            case KILL_EFFECT -> NamedTextColor.RED;
            case AURA -> NamedTextColor.GOLD;
            case TRAIL -> NamedTextColor.AQUA;
            case SECRET -> ORANGE;
        };
    }

    private static ItemRarity rarity(CosmeticCatalog.Definition definition) {
        if (definition.secret() || definition.weight() <= 100) {
            return ItemRarity.EPIC;
        }
        if (definition.weight() <= 750) {
            return ItemRarity.RARE;
        }
        return ItemRarity.UNCOMMON;
    }
}
