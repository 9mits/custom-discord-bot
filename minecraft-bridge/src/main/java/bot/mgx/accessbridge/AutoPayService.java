package bot.mgx.accessbridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Forwards a player's money to another account on a timer.
 *
 * <p>Built for the farm case: an alt that has to keep being paid to keep something
 * running. That is why the target may be offline, and why nothing is announced on
 * each transfer — at a five-second interval a chat line per payment is unusable.
 * The same reasoning as {@code sweepAutoSell}, for the same reason.
 */
final class AutoPayService implements CommandExecutor, TabCompleter, Listener {
    private static final TextColor ORANGE = TextColor.color(0xFF9900);
    private static final long SWEEP_TICKS = 20L;

    private static final int SLOT_TOGGLE = 11;
    private static final int SLOT_MODE = 13;
    private static final int SLOT_INTERVAL = 15;

    /** Its own holder, so this screen never touches the shared menu dispatch. */
    private static final class AutoPayMenu implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final MGXAccessBridge plugin;
    private final AutoPayStore store;
    private final EconomyStore money;
    /** In memory only: a restart costing one interval is not worth a disk write. */
    private final Map<UUID, Long> lastPaidAt = new HashMap<>();
    private final Map<UUID, Long> sentThisSession = new HashMap<>();
    private BukkitTask task;

    AutoPayService(MGXAccessBridge plugin, AutoPayStore store, EconomyStore money) {
        this.plugin = plugin;
        this.store = store;
        this.money = money;
    }

    void start() {
        task = plugin.getServer().getScheduler().runTaskTimer(
                plugin, this::sweep, SWEEP_TICKS, SWEEP_TICKS
        );
    }

    void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    // ------------------------------------------------------------------ sweep

    private void sweep() {
        long now = System.currentTimeMillis();
        for (Player payer : plugin.getServer().getOnlinePlayers()) {
            UUID payerId = payer.getUniqueId();
            Optional<AutoPayStore.Plan> found = store.plan(payerId);
            if (found.isEmpty()) {
                continue;
            }
            AutoPayStore.Plan plan = found.get();
            long previous = lastPaidAt.getOrDefault(payerId, 0L);
            if (now - previous < plan.intervalMillis()) {
                continue;
            }
            long amount = plan.sendAll() ? money.balance(payerId) : plan.amount();
            if (amount <= 0L) {
                // Nothing to send yet. Not a failure, and not worth a message.
                lastPaidAt.put(payerId, now);
                continue;
            }
            try {
                if (!money.transfer(payerId, plan.target(), amount)) {
                    // Short, or the target's balance would overflow. Silent by
                    // design: at five seconds this would be a wall of text.
                    lastPaidAt.put(payerId, now);
                    continue;
                }
            } catch (RuntimeException failure) {
                plugin.getLogger().warning("Autopay failed for " + payer.getName()
                        + ": " + failure.getClass().getSimpleName());
                lastPaidAt.put(payerId, now);
                continue;
            }
            lastPaidAt.put(payerId, now);
            sentThisSession.merge(payerId, amount, Long::sum);
        }
    }

