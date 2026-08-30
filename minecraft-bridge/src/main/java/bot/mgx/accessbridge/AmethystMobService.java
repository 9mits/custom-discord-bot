package bot.mgx.accessbridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Husk;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.Stray;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

/**
 * Turns a share of ordinary zombie and skeleton spawns into Amethyst variants.
 *
 * <p>The variants are real husks and strays. Those are the only zombie and skeleton
 * subtypes whose vanilla texture files match the mod art's layout exactly — husk.png is
 * 64x64 like the amethyst zombie skin, stray.png is 64x32 like the amethyst skeleton
 * skin — so the resource pack simply replaces those two files and the vanilla mob
 * renderer does the rest. Walking, arm swing, head tracking, hurt and death all animate
 * because nothing here is a display prop; the mob is the mob. An earlier attempt rode an
 * ItemDisplay on an invisible zombie, which could never animate and tipped over whenever
 * the mob looked up.
 *
 * <p>Because the pack retextures every husk and stray, this service also has to own them:
 * natural husk and stray spawns are turned back into the plain mob they stand in for, so
 * the amethyst skin never appears on something that is not an amethyst mob.
 */
final class AmethystMobService implements Listener {
    private static final TextColor AMETHYST = TextColor.color(0xB56CFF);
    private static final String DISPLAY_TAG = "mgx_amethyst_mob_visual";

