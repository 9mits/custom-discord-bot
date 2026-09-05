package bot.mgx.accessbridge;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.Statistic;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.entity.Item;
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
import java.util.function.Predicate;
import java.util.stream.Stream;

/** Physical crate openings, published odds, hourly keys, and crash-safe pending claims. */
final class CrateService implements CommandExecutor, TabCompleter, Listener {
    private static final TextColor ORANGE = TextColor.color(0xFF9900);
    private static final long ONLINE_PULSE_TICKS = 20L * 60L;
    /** One second is often enough for a bar measured in minutes, and costs nothing. */
    private static final long KEY_BAR_TICKS = 20L;
    private static final int HUB_KEYS_SLOT = 11;
    private static final int HUB_OPEN_SLOT = 13;
    private static final int HUB_ODDS_SLOT = 15;
    private static final int HUB_AUTO_SLOT = 22;
    private static final int HUB_FILTER_SLOT = 24;
    private static final int HUB_TRIPLE_SLOT = 20;
    private static final int RESULT_AGAIN_SLOT = 11;
    private static final int RESULT_AUTO_SLOT = 15;
    private static final int RESULT_BACK_SLOT = 22;
    private static final int CONFIRM_YES_SLOT = 11;
    private static final int CONFIRM_NO_SLOT = 15;
    private static final int ODDS_PER_PAGE = 45;
    private static final int FILTER_PER_PAGE = 45;
    private static final int FILTER_CLEAR_SLOT = 49;
    /** A breath after the effect ends before the menu comes back over it. */
    private static final long REVEAL_SETTLE_TICKS = 20L;
    private static final int PREVIOUS_SLOT = 45;
    private static final int NEXT_SLOT = 53;
    /** Row starts for a triple pull: the reel row, plus one above and one below. */
    private static final int[] TRIPLE_ROWS = {9, 18, 27};
    private static final int SINGLE_ROW = 18;
    static final int TRIPLE_PULL_SIZE = 3;
    /** Winners land one after another rather than all at once, so each one registers. */
    private static final long LANE_REVEAL_TICKS = 6L;
    private static final int REEL_FRAMES = 31;
    /**
     * An auto run keeps the reel and its slowdown — that is the part worth watching —
     * but at roughly a quarter of the length, so a stack of keys is minutes rather than
     * a quarter of an hour.
     */
    private static final int FAST_REEL_FRAMES = 15;
    private static final int SELECT_DEFAULT_SLOT = 11;
    private static final int SELECT_SHARD_SLOT = 13;
    private static final int SELECT_AMETHYST_SLOT = 15;
    /** A countdown that only redraws when a screen opens is a timestamp, not a timer. */
    private static final long COUNTDOWN_TICKS = 20L;

    private record OnlineRewardState(long sessionStartedAt, long rewardedIntervals) { }

    private enum Screen {
        SELECT,
        ODDS_SELECT,
        HUB,
        ODDS,
        ROLL,
        RESULT,
        CONFIRM,
        FILTER
    }

    private static final class CrateMenu implements InventoryHolder {
        private final Screen screen;
        private final int page;
        private final CrateKind kind;
        private final boolean oddsSelectorBack;
        private Inventory inventory;

        CrateMenu(Screen screen, int page, CrateKind kind) {
            this(screen, page, kind, false);
        }

        CrateMenu(Screen screen, int page, CrateKind kind, boolean oddsSelectorBack) {
            this.screen = screen;
            this.page = page;
            this.kind = kind;
            this.oddsSelectorBack = oddsSelectorBack;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    /**
     * One reel. Every lane's reward is drawn before the animation starts, so a triple
     * pull is decided at the same moment a single one is — but only the first is written
     * to the crash-safe pending slot, which holds one reward at a time. The rest are
     * reserved as the reel in front of them pays out, and until then neither their key
     * nor their reward exists anywhere but here. A crash mid-pull therefore costs a
     * player nothing: the lanes that never reserved never charged them either.
     */
    private static final class Lane {
        private final int reelFirst;
        private final int reelLast;
        private final int winningSlot;
        private final CrateCatalog.Reward reward;
        private CrateStore.Pending pending;

        Lane(int rowStart, CrateCatalog.Reward reward) {
            this.reelFirst = rowStart + 1;
            this.reelLast = rowStart + 7;
            this.winningSlot = rowStart + 4;
            this.reward = reward;
        }
    }

    /** What one reel actually paid out, and whether Auto Trash took it on the way. */
    private record Payout(CrateCatalog.Reward reward, boolean trashed) {
    }

    private long animationTicks(CrateKind kind, long ticks) {
        return Math.max(1L, Math.round(ticks * variables.decimal(
                "crate." + kind.key() + ".animation-duration-multiplier")));
    }

    private static final class RollSession {
        private final UUID playerId;
        private final List<Lane> lanes;
        private final List<Payout> delivered = new ArrayList<>();
        private final CrateKind kind;
        private final Inventory inventory;
        private final boolean fast;
        private int frame;
        private BukkitTask task;

        RollSession(
                UUID playerId,
                List<Lane> lanes,
                CrateKind kind,
                Inventory inventory,
                boolean fast
        ) {
            this.playerId = playerId;
            this.lanes = lanes;
            this.kind = kind;
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
    private final CrateOddsStore odds;
    private final GameVariableStore variables;
    private final CrateItems items;
    private final CosmeticStore cosmetics;
    private final CosmeticItems cosmeticItems;
    private final CosmeticEffectService effects;
    private final PlayerSettingsStore settings;
    private final PlayerPerkService perks;
    private final SpecialItemService specialItems;
    private final CrateFilterStore filters;
    private final AmethystProgressStore amethystProgress;
    private final ClanBattleService clanBattles;
    private final Map<UUID, RollSession> sessions = new HashMap<>();
    /** Players part way through an auto run, and how many crates are still owed. */
    private final Map<UUID, Long> autoRuns = new HashMap<>();
    /** How many rewards Auto Trash has removed during the current auto run. */
    private final Map<UUID, Integer> autoTrashed = new HashMap<>();
    /** Set for exactly one result screen, so it can say the reward was thrown away. */
    private final Set<UUID> lastRewardTrashed = new java.util.HashSet<>();
    /**
     * Players whose menu is deliberately shut while a reveal plays.
     *
     * <p>Closing the menu is normally how somebody leaves an auto run, so without this
     * the effect would end the run it interrupted.
     */
    private final Set<UUID> watchingReveal = new java.util.HashSet<>();
    private Predicate<Player> dragonAccess = ignored -> false;
    private final Map<UUID, CrateKind> selectedKinds = new HashMap<>();
    private final Map<UUID, Long> onlineCreditStarted = new HashMap<>();
    /** A stay-reward interval resets only when the player leaves, never when they move. */
    private final Map<UUID, Long> onlineRewardStarted = new HashMap<>();
    private final Map<UUID, OnlineRewardState> onlineRewardStates = new HashMap<>();
    /** The last tier rendered during this connected session, used to announce upgrades once. */
    private final Map<UUID, Integer> displayedOnlineTiers = new HashMap<>();
    private final Map<UUID, BossBar> keyBars = new HashMap<>();
    private BukkitTask keyBarTask;
    private BukkitTask hourlyTask;
    private BukkitTask countdownTask;

    CrateService(
            MGXAccessBridge plugin,
            CrateStore store,
            CrateItems items,
            CosmeticStore cosmetics,
            CosmeticItems cosmeticItems,
            CosmeticEffectService effects,
            PlayerSettingsStore settings,
            PlayerPerkService perks,
            SpecialItemService specialItems,
            CrateFilterStore filters,
            AmethystProgressStore amethystProgress,
            ClanBattleService clanBattles,
            CrateOddsStore odds,
            GameVariableStore variables
    ) {
        this.plugin = plugin;
        this.store = store;
        this.odds = odds;
        this.variables = variables;
        this.items = items;
        this.cosmetics = cosmetics;
        this.cosmeticItems = cosmeticItems;
        this.effects = effects;
        this.settings = settings;
        this.perks = perks;
        this.specialItems = specialItems;
        this.filters = filters;
        this.amethystProgress = amethystProgress;
        this.clanBattles = clanBattles;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        args = CommandArgs.withoutEchoedSender(sender.getName(), args);
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players open crates. Use /mgxadmin give to issue keys.");
            return true;
        }
        if (args.length == 0) {
            openSelector(player);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "open", "spin" -> {
                CrateKind kind = args.length >= 2
                        ? CrateKind.from(args[1]).orElse(null)
                        : selectedKinds.getOrDefault(player.getUniqueId(), CrateKind.DEFAULT);
                if (kind == null) {
                    PlayerMenuService.error(player, "Use default, amethyst, or shard.");
                    return true;
                }
                start(player, kind);
            }
            case "odds", "rewards" -> {
                if (args.length < 2) {
                    openOddsSelector(player);
                    break;
                }
                CrateKind kind = CrateKind.from(args[1]).orElse(null);
                if (kind == null) {
                    PlayerMenuService.error(player, "Use default, amethyst, or shard.");
                    break;
                }
                openOdds(player, kind, parsePage(args), true);
            }
            case "trash", "filter" -> openFilters(
                    player,
                    selectedKinds.getOrDefault(player.getUniqueId(), CrateKind.DEFAULT),
                    parsePage(args)
            );
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
                    player,
                    "Use /crate, /crate open, /crate odds, /crate trash, or /crate claim."
            );
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return Stream.of("open", "odds", "trash", "claim")
                    .filter(value -> value.startsWith(prefix))
                    .toList();
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("open")
                || args[0].equalsIgnoreCase("odds"))) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return Stream.of("default", "amethyst", "shard")
                    .filter(value -> value.startsWith(prefix))
                    .toList();
        }
        return List.of();
    }

