package bot.mgx.accessbridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.Statistic;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static bot.mgx.accessbridge.MenuItems.ORANGE;

/**
 * Shard rewards for coming back after a long absence and for bringing people to the
 * server, with {@code /referredby <player>} and {@code /referrals}.
 *
 * <p>Rewards are instant: a returning player is paid as soon as they reach the real
 * server, and a referral pays both sides the moment {@code /referredby} is accepted.
 * There is no cap on how many players one person may bring. The verification lobby
 * still holds everything back, and {@link ReferralRules} refuses alts.
 */
final class ReferralService implements Listener, CommandExecutor, TabCompleter {
    private static final long TICKS_PER_MINUTE = 20L * 60L;
    private static final long WELCOME_EXPIRY_MILLIS = 30L * ReferralRules.DAY_MILLIS;

    /** Read before Paper loads the player's data, which is the last time the absence is visible. */
    private record Arrival(boolean playedBefore, long lastSeen, long firstPlayed) {
    }

    private final MGXAccessBridge plugin;
    private final ReferralStore store;
    private final CrateItems items;
    private final DiscordIdentityService identities;
    private final GameVariableStore variables;
    private final Map<UUID, Arrival> arrivals = new ConcurrentHashMap<>();

    ReferralService(
            MGXAccessBridge plugin, ReferralStore store, CrateItems items,
            DiscordIdentityService identities, GameVariableStore variables
    ) {
        this.plugin = plugin;
        this.store = store;
        this.items = items;
        this.identities = identities;
        this.variables = variables;
    }

    void start() {
        plugin.getServer().getScheduler().runTaskTimer(
                plugin, PerfMonitor.track("referrals.minute", this::tickMinute), TICKS_PER_MINUTE, TICKS_PER_MINUTE);
    }

