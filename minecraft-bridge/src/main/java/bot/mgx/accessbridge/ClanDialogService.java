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
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The clan screens that were records wearing item costumes.
 *
 * <p>A roster is names and roles; a donor board is names and amounts. Both were drawn
 * as player heads in a chest, which caps them at a page, gives every entry a tooltip
 * nobody opens, and cannot take a typed number — the donate screen carried a tile
 * reading "Custom amount: use /clans donate &lt;amount&gt;", which is the chest saying
 * outright that it could not do the job.
 *
 * <p>The shop, sell and auction screens are deliberately left alone: there the icon
 * <em>is</em> the product, and an item is the right way to show an item.
 */
final class ClanDialogService {
    private static final ClickCallback.Options CALLBACK_OPTIONS = ClickCallback.Options.builder()
            .uses(ClickCallback.UNLIMITED_USES)
            .lifetime(Duration.ofMinutes(10))
            .build();
    private static final String AMOUNT_INPUT = "amount";
    private static final int PER_PAGE = 12;

    private final ClanStore clans;
    private final ClanMenuService menus;
    private final ClanBattleStore clanBattles;
    private final EconomyStore money;
    private final StatsDialogService stats;
    private final ProfileStatsService profiles;
    private final SettingsClientSupport clientSupport;
    private final BedrockForms forms;

    ClanDialogService(
            ClanStore clans,
            ClanMenuService menus,
            ClanBattleStore clanBattles,
            EconomyStore money,
            StatsDialogService stats,
            ProfileStatsService profiles,
            SettingsClientSupport clientSupport,
            BedrockForms forms
    ) {
        this.clans = clans;
        this.menus = menus;
        this.clanBattles = clanBattles;
        this.money = money;
        this.stats = stats;
        this.profiles = profiles;
        this.clientSupport = clientSupport;
        this.forms = forms;
    }

    // ---------------------------------------------------------------- hub

    void openHub(Player player) {
        ClanStore.ClanView clan = clans.clanOf(player.getUniqueId()).orElse(null);
        if (clan == null) {
            PlayerMenuService.error(player, "You are not in a clan.");
            return;
        }
        List<Entry> entries = List.of(
                new Entry("item/gold_ingot", "Donate", "Give money to the clan.",
                        this::openDonate),
                new Entry("block/gold_block", "Treasury",
                        EconomyFormat.dollars(clan.balance()) + " held.", this::openBalance),
                new Entry("item/diamond", "Donors", "Who has given the most.",
                        this::openDonors),
                new Entry("item/nether_star", "Upgrades", "Spend the treasury on levels.",
                        this::openUpgrade),
                new Entry("item/book", "Clan Info", "Leader, level, allies.",
                        p -> openInfo(p, clan.id(), this::openHub)),
                new Entry("item/iron_chestplate", "Members",
                        clan.members().size() + "/" + clan.memberSlots() + " in the clan.",
                        p -> openMembers(p, clan.id(), 1, this::openHub)),
                new Entry("item/ender_pearl", "Clan Warps", "Places your clan has set.",
                        p -> menus.openWarpsPreferred(p))
        );
        if (!render(player, clan.name(), "Your clan.", entries, 2)) {
            menus.openHub(player);
        }
    }

    // ------------------------------------------------------------- donate

