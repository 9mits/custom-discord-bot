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
    /** Toggle panes, laid out in a row. */
    private static final int CLAN_TAGS_SLOT = 11;
    private static final int DISCORD_CHAT_SLOT = 13;
    private static final int DISCORD_NAME_SLOT = 15;
    private static final int AUTO_SELL_SLOT = 17;
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

    /**
     * Every toggle a player controls, in one place.
     *
     * <p>Discord name visibility lives in a different store from the other two and
     * has its own command, but a player does not care where a preference is kept —
     * plugin.yml has always advertised it as a {@code /settings} option.
     */
    void openSettings(Player player) {
        Inventory inventory = create(Menu.Kind.SETTINGS, 1, SETTINGS_SIZE, "Your Settings");
        inventory.setItem(CLAN_TAGS_SLOT, toggle(
                PlayerSettingsStore.Setting.CLAN_TAGS,
                settings.isEnabled(player.getUniqueId(), PlayerSettingsStore.Setting.CLAN_TAGS)
        ));
        inventory.setItem(DISCORD_CHAT_SLOT, toggle(
                PlayerSettingsStore.Setting.DISCORD_CHAT,
                settings.isEnabled(player.getUniqueId(), PlayerSettingsStore.Setting.DISCORD_CHAT)
        ));
        inventory.setItem(AUTO_SELL_SLOT, toggle(
                PlayerSettingsStore.Setting.AUTO_SELL,
                settings.isEnabled(player.getUniqueId(), PlayerSettingsStore.Setting.AUTO_SELL)
        ));
        inventory.setItem(DISCORD_NAME_SLOT, pane(
                identities.isVisible(player.getUniqueId()),
                "Discord name",
                "Show your linked Discord name to other players."
        ));
        MenuItems.show(plugin, player, inventory);
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
            case SETTINGS -> flip(player, event.getSlot());
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

    private void flip(Player player, int slot) {
        switch (slot) {
            case AUTO_SELL_SLOT -> settings.toggle(
                    player.getUniqueId(), PlayerSettingsStore.Setting.AUTO_SELL);
            case CLAN_TAGS_SLOT -> settings.toggle(
                    player.getUniqueId(), PlayerSettingsStore.Setting.CLAN_TAGS);
            case DISCORD_CHAT_SLOT -> settings.toggle(
                    player.getUniqueId(), PlayerSettingsStore.Setting.DISCORD_CHAT);
            case DISCORD_NAME_SLOT -> {
                identities.toggleVisibility(player.getUniqueId());
                // Nametags and the player list carry the name, so they have to be
                // redrawn for everyone rather than just for this player.
                plugin.refreshClans();
            }
            default -> {
                return;
            }
        }
        openSettings(player);
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Menu menu && isPlayerMenu(menu.kind())) {
            event.setCancelled(true);
        }
    }

    private static boolean isPlayerMenu(Menu.Kind kind) {
        return kind == Menu.Kind.SETTINGS
                || kind == Menu.Kind.WHITELIST
                || kind == Menu.Kind.PERKS;
    }

    private Inventory create(Menu.Kind kind, int page, int size, String title) {
        Menu menu = new Menu(kind, null, page, null);
        Inventory inventory = Bukkit.createInventory(menu, size, Component.text(title, ORANGE));
        menu.attach(inventory);
        return inventory;
    }

    static Component prefix() {
        return Component.text("MGX » ", ORANGE, TextDecoration.BOLD);
    }

    static void error(Player player, String message) {
        player.sendMessage(prefix().append(Component.text(message, NamedTextColor.RED)));
    }
}
