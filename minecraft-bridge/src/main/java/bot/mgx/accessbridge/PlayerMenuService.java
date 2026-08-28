package bot.mgx.accessbridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;

import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

import static bot.mgx.accessbridge.MenuItems.BOARD_SIZE;
import static bot.mgx.accessbridge.MenuItems.NEXT_SLOT;
import static bot.mgx.accessbridge.MenuItems.ORANGE;
import static bot.mgx.accessbridge.MenuItems.PER_PAGE;
import static bot.mgx.accessbridge.MenuItems.PREVIOUS_SLOT;
import static bot.mgx.accessbridge.MenuItems.button;
import static bot.mgx.accessbridge.MenuItems.head;

/**
 * The screens that belong to a player rather than to a clan: their own toggles, the
 * whitelist directory, and the Discord level ladder.
 *
 * <p>Kept apart from {@link ClanMenuService} so neither becomes a catch-all; both
 * draw through {@link MenuItems} so every screen looks the same.
 */
final class PlayerMenuService implements Listener {
    private static final int[] CATEGORY_SLOTS = {10, 11, 12, 13, 14, 15, 16};
    private static final int[] SETTING_SLOTS = {9, 10, 11, 12, 14, 15, 16, 17};
    private static final int BACK_SLOT = 22;
    private static final int SETTINGS_SIZE = 27;

    private final PlayerSettingsStore settings;
    private final DiscordIdentityService identities;
    private final WhitelistDirectory whitelist;
    private final MGXAccessBridge plugin;

    PlayerMenuService(
            MGXAccessBridge plugin,
            PlayerSettingsStore settings,
            DiscordIdentityService identities,
            WhitelistDirectory whitelist
    ) {
        this.plugin = plugin;
        this.settings = settings;
        this.identities = identities;
        this.whitelist = whitelist;
    }

    /** Category-first fallback for Bedrock and Java clients without dialog support. */
    void openSettings(Player player) {
        Inventory inventory = create(Menu.Kind.SETTINGS, 1, SETTINGS_SIZE, "Your Settings");
        PlayerSettingsStore.Category[] categories = PlayerSettingsStore.Category.values();
        for (int index = 0; index < categories.length; index++) {
            PlayerSettingsStore.Category category = categories[index];
            inventory.setItem(CATEGORY_SLOTS[index], button(
                    categoryIcon(category), category.label(),
                    category.description()
            ));
        }
        MenuItems.show(plugin, player, inventory);
    }

    void openSettingsCategory(Player player, PlayerSettingsStore.Category category) {
        Inventory inventory = create(
                Menu.Kind.SETTINGS_CATEGORY,
                category.ordinal() + 1,
                SETTINGS_SIZE,
                category.label() + " Settings"
        );
        List<PlayerSettingsStore.Setting> categorySettings = category.settings();
        for (int index = 0; index < categorySettings.size() && index < SETTING_SLOTS.length; index++) {
            PlayerSettingsStore.Setting setting = categorySettings.get(index);
            inventory.setItem(SETTING_SLOTS[index], toggle(
                    setting, settings.isEnabled(player.getUniqueId(), setting)
            ));
        }
        if (category == PlayerSettingsStore.Category.VISUALS
                && categorySettings.size() < SETTING_SLOTS.length) {
            int volume = settings.musicVolume(player.getUniqueId());
            inventory.setItem(SETTING_SLOTS[categorySettings.size()], button(
                    volume == 0 ? Material.GRAY_DYE : Material.NOTE_BLOCK,
                    "Synced music volume: " + volume + "%",
                    "Controls music-synced cosmetic tracks.",
                    "Uses the master channel, so Minecraft Music may stay at 0%.",
                    "Click to cycle: 100 / 75 / 50 / 25 / 0."
            ));
        }
        if (category == PlayerSettingsStore.Category.PRIVACY) {
            inventory.setItem(SETTING_SLOTS[0], pane(
                    identities.isVisible(player.getUniqueId()),
                    "Discord name",
                    "Show your linked Discord name to other players."
            ));
        }
        inventory.setItem(BACK_SLOT, button(Material.ARROW, "Back", "Return to all settings."));
        MenuItems.show(plugin, player, inventory);
    }

