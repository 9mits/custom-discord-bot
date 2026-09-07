package bot.mgx.accessbridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

/** Virtual, self-revoking weapons held by the current top three PvP Rank players. */
final class PvpRankRewardService implements Listener {
    private static final NamespacedKey PLACEMENT_KEY = Objects.requireNonNull(
            NamespacedKey.fromString("mgx:pvp_rank_scythe")
    );
    private static final TextColor CYAN = TextColor.color(0x53E5FF);
    private static final TextColor VIOLET = TextColor.color(0xA66BFF);
    private static final TextColor SILVER = TextColor.color(0xC7CED8);
    private static final Color[] PRIMARY = {
            Color.fromRGB(83, 229, 255),
            Color.fromRGB(166, 107, 255),
            Color.fromRGB(190, 200, 214)
    };
    private static final Color[] SECONDARY = {
            Color.fromRGB(222, 255, 255),
            Color.fromRGB(63, 28, 105),
            Color.fromRGB(39, 42, 50)
    };

    private final MGXAccessBridge plugin;
    private final PvpRecordStore records;
    private final PlayerSettingsStore settings;
    private final GameVariableStore variables;
    private final Map<UUID, Long> lastSweeps = new HashMap<>();
    private final Set<UUID> fullInventoryWarnings = new HashSet<>();
    private BukkitTask ownershipSweep;
    private boolean refreshQueued;
    private Predicate<UUID> inventoryBusy = ignored -> false;

    PvpRankRewardService(
            MGXAccessBridge plugin,
            PvpRecordStore records,
            PlayerSettingsStore settings,
            GameVariableStore variables
    ) {
        this.plugin = plugin;
        this.records = records;
        this.settings = settings;
        this.variables = variables;
    }

    void start() {
        refreshSoon();
        ownershipSweep = plugin.getServer().getScheduler().runTaskTimer(
                plugin, this::reconcileOnline, 100L, 100L
        );
    }

    void useBusyPlayers(Predicate<UUID> inventoryBusy) {
        this.inventoryBusy = inventoryBusy == null ? ignored -> false : inventoryBusy;
    }

    void stop() {
        if (ownershipSweep != null) ownershipSweep.cancel();
        ownershipSweep = null;
        lastSweeps.clear();
        fullInventoryWarnings.clear();
    }