    private final MGXAccessBridge plugin;
    private final CrateItems crateItems;
    private final RandomGenerator random;
    private final NamespacedKey marker;
    private final int oneIn;

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
        clearLegacyVisuals();
        for (World world : plugin.getServer().getWorlds()) {
            for (LivingEntity entity : List.copyOf(world.getLivingEntities())) {
                if (isAmethyst(entity) && variantOf(entity.getType()) != null) {
                    convert(entity);
                } else if (isAmethyst(entity)) {
                    dress(entity);
                }
            }
        }
    }

    void stop() {
        clearLegacyVisuals();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        LivingEntity entity = event.getEntity();
        if (reclaimed(entity.getType()) != null) {
            // The pack owns the husk and stray skins, so nothing but an amethyst mob may
            // wear them. Anything that spawns on its own goes back to the plain mob.
            if (!isAmethyst(entity) && natural(event.getSpawnReason())) {
                plugin.getServer().getScheduler().runTask(plugin, () -> reclaim(entity));
            }
            return;
        }
        if (!eligible(entity.getType(), event.getSpawnReason()) || random.nextInt(oneIn) != 0) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> convert(entity));
    }

    /** Keeps an amethyst husk from drowning into an ordinary drowned and losing its skin. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTransform(EntityTransformEvent event) {
        if (event.getEntity() instanceof LivingEntity living && isAmethyst(living)) {
            event.setCancelled(true);
        }
    }

    /**
     * Strips the husk's Hunger. These variants stand in for a plain zombie, so they must
     * not hand players a debuff a zombie never had — the subtype is a texture slot here,
     * not a gameplay change. Every husk on the server is an amethyst mob, since natural
     * ones are reclaimed, so Hunger from an attack can only have come from one.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPotionEffect(EntityPotionEffectEvent event) {
        if (event.getAction() != EntityPotionEffectEvent.Action.ADDED
                || event.getNewEffect() == null) {
            return;
        }
        if (event.getNewEffect().getType().equals(PotionEffectType.HUNGER)
                && event.getCause() == EntityPotionEffectEvent.Cause.ATTACK) {
            event.setCancelled(true);
        }
    }

    /**
     * Re-dresses amethyst mobs as their chunk comes back.
     *
     * <p>Without this, appearance is only ever applied at spawn and at server start over
     * already-loaded entities. A mob saved by an older build keeps whatever it was given
     * then — which is exactly how name tags survived being turned off: the flag sat in
     * the entity's NBT out in an unloaded chunk and nothing ever revisited it.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (entity instanceof LivingEntity living && isAmethyst(living)) {
                dress(living);
            }
        }
    }

    /** Amethyst mobs do not burn away at sunrise; they are meant to be met, not waited out. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCombust(EntityCombustEvent event) {
        if (event.getEntity() instanceof LivingEntity living && isAmethyst(living)) {
            event.setCancelled(true);
        }
    }

    /**
     * Amethyst mobs never turn on each other. An Amethyst Golem is still an iron golem
     * and hunts monsters by nature, and a stray's stray arrow is enough to start a brawl,
     * either of which leaves the garrison fighting itself instead of the player.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTarget(EntityTargetEvent event) {
        if (event.getTarget() instanceof LivingEntity target && isAmethyst(target)
                && event.getEntity() instanceof LivingEntity hunter && isAmethyst(hunter)) {
            event.setTarget(null);
            event.setCancelled(true);
        }
    }

    /** Friendly fire between amethyst mobs, arrows included, simply does not land. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof LivingEntity victim) || !isAmethyst(victim)) {
            return;
        }
        Entity source = event.getDamager();
        if (source instanceof Projectile projectile
                && projectile.getShooter() instanceof Entity shooter) {
            source = shooter;
        }
        if (source instanceof LivingEntity attacker && isAmethyst(attacker)) {
            event.setCancelled(true);
        }
    }

    /**
     * Takes the Slowness off an amethyst stray's arrows at the bow. Cancelling the effect
     * where it lands would also have eaten a player's own tipped arrows, which share the
     * ARROW cause.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onShootBow(EntityShootBowEvent event) {
        if (!isAmethyst(event.getEntity()) || !(event.getProjectile() instanceof Arrow arrow)) {
            return;
        }
        arrow.clearCustomEffects();
        arrow.setBasePotionType(null);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (!isAmethyst(entity)) {
            return;
        }
        int keys = random.nextInt(1, 6);
        event.getDrops().add(crateItems.key(keys));
        entity.getWorld().spawnParticle(
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
        return natural(reason);
    }

    private static boolean natural(CreatureSpawnEvent.SpawnReason reason) {
        return reason != CreatureSpawnEvent.SpawnReason.CUSTOM
                && reason != CreatureSpawnEvent.SpawnReason.COMMAND
                && reason != CreatureSpawnEvent.SpawnReason.SPAWNER_EGG
                && reason != CreatureSpawnEvent.SpawnReason.BUILD_IRONGOLEM
                && reason != CreatureSpawnEvent.SpawnReason.BUILD_SNOWMAN
                && reason != CreatureSpawnEvent.SpawnReason.BUILD_WITHER;
    }

    /** The retextured subtype an ordinary spawn becomes. */
    static EntityType variantOf(EntityType type) {
        if (type == EntityType.ZOMBIE) {
            return EntityType.HUSK;
        }
        return type == EntityType.SKELETON ? EntityType.STRAY : null;
    }

    /** The plain mob a stray amethyst-skinned spawn has to be turned back into. */
    static EntityType reclaimed(EntityType type) {
        if (type == EntityType.HUSK) {
            return EntityType.ZOMBIE;
        }
        return type == EntityType.STRAY ? EntityType.SKELETON : null;
    }

    private void convert(LivingEntity source) {
        EntityType variant = variantOf(source.getType());
        if (variant == null || !source.isValid()) {
            return;
        }
        LivingEntity variantMob = replace(source, variant, true);
        if (variantMob == null) {
            return;
        }
        dress(variantMob);
    }

    private void reclaim(LivingEntity huskOrStray) {
        EntityType plain = reclaimed(huskOrStray.getType());
        if (plain == null || !huskOrStray.isValid() || isAmethyst(huskOrStray)) {
            return;
        }
        replace(huskOrStray, plain, false);
    }

    /**
     * Spawns {@code type} in the source mob's place carrying everything vanilla had
     * already rolled for it. The swap runs a tick after the spawn so the source is fully
     * finalised first — a skeleton has no bow until then, and a bowless stray cannot
     * fight.
     */
    private LivingEntity replace(LivingEntity source, EntityType type, boolean marked) {
        Location where = source.getLocation();
        EntityEquipment from = source.getEquipment();
        boolean baby = source instanceof Zombie zombie && zombie.isBaby();
        double health = source.getHealth();

        LivingEntity spawned;
        try {
            spawned = marked
                    ? spawnMarked(where, type)
                    : (LivingEntity) source.getWorld().spawnEntity(
                            where, type, CreatureSpawnEvent.SpawnReason.CUSTOM);
        } catch (IllegalArgumentException | ClassCastException error) {
            return null;
        }
        if (from != null) {
            EntityEquipment to = spawned.getEquipment();
            if (to != null) {
                for (EquipmentSlot slot : EquipmentSlot.values()) {
                    ItemStack item = from.getItem(slot);
                    to.setItem(slot, item);
                    to.setDropChance(slot, from.getDropChance(slot));
                }
            }
        }
        if (spawned instanceof Zombie zombie) {
            zombie.setBaby(baby);
            zombie.setShouldBurnInDay(false);
        }
        if (spawned instanceof Mob mob && source instanceof Mob sourceMob) {
            mob.setTarget(sourceMob.getTarget());
            mob.setCanPickupItems(sourceMob.getCanPickupItems());
        }
        AttributeInstance maximum = spawned.getAttribute(Attribute.MAX_HEALTH);
        spawned.setHealth(maximum == null ? health : Math.min(health, maximum.getValue()));
        source.remove();
        return spawned;
    }

    /**
     * Strips the mob of anything that could label it.
     *
     * <p>An amethyst mob carries no custom name at all. Clearing {@code CustomNameVisible}
     * is not enough and never was: vanilla renders a named entity's name whenever the
     * player's crosshair is on it, whatever that flag says
     * ({@code shouldShowName() || hasCustomName() && entity == crosshairPickEntity}). The
     * only way to have no tag is to have no name, so the amethyst name lives in the death
     * message instead — see {@link #onPlayerDeath}.
     */
    private void dress(LivingEntity entity) {
        entity.customName(null);
        entity.setCustomNameVisible(false);
        entity.setGlowing(false);
        entity.setInvisible(false);
        if (entity instanceof Zombie zombie) {
            zombie.setShouldBurnInDay(false);
        }
    }

    /**
     * Puts the amethyst name back into the death message, since the mob itself has none.
     * Replacing the subtype's name inside vanilla's own message keeps its phrasing —
     * "slain by", "shot by" — rather than inventing one per damage type.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Entity killer = event.getEntity().getKiller();
        if (killer == null) {
            killer = lastAmethystAttacker(event.getEntity());
        }
        if (!(killer instanceof LivingEntity living) || !isAmethyst(living)) {
            return;
        }
        Component message = event.deathMessage();
        if (message == null) {
            return;
        }
        event.deathMessage(message.replaceText(builder -> builder
                .matchLiteral(vanillaName(living.getType()))
                .replacement(Component.text(displayName(living.getType()), AMETHYST))));
    }

    /**
     * Spawns an amethyst mob already carrying its marker.
     *
     * <p>This has to use the pre-spawn consumer rather than {@code spawnEntity} followed
     * by a write to the container. {@code CreatureSpawnEvent} fires synchronously inside
     * the spawn call, so a marker set on the next line arrives too late for anything
     * listening — and the spawn building's zombie barrier cancels at {@code HIGHEST},
     * which silently ate every Amethyst Zombie a garrison placed near spawn even after
     * the barrier was taught to exempt them. The consumer runs before the entity joins
     * the world, so the marker is there when the event fires.
     */
    private LivingEntity spawnMarked(Location where, EntityType type) {
        return switch (type) {
            case HUSK -> marked(where, Husk.class);
            case STRAY -> marked(where, Stray.class);
            case IRON_GOLEM -> marked(where, IronGolem.class);
            case ZOMBIE -> marked(where, Zombie.class);
            case SKELETON -> marked(where, Skeleton.class);
            default -> throw new IllegalArgumentException("Not an amethyst mob type: " + type);
        };
    }

    private <T extends LivingEntity> T marked(Location where, Class<T> type) {
        return where.getWorld().spawn(where, type, mob ->
                mob.getPersistentDataContainer().set(marker, PersistentDataType.BYTE, (byte) 1));
    }

    private LivingEntity lastAmethystAttacker(Player player) {
        return player.getLastDamageCause() instanceof EntityDamageByEntityEvent cause
                && cause.getDamager() instanceof LivingEntity living && isAmethyst(living)
                ? living : null;
    }

    private static String vanillaName(EntityType type) {
        return switch (type) {
            case HUSK -> "Husk";
            case STRAY -> "Stray";
            case IRON_GOLEM -> "Iron Golem";
            default -> type.name();
        };
    }

    /** Removes the item displays the previous, non-animating implementation left behind. */
    private void clearLegacyVisuals() {
        for (World world : plugin.getServer().getWorlds()) {
            List<Entity> stale = new ArrayList<>();
            for (Entity entity : world.getEntities()) {
                if (entity.getScoreboardTags().contains(DISPLAY_TAG)) {
                    stale.add(entity);
                }
            }
            stale.forEach(Entity::remove);
        }
    }

    private boolean isAmethyst(LivingEntity entity) {
        return entity.getPersistentDataContainer().has(marker, PersistentDataType.BYTE);
    }

    private static String displayName(EntityType type) {
        if (type == EntityType.HUSK || type == EntityType.ZOMBIE) {
            return "Amethyst Zombie";
        }
        return type == EntityType.IRON_GOLEM ? "Amethyst Golem" : "Amethyst Skeleton";
    }

    /**
     * Spawns a marked amethyst mob to order. Only the airdrop guard calls this, which is
     * what keeps Amethyst Golems off the natural spawn table entirely: nothing converts a
     * spawn into one, so the only golems that carry the name tag, fight and drop keys are
     * the ones deployed around an airdrop. A plain village or player-built iron golem is
     * left completely alone — it wears the retextured skin, because a pack holds one
     * iron_golem.png and Minecraft has no iron golem variant registry, but it is not an
     * amethyst mob.
     */
    LivingEntity deploy(Location where, EntityType type) {
        if (type != EntityType.HUSK && type != EntityType.STRAY
                && type != EntityType.IRON_GOLEM) {
            throw new IllegalArgumentException("Not an amethyst mob type: " + type);
        }
        LivingEntity mob = spawnMarked(where, type);
        if (mob == null) {
            // World.spawn returns null when a listener cancels the spawn.
            return null;
        }
        EntityEquipment equipment = mob.getEquipment();
        if (type == EntityType.STRAY && equipment != null) {
            // A CUSTOM spawn skips vanilla's finalisation, and a bowless stray cannot fight.
            equipment.setItemInMainHand(new ItemStack(Material.BOW));
            equipment.setItemInMainHandDropChance(0f);
        }
        if (mob instanceof IronGolem golem) {
            // A player-created golem will not turn on players however it is provoked.
            golem.setPlayerCreated(false);
        }
        dress(mob);
        return mob;
    }

    boolean isAmethystMob(Entity entity) {
        return entity instanceof LivingEntity living && isAmethyst(living);
    }
}