    /** The screen that most needed this: four preset amounts become any amount. */
    void openDonate(Player player) {
        ClanStore.ClanView clan = clans.clanOf(player.getUniqueId()).orElse(null);
        if (clan == null) {
            PlayerMenuService.error(player, "You are not in a clan.");
            return;
        }
        long wallet = money.balance(player.getUniqueId());
        if (!clientSupport.supportsDialogs(player)) {
            if (!forms.prompt(player, "Donate", "Amount (you have "
                    + EconomyFormat.dollars(wallet) + ")", "", typed -> donate(player, typed))) {
                menus.openDonate(player);
            }
            return;
        }
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(MenuText.title("Donate"))
                        .body(List.of(
                                DialogBody.plainMessage(MenuText.stat(
                                        "Your wallet", "item/emerald",
                                        EconomyFormat.dollars(wallet)), 400),
                                DialogBody.plainMessage(MenuText.stat(
                                        "Treasury", "block/gold_block",
                                        EconomyFormat.dollars(clan.balance())), 400),
                                DialogBody.plainMessage(
                                        MenuText.body("Donations cannot be withdrawn."), 400)
                        ))
                        .inputs(List.of(DialogInput.text(AMOUNT_INPUT,
                                        Component.text("Amount", MenuText.LABEL))
                                .maxLength(20)
                                .build()))
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .canCloseWithEscape(true)
                        .build())
                .type(DialogType.confirmation(
                        ActionButton.builder(Component.text("Donate", MenuText.VALUE))
                                .width(150)
                                .action(callback((response, audience) ->
                                        donate(audience, response.getText(AMOUNT_INPUT))))
                                .build(),
                        ActionButton.builder(Component.text("Cancel", MenuText.LABEL))
                                .width(150)
                                .action(callback((response, audience) -> openHub(audience)))
                                .build()
                )));
        player.showDialog(dialog);
    }

    private void donate(Player player, String rawAmount) {
        long amount;
        try {
            amount = EconomyFormat.parseAmount(rawAmount == null ? "" : rawAmount.strip());
        } catch (IllegalArgumentException failure) {
            PlayerMenuService.error(player, "That is not an amount.");
            return;
        }
        try {
            menus.donate(player, amount);
        } catch (IOException | ClanStore.ClanException failure) {
            PlayerMenuService.error(player, failure.getMessage() == null
                    ? "That donation could not be made." : failure.getMessage());
            return;
        }
        openHub(player);
    }

    // ------------------------------------------------------------ balance

    void openBalance(Player player) {
        ClanStore.ClanView clan = clans.clanOf(player.getUniqueId()).orElse(null);
        if (clan == null) {
            return;
        }
        List<Entry> entries = List.of(
                new Entry("item/gold_ingot", "Donate", "Add to the treasury.", this::openDonate),
                new Entry("item/diamond", "Donors", "Who has given the most.", this::openDonors),
                new Entry("item/book", "Back", "Return to the clan.", this::openHub)
        );
        if (!render(player, "Treasury",
                EconomyFormat.dollars(clan.balance()) + " held. Donated money cannot be withdrawn.",
                entries, 2)) {
            menus.openBalance(player);
        }
    }

    // ------------------------------------------------------------- donors

    void openDonors(Player player) {
        ClanStore.ClanView clan = clans.clanOf(player.getUniqueId()).orElse(null);
        if (clan == null) {
            return;
        }
        List<Map.Entry<UUID, Long>> ranked = clan.rankedDonors();
        if (!clientSupport.supportsDialogs(player)) {
            List<BedrockForms.Button> buttons = new ArrayList<>();
            for (int index = 0; index < ranked.size(); index++) {
                Map.Entry<UUID, Long> donor = ranked.get(index);
                buttons.add(new BedrockForms.Button(
                        "#" + (index + 1) + " " + nameOf(clan, donor.getKey())
                                + " - " + EconomyFormat.dollars(donor.getValue()),
                        () -> stats.openCard(player, donor.getKey(),
                                nameOf(clan, donor.getKey()), this::openDonors)
                ));
            }
            buttons.add(new BedrockForms.Button("Back", () -> openHub(player)));
            if (!forms.menu(player, "Donors",
                    ranked.isEmpty() ? "No donations yet." : "Who has given the most.", buttons)) {
                menus.openDonors(player);
            }
            return;
        }
        List<DialogBody> body = new ArrayList<>();
        if (ranked.isEmpty()) {
            body.add(DialogBody.plainMessage(
                    MenuText.body("No donations yet. Be the first to give something."), 400));
        }
        for (int index = 0; index < ranked.size() && index < 10; index++) {
            Map.Entry<UUID, Long> donor = ranked.get(index);
            UUID id = donor.getKey();
            String name = nameOf(clan, id);
            body.add(DialogBody.plainMessage(MenuText.rankedRow(
                    index + 1, id, name,
                    Component.text(EconomyFormat.dollars(donor.getValue()), MenuText.VALUE)
            ).clickEvent(net.kyori.adventure.text.event.ClickEvent.callback(audience -> {
                if (audience instanceof Player clicker && clicker.isOnline()) {
                    stats.openCard(clicker, id, name, this::openDonors);
                }
            }, CALLBACK_OPTIONS)), 400));
        }
        show(player, "Donors", body, List.of(
                ActionButton.builder(Component.text("Back", MenuText.LABEL))
                        .width(150)
                        .action(callback((response, audience) -> openHub(audience)))
                        .build()
        ), 1);
    }

    // ------------------------------------------------------------- members

    void openMembers(Player player, UUID clanId, int page) {
        openMembers(player, clanId, page, viewer -> openInfo(viewer, clanId, null));
    }

    void openMembers(Player player, UUID clanId, int page, Consumer<Player> back) {
        ClanStore.ClanView clan = clans.findClanById(clanId).orElse(null);
        if (clan == null) {
            PlayerMenuService.error(player, "That clan no longer exists.");
            return;
        }
        List<Map.Entry<UUID, String>> roster = new ArrayList<>(clan.members().entrySet());
        roster.sort((left, right) -> {
            int byRole = rank(clan, left.getKey()) - rank(clan, right.getKey());
            return byRole != 0 ? byRole
                    : left.getValue().compareToIgnoreCase(right.getValue());
        });
        int pages = Math.max(1, (roster.size() + PER_PAGE - 1) / PER_PAGE);
        int current = Math.clamp(page, 1, pages);
        int first = (current - 1) * PER_PAGE;
        int last = Math.min(roster.size(), first + PER_PAGE);

        if (!clientSupport.supportsDialogs(player)) {
            List<BedrockForms.Button> buttons = new ArrayList<>();
            for (int index = first; index < last; index++) {
                Map.Entry<UUID, String> member = roster.get(index);
                buttons.add(new BedrockForms.Button(
                        member.getValue() + " - " + roleOf(clan, member.getKey()),
                        () -> openMember(player, clanId, member.getKey(), current, back)
                ));
            }
            buttons.add(new BedrockForms.Button("Back", () -> back.accept(player)));
            if (!forms.menu(player, clan.name() + " Members",
                    roster.size() + "/" + clan.memberSlots() + " in the clan.", buttons)) {
                menus.openMembers(player, clanId, current, null);
            }
            return;
        }
        List<DialogBody> body = new ArrayList<>();
        for (int index = first; index < last; index++) {
            Map.Entry<UUID, String> member = roster.get(index);
            UUID id = member.getKey();
            String name = member.getValue();
            Component line = Component.empty()
                    .append(MenuText.head(id))
                    .append(Component.text(" " + name + "  ", NamedTextColor.WHITE))
                    .append(Component.text(roleOf(clan, id), roleColour(clan, id)))
                    .append(Component.text(
                            Bukkit.getPlayer(id) != null ? "  ·  online" : "  ·  offline",
                            MenuText.LABEL));
            body.add(DialogBody.plainMessage(line.clickEvent(
                    net.kyori.adventure.text.event.ClickEvent.callback(audience -> {
                        if (audience instanceof Player clicker && clicker.isOnline()) {
                            openMember(clicker, clanId, id, current, back);
                        }
                    }, CALLBACK_OPTIONS)
            ), 400));
        }
        List<ActionButton> buttons = new ArrayList<>();
        if (current > 1) {
            buttons.add(button("Previous", audience -> openMembers(audience, clanId, current - 1)));
        }
        if (current < pages) {
            buttons.add(button("Next", audience -> openMembers(audience, clanId, current + 1)));
        }
        buttons.add(button("Back", back));
        show(player, clan.name() + " Members",
                body.isEmpty() ? List.of(DialogBody.plainMessage(
                        MenuText.body("Nobody here."), 400)) : body,
                buttons, 2);
    }

    /**
     * One member, with the actions the viewer is actually allowed to take.
     *
     * <p>The store checks the actor's rank on every one of these, so this only decides
     * what to show; a stale screen cannot be used to promote somebody.
     */
    /**
     * One card for a member: their standing in the clan and their own numbers together.
     *
     * <p>These were two screens, so seeing what somebody had actually done took three
     * clicks through a card that only repeated their role. The full profile board is
     * still one press away for the long tail of statistics.
     */
    private void openMember(
            Player player, UUID clanId, UUID memberId, int page, Consumer<Player> back
    ) {
        ClanStore.ClanView clan = clans.findClanById(clanId).orElse(null);
        if (clan == null) {
            return;
        }
        String name = clan.members().getOrDefault(memberId, "Unknown");
        ProfileStatsService.Profile profile = profiles.of(memberId, name);
        boolean manages = clan.roleOf(player.getUniqueId()) == ClanStore.ClanRole.LEADER;
        boolean self = memberId.equals(player.getUniqueId());
        boolean targetIsLeader = memberId.equals(clan.leader());
        boolean staff = clan.roleOf(memberId) == ClanStore.ClanRole.STAFF;
        long donated = clan.donations().getOrDefault(memberId, 0L);

        List<Entry> entries = new ArrayList<>();
        entries.add(new Entry("item/book", "View Full Profile", "Every number we keep.",
                audience -> stats.openProfile(audience, memberId, name,
                        viewer -> openMember(viewer, clanId, memberId, page, back))));
        if (manages && !self && !targetIsLeader) {
            entries.add(new Entry("item/gold_ingot", staff ? "Demote" : "Promote",
                    staff ? "Make them an ordinary member." : "Make them clan staff.",
                    audience -> setStaff(audience, memberId, !staff, clanId, page, back)));
            entries.add(new Entry("item/barrier", "Kick", "Remove them from the clan.",
                    audience -> {
                        if (!clientSupport.supportsDialogs(audience)) {
                            forms.confirm(audience, "Kick " + name,
                                    "Remove them from the clan?", "Kick", "Cancel",
                                    () -> kick(audience, memberId, clanId, page, back));
                            return;
                        }
                        confirm(audience, "Kick " + name, "They will lose clan access.",
                                () -> kick(audience, memberId, clanId, page, back),
                                viewer -> openMember(viewer, clanId, memberId, page, back));
                    }));
        }
        entries.add(new Entry("item/oak_door", "Back", "Return to the roster.",
                audience -> openMembers(audience, clanId, page, back)));

        if (!clientSupport.supportsDialogs(player)) {
            List<BedrockForms.Button> buttons = new ArrayList<>();
            for (Entry entry : entries) {
                buttons.add(new BedrockForms.Button(
                        entry.label(), () -> entry.action().accept(player)));
            }
            if (!forms.menu(player, name, String.join("\n",
                    roleOf(clan, memberId) + " of " + clan.name(),
                    "Donated: " + EconomyFormat.dollars(donated),
                    Bukkit.getPlayer(memberId) != null ? "Online now" : "Offline",
                    "",
                    "Money: " + EconomyFormat.dollars(profile.money()),
                    "Kills: " + StatsDialogService.compact(profile.playerKills()),
                    "Deaths: " + StatsDialogService.compact(profile.deaths()),
                    "Playtime: " + StatsDialogService.playtime(profile.playTimeTicks())
            ), buttons)) {
                menus.openMembers(player, clanId, page, null);
            }
            return;
        }
        List<DialogBody> body = List.of(
                DialogBody.plainMessage(Component.empty()
                        .append(MenuText.head(memberId))
                        .append(Component.text(" " + name + "  ", NamedTextColor.WHITE))
                        .append(Component.text(roleOf(clan, memberId),
                                roleColour(clan, memberId)))
                        .append(Component.text(
                                Bukkit.getPlayer(memberId) != null ? "  ·  online" : "  ·  offline",
                                MenuText.LABEL)), 400),
                DialogBody.plainMessage(Component.empty(), 400),
                DialogBody.plainMessage(MenuText.stat("Donated", "item/gold_ingot",
                        EconomyFormat.dollars(donated)), 400),
                DialogBody.plainMessage(MenuText.stat("Money", "item/emerald",
                        EconomyFormat.dollars(profile.money())), 400),
                DialogBody.plainMessage(MenuText.stat("Kills", "item/diamond_sword",
                        StatsDialogService.compact(profile.playerKills())), 400),
                DialogBody.plainMessage(MenuText.stat("Deaths", "item/rotten_flesh",
                        StatsDialogService.compact(profile.deaths())), 400),
                DialogBody.plainMessage(MenuText.stat("Playtime", "item/clock_00",
                        StatsDialogService.playtime(profile.playTimeTicks())), 400)
        );
        List<ActionButton> buttons = new ArrayList<>();
        for (Entry entry : entries) {
            buttons.add(ActionButton.builder(Component.empty()
                            .append(MenuText.sprite(entry.sprite()))
                            .append(Component.text(" " + entry.label(), NamedTextColor.WHITE)))
                    .tooltip(Component.text(entry.tooltip(), MenuText.LABEL))
                    .width(150)
                    .action(callback((response, audience) -> entry.action().accept(audience)))
                    .build());
        }
        show(player, name, body, buttons, 2);
    }

    private void setStaff(
            Player player, UUID memberId, boolean promote, UUID clanId, int page,
            Consumer<Player> back
    ) {
        try {
            clans.setStaff(player.getUniqueId(), memberId, promote);
        } catch (IOException | ClanStore.ClanException failure) {
            PlayerMenuService.error(player, failure.getMessage() == null
                    ? "That could not be changed." : failure.getMessage());
            return;
        }
        openMember(player, clanId, memberId, page, back);
    }

    private void kick(
            Player player, UUID memberId, UUID clanId, int page, Consumer<Player> back
    ) {
        try {
            clans.kick(player.getUniqueId(), memberId);
        } catch (IOException | ClanStore.ClanException failure) {
            PlayerMenuService.error(player, failure.getMessage() == null
                    ? "They could not be removed." : failure.getMessage());
            return;
        }
        openMembers(player, clanId, page);
    }

    // ------------------------------------------------------------ upgrades

    /** What the treasury can buy, and what it buys next. */
    void openUpgrade(Player player) {
        ClanStore.ClanView clan = clans.clanOf(player.getUniqueId()).orElse(null);
        if (clan == null) {
            return;
        }
        java.util.Optional<Integer> next = clan.nextLevel();
        List<String> lines = new ArrayList<>();
        lines.add("Level " + (clan.level() == 0 ? "unranked" : clan.level())
                + "  ·  " + ClanLevel.warpSlots(clan.level()) + " warps");
        lines.add("Treasury " + EconomyFormat.dollars(clan.balance()));
        String levelOffer;
        if (next.isEmpty()) {
            levelOffer = "Nothing left to buy.";
        } else {
            ClanLevel.Cost cost = ClanLevel.costOf(next.get()).orElseThrow();
            long short_ = ClanLevel.shortfall(clan.balance(), cost);
            levelOffer = "Level " + next.get() + " costs "
                    + EconomyFormat.dollars(cost.dollars())
                    + (short_ > 0L ? "  (need " + EconomyFormat.dollars(short_) + " more)" : "");
        }
        lines.add(levelOffer);

        List<Entry> entries = new ArrayList<>();
        if (next.isPresent()) {
            entries.add(new Entry("item/nether_star", "Buy Level " + next.get(),
                    levelOffer, audience -> buy(audience, true)));
        }
        entries.add(new Entry("item/iron_chestplate", "Buy Member Slots",
                clan.members().size() + "/" + clan.memberSlots() + " used.",
                audience -> buy(audience, false)));
        entries.add(new Entry("item/oak_door", "Back", "Return to the clan.", this::openHub));
        if (!render(player, "Upgrades", String.join("  |  ", lines), entries, 2)) {
            menus.openUpgrade(player);
        }
    }

    private void buy(Player player, boolean level) {
        try {
            if (level) {
                menus.buyLevelFor(player);
            } else {
                menus.buyMembersFor(player);
            }
        } catch (IOException | ClanStore.ClanException failure) {
            PlayerMenuService.error(player, failure.getMessage() == null
                    ? "That could not be bought." : failure.getMessage());
            openUpgrade(player);
            return;
        }
        // The purchase reopens the upgrade screen itself, so reopening it here as well
        // would draw it twice over.
    }

    // ---------------------------------------------------------------- info

    /** The clan card, for any clan rather than only the viewer's own. */
    void openInfo(Player player, UUID clanId, Consumer<Player> back) {
        ClanStore.ClanView clan = clans.findClanById(clanId).orElse(null);
        if (clan == null) {
            PlayerMenuService.error(player, "That clan no longer exists.");
            return;
        }
        long online = clan.members().keySet().stream()
                .filter(id -> Bukkit.getPlayer(id) != null).count();
        String allies = clan.allyNames().isEmpty()
                ? "None" : String.join(", ", clan.allyNames());
        String medals = ClanTag.plainMedals(clanBattles.badges(clan.id())).strip();

        if (!clientSupport.supportsDialogs(player)) {
            List<BedrockForms.Button> buttons = new ArrayList<>();
            buttons.add(new BedrockForms.Button("Members",
                    () -> openMembers(player, clanId, 1,
                            viewer -> openInfo(viewer, clanId, back))));
            if (back != null) {
                buttons.add(new BedrockForms.Button("Back", () -> back.accept(player)));
            }
            if (!forms.menu(player, clan.name(), String.join("\n",
                    "Leader: " + clan.members().getOrDefault(clan.leader(), "Unknown"),
                    "Level: " + (clan.level() == 0 ? "Unranked" : String.valueOf(clan.level())),
                    medals.isBlank() ? "Battle medals: none" : "Battle medals: " + medals,
                    "Treasury: " + EconomyFormat.dollars(clan.balance()),
                    "Members: " + clan.members().size() + "/" + clan.memberSlots(),
                    "Online: " + online,
                    "Allies: " + allies
            ), buttons)) {
                menus.openInfo(player, clan, null);
            }
            return;
        }
        List<DialogBody> body = List.of(
                DialogBody.plainMessage(Component.empty()
                        .append(MenuText.head(clan.leader()))
                        .append(Component.text(" " + clan.members()
                                .getOrDefault(clan.leader(), "Unknown"), NamedTextColor.WHITE))
                        .append(Component.text("  leader", MenuText.LABEL)), 400),
                DialogBody.plainMessage(Component.empty(), 400),
                DialogBody.plainMessage(MenuText.stat("Level", "item/nether_star",
                        clan.level() == 0 ? "Unranked" : String.valueOf(clan.level())), 400),
                DialogBody.plainMessage(MenuText.stat("Battle medals", "item/gold_ingot",
                        medals.isBlank() ? "none" : medals), 400),
                DialogBody.plainMessage(MenuText.stat("Treasury", "block/gold_block",
                        EconomyFormat.dollars(clan.balance())), 400),
                DialogBody.plainMessage(MenuText.stat("Members", "item/iron_chestplate",
                        clan.members().size() + "/" + clan.memberSlots()
                                + "  (" + online + " online)"), 400),
                DialogBody.plainMessage(MenuText.stat("Allies", "item/iron_chestplate", allies), 400)
        );
        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(button("Members", audience -> openMembers(audience, clanId, 1,
                viewer -> openInfo(viewer, clanId, back))));
        if (back != null) {
            buttons.add(button("Back", back));
        }
        show(player, clan.name(), body, buttons, 2);
    }

    // -------------------------------------------------------------- shared

    private record Entry(String sprite, String label, String tooltip, Consumer<Player> action) {
    }

    /** Draws a list of choices on whichever client the player is using. */
    private boolean render(
            Player player, String title, String body, List<Entry> entries, int columns
    ) {
        if (!clientSupport.supportsDialogs(player)) {
            List<BedrockForms.Button> buttons = new ArrayList<>();
            for (Entry entry : entries) {
                buttons.add(new BedrockForms.Button(
                        entry.label(), () -> entry.action().accept(player)
                ));
            }
            return forms.menu(player, title, body, buttons);
        }
        List<ActionButton> buttons = new ArrayList<>();
        for (Entry entry : entries) {
            buttons.add(ActionButton.builder(Component.empty()
                            .append(MenuText.sprite(entry.sprite()))
                            .append(Component.text(" " + entry.label(), NamedTextColor.WHITE)))
                    .tooltip(Component.text(entry.tooltip(), MenuText.LABEL))
                    .width(150)
                    .action(callback((response, audience) -> entry.action().accept(audience)))
                    .build());
        }
        show(player, title, List.of(DialogBody.plainMessage(MenuText.body(body), 400)),
                buttons, columns);
        return true;
    }

    private void confirm(
            Player player, String title, String body, Runnable onYes, Consumer<Player> onNo
    ) {
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(MenuText.title(title))
                        .body(List.of(DialogBody.plainMessage(
                                Component.text(body, NamedTextColor.RED), 400)))
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .canCloseWithEscape(true)
                        .build())
                .type(DialogType.confirmation(
                        ActionButton.builder(Component.text("Confirm", NamedTextColor.RED))
                                .width(150)
                                .action(callback((response, audience) -> onYes.run()))
                                .build(),
                        ActionButton.builder(Component.text("Cancel", MenuText.LABEL))
                                .width(150)
                                .action(callback((response, audience) -> onNo.accept(audience)))
                                .build()
                )));
        player.showDialog(dialog);
    }

    private void show(
            Player player, String title, List<DialogBody> body,
            List<ActionButton> buttons, int columns
    ) {
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(MenuText.title(title))
                        .body(body)
                        .afterAction(DialogBase.DialogAfterAction.NONE)
                        .pause(false)
                        .canCloseWithEscape(true)
                        .build())
                .type(DialogType.multiAction(buttons).columns(columns).build()));
        player.showDialog(dialog);
    }

    private ActionButton button(String label, Consumer<Player> run) {
        return ActionButton.builder(Component.text(label, NamedTextColor.WHITE))
                .width(150)
                .action(callback((response, audience) -> run.accept(audience)))
                .build();
    }

    private static String nameOf(ClanStore.ClanView clan, UUID id) {
        return clan.members().getOrDefault(id, "Former member");
    }

    private static String roleOf(ClanStore.ClanView clan, UUID id) {
        return switch (clan.roleOf(id)) {
            case LEADER -> "Leader";
            case STAFF -> "Staff";
            case MEMBER -> "Member";
        };
    }

    private static TextColor roleColour(ClanStore.ClanView clan, UUID id) {
        return switch (clan.roleOf(id)) {
            case LEADER -> MenuText.GOLD;
            case STAFF -> MenuText.VALUE;
            case MEMBER -> MenuText.LABEL;
        };
    }

    private static int rank(ClanStore.ClanView clan, UUID id) {
        return switch (clan.roleOf(id)) {
            case LEADER -> 0;
            case STAFF -> 1;
            case MEMBER -> 2;
        };
    }

    private DialogAction callback(BiConsumer<DialogResponseView, Player> callback) {
        return DialogAction.customClick((response, audience) -> {
            if (audience instanceof Player player && player.isOnline()) {
                callback.accept(response, player);
            }
        }, CALLBACK_OPTIONS);
    }
}
