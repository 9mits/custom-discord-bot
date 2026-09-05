package bot.mgx.accessbridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Gameplay and ownership rules for the limited Amethyst equipment set. */
final class AmethystItemService implements Listener {
    static final long ACTIVE_MILLIS = Duration.ofHours(24).toMillis();
    private static final TextColor AMETHYST = TextColor.color(0xB56CFF);
    private static final Set<String> TIMED_KINDS = Set.of(
            "pickaxe", "shovel", "axe", "shield", "sword", "hoe", "bow",
            "fishing_rod", "helmet", "chestplate", "leggings", "boots", "elytra"
    );
    /** The three that break blocks, and so the three Efficiency means anything on. */
    private static final Set<String> DIGGING_KINDS = Set.of("pickaxe", "shovel", "axe", "hoe");
    private static final int EFFICIENCY_LEVEL = 5;
    /** Live tuning; the constants above stay the defaults and stand alone in tests. */
    private static volatile java.util.function.ToDoubleFunction<String> tuning = key -> Double.NaN;

    static void tuningSource(java.util.function.ToDoubleFunction<String> source) {
        if (source != null) {
            tuning = source;
        }
    }

    private static double tuned(String key, double fallback) {
        double value = tuning.applyAsDouble(key);
        return Double.isNaN(value) ? fallback : value;
    }

    private static final Map<Material, Material> SMELTED = Map.ofEntries(
            Map.entry(Material.RAW_IRON, Material.IRON_INGOT),
            Map.entry(Material.RAW_GOLD, Material.GOLD_INGOT),
            Map.entry(Material.RAW_COPPER, Material.COPPER_INGOT),
            Map.entry(Material.COBBLESTONE, Material.STONE),
            Map.entry(Material.COBBLED_DEEPSLATE, Material.DEEPSLATE),
            Map.entry(Material.SAND, Material.GLASS),
            Map.entry(Material.RED_SAND, Material.GLASS),
            Map.entry(Material.CLAY_BALL, Material.BRICK),
            Map.entry(Material.NETHERRACK, Material.NETHER_BRICK)
    );

    private final MGXAccessBridge plugin;
    private final NamespacedKey kindKey;
    private final NamespacedKey serialKey;
    private final NamespacedKey activatedKey;
    private final NamespacedKey expiresKey;
    private final NamespacedKey arrowKey;
    private final Set<UUID> multiBreaking = new HashSet<>();
    private final Map<UUID, Integer> blockedHits = new HashMap<>();
    private Runnable auctionSweep = () -> { };
    private BukkitTask expiryTask;

    AmethystItemService(MGXAccessBridge plugin) {
        this.plugin = plugin;
        kindKey = new NamespacedKey(plugin, "amethyst_item_kind");
        serialKey = new NamespacedKey(plugin, "amethyst_item_serial");
        activatedKey = new NamespacedKey(plugin, "amethyst_activated_at");
        expiresKey = new NamespacedKey(plugin, "amethyst_expires_at");
        arrowKey = new NamespacedKey(plugin, "amethyst_arrow");
    }

    void useAuctionSweep(Runnable auctionSweep) {
        this.auctionSweep = auctionSweep == null ? () -> { } : auctionSweep;
    }

    void start() {
        stop();
        expiryTask = plugin.getServer().getScheduler().runTaskTimer(
                plugin, this::sweepOnlinePlayers, 20L, 20L
        );
    }

    void stop() {
        if (expiryTask != null) {
            expiryTask.cancel();
            expiryTask = null;
        }
    }

