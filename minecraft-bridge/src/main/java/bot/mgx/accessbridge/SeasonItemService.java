package bot.mgx.accessbridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Builds the Season Pass consumables and runs the one that needs code: the Rally Horn.
 * Tonics are real potions, so drinking them is vanilla. See {@link SeasonItemCatalog}.
 */
final class SeasonItemService implements Listener {
    private final MGXAccessBridge plugin;
    private final NamespacedKey itemKey;

    SeasonItemService(MGXAccessBridge plugin) {
        this.plugin = plugin;
        this.itemKey = new NamespacedKey(plugin, "season_item");
    }

    ItemStack create(SeasonItemCatalog.Item item, int amount) {
        Material material = Material.valueOf(item.material);
        ItemStack stack = new ItemStack(material, Math.max(1, Math.min(material.getMaxStackSize(), amount)));
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, item.id);
        TextColor colour = TextColor.color(item.colour);
        meta.displayName(Component.text(item.displayName, colour, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line(item.detail, NamedTextColor.GRAY));
        lore.add(Component.empty());
        // A potion's tooltip lists its own effects; the horn has nobody to do that for it.
        if (!(meta instanceof PotionMeta)) {
            for (SeasonItemCatalog.Effect effect : item.effects) lore.add(line("• " + effect.describe(), NamedTextColor.WHITE));
            lore.add(Component.empty());
        }
        lore.add(line(item.shared ? "Right-click to sound. Used up." : "Drink to use.", NamedTextColor.GRAY));
        lore.add(Component.text("Season Pass Reward", colour, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        meta.setEnchantmentGlintOverride(true);
        if (meta instanceof PotionMeta potion) {
            potion.setColor(Color.fromRGB(item.colour));
            for (SeasonItemCatalog.Effect effect : item.effects) {
                effectType(effect).ifPresent(type -> potion.addCustomEffect(
                        new PotionEffect(type, effect.seconds() * 20, effect.amplifier()), true));
            }
        }
        stack.setItemMeta(meta);
        return stack;
    }

    private Optional<PotionEffectType> effectType(SeasonItemCatalog.Effect effect) {
        PotionEffectType type = Registry.EFFECT.get(NamespacedKey.minecraft(effect.type().toLowerCase(Locale.ROOT)));
        if (type == null) plugin.getLogger().warning("Unknown season item effect " + effect.type());
        return Optional.ofNullable(type);
    }

    private Optional<SeasonItemCatalog.Item> itemOf(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) return Optional.empty();
        return SeasonItemCatalog.find(stack.getPersistentDataContainer().get(itemKey, PersistentDataType.STRING));
    }

    /** Sounding a Rally Horn buffs every player near it, including whoever blew it. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onHorn(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        ItemStack held = player.getInventory().getItemInMainHand();
        Optional<SeasonItemCatalog.Item> item = itemOf(held).filter(found -> found.shared);
        if (item.isEmpty()) return;
        // The vanilla horn would play its own sound and start a cooldown on every horn.
        event.setCancelled(true);
        // Buffing both sides of a duel mid-fight is not what a horn is for.
        if (VerificationLobbyService.isLobbyWorld(player.getWorld()) || plugin.inPvpDuel(player)) return;
        double radius = SeasonItemCatalog.Item.RALLY_RADIUS;
        List<Player> rallied = new ArrayList<>();
        for (Player nearby : player.getWorld().getPlayers()) {
            if (nearby.getLocation().distanceSquared(player.getLocation()) > radius * radius) continue;
            for (SeasonItemCatalog.Effect effect : item.get().effects) {
                effectType(effect).ifPresent(type -> nearby.addPotionEffect(
                        new PotionEffect(type, effect.seconds() * 20, effect.amplifier())));
            }
            rallied.add(nearby);
        }
        held.setAmount(held.getAmount() - 1);
        player.getWorld().playSound(player.getLocation(), Sound.EVENT_RAID_HORN, 2f, 1.2f);
        player.getWorld().spawnParticle(Particle.DUST, player.getLocation().add(0, 1.2, 0), 60, 1.5, 1, 1.5, 0,
                new Particle.DustOptions(Color.fromRGB(item.get().colour), 1.6f));
        String effects = item.get().effects.stream().map(SeasonItemCatalog.Effect::describe)
                .reduce((left, right) -> left + ", " + right).orElse("");
        Component message = Component.text("RALLY HORN » ", MenuText.ORANGE, TextDecoration.BOLD)
                .append(Component.text(player.getName() + " rallied " + rallied.size()
                        + (rallied.size() == 1 ? " player" : " players") + ": " + effects + ".", NamedTextColor.WHITE));
        rallied.forEach(nearby -> nearby.sendMessage(message));
    }

    private static Component line(String text, NamedTextColor colour) {
        return Component.text(text, colour).decoration(TextDecoration.ITALIC, false);
    }
}
