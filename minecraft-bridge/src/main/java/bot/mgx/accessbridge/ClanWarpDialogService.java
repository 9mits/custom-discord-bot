package bot.mgx.accessbridge;

import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

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
        open(player, null);
    }

    void open(Player player, Consumer<Player> back) {
        ClanStore.ClanView clan = clans.clanOf(player.getUniqueId()).orElse(null);
        if (clan == null) {
            PlayerMenuService.error(player, "You are not in a clan.");
            return;
        }
        if (!clientSupport.supportsDialogs(player)) {
            if (!bedrockWarps(player, clan, back)) {
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
                            openWarp(audience, name, back);
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
        if (manages && names.size() < slots) {
            buttons.add(action("Set New Warp", "Save where you are standing.",
                    audience -> openNewWarp(audience, back)));
        }
        Screens.show(player, clan.name() + " Warps", Screens.body(
                names.isEmpty()
                        ? manages ? "No clan warps yet. Set one where you are standing."
                                : "No clan warps yet."
                        : names.size() + " of " + slots + " used."
        ), buttons, 2, back);
    }

    /** Bedrock gets the same list, the same gating and the same management actions. */
    private boolean bedrockWarps(
            Player player, ClanStore.ClanView clan, Consumer<Player> back
    ) {
        boolean manages = manages(clan, player);
        List<BedrockForms.Button> buttons = new ArrayList<>();
        for (String name : clan.warps().keySet().stream()
                .sorted(String.CASE_INSENSITIVE_ORDER).toList()) {
            boolean allowed = meta.mayUse(clan.id(), name, player.getUniqueId());
            buttons.add(new BedrockForms.Button(
                    allowed || manages ? name : name + " (no access)",
                    () -> {
                        if (manages) {
                            bedrockWarp(player, name, back);
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
        if (manages && buttons.size() < ClanLevel.warpSlots(clan.level())) {
            buttons.add(new BedrockForms.Button(
                    "Set New Warp", () -> bedrockNewWarp(player, back)));
        }
        return forms.menu(player, clan.name() + " Warps",
                buttons.isEmpty() ? "No clan warps yet." : "Choose a warp.", buttons, back);
    }

    private void bedrockWarp(Player player, String warp, Consumer<Player> back) {
        ClanStore.ClanView clan = clans.clanOf(player.getUniqueId()).orElse(null);
        if (clan == null || !manages(clan, player)) {
            return;
        }
        forms.menu(player, warp, "What would you like to do?", List.of(
                new BedrockForms.Button("Teleport", () -> travel(player, warp)),
                new BedrockForms.Button(
                        "Change Icon", () -> bedrockIcons(player, warp, back)),
                new BedrockForms.Button("Rename",
                        () -> forms.prompt(player, "Rename " + warp, "New name", warp,
                                typed -> rename(player, warp, typed, back),
                                () -> bedrockWarp(player, warp, back))),
                new BedrockForms.Button("Permissions",
                        () -> bedrockPermissions(player, warp, back)),
                new BedrockForms.Button("Delete",
                        () -> forms.confirm(player, "Delete " + warp,
                                "This cannot be undone.", "Delete",
                                () -> deleteWarp(player, warp, back),
                                () -> bedrockWarp(player, warp, back)))
        ), audience -> open(audience, back));
    }

    private void bedrockNewWarp(Player player, Consumer<Player> back) {
        forms.prompt(player, "Set New Warp", "Name", "", typed -> {
            if (setWarp(player, typed)) {
                open(player, back);
            } else {
                bedrockNewWarp(player, back);
            }
        }, () -> open(player, back));
    }

    /** One toggle per member, submitted together, which is how a Bedrock form works. */
    private void bedrockPermissions(Player player, String warp, Consumer<Player> back) {
        ClanStore.ClanView clan = clans.clanOf(player.getUniqueId()).orElse(null);
        if (clan == null || !manages(clan, player)) {
            return;
        }
        java.util.Set<java.util.UUID> allowed = meta.allowed(clan.id(), warp);
        List<java.util.UUID> ids = clan.members().keySet().stream()
                .filter(memberId -> clan.roleOf(memberId) == ClanStore.ClanRole.MEMBER)
                .toList();
        if (ids.isEmpty()) {
            PlayerMenuService.error(player,
                    "There are no ordinary members whose access can be changed.");
            bedrockWarp(player, warp, back);
            return;
        }
        List<String> labels = new ArrayList<>();
        List<Boolean> initial = new ArrayList<>();
        for (java.util.UUID memberId : ids) {
            labels.add(clan.members().get(memberId));
            initial.add(allowed.isEmpty() || allowed.contains(memberId));
        }
        boolean sent = forms.toggles(player, warp + " Permissions", labels, initial, selected -> {
            java.util.Set<java.util.UUID> chosen = new java.util.LinkedHashSet<>();
            for (int index = 0; index < ids.size(); index++) {
                if (index < selected.size() && Boolean.TRUE.equals(selected.get(index))) {
                    chosen.add(ids.get(index));
                }
            }
            if (chosen.isEmpty()) {
                PlayerMenuService.error(player,
                        "Choose at least one member, or use Allow Everyone.");
                bedrockPermissions(player, warp, back);
                return;
            }
            try {
                if (chosen.size() == ids.size()) {
                    meta.allowEveryone(clan.id(), warp);
                } else {
                    meta.allowOnly(clan.id(), warp, chosen);
                }
            } catch (java.io.UncheckedIOException failure) {
                PlayerMenuService.error(player, "That could not be saved.");
            }
            bedrockWarp(player, warp, back);
        },
                () -> bedrockWarp(player, warp, back));
        if (!sent) {
            PlayerMenuService.error(player, "That screen could not be opened.");
        }
    }

    private void bedrockIcons(Player player, String warp, Consumer<Player> back) {
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
                bedrockWarp(player, warp, back);
            }));
        }
        forms.menu(player, "Choose Icon", "Pick an icon for " + warp + ".", buttons,
                viewer -> bedrockWarp(viewer, warp, back));
    }

    /** Shared by both delete paths so the store and the clan cannot drift apart. */
    private void deleteWarp(Player player, String warp, Consumer<Player> back) {
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
        open(player, back);
    }

    private void openNewWarp(Player player, Consumer<Player> back) {
        Screens.confirm(player, "Set New Warp", Screens.body(
                        "Save a clan warp where you are standing."),
                List.of(io.papermc.paper.registry.data.dialog.input.DialogInput
                        .text(NAME_INPUT, Component.text("Name", MenuText.LABEL))
                        .maxLength(16)
                        .build()),
                "Set Warp", MenuText.VALUE,
                (response, audience) -> {
                    if (setWarp(audience, response.getText(NAME_INPUT))) {
                        open(audience, back);
                    }
                },
                audience -> open(audience, back));
    }

    private boolean setWarp(Player player, String rawName) {
        String name = cleanName(rawName);
        if (name == null) {
            PlayerMenuService.error(player,
                    "Use 1-16 letters, numbers, - and _ for a warp name.");
            return false;
        }
        ClanStore.ClanView clan = clans.clanOf(player.getUniqueId()).orElse(null);
        if (clan != null && clan.warps().containsKey(name)) {
            PlayerMenuService.error(player,
                    "That clan warp already exists. Open it to manage it.");
            return false;
        }
        try {
            menus.setWarp(player, name);
            return true;
        } catch (java.io.IOException | ClanStore.ClanException failure) {
            PlayerMenuService.error(player, failure.getMessage() == null
                    ? "That warp could not be set." : failure.getMessage());
            return false;
        }
    }

    /** The management card, which only a leader or staff member ever reaches. */
    void openWarp(Player player, String warp) {
        openWarp(player, warp, null);
    }

    private void openWarp(Player player, String warp, Consumer<Player> back) {
        ClanStore.ClanView clan = clans.clanOf(player.getUniqueId()).orElse(null);
        if (clan == null || !manages(clan, player)) {
            return;
        }
        java.util.Set<java.util.UUID> allowed = meta.allowed(clan.id(), warp);
        List<ActionButton> buttons = List.of(
                action("Teleport", "Go there now.", audience -> travel(audience, warp)),
                action("Change Icon", "Pick the icon this warp shows.",
                        audience -> openIconPicker(audience, warp, "", 1, back)),
                action("Rename", "Give it a different name.",
                        audience -> openRename(audience, warp, back)),
                action("Permissions", allowed.isEmpty()
                                ? "Everyone in the clan can use it."
                                : allowed.size() + " member(s) can use it.",
                        audience -> openPermissions(audience, warp, back)),
                action("Delete", "Remove this warp for good.",
                        audience -> openDelete(audience, warp, back), NamedTextColor.RED)
        );
        show(player, warp, allowed.isEmpty()
                ? "Everyone in the clan can travel here."
                : "Only chosen members can travel here.", new ArrayList<>(buttons), 2,
                audience -> open(audience, back));
    }

    /** Who may travel here. An empty list is the everyone default, not a locked warp. */
    private void openPermissions(Player player, String warp, Consumer<Player> back) {
        ClanStore.ClanView clan = clans.clanOf(player.getUniqueId()).orElse(null);
        if (clan == null || !manages(clan, player)) {
            return;
        }
        java.util.Set<java.util.UUID> allowed = meta.allowed(clan.id(), warp);
        List<ActionButton> buttons = new ArrayList<>();
        clan.members().forEach((memberId, memberName) -> {
            if (clan.roleOf(memberId) != ClanStore.ClanRole.MEMBER) {
                return;
            }
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
                            java.util.Set<java.util.UUID> changed =
                                    new java.util.LinkedHashSet<>(allowed);
                            if (allowed.isEmpty()) {
                                changed.add(memberId);
                            } else if (!changed.remove(memberId)) {
                                changed.add(memberId);
                            } else if (changed.isEmpty()) {
                                PlayerMenuService.error(audience,
                                        "Choose at least one member, or use Allow Everyone.");
                                return;
                            }
                            meta.allowOnly(clan.id(), warp, changed);
                        } catch (java.io.UncheckedIOException failure) {
                            PlayerMenuService.error(audience, "That could not be saved.");
                            return;
                        }
                        openPermissions(audience, warp, back);
                    }))
                    .build());
        });
        buttons.add(action("Allow Everyone", "Clear the list and open it to the clan.",
                audience -> {
                    meta.allowEveryone(clan.id(), warp);
                    openPermissions(audience, warp, back);
                }));
        show(player, warp + " Permissions", allowed.isEmpty()
                ? "Nobody has been chosen, so everyone can travel here."
                : "Only the members marked YES can travel here.", buttons, 1,
                audience -> openWarp(audience, warp, back));
    }

    private void openRename(Player player, String warp, Consumer<Player> back) {
        Screens.confirm(player, "Rename " + warp,
                Screens.body("Give this warp a different name."),
                List.of(io.papermc.paper.registry.data.dialog.input.DialogInput
                        .text(NAME_INPUT, Component.text("New name", MenuText.LABEL))
                        .maxLength(16)
                        .build()),
                "Rename", MenuText.VALUE,
                (response, audience) -> rename(
                        audience, warp, response.getText(NAME_INPUT), back),
                audience -> openWarp(audience, warp, back));
    }

    /**
     * Clans have no rename, so this sets the new one where the old one stood and
     * removes the original, carrying the icon and guest list across.
     */
    private void rename(
            Player player, String warp, String rawName, Consumer<Player> back
    ) {
        ClanStore.ClanView clan = clans.clanOf(player.getUniqueId()).orElse(null);
        if (clan == null || !manages(clan, player)) {
            return;
        }
        String name = cleanName(rawName);
        if (name == null) {
            PlayerMenuService.error(player,
                    "Use 1-16 letters, numbers, - and _ for a warp name.");
            if (!clientSupport.supportsDialogs(player)) {
                bedrockWarp(player, warp, back);
            }
            return;
        }
        String currentName = warp.toLowerCase(java.util.Locale.ROOT);
        if (name.equals(currentName)) {
            PlayerMenuService.error(player, "That warp already has this name.");
            if (!clientSupport.supportsDialogs(player)) {
                bedrockWarp(player, warp, back);
            }
            return;
        }
        if (clan.warps().containsKey(name)) {
            PlayerMenuService.error(player, "Your clan already has a warp called " + name + ".");
            if (!clientSupport.supportsDialogs(player)) {
                bedrockWarp(player, warp, back);
            }
            return;
        }
        ClanStore.ClanWarp location = clan.warps().get(warp.toLowerCase(java.util.Locale.ROOT));
        if (location == null) {
            PlayerMenuService.error(player, "That warp is gone.");
            if (!clientSupport.supportsDialogs(player)) {
                open(player, back);
            }
            return;
        }
        try {
            clans.setWarp(player.getUniqueId(), name, location);
            clans.removeWarp(player.getUniqueId(), warp);
            meta.rename(clan.id(), warp, name);
        } catch (java.io.IOException | ClanStore.ClanException failure) {
            PlayerMenuService.error(player, failure.getMessage() == null
                    ? "That warp could not be renamed." : failure.getMessage());
            if (!clientSupport.supportsDialogs(player)) {
                bedrockWarp(player, warp, back);
            }
            return;
        }
        open(player, back);
    }

    private void openDelete(Player player, String warp, Consumer<Player> back) {
        Screens.confirm(player, "Delete " + warp,
                List.of(DialogBody.plainMessage(
                        Component.text("This cannot be undone.", NamedTextColor.RED), 400)),
                "Delete", NamedTextColor.RED,
                audience -> deleteWarp(audience, warp, back),
                audience -> openWarp(audience, warp, back));
    }

    /** The same catalogue the homes screen uses, so both look and search alike. */
    private void openIconPicker(
            Player player, String warp, String query, int page, Consumer<Player> back
    ) {
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
                        openWarp(audience, warp, back);
                    }))
                    .build());
        }
        if (current > 1) {
            buttons.add(action("Previous", "Earlier icons.",
                    audience -> openIconPicker(audience, warp, query, current - 1, back)));
        }
        if (current < pages) {
            buttons.add(action("Next", "More icons.",
                    audience -> openIconPicker(audience, warp, query, current + 1, back)));
        }
        buttons.add(ActionButton.builder(Component.text("Search", MenuText.VALUE))
                .width(150)
                .action(callback((response, audience) -> openIconPicker(
                        audience, warp,
                        response.getText(SEARCH_INPUT) == null
                                ? "" : response.getText(SEARCH_INPUT),
                        1,
                        back
                )))
                .build());
        Screens.show(player, "Choose Icon",
                Screens.body(matches.isEmpty()
                        ? "Nothing matches that."
                        : "Page " + current + " of " + pages + "."),
                List.of(io.papermc.paper.registry.data.dialog.input.DialogInput
                        .text(SEARCH_INPUT, Component.text("Search", MenuText.LABEL))
                        .maxLength(32)
                        .build()),
                buttons, 3, audience -> openWarp(audience, warp, back));
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
            Player player, String title, String body, List<ActionButton> buttons, int columns,
            java.util.function.Consumer<Player> back
    ) {
        Screens.show(player, title, Screens.body(body), buttons, columns, back);
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
            player.closeDialog();
            menus.useWarp(player, warp);
        } catch (ClanStore.ClanException failure) {
            PlayerMenuService.error(player, failure.getMessage());
        }
    }

    private static String cleanName(String raw) {
        if (raw == null) {
            return null;
        }
        String name = raw.strip().toLowerCase(java.util.Locale.ROOT);
        return name.matches("[a-z0-9_-]{1,16}") ? name : null;
    }

    private DialogAction callback(BiConsumer<DialogResponseView, Player> callback) {
        return DialogAction.customClick((response, audience) -> {
            if (audience instanceof Player player && player.isOnline()) {
                callback.accept(response, player);
            }
        }, CALLBACK_OPTIONS);
    }
}
