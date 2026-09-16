package bot.mgx.accessbridge;

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
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
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
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A season-locked, transferable one-roll crate with its own cinematic reveal.
 *
 * <p>The bag is a plain inert item rather than a bundle: a bundle holds items and shows a
 * fill bar, which a sealed prize must never do. The opening is pure motion — see
 * {@link GiftbagTimeline} — with no boss bar or captions narrating it.
 */
final class GiftbagService implements Listener {
    private static final TextColor VIOLET = TextColor.color(0xB56CFF);
    private static final TextColor MYTHIC = TextColor.color(0x53E5FF);
    private static final int YES_SLOT = 11;
    private static final int CONTENTS_SLOT = 13;
    private static final int NO_SLOT = 15;
    /** The contents list mirrors the crate odds pages: rewards from the first slot, Back bottom left. */
    private static final int CONTENTS_BACK_SLOT = 45;
    /** What the bag is made of. Inert: no use, no recipe, and no loom pattern once unset. */
    static final Material BAG_MATERIAL = Material.FLOWER_BANNER_PATTERN;
    /** Prizes that circle the bag before it opens. */
    private static final int ORBITERS = 8;

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
        final GiftbagCatalog.Entry reward;
        final Location anchor;
        final ItemDisplay bag;
        final List<ItemDisplay> orbiters;
        final double grandeur;
        final int duration;
        ItemDisplay prize;
        TextDisplay caption;
        int elapsed;
        int burstAt = -1;
        GiftbagTimeline.Phase phase;
        BukkitTask task;

        Reveal(UUID playerId, GiftbagStore.Pending pending, GiftbagCatalog.Entry reward, Location anchor,
                ItemDisplay bag, List<ItemDisplay> orbiters, double grandeur, int duration) {
            this.playerId = playerId;
            this.pending = pending;
            this.reward = reward;
            this.anchor = anchor;
            this.bag = bag;
            this.orbiters = orbiters;
            this.grandeur = grandeur;
            this.duration = duration;
        }

        List<Display> displays() {
            List<Display> all = new ArrayList<>(orbiters);
            all.add(bag);
            if (prize != null) all.add(prize);
            if (caption != null) all.add(caption);
            return all;
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
        ItemStack bag = new ItemStack(BAG_MATERIAL);
        ItemMeta meta = bag.getItemMeta();
        meta.setMaxStackSize(1);
        meta.displayName(Component.text("Season " + season + " Mythic Giftbag", MYTHIC)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                line("Contains one random reward.", NamedTextColor.GRAY),
                line("One Giftbag. One roll.", NamedTextColor.GRAY),
                Component.empty(),
                line("Season " + season + " gear, cosmetics, Shards,", NamedTextColor.LIGHT_PURPLE),
                line("and three exceptionally rare mythic items.", NamedTextColor.LIGHT_PURPLE),
                Component.empty(),
                line("Right-click to open", NamedTextColor.AQUA),
                line("Can be traded", NamedTextColor.DARK_GRAY),
                Component.text("Mythic", MYTHIC).decoration(TextDecoration.ITALIC, false)
        ));
        meta.getPersistentDataContainer().set(markerKey, PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(seasonKey, PersistentDataType.INTEGER, season);
        meta.getPersistentDataContainer().set(serialKey, PersistentDataType.STRING, UUID.randomUUID().toString());
        NamespacedKey model = NamespacedKey.fromString("mgx:mythic_giftbag");
        if (model != null) meta.setItemModel(model);
        meta.setEnchantmentGlintOverride(true);
        bag.setItemMeta(meta);
        inert(bag);
        SentinelHub.minted(SentinelEngine.Kind.GIFTBAG, 1L);
        return bag;
    }

    /** Strips the base item's own behaviour: a banner pattern that no loom accepts. */
    private static void inert(ItemStack bag) {
        bag.unsetData(io.papermc.paper.datacomponent.DataComponentTypes.PROVIDES_BANNER_PATTERNS);
    }

    ItemStack preview(int season) {
        return SentinelHub.quietly(() -> create(season));
    }

    boolean isGiftbag(ItemStack item) {
        return item != null && !item.getType().isAir()
                && item.getPersistentDataContainer().has(markerKey, PersistentDataType.BYTE);
    }

