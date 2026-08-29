package bot.mgx.accessbridge;

import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The last three screens that were lists of records rather than of items: the
 * whitelist, the bounty board, and the level perks.
 *
 * <p>Each was a grid of player heads or dyes standing in for a name, a number or a
 * paragraph. A chest caps them at a page, hides the detail in a tooltip, and cannot
 * be clicked through to anything — the bounty board's own empty state told the player
 * to go and type {@code /bounty set} instead.
 */
final class RecordListDialogs {
    private static final ClickCallback.Options CALLBACK_OPTIONS = ClickCallback.Options.builder()
            .uses(ClickCallback.UNLIMITED_USES)
            .lifetime(Duration.ofMinutes(10))
            .build();
    private static final int PER_PAGE = 12;
    private static final String NAME_INPUT = "player_name";
    private static final String AMOUNT_INPUT = "amount";

    private final WhitelistDirectory whitelist;
    private final BountyStore bounties;
    private final EconomyStore money;
    private final StatsDialogService stats;
    private final PlayerMenuService menus;
    private final SettingsClientSupport clientSupport;
    private final BedrockForms forms;
    private BountyService bountyMenus;

    RecordListDialogs(
            WhitelistDirectory whitelist,
            BountyStore bounties,
            EconomyStore money,
            StatsDialogService stats,
            PlayerMenuService menus,
            SettingsClientSupport clientSupport,
            BedrockForms forms
    ) {
        this.whitelist = whitelist;
        this.bounties = bounties;
        this.money = money;
        this.stats = stats;
        this.menus = menus;
        this.clientSupport = clientSupport;
        this.forms = forms;
    }

    void useBountyMenus(BountyService bountyMenus) {
        this.bountyMenus = bountyMenus;
    }

    // ---------------------------------------------------------------- perks

    /** Six milestones and what each unlocks: text, which a chest could only tooltip. */
    void openPerks(Player player) {
        int[] milestones = {5, 10, 20, 30, 40, 50};
        int[] hearts = {1, 2, 3, 4, 5, 5};
        List<String> lines = new ArrayList<>();
        for (int index = 0; index < milestones.length; index++) {
            String perk = "+" + hearts[index]
                    + (hearts[index] == 1 ? " bonus heart" : " total bonus hearts");
            if (milestones[index] == 50) {
                perk += ", +" + Math.round(PlayerPerkService.ELITE_DAMAGE_BONUS * 100)
                        + "% direct combat damage";
            }
            lines.add("Level " + milestones[index] + ": " + perk);
        }
        if (!clientSupport.supportsDialogs(player)) {
            boolean shown = forms.menu(player, "Level Perks",
                    String.join("\n", lines)
                            + "\n\nEarn them by chatting in the Discord; roles sync automatically.",
                    List.of());
            if (!shown) {
                menus.openPerks(player);
            }
            return;
        }
        List<DialogBody> body = new ArrayList<>();
        for (int index = 0; index < milestones.length; index++) {
            body.add(DialogBody.plainMessage(MenuText.stat(
                    "Level " + milestones[index],
                    milestones[index] == 50 ? "item/nether_star" : "item/redstone",
                    lines.get(index).substring(lines.get(index).indexOf(": ") + 2)
            ), 400));
        }
        body.add(DialogBody.plainMessage(Component.empty(), 400));
        body.add(DialogBody.plainMessage(MenuText.body(
                "Earn them by chatting in the Discord. Roles sync automatically."), 400));
        show(player, "Level Perks", body, List.of(), 1);
    }

    // ------------------------------------------------------------ whitelist

