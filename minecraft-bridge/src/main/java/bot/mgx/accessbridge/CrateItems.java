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
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Physical crate keys and neutral preview icons for the reel. */
final class CrateItems {
    private static final TextColor ORANGE = TextColor.color(0xFF9900);
    private final NamespacedKey keyMarker;
    private final NamespacedKey legacyKeyMarker;
    private final NamespacedKey rewardSpinMarker;
    private final NamespacedKey legacyRewardSpinMarker;
    private final CosmeticStore cosmeticStore;

    CrateItems(MGXAccessBridge plugin, CosmeticStore cosmeticStore) {
        keyMarker = new NamespacedKey(plugin, "crate_key");
        legacyKeyMarker = new NamespacedKey(plugin, "lootbox_key");
        rewardSpinMarker = new NamespacedKey(plugin, "crate_reward_spin");
        legacyRewardSpinMarker = new NamespacedKey(plugin, "lootbox_reward_spin");
        this.cosmeticStore = cosmeticStore;
    }

    ItemStack key(int amount) {
        ItemStack item = new ItemStack(Material.TRIAL_KEY, Math.max(1, Math.min(64, amount)));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("Mysterious Crate Key", ORANGE, TextDecoration.BOLD)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    line("Opens one crate with /crate."),
                    line("Not sellable. Drop it to trade it.")
            ));
            meta.getPersistentDataContainer().set(keyMarker, PersistentDataType.BYTE, (byte) 1);
            NamespacedKey model = NamespacedKey.fromString("mgx:crate_key");
            if (model != null) {
                meta.setItemModel(model);
            }
            meta.setEnchantmentGlintOverride(true);
            item.setItemMeta(meta);
        }
        return item;
    }

    boolean isKey(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return false;
        }
        var data = item.getItemMeta().getPersistentDataContainer();
        return data.has(keyMarker, PersistentDataType.BYTE)
                || data.has(legacyKeyMarker, PersistentDataType.BYTE);
    }

    int count(Player player) {
        int total = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (isKey(item)) {
                total += item.getAmount();
            }
        }
        ItemStack offHand = player.getInventory().getItemInOffHand();
        if (isKey(offHand)) {
            total += offHand.getAmount();
        }
        return total;
    }

    void upgradeLegacyKeys(Player player) {
        ItemStack[] storage = player.getInventory().getStorageContents();
        boolean changed = false;
        for (int index = 0; index < storage.length; index++) {
            ItemStack item = storage[index];
            if (!isKey(item) || item == null || !item.hasItemMeta()) {
                continue;
            }
            var data = item.getItemMeta().getPersistentDataContainer();
            if (data.has(keyMarker, PersistentDataType.BYTE)) {
                continue;
            }
            storage[index] = key(item.getAmount());
            changed = true;
        }
        if (changed) {
            player.getInventory().setStorageContents(storage);
        }
        ItemStack offHand = player.getInventory().getItemInOffHand();
        if (isLegacyKey(offHand)) {
            player.getInventory().setItemInOffHand(key(offHand.getAmount()));
        }
    }

    boolean consume(Player player) {
        ItemStack[] storage = player.getInventory().getStorageContents();
        for (int index = 0; index < storage.length; index++) {
            ItemStack item = storage[index];
            if (!isKey(item)) {
                continue;
            }
            if (item.getAmount() == 1) {
                storage[index] = null;
            } else {
                item.setAmount(item.getAmount() - 1);
            }
            player.getInventory().setStorageContents(storage);
            return true;
        }
        ItemStack offHand = player.getInventory().getItemInOffHand();
        if (isKey(offHand)) {
            if (offHand.getAmount() == 1) {
                player.getInventory().setItemInOffHand(null);
            } else {
                offHand.setAmount(offHand.getAmount() - 1);
            }
            return true;
        }
        return false;
    }

    int remove(Player player, int requested) {
        int remaining = Math.max(0, requested);
        ItemStack[] storage = player.getInventory().getStorageContents();
        for (int index = 0; index < storage.length && remaining > 0; index++) {
            ItemStack item = storage[index];
            if (!isKey(item)) {
                continue;
            }
            int removed = Math.min(remaining, item.getAmount());
            remaining -= removed;
            if (removed == item.getAmount()) {
                storage[index] = null;
            } else {
                item.setAmount(item.getAmount() - removed);
            }
        }
        player.getInventory().setStorageContents(storage);
        if (remaining > 0) {
            ItemStack offHand = player.getInventory().getItemInOffHand();
            if (isKey(offHand)) {
                int removed = Math.min(remaining, offHand.getAmount());
                remaining -= removed;
                if (removed == offHand.getAmount()) {
                    player.getInventory().setItemInOffHand(null);
                } else {
                    offHand.setAmount(offHand.getAmount() - removed);
                }
            }
        }
        return requested - remaining;
    }

    ItemStack preview(CrateCatalog.Reward reward, CosmeticItems cosmetics) {
        if (reward.cosmetic()) {
            return CosmeticCatalog.find(reward.cosmeticId())
                    .map(definition -> withSupply(
                            cosmetics.preview(definition, true),
                            cosmeticStore.inExistence(definition.id())
                    ))
                    .orElseGet(() -> new ItemStack(Material.BARRIER));
        }
        Material material = Material.matchMaterial(reward.materialName());
        ItemStack item = new ItemStack(material == null ? Material.BARRIER : material, reward.amount());
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(reward.displayName(), ORANGE, TextDecoration.BOLD)
                    .decoration(TextDecoration.ITALIC, false));
            List<Component> lore = new ArrayList<>();
            lore.add(line(reward.category().displayName()));
            lore.add(line(reward.description()));
            lore.add(Component.empty());
            lore.add(line("Rarity: " + reward.rarityDisplay()));
            lore.add(line("Chance: " + reward.displayedChance()));
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    ItemStack reward(CrateCatalog.Reward reward, UUID spinId) {
        Material material = Material.matchMaterial(reward.materialName());
        if (material == null) {
            throw new IllegalStateException("Unknown crate material " + reward.materialName());
        }
        ItemStack item = new ItemStack(material, reward.amount());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            throw new IllegalStateException("Crate reward has no item metadata.");
        }
        meta.getPersistentDataContainer().set(
                rewardSpinMarker, PersistentDataType.STRING, spinId.toString()
        );
        item.setItemMeta(meta);
        return item;
    }

    boolean carriesReward(Player player, UUID spinId) {
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (rewardSpin(item).filter(spinId::equals).isPresent()) {
                return true;
            }
        }
        return false;
    }

    boolean removeReward(Player player, UUID spinId) {
        ItemStack[] storage = player.getInventory().getStorageContents();
        for (int index = 0; index < storage.length; index++) {
            if (rewardSpin(storage[index]).filter(spinId::equals).isEmpty()) {
                continue;
            }
            storage[index] = null;
            player.getInventory().setStorageContents(storage);
            return true;
        }
        return false;
    }

    void finishReward(Player player, UUID spinId) {
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (rewardSpin(item).filter(spinId::equals).isPresent()) {
                clearRewardSpin(item);
            }
        }
    }

    void finishOrphanedRewards(Player player) {
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (rewardSpin(item).isPresent()) {
                clearRewardSpin(item);
            }
        }
    }

    private Optional<UUID> rewardSpin(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return Optional.empty();
        }
        var data = item.getItemMeta().getPersistentDataContainer();
        String raw = data.get(rewardSpinMarker, PersistentDataType.STRING);
        if (raw == null) {
            raw = data.get(legacyRewardSpinMarker, PersistentDataType.STRING);
        }
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(raw));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private boolean isLegacyKey(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return false;
        }
        var data = item.getItemMeta().getPersistentDataContainer();
        return data.has(legacyKeyMarker, PersistentDataType.BYTE)
                && !data.has(keyMarker, PersistentDataType.BYTE);
    }

    private void clearRewardSpin(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        meta.getPersistentDataContainer().remove(rewardSpinMarker);
        meta.getPersistentDataContainer().remove(legacyRewardSpinMarker);
        item.setItemMeta(meta);
    }

    private static Component line(String text) {
        return Component.text(text, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false);
    }

    private static ItemStack withSupply(ItemStack item, int supply) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        List<Component> lore = new ArrayList<>(meta.lore() == null ? List.of() : meta.lore());
        lore.add(line("In existence: " + supply));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }
}
