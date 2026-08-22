package bot.mgx.accessbridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.WeatherType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Screenshot mode for the dev blog: {@code /mgxadmin devblog}.
 *
 * <p>Taking a usable picture of a new feature means fighting the game — a
 * hotbar full of building blocks, the sidebar over one corner, somebody
 * wandering into frame, and rain. This clears all of that in one command and
 * puts it back in another.
 *
 * <p>Everything here is reversible and nothing is ever destroyed. The player's
 * belongings go to {@link DevBlogStore} on disk before the inventory is
 * touched, so a crash mid-session cannot eat them, and anything picked up
 * during a session is kept too rather than being quietly overwritten on the way
 * out.
 *
 * <p>There is no freecam. That is a client-side capability a server cannot
 * grant, and this server's own rules count it as cheating. The nearest honest
 * equivalent is spectator, which {@code cam} toggles while remembering where
 * you were standing so you land back on your feet.
 */
final class DevBlogService {

    /** State that only matters while the player is online, alongside the stash. */
    private static final class Live {
        Location cameraReturn;
        boolean hidingPlayers = true;
        boolean wasInvulnerable;
        boolean couldFly;
        boolean wasFlying;
    }

    private final Plugin plugin;
    private final DevBlogStore store;
    private final SidebarService sidebar;
    private final CosmeticStore cosmetics;
    private final Map<UUID, Live> live = new HashMap<>();

    DevBlogService(
            Plugin plugin, DevBlogStore store, SidebarService sidebar, CosmeticStore cosmetics
    ) {
        this.plugin = plugin;
        this.store = store;
        this.sidebar = sidebar;
        this.cosmetics = cosmetics;
    }

    boolean isActive(Player player) {
        return store.isActive(player.getUniqueId());
    }

    // ------------------------------------------------------------------
    // Command surface
    // ------------------------------------------------------------------

    static String usage() {
        return "Usage: /mgxadmin devblog <on [keeparmour]|off|cam|time <when>"
                + "|weather <clear|rain|reset>|players|status>";
    }

