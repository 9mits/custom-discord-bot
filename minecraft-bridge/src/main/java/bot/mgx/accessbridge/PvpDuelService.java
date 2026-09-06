package bot.mgx.accessbridge;

import io.papermc.paper.event.player.AsyncChatEvent;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleCreateEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * Consent-only PvP in a temporary, untouched part of the real overworld.
 *
 * <p>Nothing here creates a kit or a world arena. Both players bring their actual
 * inventory, accept the same terms, and return to the exact place and condition they
 * left. Death keeps inventory and levels. Only the agreed cash and optional held-stack
 * wager move to the winner; ordinary bounty and equipped-cosmetic death transfers are
 * deliberately bypassed by the services that own those systems.
 *
 * <p>Spectators use an anchored Adventure-mode viewing point. Their inventory is
 * stashed to disk, they cannot interact or relay chat, and they never receive free-roam
 * spectator mode. Fighter and spectator recoveries are disk-backed so a Paper crash is
 * resolved as a draw on the next join.
 */
final class PvpDuelService implements CommandExecutor, TabCompleter, Listener {
    private static final TextColor ORANGE = TextColor.color(0xFF9900);
    private static final String REASON_INPUT = "duel_reason";
    private static final String MONEY_INPUT = "duel_money";
    private static final String ITEM_INPUT = "duel_item";
    private static final int BOARD_SIZE = 54;
    private static final int PAGE_SIZE = 45;
    private static final int HUB_START = 11;
    private static final int HUB_INCOMING = 13;
    private static final int HUB_LIVE = 15;
    private static final long NOTICE_COOLDOWN_MILLIS = 2_000L;
    private static final Set<Material> DANGEROUS = Set.of(
            Material.WATER, Material.LAVA, Material.CACTUS, Material.MAGMA_BLOCK,
            Material.POWDER_SNOW, Material.FIRE, Material.SOUL_FIRE,
            Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.SWEET_BERRY_BUSH
    );

    private enum Phase { COUNTDOWN, FIGHTING, ENDING }
    private enum Board { HUB, TARGETS, SETUP, INCOMING, ACCEPT, LIVE }
    private enum Prompt { REASON, MONEY, ITEM }

    private record Invitation(
            UUID id,
            UUID challenger,
            UUID target,
            String reason,
            long moneyEach,
            boolean heldItemEach,
            long expiresAt
    ) {
    }

    private record AcceptedTerms(ItemStack challengerStake, ItemStack targetStake) {
    }

    private record Arena(Location center, Location first, Location second, double diameter) {
    }

    private record PlayerState(PvpDuelStore.Recovery recovery, WorldBorder previousBorder) {
    }

    private static final class Fight {
        final UUID id;
        final UUID first;
        final UUID second;
        final String firstName;
        final String secondName;
        final String reason;
        final long moneyEach;
        final Arena arena;
        final Map<UUID, PlayerState> states;
        final Set<UUID> spectators = new HashSet<>();
        Phase phase = Phase.COUNTDOWN;
        BukkitTask countdownTask;
        BukkitTask timeoutTask;

        Fight(
                UUID id,
                Player first,
                Player second,
                String reason,
                long moneyEach,
                Arena arena,
                Map<UUID, PlayerState> states
        ) {
            this.id = id;
            this.first = first.getUniqueId();
            this.second = second.getUniqueId();
            this.firstName = first.getName();
            this.secondName = second.getName();
            this.reason = reason;
            this.moneyEach = moneyEach;
            this.arena = arena;
            this.states = states;
        }

        UUID opponent(UUID playerId) {
            return first.equals(playerId) ? second : first;
        }

        String label() {
            return firstName + " vs " + secondName;
        }
    }

    private record SpectatorState(Fight fight, PlayerState player, Location anchor) {
    }

    private static final class LegacyDraft {
        final UUID target;
        String reason = "";
        long money;

        LegacyDraft(UUID target) {
            this.target = target;
        }
    }

    private static final class DuelBoard implements InventoryHolder {
        final Board board;
        final UUID subject;
        final Map<Integer, UUID> choices = new HashMap<>();
        ItemStack challengerStake;
        ItemStack targetStake;
        Inventory inventory;

        DuelBoard(Board board, UUID subject) {
            this.board = board;
            this.subject = subject;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final MGXAccessBridge plugin;
    private final EconomyStore economy;
    private final PlayerSettingsStore settings;
    private final SettingsClientSupport clientSupport;
    private final BedrockForms forms;
    private final PvpDuelStore store;
    private final Map<UUID, Invitation> invitations = new LinkedHashMap<>();
    private final Map<UUID, Fight> fights = new LinkedHashMap<>();
    private final Map<UUID, Fight> fighting = new HashMap<>();
    private final Map<UUID, SpectatorState> spectators = new HashMap<>();
    private final Map<UUID, PlayerState> pendingDeathRestore = new HashMap<>();
    private final Set<UUID> starting = new HashSet<>();
    private final Set<UUID> internalTeleports = new HashSet<>();
    private final Map<UUID, LegacyDraft> legacyDrafts = new HashMap<>();
    private final Map<UUID, Prompt> prompts = new HashMap<>();
    private final Map<UUID, Long> notices = new HashMap<>();
    private final Map<UUID, Long> lastChallenges = new HashMap<>();
    private final org.bukkit.NamespacedKey victimKey;

    PvpDuelService(
            MGXAccessBridge plugin,
            EconomyStore economy,
            PlayerSettingsStore settings,
            SettingsClientSupport clientSupport,
            BedrockForms forms,
            java.nio.file.Path recoveryFile
    ) throws IOException {
        this.plugin = plugin;
        this.economy = economy;
        this.settings = settings;
        this.clientSupport = clientSupport;
        this.forms = forms;
        this.store = new PvpDuelStore(recoveryFile);
        this.victimKey = new org.bukkit.NamespacedKey(plugin, "trophy_victim");
        // Money can be repaired while its owner is offline. Items and locations wait
        // for join, but raising to the pre-duel value is idempotent and never removes
        // anything the player earned before recovery ran.
        for (Map.Entry<UUID, PvpDuelStore.Recovery> entry : store.all().entrySet()) {
            long before = entry.getValue().balanceBefore();
            if (economy.balance(entry.getKey()) < before) {
                economy.set(entry.getKey(), before);
            }
        }
    }

    private boolean enabled() {
        return plugin.gameVariables().bool("pvp-duels.enabled");
    }

    private int inviteSeconds() {
        return plugin.gameVariables().integer("pvp-duels.invite-seconds");
    }

    private int countdownSeconds() {
        return plugin.gameVariables().integer("pvp-duels.countdown-seconds");
    }

    private int durationMinutes() {
        return plugin.gameVariables().integer("pvp-duels.duration-minutes");
    }

    private int arenaDiameter() {
        return plugin.gameVariables().integer("pvp-duels.arena-diameter");
    }

    private int maximumWager() {
        return plugin.gameVariables().integer("pvp-duels.maximum-money-wager");
    }

    private int challengeCooldownSeconds() {
        return plugin.gameVariables().integer("pvp-duels.challenge-cooldown-seconds");
    }

    boolean isFighter(UUID playerId) {
        return fighting.containsKey(playerId);
    }

    boolean isParticipant(UUID playerId) {
        return isFighter(playerId) || spectators.containsKey(playerId);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        args = CommandArgs.withoutEchoedSender(sender.getName(), args);
        if (!(sender instanceof Player player)) {
            sender.sendMessage("PvP duels are available to players only.");
            return true;
        }
        if (store.find(player.getUniqueId()).isPresent() && !isParticipant(player.getUniqueId())) {
            recover(player, true);
            return true;
        }
        if (isFighter(player.getUniqueId())) {
            if (args.length > 0 && args[0].equalsIgnoreCase("forfeit")) {
                forfeit(player);
            } else {
                openFightStatus(player, fighting.get(player.getUniqueId()));
            }
            return true;
        }
        if (spectators.containsKey(player.getUniqueId())) {
            if (args.length == 0 || args[0].equalsIgnoreCase("leave")) {
                leaveSpectator(player, true);
            } else {
                error(player, "Use /pvp leave to return to where you were.");
            }
            return true;
        }
        if (args.length == 0) {
            openHub(player);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "accept" -> acceptByName(player, args.length >= 2 ? args[1] : "");
            case "decline", "deny" -> declineByName(player, args.length >= 2 ? args[1] : "");
            case "spectate", "watch" -> {
                if (args.length >= 2) {
                    spectateByName(player, args[1]);
                } else {
                    openLive(player);
                }
            }
            case "challenge", "fight" -> {
                Player target = args.length >= 2 ? Bukkit.getPlayerExact(args[1]) : null;
                if (target == null) {
                    error(player, "Choose an online player from /pvp.");
                } else {
                    openSetup(player, target);
                }
            }
            case "leave" -> error(player, "You are not watching a duel.");
            case "forfeit" -> error(player, "You are not in a duel.");
            default -> {
                Player target = Bukkit.getPlayerExact(args[0]);
                if (target == null) {
                    error(player, "Use /pvp and choose an online player.");
                } else {
                    openSetup(player, target);
                }
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender, Command command, String alias, String[] args
    ) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return List.of("challenge", "accept", "decline", "spectate", "leave", "forfeit")
                    .stream().filter(option -> option.startsWith(prefix)).toList();
        }
        if (args.length == 2 && List.of("challenge", "accept", "decline", "spectate")
                .contains(args[0].toLowerCase(Locale.ROOT))) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .sorted(String.CASE_INSENSITIVE_ORDER).toList();
        }
        return List.of();
    }