    void openHub(Player player) {
        openSelector(player);
    }

    void dragonAccess(Predicate<Player> access) {
        dragonAccess = access == null ? ignored -> false : access;
    }

    void openFor(Player player, CrateKind kind) {
        if (kind == CrateKind.DRAGON && !dragonAccess.test(player)) {
            PlayerMenuService.error(player, "The Dragon Crate is only open inside the finished event arena.");
            return;
        }
        openKindHub(player, kind);
    }

    private void openSelector(Player player) {
        openSelector(player, false);
    }

    private void openOddsSelector(Player player) {
        openSelector(player, true);
    }

    private void openSelector(Player player, boolean oddsOnly) {
        items.upgradeLegacyKeys(player);
        if (!sessions.containsKey(player.getUniqueId())) {
            deliverPending(player, false);
        }
        deliverBankedKeys(player, false);
        CrateMenu holder = new CrateMenu(
                oddsOnly ? Screen.ODDS_SELECT : Screen.SELECT, 1, null
        );
        Inventory inventory = Bukkit.createInventory(
                holder, 27, Component.text(
                        oddsOnly ? "Choose Crate Odds" : "Mysterious Crates", ORANGE
                )
        );
        holder.inventory = inventory;
        fillHub(inventory);
        inventory.setItem(4, named(
                items.key(1),
                "Your Keys",
                "In inventory: " + items.count(player),
                "Banked: " + store.bankedKeys(player.getUniqueId()),
                "Next keys in " + remainingTime(
                        store.millisUntilNextKey(player.getUniqueId())
                ) + ".",
                keysPerHour(player) + " keys are earned per online hour."
        ));
        long now = System.currentTimeMillis();
        inventory.setItem(SELECT_DEFAULT_SLOT, selectButton(CrateKind.DEFAULT, oddsOnly, now));
        inventory.setItem(SELECT_SHARD_SLOT, selectButton(CrateKind.SHARD, oddsOnly, now));
        inventory.setItem(SELECT_AMETHYST_SLOT, selectButton(CrateKind.AMETHYST, oddsOnly, now));
        MenuItems.show(plugin, player, inventory);
    }

