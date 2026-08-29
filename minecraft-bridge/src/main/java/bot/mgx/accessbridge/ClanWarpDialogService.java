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
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * A clan's own warps, in the same shape as the public ones.
 *
 * <p>They were only reachable from inside the clan hub, several clicks past a screen
 * about donations and levels, which is a long way from where somebody stands when
 * they want to travel. The Warps button now offers both.
 */
final class ClanWarpDialogService {
    private static final ClickCallback.Options CALLBACK_OPTIONS = ClickCallback.Options.builder()
            .uses(ClickCallback.UNLIMITED_USES)
            .lifetime(Duration.ofMinutes(10))
            .build();

    private static final String NAME_INPUT = "warp_name";
    private static final String SEARCH_INPUT = "icon_search";
    private static final int ICONS_PER_PAGE = 24;

    private final ClanStore clans;
    private final ClanMenuService menus;
    private final ClanWarpMetaStore meta;
    private final SettingsClientSupport clientSupport;
    private final BedrockForms forms;

    ClanWarpDialogService(
            ClanStore clans,
            ClanMenuService menus,
            ClanWarpMetaStore meta,
            SettingsClientSupport clientSupport,
            BedrockForms forms
    ) {
        this.clans = clans;
        this.menus = menus;
        this.meta = meta;
        this.clientSupport = clientSupport;
        this.forms = forms;
    }

    /** Leaders and staff manage warps; everyone else travels with them. */
    private static boolean manages(ClanStore.ClanView clan, Player player) {
        return clan.roleOf(player.getUniqueId()) != ClanStore.ClanRole.MEMBER;
    }

    void open(Player player) {
        ClanStore.ClanView clan = clans.clanOf(player.getUniqueId()).orElse(null);
        if (clan == null) {
            PlayerMenuService.error(player, "You are not in a clan.");
            return;
        }
        if (!clientSupport.supportsDialogs(player)) {
            if (!bedrockWarps(player, clan)) {
                menus.openWarps(player);
            }
            return;
        }
        boolean manages = manages(clan, player);
        List<String> names = clan.warps().keySet().stream().sorted(
                String.CASE_INSENSITIVE_ORDER
        ).toList();
        List<ActionButton> buttons = new ArrayList<>();
        for (String name : names) {
            boolean allowed = meta.mayUse(clan.id(), name, player.getUniqueId());
            // A warp a member cannot use is still listed: hiding it would read as the
            // warp not existing, and they would ask why it vanished.
            buttons.add(ActionButton.builder(Component.empty()
                            .append(MenuText.sprite(meta.iconOf(clan.id(), name)))
                            .append(Component.text(" " + name,
                                    allowed || manages
                                            ? NamedTextColor.WHITE : NamedTextColor.DARK_GRAY)))
                    .tooltip(Component.text(
                            manages ? "Open this warp's options."
                                    : allowed ? "Travel to this clan warp."
                                            : "You do not have access to this warp.",
                            MenuText.LABEL
                    ))
                    .width(150)
                    .action(callback((response, audience) -> {
                        if (manages) {
                            openWarp(audience, name);
                        } else if (allowed) {
                            travel(audience, name);
                        } else {
                            PlayerMenuService.error(
                                    audience, "You do not have access to " + name + "."
                            );
                        }
                    }))
                    .build());
        }

        int slots = ClanLevel.warpSlots(clan.level());
        // Reached from the clan hub and from the warp list, so it Closes rather than
        // guessing which of the two to send the player back to.
        Screens.showAndClose(player, clan.name() + " Warps", Screens.body(
                names.isEmpty()
                        ? "No clan warps yet. Set one with /clans setwarp <name>."
                        : names.size() + " of " + slots + " used."
        ), buttons, 2, null);
    }

