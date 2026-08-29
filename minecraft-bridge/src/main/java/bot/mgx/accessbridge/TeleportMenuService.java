package bot.mgx.accessbridge;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.inventory.Inventory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import static bot.mgx.accessbridge.MenuItems.BOARD_SIZE;
import static bot.mgx.accessbridge.MenuItems.NEXT_SLOT;
import static bot.mgx.accessbridge.MenuItems.ORANGE;
import static bot.mgx.accessbridge.MenuItems.PER_PAGE;
import static bot.mgx.accessbridge.MenuItems.PREVIOUS_SLOT;
import static bot.mgx.accessbridge.MenuItems.button;

/** Inventory directories backed by EssentialsX's real warp and home data. */
final class TeleportMenuService implements Listener {
    private static final Set<String> WARP_COMMANDS = Set.of("warp", "warps", "ewarp", "ewarps");
    private static final Set<String> HOME_COMMANDS = Set.of("home", "homes", "ehome", "ehomes");
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final MGXAccessBridge plugin;
    private final File warpsDirectory;
    private final File userdataDirectory;
    private HomesDialogService homesDialog;
    private WarpChooserService warpChooser;

    TeleportMenuService(MGXAccessBridge plugin) {
        this.plugin = plugin;
        File essentials = new File(plugin.getDataFolder().getParentFile(), "Essentials");
        this.warpsDirectory = new File(essentials, "warps");
        this.userdataDirectory = new File(essentials, "userdata");
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        CommandRequest request = request(event.getMessage());
        if (request == null || request.hasArguments()) {
            return;
        }
        if (WARP_COMMANDS.contains(request.label())) {
            event.setCancelled(true);
            if (warpChooser != null) {
                warpChooser.open(event.getPlayer());
            } else {
                openWarps(event.getPlayer(), 1);
            }
        } else if (HOME_COMMANDS.contains(request.label())) {
            event.setCancelled(true);
            openHomesPreferred(event.getPlayer());
        }
    }

    void openWarps(Player player, int page) {
        open(player, Menu.Kind.TELEPORT_WARPS, "Warps", page, warpNames(), Material.COMPASS);
    }

    /** Wired after construction; the dialog reads this service's home list. */
    void useHomesDialog(HomesDialogService homesDialog) {
        this.homesDialog = homesDialog;
    }

    void useWarpChooser(WarpChooserService warpChooser) {
        this.warpChooser = warpChooser;
    }

    /** The command entry point, which prefers the dialog when the client has one. */
    void openHomesPreferred(Player player) {
        if (homesDialog != null) {
            homesDialog.open(player);
            return;
        }
        openHomes(player, 1);
    }

    void openHomes(Player player, int page) {
        open(player, Menu.Kind.TELEPORT_HOMES, "Homes", page, homeNames(player), Material.RED_BED);
    }

    private void open(
            Player player,
            Menu.Kind kind,
            String title,
            int requestedPage,
            List<String> names,
            Material icon
    ) {
        int page = MenuPaging.clampPage(requestedPage, names.size(), PER_PAGE);
        Menu menu = new Menu(kind, null, page, null);
        Inventory inventory = Bukkit.createInventory(
                menu, BOARD_SIZE, Component.text(MenuItems.pagedTitle(title, page, names.size()), ORANGE)
        );
        menu.attach(inventory);
        int first = MenuPaging.firstIndex(page, names.size(), PER_PAGE);
        int last = MenuPaging.lastIndex(page, names.size(), PER_PAGE);
        for (int index = first; index < last; index++) {
            int slot = index - first;
            String name = names.get(index);
            inventory.setItem(slot, button(icon, name, "Click to teleport.", "Five-second warmup."));
            menu.option(slot, name);
        }
        if (names.isEmpty()) {
            inventory.setItem(22, button(
                    Material.BARRIER,
                    kind == Menu.Kind.TELEPORT_HOMES ? "No homes set" : "No warps available",
                    kind == Menu.Kind.TELEPORT_HOMES
                            ? "Create one with /sethome <name>."
                            : "An administrator can create public warps."
            ));
        }
        MenuItems.paginate(inventory, page, names.size(), true);
        MenuItems.show(plugin, player, inventory);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Menu menu)
                || !(event.getWhoClicked() instanceof Player player)
                || !isTeleportMenu(menu.kind())) {
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getInventory()) {
            return;
        }
        int slot = event.getSlot();
        if (slot == MenuItems.backSlot(event.getInventory().getSize())) {
            player.closeInventory();
            Screens.home(player);
        } else if (slot == PREVIOUS_SLOT) {
            reopen(player, menu, menu.page() - 1);
        } else if (slot == NEXT_SLOT) {
            reopen(player, menu, menu.page() + 1);
        } else {
            menu.option(slot).ifPresent(name -> {
                player.closeInventory();
                String command = menu.kind() == Menu.Kind.TELEPORT_HOMES ? "home " : "warp ";
                player.performCommand("essentials:" + command + name);
            });
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Menu menu
                && isTeleportMenu(menu.kind())) {
            event.setCancelled(true);
        }
    }

    private void reopen(Player player, Menu menu, int page) {
        if (menu.kind() == Menu.Kind.TELEPORT_HOMES) {
            openHomes(player, page);
        } else {
            openWarps(player, page);
        }
    }

    /** The dialog lists the same warps this menu does. */
    List<String> warpNamesOf() {
        return warpNames();
    }

    private List<String> warpNames() {
        File[] files = warpsDirectory.listFiles((directory, name) ->
                name.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (files == null) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (File file : files) {
            String fileName = file.getName();
            String name = fileName.substring(0, fileName.length() - 4);
            if (SAFE_NAME.matcher(name).matches()) {
                names.add(name);
            }
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(names);
    }

    /** The dialog screen shows the same list this menu does. */
    List<String> homeNamesOf(Player player) {
        return homeNames(player);
    }

    private List<String> homeNames(Player player) {
        File file = new File(userdataDirectory, player.getUniqueId() + ".yml");
        if (!file.isFile()) {
            return List.of();
        }
        ConfigurationSection homes = YamlConfiguration.loadConfiguration(file)
                .getConfigurationSection("homes");
        if (homes == null) {
            return List.of();
        }
        return homes.getKeys(false).stream()
                .filter(name -> SAFE_NAME.matcher(name).matches())
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    static CommandRequest request(String message) {
        if (message == null || message.length() < 2 || message.charAt(0) != '/') {
            return null;
        }
        String[] parts = message.substring(1).strip().split(" +", 2);
        String label = parts[0].toLowerCase(Locale.ROOT);
        int namespace = label.indexOf(':');
        if (namespace >= 0) {
            label = label.substring(namespace + 1);
        }
        return label.isBlank() ? null : new CommandRequest(label, parts.length > 1);
    }

    private static boolean isTeleportMenu(Menu.Kind kind) {
        return kind == Menu.Kind.TELEPORT_WARPS || kind == Menu.Kind.TELEPORT_HOMES;
    }

    record CommandRequest(String label, boolean hasArguments) {
    }
}