    void openWhitelist(Player player, int page) {
        List<WhitelistDirectory.Entry> entries = whitelist.entries();
        int pages = Math.max(1, (entries.size() + PER_PAGE - 1) / PER_PAGE);
        int current = Math.clamp(page, 1, pages);
        int first = (current - 1) * PER_PAGE;
        int last = Math.min(entries.size(), first + PER_PAGE);

        if (!clientSupport.supportsDialogs(player)) {
            List<BedrockForms.Button> buttons = new ArrayList<>();
            for (int index = first; index < last; index++) {
                WhitelistDirectory.Entry entry = entries.get(index);
                Player online = Bukkit.getPlayerExact(entry.username());
                UUID id = entry.minecraftUuid();
                buttons.add(new BedrockForms.Button(
                        entry.username() + (online != null ? " (online)" : ""),
                        () -> {
                            if (id != null) {
                                stats.openCard(player, id, entry.username(),
                                        viewer -> openWhitelist(viewer, current));
                            } else {
                                PlayerMenuService.error(player,
                                        "No Minecraft profile is linked to that entry.");
                            }
                        }
                ));
            }
            addBedrockPager(buttons, current, pages,
                    target -> openWhitelist(player, target));
            if (!forms.menu(player, "Whitelist",
                    entries.size() + " with access.", buttons)) {
                menus.openWhitelist(player, current);
            }
            return;
        }
        List<DialogBody> body = new ArrayList<>();
        if (entries.isEmpty()) {
            body.add(DialogBody.plainMessage(
                    MenuText.body("Nobody yet. The directory has not synced from Discord."), 400));
        }
        for (int index = first; index < last; index++) {
            WhitelistDirectory.Entry entry = entries.get(index);
            Player online = Bukkit.getPlayerExact(entry.username());
            UUID id = entry.minecraftUuid();
            Component line = Component.empty()
                    .append(id == null
                            ? Component.text("• ", MenuText.LABEL)
                            : MenuText.head(id))
                    .append(Component.text(" " + entry.username(), NamedTextColor.WHITE))
                    .append(Component.text("  " + (entry.edition().isBlank()
                            ? "Java" : entry.edition()), MenuText.LABEL));
            String discordName = menus.visibleDiscordUsername(entry.minecraftUuid()).orElse(null);
            if (discordName != null) {
                line = line.append(Component.text("  @" + discordName, MenuText.VALUE));
            }
            line = line.append(Component.text(
                    online != null ? "  ·  online" : "", MenuText.VALUE));
            if (id != null) {
                String name = entry.username();
                line = line.clickEvent(ClickEvent.callback(audience -> {
                    if (audience instanceof Player clicker && clicker.isOnline()) {
                        stats.openCard(clicker, id, name, viewer -> openWhitelist(viewer, current));
                    }
                }, CALLBACK_OPTIONS));
            }
            body.add(DialogBody.plainMessage(line, 400));
        }
        show(player, "Whitelist  (" + entries.size() + ")", body,
                pager(current, pages, target -> openWhitelist(player, target)), 2);
    }

    // ----------------------------------------------------------- bounties

