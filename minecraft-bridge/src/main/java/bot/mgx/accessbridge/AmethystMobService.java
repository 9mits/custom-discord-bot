package bot.mgx.accessbridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

/** Converts ordinary zombie and skeleton spawns into visible Amethyst variants. */
final class AmethystMobService implements Listener {
    private static final String DISPLAY_TAG = "mgx_amethyst_mob_visual";
    private static final TextColor AMETHYST = TextColor.color(0xB56CFF);

    private final MGXAccessBridge plugin;
    private final CrateItems crateItems;
    private final RandomGenerator random;
    private final NamespacedKey marker;
    private final int oneIn;
    private final Map<UUID, ItemDisplay> visuals = new HashMap<>();
    private BukkitTask followTask;

    AmethystMobService(MGXAccessBridge plugin, CrateItems crateItems) {
        this(plugin, crateItems, ThreadLocalRandom.current());
    }

    AmethystMobService(
            MGXAccessBridge plugin, CrateItems crateItems, RandomGenerator random
    ) {
        this.plugin = plugin;
        this.crateItems = crateItems;
        this.random = random;
        marker = new NamespacedKey(plugin, "amethyst_mob");
        oneIn = Math.clamp(plugin.getConfig().getInt("amethyst-mobs.one-in", 5), 1, 10_000);
    }

    void start() {
        stop();
        clearStaleVisuals();
        for (World world : plugin.getServer().getWorlds()) {
            for (LivingEntity entity : world.getLivingEntities()) {
                if (isAmethyst(entity)) {
                    createVisual(entity);
                }
            }
        }
        followTask = plugin.getServer().getScheduler().runTaskTimer(
                plugin, this::follow, 1L, 1L
        );
    }

    void stop() {
        if (followTask != null) {
            followTask.cancel();
            followTask = null;
        }
        visuals.values().forEach(Entity::remove);
        visuals.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        LivingEntity entity = event.getEntity();
        if (!eligible(entity.getType(), event.getSpawnReason()) || random.nextInt(oneIn) != 0) {
            return;
        }
        mark(entity);
        plugin.getServer().getScheduler().runTask(plugin, () -> createVisual(entity));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (!isAmethyst(entity)) {
            return;
        }
        ItemDisplay visual = visuals.remove(entity.getUniqueId());
        if (visual != null) {
            visual.remove();
        }
        int keys = random.nextInt(1, 6);
        event.getDrops().add(crateItems.key(keys));
        event.getEntity().getWorld().spawnParticle(
                Particle.DUST, entity.getLocation().add(0d, 1d, 0d), 45,
                0.7d, 1d, 0.7d, 0d,
                new Particle.DustOptions(Color.fromRGB(181, 108, 255), 1.45f)
        );
        Player killer = entity.getKiller();
        if (killer != null) {
            killer.playSound(killer.getLocation(), Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 1f, 1.1f);
            ServerEvent.of(
                    "amethyst_mob_kill", ServerEvent.CATEGORY_COMBAT,
                    killer.getUniqueId(), killer.getName(), plugin::recordServerEvent
            ).summary(killer.getName() + " defeated an " + displayName(entity.getType()))
                    .detail("mob", entity.getType().name().toLowerCase())
                    .detail("keys", String.valueOf(keys))
                    .record();
        }
    }

    static boolean eligible(EntityType type, CreatureSpawnEvent.SpawnReason reason) {
        if (type != EntityType.ZOMBIE && type != EntityType.SKELETON) {
            return false;
        }
        return reason != CreatureSpawnEvent.SpawnReason.CUSTOM
                && reason != CreatureSpawnEvent.SpawnReason.COMMAND
                && reason != CreatureSpawnEvent.SpawnReason.SPAWNER_EGG
                && reason != CreatureSpawnEvent.SpawnReason.BUILD_IRONGOLEM
                && reason != CreatureSpawnEvent.SpawnReason.BUILD_SNOWMAN
                && reason != CreatureSpawnEvent.SpawnReason.BUILD_WITHER;
    }