    Optional<ItemStack> create(CrateCatalog.Reward reward) {
        String id = reward.sourceId();
        return switch (id) {
            case "amethyst_pickaxe" -> Optional.of(createTimed(
                    Material.DIAMOND_PICKAXE, "pickaxe", "Amethyst Pickaxe",
                    "3x3 mining", "Automatically smelts drops", "mgx:amethyst_pickaxe"
            ));
            case "amethyst_shovel" -> Optional.of(createTimed(
                    Material.DIAMOND_SHOVEL, "shovel", "Amethyst Shovel",
                    "3x3 digging", "Clears matching shovel blocks", "mgx:amethyst_shovel"
            ));
            case "amethyst_axe" -> Optional.of(createTimed(
                    Material.DIAMOND_AXE, "axe", "Amethyst Axe",
                    "Fells a whole tree", "Up to 256 connected logs", "mgx:amethyst_axe"
            ));
            case "amethyst_shield" -> Optional.of(createTimed(
                    Material.SHIELD, "shield", "Amethyst Shield",
                    "Crystal Guard, Reflect, projectile bounce",
                    "Guard Burst after repeated blocks", "mgx:amethyst_shield"
            ));
            case "amethyst_totem" -> Optional.of(createTotem());
            case "amethyst_sword" -> Optional.of(createTimed(
                    Material.DIAMOND_SWORD, "sword", "Amethyst Sword",
                    "Crystal Edge adds heavy bonus damage", "Violet lightning marks every hit",
                    "mgx:amethyst_sword"));
            case "amethyst_hoe" -> Optional.of(createTimed(
                    Material.DIAMOND_HOE, "hoe", "Amethyst Hoe",
                    "Harvests a 3x3 crop area", "Fortune V and automatic replanting",
                    "mgx:amethyst_hoe"));
            case "amethyst_bow" -> Optional.of(createTimed(
                    Material.BOW, "bow", "Amethyst Bow",
                    "Crystal shots deal bonus damage", "Every shot leaves violet lightning",
                    "mgx:amethyst_bow"));
            case "amethyst_fishing_rod" -> Optional.of(createTimed(
                    Material.FISHING_ROD, "fishing_rod", "Amethyst Fishing Rod",
                    "Treasure-tuned fishing", "Luck of the Sea V and Lure V",
                    "mgx:amethyst_fishing_rod"));
            case "amethyst_helmet" -> Optional.of(armor(Material.DIAMOND_HELMET, "helmet", "Amethyst Helmet"));
            case "amethyst_chestplate" -> Optional.of(armor(Material.DIAMOND_CHESTPLATE, "chestplate", "Amethyst Chestplate"));
            case "amethyst_leggings" -> Optional.of(armor(Material.DIAMOND_LEGGINGS, "leggings", "Amethyst Leggings"));
            case "amethyst_boots" -> Optional.of(armor(Material.DIAMOND_BOOTS, "boots", "Amethyst Boots"));
            case "amethyst_elytra" -> Optional.of(elytra());
            case "amethyst_arrows" -> Optional.of(amethystArrows(reward.amount()));
            case "amethyst_apple" -> Optional.of(amethystApple(reward.amount()));
            default -> Optional.empty();
        };
    }

    Optional<ItemStack> createById(String rewardId) {
        return CrateCatalog.everyReward().stream()
                .filter(reward -> reward.sourceId().equals(rewardId))
                .findFirst().flatMap(this::create);
    }

