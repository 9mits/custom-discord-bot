package bot.mgx.accessbridge;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** A season-locked, transferable one-roll crate with its own cinematic reveal. */
final class GiftbagService implements Listener {
    private static final TextColor VIOLET = TextColor.color(0xB56CFF);
    private static final TextColor MYTHIC = TextColor.color(0x53E5FF);
    private static final int YES_SLOT = 11;
    private static final int CONTENTS_SLOT = 13;
    private static final int NO_SLOT = 15;

    private enum Screen { CONFIRM, CONTENTS }

    private static final class Menu implements InventoryHolder {
        final Screen screen;
        final int season;
        Inventory inventory;

        Menu(Screen screen, int season) {
            this.screen = screen;
            this.season = season;
        }

        @Override public Inventory getInventory() { return inventory; }
    }

    private static final class Reveal {
        final UUID playerId;
        final GiftbagStore.Pending pending;
        final Location anchor;
        final ItemDisplay display;
        final BossBar bar;
        final List<UUID> audience;
        int elapsed;
        BukkitTask task;

        Reveal(UUID playerId, GiftbagStore.Pending pending, Location anchor, ItemDisplay display,
                BossBar bar, List<UUID> audience) {
            this.playerId = playerId;
            this.pending = pending;
            this.anchor = anchor;
            this.display = display;
            this.bar = bar;
            this.audience = audience;
        }
    }

    private final MGXAccessBridge plugin;
    private final GiftbagStore store;
    private final GameVariableStore variables;
    private final CrateItems items;
    private final CosmeticStore cosmetics;
    private final CosmeticItems cosmeticItems;
    private final PlayerSettingsStore settings;
    private final MythicGiftItemService mythicItems;
    private final NamespacedKey markerKey;
    private final NamespacedKey seasonKey;
    private final NamespacedKey serialKey;
    private final NamespacedKey rewardSpinKey;
    private final Map<UUID, Reveal> reveals = new HashMap<>();

    GiftbagService(
            MGXAccessBridge plugin, GiftbagStore store, GameVariableStore variables, CrateItems items,
            CosmeticStore cosmetics, CosmeticItems cosmeticItems, PlayerSettingsStore settings,
            MythicGiftItemService mythicItems
    ) {
        this.plugin = plugin;
        this.store = store;
        this.variables = variables;
        this.items = items;
        this.cosmetics = cosmetics;
        this.cosmeticItems = cosmeticItems;
        this.settings = settings;
        this.mythicItems = mythicItems;
        this.markerKey = new NamespacedKey(plugin, "mythic_giftbag");
        this.seasonKey = new NamespacedKey(plugin, "giftbag_season");
        this.serialKey = new NamespacedKey(plugin, "giftbag_serial");
        this.rewardSpinKey = new NamespacedKey(plugin, "giftbag_reward_spin");
    }

    ItemStack create(int season) {
        ItemStack bag = new ItemStack(Material.BUNDLE);
        ItemMeta meta = bag.getItemMeta();
        meta.setMaxStackSize(1);
        meta.displayName(Component.text("✦ ", MYTHIC, TextDecoration.BOLD)
                .append(Component.text("SEASON " + season + " ", VIOLET, TextDecoration.BOLD))
                .append(Component.text("幻 GIFTBAG", MYTHIC, TextDecoration.BOLD))
                .append(Component.text(" ✦", VIOLET, TextDecoration.BOLD))
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                line("A sealed thing people speak about as a rumour.", NamedTextColor.GRAY),
                line("One Giftbag. One irreversible roll.", NamedTextColor.WHITE),
                Component.empty(),
                line("Contains Season " + season + " exclusives, Shards,", NamedTextColor.LIGHT_PURPLE),
                line("and three impossible permanent mythic items.", NamedTextColor.LIGHT_PURPLE),
                Component.empty(),
                line("Right-click to inspect and open.", NamedTextColor.AQUA),
                line("Tradeable · Droppable · Auctionable", NamedTextColor.DARK_GRAY),
                Component.text("幻 MYTHICAL RELIC", MYTHIC, TextDecoration.BOLD)
                        .decoration(TextDecoration.ITALIC, false)
        ));
        meta.getPersistentDataContainer().set(markerKey, PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(seasonKey, PersistentDataType.INTEGER, season);
        meta.getPersistentDataContainer().set(serialKey, PersistentDataType.STRING, UUID.randomUUID().toString());
        NamespacedKey model = NamespacedKey.fromString("mgx:mythic_giftbag");
        if (model != null) meta.setItemModel(model);
        meta.setEnchantmentGlintOverride(true);
        bag.setItemMeta(meta);
        SentinelHub.minted(SentinelEngine.Kind.GIFTBAG, 1L);
        return bag;
    }

