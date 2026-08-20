package bot.mgx.accessbridge;

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
import org.bukkit.event.inventory.InventoryClickEvent;
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
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** One-key lootbox spins, published odds, the reel animation, and pending claims. */
final class LootboxService implements CommandExecutor, TabCompleter, Listener {
    private static final TextColor ORANGE = TextColor.color(0xFF9900);
    private static final int HUB_OPEN_SLOT = 13;
    private static final int HUB_ODDS_SLOT = 15;
    private static final int ODDS_PER_PAGE = 45;
    private static final int PREVIOUS_SLOT = 45;
    private static final int NEXT_SLOT = 53;
    private static final int REEL_FIRST = 9;
    private static final int REEL_LAST = 17;
    private static final int WINNING_SLOT = 13;
    private static final int REEL_FRAMES = 29;

    private enum Screen {
        HUB,
        ODDS,
        ROLL
    }

    private static final class LootboxMenu implements InventoryHolder {
        private final Screen screen;
        private final int page;
        private Inventory inventory;

        LootboxMenu(Screen screen, int page) {
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
        private final LootboxStore.Pending pending;
        private final LootboxCatalog.Reward reward;
        private final Inventory inventory;
        private int frame;
        private BukkitTask task;

        RollSession(
                UUID playerId,
                LootboxStore.Pending pending,
                LootboxCatalog.Reward reward,
                Inventory inventory
        ) {
            this.playerId = playerId;
            this.pending = pending;
            this.reward = reward;
            this.inventory = inventory;
        }
    }

    private final MGXAccessBridge plugin;
    private final LootboxStore store;
    private final LootboxItems items;
    private final CosmeticStore cosmetics;
    private final CosmeticItems cosmeticItems;
    private final CosmeticEffectService effects;
    private final Map<UUID, RollSession> sessions = new HashMap<>();