    /** Bedrock gets the same list, the same gating and the same management actions. */
    private boolean bedrockWarps(Player player, ClanStore.ClanView clan) {
        boolean manages = manages(clan, player);
        List<BedrockForms.Button> buttons = new ArrayList<>();
        for (String name : clan.warps().keySet().stream()
                .sorted(String.CASE_INSENSITIVE_ORDER).toList()) {
            boolean allowed = meta.mayUse(clan.id(), name, player.getUniqueId());
            buttons.add(new BedrockForms.Button(
                    allowed || manages ? name : name + " (no access)",
                    () -> {
                        if (manages) {
                            bedrockWarp(player, name);
                        } else if (allowed) {
                            travel(player, name);
                        } else {
                            PlayerMenuService.error(
                                    player, "You do not have access to " + name + "."
                            );
                        }
                    }
            ));
        }
        return forms.menu(player, clan.name() + " Warps",
                buttons.isEmpty() ? "No clan warps yet." : "Choose a warp.", buttons);
    }

    private void bedrockWarp(Player player, String warp) {
        ClanStore.ClanView clan = clans.clanOf(player.getUniqueId()).orElse(null);
        if (clan == null || !manages(clan, player)) {
            return;
        }
        forms.menu(player, warp, "What would you like to do?", List.of(
                new BedrockForms.Button("Teleport", () -> travel(player, warp)),
                new BedrockForms.Button("Change Icon", () -> bedrockIcons(player, warp)),
                new BedrockForms.Button("Rename",
                        () -> forms.prompt(player, "Rename " + warp, "New name", warp,
                                typed -> rename(player, warp, typed))),
                new BedrockForms.Button("Permissions",
                        () -> bedrockPermissions(player, warp)),
                new BedrockForms.Button("Delete",
                        () -> forms.confirm(player, "Delete " + warp,
                                "This cannot be undone.", "Delete", "Keep it",
                                () -> deleteWarp(player, warp))),
                new BedrockForms.Button("Back", () -> open(player))
        ));
    }

    /** One toggle per member, submitted together, which is how a Bedrock form works. */
    private void bedrockPermissions(Player player, String warp) {
        ClanStore.ClanView clan = clans.clanOf(player.getUniqueId()).orElse(null);
        if (clan == null || !manages(clan, player)) {
            return;
        }
        java.util.Set<java.util.UUID> allowed = meta.allowed(clan.id(), warp);
        List<java.util.UUID> ids = new ArrayList<>(clan.members().keySet());
        List<String> labels = new ArrayList<>();
        List<Boolean> initial = new ArrayList<>();
        for (java.util.UUID memberId : ids) {
            labels.add(clan.members().get(memberId));
            initial.add(allowed.isEmpty() || allowed.contains(memberId));
        }
        boolean sent = forms.toggles(player, warp + " Permissions", labels, initial, index -> {
            // An empty list means everyone, so the first change has to write the rest
            // in as well or turning one member off would lock out the whole clan.
            if (meta.allowed(clan.id(), warp).isEmpty()) {
                for (java.util.UUID memberId : ids) {
                    meta.toggleAllowed(clan.id(), warp, memberId);
                }
            }
            meta.toggleAllowed(clan.id(), warp, ids.get(index));
        });
        if (!sent) {
            PlayerMenuService.error(player, "That screen could not be opened.");
        }
    }

    private void bedrockIcons(Player player, String warp) {
        List<BedrockForms.Button> buttons = new ArrayList<>();
        ClanStore.ClanView clan = clans.clanOf(player.getUniqueId()).orElse(null);
        if (clan == null) {
            return;
        }
        for (String sprite : HomeIcons.all()) {
            buttons.add(new BedrockForms.Button(HomeIcons.label(sprite), () -> {
                try {
                    meta.setIcon(clan.id(), warp, sprite);
                } catch (IllegalArgumentException | java.io.UncheckedIOException failure) {
                    PlayerMenuService.error(player, "That icon could not be saved.");
                    return;
                }
                bedrockWarp(player, warp);
            }));
        }
        forms.menu(player, "Choose Icon", "Pick an icon for " + warp + ".", buttons);
    }

