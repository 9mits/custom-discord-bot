package bot.mgx.accessbridge;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

/** Physical crate openings, published odds, hourly keys, and crash-safe pending claims. */
final class CrateService implements CommandExecutor, TabCompleter, Listener {
    private static final TextColor ORANGE = TextColor.color(0xFF9900);
    private static final long ONLINE_PULSE_TICKS = 20L * 60L;
    /** One second is often enough for a bar measured in minutes, and costs nothing. */
    private static final long KEY_BAR_TICKS = 20L;
    private static final int HUB_OPEN_SLOT = 13;
    private static final int HUB_ODDS_SLOT = 15;
    private static final int HUB_AUTO_SLOT = 22;
    private static final int RESULT_AGAIN_SLOT = 11;
    private static final int RESULT_AUTO_SLOT = 15;
    private static final int RESULT_BACK_SLOT = 22;
    private static final int CONFIRM_YES_SLOT = 11;
    private static final int CONFIRM_NO_SLOT = 15;
    private static final int ODDS_PER_PAGE = 45;
    private static final int PREVIOUS_SLOT = 45;
    private static final int NEXT_SLOT = 53;
    private static final int REEL_FIRST = 19;
    private static final int REEL_LAST = 25;
    private static final int WINNING_SLOT = 22;
    private static final int REEL_FRAMES = 31;
    /**
     * An auto run keeps the reel and its slowdown — that is the part worth watching —
     * but at roughly a quarter of the length, so a stack of keys is minutes rather than
     * a quarter of an hour.
     */
    private static final int FAST_REEL_FRAMES = 15;

    private enum Screen {
        HUB,
        ODDS,
        ROLL,
        RESULT,
        CONFIRM
    }

    private static final class CrateMenu implements InventoryHolder {
        private final Screen screen;
        private final int page;
        private Inventory inventory;

        CrateMenu(Screen screen, int page) {
            this.screen = screen;
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class RollSession {
        private final UUID playerId;
        private final CrateStore.Pending pending;
        private final CrateCatalog.Reward reward;
        private final Inventory inventory;
        private final boolean fast;
        private int frame;
        private BukkitTask task;

        RollSession(
                UUID playerId,
                CrateStore.Pending pending,
                CrateCatalog.Reward reward,
                Inventory inventory,
                boolean fast
        ) {
            this.playerId = playerId;
            this.pending = pending;
            this.reward = reward;
            this.inventory = inventory;
            this.fast = fast;
        }

        int frames() {
            return fast ? FAST_REEL_FRAMES : REEL_FRAMES;
        }

        /** Ticks until the next frame. Both curves decelerate into the win. */
        long delay() {
            if (fast) {
                return frame < 8 ? 1L : frame < 12 ? 2L : 3L;
            }
            return frame < 14 ? 2L : frame < 22 ? 3L : frame < 27 ? 5L : 8L;
        }

        /** The last few frames ping instead of click, wherever the reel ends. */
        boolean settling() {
            return frame >= frames() - (fast ? 4 : 7);
        }
    }

    /** Keys an hour online is worth. Doubled alongside the 45% cut to the odds, so a
     *  player opens twice as often for roughly the loot per hour they had before. */
    static final int KEYS_PER_HOUR = 2;
    static final int BOOSTER_KEYS_PER_HOUR = 3;

    private final MGXAccessBridge plugin;
    private final CrateStore store;
    private final CrateItems items;
    private final CosmeticStore cosmetics;
    private final CosmeticItems cosmeticItems;
    private final CosmeticEffectService effects;
    private final PlayerSettingsStore settings;
    private final PlayerPerkService perks;
    private final SpecialItemService specialItems;
    private final Map<UUID, RollSession> sessions = new HashMap<>();
    /** Players part way through an auto run, and how many crates are still owed. */
    private final Map<UUID, Integer> autoRuns = new HashMap<>();
    private final Map<UUID, Long> onlineCreditStarted = new HashMap<>();
    private final Map<UUID, BossBar> keyBars = new HashMap<>();
    private BukkitTask keyBarTask;
    private BukkitTask hourlyTask;

    CrateService(
            MGXAccessBridge plugin,
            CrateStore store,
            CrateItems items,
            CosmeticStore cosmetics,
            CosmeticItems cosmeticItems,
            CosmeticEffectService effects,
            PlayerSettingsStore settings,
            PlayerPerkService perks,
            SpecialItemService specialItems
    ) {
        this.plugin = plugin;
        this.store = store;
        this.items = items;
        this.cosmetics = cosmetics;
        this.cosmeticItems = cosmeticItems;
        this.effects = effects;
        this.settings = settings;
        this.perks = perks;
        this.specialItems = specialItems;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        args = CommandArgs.withoutEchoedSender(sender.getName(), args);
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players open crates. Use /mgxadmin give to issue keys.");
            return true;
        }
        if (args.length == 0) {
            openHub(player);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "open", "spin" -> start(player);
            case "odds", "rewards" -> openOdds(player, parsePage(args));
            case "claim" -> {
                if (sessions.containsKey(player.getUniqueId())) {
                    PlayerMenuService.error(player, "Your crate is still opening.");
                    return true;
                }
                if (!deliverPending(player, true)) {
                    player.sendMessage(PlayerMenuService.prefix().append(Component.text(
                            "You do not have a reward waiting.", NamedTextColor.GRAY
                    )));
                }
            }
            default -> PlayerMenuService.error(
                    player, "Use /crate, /crate open, /crate odds, or /crate claim."
            );
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return Stream.of("open", "odds", "claim")
                    .filter(value -> value.startsWith(prefix))
                    .toList();
        }
        return List.of();
    }