    // ---------------------------------------------------------------- command

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("/autopay is a player command.");
            return true;
        }
        args = CommandArgs.withoutEchoedSender(sender.getName(), args);
        try {
            if (args.length == 0) {
                openMenu(player);
                return true;
            }
            String first = args[0].toLowerCase(Locale.ROOT);
            if (first.equals("off") || first.equals("stop")) {
                info(player, store.clear(player.getUniqueId())
                        ? "Autopay is off."
                        : "Autopay was not running.");
                return true;
            }
            if (first.equals("status") || first.equals("info")) {
                status(player);
                return true;
            }
            configure(player, args);
        } catch (IllegalArgumentException exception) {
            error(player, exception.getMessage());
        }
        return true;
    }

    private void configure(Player player, String[] args) {
        if (args.length < 2) {
            throw new IllegalArgumentException(
                    "Usage: /autopay <player> <amount|all> [seconds]"
            );
        }
        OfflinePlayer target = resolveTarget(args[0]);
        boolean sendAll = args[1].equalsIgnoreCase("all");
        long amount = sendAll ? 0L : EconomyFormat.parseAmount(args[1]);
        int interval = args.length > 2
                ? parseInterval(args[2])
                : AutoPayStore.DEFAULT_INTERVAL_SECONDS;
        String targetName = target.getName() == null ? args[0] : target.getName();
        store.set(player.getUniqueId(), new AutoPayStore.Plan(
                target.getUniqueId(), targetName, amount, interval, sendAll
        ));
        lastPaidAt.remove(player.getUniqueId());
        info(player, "Autopay is on. Sending "
                + (sendAll ? "everything you have" : EconomyFormat.dollars(amount))
                + " to " + targetName + " every " + interval + "s. Run /autopay off to stop.");
    }

    /**
     * The target does not have to be online — funding an offline alt is the point
     * — but it does have to be somebody who exists, or a typo silently pays a
     * UUID that will never be seen again.
     */
    private OfflinePlayer resolveTarget(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online;
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayerIfCached(name);
        if (offline == null || !offline.hasPlayedBefore()) {
            throw new IllegalArgumentException(
                    "No player named " + name + " has played here. Check the spelling."
            );
        }
        return offline;
    }

    private static int parseInterval(String raw) {
        int seconds;
        try {
            seconds = Integer.parseInt(raw.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Interval must be a whole number of seconds.");
        }
        if (seconds < AutoPayStore.MINIMUM_INTERVAL_SECONDS
                || seconds > AutoPayStore.MAXIMUM_INTERVAL_SECONDS) {
            throw new IllegalArgumentException(
                    "Interval must be between " + AutoPayStore.MINIMUM_INTERVAL_SECONDS
                            + " and " + AutoPayStore.MAXIMUM_INTERVAL_SECONDS + " seconds."
            );
        }
        return seconds;
    }

    private void status(Player player) {
        Optional<AutoPayStore.Plan> found = store.plan(player.getUniqueId());
        if (found.isEmpty()) {
            info(player, "Autopay is off. Use /autopay <player> <amount|all> [seconds].");
            return;
        }
        AutoPayStore.Plan plan = found.get();
        long sent = sentThisSession.getOrDefault(player.getUniqueId(), 0L);
        player.sendMessage(Component.text("Autopay", ORANGE, TextDecoration.BOLD));
        player.sendMessage(Component.text("  Paying  ", NamedTextColor.GRAY)
                .append(Component.text(plan.targetName(), NamedTextColor.WHITE)));
        player.sendMessage(Component.text("  Amount  ", NamedTextColor.GRAY)
                .append(Component.text(
                        plan.sendAll() ? "everything" : EconomyFormat.dollars(plan.amount()),
                        NamedTextColor.WHITE)));
        player.sendMessage(Component.text("  Every   ", NamedTextColor.GRAY)
                .append(Component.text(plan.intervalSeconds() + "s", NamedTextColor.WHITE)));
        player.sendMessage(Component.text("  Sent since restart  ", NamedTextColor.GRAY)
                .append(Component.text(EconomyFormat.dollars(sent), NamedTextColor.WHITE)));
    }

    // ------------------------------------------------------------------- menu

    private void openMenu(Player player) {
        AutoPayMenu holder = new AutoPayMenu();
        Inventory inventory = Bukkit.createInventory(
                holder, 27, Component.text("Autopay", ORANGE)
        );
        holder.inventory = inventory;
        draw(player, inventory);
        MenuItems.show(plugin, player, inventory);
    }

    private void draw(Player player, Inventory inventory) {
        Optional<AutoPayStore.Plan> found = store.plan(player.getUniqueId());
        boolean on = found.isPresent();
        inventory.setItem(SLOT_TOGGLE, MenuItems.button(
                on ? Material.LIME_DYE : Material.GRAY_DYE,
                on ? "Autopay is ON" : "Autopay is OFF",
                on ? "Click to stop paying " + found.get().targetName() + "."
                   : "Set it up with /autopay <player> <amount|all>."
        ));
        inventory.setItem(SLOT_MODE, MenuItems.button(
                Material.GOLD_INGOT,
                found.map(plan -> plan.sendAll()
                                ? "Sending: everything"
                                : "Sending: " + EconomyFormat.dollars(plan.amount()))
                        .orElse("Sending: nothing yet"),
                "Click to switch between a fixed amount and everything.",
                "A fixed amount defaults to $1,000 when switching."
        ));
        inventory.setItem(SLOT_INTERVAL, MenuItems.button(
                Material.CLOCK,
                found.map(plan -> "Every " + plan.intervalSeconds() + "s").orElse("Every -"),
                "Click to cycle: 5s, 30s, 60s, 300s."
        ));
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof AutoPayMenu)) {
            return;
        }
        // A button board: nothing here is a real item, so no click moves anything.
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Optional<AutoPayStore.Plan> found = store.plan(player.getUniqueId());
        try {
            switch (event.getRawSlot()) {
                case SLOT_TOGGLE -> {
                    if (found.isPresent()) {
                        store.clear(player.getUniqueId());
                    } else {
                        error(player, "Set a target first: /autopay <player> <amount|all>.");
                        return;
                    }
                }
                case SLOT_MODE -> {
                    if (found.isEmpty()) {
                        return;
                    }
                    AutoPayStore.Plan plan = found.get();
                    store.set(player.getUniqueId(), new AutoPayStore.Plan(
                            plan.target(), plan.targetName(),
                            plan.sendAll() ? 1_000L : 0L,
                            plan.intervalSeconds(), !plan.sendAll()
                    ));
                }
                case SLOT_INTERVAL -> {
                    if (found.isEmpty()) {
                        return;
                    }
                    AutoPayStore.Plan plan = found.get();
                    store.set(player.getUniqueId(), new AutoPayStore.Plan(
                            plan.target(), plan.targetName(), plan.amount(),
                            nextInterval(plan.intervalSeconds()), plan.sendAll()
                    ));
                }
                default -> {
                    return;
                }
            }
        } catch (RuntimeException exception) {
            error(player, "That could not be saved.");
            return;
        }
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.3f);
        draw(player, event.getInventory());
    }

    static int nextInterval(int current) {
        List<Integer> cycle = List.of(5, 30, 60, 300);
        for (int index = 0; index < cycle.size(); index++) {
            if (cycle.get(index) == current) {
                return cycle.get((index + 1) % cycle.size());
            }
        }
        return AutoPayStore.DEFAULT_INTERVAL_SECONDS;
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender, Command command, String alias, String[] args
    ) {
        if (args.length == 1) {
            List<String> names = new ArrayList<>(
                    plugin.getServer().getOnlinePlayers().stream().map(Player::getName).toList()
            );
            names.add("off");
            names.add("status");
            return names.stream()
                    .filter(name -> name.toLowerCase(Locale.ROOT)
                            .startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length == 2) {
            return List.of("all", "1000", "10000");
        }
        if (args.length == 3) {
            return List.of("5", "30", "60", "300");
        }
        return List.of();
    }

    private static void info(Player player, String message) {
        player.sendMessage(Component.text("Autopay ", ORANGE, TextDecoration.BOLD)
                .append(Component.text(message, NamedTextColor.WHITE)));
    }

    private static void error(Player player, String message) {
        player.sendMessage(Component.text("Autopay ", NamedTextColor.RED, TextDecoration.BOLD)
                .append(Component.text(message, NamedTextColor.WHITE)));
    }
}