    ItemStack preview(int season) {
        return SentinelHub.quietly(() -> create(season));
    }

    boolean isGiftbag(ItemStack item) {
        return item != null && item.getType() == Material.BUNDLE
                && item.getPersistentDataContainer().has(markerKey, PersistentDataType.BYTE);
    }

    Optional<Integer> seasonOf(ItemStack item) {
        if (!isGiftbag(item)) return Optional.empty();
        return Optional.ofNullable(item.getPersistentDataContainer().get(seasonKey, PersistentDataType.INTEGER));
    }

    Optional<String> serial(ItemStack item) {
        if (!isGiftbag(item)) return Optional.empty();
        return Optional.ofNullable(item.getPersistentDataContainer().get(serialKey, PersistentDataType.STRING));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK)) return;
        Player player = event.getPlayer();
        Optional<Integer> season = seasonOf(player.getInventory().getItemInMainHand());
        if (season.isEmpty()) return;
        event.setCancelled(true);
        if (!variables.bool("season.giftbag.enabled")) {
            tell(player, "Giftbags are sealed right now. Yours remains untouched.", NamedTextColor.RED);
            return;
        }
        if (VerificationLobbyService.isLobbyWorld(player.getWorld()) || plugin.inPvpDuel(player)) {
            tell(player, "Giftbags cannot open here. Yours remains untouched.", NamedTextColor.RED);
            return;
        }
        if (store.pending(player.getUniqueId()).isPresent()) {
            retry(player);
            return;
        }
        if (reveals.containsKey(player.getUniqueId())) return;
        openConfirm(player, season.get());
    }

    private void openConfirm(Player player, int season) {
        Menu holder = new Menu(Screen.CONFIRM, season);
        Inventory menu = Bukkit.createInventory(holder, 27,
                Component.text("Open the 幻 Giftbag?", VIOLET, TextDecoration.BOLD));
        holder.inventory = menu;
        fill(menu, Material.PURPLE_STAINED_GLASS_PANE);
        menu.setItem(YES_SLOT, MenuItems.detailed(Material.LIME_CONCRETE, "OPEN IT", List.of(
                line("Consumes exactly one Giftbag.", NamedTextColor.GRAY),
                line("The result is selected before the reveal begins.", NamedTextColor.GRAY))));
        menu.setItem(CONTENTS_SLOT, preview(season));
        menu.setItem(NO_SLOT, MenuItems.detailed(Material.RED_CONCRETE, "KEEP IT SEALED", List.of(
                line("Close this screen without consuming it.", NamedTextColor.GRAY))));
        player.openInventory(menu);
        sound(player, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 0.55f);
    }

    private void openContents(Player player, int season) {
        Menu holder = new Menu(Screen.CONTENTS, season);
        Inventory menu = Bukkit.createInventory(holder, 54,
                Component.text("Inside the Season " + season + " Giftbag", MYTHIC, TextDecoration.BOLD));
        holder.inventory = menu;
        fill(menu, Material.BLACK_STAINED_GLASS_PANE);
        List<GiftbagCatalog.Entry> rewards = GiftbagCatalog.forSeason(season);
        long total = liveTotal(rewards);
        int[] slots = {10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,30,32};
        for (int index = 0; index < rewards.size() && index < slots.length; index++) {
            GiftbagCatalog.Entry reward = rewards.get(index);
            ItemStack icon = rewardPreview(reward, season);
            ItemMeta meta = icon.getItemMeta();
            List<Component> lore = new ArrayList<>(meta.lore() == null ? List.of() : meta.lore());
            if (!lore.isEmpty()) lore.add(Component.empty());
            lore.add(Component.text(reward.rarity().label, TextColor.color(reward.rarity().colour), TextDecoration.BOLD)
                    .decoration(TextDecoration.ITALIC, false));
            lore.add(line(chance(liveWeight(reward), total), NamedTextColor.WHITE));
            meta.lore(lore);
            icon.setItemMeta(meta);
            menu.setItem(slots[index], icon);
        }
        menu.setItem(49, MenuItems.button(Material.ARROW, "Back"));
        player.openInventory(menu);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Menu holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getClickedInventory() != event.getInventory()) return;
        int slot = event.getRawSlot();
        if (holder.screen == Screen.CONTENTS) {
            if (slot == 49) openConfirm(player, holder.season);
            return;
        }
        if (slot == NO_SLOT) {
            player.closeInventory();
        } else if (slot == CONTENTS_SLOT) {
            openContents(player, holder.season);
        } else if (slot == YES_SLOT) {
            begin(player, holder.season);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Menu) event.setCancelled(true);
    }

    private void begin(Player player, int season) {
        if (reveals.containsKey(player.getUniqueId()) || store.pending(player.getUniqueId()).isPresent()) return;
        ItemStack held = player.getInventory().getItemInMainHand();
        if (seasonOf(held).filter(found -> found == season).isEmpty()) {
            player.closeInventory();
            tell(player, "Hold that same Giftbag in your main hand to open it.", NamedTextColor.RED);
            return;
        }
        GiftbagCatalog.Entry reward;
        try {
            reward = GiftbagCatalog.roll(season, this::liveWeight, ThreadLocalRandom.current());
        } catch (RuntimeException failure) {
            tell(player, failure.getMessage(), NamedTextColor.RED);
            return;
        }
        held.setAmount(held.getAmount() - 1);
        GiftbagStore.Pending pending;
        try {
            pending = store.reserve(player.getUniqueId(), UUID.randomUUID(), reward.id(), season,
                    System.currentTimeMillis());
        } catch (RuntimeException failure) {
            player.getInventory().addItem(create(season)).values()
                    .forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
            tell(player, "The seal would not break. Your Giftbag was returned.", NamedTextColor.RED);
            plugin.getLogger().warning("Could not reserve Giftbag reward for " + player.getName() + ": " + failure);
            return;
        }
        player.closeInventory();
        if (plugin.seasonPass() != null) {
            plugin.seasonPass().progress(player, SeasonPassRules.QuestType.OPEN_CRATES, 1L);
        }
        startReveal(player, pending);
    }

    private void startReveal(Player player, GiftbagStore.Pending pending) {
        Location anchor = player.getEyeLocation().clone().add(player.getEyeLocation().getDirection().normalize().multiply(2.2));
        anchor.setPitch(0f);
        ItemDisplay display = player.getWorld().spawn(anchor, ItemDisplay.class, entity -> {
            entity.setItemStack(preview(pending.season()));
            entity.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            entity.setGravity(false);
            entity.setInvulnerable(true);
            entity.setPersistent(false);
            entity.setGlowing(true);
            entity.setViewRange(variables.integer("season.giftbag.viewer-radius"));
        });
        BossBar bar = BossBar.bossBar(
                Component.text("✦ THE SEAL IS LISTENING... ✦", VIOLET, TextDecoration.BOLD), 0f,
                BossBar.Color.PURPLE, BossBar.Overlay.NOTCHED_20);
        List<UUID> audience = nearby(player, anchor);
        for (UUID id : audience) {
            Player viewer = Bukkit.getPlayer(id);
            if (viewer != null) plugin.bossBars().show(viewer, bar);
        }
        Reveal reveal = new Reveal(player.getUniqueId(), pending, anchor, display, bar, audience);
        reveals.put(player.getUniqueId(), reveal);
        player.showTitle(Title.title(
                Component.text("幻", MYTHIC, TextDecoration.BOLD),
                Component.text("Something impossible is waking...", VIOLET),
                Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(2), Duration.ofMillis(400))));
        sound(player, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1.2f, 0.5f);
        reveal.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> tick(reveal), 0L, 2L);
    }

    private void tick(Reveal reveal) {
        Player player = Bukkit.getPlayer(reveal.playerId);
        if (player == null || !player.isOnline() || !reveal.display.isValid()) {
            cleanup(reveal);
            return;
        }
        int duration = Math.max(40, variables.integer("season.giftbag.animation-ticks"));
        reveal.elapsed += 2;
        float progress = Math.min(1f, reveal.elapsed / (float) duration);
        reveal.bar.progress(progress);
        reveal.bar.name(Component.text(progress < .35f ? "✦ THE SEAL IS LISTENING... ✦"
                : progress < .72f ? "✦ REALITY IS THINNING... ✦"
                : progress < .94f ? "✦ DO NOT LOOK AWAY ✦" : "✦ 幻 ✦",
                progress < .72f ? VIOLET : MYTHIC, TextDecoration.BOLD));
        double wave = Math.sin(reveal.elapsed * 0.16d) * (0.08d + progress * 0.18d);
        Location at = reveal.anchor.clone().add(0, wave, 0);
        at.setYaw(reveal.elapsed * (0.9f + progress * 3.5f));
        reveal.display.teleport(at);
        int density = Math.max(1, variables.integer("season.giftbag.particle-density"));
        for (UUID id : reveal.audience) {
            Player viewer = Bukkit.getPlayer(id);
            if (viewer == null || !effects(viewer)) continue;
            double radius = .45d + progress * 1.15d;
            double angle = reveal.elapsed * .12d + id.hashCode() % 11;
            Location particle = at.clone().add(Math.cos(angle) * radius, .15d + progress,
                    Math.sin(angle) * radius);
            viewer.spawnParticle(Particle.DUST, particle, density, .05, .05, .05, 0,
                    new Particle.DustOptions(progress < .7f ? Color.fromRGB(181,108,255)
                            : Color.fromRGB(83,229,255), 1.15f));
            viewer.spawnParticle(progress < .8f ? Particle.ENCHANT : Particle.REVERSE_PORTAL,
                    at, density, radius, .5, radius, .03 + progress * .06);
        }
        if (reveal.elapsed % Math.max(4, 18 - (int) (progress * 12)) == 0) {
            float pitch = 0.55f + progress * 1.35f;
            reveal.audience.forEach(id -> sound(Bukkit.getPlayer(id), Sound.BLOCK_AMETHYST_BLOCK_CHIME, .65f, pitch));
        }
        if (reveal.elapsed >= duration) finish(reveal, player);
    }

    private void finish(Reveal reveal, Player player) {
        if (reveal.task != null) reveal.task.cancel();
        GiftbagCatalog.Entry reward = GiftbagCatalog.find(reveal.pending.rewardId()).orElseThrow();
        reveal.display.setItemStack(rewardPreview(reward, reveal.pending.season()));
        reveal.bar.name(Component.text("✦ " + reward.displayName().toUpperCase(Locale.ROOT) + " ✦",
                TextColor.color(reward.rarity().colour), TextDecoration.BOLD));
        reveal.bar.progress(1f);
        player.showTitle(Title.title(
                Component.text(reward.rarity().label, TextColor.color(reward.rarity().colour), TextDecoration.BOLD),
                Component.text(reward.displayName(), NamedTextColor.GOLD, TextDecoration.BOLD),
                Title.Times.times(Duration.ofMillis(100), Duration.ofSeconds(3), Duration.ofMillis(800))));
        for (UUID id : reveal.audience) {
            Player viewer = Bukkit.getPlayer(id);
            if (viewer == null) continue;
            if (effects(viewer)) {
                viewer.spawnParticle(Particle.END_ROD, reveal.anchor, 75, 1.4, 1.1, 1.4, .12);
                viewer.spawnParticle(Particle.SONIC_BOOM, reveal.anchor, 1, 0, 0, 0, 0);
            }
            sound(viewer, reward.rarity() == GiftbagCatalog.Rarity.IMPOSSIBLE
                    ? Sound.ENTITY_WITHER_SPAWN : Sound.BLOCK_END_PORTAL_SPAWN, 1.1f, .72f);
            sound(viewer, Sound.UI_TOAST_CHALLENGE_COMPLETE, .9f, 1.05f);
        }
        deliver(player, reveal.pending, reward);
        announce(player, reward, reveal.pending.season());
        ServerEvent.of("giftbag_opened", ServerEvent.CATEGORY_CRATE, player.getUniqueId(), player.getName(),
                plugin::recordServerEvent)
                .summary(player.getName() + " opened a Season " + reveal.pending.season() + " Giftbag")
                .detail("reward", reward.displayName())
                .detail("rarity", reward.rarity().label)
                .detail("spin_id", reveal.pending.spinId().toString())
                .record();
        Bukkit.getScheduler().runTaskLater(plugin, () -> cleanup(reveal),
                Math.max(10, variables.integer("season.giftbag.settle-ticks")));
    }

    private void deliver(Player player, GiftbagStore.Pending pending, GiftbagCatalog.Entry reward) {
        if (alreadyCarrying(player, pending.spinId())) {
            store.complete(player.getUniqueId(), pending.spinId());
            clearRewardMarker(player, pending.spinId());
            return;
        }
        if (reward.kind() == GiftbagCatalog.Kind.SEASON_COSMETIC) {
            SeasonCosmetics.category(reward.value()).flatMap(category ->
                    SeasonCosmetics.forSeason(pending.season(), category)).ifPresent(definition ->
                    cosmetics.mint(player.getUniqueId(), definition.id(), pending.spinId()));
            store.complete(player.getUniqueId(), pending.spinId());
            tell(player, rewardName(reward, pending.season()) + " is now in your /wardrobe.", MYTHIC);
            return;
        }
        ItemStack item = rewardItem(reward, pending.season());
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(rewardSpinKey, PersistentDataType.STRING, pending.spinId().toString());
        item.setItemMeta(meta);
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(item);
        if (!leftovers.isEmpty()) {
            tell(player, "Make one inventory space; your sealed reward is waiting safely.", NamedTextColor.RED);
            return;
        }
        store.complete(player.getUniqueId(), pending.spinId());
        clearRewardMarker(player, pending.spinId());
    }

    private ItemStack rewardItem(GiftbagCatalog.Entry reward, int season) {
        return switch (reward.kind()) {
            case SHARDS -> items.shard(reward.amount());
            case SEASON_ITEM -> SeasonItemCatalog.find(reward.value())
                    .filter(ignored -> plugin.seasonItems() != null)
                    .map(item -> plugin.seasonItems().create(item, reward.amount()))
                    .orElseGet(() -> items.shard(3));
            case SEASON_GEAR -> SeasonCosmetics.theme(season).flatMap(theme -> SeasonGear.Piece.parse(reward.value())
                    .map(piece -> plugin.amethystItems().createSeasonGear(theme, piece)))
                    .orElseGet(() -> items.shard(3));
            case MYTHIC_ITEM -> mythicItems.create(reward.value());
            case SEASON_COSMETIC -> throw new IllegalArgumentException("Cosmetics are not physical rewards.");
        };
    }

    private ItemStack rewardPreview(GiftbagCatalog.Entry reward, int season) {
        return SentinelHub.quietly(() -> switch (reward.kind()) {
            case SHARDS -> items.shard(reward.amount());
            case SEASON_ITEM -> SeasonItemCatalog.find(reward.value())
                    .filter(ignored -> plugin.seasonItems() != null)
                    .map(item -> plugin.seasonItems().create(item, reward.amount()))
                    .orElseGet(() -> new ItemStack(Material.GOAT_HORN));
            case SEASON_GEAR -> SeasonCosmetics.theme(season).flatMap(theme -> SeasonGear.Piece.parse(reward.value())
                    .map(piece -> plugin.amethystItems().createSeasonGear(theme, piece)))
                    .orElseGet(() -> new ItemStack(Material.NETHERITE_SWORD));
            case SEASON_COSMETIC -> SeasonCosmetics.category(reward.value()).flatMap(category ->
                    SeasonCosmetics.forSeason(season, category))
                    .map(definition -> cosmeticItems.preview(definition, false))
                    .orElseGet(() -> new ItemStack(Material.NETHER_STAR));
            case MYTHIC_ITEM -> mythicItems.preview(reward.value());
        });
    }

    private void retry(Player player) {
        GiftbagStore.Pending pending = store.pending(player.getUniqueId()).orElse(null);
        if (pending == null) return;
        GiftbagCatalog.Entry reward = GiftbagCatalog.find(pending.rewardId()).orElse(null);
        if (reward == null) {
            tell(player, "Your sealed reward needs an administrator. It has not been lost.", NamedTextColor.RED);
            return;
        }
        deliver(player, pending, reward);
        if (store.pending(player.getUniqueId()).isEmpty()) {
            tell(player, "Your interrupted Giftbag reward arrived: " + rewardName(reward, pending.season()) + ".", MYTHIC);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (event.getPlayer().isOnline()) retry(event.getPlayer());
        }, 80L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Reveal reveal = reveals.get(event.getPlayer().getUniqueId());
        if (reveal != null) cleanup(reveal);
    }

    void stop() {
        List.copyOf(reveals.values()).forEach(this::cleanup);
    }

    private void cleanup(Reveal reveal) {
        if (reveal.task != null) reveal.task.cancel();
        if (reveal.display.isValid()) reveal.display.remove();
        for (UUID id : reveal.audience) {
            Player viewer = Bukkit.getPlayer(id);
            if (viewer != null) plugin.bossBars().hide(viewer, reveal.bar);
        }
        reveals.remove(reveal.playerId, reveal);
    }

    private void announce(Player winner, GiftbagCatalog.Entry reward, int season) {
        Component message = Component.text("✦ 幻 GIFTBAG ✦ ", MYTHIC, TextDecoration.BOLD)
                .append(Component.text(winner.getName(), NamedTextColor.GOLD, TextDecoration.BOLD))
                .append(Component.text(" unsealed ", NamedTextColor.WHITE))
                .append(Component.text(rewardName(reward, season), TextColor.color(reward.rarity().colour), TextDecoration.BOLD))
                .append(Component.text("! ", NamedTextColor.WHITE))
                .append(Component.text(chance(liveWeight(reward), liveTotal(GiftbagCatalog.forSeason(season))),
                        NamedTextColor.AQUA, TextDecoration.BOLD));
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.equals(winner) || settings.isEnabled(viewer.getUniqueId(),
                    PlayerSettingsStore.Setting.CRATE_ANNOUNCEMENTS)) viewer.sendMessage(message);
        }
        plugin.getServer().getConsoleSender().sendMessage(message);
    }

    private String rewardName(GiftbagCatalog.Entry reward, int season) {
        if (reward.kind() == GiftbagCatalog.Kind.SEASON_GEAR) {
            return SeasonCosmetics.theme(season).flatMap(theme -> SeasonGear.Piece.parse(reward.value())
                    .map(piece -> SeasonGear.displayName(theme, piece))).orElse(reward.displayName());
        }
        if (reward.kind() == GiftbagCatalog.Kind.SEASON_COSMETIC) {
            return SeasonCosmetics.category(reward.value()).flatMap(category ->
                    SeasonCosmetics.forSeason(season, category))
                    .map(CosmeticCatalog.Definition::displayName).orElse(reward.displayName());
        }
        return reward.displayName();
    }

    private List<UUID> nearby(Player owner, Location anchor) {
        double radius = Math.max(8, variables.integer("season.giftbag.viewer-radius"));
        List<UUID> audience = new ArrayList<>();
        for (Player viewer : owner.getWorld().getPlayers()) {
            if (viewer.getLocation().distanceSquared(anchor) <= radius * radius) audience.add(viewer.getUniqueId());
        }
        if (!audience.contains(owner.getUniqueId())) audience.add(owner.getUniqueId());
        return List.copyOf(audience);
    }

    private boolean effects(Player player) {
        return settings.isEnabled(player.getUniqueId(), PlayerSettingsStore.Setting.CRATE_REVEAL_EFFECTS);
    }

    private void sound(Player player, Sound sound, float volume, float pitch) {
        if (player != null && settings.isEnabled(player.getUniqueId(), PlayerSettingsStore.Setting.CRATE_SOUNDS)) {
            player.playSound(player.getLocation(), sound,
                    (float) (volume * variables.decimal("season.giftbag.sound-volume")), pitch);
        }
    }

    private long liveWeight(GiftbagCatalog.Entry entry) {
        return Math.max(0, variables.integer(entry.weightKey()));
    }

    private long liveTotal(List<GiftbagCatalog.Entry> entries) {
        return entries.stream().mapToLong(this::liveWeight).sum();
    }

    static String chance(long weight, long total) {
        if (weight <= 0 || total <= 0) return "Disabled";
        if (total % weight == 0 && total / weight >= 100) {
            return "1 in " + String.format(Locale.ROOT, "%,d", total / weight);
        }
        return String.format(Locale.ROOT, "%.4f%%", weight * 100d / total)
                .replaceAll("0+%$", "%").replaceAll("\\.%$", "%");
    }

    private boolean alreadyCarrying(Player player, UUID spinId) {
        String wanted = spinId.toString();
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && wanted.equals(item.getPersistentDataContainer()
                    .get(rewardSpinKey, PersistentDataType.STRING))) return true;
        }
        return false;
    }

    private void clearRewardMarker(Player player, UUID spinId) {
        String wanted = spinId.toString();
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || !wanted.equals(item.getPersistentDataContainer()
                    .get(rewardSpinKey, PersistentDataType.STRING))) continue;
            ItemMeta meta = item.getItemMeta();
            meta.getPersistentDataContainer().remove(rewardSpinKey);
            item.setItemMeta(meta);
        }
    }

    private static void fill(Inventory inventory, Material material) {
        ItemStack pane = MenuItems.button(material, " ");
        for (int slot = 0; slot < inventory.getSize(); slot++) inventory.setItem(slot, pane);
    }

    private static Component line(String text, NamedTextColor colour) {
        return Component.text(text, colour).decoration(TextDecoration.ITALIC, false);
    }

    private static void tell(Player player, String text, TextColor colour) {
        player.sendMessage(Component.text("GIFTBAG » ", MYTHIC, TextDecoration.BOLD)
                .append(Component.text(text, colour)));
    }
}
