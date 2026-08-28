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
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
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
            "pickaxe", "shovel", "axe", "shield"
    );
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
        String id = reward.id();
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
            default -> Optional.empty();
        };
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
        meta.setEnchantmentGlintOverride(true);
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
        meta.setEnchantmentGlintOverride(true);
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
        String trigger = kind.equals("shield")
                ? "Timer begins on your first successful block."
                : "Timer begins when you first break a block.";
        return List.of(
                line(ability), line(detail), Component.empty(),
                line("Unbreakable for 24 hours after activation."),
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
        long expires = now + ACTIVE_MILLIS;
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
        owner.sendMessage(PlayerMenuService.prefix()
                .append(Component.text("Amethyst item activated! ", AMETHYST, TextDecoration.BOLD))
                .append(Component.text("It expires in 24 hours.", NamedTextColor.WHITE)));
        owner.sendActionBar(Component.text("◆ 24-HOUR AMETHYST TIMER STARTED ◆", AMETHYST,
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
        ItemStack tool = player.getInventory().getItemInMainHand();
        String kind = kind(tool).orElse("");
        if (!Set.of("pickaxe", "shovel", "axe").contains(kind)
                || expired(tool, System.currentTimeMillis())) {
            return;
        }
        activate(player, tool);
        if (multiBreaking.contains(player.getUniqueId())) {
            return;
        }
        Block centre = event.getBlock();
        boolean tree = kind.equals("axe") && Tag.LOGS.isTagged(centre.getType());
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || expired(tool, System.currentTimeMillis())) {
                return;
            }
            multiBreaking.add(player.getUniqueId());
            try {
                if (tree) {
                    fellTree(player, centre);
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
        if (!meta.hasEnchantmentGlintOverride()
                || !Boolean.TRUE.equals(meta.getEnchantmentGlintOverride())) {
            meta.setEnchantmentGlintOverride(true);
            changed = true;
        }
        if (meta.getEnchantLevel(Enchantment.UNBREAKING) == 1) {
            meta.removeEnchant(Enchantment.UNBREAKING);
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