    void openHub(Player player) {
        items.upgradeLegacyKeys(player);
        if (!sessions.containsKey(player.getUniqueId())) {
            deliverPending(player, false);
        }
        deliverBankedKeys(player, false);
        CrateMenu holder = new CrateMenu(Screen.HUB, 1);
        Inventory inventory = Bukkit.createInventory(
                holder, 27, Component.text("Mysterious Crates", ORANGE)
        );
        holder.inventory = inventory;
        fillHub(inventory);
        inventory.setItem(11, named(
                items.key(1),
                "Your Keys",
                "In inventory: " + items.count(player),
                "Banked: " + store.bankedKeys(player.getUniqueId()),
                "Next keys in " + remainingTime(
                        store.millisUntilNextKey(player.getUniqueId())
                ) + ".",
                keysPerHour(player) + " keys are earned per online hour."
        ));
        inventory.setItem(HUB_OPEN_SLOT, MenuItems.button(
                Material.CHEST,
                "Open Mysterious Crate",
                "Spends one key."
        ));
        inventory.setItem(HUB_ODDS_SLOT, MenuItems.button(
                Material.BOOK,
                "View Exact Odds",
                "Every percentage shown is exact."
        ));
        inventory.setItem(HUB_AUTO_SLOT, MenuItems.button(
                Material.HOPPER,
                "Auto Open",
                "Opens a crate with every key you hold.",
                "You confirm the number before anything is spent."
        ));
        MenuItems.show(plugin, player, inventory);
    }

    private void openOdds(Player player, int requestedPage) {
        List<CrateCatalog.Reward> rewards = CrateCatalog.all();
        int pageCount = Math.max(1, (rewards.size() + ODDS_PER_PAGE - 1) / ODDS_PER_PAGE);
        int page = Math.max(1, Math.min(pageCount, requestedPage));
        CrateMenu holder = new CrateMenu(Screen.ODDS, page);
        Inventory inventory = Bukkit.createInventory(
                holder, 54, Component.text("Crate Odds " + page + "/" + pageCount, ORANGE)
        );
        holder.inventory = inventory;
        int first = (page - 1) * ODDS_PER_PAGE;
        int last = Math.min(rewards.size(), first + ODDS_PER_PAGE);
        for (int index = first; index < last; index++) {
            inventory.setItem(index - first, items.preview(rewards.get(index), cosmeticItems));
        }
        if (page > 1) {
            inventory.setItem(PREVIOUS_SLOT, MenuItems.button(Material.ARROW, "Previous Page"));
        } else {
            inventory.setItem(PREVIOUS_SLOT, MenuItems.button(Material.BARRIER, "Back"));
        }
        if (page < pageCount) {
            inventory.setItem(NEXT_SLOT, MenuItems.button(Material.ARROW, "Next Page"));
        }
        MenuItems.show(plugin, player, inventory);
    }