    boolean discordNameVisible(java.util.UUID playerId) {
        return identities.isVisible(playerId);
    }

    void toggleDiscordName(java.util.UUID playerId) {
        identities.toggleVisibility(playerId);
        // Nametags and the player list carry the name, so redraw them immediately.
        plugin.refreshClans();
    }

    /** Everyone with access, their edition, and the Discord name they chose to show. */
    void openWhitelist(Player player, int page) {
        List<WhitelistDirectory.Entry> entries = whitelist.entries();
        Inventory inventory = create(
                Menu.Kind.WHITELIST, page, BOARD_SIZE,
                MenuItems.pagedTitle("Whitelist", page, entries.size())
        );
        int first = MenuPaging.firstIndex(page, entries.size(), PER_PAGE);
        int last = MenuPaging.lastIndex(page, entries.size(), PER_PAGE);
        for (int index = first; index < last; index++) {
            WhitelistDirectory.Entry entry = entries.get(index);
            List<String> lore = new ArrayList<>();
            lore.add(entry.edition().isBlank() ? "Java" : entry.edition());
            if (entry.discordUsername() != null && !entry.discordUsername().isBlank()) {
                lore.add("@" + entry.discordUsername());
            }
            Player online = Bukkit.getPlayerExact(entry.username());
            lore.add(online != null ? "Online now" : "Offline");
            inventory.setItem(index - first, head(
                    online == null ? null : online.getUniqueId(), entry.username(), lore
            ));
        }
        if (entries.isEmpty()) {
            inventory.setItem(22, button(Material.BARRIER, "Nobody yet",
                    "The directory has not synced from Discord."));
        }
        MenuItems.paginate(inventory, page, entries.size(), false);
        MenuItems.show(plugin, player, inventory);
    }

    /**
     * The Discord level ladder.
     *
     * <p>Figures come from {@link PlayerPerkService}, which is what actually applies
     * them — the old chat version restated them by hand and had drifted.
     */
    void openPerks(Player player) {
        Inventory inventory = create(Menu.Kind.PERKS, 1, SETTINGS_SIZE, "Level Perks");
        int[] milestones = {5, 10, 20, 30, 40, 50};
        int[] hearts = {1, 2, 3, 4, 5, 5};
        for (int index = 0; index < milestones.length; index++) {
            List<String> lore = new ArrayList<>();
            lore.add("+" + hearts[index] + (hearts[index] == 1 ? " bonus heart" : " total bonus hearts"));
            if (milestones[index] == 50) {
                lore.add("+" + Math.round(PlayerPerkService.ELITE_DAMAGE_BONUS * 100)
                        + "% direct combat damage");
            }
            inventory.setItem(10 + index, button(
                    milestones[index] == 50 ? Material.NETHER_STAR : Material.RED_DYE,
                    "Level " + milestones[index], lore
            ));
        }
        inventory.setItem(22, button(Material.BOOK, "How to earn them",
                "Chat in the Mysterious SMP X Discord.",
                "Roles sync to Minecraft automatically."));
        MenuItems.show(plugin, player, inventory);
    }

    private org.bukkit.inventory.ItemStack toggle(PlayerSettingsStore.Setting setting, boolean on) {
        return pane(on, setting.label(), setting.description());
    }