    LootboxService(
            MGXAccessBridge plugin,
            LootboxStore store,
            LootboxItems items,
            CosmeticStore cosmetics,
            CosmeticItems cosmeticItems,
            CosmeticEffectService effects
    ) {
        this.plugin = plugin;
        this.store = store;
        this.items = items;
        this.cosmetics = cosmetics;
        this.cosmeticItems = cosmeticItems;
        this.effects = effects;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        args = CommandArgs.withoutEchoedSender(sender.getName(), args);
        if (args.length > 0 && (args[0].equalsIgnoreCase("key")
                || args[0].equalsIgnoreCase("give"))) {
            return giveKey(sender, args);
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Use /lootbox key <online player> [amount].");
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
                    PlayerMenuService.error(player, "Your lootbox is still spinning.");
                    return true;
                }
                if (!deliverPending(player, true)) {
                    player.sendMessage(PlayerMenuService.prefix().append(Component.text(
                            "You do not have a reward waiting.", NamedTextColor.GRAY
                    )));
                }
            }
            default -> PlayerMenuService.error(
                    player, "Use /lootbox, /lootbox open, /lootbox odds, or /lootbox claim."
            );
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> values = new ArrayList<>(List.of("open", "odds", "claim"));
            if (plugin.mayAdminister(sender)) {
                values.add("key");
            }
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return values.stream().filter(value -> value.startsWith(prefix)).toList();
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("key")
                || args[0].equalsIgnoreCase("give")) && plugin.mayAdminister(sender)) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return plugin.getServer().getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        }
        return List.of();
    }

    void openHub(Player player) {
        if (!sessions.containsKey(player.getUniqueId())) {
            deliverPending(player, false);
        }
        LootboxMenu holder = new LootboxMenu(Screen.HUB, 1);
        Inventory inventory = Bukkit.createInventory(holder, 27, Component.text("Lootbox", ORANGE));
        holder.inventory = inventory;
        long now = System.currentTimeMillis();
        int remaining = store.remaining(player.getUniqueId(), now);
        inventory.setItem(11, named(
                items.key(1),
                "Your Keys",
                "Keys in inventory: " + items.count(player),
                "One key equals one spin."
        ));
        List<String> openLore = new ArrayList<>();
        openLore.add("Consumes exactly one key.");
        openLore.add("Spins left in your rolling window: " + remaining + "/" + LootboxStore.OPENING_LIMIT);
        if (remaining == 0) {
            openLore.add("Next spin in " + remainingTime(store.nextOpeningAt(
                    player.getUniqueId(), now
            ) - now) + ".");
        }
        inventory.setItem(HUB_OPEN_SLOT, MenuItems.button(
                remaining > 0 ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE,
                "Spin Lootbox",
                openLore
        ));
        inventory.setItem(HUB_ODDS_SLOT, MenuItems.button(
                Material.BOOK,
                "View Exact Odds",
                "Every visible percentage is exact.",
                "The hidden cosmetic displays ??? by design."
        ));
        inventory.setItem(22, MenuItems.button(
                Material.CLOCK,
                "Economy Protection",
                "Maximum: 3 spins in any rolling 24 hours.",
                "Odds never decrease and there is no hidden pity rule."
        ));
        MenuItems.show(plugin, player, inventory);
    }

    private void openOdds(Player player, int requestedPage) {
        List<LootboxCatalog.Reward> rewards = LootboxCatalog.all();
        int pageCount = Math.max(1, (rewards.size() + ODDS_PER_PAGE - 1) / ODDS_PER_PAGE);
        int page = Math.max(1, Math.min(pageCount, requestedPage));
        LootboxMenu holder = new LootboxMenu(Screen.ODDS, page);
        Inventory inventory = Bukkit.createInventory(
                holder, 54, Component.text("Lootbox Odds " + page + "/" + pageCount, ORANGE)
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
            PlayerMenuService.error(player, "Your lootbox is already spinning.");
            return;
        }
        if (store.pending(playerId).isPresent()) {
            if (!deliverPending(player, true)) {
                return;
            }
        }
        long now = System.currentTimeMillis();
        if (store.remaining(playerId, now) <= 0) {
            PlayerMenuService.error(player, "Your next spin opens in " + remainingTime(
                    store.nextOpeningAt(playerId, now) - now
            ) + ".");
            return;
        }
        if (items.count(player) <= 0) {
            PlayerMenuService.error(player, "You need a Mysterious Lootbox Key to spin.");
            return;
        }
        LootboxCatalog.Reward reward = LootboxCatalog.rewardAt(
                ThreadLocalRandom.current().nextInt(LootboxCatalog.TOTAL_WEIGHT)
        );
        UUID spinId = UUID.randomUUID();
        if (!items.consume(player)) {
            PlayerMenuService.error(player, "Your key moved before it could be consumed.");
            return;
        }
        LootboxStore.Pending pending;
        try {
            pending = store.reserve(playerId, spinId, reward.id(), now);
        } catch (LootboxStore.LimitReachedException exception) {
            player.getInventory().addItem(items.key(1));
            PlayerMenuService.error(player, "Your next spin opens in " + remainingTime(
                    exception.nextOpeningAt() - now
            ) + ".");
            return;
        } catch (IllegalStateException | UncheckedIOException exception) {
            player.getInventory().addItem(items.key(1));
            plugin.getLogger().warning("Could not reserve a lootbox reward: " + exception.getMessage());
            PlayerMenuService.error(player, "That spin could not be saved. Your key was returned.");
            return;
        }

        LootboxMenu holder = new LootboxMenu(Screen.ROLL, 1);
        Inventory inventory = Bukkit.createInventory(holder, 27, Component.text("Lootbox Rolling", ORANGE));
        holder.inventory = inventory;
        fillReel(inventory);
        RollSession session = new RollSession(playerId, pending, reward, inventory);
        sessions.put(playerId, session);
        MenuItems.show(plugin, player, inventory);
        advance(session);
    }

    private void advance(RollSession session) {
        Player player = plugin.getServer().getPlayer(session.playerId);
        if (player == null) {
            sessions.remove(session.playerId);
            return;
        }
        if (session.frame >= REEL_FRAMES) {
            finish(session, player);
            return;
        }
        for (int slot = REEL_FIRST; slot < REEL_LAST; slot++) {
            session.inventory.setItem(slot, session.inventory.getItem(slot + 1));
        }
        LootboxCatalog.Reward preview = LootboxCatalog.rewardAt(
                ThreadLocalRandom.current().nextInt(LootboxCatalog.TOTAL_WEIGHT)
        );
        session.inventory.setItem(REEL_LAST, items.preview(preview, cosmeticItems));
        player.playSound(
                player.getLocation(),
                session.frame < 23 ? Sound.BLOCK_NOTE_BLOCK_HAT : Sound.BLOCK_NOTE_BLOCK_PLING,
                0.55f,
                Math.min(1.8f, 0.7f + session.frame * 0.035f)
        );
        session.frame++;
        long delay = session.frame < 14 ? 2L
                : session.frame < 22 ? 3L
                : session.frame < 27 ? 5L
                : 8L;
        session.task = plugin.getServer().getScheduler().runTaskLater(
                plugin, () -> advance(session), delay
        );
    }

    private void finish(RollSession session, Player player) {
        for (int slot = REEL_FIRST; slot <= REEL_LAST; slot++) {
            session.inventory.setItem(slot, slot == WINNING_SLOT
                    ? items.preview(session.reward, cosmeticItems)
                    : MenuItems.button(Material.BLACK_STAINED_GLASS_PANE, " "));
        }
        session.inventory.setItem(4, MenuItems.button(Material.SPECTRAL_ARROW, "Winning Reward"));
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.15f);
        session.task = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            sessions.remove(session.playerId);
            if (!deliverPending(player, true)) {
                PlayerMenuService.error(
                        player, "Your inventory is full. Make room, then use /lootbox claim."
                );
            }
        }, 20L);
    }

    private boolean deliverPending(Player player, boolean tellWhenFull) {
        LootboxStore.Pending pending = store.pending(player.getUniqueId()).orElse(null);
        if (pending == null) {
            items.finishOrphanedRewards(player);
            return false;
        }
        LootboxCatalog.Reward reward = LootboxCatalog.find(pending.rewardId()).orElse(null);
        if (reward == null) {
            plugin.getLogger().severe("Unknown pending lootbox reward " + pending.rewardId());
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
            auditWin(player, pending, reward);
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
                plugin.getLogger().warning("Could not recover lootbox reward "
                        + pending.spinId() + ": " + exception.getMessage());
                PlayerMenuService.error(player, "Your saved reward could not be recovered yet.");
                return false;
            }
            items.finishReward(player, pending.spinId());
            player.sendMessage(winMessage(reward));
            auditWin(player, pending, reward);
            return true;
        }
        ItemStack prize = items.reward(reward, pending.spinId());
        if (!canFit(player.getInventory(), prize)) {
            if (tellWhenFull) {
                PlayerMenuService.error(
                        player, "Make room for " + reward.displayName() + ", then use /lootbox claim."
                );
            }
            return false;
        }
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(prize);
        if (!leftover.isEmpty()) {
            plugin.getLogger().warning("A capacity-checked lootbox reward did not fit for "
                    + player.getUniqueId());
            items.removeReward(player, pending.spinId());
            return false;
        }
        try {
            if (!store.complete(player.getUniqueId(), pending.spinId())) {
                items.removeReward(player, pending.spinId());
                plugin.getLogger().warning("Pending lootbox spin changed before delivery for "
                        + player.getUniqueId());
                return false;
            }
        } catch (UncheckedIOException exception) {
            items.removeReward(player, pending.spinId());
            plugin.getLogger().warning("Could not commit lootbox reward "
                    + pending.spinId() + ": " + exception.getMessage());
            PlayerMenuService.error(player, "Your saved reward could not be delivered yet.");
            return false;
        }
        items.finishReward(player, pending.spinId());
        player.sendMessage(winMessage(reward));
        auditWin(player, pending, reward);
        return true;
    }

    private boolean giveKey(CommandSender sender, String[] args) {
        if (!plugin.mayAdminister(sender)) {
            sender.sendMessage("You do not have permission to issue lootbox keys.");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage("Use /lootbox key <online player> [amount].");
            return true;
        }
        Player target = plugin.getServer().getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage("That player is not online.");
            return true;
        }
        int amount = 1;
        if (args.length >= 3) {
            try {
                amount = Integer.parseInt(args[2]);
            } catch (NumberFormatException exception) {
                sender.sendMessage("The key amount must be a whole number.");
                return true;
            }
        }
        if (amount < 1 || amount > 64) {
            sender.sendMessage("Issue between 1 and 64 keys at a time.");
            return true;
        }
        Map<Integer, ItemStack> leftover = target.getInventory().addItem(items.key(amount));
        leftover.values().forEach(item -> target.getWorld().dropItemNaturally(target.getLocation(), item));
        sender.sendMessage("Issued " + amount + " lootbox "
                + (amount == 1 ? "key" : "keys") + " to " + target.getName() + ".");
        target.sendMessage(PlayerMenuService.prefix().append(Component.text(
                "You received " + amount + " lootbox " + (amount == 1 ? "key" : "keys") + ".",
                NamedTextColor.GREEN
        )));
        UUID actor = sender instanceof Player player ? player.getUniqueId() : null;
        ServerEvent.of(
                "lootbox_key_grant",
                ServerEvent.CATEGORY_ADMIN,
                actor,
                sender.getName(),
                plugin::recordServerEvent
        ).summary("Issued " + amount + " lootbox " + (amount == 1 ? "key" : "keys")
                        + " to " + target.getName())
                .detail("target", target.getName())
                .detail("target_uuid", target.getUniqueId().toString())
                .detail("amount", amount)
                .record();
        return true;
    }

    private void auditWin(
            Player player, LootboxStore.Pending pending, LootboxCatalog.Reward reward
    ) {
        if (!reward.highImpact()) {
            return;
        }
        ServerEvent.of(
                "lootbox_rare_win",
                ServerEvent.CATEGORY_ECONOMY,
                player.getUniqueId(),
                player.getName(),
                plugin::recordServerEvent
        ).summary(player.getName() + " won " + reward.displayName() + " from a lootbox")
                .detail("reward", reward.displayName())
                .detail("chance", reward.displayedChance())
                .detail("spin_id", pending.spinId().toString())
                .record();
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof LootboxMenu menu)
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
        if (event.getInventory().getHolder() instanceof LootboxMenu) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        RollSession session = sessions.remove(event.getPlayer().getUniqueId());
        if (session != null && session.task != null) {
            session.task.cancel();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (event.getPlayer().isOnline()) {
                deliverPending(event.getPlayer(), true);
            }
        }, 40L);
    }

    void stop() {
        for (RollSession session : sessions.values()) {
            if (session.task != null) {
                session.task.cancel();
            }
        }
        sessions.clear();
    }

    private void fillReel(Inventory inventory) {
        ItemStack filler = MenuItems.button(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler);
        }
        for (int slot = REEL_FIRST; slot <= REEL_LAST; slot++) {
            LootboxCatalog.Reward preview = LootboxCatalog.rewardAt(
                    ThreadLocalRandom.current().nextInt(LootboxCatalog.TOTAL_WEIGHT)
            );
            inventory.setItem(slot, items.preview(preview, cosmeticItems));
        }
        inventory.setItem(4, MenuItems.button(Material.SPECTRAL_ARROW, "Winning Slot"));
        inventory.setItem(22, MenuItems.button(
                Material.TRIPWIRE_HOOK,
                "Key Consumed",
                "The reward is already selected and saved.",
                "Closing the animation cannot reroll it."
        ));
    }

    private static Component winMessage(LootboxCatalog.Reward reward) {
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