    private ItemStack armor(Material material, String kind, String name) {
        ItemStack item = createTimed(material, kind, name,
                "Full set grants Crystal Bulwark", "Continuous resistance and regeneration",
                "mgx:amethyst_" + kind);
        ItemMeta meta = item.getItemMeta();
        meta.addEnchant(Enchantment.PROTECTION,
                (int) tuned("amethyst-items.armor-protection-level", 5), true);
        org.bukkit.inventory.meta.components.EquippableComponent equippable = meta.getEquippable();
        equippable.setModel(NamespacedKey.fromString("mgx:amethyst_armor"));
        meta.setEquippable(equippable);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack elytra() {
        ItemStack item = createTimed(Material.ELYTRA, "elytra", "Amethyst Elytra",
                "Lightning Speed", "Glides 50% faster than an ordinary Elytra",
                "mgx:amethyst_elytra");
        ItemMeta meta = item.getItemMeta();
        meta.addEnchant(Enchantment.UNBREAKING,
                (int) tuned("amethyst-items.elytra-unbreaking-level", 5), true);
        org.bukkit.inventory.meta.components.EquippableComponent equippable = meta.getEquippable();
        equippable.setModel(NamespacedKey.fromString("mgx:amethyst_elytra"));
        meta.setEquippable(equippable);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack amethystArrows(int amount) {
        ItemStack item = new ItemStack(Material.TIPPED_ARROW, Math.clamp(amount, 1, 64));
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Amethyst Arrows", AMETHYST, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));
        meta.getPersistentDataContainer().set(arrowKey, PersistentDataType.BYTE, (byte) 1);
        NamespacedKey model = NamespacedKey.fromString("mgx:amethyst_arrow");
        if (model != null) meta.setItemModel(model);
        meta.lore(List.of(line("Consumable crystal arrows that burst with lightning."),
                line("Permanent until fired; no expiration timer.")));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack amethystApple(int amount) {
        ItemStack item = new ItemStack(Material.ENCHANTED_GOLDEN_APPLE, Math.clamp(amount, 1, 64));
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Amethyst Apple", AMETHYST, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));
        meta.getPersistentDataContainer().set(kindKey, PersistentDataType.STRING, "apple");
        NamespacedKey model = NamespacedKey.fromString("mgx:amethyst_apple");
        if (model != null) meta.setItemModel(model);
        meta.addEnchant(Enchantment.UNBREAKING, 1, true);
        meta.lore(List.of(line("A permanent consumable with powerful crystal regeneration."),
                line("Consumables do not expire.")));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createTimed(
            Material material,
            String kind,
            String name,
            String ability,
            String detail,
            String modelKey
    ) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, AMETHYST, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));
        PersistentDataContainer data = meta.getPersistentDataContainer();
        data.set(kindKey, PersistentDataType.STRING, kind);
        data.set(serialKey, PersistentDataType.STRING, UUID.randomUUID().toString());
        meta.setUnbreakable(true);
        // Ships at the vanilla maximum so nobody has to spend an anvil bringing a
        // 24-hour tool up to the speed the abilities already imply. The shield is
        // deliberately not in this set: Efficiency does nothing on one, and forcing
        // an enchantment vanilla would refuse only adds a line to its tooltip.
        if (DIGGING_KINDS.contains(kind)) {
            meta.addEnchant(Enchantment.EFFICIENCY, (int) tuned("amethyst-items.efficiency-level", EFFICIENCY_LEVEL), true);
        }
        switch (kind) {
            case "sword" -> meta.addEnchant(Enchantment.SHARPNESS,
                    (int) tuned("amethyst-items.sword-sharpness-level", 7), true);
            case "hoe" -> meta.addEnchant(Enchantment.FORTUNE,
                    (int) tuned("amethyst-items.hoe-fortune-level", 5), true);
            case "bow" -> {
                meta.addEnchant(Enchantment.POWER,
                        (int) tuned("amethyst-items.bow-power-level", 7), true);
                int infinity = (int) tuned("amethyst-items.bow-infinity-level", 1);
                if (infinity > 0) meta.addEnchant(Enchantment.INFINITY, infinity, true);
            }
            case "fishing_rod" -> {
                meta.addEnchant(Enchantment.LUCK_OF_THE_SEA,
                        (int) tuned("amethyst-items.rod-luck-level", 5), true);
                meta.addEnchant(Enchantment.LURE,
                        (int) tuned("amethyst-items.rod-lure-level", 5), true);
            }
            default -> { }
        }
        NamespacedKey model = NamespacedKey.fromString(modelKey);
        if (model != null) {
            meta.setItemModel(model);
        }
        meta.lore(inactiveLore(ability, detail, kind));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createTotem() {
        ItemStack item = new ItemStack(Material.TOTEM_OF_UNDYING);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Amethyst Totem", AMETHYST, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));
        PersistentDataContainer data = meta.getPersistentDataContainer();
        data.set(kindKey, PersistentDataType.STRING, "totem");
        data.set(serialKey, PersistentDataType.STRING, UUID.randomUUID().toString());
        NamespacedKey model = NamespacedKey.fromString("mgx:amethyst_totem");
        if (model != null) {
            meta.setItemModel(model);
        }
        meta.lore(List.of(
                line("Saves you from death, then detonates."),
                line("Cleanses debuffs and grants regeneration,"),
                line("resistance, and a 10-heart crystal shell."),
                Component.empty(),
                line("Consumed when activated.")
        ));
        item.setItemMeta(meta);
        return item;
    }

    private List<Component> inactiveLore(String ability, String detail, String kind) {
        String trigger = switch (kind) {
            case "shield" -> "Timer begins on your first successful block.";
            case "sword", "bow" -> "Timer begins on your first attack.";
            case "fishing_rod" -> "Timer begins on your first cast.";
            case "helmet", "chestplate", "leggings", "boots", "elytra" ->
                    "Timer begins when equipped and used.";
            default -> "Timer begins when you first break a block.";
        };
        double hours = tuned("amethyst-items.active-hours", 24d);
        return List.of(
                line(ability), line(detail), Component.empty(),
                line("Unbreakable for " + formatHours(hours) + " after activation."),
                line(trigger),
                line("May be enchanted before or after activation.")
        );
    }

    boolean isTimed(ItemStack item) {
        return kind(item).map(TIMED_KINDS::contains).orElse(false);
    }

    boolean canList(ItemStack item) {
        if (!isTimed(item)) {
            return true;
        }
        return activatedAt(item) == 0L && !expired(item, System.currentTimeMillis());
    }

    Optional<UUID> serial(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return Optional.empty();
        }
        String raw = item.getItemMeta().getPersistentDataContainer()
                .get(serialKey, PersistentDataType.STRING);
        try {
            return raw == null ? Optional.empty() : Optional.of(UUID.fromString(raw));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private Optional<String> kind(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return Optional.empty();
        }
        return Optional.ofNullable(item.getItemMeta().getPersistentDataContainer()
                .get(kindKey, PersistentDataType.STRING));
    }

    private long activatedAt(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return 0L;
        }
        Long value = item.getItemMeta().getPersistentDataContainer()
                .get(activatedKey, PersistentDataType.LONG);
        return value == null ? 0L : value;
    }

    private long expiresAt(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return 0L;
        }
        Long value = item.getItemMeta().getPersistentDataContainer()
                .get(expiresKey, PersistentDataType.LONG);
        return value == null ? 0L : value;
    }

