package bot.mgx.accessbridge;

import io.papermc.paper.registry.data.dialog.body.DialogBody;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static bot.mgx.accessbridge.MenuItems.ORANGE;

/**
 * Daily login streaks: play a few active minutes each UTC day to claim that day's
 * reward, and keep coming back to climb the seven-day cycle.
 *
 * <p>The claim needs real play rather than a login, so an account that joins and leaves
 * earns nothing, and one claim per day is shared by every account on a Discord link.
 * Every seventh day earns a streak freeze, which quietly covers one missed day later.
 */
final class LoginStreakService implements Listener, CommandExecutor {
    private static final long PULSE_TICKS = 20L * 20L;
    private static final long PULSE_MILLIS = PULSE_TICKS * 50L;

    private final MGXAccessBridge plugin;
    private final LoginStreakStore store;
    private final GameVariableStore variables;
    private final CrateItems items;
    private final DiscordIdentityService identities;
    private final SettingsClientSupport clientSupport;
    private final BedrockForms forms;
    private boolean dirty;
    private int pulses;

    LoginStreakService(
            MGXAccessBridge plugin, LoginStreakStore store, GameVariableStore variables,
            CrateItems items, DiscordIdentityService identities,
            SettingsClientSupport clientSupport, BedrockForms forms
    ) {
        this.plugin = plugin;
        this.store = store;
        this.variables = variables;
        this.items = items;
        this.identities = identities;
        this.clientSupport = clientSupport;
        this.forms = forms;
    }