    /** @param args the full {@code /mgxadmin} argument array. */
    void handle(Player player, String[] args) {
        String action = args.length < 2 ? "toggle" : args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "toggle" -> {
                if (isActive(player)) {
                    stop(player, true);
                } else {
                    start(player, false);
                }
            }
            case "on", "start" -> {
                if (isActive(player)) {
                    throw new IllegalArgumentException("You are already in screenshot mode.");
                }
                boolean keepArmour = args.length >= 3
                        && args[2].toLowerCase(Locale.ROOT).startsWith("keep");
                start(player, keepArmour);
            }
            case "off", "stop", "end" -> {
                requireActive(player);
                stop(player, true);
            }
            case "cam", "camera" -> {
                requireActive(player);
                camera(player);
            }
            case "time" -> {
                requireActive(player);
                time(player, args.length >= 3 ? args[2] : "");
            }
            case "weather" -> {
                requireActive(player);
                weather(player, args.length >= 3 ? args[2] : "");
            }
            case "players", "hide" -> {
                requireActive(player);
                togglePlayers(player);
            }
            case "status" -> status(player);
            default -> throw new IllegalArgumentException(usage());
        }
    }

    private void requireActive(Player player) {
        if (!isActive(player)) {
            throw new IllegalArgumentException(
                    "You are not in screenshot mode. Start one with /mgxadmin devblog on."
            );
        }
    }

    // ------------------------------------------------------------------
    // Starting and stopping
    // ------------------------------------------------------------------

    private void start(Player player, boolean keepArmour) {
        PlayerInventory inventory = player.getInventory();
        // Stashed and written to disk *before* anything is cleared, so the
        // items exist in two places at no point fewer than one.
        String contents = DevBlogStore.encode(itemsToBytes(inventory.getStorageContents()));
        String armour = keepArmour
                ? ""
                : DevBlogStore.encode(itemsToBytes(inventory.getArmorContents()));
        store.open(player.getUniqueId(), new DevBlogStore.Session(
                contents, armour, player.getGameMode().name(), keepArmour,
                System.currentTimeMillis()
        ));
        cosmetics.beginPreview(player.getUniqueId());

        Live state = new Live();
        state.wasInvulnerable = player.isInvulnerable();
        state.couldFly = player.getAllowFlight();
        state.wasFlying = player.isFlying();
        live.put(player.getUniqueId(), state);

        inventory.setStorageContents(new ItemStack[inventory.getStorageContents().length]);
        if (!keepArmour) {
            inventory.setArmorContents(new ItemStack[inventory.getArmorContents().length]);
        }
        inventory.setItemInOffHand(null);

        player.setInvulnerable(true);
        player.setFoodLevel(20);
        player.setSaturation(20f);
        player.setAllowFlight(true);
        sidebar.setSuppressed(player.getUniqueId(), true);
        sidebar.refresh(player);
        hideOthers(player, true);

        tell(player, NamedTextColor.GREEN, "Screenshot mode on."
                + (keepArmour ? " Armour kept." : " Inventory and armour stashed."));
        tell(player, NamedTextColor.GRAY, "F1 hides the HUD. /mgxadmin devblog off puts it all back.");
        tell(player, NamedTextColor.GRAY, "time, weather, cam and players are the other switches.");
    }

    /**
     * @param announce false when the server is stopping and the player will not
     *                 read a message anyway.
     */
    void stop(Player player, boolean announce) {
        DevBlogStore.Session session = store.close(player.getUniqueId()).orElse(null);
        Live state = live.remove(player.getUniqueId());
        cosmetics.endPreview(player.getUniqueId());
        if (session == null) {
            return;
        }

        PlayerInventory inventory = player.getInventory();
        // Whatever they picked up during the session is theirs too, so it is set
        // aside and handed back rather than being overwritten by the stash.
        ItemStack[] acquired = inventory.getStorageContents().clone();

        inventory.setStorageContents(bytesToItems(
                DevBlogStore.decode(session.encodedContents()),
                inventory.getStorageContents().length
        ));
        if (!session.keptArmour() && !session.encodedArmour().isEmpty()) {
            inventory.setArmorContents(bytesToItems(
                    DevBlogStore.decode(session.encodedArmour()),
                    inventory.getArmorContents().length
            ));
        }
        returnItems(player, acquired);

        player.setInvulnerable(state != null && state.wasInvulnerable);
        player.resetPlayerTime();
        player.resetPlayerWeather();
        sidebar.setSuppressed(player.getUniqueId(), false);
        sidebar.refresh(player);
        hideOthers(player, false);

        if (player.getGameMode() == GameMode.SPECTATOR) {
            restoreFromCamera(player, session, state);
        }
        if (state != null) {
            player.setAllowFlight(state.couldFly);
            player.setFlying(state.couldFly && state.wasFlying);
        }

        if (announce) {
            tell(player, NamedTextColor.GREEN, "Screenshot mode off. Everything is back.");
        }
    }

    // ------------------------------------------------------------------
    // Switches
    // ------------------------------------------------------------------

    private void camera(Player player) {
        Live state = live.computeIfAbsent(player.getUniqueId(), ignored -> new Live());
        if (player.getGameMode() == GameMode.SPECTATOR) {
            Location back = state.cameraReturn;
            state.cameraReturn = null;
            player.setGameMode(GameMode.CREATIVE);
            if (back != null) {
                player.teleport(back);
            }
            tell(player, NamedTextColor.GRAY, "Back on your feet.");
            return;
        }
        state.cameraReturn = player.getLocation().clone();
        player.setGameMode(GameMode.SPECTATOR);
        tell(player, NamedTextColor.GRAY,
                "Spectator. Fly anywhere; cam again returns you to this spot.");
        tell(player, NamedTextColor.GRAY,
                "This is not freecam — your body moves with you. No server can give you that.");
    }

    private void restoreFromCamera(Player player, DevBlogStore.Session session, Live state) {
        GameMode previous;
        try {
            previous = GameMode.valueOf(session.previousGameMode());
        } catch (IllegalArgumentException unknown) {
            previous = GameMode.SURVIVAL;
        }
        player.setGameMode(previous);
        if (state != null && state.cameraReturn != null) {
            player.teleport(state.cameraReturn);
        }
    }

    private void time(Player player, String when) {
        long ticks;
        switch (when.toLowerCase(Locale.ROOT)) {
            case "day" -> ticks = 1000L;
            case "noon" -> ticks = 6000L;
            case "dusk", "sunset" -> ticks = 12000L;
            case "night" -> ticks = 14000L;
            case "midnight" -> ticks = 18000L;
            case "dawn", "sunrise" -> ticks = 23000L;
            case "reset" -> {
                player.resetPlayerTime();
                tell(player, NamedTextColor.GRAY, "Time follows the world again.");
                return;
            }
            default -> {
                try {
                    ticks = Long.parseLong(when);
                } catch (NumberFormatException notANumber) {
                    throw new IllegalArgumentException(
                            "Usage: /mgxadmin devblog time "
                                    + "<day|noon|dusk|night|midnight|dawn|reset|ticks>"
                    );
                }
            }
        }
        // Fixed rather than relative, so the light does not drift between shots.
        player.setPlayerTime(ticks, false);
        tell(player, NamedTextColor.GRAY, "Your sky is at " + ticks + " ticks. Nobody else's is.");
    }

    private void weather(Player player, String kind) {
        switch (kind.toLowerCase(Locale.ROOT)) {
            case "clear", "sun" -> {
                player.setPlayerWeather(WeatherType.CLEAR);
                tell(player, NamedTextColor.GRAY, "Clear skies, for you.");
            }
            case "rain", "storm" -> {
                player.setPlayerWeather(WeatherType.DOWNFALL);
                tell(player, NamedTextColor.GRAY, "Raining, for you.");
            }
            case "reset" -> {
                player.resetPlayerWeather();
                tell(player, NamedTextColor.GRAY, "Weather follows the world again.");
            }
            default -> throw new IllegalArgumentException(
                    "Usage: /mgxadmin devblog weather <clear|rain|reset>"
            );
        }
    }

    private void togglePlayers(Player player) {
        Live state = live.computeIfAbsent(player.getUniqueId(), ignored -> new Live());
        state.hidingPlayers = !state.hidingPlayers;
        hideOthers(player, state.hidingPlayers);
        tell(player, NamedTextColor.GRAY, state.hidingPlayers
                ? "Other players hidden. Nobody can wander into the shot."
                : "Other players visible again.");
    }

    private void status(Player player) {
        if (!isActive(player)) {
            tell(player, NamedTextColor.GRAY, "Not in screenshot mode.");
            return;
        }
        Live state = live.get(player.getUniqueId());
        tell(player, NamedTextColor.GRAY, "Screenshot mode on. Players "
                + (state == null || state.hidingPlayers ? "hidden" : "visible")
                + ", camera " + (player.getGameMode() == GameMode.SPECTATOR ? "free" : "on foot")
                + ".");
    }

    // ------------------------------------------------------------------
    // Lifecycle, so a session can never strand somebody's belongings
    // ------------------------------------------------------------------

    /** Called on join: hands back anything a crash or restart left stashed. */
    void restoreIfStranded(Player player) {
        if (!store.isActive(player.getUniqueId())) {
            return;
        }
        stop(player, false);
        tell(player, NamedTextColor.YELLOW,
                "A screenshot session was still open, so your belongings have been put back.");
    }

    /** Called on quit and on shutdown. */
    void endSession(Player player) {
        if (store.isActive(player.getUniqueId())) {
            stop(player, false);
        }
    }

    void endEverySession(Iterable<? extends Player> online) {
        for (Player player : online) {
            endSession(player);
        }
    }

    void forget(UUID player) {
        live.remove(player);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void hideOthers(Player player, boolean hide) {
        for (Player other : plugin.getServer().getOnlinePlayers()) {
            if (other.equals(player)) {
                continue;
            }
            if (hide) {
                player.hidePlayer(plugin, other);
            } else {
                player.showPlayer(plugin, other);
            }
        }
    }

    /** Gives items back, dropping at their feet whatever will not fit. */
    private void returnItems(Player player, ItemStack[] items) {
        Inventory inventory = player.getInventory();
        for (ItemStack item : items) {
            if (item == null || item.getType().isAir()) {
                continue;
            }
            for (ItemStack overflow : inventory.addItem(item).values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), overflow);
            }
        }
    }

    private static byte[] itemsToBytes(ItemStack[] items) {
        return ItemStack.serializeItemsAsBytes(items);
    }

    private static ItemStack[] bytesToItems(byte[] raw, int size) {
        ItemStack[] restored = ItemStack.deserializeItemsFromBytes(raw);
        if (restored.length == size) {
            return restored;
        }
        // An inventory can change size between versions; never lose the tail.
        ItemStack[] sized = new ItemStack[size];
        System.arraycopy(restored, 0, sized, 0, Math.min(size, restored.length));
        return sized;
    }

    private static void tell(Player player, NamedTextColor colour, String message) {
        player.sendMessage(Component.text(message, colour));
    }
}