    private void openKindHub(Player player, CrateKind kind) {
        items.upgradeLegacyKeys(player);
        if (!sessions.containsKey(player.getUniqueId())) {
            deliverPending(player, false);
        }
        deliverBankedKeys(player, false);
        selectedKinds.put(player.getUniqueId(), kind);
        CrateMenu holder = new CrateMenu(Screen.HUB, 1, kind);
        Inventory inventory = Bukkit.createInventory(
                holder, 27, Component.text(kind.menuName(), kind.colour())
        );
        holder.inventory = inventory;
        fillHub(inventory);
        inventory.setItem(HUB_KEYS_SLOT, hubKeys(player, kind, System.currentTimeMillis()));
        inventory.setItem(HUB_OPEN_SLOT, MenuItems.button(
                kind.icon(),
                "Open " + kind.menuName(),
                "Spends " + keyCost(kind) + " " + kind.currency().shortName(keyCost(kind)) + "."
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
        int pull = pullSize(player);
        inventory.setItem(HUB_TRIPLE_SLOT, MenuItems.button(
                pull == 1 ? Material.LEVER : Material.REDSTONE_TORCH,
                "Open Three At A Time: " + (pull == 1 ? "OFF" : "ON"),
                pull == 1
                        ? "Every opening spins one reel."
                        : "Every opening spins three reels at once.",
                "Costs " + (keyCost(kind) * TRIPLE_PULL_SIZE) + " "
                        + kind.currency().shortName(keyCost(kind) * TRIPLE_PULL_SIZE)
                        + " per opening when on.",
                "Auto Open uses it too."
        ));
        int trashed = filters.count(player.getUniqueId());
        inventory.setItem(HUB_FILTER_SLOT, MenuItems.button(
                Material.CAULDRON,
                "Auto Trash",
                trashed == 0
                        ? "Nothing is being thrown away."
                        : trashed + (trashed == 1 ? " reward is" : " rewards are") + " thrown away.",
                "Pick the rewards you never want to receive.",
                "They are still rolled and still counted."
        ));
        MenuItems.show(plugin, player, inventory);
    }

    private void openOdds(Player player, CrateKind kind, int requestedPage) {
        openOdds(player, kind, requestedPage, false);
    }

    private void openOdds(
            Player player, CrateKind kind, int requestedPage, boolean oddsSelectorBack
    ) {
        List<CrateCatalog.Reward> rewards = variables.rewards(kind);
        int pageCount = Math.max(1, (rewards.size() + ODDS_PER_PAGE - 1) / ODDS_PER_PAGE);
        int page = Math.max(1, Math.min(pageCount, requestedPage));
        CrateMenu holder = new CrateMenu(Screen.ODDS, page, kind, oddsSelectorBack);
        Inventory inventory = Bukkit.createInventory(
                holder, 54, Component.text(kind.menuName() + " Odds " + page + "/" + pageCount,
                        kind.colour())
        );
        holder.inventory = inventory;
        int first = (page - 1) * ODDS_PER_PAGE;
        int last = Math.min(rewards.size(), first + ODDS_PER_PAGE);
        for (int index = first; index < last; index++) {
            CrateCatalog.Reward reward = rewards.get(index);
            inventory.setItem(index - first, items.oddsPreview(
                    reward, cosmeticItems, variables.displayedChance(kind, reward)
            ));
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

    /**
     * The Auto Trash picker.
     *
     * <p>Cosmetics are deliberately absent: they go straight to {@code /wardrobe} and so
     * cannot fill an inventory, and a mis-click that silently threw away a 1-in-500,000
     * aura is not a mistake worth allowing.
     */
    private void toggleTriplePull(Player player, CrateKind kind) {
        if (sessions.containsKey(player.getUniqueId())) {
            PlayerMenuService.error(player, "Your crate is already opening.");
            return;
        }
        boolean tripled;
        try {
            tripled = settings.toggle(
                    player.getUniqueId(), PlayerSettingsStore.Setting.CRATE_TRIPLE
            );
        } catch (UncheckedIOException exception) {
            plugin.getLogger().warning("Could not save the triple-pull setting for "
                    + player.getUniqueId() + ": " + exception.getMessage());
            PlayerMenuService.error(player, "That choice could not be saved.");
            return;
        }
        playCrateSound(player, Sound.BLOCK_LEVER_CLICK, 0.7f,
                tripled ? 1.4f : 0.9f);
        openKindHub(player, kind);
    }

    private void openFilters(Player player, CrateKind kind, int requestedPage) {
        selectedKinds.put(player.getUniqueId(), kind);
        List<CrateCatalog.Reward> rewards = trashableRewards(kind);
        int pageCount = Math.max(1, (rewards.size() + FILTER_PER_PAGE - 1) / FILTER_PER_PAGE);
        int page = Math.max(1, Math.min(pageCount, requestedPage));
        CrateMenu holder = new CrateMenu(Screen.FILTER, page, kind);
        Inventory inventory = Bukkit.createInventory(
                holder, 54,
                Component.text("Auto Trash " + page + "/" + pageCount, kind.colour())
        );
        holder.inventory = inventory;
        Set<String> discarded = filters.all(player.getUniqueId());
        int first = (page - 1) * FILTER_PER_PAGE;
        int last = Math.min(rewards.size(), first + FILTER_PER_PAGE);
        for (int index = first; index < last; index++) {
            CrateCatalog.Reward reward = rewards.get(index);
            inventory.setItem(
                    index - first,
                    filterEntry(reward, discarded.contains(reward.id()))
            );
        }
        inventory.setItem(PREVIOUS_SLOT, page > 1
                ? MenuItems.button(Material.ARROW, "Previous Page")
                : MenuItems.button(Material.BARRIER, "Back"));
        if (page < pageCount) {
            inventory.setItem(NEXT_SLOT, MenuItems.button(Material.ARROW, "Next Page"));
        }
        int trashed = filters.count(player.getUniqueId());
        inventory.setItem(FILTER_CLEAR_SLOT, MenuItems.button(
                trashed == 0 ? Material.GRAY_DYE : Material.LIME_DYE,
                trashed == 0 ? "Nothing Is Trashed" : "Keep Everything Again",
                trashed == 0
                        ? "Click a reward to start throwing it away."
                        : "Clears all " + trashed + " of your choices."
        ));
        MenuItems.show(plugin, player, inventory);
    }

    /** Item rewards only, in the order the odds pages already show them. */
    private List<CrateCatalog.Reward> trashableRewards(CrateKind kind) {
        return variables.rewards(kind).stream().filter(reward -> !reward.cosmetic()).toList();
    }

    private ItemStack filterEntry(CrateCatalog.Reward reward, boolean discarded) {
        ItemStack entry = items.oddsPreview(reward, cosmeticItems);
        ItemMeta meta = entry.getItemMeta();
        if (meta == null) {
            return entry;
        }
        List<Component> lore = meta.lore() == null
                ? new ArrayList<>() : new ArrayList<>(meta.lore());
        lore.add(Component.empty());
        lore.add(Component.text(
                discarded ? "Thrown away on sight" : "Delivered to you",
                discarded ? NamedTextColor.RED : NamedTextColor.GREEN,
                TextDecoration.BOLD
        ).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text(
                discarded ? "Click to keep it again." : "Click to throw it away.",
                NamedTextColor.GRAY
        ).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        entry.setItemMeta(meta);
        return entry;
    }

    private void toggleFilter(Player player, CrateKind kind, int page, int slot) {
        List<CrateCatalog.Reward> rewards = trashableRewards(kind);
        int index = (page - 1) * FILTER_PER_PAGE + slot;
        if (index < 0 || index >= rewards.size()) {
            return;
        }
        CrateCatalog.Reward reward = rewards.get(index);
        boolean discarded;
        try {
            discarded = filters.toggle(player.getUniqueId(), reward.id());
        } catch (UncheckedIOException exception) {
            plugin.getLogger().warning("Could not save a crate filter for "
                    + player.getUniqueId() + ": " + exception.getMessage());
            PlayerMenuService.error(player, "That choice could not be saved.");
            return;
        }
        playCrateSound(player,
                discarded ? Sound.ENTITY_ITEM_BREAK : Sound.ENTITY_ITEM_PICKUP, 0.6f,
                discarded ? 0.8f : 1.4f);
        openFilters(player, kind, page);
    }

    private void start(Player player) {
        start(player, selectedKinds.getOrDefault(player.getUniqueId(), CrateKind.DEFAULT));
    }

    private void start(Player player, CrateKind kind) {
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
        if (!kind.available(now)) {
            PlayerMenuService.error(player, kind == CrateKind.DRAGON
                    ? "The Dragon Crate is closed."
                    : "The Amethyst Crate event has ended.");
            openSelector(player);
            return;
        }
        if (kind == CrateKind.DRAGON && !dragonAccess.test(player)) {
            PlayerMenuService.error(player, "You already left this Dragon reward phase.");
            player.closeInventory();
            return;
        }
        selectedKinds.put(playerId, kind);
        int pull = pullSize(player);
        int cost = keyCost(kind) * pull;
        if (currencyCount(player, kind) < cost) {
            PlayerMenuService.error(player, "You need " + cost
                    + " " + kind.currency().fullName(cost)
                    + " to open this crate.");
            return;
        }
        int luck = specialItems.crateLuckPercent(player);
        // The balancer steers the realised rare rate back towards the published one. It is
        // composed with the player's own luck rather than replacing it, so a potion still
        // does exactly what its lore says on top of whatever the table currently needs.
        int rollPercent = CrateOddsBalance.compose(luck, balancePercent(kind));
        // What this open was expected to pay, on the table it is actually rolling on. A
        // potion, an event and the balancer's own correction all live in rollPercent, so
        // none of them can later be read back as the table having drifted.
        double expectedRate = CrateOddsBalance.expectedRareRate(
                variables.advertisedRareRate(kind), rollPercent
        );
        int[] rows = pull == 1 ? new int[]{SINGLE_ROW} : TRIPLE_ROWS;
        List<Lane> lanes = new ArrayList<>();
        for (int row : rows) {
            CrateCatalog.Reward reward = variables.randomReward(
                    kind, rollPercent, ThreadLocalRandom.current()
            );
            odds.record(kind, player.getUniqueId(), reward.rare(), expectedRate);
            lanes.add(new Lane(row, reward));
        }
        if (!reserveLane(player, kind, lanes.get(0), now)) {
            return;
        }
        auditOpen(player, kind, pull, cost, luck);

        CrateMenu holder = new CrateMenu(Screen.ROLL, 1, kind);
        Inventory inventory = Bukkit.createInventory(
                holder, 45, Component.text(
                        (pull == 1 ? "Opening " : "Opening " + pull + "x ") + kind.menuName(),
                        kind.colour())
        );
        holder.inventory = inventory;
        fillCrate(inventory, kind, lanes);
        RollSession session = new RollSession(
                playerId, lanes, kind, inventory, autoRuns.containsKey(playerId)
        );
        sessions.put(playerId, session);
        MenuItems.show(plugin, player, inventory);
        playCrateSound(player, Sound.BLOCK_CHEST_OPEN, 0.9f, 0.85f);
        advance(session);
    }

    /**
     * Spends one crate's keys and writes its reward to the pending slot.
     *
     * <p>Every failure here has to hand the keys back, because the reward it was paying
     * for does not exist yet.
     */
    private boolean reserveLane(Player player, CrateKind kind, Lane lane, long now) {
        int consumed = removeCurrency(player, kind, keyCost(kind));
        if (consumed != keyCost(kind)) {
            returnCurrency(player, kind, consumed);
            PlayerMenuService.error(player, "Your " + kind.currency().shortName(2)
                    + " moved before they could be consumed.");
            return false;
        }
        try {
            lane.pending = store.reserve(
                    player.getUniqueId(), UUID.randomUUID(), lane.reward.id(), kind, now
            );
            // The opening belongs to the battle active at the instant its currency is
            // committed, not whichever battle happens to be live after the reel ends.
            clanBattles.recordCrateOpening(player);
        } catch (IllegalStateException | UncheckedIOException exception) {
            returnCurrency(player, kind, keyCost(kind));
            plugin.getLogger().warning("Could not reserve a crate reward: " + exception.getMessage());
            PlayerMenuService.error(player, "That opening could not be saved. Your key was returned.");
            return false;
        }
        return true;
    }

    /** Three reels at a time when the player has asked for it, otherwise one. */
    private int pullSize(Player player) {
        return settings.isEnabled(player.getUniqueId(), PlayerSettingsStore.Setting.CRATE_TRIPLE)
                ? TRIPLE_PULL_SIZE : 1;
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
        for (Lane lane : session.lanes) {
            for (int slot = lane.reelFirst; slot < lane.reelLast; slot++) {
                session.inventory.setItem(slot, session.inventory.getItem(slot + 1));
            }
            session.inventory.setItem(
                    lane.reelLast, items.preview(session.kind.randomPreview(), cosmeticItems)
            );
        }
        playCrateSound(player,
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
                plugin, () -> advance(session), animationTicks(session.kind, session.delay())
        );
    }

    private void finish(RollSession session, Player player) {
        session.inventory.setItem(4, MenuItems.button(Material.CHEST, "Crate Opened"));
        resolveLane(session, player, 0);
    }

    /**
     * Pays out one reel, then starts the next.
     *
     * <p>Strictly one at a time: the pending slot holds a single reward, so a lane may
     * only reserve once the lane before it has been handed over and cleared.
     */
    private void resolveLane(RollSession session, Player player, int index) {
        Lane lane = session.lanes.get(index);
        if (lane.pending == null
                && !reserveLane(player, session.kind, lane, System.currentTimeMillis())) {
            endPull(session, player);
            return;
        }
        for (int slot = lane.reelFirst; slot <= lane.reelLast; slot++) {
            session.inventory.setItem(slot, slot == lane.winningSlot
                    ? items.revealedPreview(lane.reward, cosmeticItems)
                    : MenuItems.button(Material.BROWN_STAINED_GLASS_PANE, "Crate Panel"));
        }
        playCrateSound(player, Sound.ENTITY_PLAYER_LEVELUP, 1f,
                1.15f + index * 0.12f);
        session.task = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!deliverPending(player, true)) {
                sessions.remove(session.playerId);
                autoRuns.remove(session.playerId);
                autoTrashed.remove(session.playerId);
                PlayerMenuService.error(
                        player, "Your inventory is full. Make room, then use /crate claim."
                );
                return;
            }
            // Read per lane rather than once at the end: with three reels the flag
            // describes the last delivery, so a bin on the first reel would otherwise be
            // reported against the third.
            session.delivered.add(new Payout(
                    lane.reward, lastRewardTrashed.remove(player.getUniqueId())
            ));
            if (index + 1 < session.lanes.size()) {
                session.task = plugin.getServer().getScheduler().runTaskLater(
                        plugin, () -> resolveLane(session, player, index + 1), animationTicks(session.kind, LANE_REVEAL_TICKS)
                );
                return;
            }
            endPull(session, player);
        }, animationTicks(session.kind, autoRuns.containsKey(session.playerId) ? 10L : 20L));
    }

    private void endPull(RollSession session, Player player) {
        sessions.remove(session.playerId);
        if (session.delivered.isEmpty()) {
            // Nothing was paid out, so there is no result to show and nothing to
            // continue into. Leaving the run armed would hang it here forever.
            autoRuns.remove(session.playerId);
            autoTrashed.remove(session.playerId);
            return;
        }
        afterReward(player, List.copyOf(session.delivered), session.kind);
    }

    /** Shown once a reward lands, so opening another crate never needs the hub. */
    private void openResult(Player player, List<Payout> payouts, CrateKind kind) {
        CrateMenu holder = new CrateMenu(Screen.RESULT, 1, kind);
        Inventory inventory = Bukkit.createInventory(
                holder, 27, Component.text("Crate Opened", ORANGE)
        );
        holder.inventory = inventory;
        ItemStack panel = MenuItems.button(Material.BROWN_STAINED_GLASS_PANE, "Crate Panel");
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, panel);
        }
        // Centred whatever the count, so one reward sits where one reward always sat.
        int firstSlot = 4 - (payouts.size() - 1) / 2;
        for (int index = 0; index < payouts.size(); index++) {
            Payout payout = payouts.get(index);
            inventory.setItem(firstSlot + index, payout.trashed()
                    ? trashedPreview(payout.reward())
                    : items.revealedPreview(payout.reward(), cosmeticItems));
        }
        long keys = currencyCount(player, kind);
        int pull = pullSize(player);
        int cost = keyCost(kind) * pull;
        boolean canOpen = keys >= cost;
        inventory.setItem(RESULT_AGAIN_SLOT, MenuItems.button(
                canOpen ? Material.CHEST : Material.BARRIER,
                canOpen ? (pull == 1 ? "Open Again" : "Open " + pull + " Again")
                        : "Not Enough Keys",
                canOpen ? "Spends " + cost + " of your " + keys + " "
                        + kind.currency().shortName(keys) + "."
                        : "This needs " + cost + " " + kind.currency().shortName(cost) + "."
        ));
        long possible = keys / cost;
        inventory.setItem(RESULT_AUTO_SLOT, MenuItems.button(
                possible > 0 ? Material.HOPPER : Material.BARRIER,
                "Auto Open",
                possible > 0 ? "Opens " + (possible * pull) + " crates using "
                        + (possible * cost) + " " + kind.currency().shortName(possible * cost) + "."
                        : "You cannot afford this crate.",
                possible > 0 ? "You confirm before anything is spent."
                        : "You need more " + kind.currency().shortName(2) + "."
        ));
        inventory.setItem(RESULT_BACK_SLOT, MenuItems.button(Material.BARRIER, "Close"));
        MenuItems.show(plugin, player, inventory);
    }

