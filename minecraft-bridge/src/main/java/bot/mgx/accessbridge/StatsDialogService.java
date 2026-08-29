package bot.mgx.accessbridge;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
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
 *
 * <p>Everything is addressed by id rather than by a live {@link Player}, so a
 * leaderboard row can open a card for somebody who has already logged off.
 */
final class StatsDialogService implements CommandExecutor {
    private static final ClickCallback.Options CALLBACK_OPTIONS = ClickCallback.Options.builder()
            .uses(ClickCallback.UNLIMITED_USES)
            .lifetime(Duration.ofMinutes(10))
            .build();
    private static final int PROFILE_SIZE = 45;
    private static final String NAME_INPUT = "player_name";

    private final MGXAccessBridge plugin;
    private final ProfileStatsService profiles;
    private final CrateItems crateItems;
    private final SettingsClientSupport clientSupport;
    private final BedrockForms forms;

    StatsDialogService(
            MGXAccessBridge plugin,
            ProfileStatsService profiles,
            CrateItems crateItems,
            SettingsClientSupport clientSupport,
            BedrockForms forms
    ) {
        this.plugin = plugin;
        this.profiles = profiles;
        this.crateItems = crateItems;
        this.clientSupport = clientSupport;
        this.forms = forms;
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
            openCard(player, target.getUniqueId(), target.getName());
            return true;
        }
        openPicker(player);
        return true;
    }

    /** The player list, mirroring the teleport picker so the two read the same. */
    void openPicker(Player viewer) {
        if (!clientSupport.supportsDialogs(viewer)) {
            if (!bedrockPicker(viewer)) {
                openProfile(viewer, viewer.getUniqueId(), viewer.getName());
            }
            return;
        }
        List<ActionButton> buttons = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (VerificationLobbyService.isLobbyWorld(online.getWorld())) {
                continue;
            }
            UUID id = online.getUniqueId();
            String name = online.getName();
            buttons.add(ActionButton.builder(Component.empty()
                            .append(MenuText.head(id))
                            .append(Component.text(" " + name, NamedTextColor.WHITE)))
                    .tooltip(id.equals(viewer.getUniqueId())
                            ? Component.text("You", MenuText.LABEL)
                            : Component.text("View their stats", MenuText.LABEL))
                    .width(150)
                    .action(callback((response, audience) ->
                            openCard(audience, id, name, this::openPicker)))
                    .build());
        }
        buttons.add(ActionButton.builder(
                        Component.text("+ Look Up A Player", MenuText.VALUE))
                .tooltip(Component.text("Type a name, online or not.", MenuText.LABEL))
                .width(150)
                .action(callback((response, audience) -> openLookup(audience)))
                .build());
        show(viewer, "Player Stats", "Click a player to view their stats", buttons, 2);
    }

    /**
     * Opens anyone by name, whether or not they are here.
     *
     * <p>Their statistics are on disk either way, so being offline was never the real
     * limit — only the picker, which can list nobody who has logged out.
     */
    void openLookup(Player viewer) {
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(MenuText.title("Look Up A Player"))
                        .body(List.of(DialogBody.plainMessage(
                                MenuText.body("Type an exact Minecraft name."), 400
                        )))
                        .inputs(List.of(DialogInput.text(NAME_INPUT,
                                        Component.text("Name", MenuText.LABEL))
                                .maxLength(16)
                                .build()))
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .canCloseWithEscape(true)
                        .build())
                .type(DialogType.confirmation(
                        ActionButton.builder(Component.text("View Stats", MenuText.VALUE))
                                .width(150)
                                .action(callback((response, audience) ->
                                        lookUp(audience, response.getText(NAME_INPUT))))
                                .build(),
                        ActionButton.builder(Component.text("Cancel", MenuText.LABEL))
                                .width(150)
                                .action(callback((response, audience) -> openPicker(audience)))
                                .build()
                )));
        viewer.showDialog(dialog);
    }

    private void lookUp(Player viewer, String rawName) {
        String name = rawName == null ? "" : rawName.strip();
        if (!name.matches("[A-Za-z0-9_]{1,16}")) {
            PlayerMenuService.error(viewer, "That is not a Minecraft name.");
            return;
        }
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            openCard(viewer, online.getUniqueId(), online.getName(), this::openPicker);
            return;
        }
        // Paper's cache answers for anyone who has joined before, without a web call.
        org.bukkit.OfflinePlayer known = Bukkit.getOfflinePlayerIfCached(name);
        if (known == null) {
            PlayerMenuService.error(viewer, name + " has never played here.");
            return;
        }
        openCard(viewer, known.getUniqueId(),
                known.getName() == null ? name : known.getName(), this::openPicker);
    }

    /** The same picker as a Bedrock form, including the look-up a chest cannot offer. */
    private boolean bedrockPicker(Player viewer) {
        List<BedrockForms.Button> buttons = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (VerificationLobbyService.isLobbyWorld(online.getWorld())) {
                continue;
            }
            UUID id = online.getUniqueId();
            String name = online.getName();
            buttons.add(new BedrockForms.Button(
                    name, () -> openCard(viewer, id, name, this::openPicker)
            ));
        }
        buttons.add(new BedrockForms.Button("Look Up A Player",
                () -> forms.prompt(viewer, "Look Up A Player", "Name", "",
                        typed -> lookUp(viewer, typed))));
        return forms.menu(viewer, "Player Stats", "Choose a player.", buttons);
    }

    void openCard(Player viewer, UUID id, String fallbackName) {
        openCard(viewer, id, fallbackName, this::openPicker);
    }

    /**
     * The four headline numbers, with the full board one click away.
     *
     * <p>{@code back} is where the player came from. A card opened from a leaderboard
     * has to return to that board; sending it to the stats picker instead is how Back
     * ends up somewhere the player was never standing.
     */
    void openCard(
            Player viewer, UUID id, String fallbackName, java.util.function.Consumer<Player> back
    ) {
        ProfileStatsService.Profile profile = profiles.of(id, fallbackName);
        if (!clientSupport.supportsDialogs(viewer)) {
            boolean shown = forms.menu(viewer, profile.name(), String.join("\n",
                    "Money: " + EconomyFormat.dollars(profile.money()),
                    "Kills: " + compact(profile.playerKills()),
                    "Deaths: " + compact(profile.deaths()),
                    "Playtime: " + playtime(profile.playTimeTicks())
            ), List.of(
                    new BedrockForms.Button("View Full Profile",
                            () -> openProfile(viewer, id, profile.name(), back)),
                    new BedrockForms.Button("Back", () -> back.accept(viewer))
            ));
            if (!shown) {
                openProfile(viewer, id, fallbackName, back);
            }
            return;
        }
        List<DialogBody> body = List.of(
                DialogBody.plainMessage(MenuText.head(id), 400),
                DialogBody.plainMessage(Component.empty(), 400),
                DialogBody.plainMessage(MenuText.stat(
                        "Money", "item/emerald", EconomyFormat.dollars(profile.money())
                ), 400),
                DialogBody.plainMessage(MenuText.stat("Kills", compact(profile.playerKills())), 400),
                DialogBody.plainMessage(MenuText.stat("Deaths", compact(profile.deaths())), 400),
                DialogBody.plainMessage(
                        MenuText.stat("Playtime", playtime(profile.playTimeTicks())), 400
                )
        );
        List<ActionButton> buttons = List.of(
                ActionButton.builder(Component.text("View Full Profile", NamedTextColor.WHITE))
                        .tooltip(Component.text("Every number we keep.", MenuText.LABEL))
                        .width(310)
                        .action(callback((response, audience) ->
                                openProfile(audience, id, profile.name(), back)))
                        .build(),
                ActionButton.builder(Component.text("Back", MenuText.LABEL))
                        .width(310)
                        .action(callback((response, audience) -> back.accept(audience)))
                        .build()
        );
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(MenuText.title(profile.name()))
                        .body(body)
                        .afterAction(DialogBase.DialogAfterAction.NONE)
                        // Not a blocking prompt: the screen stays up so a toggle
                        // repaints in place instead of slamming shut on every click.
                        // NONE is only legal on a dialog that does not pause.
                        .pause(false)
                        .canCloseWithEscape(true)
                        .build())
                .type(DialogType.multiAction(buttons).columns(1).build()));
        viewer.showDialog(dialog);
    }

    /**
     * The full board. Every tile is an icon whose tooltip names the stat in green and
     * carries the figure underneath, which is the one place a long number reads
     * comfortably.
     */
    void openProfile(Player viewer, UUID id, String fallbackName) {
        openProfile(viewer, id, fallbackName, this::openPicker);
    }

    void openProfile(
            Player viewer, UUID id, String fallbackName, java.util.function.Consumer<Player> back
    ) {
        ProfileStatsService.Profile profile = profiles.of(id, fallbackName);
        Menu menu = new Menu(Menu.Kind.PLAYER_PROFILE, id, 1, null);
        Inventory inventory = Bukkit.createInventory(
                menu, PROFILE_SIZE,
                Component.text(profile.name() + " Stats", MenuText.ORANGE)
        );
        menu.attach(inventory);
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21};
        List<ItemStack> tiles = List.of(
                tile(new ItemStack(Material.EMERALD), "MONEY",
                        EconomyFormat.dollars(profile.money())),
                // The real Shard carries its own model, so the tile is that item rather
                // than a plain amethyst shard, which is a different thing in this world.
                tile(crateItems.shard(1), "SHARDS", compact(profile.shards())),
                tile(new ItemStack(Material.DIAMOND_SWORD), "PLAYER KILLS",
                        compact(profile.playerKills())),
                tile(new ItemStack(Material.ROTTEN_FLESH), "MOBS KILLED",
                        compact(profile.mobKills())),
                tile(new ItemStack(Material.SKELETON_SKULL), "DEATHS",
                        compact(profile.deaths())),
                tile(new ItemStack(Material.CLOCK), "PLAYTIME",
                        playtime(profile.playTimeTicks())),
                tile(new ItemStack(Material.DIAMOND_PICKAXE), "BLOCKS BROKEN",
                        compact(profile.blocksBroken())),
                tile(new ItemStack(Material.BRICKS), "BLOCKS PLACED",
                        compact(profile.blocksPlaced())),
                tile(new ItemStack(Material.LEATHER_BOOTS), "DISTANCE WALKED",
                        compact(profile.walkedCm() / 100L) + " blocks"),
                tile(MenuItems.head(id, profile.name(), List.of()), "PLAYER", profile.name())
        );
        for (int index = 0; index < tiles.size() && index < slots.length; index++) {
            inventory.setItem(slots[index], tiles.get(index));
        }
        inventory.setItem(40, MenuItems.button(Material.BARRIER, "Close"));
        MenuItems.show(plugin, viewer, inventory);
    }

    /** Green name, quiet figure — the tooltip labels what the grid cannot. */
    private static ItemStack tile(ItemStack item, String name, String value) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(name, MenuText.VALUE, TextDecoration.BOLD)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(Component.text(value, NamedTextColor.WHITE)
                    .decoration(TextDecoration.ITALIC, false)));
            MenuItems.asButton(meta);
            item.setItemMeta(meta);
        }
        return item;
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
                        .afterAction(DialogBase.DialogAfterAction.NONE)
                        // Not a blocking prompt: the screen stays up so a toggle
                        // repaints in place instead of slamming shut on every click.
                        // NONE is only legal on a dialog that does not pause.
                        .pause(false)
                        .canCloseWithEscape(true)
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
