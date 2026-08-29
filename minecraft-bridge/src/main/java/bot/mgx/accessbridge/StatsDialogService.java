package bot.mgx.accessbridge;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * The stats screens: a short dialog card of the headline numbers, and a chest board
 * behind {@code View Full Profile} carrying everything else.
 *
 * <p>The split follows what each surface is good at. A dialog reads well as a few
 * labelled lines and nothing more; the full breakdown is a grid of icons, which is
 * what an inventory already is, and it is also the only version Bedrock can see.
 */
final class StatsDialogService implements CommandExecutor {
    private static final ClickCallback.Options CALLBACK_OPTIONS = ClickCallback.Options.builder()
            .uses(ClickCallback.UNLIMITED_USES)
            .lifetime(Duration.ofMinutes(10))
            .build();
    private static final int PROFILE_SIZE = 45;

    private final MGXAccessBridge plugin;
    private final EconomyStore money;
    private final CrateItems crateItems;
    private final SettingsClientSupport clientSupport;

    StatsDialogService(
            MGXAccessBridge plugin,
            EconomyStore money,
            CrateItems crateItems,
            SettingsClientSupport clientSupport
    ) {
        this.plugin = plugin;
        this.money = money;
        this.crateItems = crateItems;
        this.clientSupport = clientSupport;
    }