    private void openHub(Player player) {
        expireInvitations();
        int incoming = incoming(player.getUniqueId()).size();
        if (!clientSupport.supportsDialogs(player)) {
            List<BedrockForms.Button> buttons = List.of(
                    new BedrockForms.Button("Start a Duel", () -> openTargets(player)),
                    new BedrockForms.Button("Incoming (" + incoming + ")", () -> openIncoming(player)),
                    new BedrockForms.Button("Watch Live Duels (" + fights.size() + ")", () -> openLive(player))
            );
            if (!forms.menu(player, "Safe PvP", hubBody(), buttons)) {
                openChestHub(player);
            }
            return;
        }
        List<ActionButton> buttons = List.of(
                Screens.button("item/diamond_sword", "Start a Duel",
                        "Choose an online player and set the terms.", this::openTargets),
                Screens.button("item/writable_book", "Incoming (" + incoming + ")",
                        "Review, accept, or decline your challenges.", this::openIncoming),
                Screens.button("item/spyglass", "Watch Live Duels (" + fights.size() + ")",
                        "Watch from a fixed, inventory-safe viewing point.", this::openLive)
        );
        Screens.show(player, "Safe PvP", Screens.body(hubBody()), buttons, 1, null);
    }

    private String hubBody() {
        return "PvP happens here by consent, away from bases and peaceful players.\n"
                + "KEEP INVENTORY is always on. Bring only your own gear—there are no kits.\n"
                + "Both players return to their original locations when the fight ends.";
    }

    private void openTargets(Player player) {
        List<Player> targets = targets(player);
        if (!clientSupport.supportsDialogs(player)) {
            List<BedrockForms.Button> buttons = targets.stream()
                    .map(target -> new BedrockForms.Button(target.getName(),
                            () -> openSetup(player, target)))
                    .toList();
            if (!forms.menu(player, "Choose an Opponent", "Every duel requires acceptance.",
                    buttons, this::openHub)) {
                openChestList(player, Board.TARGETS, targets, "Choose an Opponent");
            }
            return;
        }
        List<ActionButton> buttons = new ArrayList<>();
        for (Player target : targets) {
            buttons.add(Screens.button(null, target.getName(),
                    "Set fair terms for this player.", viewer -> {
                        Player current = Bukkit.getPlayer(target.getUniqueId());
                        if (current == null) {
                            error(viewer, "They went offline.");
                        } else {
                            openSetup(viewer, current);
                        }
                    }));
        }
        Screens.show(player, "Choose an Opponent",
                Screens.body(targets.isEmpty()
                        ? "Nobody available is online right now."
                        : "Choose who you personally want to fight."),
                buttons, 2, this::openHub);
    }

    private void openSetup(Player challenger, Player target) {
        if (!canChallenge(challenger, target, true)
                || !acceptingChallenges(challenger, target, true)) {
            return;
        }
        if (!clientSupport.supportsDialogs(challenger)) {
            if (forms.twoTextsAndToggle(
                    challenger, "Duel " + target.getName(),
                    "Reason or lore", "Friendly fight",
                    "Money staked by EACH player", "0",
                    "Each player also stakes their held stack", false,
                    result -> sendChallenge(
                            challenger, target.getUniqueId(), result.first(), result.second(),
                            result.toggled()
                    ), () -> openTargets(challenger))) {
                return;
            }
            openChestSetup(challenger, target);
            return;
        }
        List<DialogInput> inputs = List.of(
                DialogInput.text(REASON_INPUT, Component.text("Reason or lore", MenuText.LABEL))
                        .initial("Friendly fight")
                        .maxLength(PvpDuelRules.MAX_REASON_LENGTH)
                        .build(),
                DialogInput.text(MONEY_INPUT,
                                Component.text("Money staked by EACH player", MenuText.LABEL))
                        .initial("0")
                        .maxLength(16)
                        .build(),
                DialogInput.bool(ITEM_INPUT,
                                Component.text("Each stakes their held stack", MenuText.LABEL))
                        .initial(false)
                        .onTrue("Yes")
                        .onFalse("No")
                        .build()
        );
        ActionButton send = ActionButton.builder(MenuText.buttonLabel("Send Challenge", ORANGE))
                .tooltip(MenuText.actionHint("They must review and accept these exact terms."))
                .width(150)
                .action(Screens.callback((response, player) -> sendChallenge(
                        player,
                        target.getUniqueId(),
                        response.getText(REASON_INPUT),
                        response.getText(MONEY_INPUT),
                        Boolean.TRUE.equals(response.getBoolean(ITEM_INPUT))
                )))
                .build();
        Screens.show(challenger, "Duel " + target.getName(), Screens.body(
                "KEEP INVENTORY • no kits • your real gear • no world damage\n"
                        + "A held-stack wager can be any item, including a physical cosmetic token.\n"
                        + "The winner gets both stakes and the loser's trophy head."
        ), inputs, List.of(send), 1, this::openTargets);
    }

    private void sendChallenge(
            Player challenger, UUID targetId, String rawReason, String rawMoney, boolean heldItem
    ) {
        Player target = Bukkit.getPlayer(targetId);
        if (target == null || !canChallenge(challenger, target, true)
                || !acceptingChallenges(challenger, target, true)) {
            return;
        }
        long now = System.currentTimeMillis();
        long cooldown = challengeCooldownSeconds() * 1_000L;
        long remaining = cooldown - (now - lastChallenges.getOrDefault(
                challenger.getUniqueId(), 0L));
        if (remaining > 0L) {
            error(challenger, "Wait " + ((remaining + 999L) / 1_000L)
                    + " seconds before sending another challenge.");
            return;
        }
        String reason = PvpDuelRules.cleanReason(rawReason);
        if (!PvpDuelRules.validReason(reason)) {
            error(challenger, "Give a 3–80 character reason or lore for this duel.");
            openSetup(challenger, target);
            return;
        }
        long money;
        try {
            String value = rawMoney == null ? "" : rawMoney.strip();
            money = value.isEmpty() || value.equals("0") ? 0L : EconomyFormat.parseAmount(value);
        } catch (IllegalArgumentException exception) {
            error(challenger, exception.getMessage());
            openSetup(challenger, target);
            return;
        }
        if (!PvpDuelRules.validMoney(money, maximumWager())) {
            error(challenger, "A duel wager cannot exceed "
                    + EconomyFormat.dollars(maximumWager()) + " per player.");
            openSetup(challenger, target);
            return;
        }
        if (economy.balance(challenger.getUniqueId()) < money) {
            error(challenger, "You do not currently have " + EconomyFormat.dollars(money) + ".");
            return;
        }
        if (heldItem && empty(challenger.getInventory().getItemInMainHand())) {
            error(challenger, "Hold the stack you intend to wager, then send the challenge.");
            return;
        }
        invitations.values().removeIf(invitation -> invitation.challenger().equals(
                challenger.getUniqueId()));
        Invitation invitation = new Invitation(
                UUID.randomUUID(), challenger.getUniqueId(), targetId, reason, money,
                heldItem, System.currentTimeMillis() + inviteSeconds() * 1000L
        );
        invitations.put(invitation.id(), invitation);
        lastChallenges.put(challenger.getUniqueId(), now);
        challenger.closeDialog();
        info(challenger, "Challenge sent to " + target.getName() + ". Nothing is charged until acceptance.");
        target.sendMessage(prefix()
                .append(Component.text(challenger.getName(), NamedTextColor.GOLD))
                .append(Component.text(" challenged you to safe PvP. ", NamedTextColor.WHITE))
                .append(Component.text("Review", ORANGE, TextDecoration.BOLD)
                        .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand(
                                "/pvp accept " + challenger.getName())))
                .append(Component.text(" or use /pvp.", NamedTextColor.GRAY))
        );
    }

    private void openIncoming(Player player) {
        expireInvitations();
        List<Invitation> incoming = incoming(player.getUniqueId());
        if (!clientSupport.supportsDialogs(player)) {
            List<BedrockForms.Button> buttons = new ArrayList<>();
            for (Invitation invitation : incoming) {
                buttons.add(new BedrockForms.Button(name(invitation.challenger()),
                        () -> openInvitation(player, invitation)));
            }
            if (!forms.menu(player, "Incoming Duels",
                    incoming.isEmpty() ? "No challenges are waiting." : "Review exact terms before accepting.",
                    buttons, this::openHub)) {
                openChestInvitations(player, incoming);
            }
            return;
        }
        List<ActionButton> buttons = new ArrayList<>();
        for (Invitation invitation : incoming) {
            buttons.add(Screens.button(null, name(invitation.challenger()),
                    invitation.reason(), viewer -> openInvitation(viewer, invitation)));
        }
        Screens.show(player, "Incoming Duels", Screens.body(incoming.isEmpty()
                        ? "No challenges are waiting."
                        : "Review the reason, money, and item terms before accepting."),
                buttons, 2, this::openHub);
    }

    private void openInvitation(Player target, Invitation invitation) {
        if (!validInvitation(target, invitation, true)) {
            return;
        }
        Player challenger = Bukkit.getPlayer(invitation.challenger());
        if (challenger == null) {
            invitations.remove(invitation.id());
            error(target, "The challenger went offline.");
            return;
        }
        ItemStack challengerStake = invitation.heldItemEach()
                ? cloneOrNull(challenger.getInventory().getItemInMainHand()) : null;
        ItemStack targetStake = invitation.heldItemEach()
                ? cloneOrNull(target.getInventory().getItemInMainHand()) : null;
        String body = terms(invitation, challenger, challengerStake, target, targetStake);
        if (!clientSupport.supportsDialogs(target)) {
            if (!forms.confirm(target, "Accept Safe Duel?", body, "Accept & Fight",
                    () -> accept(target, invitation, new AcceptedTerms(challengerStake, targetStake)),
                    () -> openIncoming(target))) {
                openChestAccept(target, invitation, challengerStake, targetStake);
            }
            return;
        }
        Screens.confirm(target, "Accept Safe Duel?", Screens.body(body), "Accept & Fight",
                NamedTextColor.GREEN,
                viewer -> accept(viewer, invitation, new AcceptedTerms(challengerStake, targetStake)),
                this::openIncoming);
    }