    /** Shared by both delete paths so the store and the clan cannot drift apart. */
    private void deleteWarp(Player player, String warp) {
        ClanStore.ClanView clan = clans.clanOf(player.getUniqueId()).orElse(null);
        if (clan == null || !manages(clan, player)) {
            return;
        }
        try {
            clans.removeWarp(player.getUniqueId(), warp);
            meta.forget(clan.id(), warp);
        } catch (java.io.IOException | ClanStore.ClanException failure) {
            PlayerMenuService.error(player, "That warp could not be removed.");
            return;
        }
        open(player);
    }

    /** The management card, which only a leader or staff member ever reaches. */
    void openWarp(Player player, String warp) {
        ClanStore.ClanView clan = clans.clanOf(player.getUniqueId()).orElse(null);
        if (clan == null || !manages(clan, player)) {
            return;
        }
        java.util.Set<java.util.UUID> allowed = meta.allowed(clan.id(), warp);
        List<ActionButton> buttons = List.of(
                action("Teleport", "Go there now.", audience -> travel(audience, warp)),
                action("Change Icon", "Pick the icon this warp shows.",
                        audience -> openIconPicker(audience, warp, "", 1)),
                action("Rename", "Give it a different name.",
                        audience -> openRename(audience, warp)),
                action("Permissions", allowed.isEmpty()
                                ? "Everyone in the clan can use it."
                                : allowed.size() + " member(s) can use it.",
                        audience -> openPermissions(audience, warp)),
                action("Delete", "Remove this warp for good.",
                        audience -> openDelete(audience, warp), NamedTextColor.RED),
                action("Back", "Return to the warps.", this::open)
        );
        show(player, warp, allowed.isEmpty()
                ? "Everyone in the clan can travel here."
                : "Only chosen members can travel here.", new ArrayList<>(buttons), 2);
    }

    /** Who may travel here. An empty list is the everyone default, not a locked warp. */
    private void openPermissions(Player player, String warp) {
        ClanStore.ClanView clan = clans.clanOf(player.getUniqueId()).orElse(null);
        if (clan == null || !manages(clan, player)) {
            return;
        }
        java.util.Set<java.util.UUID> allowed = meta.allowed(clan.id(), warp);
        List<ActionButton> buttons = new ArrayList<>();
        clan.members().forEach((memberId, memberName) -> {
            boolean can = allowed.isEmpty() || allowed.contains(memberId);
            buttons.add(ActionButton.builder(Component.empty()
                            .append(MenuText.head(memberId))
                            .append(Component.text(" " + memberName + ": ", NamedTextColor.WHITE))
                            .append(Component.text(can ? "YES" : "NO",
                                    can ? MenuText.VALUE : NamedTextColor.RED)))
                    .tooltip(Component.text(
                            allowed.isEmpty()
                                    ? "Everyone is allowed. Click to allow only this member."
                                    : "Click to change their access.",
                            MenuText.LABEL
                    ))
                    .width(200)
                    .action(callback((response, audience) -> {
                        try {
                            meta.toggleAllowed(clan.id(), warp, memberId);
                        } catch (java.io.UncheckedIOException failure) {
                            PlayerMenuService.error(audience, "That could not be saved.");
                            return;
                        }
                        openPermissions(audience, warp);
                    }))
                    .build());
        });
        buttons.add(action("Allow Everyone", "Clear the list and open it to the clan.",
                audience -> {
                    meta.allowEveryone(clan.id(), warp);
                    openPermissions(audience, warp);
                }));
        buttons.add(action("Back", "Return to " + warp + ".",
                audience -> openWarp(audience, warp)));
        show(player, warp + " Permissions", allowed.isEmpty()
                ? "Nobody has been chosen, so everyone can travel here."
                : "Only the members marked YES can travel here.", buttons, 1);
    }