    /**
     * Rebuilds a Giftbag minted as a bundle into the inert bag, keeping its season and
     * serial, and hands back anything a player had stuffed inside it.
     */
    private void modernize(Player player) {
        List<ItemStack> spilled = new ArrayList<>();
        modernize(player.getInventory(), spilled);
        modernize(player.getEnderChest(), spilled);
        for (ItemStack item : spilled) {
            player.getInventory().addItem(item).values()
                    .forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
        }
        if (!spilled.isEmpty()) tell(player, "Items inside your Giftbag were returned to you.", NamedTextColor.GRAY);
    }

    private void modernize(Inventory inventory, List<ItemStack> spilled) {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (!isGiftbag(item) || item.getType() == BAG_MATERIAL) continue;
            if (item.getItemMeta() instanceof BundleMeta bundle) {
                bundle.getItems().stream().filter(stored -> stored != null && !stored.getType().isAir())
                        .forEach(stored -> spilled.add(stored.clone()));
            }
            ItemStack rebuilt = item.withType(BAG_MATERIAL);
            if (rebuilt.getItemMeta() instanceof BundleMeta leftover) {
                leftover.setItems(null);
                rebuilt.setItemMeta(leftover);
            }
            rebuilt.unsetData(io.papermc.paper.datacomponent.DataComponentTypes.BUNDLE_CONTENTS);
            inert(rebuilt);
            inventory.setItem(slot, rebuilt);
        }
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
        modernize(player);
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
                Component.text("Open Mythic Giftbag?", VIOLET));
        holder.inventory = menu;
        fill(menu, Material.PURPLE_STAINED_GLASS_PANE);
        menu.setItem(YES_SLOT, MenuItems.detailed(Material.LIME_CONCRETE, "Open Giftbag", List.of(
                line("Consumes exactly one Giftbag.", NamedTextColor.GRAY),
                line("The result is selected before the reveal begins.", NamedTextColor.GRAY))));
        menu.setItem(CONTENTS_SLOT, preview(season));
        menu.setItem(NO_SLOT, MenuItems.detailed(Material.RED_CONCRETE, "Keep Giftbag", List.of(
                line("Close this screen without consuming it.", NamedTextColor.GRAY))));
        player.openInventory(menu);
        sound(player, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 0.55f);
    }

    private void openContents(Player player, int season) {
        Menu holder = new Menu(Screen.CONTENTS, season);
        Inventory menu = Bukkit.createInventory(holder, 54,
                Component.text("Mythic Giftbag Contents", MYTHIC));
        holder.inventory = menu;
        List<GiftbagCatalog.Entry> rewards = GiftbagCatalog.forSeason(season);
        long total = liveTotal(rewards);
        for (int index = 0; index < rewards.size() && index < CONTENTS_BACK_SLOT; index++) {
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
            menu.setItem(index, icon);
        }
        menu.setItem(CONTENTS_BACK_SLOT, MenuItems.button(Material.BARRIER, "Back"));
        player.openInventory(menu);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Menu holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getClickedInventory() != event.getInventory()) return;
        int slot = event.getRawSlot();
        if (holder.screen == Screen.CONTENTS) {
            if (slot == CONTENTS_BACK_SLOT) openConfirm(player, holder.season);
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
        startReveal(player, pending, reward);
    }

    // ------------------------------------------------------------------ the opening

    private void startReveal(Player player, GiftbagStore.Pending pending, GiftbagCatalog.Entry reward) {
        Location eye = player.getEyeLocation();
        org.bukkit.util.Vector ahead = eye.getDirection().setY(0);
        if (ahead.lengthSquared() < 1.0e-4) ahead = new org.bukkit.util.Vector(0, 0, 1);
        Location anchor = eye.clone().add(ahead.normalize().multiply(3.2)).add(0, -0.25, 0);
        anchor.setYaw(0f);
        anchor.setPitch(0f);
        int season = pending.season();
        ItemDisplay bag = spawnItem(anchor, preview(season), true, Color.fromRGB(181, 108, 255));
        List<ItemDisplay> orbiters = new ArrayList<>();
        for (ItemStack prize : orbitPrizes(season)) {
            orbiters.add(spawnItem(anchor, prize, false, null));
        }
        int duration = Math.max(40, variables.integer("season.giftbag.animation-ticks"));
        Reveal reveal = new Reveal(player.getUniqueId(), pending, reward, anchor, bag, orbiters,
                grandeur(reward.rarity()), duration);
        reveals.put(player.getUniqueId(), reveal);
        forAudience(reveal, viewer -> {
            sound(viewer, Sound.BLOCK_BEACON_ACTIVATE, 1.4f, .6f);
            sound(viewer, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.6f, .5f);
        });
        callOver(player, anchor);
        reveal.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> tick(reveal), 1L, 1L);
    }

    /** A spread of what the bag could hold, rarest first, so the ring always shows the chase. */
    private List<ItemStack> orbitPrizes(int season) {
        List<GiftbagCatalog.Entry> pool = new ArrayList<>(GiftbagCatalog.forSeason(season));
        pool.sort(java.util.Comparator.comparingLong(this::liveWeight));
        List<ItemStack> prizes = new ArrayList<>();
        java.util.Set<GiftbagCatalog.Kind> shardsShown = java.util.EnumSet.noneOf(GiftbagCatalog.Kind.class);
        for (GiftbagCatalog.Entry entry : pool) {
            if (prizes.size() >= ORBITERS) break;
            if (liveWeight(entry) <= 0) continue;
            // One Shard stack is enough; the rest of the ring should be different things.
            if (entry.kind() == GiftbagCatalog.Kind.SHARDS && !shardsShown.add(entry.kind())) continue;
            try {
                prizes.add(rewardPreview(entry, season));
            } catch (RuntimeException ignored) {
                // A prize that cannot be drawn simply stays off the ring.
            }
        }
        return prizes;
    }

    private ItemDisplay spawnItem(Location at, ItemStack stack, boolean glowing, Color glow) {
        return at.getWorld().spawn(at, ItemDisplay.class, entity -> {
            entity.setItemStack(stack);
            entity.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            entity.setBillboard(Display.Billboard.FIXED);
            entity.setBrightness(new Display.Brightness(15, 15));
            entity.setInterpolationDuration(2);
            entity.setInterpolationDelay(0);
            entity.setGravity(false);
            entity.setInvulnerable(true);
            entity.setPersistent(false);
            entity.setViewRange(Math.max(0.5f, variables.integer("season.giftbag.viewer-radius") / 64f));
            entity.setTransformation(transform(0, 0, 0, 0f, 0f));
            if (glowing) {
                entity.setGlowing(true);
                if (glow != null) entity.setGlowColorOverride(glow);
            }
        });
    }

    private static Transformation transform(double x, double y, double z, float yaw, float scale) {
        return new Transformation(new Vector3f((float) x, (float) y, (float) z),
                new Quaternionf().rotateY(yaw), new Vector3f(scale, scale, scale), new Quaternionf());
    }

    private static void move(Display display, Transformation transformation) {
        if (!display.isValid()) return;
        display.setInterpolationDelay(0);
        display.setTransformation(transformation);
    }

    private void tick(Reveal reveal) {
        Player player = Bukkit.getPlayer(reveal.playerId);
        if (player == null || !player.isOnline() || !reveal.bag.isValid()
                || !player.getWorld().equals(reveal.anchor.getWorld())) {
            cleanup(reveal);
            return;
        }
        reveal.elapsed++;
        if (reveal.burstAt >= 0) {
            settle(reveal, player);
            return;
        }
        double t = GiftbagTimeline.progress(reveal.elapsed, reveal.duration);
        GiftbagTimeline.Phase phase = GiftbagTimeline.phase(t);
        if (phase != reveal.phase) {
            reveal.phase = phase;
            enter(reveal, phase);
        }
        animateBag(reveal, t);
        animateRing(reveal, t);
        effects(reveal, t, phase);
        if (reveal.elapsed >= reveal.duration) burst(reveal, player);
    }

    private void animateBag(Reveal reveal, double t) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double shake = GiftbagTimeline.shake(t);
        double bob = Math.sin(reveal.elapsed * 0.12) * 0.06;
        move(reveal.bag, transform(
                shake == 0 ? 0 : random.nextDouble(-shake, shake),
                GiftbagTimeline.bagLift(t) + bob + (shake == 0 ? 0 : random.nextDouble(-shake, shake)),
                shake == 0 ? 0 : random.nextDouble(-shake, shake),
                (float) GiftbagTimeline.bagSpin(t, reveal.duration),
                (float) GiftbagTimeline.bagScale(t, reveal.grandeur)));
    }

    private void animateRing(Reveal reveal, double t) {
        int count = reveal.orbiters.size();
        double radius = GiftbagTimeline.orbitRadius(t, reveal.grandeur);
        float scale = (float) GiftbagTimeline.orbitScale(t);
        for (int index = 0; index < count; index++) {
            double angle = GiftbagTimeline.orbitAngle(t, index, count, reveal.duration);
            move(reveal.orbiters.get(index), transform(
                    Math.cos(angle) * radius,
                    GiftbagTimeline.orbitHeight(t, index, count, reveal.duration, reveal.grandeur),
                    Math.sin(angle) * radius,
                    (float) (-angle + Math.PI / 2), scale));
        }
    }

    /** The one-off beat that marks each phase starting. */
    private void enter(Reveal reveal, GiftbagTimeline.Phase phase) {
        switch (phase) {
            case RISE -> { }
            case ORBIT -> forAudience(reveal, viewer -> {
                sound(viewer, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1f, .8f);
                sound(viewer, Sound.ENTITY_ILLUSIONER_CAST_SPELL, .6f, 1.3f);
                if (effects(viewer)) {
                    viewer.spawnParticle(Particle.END_ROD, reveal.anchor, 40, .15, .15, .15, .12);
                }
            });
            case CONVERGE -> forAudience(reveal, viewer -> {
                sound(viewer, Sound.BLOCK_PORTAL_TRIGGER, .45f, 1.35f);
                sound(viewer, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1f, .6f);
            });
            case CHARGE -> {
                reveal.orbiters.forEach(orbiter -> {
                    if (orbiter.isValid()) orbiter.remove();
                });
                reveal.bag.setGlowColorOverride(Color.fromRGB(83, 229, 255));
                forAudience(reveal, viewer -> {
                    sound(viewer, Sound.BLOCK_BEACON_POWER_SELECT, 1f, .5f);
                    sound(viewer, Sound.BLOCK_CONDUIT_ACTIVATE, 1f, .7f);
                    if (effects(viewer)) {
                        viewer.spawnParticle(Particle.ELECTRIC_SPARK, reveal.anchor, 30, .3, .3, .3, .25);
                    }
                });
            }
        }
    }

    /** Particles and the rhythmic sound bed for one buildup tick. */
    private void effects(Reveal reveal, double t, GiftbagTimeline.Phase phase) {
        int density = Math.max(1, variables.integer("season.giftbag.particle-density"));
        boolean beat = reveal.elapsed % GiftbagTimeline.beatPeriod(t) == 0;
        Particle.DustTransition violetToCyan = new Particle.DustTransition(
                Color.fromRGB(181, 108, 255), Color.fromRGB(83, 229, 255), 1.1f);
        for (Player viewer : viewers(reveal)) {
            if (beat) {
                if (phase == GiftbagTimeline.Phase.CHARGE) {
                    sound(viewer, Sound.ENTITY_WARDEN_HEARTBEAT, 1.2f, 1.0f + (float) GiftbagTimeline.within(t) * .5f);
                } else if (phase != GiftbagTimeline.Phase.RISE) {
                    sound(viewer, Sound.BLOCK_AMETHYST_BLOCK_CHIME, .8f, GiftbagTimeline.chimePitch(t));
                }
            }
            if (!effects(viewer)) continue;
            switch (phase) {
                case RISE -> {
                    double lift = GiftbagTimeline.bagLift(t);
                    for (int spoke = 0; spoke < 3; spoke++) {
                        double angle = reveal.elapsed * .35 + spoke * Math.PI * 2 / 3;
                        viewer.spawnParticle(Particle.DUST_COLOR_TRANSITION, reveal.anchor.clone().add(
                                Math.cos(angle) * .9, lift - .4, Math.sin(angle) * .9), density, .02, .02, .02, 0,
                                violetToCyan);
                    }
                    viewer.spawnParticle(Particle.REVERSE_PORTAL, reveal.anchor.clone().add(0, lift, 0),
                            density * 2, .35, .2, .35, .02);
                }
                case ORBIT, CONVERGE -> {
                    int count = reveal.orbiters.size();
                    double radius = GiftbagTimeline.orbitRadius(t, reveal.grandeur);
                    for (int index = 0; index < count; index++) {
                        double angle = GiftbagTimeline.orbitAngle(t, index, count, reveal.duration);
                        Location at = reveal.anchor.clone().add(Math.cos(angle) * radius,
                                GiftbagTimeline.orbitHeight(t, index, count, reveal.duration, reveal.grandeur),
                                Math.sin(angle) * radius);
                        viewer.spawnParticle(Particle.DUST_COLOR_TRANSITION, at, 1, 0, 0, 0, 0, violetToCyan);
                    }
                    if (phase == GiftbagTimeline.Phase.CONVERGE) {
                        viewer.spawnParticle(Particle.REVERSE_PORTAL, reveal.anchor, density * 3, 1.4, .8, 1.4, .06);
                        if (reveal.elapsed % 3 == 0) {
                            viewer.spawnParticle(Particle.ELECTRIC_SPARK, reveal.anchor, density, .25, .25, .25, .05);
                        }
                    } else if (reveal.elapsed % 4 == 0) {
                        viewer.spawnParticle(Particle.ENCHANT, reveal.anchor, density * 3, .6, .6, .6, .6);
                    }
                }
                case CHARGE -> {
                    double local = GiftbagTimeline.within(t);
                    viewer.spawnParticle(Particle.END_ROD, reveal.anchor.clone().add(0, .6 + local * 1.4, 0),
                            density, .05, local * .7, .05, .01);
                    // A pillar the neighbours can see over the trees, taller the rarer the prize.
                    int height = (int) Math.round((6 + 18 * reveal.grandeur) * local);
                    for (int step = 0; step < height; step += 2) {
                        viewer.spawnParticle(Particle.DUST, reveal.anchor.clone().add(0, 1 + step, 0), 1,
                                .08, .25, .08, 0, new Particle.DustOptions(Color.fromRGB(83, 229, 255), 1.6f));
                    }
                    viewer.spawnParticle(Particle.DUST_COLOR_TRANSITION, reveal.anchor, density * 3,
                            .55, .55, .55, 0, violetToCyan);
                    for (int spoke = 0; spoke < 6; spoke++) {
                        double angle = spoke * Math.PI / 3 + reveal.elapsed * .5;
                        double reach = 2.4 * (1 - local);
                        viewer.spawnParticle(Particle.GLOW, reveal.anchor.clone().add(Math.cos(angle) * reach,
                                0, Math.sin(angle) * reach), 1, 0, 0, 0, 0);
                    }
                }
            }
        }
    }

    /** The bag bursts, the prize takes its place, and the prize is paid. */
    private void burst(Reveal reveal, Player player) {
        reveal.burstAt = reveal.elapsed;
        GiftbagCatalog.Entry reward = reveal.reward;
        int season = reveal.pending.season();
        Color colour = Color.fromRGB(reward.rarity().colour);
        if (reveal.bag.isValid()) reveal.bag.remove();
        reveal.orbiters.forEach(orbiter -> {
            if (orbiter.isValid()) orbiter.remove();
        });
        reveal.prize = spawnItem(reveal.anchor, rewardPreview(reward, season), true, colour);
        reveal.caption = reveal.anchor.getWorld().spawn(reveal.anchor.clone().add(0, 1.35, 0), TextDisplay.class, text -> {
            text.text(Component.text(reward.rarity().label.toUpperCase(Locale.ROOT),
                            TextColor.color(reward.rarity().colour), TextDecoration.BOLD)
                    .append(Component.newline())
                    .append(Component.text(rewardName(reward, season), NamedTextColor.WHITE, TextDecoration.BOLD)));
            text.setBillboard(Display.Billboard.CENTER);
            text.setAlignment(TextDisplay.TextAlignment.CENTER);
            text.setShadowed(true);
            text.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            text.setBrightness(new Display.Brightness(15, 15));
            text.setPersistent(false);
            text.setViewRange(Math.max(0.5f, variables.integer("season.giftbag.viewer-radius") / 64f));
            text.setInterpolationDuration(6);
            text.setTransformation(transform(0, 0, 0, 0f, 0f));
        });
        boolean mythicItem = reward.rarity() == GiftbagCatalog.Rarity.MYTHIC_ITEM;
        double grandeur = reveal.grandeur;
        // Volume is also range in Minecraft: a 3.0 burst carries about 48 blocks, which is
        // what turns a good pull into something the neighbours come running for.
        float carry = (float) (1.0 + grandeur * 2.0);
        int rays = (int) Math.round(48 + 72 * grandeur);
        forAudience(reveal, viewer -> {
            if (effects(viewer)) {
                viewer.spawnParticle(Particle.FLASH, reveal.anchor, 1, 0, 0, 0, 0, colour);
                viewer.spawnParticle(Particle.SONIC_BOOM, reveal.anchor, 1, 0, 0, 0, 0);
                viewer.spawnParticle(Particle.TOTEM_OF_UNDYING, reveal.anchor,
                        (int) Math.round(90 + 150 * grandeur), .2, .2, .2, .75 + grandeur * .5);
                // A shell of sparks thrown straight outward: count 0 makes the offset a velocity.
                for (int ray = 0; ray < rays; ray++) {
                    double polar = Math.acos(1 - 2 * (ray + .5) / rays);
                    double azimuth = Math.PI * (1 + Math.sqrt(5)) * ray;
                    double speed = .45 + grandeur * .5;
                    viewer.spawnParticle(Particle.END_ROD, reveal.anchor, 0,
                            Math.sin(polar) * Math.cos(azimuth), Math.cos(polar),
                            Math.sin(polar) * Math.sin(azimuth), speed);
                }
                for (int ray = 0; ray < 36; ray++) {
                    double angle = ray * Math.PI * 2 / 36;
                    viewer.spawnParticle(Particle.FIREWORK, reveal.anchor, 0, Math.cos(angle), 0, Math.sin(angle), .38);
                }
                if (grandeur >= 0.45) {
                    // Explosion emitters and fireworks are drawn far past the usual particle
                    // range, so this is the part somebody across the valley actually sees.
                    viewer.spawnParticle(Particle.EXPLOSION_EMITTER, reveal.anchor, 1, 0, 0, 0, 0);
                    for (int step = 0; step < 26; step += 2) {
                        viewer.spawnParticle(Particle.FIREWORK, reveal.anchor.clone().add(0, step, 0),
                                (int) Math.round(2 + 4 * grandeur), .35, .35, .35, .06);
                    }
                }
            }
            sound(viewer, Sound.ENTITY_GENERIC_EXPLODE, .55f * carry, 1.5f);
            sound(viewer, Sound.ITEM_TOTEM_USE, .7f * carry, 1.1f);
            sound(viewer, Sound.UI_TOAST_CHALLENGE_COMPLETE, .9f, 1.05f);
            if (grandeur >= 0.45) sound(viewer, Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST_FAR, carry, 1.1f);
            if (mythicItem) {
                sound(viewer, Sound.ENTITY_ENDER_DRAGON_GROWL, carry, 1.2f);
                sound(viewer, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, carry, 1.6f);
            }
        });
        // Harmless: the effect strike has no damage, no fire and no block change, and it is
        // the one cue that reaches players who are nowhere near the opening.
        if (mythicItem) reveal.anchor.getWorld().strikeLightningEffect(reveal.anchor);
        player.showTitle(Title.title(
                Component.text(reward.rarity().label, TextColor.color(reward.rarity().colour), TextDecoration.BOLD),
                Component.text(rewardName(reward, season), NamedTextColor.GOLD, TextDecoration.BOLD),
                Title.Times.times(Duration.ofMillis(80), Duration.ofSeconds(3), Duration.ofMillis(700))));
        deliver(player, reveal.pending, reward);
        announce(player, reward, season);
        ServerEvent.of("giftbag_opened", ServerEvent.CATEGORY_CRATE, player.getUniqueId(), player.getName(),
                plugin::recordServerEvent)
                .summary(player.getName() + " opened a Season " + season + " Giftbag")
                .detail("reward", reward.displayName())
                .detail("rarity", reward.rarity().label)
                .detail("spin_id", reveal.pending.spinId().toString())
                .record();
    }

    /** The prize idles in the air, then flies to its owner and the stage clears. */
    private void settle(Reveal reveal, Player player) {
        int since = reveal.elapsed - reveal.burstAt;
        int hold = Math.max(10, variables.integer("season.giftbag.settle-ticks"));
        Color colour = Color.fromRGB(reveal.reward.rarity().colour);
        if (since <= hold) {
            float scale = (float) GiftbagTimeline.revealScale(since, reveal.grandeur);
            if (reveal.prize != null) {
                move(reveal.prize, transform(0, .15 + Math.sin(since * .12) * .08, 0, since * .07f, scale));
            }
            if (reveal.caption != null && since == 2) move(reveal.caption, transform(0, 0, 0, 0f, 1f));
            if (since % 2 == 0) {
                forAudience(reveal, viewer -> {
                    if (!effects(viewer)) return;
                    double angle = since * .3;
                    for (int spoke = 0; spoke < 3; spoke++) {
                        double around = angle + spoke * Math.PI * 2 / 3;
                        viewer.spawnParticle(Particle.DUST, reveal.anchor.clone().add(Math.cos(around) * 1.1,
                                -.4 + (since % 20) / 20.0 * 1.2, Math.sin(around) * 1.1), 1, 0, 0, 0, 0,
                                new Particle.DustOptions(colour, 1.3f));
                    }
                    if (since % 6 == 0) viewer.spawnParticle(Particle.GLOW, reveal.anchor, 2, .5, .5, .5, 0);
                });
            }
            return;
        }
        int flight = since - hold;
        if (flight == 1) {
            Location target = player.getEyeLocation().subtract(0, .4, 0);
            org.bukkit.util.Vector towards = target.toVector().subtract(reveal.anchor.toVector());
            if (reveal.prize != null) {
                reveal.prize.setInterpolationDuration(8);
                move(reveal.prize, transform(towards.getX(), towards.getY(), towards.getZ(), 3f, .2f));
            }
            if (reveal.caption != null) move(reveal.caption, transform(0, 0, 0, 0f, 0f));
        }
        if (flight == 9) {
            sound(player, Sound.ENTITY_ITEM_PICKUP, 1f, .8f);
            sound(player, Sound.BLOCK_AMETHYST_CLUSTER_BREAK, .8f, 1.4f);
            cleanup(reveal);
        }
    }

    private void forAudience(Reveal reveal, java.util.function.Consumer<Player> action) {
        viewers(reveal).forEach(action);
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
        modernize(event.getPlayer());
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
        for (Display display : reveal.displays()) {
            if (display.isValid()) display.remove();
        }
        reveals.remove(reveal.playerId, reveal);
    }

    private void announce(Player winner, GiftbagCatalog.Entry reward, int season) {
        Component message = Component.text("Mythic Giftbag » ", MYTHIC)
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

    /** How big a win is coming, which is how far the bag swells before it opens. */
    static double grandeur(GiftbagCatalog.Rarity rarity) {
        return switch (rarity) {
            case RARE -> 0.0;
            case EXCLUSIVE -> 0.45;
            case MYTHIC -> 0.7;
            case MYTHIC_ITEM -> 1.0;
        };
    }

    /**
     * Whoever can see the opening right now, recomputed every tick: someone who runs over
     * mid-animation should get the sounds and sparks, not just the silent entities.
     */
    private List<Player> viewers(Reveal reveal) {
        double radius = Math.max(8, variables.integer("season.giftbag.viewer-radius"));
        List<Player> viewers = new ArrayList<>();
        for (Player viewer : reveal.anchor.getWorld().getPlayers()) {
            if (viewer.getLocation().distanceSquared(reveal.anchor) <= radius * radius) viewers.add(viewer);
        }
        Player owner = Bukkit.getPlayer(reveal.playerId);
        if (owner != null && owner.isOnline() && !viewers.contains(owner)) viewers.add(owner);
        return viewers;
    }

    /** Tells the wider neighbourhood where to look, once, as the seal starts to give. */
    private void callOver(Player owner, Location anchor) {
        double radius = Math.max(8, variables.integer("season.giftbag.notice-radius"));
        Component line = Component.text("GIFTBAG » ", MYTHIC, TextDecoration.BOLD)
                .append(Component.text(owner.getName() + " is opening a Mythic Giftbag nearby. ",
                        NamedTextColor.WHITE))
                .append(Component.text(Math.round(anchor.getX()) + ", " + Math.round(anchor.getY())
                        + ", " + Math.round(anchor.getZ()), VIOLET));
        for (Player viewer : anchor.getWorld().getPlayers()) {
            if (viewer.equals(owner) || viewer.getLocation().distanceSquared(anchor) > radius * radius) continue;
            if (!settings.isEnabled(viewer.getUniqueId(), PlayerSettingsStore.Setting.CRATE_ANNOUNCEMENTS)) continue;
            viewer.sendMessage(line);
            sound(viewer, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.2f, .7f);
        }
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
