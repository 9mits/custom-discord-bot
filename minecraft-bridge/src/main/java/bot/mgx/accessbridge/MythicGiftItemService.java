package bot.mgx.accessbridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** The three permanent, extremely rare power items that only a Giftbag can hatch. */
final class MythicGiftItemService implements Listener {
    private static final TextColor IMPOSSIBLE = TextColor.color(0x53E5FF);

    private final MGXAccessBridge plugin;
    private final GameVariableStore variables;
    private final NamespacedKey kindKey;
    private final NamespacedKey serialKey;
    private final NamespacedKey chargesKey;

    MythicGiftItemService(MGXAccessBridge plugin, GameVariableStore variables) {
        this.plugin = plugin;
        this.variables = variables;
        this.kindKey = new NamespacedKey(plugin, "giftbag_mythic_item");
        this.serialKey = new NamespacedKey(plugin, "giftbag_mythic_serial");
        this.chargesKey = new NamespacedKey(plugin, "giftbag_mythic_charges");
        variables.onChange(key -> {
            if (!key.startsWith("season.giftbag.riftcleaver.")
                    && !key.startsWith("season.giftbag.worldcarver.")) return;
            plugin.getServer().getScheduler().runTask(plugin, this::refreshOnlineItems);
        });
    }

    ItemStack create(String id) {
        ItemStack item = create(id, UUID.randomUUID(), defaultCharges(id));
        SentinelHub.minted(SentinelEngine.Kind.MYTHIC_ITEM, 1L);
        return item;
    }

    ItemStack preview(String id) {
        return SentinelHub.quietly(() -> create(id, UUID.randomUUID(), defaultCharges(id)));
    }

    private ItemStack create(String id, UUID serial, int charges) {
        String normalized = id == null ? "" : id.toLowerCase(Locale.ROOT);
        Material material = switch (normalized) {
            case "riftcleaver" -> Material.NETHERITE_SWORD;
            case "worldcarver" -> Material.NETHERITE_PICKAXE;
            case "fatebound_idol" -> Material.TOTEM_OF_UNDYING;
            default -> throw new IllegalArgumentException("Unknown Giftbag mythic item " + id);
        };
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(kindKey, PersistentDataType.STRING, normalized);
        meta.getPersistentDataContainer().set(serialKey, PersistentDataType.STRING, serial.toString());
        if (normalized.equals("fatebound_idol")) {
            meta.getPersistentDataContainer().set(chargesKey, PersistentDataType.INTEGER, Math.max(1, charges));
        }
        item.setItemMeta(meta);
        refresh(item);
        return item;
    }

    void refresh(ItemStack item) {
        Optional<String> kind = idOf(item);
        if (kind.isEmpty()) return;
        ItemMeta meta = item.getItemMeta();
        meta.setUnbreakable(true);
        meta.setEnchantmentGlintOverride(true);
        switch (kind.get()) {
            case "riftcleaver" -> {
                int looting = variables.integer("season.giftbag.riftcleaver.looting-level");
                meta.displayName(name("✦ Riftcleaver ✦"));
                meta.lore(List.of(
                        lore("Permanent. Unbreakable. Impossible."),
                        lore("Deals " + formatMultiplier(variables.decimal("season.giftbag.riftcleaver.mob-damage-multiplier"))
                                + " total damage to mobs."),
                        lore("Looting " + looting + ". Never gains bonus damage against players."),
                        rarity()
                ));
                meta.addEnchant(Enchantment.SHARPNESS, 5, true);
                meta.addEnchant(Enchantment.SWEEPING_EDGE, 5, true);
                meta.removeEnchant(Enchantment.LOOTING);
                meta.addEnchant(Enchantment.LOOTING, looting, true);
            }
            case "worldcarver" -> {
                int efficiency = variables.integer("season.giftbag.worldcarver.efficiency-level");
                int fortune = variables.integer("season.giftbag.worldcarver.fortune-level");
                meta.displayName(name("✦ Worldcarver ✦"));
                meta.lore(List.of(
                        lore("Permanent. Unbreakable. Impossible."),
                        lore("Efficiency " + efficiency + " · Fortune " + fortune),
                        lore("A tool spoken about more often than it is seen."),
                        rarity()
                ));
                meta.removeEnchant(Enchantment.EFFICIENCY);
                meta.removeEnchant(Enchantment.FORTUNE);
                meta.addEnchant(Enchantment.EFFICIENCY, efficiency, true);
                meta.addEnchant(Enchantment.FORTUNE, fortune, true);
            }
            case "fatebound_idol" -> {
                int charges = Math.max(1, meta.getPersistentDataContainer()
                        .getOrDefault(chargesKey, PersistentDataType.INTEGER, defaultCharges(kind.get())));
                meta.displayName(name("✦ Fatebound Idol ✦"));
                meta.lore(List.of(
                        lore("Fate refuses to take you " + charges + (charges == 1 ? " time." : " times.")),
                        lore("Each resurrection consumes one sealed charge."),
                        lore("The last charge consumes the Idol."),
                        rarity()
                ));
            }
            default -> { return; }
        }
        item.setItemMeta(meta);
    }