    private void start(Player player) {
        UUID playerId = player.getUniqueId();
        if (sessions.containsKey(playerId)) {
            PlayerMenuService.error(player, "Your crate is already opening.");
            return;
        }
        if (store.pending(playerId).isPresent()) {
            if (!deliverPending(player, true)) {
                return;
            }
        }
        long now = System.currentTimeMillis();
        if (items.count(player) <= 0) {
            PlayerMenuService.error(player, "You need a Mysterious Crate Key to open a crate.");
            return;
        }
        int luck = specialItems.crateLuckPercent(player);
        int luckyTotal = CrateCatalog.luckyTotalWeight(luck);
        CrateCatalog.Reward reward = CrateCatalog.rewardAtLucky(
                ThreadLocalRandom.current().nextInt(luckyTotal), luck
        );
        UUID spinId = UUID.randomUUID();
        if (!items.consume(player)) {
            PlayerMenuService.error(player, "Your key moved before it could be consumed.");
            return;
        }
        CrateStore.Pending pending;
        try {
            pending = store.reserve(playerId, spinId, reward.id(), now);
        } catch (IllegalStateException | UncheckedIOException exception) {
            returnKey(player);
            plugin.getLogger().warning("Could not reserve a crate reward: " + exception.getMessage());
            PlayerMenuService.error(player, "That opening could not be saved. Your key was returned.");
            return;
        }

        CrateMenu holder = new CrateMenu(Screen.ROLL, 1);
        Inventory inventory = Bukkit.createInventory(
                holder, 45, Component.text("Opening Mysterious Crate", ORANGE)
        );
        holder.inventory = inventory;
        fillCrate(inventory);
        RollSession session = new RollSession(
                playerId, pending, reward, inventory, autoRuns.containsKey(playerId)
        );
        sessions.put(playerId, session);
        MenuItems.show(plugin, player, inventory);
        player.playSound(player.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.9f, 0.85f);
        advance(session);
    }

    private void advance(RollSession session) {
        Player player = plugin.getServer().getPlayer(session.playerId);
        if (player == null) {
            sessions.remove(session.playerId);
            return;
        }
        if (session.frame >= session.frames()) {
            finish(session, player);
            return;
        }
        for (int slot = REEL_FIRST; slot < REEL_LAST; slot++) {
            session.inventory.setItem(slot, session.inventory.getItem(slot + 1));
        }
        CrateCatalog.Reward preview = CrateCatalog.rewardAt(
                ThreadLocalRandom.current().nextInt(CrateCatalog.TOTAL_WEIGHT)
        );
        session.inventory.setItem(REEL_LAST, items.preview(preview, cosmeticItems));
        player.playSound(
                player.getLocation(),
                session.settling()
                        ? Sound.BLOCK_NOTE_BLOCK_PLING
                        : Sound.BLOCK_WOODEN_BUTTON_CLICK_ON,
                0.55f,
                // The rise is spread across however many frames this reel has, so the
                // fast one climbs to the same pitch it would have reached slowly.
                Math.min(1.8f, 0.7f + (1.1f * session.frame / session.frames()))
        );
        session.frame++;
        session.task = plugin.getServer().getScheduler().runTaskLater(
                plugin, () -> advance(session), session.delay()
        );
    }