    /** Coalesces the two record writes at the end of a duel into one inventory pass. */
    void refreshSoon() {
        if (refreshQueued || !plugin.isEnabled()) return;
        refreshQueued = true;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            refreshQueued = false;
            reconcileOnline();
        });
    }

    static int placementOf(
            UUID playerId,
            Map<UUID, PvpRecordStore.Record> records,
            Function<UUID, String> names
    ) {
        return PvpRankLeaderboard.top(records, names, 3).stream()
                .filter(row -> row.playerId().equals(playerId))
                .map(PvpRankLeaderboard.Row::placement)
                .findFirst().orElse(0);
    }

    static boolean isRewardScythe(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer()
                .has(PLACEMENT_KEY, PersistentDataType.INTEGER);
    }

    static String rewardName(int placement) {
        return switch (placement) {
            case 1 -> "Apex Scythe";
            case 2 -> "Void Scythe";
            case 3 -> "Shadow Scythe";
            default -> "";
        };
    }

    private void reconcileOnline() {
        Map<UUID, PvpRecordStore.Record> snapshot = records.all();
        Map<UUID, Integer> placements = new HashMap<>();
        for (PvpRankLeaderboard.Row row : PvpRankLeaderboard.top(snapshot, this::name, 3)) {
            placements.put(row.playerId(), row.placement());
        }
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (inventoryBusy.test(player.getUniqueId())) continue;
            reconcile(player, placements.getOrDefault(player.getUniqueId(), 0));
        }
    }

    private void reconcile(Player player, int expected) {
        PlayerInventory inventory = player.getInventory();
        ItemStack[] storage = inventory.getStorageContents();
        int rememberedSlot = -1;
        int previous = 0;
        for (int slot = 0; slot < storage.length; slot++) {
            if (!isRewardScythe(storage[slot])) continue;
            int held = placement(storage[slot]);
            if (rememberedSlot < 0) rememberedSlot = slot;
            if (held > 0) previous = previous == 0 ? held : previous;
            storage[slot] = null;
        }
        int offhandPlacement = placement(inventory.getItemInOffHand());
        boolean keptOffhand = isRewardScythe(inventory.getItemInOffHand());
        if (keptOffhand) {
            if (offhandPlacement > 0) previous = previous == 0 ? offhandPlacement : previous;
            inventory.setItemInOffHand(null);
        }
        ItemStack[] armor = inventory.getArmorContents();
        for (int slot = 0; slot < armor.length; slot++) {
            if (!isRewardScythe(armor[slot])) continue;
            int held = placement(armor[slot]);
            if (held > 0) previous = previous == 0 ? held : previous;
            armor[slot] = null;
        }
        inventory.setStorageContents(storage);
        inventory.setArmorContents(armor);

        if (expected <= 0) {
            fullInventoryWarnings.remove(player.getUniqueId());
            if (previous > 0) ownershipMessage(player, "Your PvP leaderboard Scythe was revoked.");
            return;
        }

        ItemStack reward = createScythe(expected);
        if (keptOffhand) {
            inventory.setItemInOffHand(reward);
        } else if (rememberedSlot >= 0) {
            inventory.setItem(rememberedSlot, reward);
        } else {
            int empty = inventory.firstEmpty();
            if (empty < 0) {
                if (fullInventoryWarnings.add(player.getUniqueId())) {
                    ownershipMessage(player, "Free one inventory slot to receive your #"
                            + expected + " PvP leaderboard Scythe.");
                }
                return;
            }
            inventory.setItem(empty, reward);
        }
        fullInventoryWarnings.remove(player.getUniqueId());
        if (previous == 0) {
            ownershipMessage(player, "You received the #" + expected
                    + " PvP Rank leaderboard Scythe.");
        } else if (previous != expected) {
            ownershipMessage(player, "Your PvP leaderboard Scythe changed from #"
                    + previous + " to #" + expected + ".");
        }
    }

    private ItemStack createScythe(int placement) {
        ItemStack item = new ItemStack(Material.NETHERITE_SWORD);
        ItemMeta meta = item.getItemMeta();
        TextColor color = switch (placement) {
            case 1 -> CYAN;
            case 2 -> VIOLET;
            default -> SILVER;
        };
        String name = rewardName(placement);
        meta.displayName(Component.text(name, color, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));
        meta.getPersistentDataContainer().set(
                PLACEMENT_KEY, PersistentDataType.INTEGER, placement
        );
        NamespacedKey model = NamespacedKey.fromString("mgx:pvp_scythe_" + placement);
        if (model != null) meta.setItemModel(model);
        meta.setUnbreakable(true);
        meta.setEnchantmentGlintOverride(true);
        meta.addEnchant(Enchantment.SHARPNESS, 5, true);
        meta.addEnchant(Enchantment.SWEEPING_EDGE, 3, true);
        meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);
        meta.lore(List.of(
                line("Temporary PvP Rank leaderboard reward", color),
                line("#" + placement + " on the PvP Rank leaderboard", NamedTextColor.WHITE),
                line("+" + oneDecimal(bonusDamage(placement))
                        + " damage beyond a maxed Netherite Sword", NamedTextColor.GRAY),
                line("Heavy themed sweep and exclusive kill climax", NamedTextColor.GRAY),
                Component.empty(),
                line("Available only while you hold this placement", NamedTextColor.YELLOW),
                line("Cannot be dropped, stored, traded, or listed", NamedTextColor.RED)
        ));
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        plugin.getServer().getScheduler().runTask(plugin, this::reconcileOnline);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        int placement = validPlacement(player, player.getInventory().getItemInMainHand());
        if (placement <= 0) return;
        event.setDamage(event.getDamage() + bonusDamage(placement));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwing(PlayerAnimationEvent event) {
        Player player = event.getPlayer();
        int placement = validPlacement(player, player.getInventory().getItemInMainHand());
        if (placement <= 0) return;
        long now = System.currentTimeMillis();
        long cooldown = variables.integer("pvp-rank-rewards.sweep-cooldown-ms");
        if (now - lastSweeps.getOrDefault(player.getUniqueId(), 0L) < cooldown) return;
        lastSweeps.put(player.getUniqueId(), now);
        drawSweep(player, placement);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        LivingEntity victim = event.getEntity();
        Player killer = victim.getKiller();
        if (killer == null) return;
        int placement = validPlacement(killer, killer.getInventory().getItemInMainHand());
        if (placement > 0) animateKill(killer, victim.getLocation().add(0d, 0.65d, 0d), placement);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (!isRewardScythe(event.getItemDrop().getItemStack())) return;
        event.setCancelled(true);
        ownershipMessage(event.getPlayer(), "PvP leaderboard Scythes cannot be dropped.");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        boolean outsidePlayerInventory = !(event.getClickedInventory() instanceof PlayerInventory);
        boolean cursorIntoContainer = isRewardScythe(event.getCursor()) && outsidePlayerInventory;
        boolean shiftedIntoContainer = event.isShiftClick() && isRewardScythe(event.getCurrentItem());
        boolean hotbarIntoContainer = outsidePlayerInventory
                && event.getHotbarButton() >= 0
                && isRewardScythe(player.getInventory().getItem(event.getHotbarButton()));
        boolean intoBundle = (isRewardScythe(event.getCursor())
                && isBundle(event.getCurrentItem()))
                || (isRewardScythe(event.getCurrentItem())
                && isBundle(event.getCursor()));
        if (cursorIntoContainer || shiftedIntoContainer || hotbarIntoContainer || intoBundle) {
            event.setCancelled(true);
            ownershipMessage(player, "PvP leaderboard Scythes stay in your inventory.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!isRewardScythe(event.getOldCursor())) return;
        int topSize = event.getView().getTopInventory().getSize();
        if (event.getRawSlots().stream().anyMatch(slot -> slot < topSize)) {
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player player) {
                ownershipMessage(player, "PvP leaderboard Scythes stay in your inventory.");
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof ItemFrame)) return;
        ItemStack held = event.getHand() == org.bukkit.inventory.EquipmentSlot.HAND
                ? event.getPlayer().getInventory().getItemInMainHand()
                : event.getPlayer().getInventory().getItemInOffHand();
        if (isRewardScythe(held)) {
            event.setCancelled(true);
            ownershipMessage(event.getPlayer(),
                    "PvP leaderboard Scythes cannot be stored on entities.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        if (isRewardScythe(event.getPlayerItem())) {
            event.setCancelled(true);
            ownershipMessage(event.getPlayer(),
                    "PvP leaderboard Scythes cannot be stored on entities.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerDeath(PlayerDeathEvent event) {
        event.getDrops().removeIf(PvpRankRewardService::isRewardScythe);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        plugin.getServer().getScheduler().runTask(plugin, this::reconcileOnline);
    }

    private int validPlacement(Player player, ItemStack item) {
        int itemPlacement = placement(item);
        if (itemPlacement <= 0) return 0;
        int current = placementOf(player.getUniqueId(), records.all(), this::name);
        if (current != itemPlacement) {
            refreshSoon();
            return 0;
        }
        return current;
    }

    private static int placement(ItemStack item) {
        if (!isRewardScythe(item)) return 0;
        Integer value = item.getItemMeta().getPersistentDataContainer()
                .get(PLACEMENT_KEY, PersistentDataType.INTEGER);
        return value == null || value < 1 || value > 3 ? 0 : value;
    }

    private static boolean isBundle(ItemStack item) {
        return item != null && item.hasItemMeta() && item.getItemMeta() instanceof BundleMeta;
    }

    private double bonusDamage(int placement) {
        String tier = switch (placement) {
            case 1 -> "first";
            case 2 -> "second";
            default -> "third";
        };
        return variables.decimal("pvp-rank-rewards." + tier + "-bonus-damage");
    }

    private void drawSweep(Player owner, int placement) {
        Location eye = owner.getEyeLocation();
        Vector forward = eye.getDirection().setY(0d);
        if (forward.lengthSquared() < 0.001d) forward = new Vector(0d, 0d, 1d);
        forward.normalize();
        int count = (int) variables.integer("pvp-rank-rewards.sweep-particles");
        double radius = variables.decimal("pvp-rank-rewards.sweep-radius");
        Particle.DustOptions primary = new Particle.DustOptions(PRIMARY[placement - 1], 1.45f);
        Particle.DustOptions secondary = new Particle.DustOptions(SECONDARY[placement - 1], 1.05f);
        List<Player> viewers = viewers(owner);
        for (int point = 0; point < count; point++) {
            double progress = count == 1 ? 0.5d : point / (double) (count - 1);
            double angle = Math.toRadians(-120d + progress * 240d);
            Vector ray = forward.clone().rotateAroundY(angle);
            double curve = Math.sin(progress * Math.PI);
            Location at = eye.clone().add(ray.clone().multiply(radius * (0.70d + 0.30d * curve)))
                    .add(0d, -0.52d + curve * 0.48d, 0d);
            Location inner = eye.clone().add(ray.multiply(radius * (0.48d + 0.20d * curve)))
                    .add(0d, -0.43d + curve * 0.33d, 0d);
            for (Player viewer : viewers) {
                viewer.spawnParticle(Particle.DUST, at, 1, 0d, 0d, 0d, 0d,
                        point % 3 == 0 ? secondary : primary);
                if (point % 2 == 0) {
                    viewer.spawnParticle(Particle.DUST, inner, 1, 0d, 0d, 0d, 0d, secondary);
                }
                if (point % 4 == 0) viewer.spawnParticle(Particle.CRIT, at, 1,
                        0.03d, 0.03d, 0.03d, 0.01d);
                if (point % 5 == 0) viewer.spawnParticle(Particle.SWEEP_ATTACK, at, 1);
            }
        }
        float pitch = switch (placement) {
            case 1 -> 1.35f;
            case 2 -> 0.85f;
            default -> 0.62f;
        };
        play(viewers, eye, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, pitch);
        play(viewers, eye, Sound.ENTITY_PLAYER_ATTACK_STRONG, 0.42f, pitch * 0.9f);
    }

    private void animateKill(Player owner, Location centre, int placement) {
        int frames = (int) variables.integer("pvp-rank-rewards.kill-effect-frames");
        double maximumRadius = variables.decimal("pvp-rank-rewards.kill-effect-radius");
        new BukkitRunnable() {
            private int frame;

            @Override
            public void run() {
                if (frame >= frames || !owner.isOnline()) {
                    cancel();
                    return;
                }
                double progress = frame / (double) Math.max(1, frames - 1);
                double radius = 0.25d + Math.sin(progress * Math.PI) * maximumRadius;
                List<Player> viewers = viewers(owner);
                drawKillFrame(viewers, centre, placement, frame, radius);
                frame++;
            }
        }.runTaskTimer(plugin, 0L, 2L);
    }

    private void drawKillFrame(
            List<Player> viewers, Location centre, int placement,
            int frame, double radius
    ) {
        Particle.DustOptions primary = new Particle.DustOptions(PRIMARY[placement - 1], 1.55f);
        Particle.DustOptions secondary = new Particle.DustOptions(SECONDARY[placement - 1], 1.15f);
        int points = 28;
        for (int point = 0; point < points; point++) {
            double angle = Math.PI * 2d * point / points + frame * (placement == 2 ? -0.25d : 0.2d);
            double y = placement == 2
                    ? -0.65d + point / (double) points * 3.4d
                    : Math.sin(angle * (placement == 3 ? 2d : 1d)) * 0.55d;
            Location at = centre.clone().add(Math.cos(angle) * radius, y, Math.sin(angle) * radius);
            for (Player viewer : viewers) {
                viewer.spawnParticle(Particle.DUST, at, 1, 0d, 0d, 0d, 0d,
                        point % 4 == 0 ? secondary : primary);
                if (placement == 1 && point % 9 == 0) viewer.spawnParticle(Particle.END_ROD, at, 1);
                if (placement == 2 && point % 7 == 0) viewer.spawnParticle(Particle.PORTAL, at, 2,
                        0.08d, 0.08d, 0.08d, 0.03d);
                if (placement == 3 && point % 7 == 0) viewer.spawnParticle(Particle.SOUL, at, 1,
                        0.04d, 0.08d, 0.04d, 0.01d);
            }
        }
        int climax = Math.max(2, (int) Math.round((framesForEffects() - 1) * 0.62d));
        if (frame == 0) {
            play(viewers, centre, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f,
                    placement == 1 ? 1.45f : placement == 2 ? 0.82f : 0.58f);
        }
        if (frame == climax) {
            for (Player viewer : viewers) {
                viewer.spawnParticle(placement == 1 ? Particle.SONIC_BOOM : Particle.EXPLOSION,
                        centre, placement == 1 ? 1 : 3, 0.35d, 0.55d, 0.35d, 0d);
                viewer.spawnParticle(Particle.SWEEP_ATTACK, centre, 12,
                        1.5d, 1.2d, 1.5d, 0d);
            }
            Sound climaxSound = switch (placement) {
                case 1 -> Sound.ENTITY_WARDEN_SONIC_BOOM;
                case 2 -> Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE;
                default -> Sound.ENTITY_WITHER_SKELETON_DEATH;
            };
            play(viewers, centre, climaxSound, 1.25f, placement == 3 ? 0.62f : 1.0f);
            play(viewers, centre, Sound.ENTITY_GENERIC_EXPLODE, 0.9f,
                    placement == 1 ? 1.25f : placement == 2 ? 0.82f : 0.65f);
        }
        if (frame == framesForEffects() - 1) {
            play(viewers, centre, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.6f,
                    placement == 1 ? 1.8f : placement == 2 ? 1.15f : 0.72f);
        }
    }

    private int framesForEffects() {
        return (int) variables.integer("pvp-rank-rewards.kill-effect-frames");
    }

    private List<Player> viewers(Player owner) {
        List<Player> visible = new ArrayList<>();
        for (Player viewer : plugin.getServer().getOnlinePlayers()) {
            if (!viewer.getWorld().equals(owner.getWorld())
                    || viewer.getLocation().distanceSquared(owner.getLocation()) > 4_096d) continue;
            PlayerSettingsStore.Setting setting = viewer.equals(owner)
                    ? PlayerSettingsStore.Setting.OWN_KILL_EFFECTS_VISIBLE
                    : PlayerSettingsStore.Setting.COSMETICS_VISIBLE;
            if (settings.isEnabled(viewer.getUniqueId(), setting)) visible.add(viewer);
        }
        return visible;
    }

    private void play(List<Player> viewers, Location at, Sound sound, float volume, float pitch) {
        float tunedVolume = (float) (volume * variables.decimal("pvp-rank-rewards.sound-volume"));
        for (Player viewer : viewers) {
            if (settings.isEnabled(viewer.getUniqueId(), PlayerSettingsStore.Setting.COSMETIC_SOUNDS)) {
                viewer.playSound(at, sound, SoundCategory.PLAYERS, tunedVolume, pitch);
            }
        }
    }

    private String name(UUID playerId) {
        String name = plugin.getServer().getOfflinePlayer(playerId).getName();
        return name == null || name.isBlank() ? playerId.toString().substring(0, 8) : name;
    }

    private static Component line(String text, TextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    private static String oneDecimal(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static void ownershipMessage(Player player, String text) {
        player.sendMessage(PlayerMenuService.prefix()
                .append(Component.text(text, NamedTextColor.WHITE)));
    }
}