    void start() {
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::pulse, PULSE_TICKS, PULSE_TICKS);
    }

    static long today() {
        return LocalDate.now(ZoneOffset.UTC).toEpochDay();
    }

    /** The streak a player still holds today, for the sidebar. */
    int liveStreak(UUID playerId) {
        return LoginStreakRules.liveStreak(store.state(playerId), today());
    }

    // ------------------------------------------------------------------ play time

    private void pulse() {
        if (!variables.bool("streaks.enabled")) return;
        long today = today();
        long required = variables.integer("streaks.required-minutes") * 60_000L;
        for (Player player : List.copyOf(plugin.getServer().getOnlinePlayers())) {
            if (VerificationLobbyService.isLobbyWorld(player.getWorld())) continue;
            if (plugin.afkService() != null && plugin.afkService().isAfk(player.getUniqueId())) continue;
            LoginStreakStore.Row row = store.row(player.getUniqueId());
            if (row.lastClaimDay == today) continue;
            if (row.activeDay != today) {
                row.activeDay = today;
                row.activeMillis = 0L;
            }
            row.activeMillis += PULSE_MILLIS;
            dirty = true;
            // A reward lands in the inventory, so it waits while the PvP systems or
            // screenshot mode are holding that inventory; the minutes still count.
            boolean busy = plugin.inPvpDuel(player) || plugin.inScreenshotMode(player)
                    || (plugin.pvpCompetition() != null
                    && plugin.pvpCompetition().isParticipant(player.getUniqueId()));
            if (row.activeMillis >= required && !busy) {
                claim(player, today);
                save();
            }
        }
        // Minute-by-minute progress is cheap to lose; a claim above is saved at once.
        if (dirty && ++pulses % 3 == 0) save();
    }

    private void claim(Player player, long today) {
        UUID playerId = player.getUniqueId();
        String owner = ReferralRules.ownerKey(playerId,
                identities.visibleUsername(playerId).orElse(null));
        if (store.ownerClaimDay(owner) == today) {
            // The streak itself still counts for this account; only the reward is shared.
            LoginStreakRules.Claim claim = LoginStreakRules.claim(store.state(playerId), today,
                    variables.integer("streaks.maximum-freezes"));
            store.save(playerId, claim.state());
            info(player, "Day " + claim.state().streak() + " counted. Your linked account"
                    + " already collected today's streak reward.");
            dirty = true;
            return;
        }
        LoginStreakRules.Claim claim = LoginStreakRules.claim(store.state(playerId), today,
                variables.integer("streaks.maximum-freezes"));
        if (!claim.claimed()) return;
        store.save(playerId, claim.state());
        store.claimForOwner(owner, today);
        dirty = true;
        int streak = claim.state().streak();
        int cycleDay = LoginStreakRules.cycleDay(streak);
        Reward reward = reward(cycleDay, streak);
        pay(player, reward);

        player.showTitle(Title.title(
                Component.text("DAY " + streak + " STREAK", ORANGE, TextDecoration.BOLD),
                Component.text(reward.describe(), NamedTextColor.GOLD),
                Title.Times.times(Duration.ofMillis(150), Duration.ofMillis(2500),
                        Duration.ofMillis(500))));
        player.playSound(player, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
        player.sendMessage(Component.empty());
        player.sendMessage(Component.text("DAILY STREAK — DAY " + streak, ORANGE, TextDecoration.BOLD));
        player.sendMessage(Component.text("Reward: " + reward.describe(), NamedTextColor.WHITE));
        if (claim.freezesUsed() > 0) {
            player.sendMessage(Component.text(claim.freezesUsed() + " streak freeze"
                    + (claim.freezesUsed() == 1 ? " covered the day" : "s covered the days")
                    + " you missed.", NamedTextColor.AQUA));
        } else if (claim.reset()) {
            player.sendMessage(Component.text("Your last streak ended. Day 1 starts a new one.",
                    NamedTextColor.GRAY));
        }
        if (claim.freezeEarned()) {
            player.sendMessage(Component.text("You earned a streak freeze. It covers one missed"
                    + " day automatically.", NamedTextColor.AQUA));
        }
        player.sendMessage(Component.text("Come back tomorrow for Day " + (streak + 1) + ": "
                + reward(LoginStreakRules.cycleDay(streak + 1), streak + 1).describe()
                + "  •  /streak", NamedTextColor.GRAY));
        player.sendMessage(Component.empty());
        if (streak >= LoginStreakRules.CYCLE_DAYS && streak % LoginStreakRules.CYCLE_DAYS == 0) {
            Component shout = Component.text("★ ", ORANGE)
                    .append(Component.text(player.getName(), NamedTextColor.WHITE, TextDecoration.BOLD))
                    .append(Component.text(" reached a " + streak + "-day login streak!",
                            NamedTextColor.GOLD));
            for (Player online : plugin.getServer().getOnlinePlayers()) {
                if (!online.equals(player)) online.sendMessage(shout);
            }
        }
        ServerEvent.of("login_streak", ServerEvent.CATEGORY_PROGRESSION, playerId,
                        player.getName(), plugin::recordServerEvent)
                .summary(player.getName() + " claimed day " + streak + " of their login streak")
                .detail("streak", streak)
                .record();
    }

    // ------------------------------------------------------------------ rewards

    /** One day's reward, read from settings so the owner can retune the cycle live. */
    record Reward(int keys, int shards, long money, int bonusShards) {
        String describe() {
            List<String> parts = new ArrayList<>();
            if (keys > 0) parts.add(keys + (keys == 1 ? " Key" : " Keys"));
            int allShards = shards + bonusShards;
            if (allShards > 0) parts.add(allShards + (allShards == 1 ? " Shard" : " Shards"));
            if (money > 0L) parts.add(EconomyFormat.dollars(money));
            return parts.isEmpty() ? "Streak progress" : String.join(" + ", parts);
        }
    }

    Reward reward(int cycleDay, int streak) {
        String base = "streaks.day-" + cycleDay + ".";
        int milestoneEvery = variables.integer("streaks.milestone-every-days");
        int bonus = milestoneEvery > 0 && streak % milestoneEvery == 0
                ? variables.integer("streaks.milestone-shards") : 0;
        return new Reward(variables.integer(base + "keys"), variables.integer(base + "shards"),
                variables.integer(base + "money"), bonus);
    }

    private void pay(Player player, Reward reward) {
        if (reward.keys() > 0 && plugin.crateService() != null) {
            plugin.crateService().grantKeys(player, reward.keys());
        }
        int shards = reward.shards() + reward.bonusShards();
        for (int left = shards; left > 0; left -= 64) {
            player.getInventory().addItem(items.shard(Math.min(64, left))).values()
                    .forEach(spill -> player.getWorld().dropItemNaturally(player.getLocation(), spill));
        }
        if (reward.money() > 0L) {
            EconomyStore economy = plugin.economy();
            if (economy.canDeposit(player.getUniqueId(), reward.money())) {
                economy.deposit(player.getUniqueId(), reward.money());
            } else {
                error(player, "Your wallet is at its limit, so today's money could not be added.");
            }
        }
    }

    // ------------------------------------------------------------------ join and page

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline() || !variables.bool("streaks.enabled")
                    || VerificationLobbyService.isLobbyWorld(player.getWorld())) return;
            long today = today();
            LoginStreakRules.State state = store.state(player.getUniqueId());
            if (state.lastClaimDay() == today) return;
            int next = LoginStreakRules.nextStreakDay(state, today);
            int live = LoginStreakRules.liveStreak(state, today);
            player.sendMessage(Component.text("DAILY STREAK » ", ORANGE, TextDecoration.BOLD)
                    .append(Component.text("Play " + variables.integer("streaks.required-minutes")
                            + " active minutes today to claim Day " + next + ": ", NamedTextColor.WHITE))
                    .append(Component.text(reward(LoginStreakRules.cycleDay(next), next).describe(),
                            NamedTextColor.GOLD, TextDecoration.BOLD)));
            if (live > 0 && state.lastClaimDay() == today - 1) {
                player.sendMessage(Component.text("Your " + live + "-day streak ends at 00:00 UTC"
                        + " if you do not.", NamedTextColor.GRAY));
            }
        }, 100L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (dirty) save();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command is available to players only.");
            return true;
        }
        open(player);
        return true;
    }

    void open(Player player) {
        long today = today();
        LoginStreakRules.State state = store.state(player.getUniqueId());
        LoginStreakStore.Row row = store.row(player.getUniqueId());
        boolean claimed = state.lastClaimDay() == today;
        int live = LoginStreakRules.liveStreak(state, today);
        int next = LoginStreakRules.nextStreakDay(state, today);
        long required = variables.integer("streaks.required-minutes");
        long played = row.activeDay == today ? row.activeMillis / 60_000L : 0L;
        String todayLine = claimed ? "Claimed • back tomorrow"
                : Math.min(played, required) + " / " + required + " active minutes";
        // Where the current cycle is: completed days, today, and what is still ahead.
        int base = Math.max(1, claimed ? live : next);
        int cycleStart = base - LoginStreakRules.cycleDay(base) + 1;
        List<DialogBody> page = new ArrayList<>(List.of(
                DialogBody.plainMessage(MenuText.stat("Current streak",
                        live + (live == 1 ? " day" : " days")), 400),
                DialogBody.plainMessage(MenuText.stat("Best streak",
                        state.best() + (state.best() == 1 ? " day" : " days")), 400),
                DialogBody.plainMessage(MenuText.stat("Streak freezes",
                        state.freezes() + " / " + variables.integer("streaks.maximum-freezes")), 400),
                DialogBody.plainMessage(MenuText.stat("Today", todayLine), 400),
                DialogBody.plainMessage(Component.empty(), 400)));
        StringBuilder plain = new StringBuilder("Current streak: " + live + " days. Today: "
                + todayLine + ".\n\n");
        for (int day = 1; day <= LoginStreakRules.CYCLE_DAYS; day++) {
            int streakDay = cycleStart + day - 1;
            boolean done = claimed ? streakDay <= live : streakDay < next;
            boolean isToday = !claimed && streakDay == next;
            String sprite = done ? "item/lime_dye" : isToday ? "item/clock_00" : "item/gray_dye";
            String status = done ? "  ✔" : isToday ? "  ◄ TODAY" : "";
            String rewardText = reward(day, streakDay).describe();
            page.add(DialogBody.plainMessage(MenuText.stat("Day " + day, sprite,
                    rewardText + status), 400));
            plain.append("Day ").append(day).append(": ").append(rewardText).append(status).append('\n');
        }
        page.add(DialogBody.plainMessage(Component.empty(), 400));
        page.add(DialogBody.plainMessage(MenuText.muted("Play " + required + " active minutes a day."
                + " Every 7th day earns a streak freeze that covers one missed day."
                + " Days reset at 00:00 UTC."), 400));
        if (!clientSupport.supportsDialogs(player)) {
            if (!forms.menu(player, "Daily Streak", plain.toString(), List.of())) {
                player.sendMessage(Component.text(plain.toString(), NamedTextColor.GRAY));
            }
            return;
        }
        Screens.showStandalone(player, "Daily Streak", page, List.of(), 1);
    }

    private void save() {
        try {
            store.persist();
            dirty = false;
        } catch (RuntimeException failure) {
            plugin.getLogger().warning("Could not save login streaks: " + failure.getMessage());
        }
    }

    private static void info(Player player, String text) {
        player.sendMessage(Component.text("DAILY STREAK » ", ORANGE, TextDecoration.BOLD)
                .append(Component.text(text, NamedTextColor.WHITE)));
    }

    private static void error(Player player, String text) {
        player.sendMessage(Component.text("DAILY STREAK » ", ORANGE, TextDecoration.BOLD)
                .append(Component.text(text, NamedTextColor.RED)));
    }
}