    void openBounties(Player player, int page) {
        List<BountyStore.Entry> ranked = bounties.ranked();
        int pages = Math.max(1, (ranked.size() + PER_PAGE - 1) / PER_PAGE);
        int current = Math.clamp(page, 1, pages);
        int first = (current - 1) * PER_PAGE;
        int last = Math.min(ranked.size(), first + PER_PAGE);
        long mine = bounties.amountOn(player.getUniqueId());

        if (!clientSupport.supportsDialogs(player)) {
            List<BedrockForms.Button> buttons = new ArrayList<>();
            for (int index = first; index < last; index++) {
                BountyStore.Entry entry = ranked.get(index);
                buttons.add(new BedrockForms.Button(
                        "#" + (index + 1) + " " + nameOf(entry.target())
                                + " - " + EconomyFormat.dollars(entry.amount()),
                        () -> stats.openCard(player, entry.target(), nameOf(entry.target()),
                                viewer -> openBounties(viewer, current))
                ));
            }
            addBedrockPager(buttons, current, pages,
                    target -> openBounties(player, target));
            buttons.add(new BedrockForms.Button(
                    "Place a Bounty", () -> promptBounty(player, current)));
            if (!forms.menu(player, "Bounties",
                    "On you: " + EconomyFormat.dollars(mine), buttons)) {
                if (bountyMenus != null) {
                    bountyMenus.openBoard(player, current);
                } else {
                    PlayerMenuService.error(player, "The bounty board could not be opened.");
                }
            }
            return;
        }
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(MenuText.stat(
                "On you", "item/gold_ingot", EconomyFormat.dollars(mine)), 400));
        body.add(DialogBody.plainMessage(Component.empty(), 400));
        if (ranked.isEmpty()) {
            body.add(DialogBody.plainMessage(MenuText.body("No bounties standing."), 400));
        }
        for (int index = first; index < last; index++) {
            BountyStore.Entry entry = ranked.get(index);
            UUID target = entry.target();
            String name = nameOf(target);
            Component line = MenuText.rankedRow(index + 1, target, name,
                            Component.text(EconomyFormat.dollars(entry.amount()), MenuText.VALUE))
                    .append(Component.text(
                            Bukkit.getPlayer(target) != null ? "  ·  online" : "", MenuText.LABEL))
                    .clickEvent(ClickEvent.callback(audience -> {
                        if (audience instanceof Player clicker && clicker.isOnline()) {
                            stats.openCard(clicker, target, name,
                                    viewer -> openBounties(viewer, current));
                        }
                    }, CALLBACK_OPTIONS));
            body.add(DialogBody.plainMessage(line, 400));
        }
        List<ActionButton> buttons = new ArrayList<>(
                pager(current, pages, target -> openBounties(player, target)));
        buttons.add(ActionButton.builder(Component.text("Place a Bounty", MenuText.VALUE))
                .tooltip(Component.text("Put money on somebody's head.", MenuText.LABEL))
                .width(150)
                .action(callback((response, audience) -> promptBounty(audience, current)))
                .build());
        show(player, "Bounties", body, buttons, 2);
    }

    /**
     * Placing a bounty needs a name and an amount, which is exactly the pair a chest
     * could never ask for — the old board's empty state told the player to go and type
     * the command instead.
     */
    private void promptBounty(Player player, int page) {
        if (!clientSupport.supportsDialogs(player)) {
            forms.prompt(player, "Place a Bounty", "Player name", "", name ->
                    forms.prompt(player, "Place a Bounty", "Amount", "", amount ->
                            placeBounty(player, name, amount, page),
                            () -> openBounties(player, page)),
                    () -> openBounties(player, page));
            return;
        }
        Screens.confirm(player, "Place a Bounty",
                List.of(DialogBody.plainMessage(MenuText.stat(
                        "Your wallet", "item/emerald",
                        EconomyFormat.dollars(money.balance(player.getUniqueId()))), 400)),
                List.of(
                        io.papermc.paper.registry.data.dialog.input.DialogInput
                                .text(NAME_INPUT, Component.text("Player", MenuText.LABEL))
                                .maxLength(16).build(),
                        io.papermc.paper.registry.data.dialog.input.DialogInput
                                .text(AMOUNT_INPUT, Component.text("Amount", MenuText.LABEL))
                                .maxLength(20).build()
                ),
                "Place", MenuText.VALUE,
                (response, audience) -> placeBounty(audience,
                        response.getText(NAME_INPUT), response.getText(AMOUNT_INPUT), page),
                audience -> openBounties(audience, page));
    }

    private void placeBounty(Player player, String rawName, String rawAmount, int page) {
        String name = text(rawName);
        String amount = text(rawAmount);
        if (!name.matches("[A-Za-z0-9_]{1,16}") || amount.isBlank()
                || amount.chars().anyMatch(Character::isWhitespace)) {
            PlayerMenuService.error(player, "Give a player name and one valid amount.");
            if (!clientSupport.supportsDialogs(player)) {
                promptBounty(player, page);
            }
            return;
        }
        player.performCommand("bounty set " + name + " " + amount);
        openBounties(player, page);
    }

    // -------------------------------------------------------------- shared

    private static String text(String raw) {
        return raw == null ? "" : raw.strip();
    }

    private static String nameOf(UUID target) {
        Player online = Bukkit.getPlayer(target);
        if (online != null) {
            return online.getName();
        }
        String known = Bukkit.getOfflinePlayer(target).getName();
        return known == null ? target.toString().substring(0, 8) : known;
    }

    private List<ActionButton> pager(int current, int pages, Consumer<Integer> go) {
        List<ActionButton> buttons = new ArrayList<>();
        if (current > 1) {
            buttons.add(ActionButton.builder(Component.text("Previous", NamedTextColor.WHITE))
                    .width(150)
                    .action(callback((response, audience) -> go.accept(current - 1)))
                    .build());
        }
        if (current < pages) {
            buttons.add(ActionButton.builder(Component.text("Next", NamedTextColor.WHITE))
                    .width(150)
                    .action(callback((response, audience) -> go.accept(current + 1)))
                    .build());
        }
        return buttons;
    }

    private static void addBedrockPager(
            List<BedrockForms.Button> buttons,
            int current,
            int pages,
            Consumer<Integer> go
    ) {
        if (current > 1) {
            buttons.add(new BedrockForms.Button("Previous Page", () -> go.accept(current - 1)));
        }
        if (current < pages) {
            buttons.add(new BedrockForms.Button("Next Page", () -> go.accept(current + 1)));
        }
    }

    private void show(
            Player player, String title, List<DialogBody> body,
            List<ActionButton> buttons, int columns
    ) {
        Screens.show(player, title, body, buttons, columns, null);
    }

    private DialogAction callback(BiConsumer<DialogResponseView, Player> callback) {
        return DialogAction.customClick((response, audience) -> {
            if (audience instanceof Player player && player.isOnline()) {
                callback.accept(response, player);
            }
        }, CALLBACK_OPTIONS);
    }
}