    Optional<String> idOf(ItemStack item) {
        if (item == null || item.getType().isAir()) return Optional.empty();
        return Optional.ofNullable(item.getPersistentDataContainer().get(kindKey, PersistentDataType.STRING));
    }

    Optional<String> serial(ItemStack item) {
        if (idOf(item).isEmpty()) return Optional.empty();
        return Optional.ofNullable(item.getPersistentDataContainer().get(serialKey, PersistentDataType.STRING));
    }

    boolean isMythic(ItemStack item) {
        return idOf(item).isPresent();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)
                || !(event.getEntity() instanceof LivingEntity)
                || event.getEntity() instanceof Player
                || idOf(player.getInventory().getItemInMainHand()).filter("riftcleaver"::equals).isEmpty()) return;
        event.setDamage(event.getDamage() * Math.max(1d,
                variables.decimal("season.giftbag.riftcleaver.mob-damage-multiplier")));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onResurrect(EntityResurrectEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        EquipmentSlot hand = idolHand(player);
        if (hand == null) return;
        ItemStack idol = hand == EquipmentSlot.HAND
                ? player.getInventory().getItemInMainHand() : player.getInventory().getItemInOffHand();
        String serial = serial(idol).orElse("");
        int charges = idol.getPersistentDataContainer()
                .getOrDefault(chargesKey, PersistentDataType.INTEGER, defaultCharges("fatebound_idol"));
        if (charges <= 1 || serial.isBlank()) return;
        plugin.getServer().getScheduler().runTask(plugin,
                () -> restoreIdolCharge(player, serial, charges - 1, hand));
    }

    private void restoreIdolCharge(Player player, String serial, int remaining, EquipmentSlot hand) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (this.serial(item).filter(serial::equals).isEmpty()) continue;
            ItemMeta meta = item.getItemMeta();
            meta.getPersistentDataContainer().set(chargesKey, PersistentDataType.INTEGER, remaining);
            item.setItemMeta(meta);
            refresh(item);
            return;
        }
        ItemStack restored = create("fatebound_idol", UUID.fromString(serial), remaining);
        ItemStack held = hand == EquipmentSlot.HAND
                ? player.getInventory().getItemInMainHand() : player.getInventory().getItemInOffHand();
        if (held.getType().isAir()) {
            if (hand == EquipmentSlot.HAND) player.getInventory().setItemInMainHand(restored);
            else player.getInventory().setItemInOffHand(restored);
            return;
        }
        player.getInventory().addItem(restored).values()
                .forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
    }

    private EquipmentSlot idolHand(Player player) {
        if (idOf(player.getInventory().getItemInMainHand()).filter("fatebound_idol"::equals).isPresent()) {
            return EquipmentSlot.HAND;
        }
        if (idOf(player.getInventory().getItemInOffHand()).filter("fatebound_idol"::equals).isPresent()) {
            return EquipmentSlot.OFF_HAND;
        }
        return null;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        refresh(event.getPlayer());
    }

    @EventHandler
    public void onHeld(PlayerItemHeldEvent event) {
        ItemStack next = event.getPlayer().getInventory().getItem(event.getNewSlot());
        refresh(next);
    }

    private int defaultCharges(String id) {
        return id.equals("fatebound_idol")
                ? Math.max(1, variables.integer("season.giftbag.fatebound-idol.charges")) : 1;
    }

    private void refreshOnlineItems() {
        plugin.getServer().getOnlinePlayers().forEach(this::refresh);
    }

    private void refresh(Player player) {
        for (ItemStack item : player.getInventory().getContents()) refresh(item);
        for (ItemStack item : player.getEnderChest().getContents()) refresh(item);
    }

    private static Component name(String text) {
        return Component.text(text, IMPOSSIBLE, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false);
    }

    private static Component lore(String text) {
        return Component.text(text, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false);
    }

    private static Component rarity() {
        return Component.text("幻 IMPOSSIBLE · MYTHIC GIFTBAG ONLY", IMPOSSIBLE, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false);
    }

    private static String formatMultiplier(double value) {
        return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString() + "x";
    }
}
