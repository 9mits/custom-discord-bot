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

/** Physical lootbox keys and neutral preview icons for the reel. */
final class LootboxItems {
    private static final TextColor ORANGE = TextColor.color(0xFF9900);
    private final NamespacedKey keyMarker;
    private final NamespacedKey rewardSpinMarker;

    LootboxItems(MGXAccessBridge plugin) {
        keyMarker = new NamespacedKey(plugin, "lootbox_key");
        rewardSpinMarker = new NamespacedKey(plugin, "lootbox_reward_spin");
    }

    ItemStack key(int amount) {
        ItemStack item = new ItemStack(Material.TRIAL_KEY, Math.max(1, Math.min(64, amount)));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("Mysterious Lootbox Key", ORANGE, TextDecoration.BOLD)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    line("One key opens one spin."),
                    line("Use /lootbox to open it."),
                    line("Tradable and not sold in /shop.")
            ));
            meta.getPersistentDataContainer().set(keyMarker, PersistentDataType.BYTE, (byte) 1);
            NamespacedKey model = NamespacedKey.fromString("mgx:lootbox_key");
            if (model != null) {
                meta.setItemModel(model);
            }
            meta.setEnchantmentGlintOverride(true);
            item.setItemMeta(meta);
        }
        return item;
    }

    boolean isKey(ItemStack item) {
        return item != null
                && !item.getType().isAir()
                && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer()
                        .has(keyMarker, PersistentDataType.BYTE);
    }

    int count(Player player) {
        int total = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (isKey(item)) {
                total += item.getAmount();
            }
        }
        return total;
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
        return false;
    }

    ItemStack preview(LootboxCatalog.Reward reward, CosmeticItems cosmetics) {
        if (reward.cosmetic()) {
            return CosmeticCatalog.find(reward.cosmeticId())
                    .map(definition -> cosmetics.preview(definition, true))
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

    ItemStack reward(LootboxCatalog.Reward reward, UUID spinId) {
        Material material = Material.matchMaterial(reward.materialName());
        if (material == null) {
            throw new IllegalStateException("Unknown lootbox material " + reward.materialName());
        }
        ItemStack item = new ItemStack(material, reward.amount());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            throw new IllegalStateException("Lootbox reward has no item metadata.");
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
        String raw = item.getItemMeta().getPersistentDataContainer().get(
                rewardSpinMarker, PersistentDataType.STRING
        );
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(raw));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private void clearRewardSpin(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        meta.getPersistentDataContainer().remove(rewardSpinMarker);
        item.setItemMeta(meta);
    }

    private static Component line(String text) {
        return Component.text(text, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false);
    }
}