    private boolean expired(ItemStack item, long now) {
        long expires = expiresAt(item);
        return expires > 0L && expires <= now;
    }

    private boolean activate(Player owner, ItemStack item) {
        if (!isTimed(item) || activatedAt(item) > 0L) {
            return false;
        }
        long now = System.currentTimeMillis();
        long expires = now
                + (long) (tuned("amethyst-items.active-hours", 24d) * 3_600_000d);
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer data = meta.getPersistentDataContainer();
        data.set(activatedKey, PersistentDataType.LONG, now);
        data.set(expiresKey, PersistentDataType.LONG, expires);
        List<Component> old = new ArrayList<>(withoutInactiveAuctionStatus(meta.lore()));
        if (!old.isEmpty() && !old.get(old.size() - 1).equals(Component.empty())) {
            old.add(Component.empty());
        }
        old.add(activeCountdown(expires - now));
        meta.lore(old);
        item.setItemMeta(meta);
        double hours = tuned("amethyst-items.active-hours", 24d);
        owner.sendMessage(PlayerMenuService.prefix()
                .append(Component.text("Amethyst item activated! ", AMETHYST, TextDecoration.BOLD))
                .append(Component.text("It expires in " + formatHours(hours) + ".", NamedTextColor.WHITE)));
        owner.sendActionBar(Component.text("◆ AMETHYST TIMER STARTED: " + formatHours(hours).toUpperCase(Locale.ROOT)
                        + " ◆", AMETHYST,
                TextDecoration.BOLD));
        owner.playSound(owner.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.2f, 1.1f);
        owner.getWorld().spawnParticle(Particle.END_ROD, owner.getLocation().add(0, 1, 0),
                35, 0.55, 0.8, 0.55, 0.08);
        auctionSweep.run();
        return true;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onToolBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (event.getBlock().getType() == Material.AMETHYST_BLOCK
                && ThreadLocalRandom.current().nextInt((int) tuned(
                        "amethyst-blocks.shard-one-in", 25_000d)) == 0) {
            int amount = (int) tuned("amethyst-blocks.shard-amount", 1d);
            if (amount > 0) {
                player.getInventory().addItem(plugin.crateItems().shard(amount)).values().forEach(left ->
                        player.getWorld().dropItemNaturally(player.getLocation(), left));
                player.sendActionBar(Component.text("◆ RARE AMETHYST SHARD FOUND ◆", AMETHYST,
                        TextDecoration.BOLD));
            }
        }
        ItemStack tool = player.getInventory().getItemInMainHand();
        String kind = kind(tool).orElse("");
        if (!Set.of("pickaxe", "shovel", "axe", "hoe").contains(kind)
                || expired(tool, System.currentTimeMillis())) {
            return;
        }
        activate(player, tool);
        if (multiBreaking.contains(player.getUniqueId())) {
            return;
        }
        Block centre = event.getBlock();
        Material centreCrop = centre.getBlockData() instanceof org.bukkit.block.data.Ageable crop
                && crop.getAge() >= crop.getMaximumAge() ? centre.getType() : null;
        boolean tree = kind.equals("axe") && Tag.LOGS.isTagged(centre.getType());
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || expired(tool, System.currentTimeMillis())) {
                return;
            }
            multiBreaking.add(player.getUniqueId());
            try {
                if (tree) {
                    fellTree(player, centre);
                } else if (kind.equals("hoe")) {
                    harvestCrops(player, centre, centreCrop);
                } else {
                    boolean pickaxe = kind.equals("pickaxe");
                    boolean shovel = kind.equals("shovel");
                    for (Block block : miningPlane(player, centre)) {
                        if (block.getType().isAir() || block.getType().getHardness() < 0f) {
                            continue;
                        }
                        if ((pickaxe && Tag.MINEABLE_PICKAXE.isTagged(block.getType()))
                                || (shovel && Tag.MINEABLE_SHOVEL.isTagged(block.getType()))) {
                            player.breakBlock(block);
                        }
                    }
                }
            } finally {
                multiBreaking.remove(player.getUniqueId());
            }
        });
    }

    private void harvestCrops(Player player, Block centre, Material centreCrop) {
        int radius = (int) tuned("amethyst-items.hoe-radius", 1d);
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                Block block = centre.getRelative(x, 0, z);
                if (x == 0 && z == 0 && centreCrop != null && block.getType().isAir()) {
                    block.setType(centreCrop, false);
                    continue;
                }
                if (!(block.getBlockData() instanceof org.bukkit.block.data.Ageable crop)
                        || crop.getAge() < crop.getMaximumAge()) continue;
                Material cropType = block.getType();
                block.breakNaturally(player.getInventory().getItemInMainHand());
                block.setType(cropType, false);
                org.bukkit.block.data.Ageable replanted = (org.bukkit.block.data.Ageable) block.getBlockData();
                replanted.setAge(0);
                block.setBlockData(replanted, false);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBow(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player player) || event.getBow() == null) return;
        boolean specialArrow = isAmethystArrow(event.getConsumable());
        boolean specialBow = kind(event.getBow()).filter("bow"::equals).isPresent()
                && !expired(event.getBow(), System.currentTimeMillis());
        if (!specialArrow && !specialBow) return;
        if (specialBow) activate(player, event.getBow());
        if (event.getProjectile() instanceof Projectile projectile) {
            projectile.getPersistentDataContainer().set(arrowKey, PersistentDataType.BYTE,
                    (byte) (specialArrow ? 2 : 1));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onWeaponDamage(EntityDamageByEntityEvent event) {
        Player player = event.getDamager() instanceof Player direct ? direct
                : event.getDamager() instanceof Projectile projectile
                && projectile.getShooter() instanceof Player shooter ? shooter : null;
        if (player == null) return;
        if (event.getDamager() instanceof Player) {
            ItemStack weapon = player.getInventory().getItemInMainHand();
            if (kind(weapon).filter("sword"::equals).isPresent()
                    && !expired(weapon, System.currentTimeMillis())) {
                activate(player, weapon);
                event.setDamage(event.getDamage() + tuned("amethyst-items.sword-damage", 6d));
                crystalLightning(event.getEntity());
            }
        } else if (event.getDamager() instanceof Projectile projectile) {
            Byte mark = projectile.getPersistentDataContainer().get(arrowKey, PersistentDataType.BYTE);
            if (mark != null) {
                String key = mark == 2 ? "amethyst-items.arrow-damage" : "amethyst-items.bow-damage";
                event.setDamage(event.getDamage() + tuned(key, mark == 2 ? 8d : 5d));
                crystalLightning(event.getEntity());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        ItemStack rod = event.getPlayer().getInventory().getItemInMainHand();
        if (kind(rod).filter("fishing_rod"::equals).isPresent()
                && !expired(rod, System.currentTimeMillis())) activate(event.getPlayer(), rod);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (!kind(event.getItem()).filter("apple"::equals).isPresent()) return;
        int seconds = (int) tuned("amethyst-items.apple-regeneration-seconds", 20d);
        if (seconds > 0) event.getPlayer().addPotionEffect(new PotionEffect(
                PotionEffectType.REGENERATION, seconds * 20,
                (int) tuned("amethyst-items.apple-regeneration-level", 3d)));
        event.getPlayer().setAbsorptionAmount(Math.max(event.getPlayer().getAbsorptionAmount(),
                tuned("amethyst-items.apple-absorption-hearts", 10d) * 2d));
        event.getPlayer().getWorld().spawnParticle(Particle.END_ROD,
                event.getPlayer().getLocation().add(0, 1, 0),
                (int) tuned("amethyst-items.apple-particle-count", 70d),
                0.7, 0.9, 0.7, 0.08);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWear(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        ItemStack chest = player.getInventory().getChestplate();
        if (player.isGliding() && kind(chest).filter("elytra"::equals).isPresent()
                && !expired(chest, System.currentTimeMillis())) {
            activate(player, chest);
            double multiplier = tuned("amethyst-items.elytra-speed-multiplier", 1.5d);
            double current = player.getVelocity().length();
            double maximum = tuned("amethyst-items.elytra-maximum-velocity", 3.5d);
            double response = tuned("amethyst-items.elytra-boost-response", 0.035d);
            if (current > 0.1d && current < maximum) player.setVelocity(player.getVelocity().multiply(
                    1d + (multiplier - 1d) * response));
            player.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, player.getLocation(),
                    (int) tuned("amethyst-items.elytra-particle-count", 3d),
                    0.4, 0.2, 0.4, 0.02);
        }
        ItemStack[] armor = player.getInventory().getArmorContents();
        String[] kinds = {"boots", "leggings", "chestplate", "helmet"};
        for (int index = 0; index < armor.length; index++) {
            if (!kind(armor[index]).filter(kinds[index]::equals).isPresent()
                    || expired(armor[index], System.currentTimeMillis())) return;
        }
        for (ItemStack piece : armor) activate(player, piece);
        int effectTicks = (int) tuned("amethyst-items.armor-effect-seconds", 3d) * 20;
        player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, effectTicks,
                (int) tuned("amethyst-items.armor-resistance-level", 1d), true, false, true));
        player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, effectTicks,
                (int) tuned("amethyst-items.armor-regeneration-level", 0d),
                true, false, true));
    }

    private void crystalLightning(Entity target) {
        int chance = (int) tuned("amethyst-items.impact-lightning-percent", 100d);
        if (chance > 0 && ThreadLocalRandom.current().nextInt(100) < chance) {
            target.getWorld().strikeLightningEffect(target.getLocation());
        }
    }

    private boolean isAmethystArrow(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(arrowKey, PersistentDataType.BYTE);
    }

    private static String formatHours(double hours) {
        return hours == Math.rint(hours) ? String.format(Locale.ROOT, "%.0f hours", hours)
                : String.format(Locale.ROOT, "%.1f hours", hours);
    }

    private void fellTree(Player player, Block origin) {
        ArrayDeque<Block> pending = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    if (x != 0 || y != 0 || z != 0) {
                        pending.add(origin.getRelative(x, y, z));
                    }
                }
            }
        }
        int broken = 0;
        while (!pending.isEmpty() && broken < 256) {
            Block block = pending.removeFirst();
            String key = block.getX() + ":" + block.getY() + ":" + block.getZ();
            if (!visited.add(key) || !Tag.LOGS.isTagged(block.getType())) {
                continue;
            }
            if (!block.equals(origin) && player.breakBlock(block)) {
                broken++;
            }
            for (int x = -1; x <= 1; x++) {
                for (int y = -1; y <= 1; y++) {
                    for (int z = -1; z <= 1; z++) {
                        if (x != 0 || y != 0 || z != 0) {
                            pending.add(block.getRelative(x, y, z));
                        }
                    }
                }
            }
        }
        origin.getWorld().spawnParticle(Particle.WITCH, origin.getLocation().add(0.5, 1, 0.5),
                26, 0.8, 1.4, 0.8, 0.05);
        origin.getWorld().playSound(origin.getLocation(), Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 1f, 0.7f);
    }

    private static List<Block> miningPlane(Player player, Block centre) {
        Vector direction = player.getEyeLocation().getDirection();
        List<Block> blocks = new ArrayList<>(8);
        for (int first = -1; first <= 1; first++) {
            for (int second = -1; second <= 1; second++) {
                if (first == 0 && second == 0) {
                    continue;
                }
                if (Math.abs(direction.getY()) > 0.65d) {
                    blocks.add(centre.getRelative(first, 0, second));
                } else if (Math.abs(direction.getX()) > Math.abs(direction.getZ())) {
                    blocks.add(centre.getRelative(0, second, first));
                } else {
                    blocks.add(centre.getRelative(first, second, 0));
                }
            }
        }
        return blocks;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAutoSmelt(BlockDropItemEvent event) {
        ItemStack tool = event.getPlayer().getInventory().getItemInMainHand();
        if (!kind(tool).filter("pickaxe"::equals).isPresent()
                || expired(tool, System.currentTimeMillis())) {
            return;
        }
        event.getItems().forEach(entity -> {
            ItemStack stack = entity.getItemStack();
            Material result = SMELTED.get(stack.getType());
            if (result != null) {
                entity.setItemStack(new ItemStack(result, stack.getAmount()));
                entity.getWorld().spawnParticle(Particle.WITCH, entity.getLocation(),
                        4, 0.12, 0.12, 0.12, 0.01);
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onShieldBlock(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player player) || !player.isBlocking()) {
            return;
        }
        ItemStack shield = activeShield(player);
        if (shield == null || expired(shield, System.currentTimeMillis())) {
            return;
        }
        activate(player, shield);
        if (event.getDamager() instanceof Projectile projectile
                && ThreadLocalRandom.current().nextDouble() < 0.35d) {
            projectile.setVelocity(projectile.getVelocity().multiply(-1.35d));
            projectile.setShooter(player);
            player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_CLUSTER_HIT, 1.1f, 1.5f);
        }
        if (ThreadLocalRandom.current().nextDouble() < 0.18d) {
            event.setCancelled(true);
            player.sendActionBar(Component.text("◆ CRYSTAL GUARD ◆", AMETHYST,
                    TextDecoration.BOLD));
        } else if (event.getDamager() instanceof LivingEntity attacker
                && !(event.getDamager() instanceof Projectile)
                && ThreadLocalRandom.current().nextDouble() < 0.22d) {
            attacker.damage(Math.max(1d, event.getFinalDamage() * 0.35d), player);
            attacker.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR,
                    attacker.getLocation().add(0, 1, 0), 8, 0.25, 0.4, 0.25, 0.1);
        }
        int count = blockedHits.merge(player.getUniqueId(), 1, Integer::sum);
        if (count >= 4) {
            blockedHits.put(player.getUniqueId(), 0);
            guardBurst(player);
        }
    }

    private ItemStack activeShield(Player player) {
        ItemStack main = player.getInventory().getItemInMainHand();
        if (kind(main).filter("shield"::equals).isPresent()) {
            return main;
        }
        ItemStack off = player.getInventory().getItemInOffHand();
        return kind(off).filter("shield"::equals).isPresent() ? off : null;
    }

    private void guardBurst(Player player) {
        player.getWorld().spawnParticle(Particle.END_ROD, player.getLocation().add(0, 1, 0),
                70, 1.1, 0.7, 1.1, 0.15);
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 0.75f, 1.6f);
        for (Entity nearby : player.getNearbyEntities(4.5d, 3d, 4.5d)) {
            if (!(nearby instanceof LivingEntity living) || nearby.equals(player)) {
                continue;
            }
            Vector away = nearby.getLocation().toVector()
                    .subtract(player.getLocation().toVector()).normalize().multiply(1.25d).setY(0.45d);
            nearby.setVelocity(away);
            living.damage(2d, player);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemDamage(PlayerItemDamageEvent event) {
        if (isTimed(event.getItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onResurrect(EntityResurrectEvent event) {
        if (!(event.getEntity() instanceof Player player) || !holdsTotem(player)) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> triggerTotem(player));
    }

    private boolean holdsTotem(Player player) {
        return kind(player.getInventory().getItemInMainHand()).filter("totem"::equals).isPresent()
                || kind(player.getInventory().getItemInOffHand()).filter("totem"::equals).isPresent();
    }

    private void triggerTotem(Player player) {
        player.getActivePotionEffects().stream()
                .filter(effect -> effect.getType().getCategory()
                        == org.bukkit.potion.PotionEffectTypeCategory.HARMFUL)
                .map(PotionEffect::getType)
                .forEach(player::removePotionEffect);
        player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 20 * 12, 2));
        player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 20 * 10, 1));
        player.setAbsorptionAmount(Math.max(player.getAbsorptionAmount(), 20d));
        for (Entity nearby : player.getNearbyEntities(6d, 4d, 6d)) {
            if (nearby instanceof LivingEntity && !nearby.equals(player)) {
                Vector away = nearby.getLocation().toVector()
                        .subtract(player.getLocation().toVector()).normalize().multiply(1.8d).setY(0.7d);
                nearby.setVelocity(away);
            }
        }
        for (int wave = 0; wave < 5; wave++) {
            int frame = wave;
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                double radius = 0.6d + frame * 0.55d;
                for (int step = 0; step < 28; step++) {
                    double angle = Math.PI * 2d * step / 28d + frame * 0.35d;
                    player.getWorld().spawnParticle(
                            frame % 2 == 0 ? Particle.END_ROD : Particle.WITCH,
                            player.getLocation().add(Math.cos(angle) * radius, 0.4d + frame * 0.18d,
                                    Math.sin(angle) * radius),
                            1, 0, 0, 0, 0
                    );
                }
            }, wave * 3L);
        }
        player.getWorld().playSound(player.getLocation(), Sound.ITEM_TOTEM_USE, 1.4f, 0.8f);
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 1.2f, 0.55f);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        plugin.getServer().getScheduler().runTask(plugin, () -> sweep(event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryOpen(InventoryOpenEvent event) {
        ItemStack[] contents = event.getInventory().getContents();
        if (purge(contents, System.currentTimeMillis(), event.getInventory()::setContents)
                && event.getPlayer() instanceof Player player) {
            player.sendMessage(PlayerMenuService.prefix().append(Component.text(
                    "An expired Amethyst item disappeared.", NamedTextColor.LIGHT_PURPLE
            )));
        }
    }

    private void sweepOnlinePlayers() {
        long now = System.currentTimeMillis();
        for (org.bukkit.World world : Bukkit.getWorlds()) {
            for (org.bukkit.entity.Item dropped : world.getEntitiesByClass(org.bukkit.entity.Item.class)) {
                ItemStack stack = dropped.getItemStack();
                if (expired(stack, now)) {
                    dropped.remove();
                } else {
                    boolean changed = upgradeLegacyItem(stack);
                    changed |= refreshCountdown(stack, now);
                    if (changed) {
                        dropped.setItemStack(stack);
                    }
                }
            }
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (sweep(player, now)) {
                player.sendMessage(PlayerMenuService.prefix().append(Component.text(
                        "An activated Amethyst item expired and disappeared.", NamedTextColor.LIGHT_PURPLE
                )));
                player.sendActionBar(Component.text("◆ AMETHYST ITEM EXPIRED ◆", AMETHYST));
                player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 0.9f, 0.55f);
            }
        }
    }

    private void sweep(Player player) {
        sweep(player, System.currentTimeMillis());
    }

    private boolean sweep(Player player, long now) {
        boolean removed = purge(player.getInventory().getContents(), now,
                player.getInventory()::setContents);
        removed |= purge(player.getEnderChest().getContents(), now,
                player.getEnderChest()::setContents);
        return removed;
    }

    private boolean purge(ItemStack[] contents, long now, java.util.function.Consumer<ItemStack[]> setter) {
        boolean changed = false;
        boolean removed = false;
        for (int index = 0; index < contents.length; index++) {
            if (expired(contents[index], now)) {
                contents[index] = null;
                changed = true;
                removed = true;
            } else {
                changed |= upgradeLegacyItem(contents[index]);
                changed |= refreshCountdown(contents[index], now);
            }
        }
        if (changed) {
            setter.accept(contents);
        }
        return removed;
    }

    private boolean refreshCountdown(ItemStack item, long now) {
        long expires = expiresAt(item);
        if (expires <= now || !isTimed(item)) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        List<Component> lore = new ArrayList<>(meta.lore() == null ? List.of() : meta.lore());
        Component updated = activeCountdown(expires - now);
        for (int index = 0; index < lore.size(); index++) {
            Component current = lore.get(index);
            String plain = PlainTextComponentSerializer.plainText().serialize(current);
            if (!plain.startsWith("ACTIVE —")) {
                continue;
            }
            if (current.equals(updated)) {
                return false;
            }
            lore.set(index, updated);
            meta.lore(lore);
            item.setItemMeta(meta);
            return true;
        }
        lore.add(updated);
        meta.lore(lore);
        item.setItemMeta(meta);
        return true;
    }

    boolean upgradeLegacyItem(ItemStack item) {
        if (!isTimed(item) || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        List<Component> original = meta.lore();
        List<Component> cleaned = withoutInactiveAuctionStatus(original);
        boolean changed = !cleaned.equals(original == null ? List.of() : original);
        if (changed) {
            meta.lore(cleaned);
        }
        // Cleared rather than forced false: these carry a designed model, and a
        // forced glint over one reads as a sticker rather than an enchantment. The
        // pickaxe, axe and shovel can still be enchanted for real, and that glint is
        // vanilla's to draw — overriding it off would hide a genuine enchantment.
        if (meta.hasEnchantmentGlintOverride()) {
            meta.setEnchantmentGlintOverride(null);
            changed = true;
        }
        if (meta.getEnchantLevel(Enchantment.UNBREAKING) == 1) {
            meta.removeEnchant(Enchantment.UNBREAKING);
            changed = true;
        }
        // Tools minted before Efficiency shipped with them catch up here, on the join
        // and container sweeps, rather than staying slower than the ones bought today.
        // Only ever upwards: a player who put Efficiency V on by hand loses nothing.
        if (kind(item).filter(DIGGING_KINDS::contains).isPresent()
                && meta.getEnchantLevel(Enchantment.EFFICIENCY) < (int) tuned("amethyst-items.efficiency-level", EFFICIENCY_LEVEL)) {
            meta.addEnchant(Enchantment.EFFICIENCY, (int) tuned("amethyst-items.efficiency-level", EFFICIENCY_LEVEL), true);
            changed = true;
        }
        if (changed) {
            item.setItemMeta(meta);
        }
        return changed;
    }

    static List<Component> withoutInactiveAuctionStatus(List<Component> lore) {
        List<Component> cleaned = new ArrayList<>(lore == null ? List.of() : lore);
        cleaned.removeIf(component -> PlainTextComponentSerializer.plainText()
                .serialize(component).startsWith("INACTIVE — safe to auction"));
        while (!cleaned.isEmpty() && cleaned.get(cleaned.size() - 1).equals(Component.empty())) {
            cleaned.remove(cleaned.size() - 1);
        }
        return List.copyOf(cleaned);
    }

    private static Component activeCountdown(long remainingMillis) {
        return Component.text(
                "ACTIVE — expires in " + remainingDuration(remainingMillis),
                NamedTextColor.LIGHT_PURPLE
        ).decoration(TextDecoration.ITALIC, false);
    }

    static String remainingDuration(long remainingMillis) {
        long safeMillis = Math.max(1L, remainingMillis);
        if (safeMillis < 60_000L) {
            long seconds = Math.max(1L, (safeMillis + 999L) / 1_000L);
            return seconds + "s";
        }
        long totalMinutes = safeMillis / 60_000L;
        long hours = totalMinutes / 60L;
        long minutes = totalMinutes % 60L;
        if (hours > 0L) {
            return hours + "h " + minutes + "m";
        }
        return totalMinutes + "m";
    }

    private static Component line(String text) {
        return Component.text(text, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false);
    }
}
