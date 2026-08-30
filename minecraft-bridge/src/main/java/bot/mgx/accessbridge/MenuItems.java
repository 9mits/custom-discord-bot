package bot.mgx.accessbridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Shared drawing for the menu screens, so every one of them looks the same. */
final class MenuItems {
    static final TextColor ORANGE = TextColor.color(0xFF9900);
    /** A 54-slot board keeps the bottom row for navigation. */
    static final int BOARD_SIZE = 54;
    static final int PER_PAGE = 45;
    static final int PREVIOUS_SLOT = 45;
    static final int NEXT_SLOT = 53;

    private MenuItems() {
    }

    /**
     * Shows a screen to a player on the next tick.
     *
     * <p>Bukkit forbids {@code openInventory} from inside an {@code InventoryClickEvent}
     * handler, and Bedrock is where ignoring it shows. Geyser is still settling the
     * click transaction when the replacement container arrives, so it drops the open:
     * the board freezes for a Bedrock player while a Java player sees it change, which
     * reads as clicks doing nothing at all. Every screen goes through here, including
     * the ones opened straight from a command, so there is a single path to get right.
     */
    static void show(Plugin plugin, Player player, Inventory inventory) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                player.openInventory(inventory);
            }
        });
    }

    /** Where Back sits on a board of this size. The arithmetic lives in {@link MenuPaging}. */
    static int backSlot(int size) {
        return MenuPaging.backSlot(size);
    }

    /**
     * Strips vanilla's own tooltip from a menu tile.
     *
     * <p>These items are buttons, not gear. A pickaxe used as an icon otherwise carries
     * "When in Main Hand: 5 Attack Damage" under the label, which reads as part of the
     * screen and is never what the tile is saying.
     */
    static void asButton(ItemMeta meta) {
        meta.addItemFlags(ItemFlag.values());
        // Paper only omits the attribute block when the item has an explicit empty set;
        // the flag alone still leaves a gap on some clients.
        meta.setAttributeModifiers(com.google.common.collect.ImmutableMultimap.of());
    }

    static ItemStack button(Material material, String name, String... lore) {
        return button(material, name, List.of(lore));
    }

    static ItemStack button(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(title(name));
            meta.lore(loreOf(lore));
            asButton(meta);
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * A player's head, named for them.
     *
     * <p>Resolved against Paper's profile cache. Everyone shown on these screens has
     * joined the server, so the profile is already cached and nothing blocks the main
     * thread; an uncached one simply draws the default head.
     */
    static ItemStack head(UUID playerId, String name, List<String> lore) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        if (item.getItemMeta() instanceof SkullMeta meta) {
            if (playerId != null) {
                meta.setOwningPlayer(Bukkit.getOfflinePlayer(playerId));
            }
            meta.displayName(title(name));
            meta.lore(loreOf(lore));
            asButton(meta);
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * A button whose lore the caller has already drawn.
     *
     * <p>{@link #button(Material, String, List)} greys every line, which is right for
     * the explanatory lore that makes up most of these screens and wrong for a line
     * that has to be noticed — a countdown reading as body text is a countdown nobody
     * reads.
     */
    static ItemStack detailed(Material material, String name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(title(name));
            meta.lore(lore.stream()
                    .map(line -> line.decoration(TextDecoration.ITALIC, false))
                    .toList());
            asButton(meta);
            item.setItemMeta(meta);
        }
        return item;
    }

    /** Draws the navigation row, showing an arrow only where there is somewhere to go. */
    static void paginate(Inventory inventory, int page, int total, boolean withBack) {
        if (MenuPaging.hasPrevious(page, total, PER_PAGE)) {
            inventory.setItem(PREVIOUS_SLOT, button(Material.ARROW, "Previous page"));
        }
        if (MenuPaging.hasNext(page, total, PER_PAGE)) {
            inventory.setItem(NEXT_SLOT, button(Material.ARROW, "Next page"));
        }
        if (withBack) {
            back(inventory);
        }
    }

    /** Draws Back in the one place every screen keeps it. */
    static void back(Inventory inventory) {
        inventory.setItem(backSlot(inventory.getSize()), button(Material.BARRIER, "Back"));
    }

    static String pagedTitle(String label, int page, int total) {
        int pages = MenuPaging.pageCount(total, PER_PAGE);
        return pages > 1
                ? label + "  " + MenuPaging.clampPage(page, total, PER_PAGE) + "/" + pages
                : label;
    }

    private static Component title(String name) {
        return Component.text(name, ORANGE, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false);
    }

    private static List<Component> loreOf(List<String> lore) {
        List<Component> lines = new ArrayList<>();
        for (String line : lore) {
            lines.add(Component.text(line, NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false));
        }
        return lines;
    }

}