    private String terms(
            Invitation invitation,
            Player challenger,
            ItemStack challengerStake,
            Player target,
            ItemStack targetStake
    ) {
        StringBuilder body = new StringBuilder();
        body.append(challenger.getName()).append(" vs ").append(target.getName()).append('\n');
        body.append("Reason: ").append(invitation.reason()).append('\n');
        body.append("Cash: ").append(EconomyFormat.dollars(invitation.moneyEach()))
                .append(" EACH").append('\n');
        if (invitation.heldItemEach()) {
            body.append(challenger.getName()).append(" holds: ")
                    .append(itemName(challengerStake)).append('\n');
            body.append(target.getName()).append(" holds: ")
                    .append(itemName(targetStake)).append('\n');
        } else {
            body.append("Held-item stake: none\n");
        }
        body.append("KEEP INVENTORY is guaranteed. No kits. No block damage.\n");
        body.append("You return to your exact starting location when it ends.");
        return body.toString();
    }

    private void accept(Player target, Invitation invitation, AcceptedTerms terms) {
        if (!validInvitation(target, invitation, true)) {
            return;
        }
        Player challenger = Bukkit.getPlayer(invitation.challenger());
        if (challenger == null || !canChallenge(challenger, target, true)) {
            invitations.remove(invitation.id());
            return;
        }
        if (invitation.heldItemEach()
                && (empty(terms.challengerStake()) || empty(terms.targetStake()))) {
            error(target, "Both players must hold the stack they are staking.");
            return;
        }
        if (!sameCurrentStack(challenger, terms.challengerStake())
                || !sameCurrentStack(target, terms.targetStake())) {
            error(target, "A held stake changed. Review the updated terms before accepting.");
            openInvitation(target, invitation);
            return;
        }
        invitations.remove(invitation.id());
        starting.add(challenger.getUniqueId());
        starting.add(target.getUniqueId());
        challenger.closeDialog();
        target.closeDialog();
        info(challenger, target.getName() + " accepted. Finding untouched ground...");
        info(target, "Finding untouched ground for your duel...");
        World world = overworld();
        if (world == null) {
            failStart(challenger, target, "The overworld is unavailable.");
            return;
        }
        findArena(world, challenger, target, arenaDiameter(), 0, arena -> beginFight(
                challenger, target, invitation, terms, arena
        ));
    }