    // ------------------------------------------------------------------ arrivals

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayer(event.getUniqueId());
        arrivals.put(event.getUniqueId(), new Arrival(
                offline.hasPlayedBefore(), offline.getLastSeen(), offline.getFirstPlayed()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        Arrival arrival = arrivals.remove(id);
        Optional<ReferralStore.AccountRow> existing = store.account(id);
        boolean playedBefore = existing.isPresent()
                || (arrival == null ? player.hasPlayedBefore() : arrival.playedBefore());
        long lastSeen = Math.max(existing.map(row -> row.lastSeen).orElse(0L),
                arrival == null ? 0L : arrival.lastSeen());

        ReferralStore.AccountRow row = store.accountOrCreate(id);
        if (row.firstSeen <= 0L) {
            row.firstSeen = arrival != null && arrival.firstPlayed() > 0L && playedBefore
                    ? arrival.firstPlayed() : now;
        }
        long playMinutes = playMinutes(player);
        row.playMinutes = Math.max(row.playMinutes, playMinutes);
        identities.visibleUsername(id).ifPresent(owner -> row.discordOwner = owner);
        rememberAddress(player);
        row.lastSeen = now;

        if (enabled() && store.welcome(id).isEmpty()) {
            ReferralRules.Kind kind = null;
            if (!playedBefore) {
                kind = ReferralRules.Kind.NEW;
            } else if (ReferralRules.returning(lastSeen, now,
                    variables.integer("referrals.return-after-days"), playMinutes,
                    variables.integer("referrals.returning-minimum-play-minutes"))
                    && ReferralRules.returnCooldownOver(lastReturnRewardAt(id, row), now,
                    variables.integer("referrals.return-cooldown-days"))) {
                kind = ReferralRules.Kind.RETURNING;
            }
            if (kind != null) {
                store.openWelcome(id, kind, now);
                // A tick of grace so the reward lands in a loaded inventory.
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> release(player), 80L);
            }
        }
        save();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) deliverOwed(player);
        }, 60L);
    }

    /** A player held in the verification lobby is paid and told when it releases them. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        if (VerificationLobbyService.isLobbyWorld(event.getFrom())) {
            release(event.getPlayer());
        }
    }

    private void release(Player player) {
        if (!player.isOnline() || VerificationLobbyService.isLobbyWorld(player.getWorld())) {
            return;
        }
        Optional<ReferralStore.Welcome> open = store.welcome(player.getUniqueId());
        if (open.isEmpty()) return;
        if (enabled() && !open.get().qualified) {
            qualify(player, open.get(), System.currentTimeMillis());
            save();
        }
        sendWelcome(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        arrivals.remove(player.getUniqueId());
        ReferralStore.AccountRow row = store.accountOrCreate(player.getUniqueId());
        row.lastSeen = System.currentTimeMillis();
        row.playMinutes = Math.max(row.playMinutes, playMinutes(player));
        save();
    }

    // -------------------------------------------------------------- the minute

    private void tickMinute() {
        long now = System.currentTimeMillis();
        int window = variables.integer("referrals.claim-window-minutes");
        for (Player player : List.copyOf(plugin.getServer().getOnlinePlayers())) {
            UUID id = player.getUniqueId();
            ReferralStore.AccountRow row = store.accountOrCreate(id);
            row.lastSeen = now;
            row.playMinutes = Math.max(row.playMinutes, playMinutes(player));
            Optional<ReferralStore.Welcome> open = store.welcome(id);
            if (open.isEmpty() || VerificationLobbyService.isLobbyWorld(player.getWorld())) {
                continue;
            }
            ReferralStore.Welcome welcome = open.get();
            welcome.onlineMinutes++;
            if (enabled() && !welcome.qualified) {
                qualify(player, welcome, now);
            }
            boolean windowOver = welcome.onlineMinutes >= window;
            if (welcome.qualified && (welcome.referrer != null || windowOver)) {
                store.closeWelcome(id);
            } else if (!welcome.qualified && windowOver && welcome.referrer == null
                    && welcome.kind == ReferralRules.Kind.NEW) {
                // Nobody was named and a new player has no reward of their own.
                store.closeWelcome(id);
            }
        }
        store.welcomes().forEach((id, welcome) -> {
            if (now - welcome.startedAt > WELCOME_EXPIRY_MILLIS) store.closeWelcome(id);
        });
        save();
    }

    private void qualify(Player player, ReferralStore.Welcome welcome, long now) {
        welcome.qualified = true;
        UUID id = player.getUniqueId();
        if (welcome.kind == ReferralRules.Kind.RETURNING && !welcome.playerPaid) {
            welcome.playerPaid = true;
            ReferralStore.AccountRow row = store.accountOrCreate(id);
            if (ReferralRules.returnCooldownOver(lastReturnRewardAt(id, row), now,
                    variables.integer("referrals.return-cooldown-days"))) {
                row.lastReturnRewardAt = now;
                int shards = variables.integer("referrals.returning-player-shards");
                pay(id, shards, "for coming back to the server. Welcome back!");
                audit(player, "referral_return", player.getName() + " was rewarded for returning",
                        null, shards);
            }
        }
        if (welcome.referrer != null) {
            payReferral(player, welcome, now);
        }
    }

    private void payReferral(Player referee, ReferralStore.Welcome welcome, long now) {
        if (welcome.referrerPaid) return;
        welcome.referrerPaid = true;
        UUID referrerId;
        try {
            referrerId = UUID.fromString(welcome.referrer);
        } catch (IllegalArgumentException invalid) {
            return;
        }
        ReferralRules.Account refereeAccount = account(referee.getUniqueId(), referee);
        ReferralRules.Account referrerAccount = account(referrerId, Bukkit.getPlayer(referrerId));
        boolean genuinelyNew = welcome.kind != ReferralRules.Kind.NEW
                || ReferralRules.genuinelyNew(refereeAccount, knownAccounts());
        // Checked again at payout without the history: a Discord link or a shared
        // connection that appeared after the claim still cancels the reward.
        String refusal = ReferralRules.refusal(refereeAccount, welcome.kind, genuinelyNew,
                referrerAccount, List.of(), settings(), now);
        String referrerName = nameOf(referrerId);
        if (refusal != null) {
            welcome.playerPaid = true;
            error(referee, "Referral rewards were cancelled: " + refusal);
            return;
        }
        int refereeShards = 0;
        int referrerShards;
        if (welcome.kind == ReferralRules.Kind.NEW) {
            referrerShards = variables.integer("referrals.new-referrer-shards");
            if (!welcome.playerPaid) {
                welcome.playerPaid = true;
                refereeShards = variables.integer("referrals.new-player-shards");
                pay(referee.getUniqueId(), refereeShards,
                        "for joining through " + referrerName + "'s invite.");
            }
            pay(referrerId, referrerShards, "for inviting " + referee.getName()
                    + " to the server.");
        } else {
            referrerShards = variables.integer("referrals.returning-referrer-shards");
            pay(referrerId, referrerShards, "for bringing " + referee.getName()
                    + " back to the server.");
        }
        audit(referee, "referral_reward", referee.getName() + " was referred by " + referrerName,
                referrerName, refereeShards + referrerShards);
    }

    // --------------------------------------------------------------- commands

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command is available to players only.");
            return true;
        }
        if (command.getName().equalsIgnoreCase("referrals")) {
            showStatus(player);
            return true;
        }
        if (!enabled()) {
            error(player, "Referral rewards are switched off right now.");
            return true;
        }
        if (args.length != 1) {
            error(player, "Use /referredby <player> with the name of the player who invited you.");
            return true;
        }
        claim(player, args[0]);
        return true;
    }

    private void claim(Player player, String name) {
        UUID id = player.getUniqueId();
        Optional<ReferralStore.Welcome> open = store.welcome(id);
        if (open.isEmpty()) {
            error(player, "Only brand-new players, and players returning after "
                    + variables.integer("referrals.return-after-days")
                    + "+ days away, can name who brought them. See /referrals.");
            return;
        }
        ReferralStore.Welcome welcome = open.get();
        if (welcome.referrer != null) {
            error(player, "You already named " + nameOf(UUID.fromString(welcome.referrer))
                    + " as the player who brought you.");
            return;
        }
        if (welcome.onlineMinutes >= variables.integer("referrals.claim-window-minutes")) {
            error(player, "The time to name who brought you has passed.");
            return;
        }
        OfflinePlayer target = Bukkit.getPlayerExact(name);
        if (target == null) target = Bukkit.getOfflinePlayerIfCached(name);
        if (target == null || (!target.isOnline() && !target.hasPlayedBefore())) {
            error(player, "No player named " + name + " has played here.");
            return;
        }
        UUID referrerId = target.getUniqueId();
        long now = System.currentTimeMillis();
        ReferralRules.Account referee = account(id, player);
        ReferralRules.Account referrer = account(referrerId, target.getPlayer());
        boolean genuinelyNew = welcome.kind != ReferralRules.Kind.NEW
                || ReferralRules.genuinelyNew(referee, knownAccounts());
        String refusal = ReferralRules.refusal(referee, welcome.kind, genuinelyNew, referrer,
                store.history(), settings(), now);
        if (refusal != null) {
            error(player, refusal);
            return;
        }
        welcome.referrer = referrerId.toString();
        store.addReferral(referrerId, referrer.ownerKey(), id, referee.ownerKey(),
                welcome.kind, now);
        if (welcome.qualified) {
            payReferral(player, welcome, now);
        } else {
            qualify(player, welcome, now);
        }
        store.closeWelcome(id);
        save();
    }

    private void showStatus(Player player) {
        UUID id = player.getUniqueId();
        player.sendMessage(Component.empty());
        player.sendMessage(Component.text("REFERRAL REWARDS", ORANGE, TextDecoration.BOLD));
        player.sendMessage(line("Invite a brand-new player: you get "
                + variables.integer("referrals.new-referrer-shards") + " Shards, they get "
                + variables.integer("referrals.new-player-shards") + "."));
        player.sendMessage(line("Bring back a player away " + variables.integer("referrals.return-after-days")
                + "+ days: you both get " + variables.integer("referrals.returning-referrer-shards")
                + " Shards. Returning alone still pays them "
                + variables.integer("referrals.returning-player-shards") + "."));
        player.sendMessage(line("They type /referredby <your name> within "
                + variables.integer("referrals.claim-window-minutes")
                + " minutes of joining and the Shards arrive instantly. There is no limit."));
        player.sendMessage(Component.text("Linked alts, shared connections and brand-new inviter"
                + " accounts are not eligible.", NamedTextColor.GRAY));
        store.welcome(id).ifPresent(welcome -> player.sendMessage(Component.text(
                (welcome.kind == ReferralRules.Kind.NEW ? "Welcome! " : "Welcome back! ")
                        + (welcome.referrer == null
                        ? "You can still name who brought you with /referredby <player>."
                        : "Your referral is saved."), NamedTextColor.GREEN)));
        String owner = account(id, player).ownerKey();
        player.sendMessage(line("Players you have brought: " + store.referralsBy(owner).size()));
        player.sendMessage(Component.empty());
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender, Command command, String alias, String[] args
    ) {
        if (!command.getName().equalsIgnoreCase("referredby") || args.length != 1) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        List<String> names = new ArrayList<>();
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            if (!online.equals(sender) && online.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                names.add(online.getName());
            }
        }
        return names;
    }

    // ---------------------------------------------------------------- helpers

    private void sendWelcome(Player player) {
        Optional<ReferralStore.Welcome> open = store.welcome(player.getUniqueId());
        if (open.isEmpty() || open.get().referrer != null) return;
        ReferralStore.Welcome welcome = open.get();
        player.sendMessage(Component.empty());
        if (welcome.kind == ReferralRules.Kind.RETURNING) {
            player.sendMessage(Component.text("WELCOME BACK", ORANGE, TextDecoration.BOLD));
            player.sendMessage(line("Did someone bring you back? Type /referredby <player> and they"
                    + " get " + variables.integer("referrals.returning-referrer-shards")
                    + " Shards too."));
        } else {
            player.sendMessage(Component.text("WELCOME TO THE SERVER", ORANGE, TextDecoration.BOLD));
            player.sendMessage(line("Did a friend invite you? Type /referredby <player> within "
                    + variables.integer("referrals.claim-window-minutes") + " minutes."));
            player.sendMessage(line("You instantly get "
                    + variables.integer("referrals.new-player-shards") + " Shards and they get "
                    + variables.integer("referrals.new-referrer-shards") + "."));
        }
        player.sendMessage(Component.empty());
    }

    private void pay(UUID playerId, int shards, String reason) {
        if (shards <= 0) return;
        Player online = Bukkit.getPlayer(playerId);
        if (online == null) {
            store.owe(playerId, shards);
            return;
        }
        giveShards(online, shards);
        online.sendMessage(Component.text("REFERRALS » ", ORANGE, TextDecoration.BOLD)
                .append(Component.text("You received " + shards + " Shards " + reason,
                        NamedTextColor.GREEN)));
        online.playSound(online, Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.4f);
    }

    private void deliverOwed(Player player) {
        int owed = store.takeOwed(player.getUniqueId());
        if (owed <= 0) return;
        save();
        giveShards(player, owed);
        info(player, "You received " + owed + " Shards from referral rewards earned while you"
                + " were away.");
    }

    private void giveShards(Player player, int shards) {
        for (int left = shards; left > 0; left -= 64) {
            player.getInventory().addItem(items.shard(Math.min(64, left))).values()
                    .forEach(spill -> player.getWorld().dropItemNaturally(player.getLocation(), spill));
        }
    }

    private ReferralRules.Account account(UUID id, Player online) {
        ReferralStore.AccountRow row = store.account(id).orElse(null);
        OfflinePlayer offline = online != null ? online : Bukkit.getOfflinePlayer(id);
        long firstSeen = row != null && row.firstSeen > 0L ? row.firstSeen : offline.getFirstPlayed();
        long minutes = row == null ? 0L : row.playMinutes;
        if (online != null) {
            minutes = Math.max(minutes, playMinutes(online));
        } else if (minutes <= 0L) {
            try {
                minutes = offline.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20L / 60L;
            } catch (RuntimeException unavailable) {
                minutes = 0L;
            }
        }
        String owner = identities.visibleUsername(id).orElse(row == null ? null : row.discordOwner);
        return new ReferralRules.Account(id, owner, firstSeen, minutes,
                row == null || row.addresses == null ? Set.of() : new HashSet<>(row.addresses));
    }

    private List<ReferralRules.Account> knownAccounts() {
        List<ReferralRules.Account> known = new ArrayList<>();
        store.accounts().forEach((id, row) -> known.add(new ReferralRules.Account(
                id, identities.visibleUsername(id).orElse(row.discordOwner),
                row.firstSeen, row.playMinutes, Set.of())));
        return known;
    }

    /** The newest return reward paid to this person on any of their linked accounts. */
    private long lastReturnRewardAt(UUID id, ReferralStore.AccountRow own) {
        String owner = ReferralRules.ownerKey(id, identities.visibleUsername(id).orElse(own.discordOwner));
        long newest = own.lastReturnRewardAt;
        for (Map.Entry<UUID, ReferralStore.AccountRow> entry : store.accounts().entrySet()) {
            ReferralStore.AccountRow row = entry.getValue();
            if (ReferralRules.ownerKey(entry.getKey(), row.discordOwner).equals(owner)) {
                newest = Math.max(newest, row.lastReturnRewardAt);
            }
        }
        return newest;
    }

    private void rememberAddress(Player player) {
        InetSocketAddress socket = player.getAddress();
        InetAddress address = socket == null ? null : socket.getAddress();
        if (address == null || address.isLoopbackAddress() || address.isAnyLocalAddress()
                || address.isSiteLocalAddress() || address.isLinkLocalAddress()) {
            // A proxy or Geyser on the same host reports its own address for everyone.
            return;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet6Address) {
            if ((bytes[0] & 0xFE) == 0xFC) return;
            // A home IPv6 connection rotates within its /64, so the prefix is the connection.
            bytes = Arrays.copyOf(bytes, 8);
        }
        store.rememberAddress(player.getUniqueId(), store.hashAddress(Arrays.toString(bytes)));
    }

    private ReferralRules.Settings settings() {
        return new ReferralRules.Settings(
                variables.integer("referrals.referrer-minimum-days"),
                variables.integer("referrals.referrer-minimum-play-minutes"),
                variables.bool("referrals.block-shared-address"));
    }

    private boolean enabled() {
        return variables.bool("referrals.enabled");
    }

    private static long playMinutes(Player player) {
        return Math.max(0L, player.getStatistic(Statistic.PLAY_ONE_MINUTE)) / 20L / 60L;
    }

    private static String nameOf(UUID id) {
        String name = Bukkit.getOfflinePlayer(id).getName();
        return name == null ? "that player" : name;
    }

    private void audit(Player actor, String action, String summary, String referrer, int shards) {
        ServerEvent.Builder builder = ServerEvent.of(action, ServerEvent.CATEGORY_PROGRESSION,
                actor.getUniqueId(), actor.getName(), plugin::recordServerEvent)
                .summary(summary)
                .detail("shards", shards);
        if (referrer != null) builder.detail("referrer", referrer);
        builder.record();
    }

    private void save() {
        try {
            store.persist();
        } catch (RuntimeException failure) {
            plugin.getLogger().warning("Could not save referral rewards: " + failure.getMessage());
        }
    }

    private static Component line(String text) {
        return Component.text(text, NamedTextColor.WHITE);
    }

    private static void info(Player player, String text) {
        player.sendMessage(Component.text("REFERRALS » ", ORANGE, TextDecoration.BOLD)
                .append(Component.text(text, NamedTextColor.WHITE)));
    }

    private static void error(Player player, String text) {
        player.sendMessage(Component.text("REFERRALS » ", ORANGE, TextDecoration.BOLD)
                .append(Component.text(text, NamedTextColor.RED)));
    }
}