    /** The result screen still shows what was rolled, greyed out and named as binned. */
    private ItemStack trashedPreview(CrateCatalog.Reward reward) {
        ItemStack preview = items.revealedPreview(reward, cosmeticItems);
        ItemMeta meta = preview.getItemMeta();
        if (meta == null) {
            return preview;
        }
        List<Component> lore = meta.lore() == null
                ? new ArrayList<>() : new ArrayList<>(meta.lore());
        lore.add(Component.empty());
        lore.add(Component.text("Auto Trash threw this away.", NamedTextColor.RED,
                TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("It still counts as an opening.", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        preview.setItemMeta(meta);
        return preview;
    }

    /** Asks once, because the answer spends every key the player owns. */
    private void confirmAutoOpen(Player player, CrateKind kind) {
        if (sessions.containsKey(player.getUniqueId())) {
            PlayerMenuService.error(player, "Your crate is already opening.");
            return;
        }
        long keys = currencyCount(player, kind);
        int pull = pullSize(player);
        int cost = keyCost(kind) * pull;
        long opens = keys / cost;
        if (opens <= 0) {
            PlayerMenuService.error(player, "You need " + cost + " "
                    + kind.currency().fullName(cost) + " to open this crate.");
            return;
        }
        CrateMenu holder = new CrateMenu(Screen.CONFIRM, 1, kind);
        Inventory inventory = Bukkit.createInventory(
                holder, 27, Component.text("Open " + (opens * pull) + " crates?", ORANGE)
        );
        holder.inventory = inventory;
        ItemStack panel = MenuItems.button(Material.BROWN_STAINED_GLASS_PANE, "Crate Panel");
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, panel);
        }
        inventory.setItem(CONFIRM_YES_SLOT, MenuItems.button(
                Material.LIME_CONCRETE,
                "Confirm: spend " + (opens * cost) + " "
                        + kind.currency().shortName(opens * cost),
                pull == 1
                        ? "Opens " + opens + " crates one after another."
                        : "Runs " + opens + " pulls of " + pull + " reels each.",
                keyCost(kind) + " " + kind.currency().shortName(keyCost(kind))
                        + (keyCost(kind) == 1 ? " is" : " are") + " spent per crate.",
                "Close the menu part way to keep the rest."
        ));
        inventory.setItem(CONFIRM_NO_SLOT, MenuItems.button(
                Material.RED_CONCRETE,
                "Cancel",
                "Spends nothing. Keeps all " + keys + " " + kind.currency().shortName(keys) + "."
        ));
        inventory.setItem(4, currencyItem(kind, 1));
        MenuItems.show(plugin, player, inventory);
    }

    /**
     * Runs the reel once per key without stopping in between. The count is fixed when
     * the run starts so a key arriving mid-run does not extend it, and closing the menu
     * ends the run with the unspent keys still in the player's inventory.
     */
    private void beginAutoOpen(Player player, CrateKind kind) {
        long keys = currencyCount(player, kind);
        int cost = keyCost(kind) * pullSize(player);
        long opens = keys / cost;
        if (opens <= 0) {
            PlayerMenuService.error(player, "You need " + cost + " "
                    + kind.currency().fullName(cost) + " to open this crate.");
            return;
        }
        autoRuns.put(player.getUniqueId(), opens);
        autoTrashed.remove(player.getUniqueId());
        start(player, kind);
    }

    /**
     * Called once a reward lands.
     *
     * <p>Anything with an effect worth watching gets the menu out of the way and the run
     * held until it finishes — a reel opening over the top of a Secret reveal is the
     * one drop nobody gets to see. Everything else continues immediately, so an ordinary
     * auto run is unaffected.
     */
    private void afterReward(Player player, List<Payout> payouts, CrateKind kind) {
        // A triple pull waits for its best drop, not its last one.
        long revealTicks = payouts.stream()
                .mapToLong(payout ->
                        CosmeticEffectService.revealDurationTicks(payout.reward()))
                .max().orElse(0L);
        if (revealTicks <= 0L) {
            continueAfterReward(player, payouts, kind);
            return;
        }
        UUID watcherId = player.getUniqueId();
        watchingReveal.add(watcherId);
        player.closeInventory();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            watchingReveal.remove(watcherId);
            if (!player.isOnline() || sessions.containsKey(watcherId)) {
                return;
            }
            continueAfterReward(player, payouts, kind);
        }, revealTicks + REVEAL_SETTLE_TICKS);
    }

    private void continueAfterReward(Player player, List<Payout> payouts, CrateKind kind) {
        Long remaining = autoRuns.get(player.getUniqueId());
        if (remaining == null) {
            openResult(player, payouts, kind);
            return;
        }
        long left = remaining - 1;
        if (left <= 0 || currencyCount(player, kind) < keyCost(kind) * pullSize(player)) {
            autoRuns.remove(player.getUniqueId());
            Integer counted = autoTrashed.remove(player.getUniqueId());
            int trashed = counted == null ? 0 : counted;
            player.sendMessage(PlayerMenuService.prefix().append(Component.text(
                    "Auto open finished.", NamedTextColor.GREEN
            )));
            if (trashed > 0) {
                player.sendMessage(PlayerMenuService.prefix().append(Component.text(
                        "Auto Trash threw away " + trashed
                                + (trashed == 1 ? " reward." : " rewards."),
                        NamedTextColor.GRAY
                )));
            }
            openResult(player, payouts, kind);
            return;
        }
        autoRuns.put(player.getUniqueId(), left);
        start(player, kind);
    }

    private boolean deliverPending(Player player, boolean tellWhenFull) {
        lastRewardTrashed.remove(player.getUniqueId());
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
            return true;
        }
        // Applied only while the sealed reward item does not exist yet. Somebody who
        // already carries one from an earlier spin keeps it, because Auto Trash decides
        // what is handed over and never reaches into an inventory.
        if (!items.carriesReward(player, pending.spinId())
                && filters.discards(player.getUniqueId(), reward.id())) {
            try {
                if (!store.complete(player.getUniqueId(), pending.spinId())) {
                    return false;
                }
            } catch (UncheckedIOException exception) {
                plugin.getLogger().warning("Could not discard crate reward "
                        + pending.spinId() + ": " + exception.getMessage());
                PlayerMenuService.error(player, "Your reward could not be processed yet.");
                return false;
            }
            UUID playerId = player.getUniqueId();
            lastRewardTrashed.add(playerId);
            if (autoRuns.containsKey(playerId)) {
                autoTrashed.merge(playerId, 1, Integer::sum);
            }
            recordWin(player, pending, reward);
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
        CrateKind kind = pending.crateKind();
        if (kind == CrateKind.AMETHYST) {
            try {
                amethystProgress.recordCratesOpened(player.getUniqueId(), 1);
            } catch (UncheckedIOException exception) {
                plugin.getLogger().warning("Could not record Amethyst Crate opening for "
                        + player.getUniqueId() + ": " + exception.getMessage());
            }
        } else if (kind == CrateKind.DRAGON) {
            try {
                amethystProgress.recordDragonCrate(player.getUniqueId());
            } catch (UncheckedIOException exception) {
                plugin.getLogger().warning("Could not record Dragon Crate opening for "
                        + player.getUniqueId() + ": " + exception.getMessage());
            }
        }
        auditWin(player, pending, reward);
        if (reward.revealTier() != CrateCatalog.RevealTier.NONE) {
            announceTieredWin(player, reward, kind);
            if (settings.isEnabled(
                    player.getUniqueId(), PlayerSettingsStore.Setting.CRATE_REVEAL_EFFECTS
            )) {
                effects.playCrateReveal(player, reward);
            }
        }
    }

    /** Exercises the same broadcast and VFX path as a real win, without minting a reward. */
    void testReveal(Player player, CrateCatalog.RevealTier tier) {
        CrateCatalog.Reward reward = CrateCatalog.revealExample(tier).orElseThrow(
                () -> new IllegalArgumentException("That crate reveal tier is not available.")
        );
        announceTieredWin(player, reward, selectedKinds.getOrDefault(
                player.getUniqueId(), CrateKind.DEFAULT
        ));
        if (settings.isEnabled(
                player.getUniqueId(), PlayerSettingsStore.Setting.CRATE_REVEAL_EFFECTS
        )) {
            effects.playCrateReveal(player, reward);
        }
    }

    private void announceTieredWin(
            Player player, CrateCatalog.Reward reward, CrateKind kind
    ) {
        CrateCatalog.RevealTier tier = reward.revealTier();
        String crateName = kind.displayName();
        Component announcement = PlayerMenuService.prefix();
        if (tier == CrateCatalog.RevealTier.MYTHIC) {
            announcement = announcement.append(Component.text("WOW! ", NamedTextColor.GOLD,
                    TextDecoration.BOLD));
        } else if (tier == CrateCatalog.RevealTier.SECRET) {
            announcement = announcement.append(Component.text("NO WAY! ", NamedTextColor.LIGHT_PURPLE,
                    TextDecoration.BOLD));
        } else if (tier == CrateCatalog.RevealTier.GENUINE_SECRET) {
            announcement = announcement.append(Component.text("✦ IMPOSSIBLE! ", NamedTextColor.GOLD,
                    TextDecoration.BOLD));
        }
        announcement = announcement
                .append(Component.text(player.getName(), NamedTextColor.GOLD, TextDecoration.BOLD))
                .append(Component.text(" opened a ", NamedTextColor.WHITE))
                .append(Component.text(reward.rarityDisplay() + " ", tierColour(tier),
                        TextDecoration.BOLD))
                .append(Component.text(reward.displayName(), NamedTextColor.LIGHT_PURPLE,
                        TextDecoration.BOLD))
                .append(Component.text(" from the " + crateName + "!", NamedTextColor.WHITE));
        if (tier == CrateCatalog.RevealTier.GENUINE_SECRET) {
            announcement = announcement.append(Component.text(
                    " • 1 in " + String.format(Locale.ROOT, "%,d",
                            CrateCatalog.hiddenAmethystOneIn()),
                    NamedTextColor.AQUA, TextDecoration.BOLD
            ));
        }
        if (reward.cosmetic()) {
            announcement = announcement.append(Component.text(
                    " • In existence: " + cosmetics.inExistence(reward.cosmeticId()),
                    NamedTextColor.DARK_AQUA
            ));
        }
        for (Player viewer : plugin.getServer().getOnlinePlayers()) {
            // The winner always hears about their own pull.
            if (viewer.equals(player) || settings.isEnabled(
                    viewer.getUniqueId(), PlayerSettingsStore.Setting.CRATE_ANNOUNCEMENTS
            )) {
                viewer.sendMessage(announcement);
            }
        }
        plugin.getServer().getConsoleSender().sendMessage(announcement);
    }

    /** Crate audio is one preference, so it is gated once rather than per call. */
    private void playCrateSound(Player player, Sound sound, float volume, float pitch) {
        if (settings.isEnabled(
                player.getUniqueId(), PlayerSettingsStore.Setting.CRATE_SOUNDS
        )) {
            player.playSound(player.getLocation(), sound, volume, pitch);
        }
    }

    private static TextColor tierColour(CrateCatalog.RevealTier tier) {
        return switch (tier) {
            case LEGENDARY -> TextColor.color(0xFFB52E);
            case MYTHIC -> TextColor.color(0xFF4FD8);
            case SECRET -> TextColor.color(0xB56CFF);
            case GENUINE_SECRET -> TextColor.color(0x53E5FF);
            default -> NamedTextColor.WHITE;
        };
    }

    /** One line for the keys leaving a player's hands, before anything is won. */
    private int balancePercent(CrateKind kind) {
        CrateOddsStore.Counts counts = odds.counts(kind);
        double target = variables.advertisedRareRate(kind);
        return CrateOddsBalance.percent(
                counts.opens(), counts.rareHits(), counts.expectedHits(), target
        );
    }

    private void auditOpen(Player player, CrateKind kind, int pull, int cost, int luck) {
        // Once an opening is over nothing remembers it happened, so "crates opened this
        // week" has to be counted as it occurs rather than derived afterwards.
        plugin.metricCounters().increment(ServerMetrics.CRATES_OPENED, pull);
        plugin.metricCounters().increment(ServerMetrics.KEYS_EARNED, -(long) cost);
        ServerEvent.Builder builder = ServerEvent.of(
                "crate_open",
                ServerEvent.CATEGORY_CRATE,
                player.getUniqueId(),
                player.getName(),
                plugin::recordServerEvent
        ).summary(player.getName() + " opened "
                        + (pull == 1 ? "the " : pull + "x the ") + kind.displayName())
                .detail("crate", kind.displayName())
                .detail("openings", pull)
                .detail(kind.currency() == CrateKind.Currency.SHARD
                        ? "shards_spent" : "keys_spent", cost);
        if (luck != CrateCatalog.NO_LUCK_PERCENT) {
            builder.detail("crate_luck", luck + "%");
        }
        builder.record();
    }

    /**
     * Every reward, and a second louder line for the ones worth noticing.
     *
     * <p>Two events rather than one because they answer different questions and are
     * read in different places: a crate log wants everything a crate has ever paid
     * out, and the important log wants only what somebody would want telling about.
     * Which channel each lands in is the routing table's decision, not this one's.
     */
    private void auditWin(
            Player player, CrateStore.Pending pending, CrateCatalog.Reward reward
    ) {
        CrateKind kind = pending.crateKind();
        ServerEvent.of(
                "crate_reward",
                ServerEvent.CATEGORY_CRATE,
                player.getUniqueId(),
                player.getName(),
                plugin::recordServerEvent
        ).summary(player.getName() + " won " + reward.displayName()
                        + " from the " + kind.displayName())
                .detail("reward", reward.displayName())
                .detail("rarity", reward.rarityDisplay())
                .detail("chance", reward.displayedChance())
                .detail("crate", kind.displayName())
                .detail("opening_id", pending.spinId().toString())
                .record();
        if (!reward.highImpact()) {
            return;
        }
        ServerEvent.of(
                "crate_rare_win",
                ServerEvent.CATEGORY_CRATE,
                player.getUniqueId(),
                player.getName(),
                plugin::recordServerEvent
        ).summary(player.getName() + " won " + reward.displayName() + " from a crate")
                .detail("reward", reward.displayName())
                .detail("rarity", reward.rarityDisplay())
                .detail("chance", reward.displayedChance())
                .detail("crate", kind.displayName())
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
        if (menu.screen == Screen.SELECT || menu.screen == Screen.ODDS_SELECT) {
            boolean oddsOnly = menu.screen == Screen.ODDS_SELECT;
            if (event.getSlot() == SELECT_DEFAULT_SLOT) {
                if (oddsOnly) {
                    openOdds(player, CrateKind.DEFAULT, 1, true);
                } else {
                    openKindHub(player, CrateKind.DEFAULT);
                }
            } else if (event.getSlot() == SELECT_SHARD_SLOT) {
                if (oddsOnly) {
                    openOdds(player, CrateKind.SHARD, 1, true);
                } else {
                    openKindHub(player, CrateKind.SHARD);
                }
            } else if (event.getSlot() == SELECT_AMETHYST_SLOT) {
                if (CrateKind.AMETHYST.available(System.currentTimeMillis())) {
                    if (oddsOnly) {
                        openOdds(player, CrateKind.AMETHYST, 1, true);
                    } else {
                        openKindHub(player, CrateKind.AMETHYST);
                    }
                } else {
                    PlayerMenuService.error(player, "The Amethyst Crate event has ended.");
                }
            }
        } else if (menu.screen == Screen.HUB) {
            if (event.getSlot() == HUB_OPEN_SLOT) {
                start(player, menu.kind);
            } else if (event.getSlot() == HUB_ODDS_SLOT) {
                openOdds(player, menu.kind, 1);
            } else if (event.getSlot() == HUB_AUTO_SLOT) {
                confirmAutoOpen(player, menu.kind);
            } else if (event.getSlot() == HUB_FILTER_SLOT) {
                openFilters(player, menu.kind, 1);
            } else if (event.getSlot() == HUB_TRIPLE_SLOT) {
                toggleTriplePull(player, menu.kind);
            }
        } else if (menu.screen == Screen.RESULT) {
            if (event.getSlot() == RESULT_AGAIN_SLOT) {
                start(player, menu.kind);
            } else if (event.getSlot() == RESULT_AUTO_SLOT) {
                confirmAutoOpen(player, menu.kind);
            } else if (event.getSlot() == RESULT_BACK_SLOT) {
                player.closeInventory();
            }
        } else if (menu.screen == Screen.CONFIRM) {
            if (event.getSlot() == CONFIRM_YES_SLOT) {
                beginAutoOpen(player, menu.kind);
            } else if (event.getSlot() == CONFIRM_NO_SLOT) {
                openKindHub(player, menu.kind);
            }
        } else if (menu.screen == Screen.FILTER) {
            if (event.getSlot() == PREVIOUS_SLOT) {
                if (menu.page == 1) {
                    openKindHub(player, menu.kind);
                } else {
                    openFilters(player, menu.kind, menu.page - 1);
                }
            } else if (event.getSlot() == NEXT_SLOT) {
                openFilters(player, menu.kind, menu.page + 1);
            } else if (event.getSlot() == FILTER_CLEAR_SLOT) {
                if (filters.clear(player.getUniqueId()) > 0) {
                    playCrateSound(player,
                            Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.2f);
                }
                openFilters(player, menu.kind, menu.page);
            } else if (event.getSlot() < FILTER_PER_PAGE) {
                toggleFilter(player, menu.kind, menu.page, event.getSlot());
            }
        } else if (menu.screen == Screen.ODDS) {
            if (event.getSlot() == PREVIOUS_SLOT) {
                if (menu.page == 1) {
                    if (menu.oddsSelectorBack) {
                        openOddsSelector(player);
                    } else {
                        openKindHub(player, menu.kind);
                    }
                } else {
                    openOdds(player, menu.kind, menu.page - 1, menu.oddsSelectorBack);
                }
            } else if (event.getSlot() == NEXT_SLOT) {
                openOdds(player, menu.kind, menu.page + 1, menu.oddsSelectorBack);
            }
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof CrateMenu) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player) || !items.isKey(event.getItem().getItemStack())) {
            return;
        }
        if (items.giveKeys(player, items.keyCount(event.getItem().getItemStack()))) {
            event.setCancelled(true);
            event.getItem().remove();
        }
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
                    || watchingReveal.contains(player.getUniqueId())
                    || player.getOpenInventory().getTopInventory().getHolder() instanceof CrateMenu) {
                return;
            }
            Long left = autoRuns.remove(player.getUniqueId());
            Integer trashed = autoTrashed.remove(player.getUniqueId());
            if (left != null && left > 0) {
                CrateKind kind = selectedKinds.getOrDefault(
                        player.getUniqueId(), CrateKind.DEFAULT
                );
                player.sendMessage(PlayerMenuService.prefix().append(Component.text(
                        "Auto open stopped. " + currencyCount(player, kind) + " "
                                + kind.currency().shortName(currencyCount(player, kind)) + " left."
                                + (trashed == null ? "" : " Auto Trash removed " + trashed + "."),
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
        creditOnlineRewardOnQuit(player, now);
        autoRuns.remove(player.getUniqueId());
        autoTrashed.remove(player.getUniqueId());
        lastRewardTrashed.remove(player.getUniqueId());
        watchingReveal.remove(player.getUniqueId());
        onlineRewardStarted.remove(player.getUniqueId());
        onlineRewardStates.remove(player.getUniqueId());
        displayedOnlineTiers.remove(player.getUniqueId());
        hideKeyBar(player.getUniqueId());
        RollSession session = sessions.remove(player.getUniqueId());
        if (session != null && session.task != null) {
            session.task.cancel();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (VerificationLobbyService.isLobbyWorld(event.getPlayer().getWorld())) {
            hideKeyBar(event.getPlayer().getUniqueId());
            return;
        }
        items.upgradeLegacyKeys(event.getPlayer());
        long now = System.currentTimeMillis();
        onlineCreditStarted.put(event.getPlayer().getUniqueId(), now);
        onlineRewardStarted.put(event.getPlayer().getUniqueId(), now);
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
            if (VerificationLobbyService.isLobbyWorld(player.getWorld())) {
                continue;
            }
            items.upgradeLegacyKeys(player);
            onlineCreditStarted.put(player.getUniqueId(), now);
            onlineRewardStarted.put(player.getUniqueId(), now);
        }
        hourlyTask = plugin.getServer().getScheduler().runTaskTimer(
                plugin, this::creditOnlinePlayers, ONLINE_PULSE_TICKS, ONLINE_PULSE_TICKS
        );
        keyBarTask = plugin.getServer().getScheduler().runTaskTimer(
                plugin, this::refreshKeyBars, KEY_BAR_TICKS, KEY_BAR_TICKS
        );
        countdownTask = plugin.getServer().getScheduler().runTaskTimer(
                plugin, this::refreshCountdowns, COUNTDOWN_TICKS, COUNTDOWN_TICKS
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
        if (countdownTask != null) {
            countdownTask.cancel();
            countdownTask = null;
        }
        for (UUID playerId : Set.copyOf(keyBars.keySet())) {
            hideKeyBar(playerId);
        }
        onlineCreditStarted.clear();
        onlineRewardStarted.clear();
        onlineRewardStates.clear();
        displayedOnlineTiers.clear();
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
        boolean onlineRewardsEnabled = variables.bool("online-rewards.enabled");
        int onlinePlayers = (int) plugin.getServer().getOnlinePlayers().stream()
                .filter(player -> !VerificationLobbyService.isLobbyWorld(player.getWorld()))
                .count();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            UUID playerId = player.getUniqueId();
            if (VerificationLobbyService.isLobbyWorld(player.getWorld())) {
                onlineRewardStarted.remove(playerId);
                onlineRewardStates.remove(playerId);
                displayedOnlineTiers.remove(playerId);
                hideKeyBar(playerId);
                continue;
            }
            if (!settings.isEnabled(playerId, PlayerSettingsStore.Setting.KEY_TIMER_BAR)) {
                hideKeyBar(playerId);
                continue;
            }
            Component title;
            float progress;
            BossBar.Color colour;
            if (onlineRewardsEnabled) {
                long startedAt = onlineRewardStarted.computeIfAbsent(playerId, ignored -> now);
                OnlineRewardDisplay.Status status = onlineRewardStatus(
                        player, startedAt, now, onlinePlayers
                );
                title = OnlineRewardDisplay.bossBar(status, now);
                progress = KeyTimer.progress(
                        status.rewardRemainingMillis(),
                        Duration.ofMinutes(status.intervalMinutes()).toMillis()
                );
                colour = OnlineRewardDisplay.barColor(status.onlineBonusKeys());
                Integer previousTier = displayedOnlineTiers.put(playerId, status.tier());
                if (previousTier != null && status.tier() > previousTier) {
                    player.sendMessage(PlayerMenuService.prefix().append(
                            OnlineRewardDisplay.tierUp(status)
                    ));
                    player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP,
                            0.9f, 1.25f);
                }
            } else {
                displayedOnlineTiers.remove(playerId);
                Long since = onlineCreditStarted.get(playerId);
                long uncredited = since == null ? 0L : now - since;
                long remaining = KeyTimer.remaining(store.millisUntilNextKey(playerId), uncredited);
                title = keyBarTitle(remaining);
                progress = KeyTimer.progress(remaining, CrateStore.HOURLY_KEY_MILLIS);
                colour = BossBar.Color.YELLOW;
            }
            BossBar bar = keyBars.get(playerId);
            if (bar == null) {
                bar = BossBar.bossBar(
                        title, progress, colour, BossBar.Overlay.PROGRESS
                );
                keyBars.put(playerId, bar);
                player.showBossBar(bar);
            } else {
                bar.name(title);
                bar.progress(progress);
                bar.color(colour);
            }
        }
        // A player who logged out between pulses still owns a bar in the map.
        for (UUID playerId : Set.copyOf(keyBars.keySet())) {
            if (plugin.getServer().getPlayer(playerId) == null) {
                keyBars.remove(playerId);
            }
        }
    }

    private OnlineRewardDisplay.Status onlineRewardStatus(
            Player player,
            long startedAt,
            long now,
            int onlinePlayers
    ) {
        UUID playerId = player.getUniqueId();
        int intervalMinutes = variables.integer("online-rewards.interval-minutes");
        long intervalMillis = Duration.ofMinutes(intervalMinutes).toMillis();
        OnlineRewardState state = onlineRewardStates.get(playerId);
        long rewarded = state != null && state.sessionStartedAt() == startedAt
                ? state.rewardedIntervals() : 0L;
        long nextRewardAt = startedAt + (rewarded + 1L) * intervalMillis;
        long remaining = Math.max(0L, nextRewardAt - now);
        long lifetimeSeconds = lifetimeOnlineSeconds(player);
        GameVariableStore.OnlineRewardTier tier = variables.onlineRewardTier(lifetimeSeconds);
        int onlineBonus = variables.onlinePopulationBonusKeys(onlinePlayers);
        int eventMultiplier = variables.bool("online-rewards.key-events-multiply-bonus")
                ? plugin.keyEventMultiplier() : 1;
        int keys = Math.multiplyExact(
                Math.addExact(tier.bonusKeys(), onlineBonus), eventMultiplier
        );
        return new OnlineRewardDisplay.Status(
                tier.number(), keys, onlinePlayers, onlineBonus, intervalMinutes, remaining
        );
    }

    private static long lifetimeOnlineSeconds(Player player) {
        return Math.max(0L, (long) player.getStatistic(Statistic.PLAY_ONE_MINUTE)) / 20L;
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
            if (VerificationLobbyService.isLobbyWorld(player.getWorld())) {
                onlineCreditStarted.remove(player.getUniqueId());
                onlineRewardStarted.remove(player.getUniqueId());
                onlineRewardStates.remove(player.getUniqueId());
                continue;
            }
            Long previous = onlineCreditStarted.get(player.getUniqueId());
            if (previous == null) {
                onlineCreditStarted.put(player.getUniqueId(), now);
                onlineRewardStarted.putIfAbsent(player.getUniqueId(), now);
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
            // The "banked, make space" line is the one notice worth keeping even when
            // key notices are off: it is the only sign the keys are waiting.
            if (entry.getValue().earned() > 0 && delivered == 0 && settings.isEnabled(
                    player.getUniqueId(), PlayerSettingsStore.Setting.CHAT_NOTIFICATIONS
            )) {
                player.sendMessage(PlayerMenuService.prefix().append(Component.text(
                        "Your " + entry.getValue().earned() + " hourly crate "
                                + (entry.getValue().earned() == 1 ? "key is" : "keys are")
                                + " banked. Clear inventory space to receive "
                                + (entry.getValue().earned() == 1 ? "it." : "them."),
                        NamedTextColor.GOLD
                )));
            }
        }
        creditOnlineRewards(now);
    }

    /**
     * Adds a second stay-online ladder above the ordinary hourly key rate.
     *
     * <p>The interval belongs to this connected session. Movement and chat do nothing to
     * it; only leaving the server starts the countdown over. The tier comes from vanilla
     * lifetime playtime, so existing players keep every hour they already earned.
     */
    private void creditOnlineRewards(long now) {
        long intervalMillis = Duration.ofMinutes(
                variables.integer("online-rewards.interval-minutes")
        ).toMillis();
        List<? extends Player> eligible = plugin.getServer().getOnlinePlayers().stream()
                .filter(player -> !VerificationLobbyService.isLobbyWorld(player.getWorld()))
                .toList();
        int onlinePlayers = eligible.size();
        boolean enabled = variables.bool("online-rewards.enabled");
        Set<UUID> currentlyOnline = new java.util.HashSet<>();
        for (Player player : eligible) {
            UUID playerId = player.getUniqueId();
            currentlyOnline.add(playerId);
            long startedAt = onlineRewardStarted.computeIfAbsent(playerId, ignored -> now);
            creditOnlineReward(player, startedAt, now, intervalMillis, onlinePlayers, enabled);
        }
        onlineRewardStates.keySet().retainAll(currentlyOnline);
        displayedOnlineTiers.keySet().retainAll(currentlyOnline);
    }

    private void creditOnlineReward(
            Player player,
            long startedAt,
            long now,
            long intervalMillis,
            int onlinePlayers,
            boolean enabled
    ) {
        UUID playerId = player.getUniqueId();
        long completed = Math.max(0L, now - startedAt) / intervalMillis;
        OnlineRewardState state = onlineRewardStates.get(playerId);
        long rewarded = state != null && state.sessionStartedAt() == startedAt
                ? state.rewardedIntervals() : 0L;
        if (!enabled) {
            onlineRewardStates.put(playerId, new OnlineRewardState(startedAt, completed));
            return;
        }
        while (rewarded < completed) {
            long interval = rewarded + 1L;
            long lifetimeAtReward = Math.max(
                    0L,
                    lifetimeOnlineSeconds(player) - (completed - interval)
                            * (intervalMillis / 1_000L)
            );
            try {
                deliverOnlineReward(player, lifetimeAtReward, onlinePlayers);
            } catch (ArithmeticException | UncheckedIOException exception) {
                plugin.getLogger().warning("Could not deliver an online stay reward to "
                        + player.getName() + ": " + exception.getMessage());
                break;
            }
            rewarded = interval;
            onlineRewardStates.put(playerId, new OnlineRewardState(startedAt, rewarded));
        }
        onlineRewardStates.putIfAbsent(playerId, new OnlineRewardState(startedAt, rewarded));
    }

    private void creditOnlineRewardOnQuit(Player player, long now) {
        Long startedAt = onlineRewardStarted.get(player.getUniqueId());
        if (startedAt == null || startedAt > now) {
            return;
        }
        long intervalMillis = Duration.ofMinutes(
                variables.integer("online-rewards.interval-minutes")
        ).toMillis();
        int onlinePlayers = (int) plugin.getServer().getOnlinePlayers().stream()
                .filter(online -> !VerificationLobbyService.isLobbyWorld(online.getWorld()))
                .count();
        creditOnlineReward(
                player, startedAt, now, intervalMillis, Math.max(1, onlinePlayers),
                variables.bool("online-rewards.enabled")
        );
    }

    private void deliverOnlineReward(
            Player player, long lifetimeOnlineSeconds, int onlinePlayers
    ) {
        GameVariableStore.OnlineRewardTier tier = variables.onlineRewardTier(lifetimeOnlineSeconds);
        int onlineBonus = variables.onlinePopulationBonusKeys(onlinePlayers);
        int keys = Math.addExact(tier.bonusKeys(), onlineBonus);
        int displayedOnlineBonus = onlineBonus;
        if (variables.bool("online-rewards.key-events-multiply-bonus")) {
            int eventMultiplier = plugin.keyEventMultiplier();
            keys = Math.multiplyExact(keys, eventMultiplier);
            displayedOnlineBonus = Math.multiplyExact(onlineBonus, eventMultiplier);
        }

        List<String> delivered = new ArrayList<>();
        if (keys > 0) {
            store.bankKeys(player.getUniqueId(), keys);
            int accepted = deliverBankedKeys(player, false);
            delivered.add(keys + " bonus " + (keys == 1 ? "key" : "keys")
                    + (accepted < keys ? " (banked if inventory is full)" : ""));
        }
        int emeralds = rollAmount(tier.emeralds(), tier.emeraldOneIn());
        int diamonds = rollAmount(tier.diamonds(), tier.diamondOneIn());
        int netherite = rollAmount(tier.netheriteIngots(), tier.netheriteOneIn());
        int shards = rollAmount(tier.shards(), tier.shardOneIn());
        giveOnlineRewardItem(player, Material.EMERALD, emeralds, delivered, "emerald");
        giveOnlineRewardItem(player, Material.DIAMOND, diamonds, delivered, "diamond");
        giveOnlineRewardItem(player, Material.NETHERITE_INGOT, netherite, delivered, "netherite ingot");
        if (shards > 0) {
            giveOnlineRewardStack(player, items.shard(shards));
            delivered.add(shards + " " + (shards == 1 ? "Shard" : "Shards"));
            ServerEvent.of(
                    "online_shard_reward", ServerEvent.CATEGORY_CRATE,
                    player.getUniqueId(), player.getName(), plugin::recordServerEvent
            ).summary(player.getName() + " earned an exceptionally rare online Shard")
                    .detail("Online tier", tier.number())
                    .detail("Lifetime online hours", lifetimeOnlineSeconds / 3_600L)
                    .detail("Online players", onlinePlayers)
                    .record();
        }
        if (delivered.isEmpty()) {
            delivered.add("progress toward the next tier");
        }
        // One persistent line per connected interval is the receipt for the whole ladder.
        player.sendMessage(PlayerMenuService.prefix()
                .append(Component.text("Online Tier " + tier.number() + " reward: ",
                        NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD))
                .append(Component.text(String.join(", ", delivered) + ". ",
                        NamedTextColor.WHITE))
                .append(Component.text(onlinePlayers + " online added " + displayedOnlineBonus
                        + " bonus " + (displayedOnlineBonus == 1 ? "key" : "keys") + ".",
                        NamedTextColor.GRAY)));
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP,
                0.8f, shards > 0 ? 1.7f : 1.25f);
    }

    private static int rollAmount(int amount, int oneIn) {
        if (amount <= 0) {
            return 0;
        }
        return ThreadLocalRandom.current().nextInt(Math.max(1, oneIn)) == 0 ? amount : 0;
    }

    private void giveOnlineRewardItem(
            Player player, Material material, int amount, List<String> delivered, String name
    ) {
        if (amount <= 0) {
            return;
        }
        for (int portion : StackSplit.portions(amount, material.getMaxStackSize())) {
            giveOnlineRewardStack(player, new ItemStack(material, portion));
        }
        delivered.add(amount + " " + name + (amount == 1 ? "" : "s"));
    }

    private static void giveOnlineRewardStack(Player player, ItemStack stack) {
        player.getInventory().addItem(stack).values().forEach(overflow -> {
            Item drop = player.getWorld().dropItemNaturally(player.getLocation(), overflow);
            drop.setOwner(player.getUniqueId());
            drop.setPickupDelay(0);
        });
    }

    /** What an hour online is worth to this player, before any live event. */
    private int keysPerHour(Player player) {
        return variables.keysPerHour(perks.profile(player.getUniqueId()).booster());
    }

    int keyCost(CrateKind kind) {
        return variables.keyCost(kind);
    }

    private Map<UUID, CrateStore.KeyCredit> creditOnline(Map<UUID, Long> elapsed) {
        if (elapsed.isEmpty()) {
            return Map.of();
        }
        try {
            Map<UUID, Integer> rates = new HashMap<>();
            int eventRate = plugin.keyEventMultiplier();
            elapsed.keySet().forEach(playerId -> rates.put(
                    playerId,
                    variables.keysPerHour(perks.profile(playerId).booster())
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
        int delivered = items.giveKeys(player, banked) ? banked : 0;
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
            playCrateSound(player, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.15f);
        }
        return delivered;
    }

    private long currencyCount(Player player, CrateKind kind) {
        return kind.currency() == CrateKind.Currency.SHARD
                ? items.countShards(player) : items.count(player);
    }

    private int removeCurrency(Player player, CrateKind kind, int count) {
        return kind.currency() == CrateKind.Currency.SHARD
                ? items.removeShards(player, count) : items.remove(player, count);
    }

    private ItemStack currencyItem(CrateKind kind, int count) {
        return kind.currency() == CrateKind.Currency.SHARD
                ? items.shard(count) : items.key(count);
    }

    private void returnCurrency(Player player, CrateKind kind, int count) {
        if (count <= 0) {
            return;
        }
        if (kind.currency() == CrateKind.Currency.KEY && items.giveKeys(player, count)) return;
        player.getInventory().addItem(currencyItem(kind, count)).values().forEach(overflow ->
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

    private void fillCrate(Inventory inventory, CrateKind kind, List<Lane> lanes) {
        ItemStack filler = MenuItems.button(Material.BROWN_STAINED_GLASS_PANE, "Crate Panel");
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler);
        }
        ItemStack brace = MenuItems.button(Material.IRON_BARS, "Iron Crate Brace");
        for (int slot : List.of(0, 8, 36, 44)) {
            inventory.setItem(slot, brace);
        }
        for (Lane lane : lanes) {
            for (int slot = lane.reelFirst; slot <= lane.reelLast; slot++) {
                inventory.setItem(slot, items.preview(kind.randomPreview(), cosmeticItems));
            }
            // The marker sits at the end of the row rather than above and below it,
            // because with three reels there is no row left to spare for a pointer.
            inventory.setItem(lane.reelFirst - 1,
                    MenuItems.button(Material.SPECTRAL_ARROW, "Winning Slot"));
            inventory.setItem(lane.reelLast + 1,
                    MenuItems.button(Material.HOPPER, "Reward Locked In"));
        }
        inventory.setItem(4, MenuItems.button(Material.BARREL, "Opening"));
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
                        " (" + reward.actualChance() + ").", NamedTextColor.GRAY
                ));
    }




    /**
     * One crate's tile on the chooser, with the countdown when the crate is limited.
     *
     * <p>Shared with {@link #refreshCountdowns()} so the tile a player is looking at
     * and the tile the timer rewrites are drawn by the same code.
     */
    private ItemStack selectButton(CrateKind kind, boolean oddsOnly, long now) {
        String action = oddsOnly
                ? "View exact odds."
                : keyCost(kind) + " " + kind.currency().shortName(keyCost(kind)) + " required.";
        if (!kind.limited()) {
            return MenuItems.button(kind.icon(), kind.menuName(), "Permanent rewards.", action);
        }
        boolean open = kind.available(now);
        List<Component> lore = new ArrayList<>(kind.countdownLines(now));
        lore.add(Component.text(open ? action : "No longer open.", NamedTextColor.GRAY));
        return MenuItems.detailed(open ? kind.icon() : Material.BARRIER, kind.menuName(), lore);
    }

    /** The key tile on a crate's own screen, carrying that crate's countdown. */
    private ItemStack hubKeys(Player player, CrateKind kind, long now) {
        String held = "In inventory: " + currencyCount(player, kind);
        if (!kind.limited()) {
            return named(
                    currencyItem(kind, 1),
                    kind.currency() == CrateKind.Currency.SHARD ? "Your Shards" : "Your Keys",
                    held,
                    "Permanent crate"
            );
        }
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(held, NamedTextColor.GRAY));
        lore.addAll(kind.countdownLines(now));
        ItemStack item = currencyItem(kind, 1);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("Your Keys", ORANGE, TextDecoration.BOLD)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(lore.stream()
                    .map(line -> line.decoration(TextDecoration.ITALIC, false))
                    .toList());
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * Reruns the countdown on every crate screen that is currently open.
     *
     * <p>Only the one slot holding the countdown is rewritten, so a player part way
     * through clicking something else on the same screen is never disturbed.
     */
    private void refreshCountdowns() {
        long now = System.currentTimeMillis();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (!(player.getOpenInventory().getTopInventory().getHolder() instanceof CrateMenu menu)
                    || menu.inventory == null) {
                continue;
            }
            if (menu.screen == Screen.SELECT || menu.screen == Screen.ODDS_SELECT) {
                menu.inventory.setItem(SELECT_AMETHYST_SLOT, selectButton(
                        CrateKind.AMETHYST, menu.screen == Screen.ODDS_SELECT, now
                ));
            } else if (menu.screen == Screen.HUB && menu.kind != null && menu.kind.limited()) {
                menu.inventory.setItem(HUB_KEYS_SLOT, hubKeys(player, menu.kind, now));
            } else {
                continue;
            }
            player.updateInventory();
        }
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
        if (args.length < 3) {
            return 1;
        }
        try {
            return Math.max(1, Integer.parseInt(args[2]));
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