    private void openRename(Player player, String warp) {
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(MenuText.title("Rename " + warp))
                        .body(List.of(DialogBody.plainMessage(
                                MenuText.body("Give this warp a different name."), 400
                        )))
                        .inputs(List.of(io.papermc.paper.registry.data.dialog.input.DialogInput
                                .text(NAME_INPUT, Component.text("New name", MenuText.LABEL))
                                .maxLength(32)
                                .build()))
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .canCloseWithEscape(true)
                        .build())
                .type(DialogType.confirmation(
                        ActionButton.builder(Component.text("Rename", MenuText.VALUE))
                                .width(150)
                                .action(callback((response, audience) ->
                                        rename(audience, warp, response.getText(NAME_INPUT))))
                                .build(),
                        ActionButton.builder(Component.text("Cancel", MenuText.LABEL))
                                .width(150)
                                .action(callback((response, audience) ->
                                        openWarp(audience, warp)))
                                .build()
                )));
        player.showDialog(dialog);
    }

    /**
     * Clans have no rename, so this sets the new one where the old one stood and
     * removes the original, carrying the icon and guest list across.
     */
    private void rename(Player player, String warp, String rawName) {
        ClanStore.ClanView clan = clans.clanOf(player.getUniqueId()).orElse(null);
        if (clan == null || !manages(clan, player)) {
            return;
        }
        String name = rawName == null ? "" : rawName.strip();
        if (!name.matches("[A-Za-z0-9_-]{1,32}")) {
            PlayerMenuService.error(player, "Use letters, numbers, - and _ for a warp name.");
            return;
        }
        ClanStore.ClanWarp location = clan.warps().get(warp.toLowerCase(java.util.Locale.ROOT));
        if (location == null) {
            PlayerMenuService.error(player, "That warp is gone.");
            return;
        }
        try {
            clans.setWarp(player.getUniqueId(), name, location);
            clans.removeWarp(player.getUniqueId(), warp);
            meta.rename(clan.id(), warp, name);
        } catch (java.io.IOException | ClanStore.ClanException failure) {
            PlayerMenuService.error(player, failure.getMessage() == null
                    ? "That warp could not be renamed." : failure.getMessage());
            return;
        }
        open(player);
    }

    private void openDelete(Player player, String warp) {
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(MenuText.title("Delete " + warp))
                        .body(List.of(DialogBody.plainMessage(
                                Component.text("This cannot be undone.", NamedTextColor.RED), 400
                        )))
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .canCloseWithEscape(true)
                        .build())
                .type(DialogType.confirmation(
                        ActionButton.builder(Component.text("Delete", NamedTextColor.RED))
                                .width(150)
                                .action(callback((response, audience) -> {
                                    ClanStore.ClanView owned =
                                            clans.clanOf(audience.getUniqueId()).orElse(null);
                                    if (owned == null || !manages(owned, audience)) {
                                        return;
                                    }
                                    try {
                                        clans.removeWarp(audience.getUniqueId(), warp);
                                        meta.forget(owned.id(), warp);
                                    } catch (java.io.IOException | ClanStore.ClanException failure) {
                                        PlayerMenuService.error(
                                                audience, "That warp could not be removed."
                                        );
                                        return;
                                    }
                                    open(audience);
                                }))
                                .build(),
                        ActionButton.builder(Component.text("Keep it", MenuText.LABEL))
                                .width(150)
                                .action(callback((response, audience) ->
                                        openWarp(audience, warp)))
                                .build()
                )));
        player.showDialog(dialog);
    }

    /** The same catalogue the homes screen uses, so both look and search alike. */
    private void openIconPicker(Player player, String warp, String query, int page) {
        ClanStore.ClanView clan = clans.clanOf(player.getUniqueId()).orElse(null);
        if (clan == null || !manages(clan, player)) {
            return;
        }
        List<String> matches = HomeIcons.search(query);
        int pages = Math.max(1, (matches.size() + ICONS_PER_PAGE - 1) / ICONS_PER_PAGE);
        int current = Math.clamp(page, 1, pages);
        int first = (current - 1) * ICONS_PER_PAGE;
        int last = Math.min(matches.size(), first + ICONS_PER_PAGE);

        List<ActionButton> buttons = new ArrayList<>();
        for (int index = first; index < last; index++) {
            String sprite = matches.get(index);
            buttons.add(ActionButton.builder(Component.empty()
                            .append(MenuText.sprite(sprite))
                            .append(Component.text(" " + HomeIcons.label(sprite),
                                    NamedTextColor.WHITE)))
                    .width(150)
                    .action(callback((response, audience) -> {
                        try {
                            meta.setIcon(clan.id(), warp, sprite);
                        } catch (IllegalArgumentException | java.io.UncheckedIOException failure) {
                            PlayerMenuService.error(audience, "That icon could not be saved.");
                            return;
                        }
                        openWarp(audience, warp);
                    }))
                    .build());
        }
        if (current > 1) {
            buttons.add(action("Previous", "Earlier icons.",
                    audience -> openIconPicker(audience, warp, query, current - 1)));
        }
        if (current < pages) {
            buttons.add(action("Next", "More icons.",
                    audience -> openIconPicker(audience, warp, query, current + 1)));
        }
        buttons.add(ActionButton.builder(Component.text("Search", MenuText.VALUE))
                .width(150)
                .action(callback((response, audience) -> openIconPicker(
                        audience, warp,
                        response.getText(SEARCH_INPUT) == null
                                ? "" : response.getText(SEARCH_INPUT),
                        1
                )))
                .build());
        buttons.add(action("Back", "Return to " + warp + ".",
                audience -> openWarp(audience, warp)));

        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(MenuText.title("Choose Icon"))
                        .body(List.of(DialogBody.plainMessage(MenuText.body(
                                matches.isEmpty()
                                        ? "Nothing matches that."
                                        : "Page " + current + " of " + pages + "."
                        ), 400)))
                        .inputs(List.of(io.papermc.paper.registry.data.dialog.input.DialogInput
                                .text(SEARCH_INPUT, Component.text("Search", MenuText.LABEL))
                                .maxLength(32)
                                .build()))
                        .afterAction(DialogBase.DialogAfterAction.NONE)
                        .pause(false)
                        .canCloseWithEscape(true)
                        .build())
                .type(DialogType.multiAction(buttons).columns(3).build()));
        player.showDialog(dialog);
    }

    private ActionButton action(
            String label, String tooltip, java.util.function.Consumer<Player> run
    ) {
        return action(label, tooltip, run, NamedTextColor.WHITE);
    }

    private ActionButton action(
            String label,
            String tooltip,
            java.util.function.Consumer<Player> run,
            net.kyori.adventure.text.format.TextColor colour
    ) {
        return ActionButton.builder(Component.text(label, colour))
                .tooltip(Component.text(tooltip, MenuText.LABEL))
                .width(150)
                .action(callback((response, audience) -> run.accept(audience)))
                .build();
    }

    private void show(
            Player player, String title, String body, List<ActionButton> buttons, int columns
    ) {
        Screens.show(player, title, Screens.body(body), buttons, columns, this::open);
    }

    /** The warmup, permissions and world checks stay with the existing command path. */
    private void travel(Player player, String warp) {
        ClanStore.ClanView clan = clans.clanOf(player.getUniqueId()).orElse(null);
        if (clan != null && !manages(clan, player)
                && !meta.mayUse(clan.id(), warp, player.getUniqueId())) {
            PlayerMenuService.error(player, "You do not have access to " + warp + ".");
            return;
        }
        try {
            menus.useWarp(player, warp);
        } catch (ClanStore.ClanException failure) {
            PlayerMenuService.error(player, failure.getMessage());
        }
    }

    private DialogAction callback(BiConsumer<DialogResponseView, Player> callback) {
        return DialogAction.customClick((response, audience) -> {
            if (audience instanceof Player player && player.isOnline()) {
                callback.accept(response, player);
            }
        }, CALLBACK_OPTIONS);
    }
}