    @Override
    public boolean onCommand(
            CommandSender sender, Command command, String label, String[] args
    ) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Stats are available to players only.");
            return true;
        }
        args = CommandArgs.withoutEchoedSender(sender.getName(), args);
        if (args.length >= 1) {
            Player target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                PlayerMenuService.error(player, "No player named " + args[0] + " is online.");
                return true;
            }
            openCard(player, target);
            return true;
        }
        openPicker(player);
        return true;
    }

    /** The player list, mirroring the teleport picker so the two read the same. */
    void openPicker(Player viewer) {
        if (!clientSupport.supportsDialogs(viewer)) {
            openProfile(viewer, viewer);
            return;
        }
        List<ActionButton> buttons = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (VerificationLobbyService.isLobbyWorld(online.getWorld())) {
                continue;
            }
            UUID id = online.getUniqueId();
            buttons.add(ActionButton.builder(Component.empty()
                            .append(MenuText.head(id))
                            .append(Component.text(" " + online.getName(), NamedTextColor.WHITE)))
                    .tooltip(id.equals(viewer.getUniqueId())
                            ? Component.text("You", MenuText.LABEL)
                            : Component.text("View their stats", MenuText.LABEL))
                    .width(150)
                    .action(callback((response, audience) -> {
                        Player target = Bukkit.getPlayer(id);
                        if (target == null) {
                            PlayerMenuService.error(audience, "They went offline.");
                            return;
                        }
                        openCard(audience, target);
                    }))
                    .build());
        }
        show(viewer, "Player Stats", "Click a player to view their stats", buttons, 2);
    }

    /** The four headline numbers, with the full board one click away. */
    void openCard(Player viewer, Player subject) {
        if (!clientSupport.supportsDialogs(viewer)) {
            openProfile(viewer, subject);
            return;
        }
        UUID id = subject.getUniqueId();
        List<DialogBody> body = List.of(
                DialogBody.plainMessage(MenuText.head(id), 400),
                DialogBody.plainMessage(Component.empty(), 400),
                DialogBody.plainMessage(MenuText.stat(
                        "Money", Material.EMERALD, EconomyFormat.dollars(money.balance(id))
                ), 400),
                DialogBody.plainMessage(MenuText.stat(
                        "Kills", compact(statistic(subject, Statistic.PLAYER_KILLS))
                ), 400),
                DialogBody.plainMessage(MenuText.stat(
                        "Deaths", compact(statistic(subject, Statistic.DEATHS))
                ), 400),
                DialogBody.plainMessage(MenuText.stat(
                        "Playtime", playtime(statistic(subject, Statistic.PLAY_ONE_MINUTE))
                ), 400)
        );
        List<ActionButton> buttons = List.of(
                ActionButton.builder(Component.text("View Full Profile", NamedTextColor.WHITE))
                        .tooltip(Component.text("Every number we keep.", MenuText.LABEL))
                        .width(310)
                        .action(callback((response, audience) -> {
                            Player target = Bukkit.getPlayer(id);
                            openProfile(audience, target == null ? audience : target);
                        }))
                        .build(),
                ActionButton.builder(Component.text("Back", MenuText.LABEL))
                        .width(310)
                        .action(callback((response, audience) -> openPicker(audience)))
                        .build()
        );
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(MenuText.title(subject.getName()))
                        .body(body)
                        .afterAction(DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE)
                        .build())
                .type(DialogType.multiAction(buttons).columns(1).build()));
        viewer.showDialog(dialog);
    }

    /**
     * The full board. Every tile is an icon whose tooltip names the stat in green and
     * carries the figure underneath, which is the one place a long number reads
     * comfortably.
     */
    void openProfile(Player viewer, Player subject) {
        Menu menu = new Menu(Menu.Kind.PLAYER_PROFILE, subject.getUniqueId(), 1, null);
        Inventory inventory = Bukkit.createInventory(
                menu, PROFILE_SIZE,
                Component.text(subject.getName().toUpperCase(Locale.ROOT) + " Stats", MenuText.ORANGE)
        );
        menu.attach(inventory);
        UUID id = subject.getUniqueId();
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21};
        List<ItemStack> tiles = List.of(
                tile(Material.EMERALD, "MONEY", EconomyFormat.dollars(money.balance(id))),
                tile(Material.AMETHYST_SHARD, "SHARDS", compact(crateItems.countShards(subject))),
                tile(Material.DIAMOND_SWORD, "PLAYER KILLS",
                        compact(statistic(subject, Statistic.PLAYER_KILLS))),
                tile(Material.ROTTEN_FLESH, "MOBS KILLED",
                        compact(statistic(subject, Statistic.MOB_KILLS))),
                tile(Material.SKELETON_SKULL, "DEATHS",
                        compact(statistic(subject, Statistic.DEATHS))),
                tile(Material.CLOCK, "PLAYTIME",
                        playtime(statistic(subject, Statistic.PLAY_ONE_MINUTE))),
                tile(Material.DIAMOND_PICKAXE, "BLOCKS BROKEN", compact(blocksBroken(subject))),
                tile(Material.BRICKS, "BLOCKS PLACED", compact(blocksPlaced(subject))),
                tile(Material.LEATHER_BOOTS, "DISTANCE WALKED",
                        compact(statistic(subject, Statistic.WALK_ONE_CM) / 100L) + " blocks"),
                tile(Material.CHEST, "CRATES OPENED",
                        compact(statistic(subject, Statistic.CHEST_OPENED)))
        );
        for (int index = 0; index < tiles.size() && index < slots.length; index++) {
            inventory.setItem(slots[index], tiles.get(index));
        }
        inventory.setItem(40, MenuItems.button(Material.BARRIER, "Close"));
        MenuItems.show(plugin, viewer, inventory);
    }

    /** Green name, quiet figure — the tooltip does the labelling the grid cannot. */
    private static ItemStack tile(Material material, String name, String value) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(name, MenuText.VALUE, TextDecoration.BOLD)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(Component.text(value, NamedTextColor.WHITE)
                    .decoration(TextDecoration.ITALIC, false)));
            item.setItemMeta(meta);
        }
        return item;
    }

    private static long statistic(Player player, Statistic statistic) {
        try {
            return player.getStatistic(statistic);
        } catch (IllegalArgumentException ignored) {
            // A statistic that needs a material or entity argument is not a total.
            return 0L;
        }
    }

    /**
     * The block materials each per-block statistic actually accepts, worked out once.
     *
     * <p>Probing every material on each open threw and caught hundreds of exceptions
     * on the main thread, which is an expensive way to learn something that never
     * changes while the server is running.
     */
    private static volatile List<Material> mined = List.of();
    private static volatile List<Material> placed = List.of();

    /**
     * Learns which materials each statistic accepts, using the first player to ask.
     * Bukkit only answers that question by throwing, and the answer never changes
     * while the server runs, so it is worked out once instead of on every open.
     */
    private static void learnSupported(Player probe) {
        if (!mined.isEmpty()) {
            return;
        }
        mined = supported(probe, Statistic.MINE_BLOCK);
        placed = supported(probe, Statistic.USE_ITEM);
    }

    private static List<Material> supported(Player probe, Statistic statistic) {
        List<Material> materials = new ArrayList<>();
        for (Material material : Material.values()) {
            if (!material.isBlock() || material.isLegacy()) {
                continue;
            }
            // Bukkit has no "is this valid" query, so one probe per material at
            // startup stands in for it and the result is reused from then on.
            try {
                probe.getStatistic(statistic, material);
                materials.add(material);
            } catch (IllegalArgumentException ignored) {
                // Not every block material is a statistic of this kind.
            }
        }
        return List.copyOf(materials);
    }

    private static long total(Player player, Statistic statistic, List<Material> materials) {
        long total = 0L;
        for (Material material : materials) {
            try {
                total += player.getStatistic(statistic, material);
            } catch (IllegalArgumentException ignored) {
                // A material the server accepted at startup but not for this player.
            }
        }
        return total;
    }

    /** Vanilla keeps mining per block, so the total is the sum of every material. */
    private static long blocksBroken(Player player) {
        learnSupported(player);
        return total(player, Statistic.MINE_BLOCK, mined);
    }

    /**
     * There is no vanilla "blocks placed" total. Using a block item is the closest
     * honest proxy, and is what the number has always meant on servers that show it.
     */
    private static long blocksPlaced(Player player) {
        learnSupported(player);
        return total(player, Statistic.USE_ITEM, placed);
    }

    static String compact(long value) {
        if (value < 1_000L) {
            return String.valueOf(value);
        }
        if (value < 1_000_000L) {
            return trim(value / 1_000d) + "K";
        }
        if (value < 1_000_000_000L) {
            return trim(value / 1_000_000d) + "M";
        }
        return trim(value / 1_000_000_000d) + "B";
    }

    private static String trim(double value) {
        String text = String.format(Locale.ROOT, "%.1f", value);
        return text.endsWith(".0") ? text.substring(0, text.length() - 2) : text;
    }

    static String playtime(long ticks) {
        long minutes = ticks / 1_200L;
        long days = minutes / 1_440L;
        long hours = (minutes % 1_440L) / 60L;
        if (days > 0L) {
            return days + "d " + hours + "h";
        }
        if (hours > 0L) {
            return hours + "h " + (minutes % 60L) + "m";
        }
        return minutes + "m";
    }

    private void show(
            Player player, String title, String body, List<ActionButton> buttons, int columns
    ) {
        List<ActionButton> shown = new ArrayList<>(buttons);
        shown.add(ActionButton.builder(Component.text("Close", MenuText.LABEL))
                .width(150)
                .action(callback((response, audience) -> audience.closeDialog()))
                .build());
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(MenuText.title(title))
                        .body(List.of(DialogBody.plainMessage(MenuText.body(body), 400)))
                        .afterAction(DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE)
                        .build())
                .type(DialogType.multiAction(shown).columns(columns).build()));
        player.showDialog(dialog);
    }

    private DialogAction callback(BiConsumer<DialogResponseView, Player> callback) {
        return DialogAction.customClick((response, audience) -> {
            if (audience instanceof Player player && player.isOnline()) {
                callback.accept(response, player);
            }
        }, CALLBACK_OPTIONS);
    }
}
