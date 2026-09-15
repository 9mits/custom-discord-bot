package bot.mgx.accessbridge;

import io.papermc.paper.registry.data.dialog.body.DialogBody;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static bot.mgx.accessbridge.MenuItems.ORANGE;

/**
 * Daily login streaks, kept deliberately simple: join the server on a UTC day and that
 * day counts. Every day in a row adds Daily Crate luck, up to a ceiling; missing a day
 * starts the streak again. There are no freezes and no playtime requirement to explain.
 *
 * <p>A player still online when the day turns over is counted a minute later, so nobody
 * loses a streak for not logging out. One Season XP bonus per day is shared by every
 * account on a Discord link.
 */
final class LoginStreakService implements Listener, CommandExecutor {
    private static final long PULSE_TICKS = 20L * 60L;

    private final MGXAccessBridge plugin;
    private final LoginStreakStore store;
    private final GameVariableStore variables;
    private final DiscordIdentityService identities;
    private final SettingsClientSupport clientSupport;
    private final BedrockForms forms;

    LoginStreakService(
            MGXAccessBridge plugin, LoginStreakStore store, GameVariableStore variables,
            CrateItems items, DiscordIdentityService identities,
            SettingsClientSupport clientSupport, BedrockForms forms
    ) {
        this.plugin = plugin;
        this.store = store;
        this.variables = variables;
        this.identities = identities;
        this.clientSupport = clientSupport;
        this.forms = forms;
    }

    void start() {
        plugin.getServer().getScheduler().runTaskTimer(plugin, PerfMonitor.track("login-streaks.pulse", this::pulse), PULSE_TICKS, PULSE_TICKS);
    }

    static long today() {
        return LocalDate.now(ZoneOffset.UTC).toEpochDay();
    }

    /** The streak a player still holds today. */
    int liveStreak(UUID playerId) {
        return LoginStreakRules.liveStreak(store.state(playerId), today());
    }

    /** Daily Crate luck, in percent, for a streak of this many days. */
    static int luckFor(int streak, int perDay, int maximum) {
        return Math.max(0, Math.min(maximum, streak * perDay));
    }

    private void pulse() {
        if (!variables.bool("streaks.enabled")) return;
        long today = today();
        for (Player player : List.copyOf(plugin.getServer().getOnlinePlayers())) {
            if (!VerificationLobbyService.isLobbyWorld(player.getWorld())
                    && store.state(player.getUniqueId()).lastClaimDay() != today) {
                claim(player, today);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && variables.bool("streaks.enabled")
                    && !VerificationLobbyService.isLobbyWorld(player.getWorld())) {
                claim(player, today());
            }
        }, 60L);
    }

    private void claim(Player player, long today) {
        UUID playerId = player.getUniqueId();
        LoginStreakRules.Claim claim = LoginStreakRules.claim(store.state(playerId), today, 0);
        if (!claim.claimed()) return;
        store.save(playerId, claim.state());
        int streak = claim.state().streak();
        String owner = ReferralRules.ownerKey(playerId, identities.visibleUsername(playerId).orElse(null));
        if (store.ownerClaimDay(owner) != today) {
            store.claimForOwner(owner, today);
            if (plugin.seasonPass() != null) {
                plugin.seasonPass().bonusXp(player, variables.integer("season.streak-xp"));
            }
        }
        save();

        int luck = luckFor(streak, variables.integer("crate.daily.streak-luck-per-day"),
                variables.integer("crate.daily.streak-luck-maximum"));
        int maximum = variables.integer("crate.daily.streak-luck-maximum");
        player.sendMessage(Component.text("DAILY STREAK » ", ORANGE, TextDecoration.BOLD)
                .append(Component.text("Day " + streak + "  •  Daily Crate luck +" + luck + "%"
                        + (luck >= maximum ? " (max)" : ""), NamedTextColor.WHITE))
                .append(Component.text("  •  /crate", NamedTextColor.GRAY)));
        if (claim.reset()) {
            player.sendMessage(Component.text("You missed a day, so your streak started again.",
                    NamedTextColor.GRAY));
        }
        player.playSound(player, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.3f);
        if (streak % LoginStreakRules.CYCLE_DAYS == 0) {
            Component shout = Component.text("★ ", ORANGE)
                    .append(Component.text(player.getName(), NamedTextColor.WHITE, TextDecoration.BOLD))
                    .append(Component.text(" reached a " + streak + "-day login streak!", NamedTextColor.GOLD));
            for (Player online : plugin.getServer().getOnlinePlayers()) {
                if (!online.equals(player)) online.sendMessage(shout);
            }
        }
        ServerEvent.of("login_streak", ServerEvent.CATEGORY_PROGRESSION, playerId,
                        player.getName(), plugin::recordServerEvent)
                .summary(player.getName() + " reached day " + streak + " of their login streak")
                .detail("streak", streak)
                .record();
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
        int live = LoginStreakRules.liveStreak(state, today);
        int perDay = variables.integer("crate.daily.streak-luck-per-day");
        int maximum = variables.integer("crate.daily.streak-luck-maximum");
        int luck = luckFor(live, perDay, maximum);
        int daysToMax = perDay <= 0 ? 0 : Math.max(0, (maximum + perDay - 1) / perDay - live);
        boolean ready = plugin.crateService() != null && plugin.crateService().passes() != null
                && plugin.crateService().passes().dailyReady(player.getUniqueId(), today);
        String crate = ready ? "Ready to open"
                : "Opens again in " + CrateService.countdown(CrateService.millisUntilDailyReset(System.currentTimeMillis()));
        List<DialogBody> page = new ArrayList<>(List.of(
                DialogBody.plainMessage(MenuText.stat("Current streak", live + (live == 1 ? " day" : " days")), 400),
                DialogBody.plainMessage(MenuText.stat("Best streak", state.best() + (state.best() == 1 ? " day" : " days")), 400),
                DialogBody.plainMessage(MenuText.stat("Daily Crate luck", "+" + luck + "%"
                        + (luck >= maximum ? "  •  max" : "  •  max in " + daysToMax + (daysToMax == 1 ? " day" : " days"))), 400),
                DialogBody.plainMessage(MenuText.stat("Daily Crate", crate), 400),
                DialogBody.plainMessage(Component.empty(), 400),
                DialogBody.plainMessage(MenuText.muted("Join every day: each day adds +" + perDay
                        + "% rare-reward luck to your Daily Crate, up to +" + maximum
                        + "%. Miss a day and it resets. Days change at 00:00 UTC."), 400)));
        String plain = "Streak: " + live + " days (best " + state.best() + "). Daily Crate luck +" + luck
                + "%. Daily Crate: " + crate + ".\nJoin every day for +" + perDay + "% luck, up to +" + maximum
                + "%. Miss a day and it resets.";
        if (!clientSupport.supportsDialogs(player)) {
            if (!forms.menu(player, "Daily Streak", plain, List.of())) {
                player.sendMessage(Component.text(plain, NamedTextColor.GRAY));
            }
            return;
        }
        Screens.showStandalone(player, "Daily Streak", page, List.of(), 1);
    }

    private void save() {
        try {
            store.persist();
        } catch (RuntimeException failure) {
            plugin.getLogger().warning("Could not save login streaks: " + failure.getMessage());
        }
    }
}