    private void findArena(
            World world, Player firstPlayer, Player secondPlayer,
            int diameter, int attempted, Consumer<Arena> found
    ) {
        int attempts = plugin.gameVariables().integer("pvp-duels.location-attempts");
        if (attempted >= attempts) {
            failStart(firstPlayer, secondPlayer,
                    "No untouched safe arena was found. Try again shortly.");
            return;
        }
        Candidate candidate = candidate(world, diameter);
        if (candidate == null) {
            findArena(world, firstPlayer, secondPlayer, diameter, attempted + 1, found);
            return;
        }
        int separation = Math.max(8, Math.min(20, diameter / 4));
        int x1 = candidate.x() - separation;
        int x2 = candidate.x() + separation;
        int z = candidate.z();
        CompletableFuture<Chunk> center = world.getChunkAtAsync(candidate.x() >> 4, z >> 4, true);
        CompletableFuture<Chunk> first = world.getChunkAtAsync(x1 >> 4, z >> 4, true);
        CompletableFuture<Chunk> second = world.getChunkAtAsync(x2 >> 4, z >> 4, true);
        CompletableFuture.allOf(center, first, second).whenComplete((ignored, failure) ->
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (failure != null) {
                        findArena(world, firstPlayer, secondPlayer, diameter, attempted + 1, found);
                        return;
                    }
                    Location one = safeLocation(world, x1, z, 90f);
                    Location two = safeLocation(world, x2, z, -90f);
                    Location middle = safeLocation(world, candidate.x(), z, 0f);
                    if (one == null || two == null || middle == null
                            || center.join().getInhabitedTime() > 0L
                            || first.join().getInhabitedTime() > 0L
                            || second.join().getInhabitedTime() > 0L) {
                        findArena(world, firstPlayer, secondPlayer, diameter, attempted + 1, found);
                        return;
                    }
                    found.accept(new Arena(middle, one, two, diameter));
                })
        );
    }

    private record Candidate(int x, int z) {
    }

    private Candidate candidate(World world, int diameter) {
        WorldBorder border = world.getWorldBorder();
        double edge = Math.max(64d, diameter / 2d + 32d);
        double borderRadius = Math.max(0d, border.getSize() / 2d - edge);
        double minimum = plugin.gameVariables().integer("pvp-duels.minimum-radius");
        double maximum = Math.min(
                plugin.gameVariables().integer("pvp-duels.maximum-radius"), borderRadius
        );
        if (maximum <= minimum) {
            return null;
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int sample = 0; sample < 12; sample++) {
            double radius = PvpDuelRules.radius(minimum, maximum, random.nextDouble());
            double angle = random.nextDouble(0d, Math.PI * 2d);
            int x = (int) Math.floor(border.getCenter().getX() + Math.cos(angle) * radius);
            int z = (int) Math.floor(border.getCenter().getZ() + Math.sin(angle) * radius);
            if (!separatedFromFights(world, x, z, diameter * 3d)
                    || !untouched(world, x, z, diameter)) {
                continue;
            }
            return new Candidate(x, z);
        }
        return null;
    }

    /** Every chunk the arena can reach must be new, not merely quiet right now. */
    private static boolean untouched(World world, int x, int z, double diameter) {
        int chunkRadius = Math.max(1, (int) Math.ceil(diameter / 32d) + 1);
        int centerX = x >> 4;
        int centerZ = z >> 4;
        for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
            for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                if (world.isChunkGenerated(centerX + dx, centerZ + dz)) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean separatedFromFights(World world, int x, int z, double distance) {
        for (Fight fight : fights.values()) {
            Location center = fight.arena.center();
            if (center.getWorld() != null && center.getWorld().equals(world)
                    && !PvpDuelRules.separated(x, z, center.getX(), center.getZ(), distance)) {
                return false;
            }
        }
        return true;
    }

    private static Location safeLocation(World world, int x, int z, float yaw) {
        int y = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        if (y <= world.getMinHeight() || y + 2 >= world.getMaxHeight()) {
            return null;
        }
        Block floor = world.getBlockAt(x, y, z);
        Block feet = world.getBlockAt(x, y + 1, z);
        Block head = world.getBlockAt(x, y + 2, z);
        if (!floor.getType().isSolid() || DANGEROUS.contains(floor.getType())
                || !feet.isPassable() || !head.isPassable()) {
            return null;
        }
        return new Location(world, x + 0.5d, y + 1d, z + 0.5d, yaw, 0f);
    }

    private void beginFight(
            Player first,
            Player second,
            Invitation invitation,
            AcceptedTerms terms,
            Arena arena
    ) {
        if (!starting.contains(first.getUniqueId()) || !starting.contains(second.getUniqueId())
                || !readyToStart(first, second)
                || !sameCurrentStack(first, terms.challengerStake())
                || !sameCurrentStack(second, terms.targetStake())) {
            failStart(first, second, "The duel state or a held wager changed before teleport.");
            return;
        }
        long wager = invitation.moneyEach();
        if (economy.balance(first.getUniqueId()) < wager
                || economy.balance(second.getUniqueId()) < wager) {
            failStart(first, second, "Both players must still be able to cover the cash wager.");
            return;
        }
        if (!PvpDuelRules.canReceivePool(economy.balance(first.getUniqueId()), wager)
                || !PvpDuelRules.canReceivePool(economy.balance(second.getUniqueId()), wager)) {
            failStart(first, second, "A wallet is too close to its limit for this prize pool.");
            return;
        }
        UUID fightId = UUID.randomUUID();
        PvpDuelStore.Recovery firstRecovery = recovery(
                fightId, PvpDuelStore.Role.FIGHTER, first, terms.challengerStake(), false
        );
        PvpDuelStore.Recovery secondRecovery = recovery(
                fightId, PvpDuelStore.Role.FIGHTER, second, terms.targetStake(), false
        );
        try {
            store.putAll(Map.of(
                    first.getUniqueId(), firstRecovery,
                    second.getUniqueId(), secondRecovery
            ));
            takeHeldStake(first, terms.challengerStake());
            takeHeldStake(second, terms.targetStake());
            if (wager > 0L && (!economy.tryWithdraw(first.getUniqueId(), wager)
                    || !economy.tryWithdraw(second.getUniqueId(), wager))) {
                throw new IllegalStateException("A wager balance changed while the duel was starting.");
            }
        } catch (RuntimeException failure) {
            restoreStartFailure(first, firstRecovery);
            restoreStartFailure(second, secondRecovery);
            safeRemoveRecovery(first.getUniqueId());
            safeRemoveRecovery(second.getUniqueId());
            failStart(first, second, failure.getMessage() == null
                    ? "The duel could not safely lock its stakes." : failure.getMessage());
            return;
        }
        Map<UUID, PlayerState> states = new LinkedHashMap<>();
        states.put(first.getUniqueId(), new PlayerState(firstRecovery, first.getWorldBorder()));
        states.put(second.getUniqueId(), new PlayerState(secondRecovery, second.getWorldBorder()));
        Fight fight = new Fight(
                fightId, first, second, invitation.reason(), wager, arena, Map.copyOf(states)
        );
        fights.put(fight.id, fight);
        fighting.put(fight.first, fight);
        fighting.put(fight.second, fight);
        starting.remove(fight.first);
        starting.remove(fight.second);
        boolean firstMoved = prepareFighter(first, fight, arena.first());
        boolean secondMoved = prepareFighter(second, fight, arena.second());
        if (!firstMoved || !secondMoved) {
            endFight(fight, null, "A protected teleport was refused — stakes returned");
            return;
        }
        record("duel_started", first, fight.label() + " began a safe PvP duel")
                .detail("opponent", second.getName())
                .detail("reason", fight.reason)
                .detail("money_each", String.valueOf(fight.moneyEach))
                .record();
        tickCountdown(fight, countdownSeconds());
    }

    private boolean prepareFighter(Player player, Fight fight, Location start) {
        player.closeInventory();
        player.closeDialog();
        player.setGameMode(GameMode.SURVIVAL);
        player.setAllowFlight(false);
        player.setFlying(false);
        player.setInvulnerable(true);
        player.setFireTicks(0);
        player.setFallDistance(0f);
        player.setFoodLevel(20);
        player.setSaturation(20f);
        if (player.getAttribute(Attribute.MAX_HEALTH) != null) {
            player.setHealth(player.getAttribute(Attribute.MAX_HEALTH).getValue());
        }
        player.setWorldBorder(personalBorder(fight.arena));
        boolean moved = teleport(player, start);
        player.sendMessage(prefix().append(Component.text(
                "KEEP INVENTORY is active. Fight only your opponent; /pvp forfeit ends it.",
                NamedTextColor.GREEN
        )));
        return moved;
    }

    private WorldBorder personalBorder(Arena arena) {
        WorldBorder border = Bukkit.createWorldBorder();
        border.setCenter(arena.center());
        border.setSize(arena.diameter());
        border.setWarningDistance(5);
        border.setDamageBuffer(0d);
        border.setDamageAmount(2d);
        return border;
    }

    private void tickCountdown(Fight fight, int remaining) {
        if (fight.phase != Phase.COUNTDOWN) {
            return;
        }
        if (remaining <= 0) {
            fight.phase = Phase.FIGHTING;
            for (UUID playerId : List.of(fight.first, fight.second)) {
                Player player = Bukkit.getPlayer(playerId);
                if (player != null) {
                    player.setInvulnerable(false);
                    player.showTitle(Title.title(
                            Component.text("FIGHT!", NamedTextColor.RED, TextDecoration.BOLD),
                            Component.text("KEEP INVENTORY", NamedTextColor.GREEN),
                            Title.Times.times(Duration.ZERO, Duration.ofSeconds(1), Duration.ofMillis(300))
                    ));
                    player.playSound(player, Sound.ENTITY_ENDER_DRAGON_GROWL, 0.6f, 1.4f);
                }
            }
            fight.timeoutTask = plugin.getServer().getScheduler().runTaskLater(
                    plugin, () -> endFight(fight, null, "Time limit reached — draw"),
                    durationMinutes() * 60L * 20L
            );
            return;
        }
        for (UUID playerId : List.of(fight.first, fight.second)) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                player.showTitle(Title.title(
                        Component.text(String.valueOf(remaining), ORANGE, TextDecoration.BOLD),
                        Component.text("KEEP INVENTORY • no world damage", NamedTextColor.GREEN),
                        Title.Times.times(Duration.ZERO, Duration.ofMillis(800), Duration.ofMillis(100))
                ));
                player.playSound(player, Sound.BLOCK_NOTE_BLOCK_HAT, 0.8f, 1.1f);
            }
        }
        fight.countdownTask = plugin.getServer().getScheduler().runTaskLater(
                plugin, () -> tickCountdown(fight, remaining - 1), 20L
        );
    }

    private void openFightStatus(Player player, Fight fight) {
        if (fight == null) {
            return;
        }
        String body = fight.label() + "\nReason: " + fight.reason + "\n"
                + "KEEP INVENTORY is active. Leaving counts as a forfeit.";
        if (!clientSupport.supportsDialogs(player)) {
            forms.confirm(player, "Duel in Progress", body, "Forfeit", () -> forfeit(player));
            return;
        }
        Screens.confirm(player, "Duel in Progress", Screens.body(body), "Forfeit",
                NamedTextColor.RED, this::forfeit, Player::closeDialog);
    }

    private void forfeit(Player player) {
        Fight fight = fighting.get(player.getUniqueId());
        if (fight == null) {
            error(player, "You are not in a duel.");
            return;
        }
        endFight(fight, fight.opponent(player.getUniqueId()), player.getName() + " forfeited");
    }

    private void endFight(Fight fight, UUID winnerId, String result) {
        if (fight == null || fight.phase == Phase.ENDING) {
            return;
        }
        fight.phase = Phase.ENDING;
        cancel(fight.countdownTask);
        cancel(fight.timeoutTask);
        Player first = Bukkit.getPlayer(fight.first);
        Player second = Bukkit.getPlayer(fight.second);
        // CombatLog is allowed to protect ordinary fights, but it must not cancel the
        // exact return teleport promised by a completed duel.
        clearCombatLog(first, second);
        Player winner = winnerId == null ? null : Bukkit.getPlayer(winnerId);

        // A named winner must still be present to receive physical custody. Falling
        // back to a draw is safer than clearing escrow for an offline inventory.
        if (winnerId != null && winner == null) {
            winnerId = null;
            result = "Winner disconnected — stakes returned as a draw";
        }

        try {
            settleMoney(fight, winnerId);
        } catch (RuntimeException failure) {
            plugin.getLogger().severe("Could not settle duel " + fight.id + ": " + failure.getMessage());
            // Safer than inventing a winner when custody could not be saved.
            winnerId = null;
            winner = null;
            result = "Settlement failed — stakes returned as a draw";
            refundMoney(fight);
        }

        for (UUID spectatorId : List.copyOf(fight.spectators)) {
            Player viewer = Bukkit.getPlayer(spectatorId);
            if (viewer != null) {
                leaveSpectator(viewer, false);
                info(viewer, result + ". " + fight.label() + " is over.");
            }
        }

        fighting.remove(fight.first);
        fighting.remove(fight.second);
        fights.remove(fight.id);
        restoreAtEnd(first, fight.states.get(fight.first));
        restoreAtEnd(second, fight.states.get(fight.second));

        Set<UUID> receivedPhysicalCustody = new HashSet<>();
        if (winnerId == null) {
            returnStake(first, fight.states.get(fight.first));
            returnStake(second, fight.states.get(fight.second));
            if (first != null) {
                receivedPhysicalCustody.add(fight.first);
                first.saveData();
            }
            if (second != null) {
                receivedPhysicalCustody.add(fight.second);
                second.saveData();
            }
        } else if (winner != null) {
            returnStake(winner, fight.states.get(fight.first));
            returnStake(winner, fight.states.get(fight.second));
            UUID loserId = winnerId.equals(fight.first) ? fight.second : fight.first;
            String loserName = winnerId.equals(fight.first) ? fight.secondName : fight.firstName;
            giveSafely(winner, duelHead(loserId, loserName));
            receivedPhysicalCustody.add(fight.first);
            receivedPhysicalCustody.add(fight.second);
            winner.saveData();
        }

        // Clear durable escrow only after physical prizes have reached an online
        // inventory and that player data has been flushed. If Paper stops earlier,
        // recovery favours returning an item over silently destroying it.
        for (UUID playerId : List.of(fight.first, fight.second)) {
            PvpDuelStore.Recovery recovery = fight.states.get(playerId).recovery();
            if (!recovery.encodedStake().isEmpty() && !receivedPhysicalCustody.contains(playerId)) {
                continue;
            }
            try {
                store.settle(playerId, economy.balance(playerId));
            } catch (RuntimeException failure) {
                plugin.getLogger().severe("Could not close duel escrow for " + playerId
                        + ": " + failure.getMessage());
                continue;
            }
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && !player.isDead()) {
                safeRemoveRecovery(playerId);
            }
        }
        announceFightEnd(fight, winner, result);
        if (winner != null) {
            record("duel_finished", winner, winner.getName() + " won a safe PvP duel")
                    .detail("opponent", winnerId.equals(fight.first)
                            ? fight.secondName : fight.firstName)
                    .detail("reason", fight.reason)
                    .detail("result", result)
                    .record();
        }
    }

    private void settleMoney(Fight fight, UUID winnerId) {
        if (fight.moneyEach <= 0L) {
            return;
        }
        if (winnerId == null) {
            refundMoney(fight);
            return;
        }
        long pool = Math.multiplyExact(fight.moneyEach, 2L);
        economy.deposit(winnerId, pool);
    }

    private void refundMoney(Fight fight) {
        for (UUID playerId : List.of(fight.first, fight.second)) {
            long before = fight.states.get(playerId).recovery().balanceBefore();
            if (economy.balance(playerId) < before) {
                economy.set(playerId, before);
            }
        }
    }

    private void restoreAtEnd(Player player, PlayerState state) {
        if (player == null || state == null) {
            return;
        }
        if (player.isDead()) {
            pendingDeathRestore.put(player.getUniqueId(), state);
            return;
        }
        restorePlayer(player, state);
    }

    private void announceFightEnd(Fight fight, Player winner, String result) {
        Component message = prefix().append(winner == null
                ? Component.text(fight.label() + " ended in a draw. " + result + ".",
                        NamedTextColor.YELLOW)
                : Component.text(winner.getName() + " won " + fight.label()
                        + ". " + result + ".", NamedTextColor.GOLD));
        for (UUID playerId : List.of(fight.first, fight.second)) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                player.sendMessage(message);
            }
        }
    }

    private void openLive(Player player) {
        List<Fight> live = fights.values().stream()
                .filter(fight -> fight.phase != Phase.ENDING)
                .toList();
        if (!clientSupport.supportsDialogs(player)) {
            List<BedrockForms.Button> buttons = live.stream()
                    .map(fight -> new BedrockForms.Button(fight.label(),
                            () -> joinSpectator(player, fight)))
                    .toList();
            if (!forms.menu(player, "Live Duels",
                    live.isEmpty() ? "No duel is live." : "Viewing is fixed in place and inventory-safe.",
                    buttons, this::openHub)) {
                openChestFights(player, live);
            }
            return;
        }
        List<ActionButton> buttons = new ArrayList<>();
        for (Fight fight : live) {
            buttons.add(Screens.button(null, fight.label(), fight.reason,
                    viewer -> joinSpectator(viewer, fight)));
        }
        Screens.show(player, "Live Duels", Screens.body(live.isEmpty()
                        ? "No duel is live right now."
                        : "Watch from an anchored stand. No items, interaction, free roam, or chat."),
                buttons, 2, this::openHub);
    }

    private void joinSpectator(Player player, Fight fight) {
        if (fight == null || fight.phase == Phase.ENDING || !fights.containsKey(fight.id)) {
            error(player, "That duel has ended.");
            return;
        }
        if (VerificationLobbyService.isLobbyWorld(player.getWorld())) {
            error(player, "Finish verification before watching a duel.");
            return;
        }
        if (plugin.inScreenshotMode(player)) {
            error(player, "Close screenshot mode before watching a duel.");
            return;
        }
        if (isParticipant(player.getUniqueId()) || starting.contains(player.getUniqueId())) {
            error(player, "You are already busy with a duel.");
            return;
        }
        if (fight.spectators.size() >= plugin.gameVariables().integer("pvp-duels.maximum-spectators")) {
            error(player, "That viewing stand is full.");
            return;
        }
        Location anchor = fight.arena.center().clone().add(0d,
                Math.max(8d, Math.max(
                        fight.arena.first().getY() - fight.arena.center().getY(),
                        fight.arena.second().getY() - fight.arena.center().getY()
                ) + 8d), 0d);
        PvpDuelStore.Recovery recovery = recovery(
                fight.id, PvpDuelStore.Role.SPECTATOR, player, null, true
        );
        PlayerState state = new PlayerState(recovery, player.getWorldBorder());
        try {
            store.putAll(Map.of(player.getUniqueId(), recovery));
        } catch (UncheckedIOException failure) {
            error(player, "Your inventory could not be protected for viewing.");
            return;
        }
        SpectatorState spectator = new SpectatorState(fight, state, anchor);
        spectators.put(player.getUniqueId(), spectator);
        fight.spectators.add(player.getUniqueId());
        player.closeDialog();
        player.closeInventory();
        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[4]);
        player.getInventory().setItemInOffHand(null);
        player.setGameMode(GameMode.ADVENTURE);
        player.setInvulnerable(true);
        player.setAllowFlight(true);
        player.setFlying(true);
        player.setWorldBorder(personalBorder(fight.arena));
        if (!teleport(player, anchor)) {
            leaveSpectator(player, false);
            error(player, "The protected viewing teleport was refused. Your items are back.");
            return;
        }
        for (UUID fighterId : List.of(fight.first, fight.second)) {
            Player fighter = Bukkit.getPlayer(fighterId);
            if (fighter != null) {
                fighter.hidePlayer(plugin, player);
            }
        }
        info(player, "Watching " + fight.label()
                + " from a fixed stand. /pvp leave returns you and your items.");
    }

    private void leaveSpectator(Player player, boolean announce) {
        SpectatorState spectator = spectators.remove(player.getUniqueId());
        if (spectator == null) {
            if (announce) {
                error(player, "You are not watching a duel.");
            }
            return;
        }
        spectator.fight().spectators.remove(player.getUniqueId());
        for (UUID fighterId : List.of(spectator.fight().first, spectator.fight().second)) {
            Player fighter = Bukkit.getPlayer(fighterId);
            if (fighter != null) {
                fighter.showPlayer(plugin, player);
            }
        }
        restoreSpectatorInventory(player, spectator.player().recovery());
        restorePlayer(player, spectator.player());
        safeRemoveRecovery(player.getUniqueId());
        if (announce) {
            info(player, "Viewing ended. Your location and belongings are back.");
        }
    }

    private void openChestHub(Player player) {
        DuelBoard holder = board(Board.HUB, null, 27, "Safe PvP");
        holder.inventory.setItem(HUB_START, MenuItems.button(
                Material.DIAMOND_SWORD, "Start a Duel", "Choose an opponent."));
        holder.inventory.setItem(HUB_INCOMING, MenuItems.button(
                Material.WRITABLE_BOOK, "Incoming", "Review your challenges."));
        holder.inventory.setItem(HUB_LIVE, MenuItems.button(
                Material.SPYGLASS, "Watch Live Duels", "Fixed viewing stand; no items."));
        MenuItems.back(holder.inventory);
        MenuItems.show(plugin, player, holder.inventory);
    }

    private void openChestList(Player player, Board kind, List<Player> choices, String title) {
        DuelBoard holder = board(kind, null, BOARD_SIZE, title);
        for (int slot = 0; slot < choices.size() && slot < PAGE_SIZE; slot++) {
            Player target = choices.get(slot);
            holder.inventory.setItem(slot, MenuItems.head(
                    target.getUniqueId(), target.getName(), List.of("Set duel terms")));
            holder.choices.put(slot, target.getUniqueId());
        }
        MenuItems.back(holder.inventory);
        MenuItems.show(plugin, player, holder.inventory);
    }

    private void openChestSetup(Player player, Player target) {
        DuelBoard holder = board(Board.SETUP, target.getUniqueId(), 27,
                "Duel " + target.getName());
        holder.inventory.setItem(11, MenuItems.button(Material.SHIELD,
                "Friendly Duel", "No stakes. KEEP INVENTORY."));
        holder.inventory.setItem(13, MenuItems.button(Material.CHEST,
                "Stake Held Stacks", "Each player stakes the stack in hand."));
        holder.inventory.setItem(15, MenuItems.button(Material.WRITABLE_BOOK,
                "Custom Terms", "Set the reason, cash and item wager in chat."));
        MenuItems.back(holder.inventory);
        MenuItems.show(plugin, player, holder.inventory);
    }

    private void openChestInvitations(Player player, List<Invitation> incoming) {
        DuelBoard holder = board(Board.INCOMING, null, BOARD_SIZE, "Incoming Duels");
        for (int slot = 0; slot < incoming.size() && slot < PAGE_SIZE; slot++) {
            Invitation invite = incoming.get(slot);
            holder.inventory.setItem(slot, MenuItems.head(
                    invite.challenger(), name(invite.challenger()), List.of(invite.reason())));
            holder.choices.put(slot, invite.id());
        }
        MenuItems.back(holder.inventory);
        MenuItems.show(plugin, player, holder.inventory);
    }

    private void openChestAccept(
            Player player,
            Invitation invitation,
            ItemStack challengerStake,
            ItemStack targetStake
    ) {
        DuelBoard holder = board(Board.ACCEPT, invitation.id(), 27, "Accept Safe Duel?");
        holder.challengerStake = cloneOrNull(challengerStake);
        holder.targetStake = cloneOrNull(targetStake);
        holder.inventory.setItem(11, MenuItems.button(Material.LIME_CONCRETE,
                "Accept & Fight", "KEEP INVENTORY", "Accept the exact terms shown in chat."));
        holder.inventory.setItem(15, MenuItems.button(Material.RED_CONCRETE,
                "Decline", "No wager will be charged."));
        Player challenger = Bukkit.getPlayer(invitation.challenger());
        if (challenger != null) {
            info(player, terms(invitation, challenger, challengerStake, player, targetStake));
        }
        MenuItems.back(holder.inventory);
        MenuItems.show(plugin, player, holder.inventory);
    }

    private void openChestFights(Player player, List<Fight> live) {
        DuelBoard holder = board(Board.LIVE, null, BOARD_SIZE, "Live Duels");
        for (int slot = 0; slot < live.size() && slot < PAGE_SIZE; slot++) {
            Fight fight = live.get(slot);
            holder.inventory.setItem(slot, MenuItems.button(
                    Material.SPYGLASS, fight.label(), fight.reason));
            holder.choices.put(slot, fight.id);
        }
        MenuItems.back(holder.inventory);
        MenuItems.show(plugin, player, holder.inventory);
    }

    private DuelBoard board(Board kind, UUID subject, int size, String title) {
        DuelBoard holder = new DuelBoard(kind, subject);
        holder.inventory = Bukkit.createInventory(holder, size, Component.text(title, ORANGE));
        return holder;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onBoardClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof DuelBoard holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || event.getClickedInventory() != event.getInventory()) {
            return;
        }
        int slot = event.getSlot();
        if (slot == MenuItems.backSlot(event.getInventory().getSize())) {
            Consumer<Player> destination = switch (holder.board) {
                case HUB -> Screens::home;
                case SETUP -> this::openTargets;
                case ACCEPT -> this::openIncoming;
                default -> this::openHub;
            };
            runLater(player, destination);
            return;
        }
        switch (holder.board) {
            case HUB -> {
                if (slot == HUB_START) runLater(player, this::openTargets);
                if (slot == HUB_INCOMING) runLater(player, this::openIncoming);
                if (slot == HUB_LIVE) runLater(player, this::openLive);
            }
            case TARGETS -> {
                UUID id = holder.choices.get(slot);
                if (id != null) runLater(player, viewer -> {
                    Player target = Bukkit.getPlayer(id);
                    if (target == null) error(viewer, "They went offline.");
                    else openSetup(viewer, target);
                });
            }
            case SETUP -> runLater(player, viewer -> chestSetupClick(viewer, holder.subject, slot));
            case INCOMING -> {
                UUID id = holder.choices.get(slot);
                if (id != null) runLater(player, viewer -> {
                    Invitation invite = invitations.get(id);
                    if (invite == null) error(viewer, "That challenge expired.");
                    else openInvitation(viewer, invite);
                });
            }
            case ACCEPT -> runLater(player, viewer -> {
                Invitation invite = invitations.get(holder.subject);
                if (invite == null) {
                    error(viewer, "That challenge expired.");
                    return;
                }
                if (slot == 11) {
                    accept(viewer, invite, new AcceptedTerms(
                            holder.challengerStake, holder.targetStake));
                } else if (slot == 15) {
                    declineByName(viewer, name(invite.challenger()));
                }
            });
            case LIVE -> {
                UUID id = holder.choices.get(slot);
                if (id != null) runLater(player, viewer -> {
                    Fight fight = fights.get(id);
                    if (fight == null) error(viewer, "That duel ended.");
                    else joinSpectator(viewer, fight);
                });
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onBoardDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof DuelBoard) {
            event.setCancelled(true);
        }
    }

    private void chestSetupClick(Player player, UUID targetId, int slot) {
        Player target = Bukkit.getPlayer(targetId);
        if (target == null) {
            error(player, "They went offline.");
            return;
        }
        if (slot == 11) {
            sendChallenge(player, targetId, "Friendly fight", "0", false);
        } else if (slot == 13) {
            sendChallenge(player, targetId, "Friendly item wager", "0", true);
        } else if (slot == 15) {
            legacyDrafts.put(player.getUniqueId(), new LegacyDraft(targetId));
            prompts.put(player.getUniqueId(), Prompt.REASON);
            player.closeInventory();
            info(player, "Type the duel reason or lore in chat, or type cancel.");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onPromptChat(AsyncChatEvent event) {
        Prompt prompt = prompts.get(event.getPlayer().getUniqueId());
        if (prompt == null) {
            if (spectators.containsKey(event.getPlayer().getUniqueId())) {
                event.setCancelled(true);
                plugin.getServer().getScheduler().runTask(plugin, () -> error(
                        event.getPlayer(),
                        "Viewing chat is disabled so spectators cannot coach a fighter."
                ));
            }
            return;
        }
        event.setCancelled(true);
        String typed = PlainTextComponentSerializer.plainText().serialize(event.message()).strip();
        plugin.getServer().getScheduler().runTask(plugin,
                () -> continueLegacyPrompt(event.getPlayer(), prompt, typed));
    }

    private void continueLegacyPrompt(Player player, Prompt prompt, String typed) {
        LegacyDraft draft = legacyDrafts.get(player.getUniqueId());
        if (draft == null || typed.equalsIgnoreCase("cancel")) {
            prompts.remove(player.getUniqueId());
            legacyDrafts.remove(player.getUniqueId());
            info(player, "Custom challenge cancelled.");
            return;
        }
        if (prompt == Prompt.REASON) {
            if (!PvpDuelRules.validReason(typed)) {
                error(player, "Give a 3–80 character reason or lore, or type cancel.");
                return;
            }
            draft.reason = PvpDuelRules.cleanReason(typed);
            prompts.put(player.getUniqueId(), Prompt.MONEY);
            info(player, "Type the money each player stakes, 0 for none, or cancel.");
            return;
        }
        if (prompt == Prompt.MONEY) {
            try {
                draft.money = typed.equals("0") ? 0L : EconomyFormat.parseAmount(typed);
                if (!PvpDuelRules.validMoney(draft.money, maximumWager())) {
                    throw new IllegalArgumentException("Maximum: "
                            + EconomyFormat.dollars(maximumWager()) + ".");
                }
            } catch (IllegalArgumentException exception) {
                error(player, exception.getMessage() + " Type another amount, or cancel.");
                return;
            }
            prompts.put(player.getUniqueId(), Prompt.ITEM);
            info(player, "Also stake each player's held stack? Type yes or no.");
            return;
        }
        if (!typed.equalsIgnoreCase("yes") && !typed.equalsIgnoreCase("no")) {
            error(player, "Type yes, no, or cancel.");
            return;
        }
        prompts.remove(player.getUniqueId());
        legacyDrafts.remove(player.getUniqueId());
        sendChallenge(player, draft.target, draft.reason, String.valueOf(draft.money),
                typed.equalsIgnoreCase("yes"));
    }

    /**
     * One PvP gate for the entire server. Unrelated players can never damage each
     * other; an accepted pair is uncancelled only while its countdown has finished.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            if (event instanceof EntityDamageByEntityEvent byEntity) {
                Player attacker = attackingPlayer(byEntity);
                if (attacker != null && isFighter(attacker.getUniqueId())) {
                    event.setCancelled(true);
                }
            }
            return;
        }
        if (spectators.containsKey(victim.getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        Fight victimFight = fighting.get(victim.getUniqueId());
        if (victimFight != null) {
            if (victimFight.phase != Phase.FIGHTING) {
                event.setCancelled(true);
                return;
            }
            if (event instanceof EntityDamageByEntityEvent byEntity) {
                boolean opponent = isOpponentAttack(
                        byEntity, victimFight.opponent(victim.getUniqueId())
                );
                event.setCancelled(!opponent);
            }
            return;
        }
        if (!(event instanceof EntityDamageByEntityEvent byEntity)) {
            return;
        }
        Player attacker = attackingPlayer(byEntity);
        if (attacker == null) {
            return;
        }
        // This includes a fighter aiming outside their match and all ordinary world
        // PvP. A player can be fought only through the explicit /pvp contract.
        event.setCancelled(true);
        maybeExplainBlocked(attacker);
        clearCombatLog(attacker, victim);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getPlayer();
        Fight fight = fighting.get(victim.getUniqueId());
        if (fight == null || fight.phase == Phase.ENDING) {
            return;
        }
        event.setKeepInventory(true);
        event.setKeepLevel(true);
        event.setDroppedExp(0);
        event.getDrops().clear();
        UUID winner = fight.opponent(victim.getUniqueId());
        Player winnerPlayer = Bukkit.getPlayer(winner);
        event.deathMessage(Component.text(
                (winnerPlayer == null ? name(winner) : winnerPlayer.getName())
                        + " defeated " + victim.getName()
                        + " in a safe duel — inventory kept.",
                NamedTextColor.GOLD
        ));
        plugin.getServer().getScheduler().runTask(plugin,
                () -> endFight(fight, winner, victim.getName() + " was defeated"));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onRespawn(PlayerRespawnEvent event) {
        PlayerState state = pendingDeathRestore.remove(event.getPlayer().getUniqueId());
        if (state == null) {
            return;
        }
        Location origin = origin(state.recovery());
        if (origin != null) {
            event.setRespawnLocation(origin);
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            restorePlayer(event.getPlayer(), state);
            safeRemoveRecovery(event.getPlayer().getUniqueId());
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        if (to == null || samePosition(event.getFrom(), to)) {
            return;
        }
        SpectatorState spectator = spectators.get(event.getPlayer().getUniqueId());
        if (spectator != null) {
            event.setCancelled(true);
            return;
        }
        Fight fight = fighting.get(event.getPlayer().getUniqueId());
        if (fight == null) {
            return;
        }
        if (fight.phase == Phase.COUNTDOWN
                || to.getWorld() == null
                || !to.getWorld().equals(fight.arena.center().getWorld())
                || !PvpDuelRules.inside(to.getX(), to.getZ(),
                        fight.arena.center().getX(), fight.arena.center().getZ(),
                        fight.arena.diameter())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onTeleport(PlayerTeleportEvent event) {
        if (isParticipant(event.getPlayer().getUniqueId())
                && !internalTeleports.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            error(event.getPlayer(), "Leave or finish the duel before teleporting.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!isParticipant(event.getPlayer().getUniqueId())) {
            return;
        }
        String typed = event.getMessage().strip().toLowerCase(Locale.ROOT);
        boolean allowed = typed.equals("/pvp forfeit") || typed.equals("/pvp leave")
                || typed.equals("/duel forfeit") || typed.equals("/duel leave");
        if (!allowed) {
            event.setCancelled(true);
            error(event.getPlayer(), "Only /pvp forfeit or /pvp leave is available here.");
        } else {
            // CombatLog may have refused it earlier in the same event. These two are
            // the controlled exit routes, so they must reach this service.
            event.setCancelled(false);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBreak(BlockBreakEvent event) {
        if (isParticipant(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlace(BlockPlaceEvent event) {
        if (isParticipant(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (isParticipant(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (isParticipant(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if (spectators.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        if (isFighter(event.getPlayer().getUniqueId()) && event.getClickedBlock() != null) {
            // Doors, containers and terrain stay untouched, while a player pointing
            // at the ground may still eat, shield, draw a bow, or use another item
            // they actually brought. Placement has its own hard cancellation below.
            event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
            event.setUseItemInHand(org.bukkit.event.Event.Result.ALLOW);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (isParticipant(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDrop(PlayerDropItemEvent event) {
        if (isParticipant(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && isParticipant(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        if (spectators.containsKey(player.getUniqueId())
                || (isFighter(player.getUniqueId())
                        && event.getInventory().getType() != InventoryType.CRAFTING)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onEntityPlace(EntityPlaceEvent event) {
        if (event.getPlayer() != null && isParticipant(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onHangingPlace(HangingPlaceEvent event) {
        if (event.getPlayer() != null && isParticipant(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onVehicleCreate(VehicleCreateEvent event) {
        if (insideAnyArena(event.getVehicle().getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (insideAnyArena(event.getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onFood(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player
                && spectators.containsKey(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (insideAnyArena(event.getLocation())) event.blockList().clear();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (insideAnyArena(event.getBlock().getLocation())) event.blockList().clear();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onIgnite(BlockIgniteEvent event) {
        if (insideAnyArena(event.getBlock().getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        Fight fight = fighting.get(player.getUniqueId());
        if (fight != null && fight.phase != Phase.ENDING) {
            endFight(fight, fight.opponent(player.getUniqueId()), player.getName() + " left and forfeited");
        }
        if (spectators.containsKey(player.getUniqueId())) {
            leaveSpectator(player, false);
        }
        invitations.values().removeIf(invitation -> invitation.challenger().equals(player.getUniqueId())
                || invitation.target().equals(player.getUniqueId()));
        prompts.remove(player.getUniqueId());
        legacyDrafts.remove(player.getUniqueId());
        starting.remove(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (store.find(event.getPlayer().getUniqueId()).isEmpty()) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> recover(event.getPlayer(), false));
    }

    void stop() {
        for (Fight fight : List.copyOf(fights.values())) {
            endFight(fight, null, "Server restart — stakes returned");
        }
        for (UUID spectatorId : List.copyOf(spectators.keySet())) {
            Player player = Bukkit.getPlayer(spectatorId);
            if (player != null) leaveSpectator(player, false);
        }
        invitations.clear();
        prompts.clear();
        legacyDrafts.clear();
        lastChallenges.clear();
        starting.clear();
    }

    /** An owner pause resolves live contracts as draws instead of freezing fighters. */
    void pauseAll(String reason) {
        for (Fight fight : List.copyOf(fights.values())) {
            endFight(fight, null, reason);
        }
    }

    private void recover(Player player, boolean announce) {
        PvpDuelStore.Recovery recovery = store.find(player.getUniqueId()).orElse(null);
        if (recovery == null) {
            return;
        }
        if (economy.balance(player.getUniqueId()) < recovery.balanceBefore()) {
            economy.set(player.getUniqueId(), recovery.balanceBefore());
        }
        if (recovery.role() == PvpDuelStore.Role.SPECTATOR
                && !recovery.encodedInventory().isEmpty()) {
            restoreSpectatorInventory(player, recovery);
        } else if (!recovery.encodedStake().isEmpty()) {
            ItemStack stake = decodeItem(recovery.encodedStake());
            restoreMissing(player, stake, recovery.heldSlot());
        }
        restorePlayer(player, new PlayerState(recovery, null));
        safeRemoveRecovery(player.getUniqueId());
        if (announce) {
            info(player, "An interrupted duel was resolved as a draw. Your state and stakes are back.");
        } else {
            info(player, "An interrupted safe duel was recovered. Your location, state, and stakes are back.");
        }
    }

    private static void restoreSpectatorInventory(
            Player player, PvpDuelStore.Recovery recovery
    ) {
        if (recovery.encodedInventory().isEmpty()) {
            return;
        }
        ItemStack[] saved = decodeItems(recovery.encodedInventory());
        player.getInventory().clear();
        player.getInventory().setContents(sized(
                saved, player.getInventory().getContents().length));
        player.getInventory().setHeldItemSlot(Math.max(0, Math.min(8, recovery.heldSlot())));
    }

    private PvpDuelStore.Recovery recovery(
            UUID duelId,
            PvpDuelStore.Role role,
            Player player,
            ItemStack stake,
            boolean inventory
    ) {
        Location origin = player.getLocation();
        return new PvpDuelStore.Recovery(
                duelId, role, origin.getWorld().getUID(), origin.getWorld().getName(),
                origin.getX(), origin.getY(), origin.getZ(), origin.getYaw(), origin.getPitch(),
                player.getGameMode().name(), player.isInvulnerable(), player.getAllowFlight(),
                player.isFlying(), player.getHealth(), player.getFoodLevel(), player.getSaturation(),
                player.getExhaustion(), player.getFireTicks(), player.getFallDistance(),
                player.getRemainingAir(), player.getInventory().getHeldItemSlot(),
                economy.balance(player.getUniqueId()), encodeItem(stake),
                inventory ? encodeItems(player.getInventory().getContents()) : ""
        );
    }

    private void restorePlayer(Player player, PlayerState state) {
        PvpDuelStore.Recovery recovery = state.recovery();
        try {
            player.setGameMode(GameMode.valueOf(recovery.gameMode()));
        } catch (IllegalArgumentException ignored) {
            player.setGameMode(GameMode.SURVIVAL);
        }
        player.setInvulnerable(recovery.invulnerable());
        player.setAllowFlight(recovery.allowFlight());
        player.setFlying(recovery.allowFlight() && recovery.flying());
        player.setFoodLevel(recovery.food());
        player.setSaturation(recovery.saturation());
        player.setExhaustion(recovery.exhaustion());
        player.setFireTicks(recovery.fireTicks());
        player.setFallDistance(recovery.fallDistance());
        player.setRemainingAir(recovery.remainingAir());
        if (!player.isDead() && player.getAttribute(Attribute.MAX_HEALTH) != null) {
            player.setHealth(Math.max(0.1d, Math.min(
                    recovery.health(), player.getAttribute(Attribute.MAX_HEALTH).getValue())));
        }
        Location origin = origin(recovery);
        if (origin != null) {
            teleport(player, origin);
        }
        // A previous border may belong to the original world, so put it back only
        // after the cross-world return has completed. Null resets a crash recovery to
        // that world's own border.
        player.setWorldBorder(state.previousBorder());
    }

    private Location origin(PvpDuelStore.Recovery recovery) {
        World world = Bukkit.getWorld(recovery.worldId());
        if (world == null) world = Bukkit.getWorld(recovery.worldName());
        return world == null ? null : new Location(
                world, recovery.x(), recovery.y(), recovery.z(), recovery.yaw(), recovery.pitch()
        );
    }

    private void restoreStartFailure(Player player, PvpDuelStore.Recovery recovery) {
        if (economy.balance(player.getUniqueId()) < recovery.balanceBefore()) {
            economy.set(player.getUniqueId(), recovery.balanceBefore());
        }
        if (!recovery.encodedStake().isEmpty()) {
            restoreMissing(player, decodeItem(recovery.encodedStake()), recovery.heldSlot());
        }
    }

    private static void takeHeldStake(Player player, ItemStack expected) {
        if (expected == null) return;
        ItemStack current = player.getInventory().getItemInMainHand();
        if (empty(current) || !current.isSimilar(expected) || current.getAmount() != expected.getAmount()) {
            throw new IllegalStateException("A held wager changed while the duel was starting.");
        }
        player.getInventory().setItemInMainHand(null);
    }

    private void returnStake(Player player, PlayerState state) {
        if (player == null || state == null || state.recovery().encodedStake().isEmpty()) {
            return;
        }
        giveSafely(player, decodeItem(state.recovery().encodedStake()));
    }

    private void giveSafely(Player player, ItemStack item) {
        if (empty(item)) return;
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(item);
        for (ItemStack left : overflow.values()) {
            Map<Integer, ItemStack> enderOverflow = player.getEnderChest().addItem(left);
            for (ItemStack last : enderOverflow.values()) {
                org.bukkit.entity.Item dropped = player.getWorld().dropItemNaturally(
                        player.getLocation(), last
                );
                dropped.setOwner(player.getUniqueId());
                dropped.setPickupDelay(20);
            }
        }
    }

    private ItemStack duelHead(UUID victimId, String victimName) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        if (!(head.getItemMeta() instanceof SkullMeta meta)) {
            return head;
        }
        meta.setOwningPlayer(Bukkit.getOfflinePlayer(victimId));
        meta.displayName(Component.text(victimName + "'s Duel Trophy",
                ORANGE, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                line("Won in consensual /pvp."),
                line("The defeated player kept their inventory."),
                line("Tradable and protected from /sell.")
        ));
        meta.getPersistentDataContainer().set(
                victimKey, PersistentDataType.STRING, victimId.toString()
        );
        head.setItemMeta(meta);
        return head;
    }

    private void failStart(Player first, Player second, String reason) {
        starting.remove(first.getUniqueId());
        starting.remove(second.getUniqueId());
        error(first, reason);
        error(second, reason);
    }

    private boolean canChallenge(Player challenger, Player target, boolean explain) {
        String problem = null;
        if (!enabled()) problem = "Safe PvP is currently disabled.";
        else if (!plugin.duelDamageEnabled()) problem = "All PvP is currently paused by the server.";
        else if (challenger.equals(target)) problem = "You cannot challenge yourself.";
        else if (!challenger.isOnline() || !target.isOnline()) problem = "Both players must be online.";
        else if (VerificationLobbyService.isLobbyWorld(challenger.getWorld())
                || VerificationLobbyService.isLobbyWorld(target.getWorld())) {
            problem = "Verification-lobby players cannot duel.";
        } else if (plugin.inScreenshotMode(challenger) || plugin.inScreenshotMode(target)) {
            problem = "Screenshot mode must be closed before dueling.";
        } else if (isParticipant(challenger.getUniqueId()) || isParticipant(target.getUniqueId())
                || starting.contains(challenger.getUniqueId()) || starting.contains(target.getUniqueId())) {
            problem = "One of you is already busy with a duel.";
        } else if (store.find(challenger.getUniqueId()).isPresent()
                || store.find(target.getUniqueId()).isPresent()) {
            problem = "One player's interrupted-duel recovery is still finishing.";
        } else if ((plugin.afkService() != null && plugin.afkService().inCombat(challenger))
                || (plugin.afkService() != null && plugin.afkService().inCombat(target))) {
            problem = "Finish the current combat tag before arranging a duel.";
        }
        if (problem != null && explain) error(challenger, problem);
        return problem == null;
    }

    private boolean acceptingChallenges(Player challenger, Player target, boolean explain) {
        boolean accepting = settings.isEnabled(
                target.getUniqueId(), PlayerSettingsStore.Setting.DUEL_REQUESTS
        );
        if (!accepting && explain) {
            error(challenger, target.getName() + " is not accepting PvP challenges.");
        }
        return accepting;
    }

    private boolean readyToStart(Player first, Player second) {
        return enabled()
                && plugin.duelDamageEnabled()
                && first.isOnline()
                && second.isOnline()
                && !isParticipant(first.getUniqueId())
                && !isParticipant(second.getUniqueId())
                && store.find(first.getUniqueId()).isEmpty()
                && store.find(second.getUniqueId()).isEmpty()
                && !VerificationLobbyService.isLobbyWorld(first.getWorld())
                && !VerificationLobbyService.isLobbyWorld(second.getWorld())
                && !plugin.inScreenshotMode(first)
                && !plugin.inScreenshotMode(second);
    }

    private boolean validInvitation(Player target, Invitation invitation, boolean explain) {
        expireInvitations();
        boolean valid = invitation != null
                && invitations.containsKey(invitation.id())
                && invitation.target().equals(target.getUniqueId())
                && invitation.expiresAt() > System.currentTimeMillis();
        if (!valid && explain) error(target, "That duel challenge expired or is no longer available.");
        return valid;
    }

    private void acceptByName(Player target, String challengerName) {
        Invitation invite = incoming(target.getUniqueId()).stream()
                .filter(row -> challengerName.isBlank()
                        || name(row.challenger()).equalsIgnoreCase(challengerName))
                .findFirst().orElse(null);
        if (invite == null) {
            error(target, "No matching challenge is waiting. Open /pvp to review them.");
        } else {
            openInvitation(target, invite);
        }
    }

    private void declineByName(Player target, String challengerName) {
        Invitation invite = incoming(target.getUniqueId()).stream()
                .filter(row -> challengerName.isBlank()
                        || name(row.challenger()).equalsIgnoreCase(challengerName))
                .findFirst().orElse(null);
        if (invite == null) {
            error(target, "No matching challenge is waiting.");
            return;
        }
        invitations.remove(invite.id());
        Player challenger = Bukkit.getPlayer(invite.challenger());
        if (challenger != null) info(challenger, target.getName() + " declined your duel.");
        info(target, "Challenge declined.");
    }

    private void spectateByName(Player player, String name) {
        Fight fight = fights.values().stream()
                .filter(row -> row.firstName.equalsIgnoreCase(name)
                        || row.secondName.equalsIgnoreCase(name))
                .findFirst().orElse(null);
        if (fight == null) error(player, "That player is not in a live duel.");
        else joinSpectator(player, fight);
    }

    private List<Invitation> incoming(UUID playerId) {
        return invitations.values().stream()
                .filter(invite -> invite.target().equals(playerId))
                .sorted(Comparator.comparingLong(Invitation::expiresAt))
                .toList();
    }

    private void expireInvitations() {
        long now = System.currentTimeMillis();
        invitations.values().removeIf(invitation -> invitation.expiresAt() <= now);
    }

    private List<Player> targets(Player viewer) {
        return Bukkit.getOnlinePlayers().stream()
                .map(player -> (Player) player)
                .filter(player -> !player.equals(viewer))
                .filter(player -> !VerificationLobbyService.isLobbyWorld(player.getWorld()))
                .filter(player -> !isParticipant(player.getUniqueId()))
                .filter(player -> acceptingChallenges(viewer, player, false))
                .sorted(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private boolean insideAnyArena(Location location) {
        if (location.getWorld() == null) return false;
        for (Fight fight : fights.values()) {
            Location center = fight.arena.center();
            if (location.getWorld().equals(center.getWorld())
                    && PvpDuelRules.inside(location.getX(), location.getZ(),
                            center.getX(), center.getZ(), fight.arena.diameter())) {
                return true;
            }
        }
        return false;
    }

    private void maybeExplainBlocked(Player attacker) {
        long now = System.currentTimeMillis();
        if (now - notices.getOrDefault(attacker.getUniqueId(), 0L) < NOTICE_COOLDOWN_MILLIS) {
            return;
        }
        notices.put(attacker.getUniqueId(), now);
        error(attacker, "Uninvited PvP is blocked. Use /pvp for a safe, consensual fight.");
    }

    private boolean teleport(Player player, Location location) {
        internalTeleports.add(player.getUniqueId());
        try {
            return player.teleport(location, PlayerTeleportEvent.TeleportCause.PLUGIN);
        } finally {
            internalTeleports.remove(player.getUniqueId());
        }
    }

    private void runLater(Player player, Consumer<Player> action) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                player.closeInventory();
                action.accept(player);
            }
        });
    }

    private void safeRemoveRecovery(UUID playerId) {
        try {
            store.remove(playerId);
        } catch (RuntimeException failure) {
            plugin.getLogger().warning("Could not clear recovered PvP state for "
                    + playerId + ": " + failure.getMessage());
        }
    }

    private ServerEvent.Builder record(String action, Player actor, String summary) {
        return ServerEvent.of(action, ServerEvent.CATEGORY_COMBAT,
                actor.getUniqueId(), actor.getName(), plugin::recordServerEvent).summary(summary);
    }

    private World overworld() {
        return Bukkit.getWorlds().stream()
                .filter(world -> world.getEnvironment() == World.Environment.NORMAL)
                .filter(world -> !VerificationLobbyService.isLobbyWorld(world))
                .findFirst().orElse(null);
    }

    private static Player attackingPlayer(EntityDamageByEntityEvent event) {
        Entity source = event.getDamager();
        if (source instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) {
            source = shooter;
        }
        if (source instanceof AreaEffectCloud cloud && cloud.getSource() instanceof Entity owner) {
            source = owner;
        }
        if (source instanceof TNTPrimed tnt && tnt.getSource() != null) {
            source = tnt.getSource();
        }
        if (source instanceof Tameable tameable && tameable.getOwner() instanceof Player owner) {
            source = owner;
        }
        return source instanceof Player player ? player : null;
    }

    private static boolean isOpponentAttack(EntityDamageByEntityEvent event, UUID opponentId) {
        Entity source = event.getDamager();
        if (source instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) {
            source = shooter;
        }
        if (source instanceof AreaEffectCloud cloud && cloud.getSource() instanceof Entity owner) {
            source = owner;
        }
        if (source instanceof TNTPrimed tnt && tnt.getSource() != null) {
            source = tnt.getSource();
        }
        // Tamed animals are deliberately excluded: this is the two accepted players
        // fighting with what is in their inventories, not a pet-assisted ambush.
        return source instanceof Player player && opponentId.equals(player.getUniqueId());
    }

    private static boolean samePosition(Location first, Location second) {
        return first.getWorld() == second.getWorld()
                && first.getX() == second.getX()
                && first.getY() == second.getY()
                && first.getZ() == second.getZ();
    }

    private static boolean sameCurrentStack(Player player, ItemStack expected) {
        if (expected == null) return true;
        ItemStack current = player.getInventory().getItemInMainHand();
        return !empty(current) && current.isSimilar(expected) && current.getAmount() == expected.getAmount();
    }

    private static ItemStack cloneOrNull(ItemStack item) {
        return empty(item) ? null : item.clone();
    }

    private static boolean empty(ItemStack item) {
        return item == null || item.getType().isAir() || item.getAmount() <= 0;
    }

    private static String itemName(ItemStack item) {
        if (empty(item)) return "nothing (acceptance unavailable)";
        Component custom = item.getItemMeta() == null ? null : item.getItemMeta().displayName();
        String name = custom == null
                ? item.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ')
                : PlainTextComponentSerializer.plainText().serialize(custom);
        return item.getAmount() + "x " + name;
    }

    private static String encodeItem(ItemStack item) {
        return empty(item) ? "" : Base64.getEncoder().encodeToString(item.serializeAsBytes());
    }

    private static ItemStack decodeItem(String encoded) {
        return ItemStack.deserializeBytes(Base64.getDecoder().decode(encoded));
    }

    private static String encodeItems(ItemStack[] items) {
        return Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(items));
    }

    private static ItemStack[] decodeItems(String encoded) {
        return ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(encoded));
    }

    private static ItemStack[] sized(ItemStack[] items, int size) {
        if (items.length == size) return items;
        ItemStack[] result = new ItemStack[size];
        System.arraycopy(items, 0, result, 0, Math.min(items.length, size));
        return result;
    }

    private void restoreMissing(Player player, ItemStack stake, int heldSlot) {
        if (empty(stake)) return;
        ItemStack originalSlot = player.getInventory().getItem(
                Math.max(0, Math.min(8, heldSlot))
        );
        // The durable record is written immediately before escrow is removed. If
        // that original slot still contains the exact stack, the stop happened in
        // the tiny pre-removal window. Otherwise the escrowed stack must come back
        // in full; counting similar items elsewhere could consume an unrelated stack.
        if (!empty(originalSlot) && originalSlot.isSimilar(stake)
                && originalSlot.getAmount() == stake.getAmount()) {
            return;
        }
        giveSafely(player, stake.clone());
    }

    private static void clearCombatLog(Player... players) {
        org.bukkit.plugin.Plugin combatLog = Bukkit.getPluginManager().getPlugin("CombatLog");
        if (combatLog == null || !combatLog.isEnabled()) return;
        for (Player player : players) {
            if (player == null) continue;
            for (String name : List.of("untag", "removeTag", "removeCombat", "endCombat")) {
                try {
                    combatLog.getClass().getMethod(name, Player.class).invoke(combatLog, player);
                    break;
                } catch (ReflectiveOperationException ignored) {
                    // Try the next known API spelling.
                }
            }
        }
    }

    private static void cancel(BukkitTask task) {
        if (task != null) task.cancel();
    }

    private static String name(UUID playerId) {
        Player online = Bukkit.getPlayer(playerId);
        if (online != null) return online.getName();
        String name = Bukkit.getOfflinePlayer(playerId).getName();
        return name == null ? "Unknown player" : name;
    }

    private static Component line(String text) {
        return Component.text(text, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false);
    }

    private static Component prefix() {
        return Component.text("PVP » ", ORANGE, TextDecoration.BOLD);
    }

    private static void info(Player player, String message) {
        player.sendMessage(prefix().append(Component.text(message, NamedTextColor.GRAY)));
    }

    private static void error(Player player, String message) {
        player.sendMessage(prefix().append(Component.text(message, NamedTextColor.RED)));
    }
}