    private void finish(RollSession session, Player player) {
        for (int slot = REEL_FIRST; slot <= REEL_LAST; slot++) {
            session.inventory.setItem(slot, slot == WINNING_SLOT
                    ? items.revealedPreview(session.reward, cosmeticItems)
                    : MenuItems.button(Material.BROWN_STAINED_GLASS_PANE, "Crate Panel"));
        }
        session.inventory.setItem(4, MenuItems.button(Material.CHEST, "Crate Opened"));
        session.inventory.setItem(13, MenuItems.button(Material.SPECTRAL_ARROW, "Winning Slot"));
        session.inventory.setItem(31, MenuItems.button(Material.HOPPER, "Reward Locked In"));
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.15f);
        session.task = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            sessions.remove(session.playerId);
            if (!deliverPending(player, true)) {
                autoRuns.remove(session.playerId);
                PlayerMenuService.error(
                        player, "Your inventory is full. Make room, then use /crate claim."
                );
                return;
            }
            afterReward(player, session.reward);
        }, autoRuns.containsKey(session.playerId) ? 10L : 20L);
    }

    /** Shown once a reward lands, so opening another crate never needs the hub. */
    private void openResult(Player player, CrateCatalog.Reward reward) {
        CrateMenu holder = new CrateMenu(Screen.RESULT, 1);
        Inventory inventory = Bukkit.createInventory(
                holder, 27, Component.text("Crate Opened", ORANGE)
        );
        holder.inventory = inventory;
        ItemStack panel = MenuItems.button(Material.BROWN_STAINED_GLASS_PANE, "Crate Panel");
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, panel);
        }
        inventory.setItem(4, items.revealedPreview(reward, cosmeticItems));
        int keys = items.count(player);
        inventory.setItem(RESULT_AGAIN_SLOT, MenuItems.button(
                keys > 0 ? Material.CHEST : Material.BARRIER,
                keys > 0 ? "Open Again" : "No Keys Left",
                keys > 0 ? "Spends one of your " + keys + " keys." : "Earn them by playing."
        ));
        inventory.setItem(RESULT_AUTO_SLOT, MenuItems.button(
                keys > 0 ? Material.HOPPER : Material.BARRIER,
                "Auto Open",
                keys > 0 ? "Opens all " + keys + " of your keys." : "You have no keys to spend.",
                keys > 0 ? "You confirm before anything is spent." : "Earn them by playing."
        ));
        inventory.setItem(RESULT_BACK_SLOT, MenuItems.button(Material.BARRIER, "Close"));
        MenuItems.show(plugin, player, inventory);
    }

    /** Asks once, because the answer spends every key the player owns. */
    private void confirmAutoOpen(Player player) {
        if (sessions.containsKey(player.getUniqueId())) {
            PlayerMenuService.error(player, "Your crate is already opening.");
            return;
        }
        int keys = items.count(player);
        if (keys <= 0) {
            PlayerMenuService.error(player, "You need a Mysterious Crate Key to open a crate.");
            return;
        }
        CrateMenu holder = new CrateMenu(Screen.CONFIRM, 1);
        Inventory inventory = Bukkit.createInventory(
                holder, 27, Component.text("Use all " + keys + " keys?", ORANGE)
        );
        holder.inventory = inventory;
        ItemStack panel = MenuItems.button(Material.BROWN_STAINED_GLASS_PANE, "Crate Panel");
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, panel);
        }
        inventory.setItem(CONFIRM_YES_SLOT, MenuItems.button(
                Material.LIME_CONCRETE,
                "Confirm: spend all " + keys + " keys",
                "Opens " + keys + " crates one after another.",
                "Every key you are holding will be used.",
                "Close the menu part way to keep the rest."
        ));
        inventory.setItem(CONFIRM_NO_SLOT, MenuItems.button(
                Material.RED_CONCRETE,
                "Cancel",
                "Spends nothing. Keeps all " + keys + " keys."
        ));
        inventory.setItem(4, items.key(1));
        MenuItems.show(plugin, player, inventory);
    }

    /**
     * Runs the reel once per key without stopping in between. The count is fixed when
     * the run starts so a key arriving mid-run does not extend it, and closing the menu
     * ends the run with the unspent keys still in the player's inventory.
     */
    private void beginAutoOpen(Player player) {
        int keys = items.count(player);
        if (keys <= 0) {
            PlayerMenuService.error(player, "You need a Mysterious Crate Key to open a crate.");
            return;
        }
        autoRuns.put(player.getUniqueId(), keys);
        start(player);
    }

    /** Called once a reward lands: continues the run, or shows the result screen. */
    private void afterReward(Player player, CrateCatalog.Reward reward) {
        Integer remaining = autoRuns.get(player.getUniqueId());
        if (remaining == null) {
            openResult(player, reward);
            return;
        }
        int left = remaining - 1;
        if (left <= 0 || items.count(player) <= 0) {
            autoRuns.remove(player.getUniqueId());
            player.sendMessage(PlayerMenuService.prefix().append(Component.text(
                    "Auto open finished.", NamedTextColor.GREEN
            )));
            openResult(player, reward);
            return;
        }
        autoRuns.put(player.getUniqueId(), left);
        start(player);
    }

    private boolean deliverPending(Player player, boolean tellWhenFull) {
        CrateStore.Pending pending = store.pending(player.getUniqueId()).orElse(null);
        if (pending == null) {
            items.finishOrphanedRewards(player);
            return false;
        }
        CrateCatalog.Reward reward = CrateCatalog.find(pending.rewardId()).orElse(null);
        if (reward == null) {
            plugin.getLogger().severe("Unknown pending crate reward " + pending.rewardId());
            PlayerMenuService.error(player, "Your saved reward needs an administrator to repair it.");
            return false;
        }
        if (reward.cosmetic()) {
            CosmeticCatalog.Definition definition = CosmeticCatalog.find(reward.cosmeticId()).orElse(null);
            if (definition == null) {
                plugin.getLogger().severe("Unknown pending cosmetic " + reward.cosmeticId());
                return false;
            }
            try {
                cosmetics.mint(player.getUniqueId(), definition.id(), pending.spinId());
                if (!store.complete(player.getUniqueId(), pending.spinId())) {
                    throw new IllegalStateException("The pending cosmetic spin changed during delivery.");
                }
            } catch (IllegalStateException | UncheckedIOException exception) {
                plugin.getLogger().warning("Could not complete cosmetic reward "
                        + pending.spinId() + ": " + exception.getMessage());
                PlayerMenuService.error(player, "Your saved cosmetic could not be delivered yet.");
                return false;
            }
            player.sendMessage(winMessage(reward).append(Component.text(
                    " It is stored in /wardrobe.", NamedTextColor.GRAY
            )));
            recordWin(player, pending, reward);
            if (definition.secret()) {
                effects.playSecretReveal(player);
            }
            return true;
        }
        if (items.carriesReward(player, pending.spinId())) {
            try {
                if (!store.complete(player.getUniqueId(), pending.spinId())) {
                    return false;
                }
            } catch (UncheckedIOException exception) {
                plugin.getLogger().warning("Could not recover crate reward "
                        + pending.spinId() + ": " + exception.getMessage());
                PlayerMenuService.error(player, "Your saved reward could not be recovered yet.");
                return false;
            }
            items.finishReward(player, pending.spinId(), reward);
            player.sendMessage(winMessage(reward));
            recordWin(player, pending, reward);
            return true;
        }
        ItemStack prize = items.reward(reward, pending.spinId());
        if (!canFit(player.getInventory(), prize)) {
            if (tellWhenFull) {
                PlayerMenuService.error(
                        player, "Make room for " + reward.displayName() + ", then use /crate claim."
                );
            }
            return false;
        }
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(prize);
        if (!leftover.isEmpty()) {
            plugin.getLogger().warning("A capacity-checked crate reward did not fit for "
                    + player.getUniqueId());
            items.removeReward(player, pending.spinId());
            return false;
        }
        try {
            if (!store.complete(player.getUniqueId(), pending.spinId())) {
                items.removeReward(player, pending.spinId());
                plugin.getLogger().warning("Pending crate spin changed before delivery for "
                        + player.getUniqueId());
                return false;
            }
        } catch (UncheckedIOException exception) {
            items.removeReward(player, pending.spinId());
            plugin.getLogger().warning("Could not commit crate reward "
                    + pending.spinId() + ": " + exception.getMessage());
            PlayerMenuService.error(player, "Your saved reward could not be delivered yet.");
            return false;
        }
        items.finishReward(player, pending.spinId(), reward);
        player.sendMessage(winMessage(reward));
        recordWin(player, pending, reward);
        return true;
    }

    private void recordWin(
            Player player, CrateStore.Pending pending, CrateCatalog.Reward reward
    ) {
        auditWin(player, pending, reward);
        if (reward.rare()) {
            announceRareWin(player, reward);
        }
    }

    private void announceRareWin(Player player, CrateCatalog.Reward reward) {
        Component announcement = PlayerMenuService.prefix()
                .append(Component.text(player.getName(), NamedTextColor.GOLD, TextDecoration.BOLD))
                .append(Component.text(" opened ", NamedTextColor.WHITE))
                .append(Component.text(reward.displayName(), NamedTextColor.LIGHT_PURPLE,
                        TextDecoration.BOLD))
                .append(Component.text(" from a Mysterious Crate", NamedTextColor.WHITE))
                .append(Component.text(
                        " (chance: " + reward.displayedChance() + ")",
                        NamedTextColor.GRAY
                ));
        if (reward.cosmetic()) {
            announcement = announcement.append(Component.text(
                    " • In existence: " + cosmetics.inExistence(reward.cosmeticId()),
                    NamedTextColor.DARK_AQUA
            ));
        }
        // Every rare win is still announced, but only a sub-0.01% one is loud: a chime
        // players hear several times an hour stops reading as something remarkable.
        boolean chime = reward.jackpot();
        for (Player viewer : plugin.getServer().getOnlinePlayers()) {
            viewer.sendMessage(announcement);
            if (chime) {
                viewer.playSound(
                        viewer.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 0.95f
                );
            }
        }
        plugin.getServer().getConsoleSender().sendMessage(announcement);
    }

    private void auditWin(
            Player player, CrateStore.Pending pending, CrateCatalog.Reward reward
    ) {
        if (!reward.highImpact()) {
            return;
        }
        ServerEvent.of(
                "crate_rare_win",
                ServerEvent.CATEGORY_ECONOMY,
                player.getUniqueId(),
                player.getName(),
                plugin::recordServerEvent
        ).summary(player.getName() + " won " + reward.displayName() + " from a crate")
                .detail("reward", reward.displayName())
                .detail("chance", reward.displayedChance())
                .detail("opening_id", pending.spinId().toString())
                .record();
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player && items.isKey(event.getCurrentItem())) {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    items.upgradeLegacyKeys(player);
                }
            });
        }
        if (!(event.getInventory().getHolder() instanceof CrateMenu menu)
                || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getInventory()) {
            return;
        }
        if (menu.screen == Screen.HUB) {
            if (event.getSlot() == HUB_OPEN_SLOT) {
                start(player);
            } else if (event.getSlot() == HUB_ODDS_SLOT) {
                openOdds(player, 1);
            } else if (event.getSlot() == HUB_AUTO_SLOT) {
                confirmAutoOpen(player);
            }
        } else if (menu.screen == Screen.RESULT) {
            if (event.getSlot() == RESULT_AGAIN_SLOT) {
                start(player);
            } else if (event.getSlot() == RESULT_AUTO_SLOT) {
                confirmAutoOpen(player);
            } else if (event.getSlot() == RESULT_BACK_SLOT) {
                player.closeInventory();
            }
        } else if (menu.screen == Screen.CONFIRM) {
            if (event.getSlot() == CONFIRM_YES_SLOT) {
                beginAutoOpen(player);
            } else if (event.getSlot() == CONFIRM_NO_SLOT) {
                openHub(player);
            }
        } else if (menu.screen == Screen.ODDS) {
            if (event.getSlot() == PREVIOUS_SLOT) {
                if (menu.page == 1) {
                    openHub(player);
                } else {
                    openOdds(player, menu.page - 1);
                }
            } else if (event.getSlot() == NEXT_SLOT) {
                openOdds(player, menu.page + 1);
            }
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof CrateMenu) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player) || !items.isKey(event.getItem().getItemStack())) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                items.upgradeLegacyKeys(player);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof CrateMenu)
                || !(event.getPlayer() instanceof Player player)
                || !autoRuns.containsKey(player.getUniqueId())) {
            return;
        }
        // Every crate in the run replaces the previous screen, which fires a close of
        // its own. Only a player who has not landed in another crate screen a few ticks
        // later actually walked away, and that is what ends the run.
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()
                    || player.getOpenInventory().getTopInventory().getHolder() instanceof CrateMenu) {
                return;
            }
            Integer left = autoRuns.remove(player.getUniqueId());
            if (left != null && left > 0) {
                player.sendMessage(PlayerMenuService.prefix().append(Component.text(
                        "Auto open stopped. " + items.count(player) + " keys left.",
                        NamedTextColor.GRAY
                )));
            }
        }, 3L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        Long previous = onlineCreditStarted.remove(player.getUniqueId());
        long now = System.currentTimeMillis();
        if (previous != null && now > previous) {
            creditOnline(Map.of(player.getUniqueId(), now - previous));
        }
        autoRuns.remove(player.getUniqueId());
        hideKeyBar(player.getUniqueId());
        RollSession session = sessions.remove(player.getUniqueId());
        if (session != null && session.task != null) {
            session.task.cancel();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        items.upgradeLegacyKeys(event.getPlayer());
        onlineCreditStarted.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (event.getPlayer().isOnline()) {
                deliverPending(event.getPlayer(), true);
                deliverBankedKeys(event.getPlayer(), true);
            }
        }, 40L);
    }

    void start() {
        if (hourlyTask != null) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            items.upgradeLegacyKeys(player);
            onlineCreditStarted.put(player.getUniqueId(), now);
        }
        hourlyTask = plugin.getServer().getScheduler().runTaskTimer(
                plugin, this::creditOnlinePlayers, ONLINE_PULSE_TICKS, ONLINE_PULSE_TICKS
        );
        keyBarTask = plugin.getServer().getScheduler().runTaskTimer(
                plugin, this::refreshKeyBars, KEY_BAR_TICKS, KEY_BAR_TICKS
        );
    }

    void stop() {
        creditOnlinePlayers();
        if (hourlyTask != null) {
            hourlyTask.cancel();
            hourlyTask = null;
        }
        if (keyBarTask != null) {
            keyBarTask.cancel();
            keyBarTask = null;
        }
        for (UUID playerId : Set.copyOf(keyBars.keySet())) {
            hideKeyBar(playerId);
        }
        onlineCreditStarted.clear();
        for (RollSession session : sessions.values()) {
            if (session.task != null) {
                session.task.cancel();
            }
        }
        sessions.clear();
    }

    /**
     * A bar counting down to the next hourly key. The store only learns about elapsed
     * time once a minute, so the seconds since that pulse are added here — otherwise
     * the bar would sit still and then jump a minute at a time.
     */
    private void refreshKeyBars() {
        long now = System.currentTimeMillis();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            UUID playerId = player.getUniqueId();
            if (!settings.isEnabled(playerId, PlayerSettingsStore.Setting.KEY_TIMER_BAR)) {
                hideKeyBar(playerId);
                continue;
            }
            Long since = onlineCreditStarted.get(playerId);
            long uncredited = since == null ? 0L : now - since;
            long remaining = KeyTimer.remaining(store.millisUntilNextKey(playerId), uncredited);
            float progress = KeyTimer.progress(remaining, CrateStore.HOURLY_KEY_MILLIS);
            BossBar bar = keyBars.get(playerId);
            if (bar == null) {
                bar = BossBar.bossBar(
                        keyBarTitle(remaining), progress,
                        BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS
                );
                keyBars.put(playerId, bar);
                player.showBossBar(bar);
            } else {
                bar.name(keyBarTitle(remaining));
                bar.progress(progress);
            }
        }
        // A player who logged out between pulses still owns a bar in the map.
        for (UUID playerId : Set.copyOf(keyBars.keySet())) {
            if (plugin.getServer().getPlayer(playerId) == null) {
                keyBars.remove(playerId);
            }
        }
    }

    private static Component keyBarTitle(long remaining) {
        String left = KeyTimer.label(remaining);
        if (left.isEmpty()) {
            return Component.text("Crate key ready", NamedTextColor.GREEN, TextDecoration.BOLD);
        }
        return Component.text("Next crate key in ", NamedTextColor.WHITE)
                .append(Component.text(left, ORANGE, TextDecoration.BOLD));
    }

    private void hideKeyBar(UUID playerId) {
        BossBar bar = keyBars.remove(playerId);
        if (bar == null) {
            return;
        }
        Player player = plugin.getServer().getPlayer(playerId);
        if (player != null) {
            player.hideBossBar(bar);
        }
    }

    private void creditOnlinePlayers() {
        long now = System.currentTimeMillis();
        Map<UUID, Long> elapsed = new HashMap<>();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            Long previous = onlineCreditStarted.get(player.getUniqueId());
            if (previous == null) {
                onlineCreditStarted.put(player.getUniqueId(), now);
            } else if (now > previous) {
                elapsed.put(player.getUniqueId(), now - previous);
            }
        }
        Map<UUID, CrateStore.KeyCredit> credits = creditOnline(elapsed);
        if (credits == null) {
            return;
        }
        elapsed.keySet().forEach(playerId -> onlineCreditStarted.put(playerId, now));
        for (Map.Entry<UUID, CrateStore.KeyCredit> entry : credits.entrySet()) {
            Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player == null) {
                continue;
            }
            int delivered = deliverBankedKeys(player, entry.getValue().earned() > 0);
            if (entry.getValue().earned() > 0 && delivered == 0) {
                player.sendMessage(PlayerMenuService.prefix().append(Component.text(
                        "Your " + entry.getValue().earned() + " hourly crate "
                                + (entry.getValue().earned() == 1 ? "key is" : "keys are")
                                + " banked. Clear inventory space to receive "
                                + (entry.getValue().earned() == 1 ? "it." : "them."),
                        NamedTextColor.GOLD
                )));
            }
        }
    }

    /** What an hour online is worth to this player, before any live event. */
    private int keysPerHour(Player player) {
        return perks.profile(player.getUniqueId()).booster()
                ? BOOSTER_KEYS_PER_HOUR
                : KEYS_PER_HOUR;
    }

    private Map<UUID, CrateStore.KeyCredit> creditOnline(Map<UUID, Long> elapsed) {
        if (elapsed.isEmpty()) {
            return Map.of();
        }
        try {
            Map<UUID, Integer> rates = new HashMap<>();
            int eventRate = plugin.serverEventMultiplier(ServerEventType.KEY);
            elapsed.keySet().forEach(playerId -> rates.put(
                    playerId,
                    (perks.profile(playerId).booster() ? BOOSTER_KEYS_PER_HOUR : KEYS_PER_HOUR)
                            * eventRate
            ));
            return store.creditOnline(elapsed, rates);
        } catch (IllegalArgumentException | ArithmeticException | UncheckedIOException exception) {
            plugin.getLogger().warning("Could not save hourly crate-key progress: "
                    + exception.getMessage());
            return null;
        }
    }

    private int deliverBankedKeys(Player player, boolean notify) {
        int banked = store.bankedKeys(player.getUniqueId());
        if (banked <= 0) {
            return 0;
        }
        int remaining = banked;
        int delivered = 0;
        while (remaining > 0) {
            int batch = Math.min(64, remaining);
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(items.key(batch));
            int rejected = leftover.values().stream().mapToInt(ItemStack::getAmount).sum();
            int accepted = batch - rejected;
            delivered += accepted;
            remaining -= accepted;
            if (accepted < batch) {
                break;
            }
        }
        if (delivered == 0) {
            return 0;
        }
        try {
            int claimed = store.claimBankedKeys(player.getUniqueId(), delivered);
            if (claimed != delivered) {
                items.remove(player, delivered - claimed);
                delivered = claimed;
            }
        } catch (UncheckedIOException exception) {
            items.remove(player, delivered);
            plugin.getLogger().warning("Could not claim banked crate keys: "
                    + exception.getMessage());
            return 0;
        }
        if (notify && delivered > 0) {
            player.sendMessage(PlayerMenuService.prefix().append(Component.text(
                    "You earned " + delivered + " hourly crate "
                            + (delivered == 1 ? "key" : "keys") + ".",
                    NamedTextColor.GREEN
            )));
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.15f);
        }
        return delivered;
    }

    private void returnKey(Player player) {
        player.getInventory().addItem(items.key(1)).values().forEach(overflow ->
                player.getWorld().dropItemNaturally(player.getLocation(), overflow));
    }

    private static void fillHub(Inventory inventory) {
        ItemStack panel = MenuItems.button(Material.BROWN_STAINED_GLASS_PANE, "Crate Panel");
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, panel);
        }
        ItemStack brace = MenuItems.button(Material.IRON_BARS, "Iron Crate Brace");
        for (int slot : List.of(0, 8, 18, 26)) {
            inventory.setItem(slot, brace);
        }
        inventory.setItem(4, MenuItems.button(Material.BARREL, "Mysterious Crates"));
    }

    private void fillCrate(Inventory inventory) {
        ItemStack filler = MenuItems.button(Material.BROWN_STAINED_GLASS_PANE, "Crate Panel");
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler);
        }
        ItemStack brace = MenuItems.button(Material.IRON_BARS, "Iron Crate Brace");
        for (int slot : List.of(0, 8, 9, 17, 27, 35, 36, 44)) {
            inventory.setItem(slot, brace);
        }
        for (int slot = REEL_FIRST; slot <= REEL_LAST; slot++) {
            CrateCatalog.Reward preview = CrateCatalog.rewardAt(
                    ThreadLocalRandom.current().nextInt(CrateCatalog.TOTAL_WEIGHT)
            );
            inventory.setItem(slot, items.preview(preview, cosmeticItems));
        }
        inventory.setItem(4, MenuItems.button(Material.BARREL, "Opening"));
        inventory.setItem(13, MenuItems.button(Material.SPECTRAL_ARROW, "Winning Slot"));
        inventory.setItem(31, MenuItems.button(Material.HOPPER, "Winning Slot"));
        inventory.setItem(40, MenuItems.button(
                Material.TRIPWIRE_HOOK,
                "Key Consumed",
                "The reward is already decided."
        ));
    }

    private static Component winMessage(CrateCatalog.Reward reward) {
        return PlayerMenuService.prefix()
                .append(Component.text("You won ", NamedTextColor.WHITE))
                .append(Component.text(reward.displayName(), NamedTextColor.GOLD, TextDecoration.BOLD))
                .append(Component.text(
                        " (" + reward.displayedChance() + ").", NamedTextColor.GRAY
                ));
    }




    private static ItemStack named(ItemStack item, String name, String... lore) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(name, ORANGE, TextDecoration.BOLD)
                    .decoration(TextDecoration.ITALIC, false));
            List<Component> lines = new ArrayList<>();
            for (String value : lore) {
                lines.add(Component.text(value, NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false));
            }
            meta.lore(lines);
            item.setItemMeta(meta);
        }
        return item;
    }

    private static boolean canFit(Inventory inventory, ItemStack prize) {
        int remaining = prize.getAmount();
        for (ItemStack existing : inventory.getStorageContents()) {
            if (existing == null || existing.getType().isAir()) {
                remaining -= prize.getMaxStackSize();
            } else if (existing.isSimilar(prize)) {
                remaining -= Math.max(0, existing.getMaxStackSize() - existing.getAmount());
            }
            if (remaining <= 0) {
                return true;
            }
        }
        return false;
    }

    private static int parsePage(String[] args) {
        if (args.length < 2) {
            return 1;
        }
        try {
            return Math.max(1, Integer.parseInt(args[1]));
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }

    private static String remainingTime(long millis) {
        long seconds = Math.max(1L, (millis + 999L) / 1_000L);
        Duration duration = Duration.ofSeconds(seconds);
        long hours = duration.toHours();
        long minutes = duration.minusHours(hours).toMinutes();
        if (hours > 0L) {
            return hours + "h " + minutes + "m";
        }
        return Math.max(1L, minutes) + "m";
    }
}
