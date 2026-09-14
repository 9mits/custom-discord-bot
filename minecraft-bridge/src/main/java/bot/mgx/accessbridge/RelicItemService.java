package bot.mgx.accessbridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Relics from the key-free crates: one unusual ability each, and a step below Eternal
 * Amethyst gear. They wear out (Mending works), and every ability is a quality-of-life
 * trick rather than raw power, so a lucky Daily Crate cannot out-gear a Season Scythe.
 */
final class RelicItemService implements Listener {
    private static final int VEIN_LIMIT = 16;
    private static final BlockFace[] NEIGHBOURS = {
            BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
    };

    private final MGXAccessBridge plugin;
    private final NamespacedKey relicKey;
    private final NamespacedKey frostKey;
    private final Set<UUID> veinMining = new HashSet<>();

    RelicItemService(MGXAccessBridge plugin) {
        this.plugin = plugin;
        relicKey = new NamespacedKey(plugin, "relic");
        frostKey = new NamespacedKey(plugin, "relic_frost_arrow");
    }

    void start() {
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::lanternPulse, 40L, 40L);
    }

    ItemStack create(RelicCatalog.Relic relic) {
        ItemStack item = new ItemStack(Material.valueOf(relic.material));
        ItemMeta meta = item.getItemMeta();
        TextColor colour = TextColor.color(relic.colour);
        meta.displayName(Component.text(relic.displayName, colour, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));
        meta.getPersistentDataContainer().set(relicKey, PersistentDataType.STRING, relic.id);
        NamespacedKey model = NamespacedKey.fromString(relic.modelKey());
        if (model != null) meta.setItemModel(model);
        meta.addEnchant(Enchantment.UNBREAKING, 3, true);
        switch (relic) {
            case VEINSEEKER_PICKAXE, MAGNETITE_SHOVEL -> meta.addEnchant(Enchantment.EFFICIENCY, 5, true);
            case BLOODTHIRST_BLADE -> meta.addEnchant(Enchantment.SHARPNESS, 5, true);
            case FROSTBITE_BOW -> meta.addEnchant(Enchantment.POWER, 5, true);
            case VERDANT_SICKLE -> meta.addEnchant(Enchantment.EFFICIENCY, 4, true);
            case LANTERN_HELM, CLOUDSTRIDER_BOOTS -> {
                meta.addEnchant(Enchantment.PROTECTION, 4, true);
                if (relic == RelicCatalog.Relic.CLOUDSTRIDER_BOOTS) {
                    meta.addEnchant(Enchantment.FEATHER_FALLING, 4, true);
                }
                org.bukkit.inventory.meta.components.EquippableComponent equippable = meta.getEquippable();
                equippable.setSlot(relic == RelicCatalog.Relic.LANTERN_HELM ? EquipmentSlot.HEAD : EquipmentSlot.FEET);
                equippable.setModel(model);
                meta.setEquippable(equippable);
            }
        }
        meta.lore(List.of(
                line(relic.ability, colour),
                line(relic.detail, NamedTextColor.GRAY),
                Component.empty(),
                line("Relic  •  Mending works on it", NamedTextColor.DARK_AQUA)));
        item.setItemMeta(meta);
        return item;
    }

    Optional<RelicCatalog.Relic> relic(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return Optional.empty();
        return RelicCatalog.find(item.getItemMeta().getPersistentDataContainer().get(relicKey, PersistentDataType.STRING));
    }

    private boolean holding(Player player, RelicCatalog.Relic relic) {
        return relic(player.getInventory().getItemInMainHand()).filter(relic::equals).isPresent();
    }

    // ------------------------------------------------------------------ Veinseeker

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVein(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (!holding(player, RelicCatalog.Relic.VEINSEEKER_PICKAXE) || veinMining.contains(player.getUniqueId())) {
            return;
        }
        Block origin = event.getBlock();
        Material ore = origin.getType();
        if (!isOre(ore)) return;
        List<Block> vein = connected(origin, ore);
        if (vein.isEmpty()) return;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            veinMining.add(player.getUniqueId());
            try {
                for (Block block : vein) {
                    if (block.getType() != ore || !holding(player, RelicCatalog.Relic.VEINSEEKER_PICKAXE)) break;
                    player.breakBlock(block);
                }
            } finally {
                veinMining.remove(player.getUniqueId());
            }
            player.playSound(player, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.6f, 1.6f);
        });
    }

    /** The same-type ore touching {@code origin}, nearest first, never the origin itself. */
    private static List<Block> connected(Block origin, Material ore) {
        Deque<Block> queue = new ArrayDeque<>(List.of(origin));
        Set<Block> seen = new HashSet<>(List.of(origin));
        List<Block> found = new java.util.ArrayList<>();
        while (!queue.isEmpty() && found.size() < VEIN_LIMIT) {
            Block current = queue.poll();
            for (BlockFace face : NEIGHBOURS) {
                Block next = current.getRelative(face);
                if (next.getType() == ore && seen.add(next)) {
                    found.add(next);
                    queue.add(next);
                    if (found.size() >= VEIN_LIMIT) break;
                }
            }
        }
        return found;
    }

    private static boolean isOre(Material material) {
        return Tag.COAL_ORES.isTagged(material) || Tag.IRON_ORES.isTagged(material)
                || Tag.COPPER_ORES.isTagged(material) || Tag.GOLD_ORES.isTagged(material)
                || Tag.REDSTONE_ORES.isTagged(material) || Tag.LAPIS_ORES.isTagged(material)
                || Tag.DIAMOND_ORES.isTagged(material) || Tag.EMERALD_ORES.isTagged(material)
                || material == Material.NETHER_QUARTZ_ORE || material == Material.ANCIENT_DEBRIS;
    }

    // ------------------------------------------------------------------ Magnetite and Verdant

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrops(BlockDropItemEvent event) {
        Player player = event.getPlayer();
        if (holding(player, RelicCatalog.Relic.VERDANT_SICKLE)
                && event.getBlockState().getBlockData() instanceof Ageable crop
                && crop.getAge() >= crop.getMaximumAge()) {
            replant(event.getBlock(), event.getBlockState().getType());
            if (ThreadLocalRandom.current().nextInt(5) == 0) {
                for (Item drop : event.getItems()) {
                    ItemStack stack = drop.getItemStack();
                    stack.setAmount(Math.min(stack.getMaxStackSize(), stack.getAmount() * 2));
                    drop.setItemStack(stack);
                }
                event.getBlock().getWorld().spawnParticle(Particle.HAPPY_VILLAGER,
                        event.getBlock().getLocation().add(0.5, 0.6, 0.5), 8, 0.3, 0.3, 0.3, 0);
            }
        }
        if (holding(player, RelicCatalog.Relic.MAGNETITE_SHOVEL) && player.getGameMode() != GameMode.CREATIVE) {
            for (Item drop : List.copyOf(event.getItems())) {
                Map<Integer, ItemStack> left = player.getInventory().addItem(drop.getItemStack());
                if (left.isEmpty()) {
                    event.getItems().remove(drop);
                } else {
                    drop.setItemStack(left.values().iterator().next());
                }
            }
        }
    }

    private void replant(Block block, Material crop) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!block.getType().isAir()) return;
            Material below = block.getRelative(BlockFace.DOWN).getType();
            boolean soil = crop == Material.NETHER_WART ? below == Material.SOUL_SAND : below == Material.FARMLAND;
            if (!soil) return;
            block.setType(crop);
            if (block.getBlockData() instanceof Ageable planted) {
                planted.setAge(0);
                block.setBlockData(planted);
            }
        });
    }

    // ------------------------------------------------------------------ combat

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBloodthirst(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player) || !holding(player, RelicCatalog.Relic.BLOODTHIRST_BLADE)) {
            return;
        }
        if (!(event.getEntity() instanceof LivingEntity)) return;
        double heal = Math.min(3d, event.getFinalDamage() * 0.2d);
        var maxHealth = player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
        if (heal <= 0d || maxHealth == null) return;
        player.setHealth(Math.min(maxHealth.getValue(), player.getHealth() + heal));
        player.getWorld().spawnParticle(Particle.DUST, player.getLocation().add(0, 1.2, 0), 6, 0.3, 0.3, 0.3, 0,
                new Particle.DustOptions(Color.fromRGB(0xE0303F), 1f));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFrostShot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        if (relic(event.getBow()).filter(RelicCatalog.Relic.FROSTBITE_BOW::equals).isEmpty()) return;
        if (event.getProjectile() instanceof Projectile projectile) {
            projectile.getPersistentDataContainer().set(frostKey, PersistentDataType.BYTE, (byte) 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFrostHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Projectile projectile)
                || !projectile.getPersistentDataContainer().has(frostKey, PersistentDataType.BYTE)
                || !(event.getEntity() instanceof LivingEntity target)) {
            return;
        }
        target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 1));
        target.setFreezeTicks(Math.max(target.getFreezeTicks(), 100));
        target.getWorld().spawnParticle(Particle.SNOWFLAKE, target.getLocation().add(0, 1, 0), 14, 0.35, 0.5, 0.35, 0.02);
    }

    // ------------------------------------------------------------------ armour

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFall(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL || !(event.getEntity() instanceof Player player)) {
            return;
        }
        if (relic(player.getInventory().getBoots()).filter(RelicCatalog.Relic.CLOUDSTRIDER_BOOTS::equals).isPresent()) {
            event.setCancelled(true);
            player.getWorld().spawnParticle(Particle.CLOUD, player.getLocation(), 8, 0.3, 0.05, 0.3, 0.01);
        }
    }

    /** Night vision below the surface; short enough that it fades the moment the helm comes off. */
    private void lanternPulse() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (relic(player.getInventory().getHelmet()).filter(RelicCatalog.Relic.LANTERN_HELM::equals).isEmpty()) {
                continue;
            }
            boolean underground = player.getLocation().getBlockY() < 50
                    || player.getWorld().getEnvironment() != org.bukkit.World.Environment.NORMAL;
            if (underground) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, 260, 0, true, false, true));
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        veinMining.remove(event.getPlayer().getUniqueId());
    }

    private static Component line(String text, TextColor colour) {
        return Component.text(text, colour).decoration(TextDecoration.ITALIC, false);
    }
}