    private void mark(LivingEntity entity) {
        entity.getPersistentDataContainer().set(marker, PersistentDataType.BYTE, (byte) 1);
        entity.customName(Component.text(displayName(entity.getType()), AMETHYST,
                TextDecoration.BOLD));
        entity.setCustomNameVisible(true);
        entity.setGlowing(true);
        equipFallback(entity);
    }

    private void equipFallback(LivingEntity entity) {
        EntityEquipment equipment = entity.getEquipment();
        if (equipment == null) {
            return;
        }
        if (equipment.getChestplate().getType().isAir()) {
            equipment.setChestplate(leather(Material.LEATHER_CHESTPLATE));
            equipment.setChestplateDropChance(0f);
        }
        if (equipment.getLeggings().getType().isAir()) {
            equipment.setLeggings(leather(Material.LEATHER_LEGGINGS));
            equipment.setLeggingsDropChance(0f);
        }
        if (equipment.getBoots().getType().isAir()) {
            equipment.setBoots(leather(Material.LEATHER_BOOTS));
            equipment.setBootsDropChance(0f);
        }
    }

    private static ItemStack leather(Material material) {
        ItemStack item = new ItemStack(material);
        LeatherArmorMeta meta = (LeatherArmorMeta) item.getItemMeta();
        meta.setColor(Color.fromRGB(139, 66, 201));
        meta.setEnchantmentGlintOverride(true);
        item.setItemMeta(meta);
        return item;
    }

    private void createVisual(LivingEntity entity) {
        if (!entity.isValid() || !isAmethyst(entity) || visuals.containsKey(entity.getUniqueId())) {
            return;
        }
        String model = entity.getType() == EntityType.ZOMBIE
                ? "mgx:amethyst_zombie" : "mgx:amethyst_skeleton";
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.setItemModel(NamespacedKey.fromString(model));
        item.setItemMeta(meta);
        ItemDisplay display = entity.getWorld().spawn(entity.getLocation(), ItemDisplay.class, visual -> {
            visual.setItemStack(item);
            visual.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            visual.setInterpolationDuration(1);
            visual.setTeleportDuration(1);
            visual.setViewRange(1.5f);
            visual.setTransformation(new Transformation(
                    new Vector3f(0f, 0f, 0f), new Quaternionf(),
                    new Vector3f(1f, 1f, 1f), new Quaternionf()
            ));
            visual.setPersistent(false);
            visual.addScoreboardTag(DISPLAY_TAG);
        });
        visuals.put(entity.getUniqueId(), display);
    }

    private void follow() {
        visuals.entrySet().removeIf(entry -> {
            LivingEntity mob = living(entry.getKey());
            ItemDisplay visual = entry.getValue();
            if (mob == null || !mob.isValid() || !visual.isValid()) {
                visual.remove();
                return true;
            }
            visual.teleport(mob.getLocation());
            if (plugin.getServer().getCurrentTick() % 10 == 0) {
                mob.getWorld().spawnParticle(
                        Particle.END_ROD, mob.getLocation().add(0d, 1d, 0d),
                        1, 0.35d, 0.65d, 0.35d, 0d
                );
            }
            return false;
        });
    }

    private LivingEntity living(UUID id) {
        Entity entity = plugin.getServer().getEntity(id);
        return entity instanceof LivingEntity living ? living : null;
    }

    private boolean isAmethyst(LivingEntity entity) {
        return entity.getPersistentDataContainer().has(marker, PersistentDataType.BYTE);
    }

    private void clearStaleVisuals() {
        for (World world : plugin.getServer().getWorlds()) {
            world.getEntities().stream()
                    .filter(entity -> entity.getScoreboardTags().contains(DISPLAY_TAG))
                    .forEach(Entity::remove);
        }
    }

    private static String displayName(EntityType type) {
        return type == EntityType.ZOMBIE ? "Amethyst Zombie" : "Amethyst Skeleton";
    }
}