    private static org.bukkit.inventory.ItemStack pane(boolean on, String label, String description) {
        return button(
                on ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE,
                label,
                List.of(description, "", on ? "ON — click to turn off" : "OFF — click to turn on")
        );
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Menu menu)
                || !(event.getWhoClicked() instanceof Player player)
                || !isPlayerMenu(menu.kind())) {
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getInventory()) {
            return;
        }
        switch (menu.kind()) {
            case SETTINGS -> openCategory(player, event.getSlot());
            case SETTINGS_CATEGORY -> flip(
                    player, categoryFromPage(menu.page()), event.getSlot());
            case WHITELIST -> {
                if (event.getSlot() == PREVIOUS_SLOT) {
                    openWhitelist(player, menu.page() - 1);
                } else if (event.getSlot() == NEXT_SLOT) {
                    openWhitelist(player, menu.page() + 1);
                }
            }
            default -> { }
        }
    }

    private void openCategory(Player player, int slot) {
        for (int index = 0; index < CATEGORY_SLOTS.length; index++) {
            if (slot == CATEGORY_SLOTS[index]) {
                openSettingsCategory(player, PlayerSettingsStore.Category.values()[index]);
                return;
            }
        }
    }

    private void flip(Player player, PlayerSettingsStore.Category category, int slot) {
        if (category == null) {
            openSettings(player);
            return;
        }
        if (slot == BACK_SLOT) {
            openSettings(player);
            return;
        }
        try {
            if (category == PlayerSettingsStore.Category.PRIVACY && slot == SETTING_SLOTS[0]) {
                toggleDiscordName(player.getUniqueId());
            } else {
                List<PlayerSettingsStore.Setting> categorySettings = category.settings();
                int settingIndex = indexOf(SETTING_SLOTS, slot);
                if (category == PlayerSettingsStore.Category.VISUALS
                        && settingIndex == categorySettings.size()) {
                    settings.cycleMusicVolume(player.getUniqueId());
                    openSettingsCategory(player, category);
                    return;
                }
                if (settingIndex < 0 || settingIndex >= categorySettings.size()) {
                    return;
                }
                settings.toggle(player.getUniqueId(), categorySettings.get(settingIndex));
                // Sidebar sections, nametags, and the player list should react on
                // this click rather than on the next repeating refresh.
                plugin.refreshClans();
            }
            openSettingsCategory(player, category);
        } catch (IllegalStateException exception) {
            error(player, exception.getMessage());
        } catch (UncheckedIOException exception) {
            plugin.getLogger().warning("Could not save a player setting: " + exception.getMessage());
            error(player, "That setting could not be saved. Please try again.");
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Menu menu && isPlayerMenu(menu.kind())) {
            event.setCancelled(true);
        }
    }

    private static boolean isPlayerMenu(Menu.Kind kind) {
        return kind == Menu.Kind.SETTINGS
                || kind == Menu.Kind.SETTINGS_CATEGORY
                || kind == Menu.Kind.WHITELIST
                || kind == Menu.Kind.PERKS;
    }

    private static PlayerSettingsStore.Category categoryFromPage(int page) {
        int index = page - 1;
        PlayerSettingsStore.Category[] categories = PlayerSettingsStore.Category.values();
        return index >= 0 && index < categories.length ? categories[index] : null;
    }

    private static int indexOf(int[] values, int wanted) {
        for (int index = 0; index < values.length; index++) {
            if (values[index] == wanted) {
                return index;
            }
        }
        return -1;
    }

    private static Material categoryIcon(PlayerSettingsStore.Category category) {
        return switch (category) {
            case CHAT -> Material.WRITABLE_BOOK;
            case NOTIFICATIONS -> Material.BELL;
            case PVP -> Material.IRON_SWORD;
            case VISUALS -> Material.AMETHYST_SHARD;
            case PRIVACY -> Material.SHIELD;
            case SCOREBOARD -> Material.MAP;
            case GENERAL -> Material.COMPARATOR;
        };
    }

    private Inventory create(Menu.Kind kind, int page, int size, String title) {
        Menu menu = new Menu(kind, null, page, null);
        Inventory inventory = Bukkit.createInventory(menu, size, Component.text(title, ORANGE));
        menu.attach(inventory);
        return inventory;
    }

    static Component prefix() {
        return Component.text("SERVER » ", ORANGE, TextDecoration.BOLD);
    }

    static void error(Player player, String message) {
        player.sendMessage(prefix().append(Component.text(message, NamedTextColor.RED)));
    }
}
