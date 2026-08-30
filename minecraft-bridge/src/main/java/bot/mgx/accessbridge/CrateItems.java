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
    private static final TextColor AMETHYST = TextColor.color(0xB56CFF);
    private final NamespacedKey keyMarker;
    private final NamespacedKey shardMarker;
    private final NamespacedKey shardGrantMarker;
    private final NamespacedKey legacyKeyMarker;
    private final NamespacedKey rewardSpinMarker;
    private final NamespacedKey legacyRewardSpinMarker;
    private final CosmeticStore cosmeticStore;
    private final SpecialItemService specialItems;

    CrateItems(
            MGXAccessBridge plugin, CosmeticStore cosmeticStore, SpecialItemService specialItems
    ) {
        keyMarker = new NamespacedKey(plugin, "crate_key");
        shardMarker = new NamespacedKey(plugin, "shard");
        shardGrantMarker = new NamespacedKey(plugin, "shard_grant");
        legacyKeyMarker = new NamespacedKey(plugin, "lootbox_key");
        rewardSpinMarker = new NamespacedKey(plugin, "crate_reward_spin");
        legacyRewardSpinMarker = new NamespacedKey(plugin, "lootbox_reward_spin");
        this.cosmeticStore = cosmeticStore;
        this.specialItems = specialItems;
    }

    ItemStack shard(int amount) {
        ItemStack item = new ItemStack(Material.AMETHYST_SHARD, Math.max(1, Math.min(64, amount)));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("Shard", TextColor.color(0x53E5FF), TextDecoration.BOLD)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    Component.text("Extremely rare permanent crate currency.", NamedTextColor.LIGHT_PURPLE)
                            .decoration(TextDecoration.ITALIC, false),
                    line("Use it to open the Shard Crate.")
            ));
            meta.getPersistentDataContainer().set(shardMarker, PersistentDataType.BYTE, (byte) 1);
            NamespacedKey model = NamespacedKey.fromString("mgx:shard");
            if (model != null) {
                meta.setItemModel(model);
            }
            meta.setEnchantmentGlintOverride(true);
            item.setItemMeta(meta);
        }
        return item;
    }

    ItemStack shard(int amount, UUID grantId) {
        ItemStack item = shard(amount);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(
                    shardGrantMarker, PersistentDataType.STRING, grantId.toString()
            );
            item.setItemMeta(meta);
        }
        return item;
    }

    ItemStack key(int amount) {
        ItemStack item = new ItemStack(Material.TRIAL_KEY, Math.max(1, Math.min(64, amount)));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("Mysterious Crate Key", ORANGE, TextDecoration.BOLD)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    line("Default Crate: 1 key"),
                    line("Limited Amethyst Crate: 2 keys")
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

    boolean isShard(ItemStack item) {
        return item != null && !item.getType().isAir() && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer()
                .has(shardMarker, PersistentDataType.BYTE);
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

    int countShards(Player player) {
        int total = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (isShard(item)) {
                total += item.getAmount();
            }
        }
        ItemStack offHand = player.getInventory().getItemInOffHand();
        return isShard(offHand) ? total + offHand.getAmount() : total;
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

    int removeShards(Player player, int requested) {
        int remaining = Math.max(0, requested);
        ItemStack[] storage = player.getInventory().getStorageContents();
        for (int index = 0; index < storage.length && remaining > 0; index++) {
            ItemStack item = storage[index];
            if (!isShard(item)) {
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
            if (isShard(offHand)) {
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

    boolean carriesShardGrant(Player player, UUID grantId) {
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (shardGrant(item).filter(grantId::equals).isPresent()) {
                return true;
            }
        }
        return shardGrant(player.getInventory().getItemInOffHand()).filter(grantId::equals).isPresent();
    }

    void finishShardGrant(Player player, UUID grantId) {
        int recovered = 0;
        ItemStack[] storage = player.getInventory().getStorageContents();
        for (int index = 0; index < storage.length; index++) {
            if (shardGrant(storage[index]).filter(grantId::equals).isEmpty()) {
                continue;
            }
            recovered += storage[index].getAmount();
            storage[index] = null;
        }
        player.getInventory().setStorageContents(storage);
        ItemStack offHand = player.getInventory().getItemInOffHand();
        if (shardGrant(offHand).filter(grantId::equals).isPresent()) {
            recovered += offHand.getAmount();
            player.getInventory().setItemInOffHand(null);
        }
        for (int portion : StackSplit.portions(recovered, 64)) {
            player.getInventory().addItem(shard(portion)).values().forEach(overflow ->
                    player.getWorld().dropItemNaturally(player.getLocation(), overflow));
        }
    }

    boolean removeShardGrant(Player player, UUID grantId) {
        ItemStack[] storage = player.getInventory().getStorageContents();
        for (int index = 0; index < storage.length; index++) {
            if (shardGrant(storage[index]).filter(grantId::equals).isEmpty()) {
                continue;
            }
            storage[index] = null;
            player.getInventory().setStorageContents(storage);
            return true;
        }
        ItemStack offHand = player.getInventory().getItemInOffHand();
        if (shardGrant(offHand).filter(grantId::equals).isPresent()) {
            player.getInventory().setItemInOffHand(null);
            return true;
        }
        return false;
    }

    ItemStack preview(CrateCatalog.Reward reward, CosmeticItems cosmetics) {
        return preview(reward, cosmetics, false, null);
    }

    ItemStack oddsPreview(CrateCatalog.Reward reward, CosmeticItems cosmetics) {
        return preview(reward, cosmetics, false, null);
    }

    ItemStack oddsPreview(
            CrateCatalog.Reward reward, CosmeticItems cosmetics, String displayedChance
    ) {
        return preview(reward, cosmetics, false, displayedChance);
    }

    ItemStack revealedPreview(CrateCatalog.Reward reward, CosmeticItems cosmetics) {
        return preview(reward, cosmetics, true, null);
    }

    private ItemStack preview(
            CrateCatalog.Reward reward,
            CosmeticItems cosmetics,
            boolean revealSecret,
            String chanceOverride
    ) {
        if (reward.cosmetic()) {
            return CosmeticCatalog.find(reward.cosmeticId())
                    .map(definition -> withSupply(
                            cosmetics.preview(definition,
                                    !(revealSecret && definition.secret()), chanceOverride),
                            cosmeticStore.inExistence(definition.id())
                    ))
                    .orElseGet(() -> new ItemStack(Material.BARRIER));
        }
        Material material = Material.matchMaterial(reward.materialName());
        Optional<ItemStack> special = specialItems.create(reward);
        ItemStack item = special.orElseGet(
                () -> new ItemStack(material == null ? Material.BARRIER : material, reward.amount())
        );
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (special.isEmpty()) {
                meta.displayName(Component.text(reward.displayName(), ORANGE, TextDecoration.BOLD)
                        .decoration(TextDecoration.ITALIC, false));
            }
            List<Component> lore = new ArrayList<>(meta.lore() == null ? List.of() : meta.lore());
            addLimitedAmethystLore(lore, reward);
            if (!lore.isEmpty()) {
                lore.add(Component.empty());
            }
            lore.add(line(reward.category().displayName()));
            lore.add(line(reward.description()));
            lore.add(Component.empty());
            lore.add(line("Rarity: " + reward.rarityDisplay()));
            lore.add(line("Chance: " + (chanceOverride == null
                    ? reward.displayedChance() : chanceOverride)));
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    ItemStack reward(CrateCatalog.Reward reward, UUID spinId) {
        ItemStack item = reward(reward);
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

    ItemStack reward(CrateCatalog.Reward reward) {
        Material material = Material.matchMaterial(reward.materialName());
        if (material == null) {
            throw new IllegalStateException("Unknown crate material " + reward.materialName());
        }
        ItemStack item = specialItems.create(reward)
                .orElseGet(() -> new ItemStack(material, reward.amount()));
        ItemMeta meta = item.getItemMeta();
        if (meta != null && CrateCatalog.isExclusiveAmethyst(reward)) {
            List<Component> lore = new ArrayList<>(meta.lore() == null ? List.of() : meta.lore());
            addLimitedAmethystLore(lore, reward);
            meta.lore(lore);
            item.setItemMeta(meta);
        }
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

    /**
     * Commits a delivered reward by swapping the marked stack for a pristine one.
     *
     * <p>The marker is what survives a crash between handing the item over and
     * recording it, but it also makes the stack unlike every other stack of the same
     * item, so it lands in its own slot and never merges. Stripping the marker in place
     * left that split behind: ten openings meant ten slots of two diamonds. Re-adding a
     * clean stack instead lets the inventory merge it the way a normal pickup would.
     */
    void finishReward(Player player, UUID spinId, CrateCatalog.Reward reward) {
        ItemStack[] storage = player.getInventory().getStorageContents();
        int recovered = 0;
        for (int index = 0; index < storage.length; index++) {
            if (rewardSpin(storage[index]).filter(spinId::equals).isPresent()) {
                recovered += storage[index].getAmount();
                storage[index] = null;
            }
        }
        if (recovered <= 0) {
            return;
        }
        player.getInventory().setStorageContents(storage);
        ItemStack clean = reward(reward);
        for (int portion : StackSplit.portions(recovered, clean.getMaxStackSize())) {
            ItemStack stack = clean.clone();
            stack.setAmount(portion);
            // The slots the marked stacks vacated hold at least as much as goes back,
            // so anything left over is a bug worth dropping rather than deleting.
            player.getInventory().addItem(stack).values().forEach(overflow ->
                    player.getWorld().dropItemNaturally(player.getLocation(), overflow));
        }
    }

    /** A marked stack with no pending record left: strip it so it can stack again. */
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

    private Optional<UUID> shardGrant(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return Optional.empty();
        }
        String raw = item.getItemMeta().getPersistentDataContainer().get(
                shardGrantMarker, PersistentDataType.STRING
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

    private static void addLimitedAmethystLore(
            List<Component> lore, CrateCatalog.Reward reward
    ) {
        if (!CrateCatalog.isExclusiveAmethyst(reward)) {
            return;
        }
        Component provenance = Component.text(
                "Part of the Limited-Time Amethyst Crate", AMETHYST
        ).decoration(TextDecoration.ITALIC, false);
        if (lore.contains(provenance)) {
            return;
        }
        if (!lore.isEmpty() && !Component.empty().equals(lore.get(lore.size() - 1))) {
            lore.add(Component.empty());
        }
        lore.add(provenance);
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
