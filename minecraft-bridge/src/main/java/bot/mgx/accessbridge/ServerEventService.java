package bot.mgx.accessbridge;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.server.ServerListPingEvent;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Makes the multiplier events visible: a boss bar while they run, a banner on
 * join, and the event name on the second line of the server list.
 *
 * <p>A multiplier nobody can see is indistinguishable from no multiplier, so the
 * presentation is not decoration here — it is the feature.
 */
final class ServerEventService implements Listener {
    private static final long REFRESH_TICKS = 40L;
    private static final long JOIN_DELAY_TICKS = 50L;

    private final MGXAccessBridge plugin;
    private final ServerEventStore store;
    private final PersonalNotificationService notifications;
    /** Every multiplier shares one line so stacked events never bury the screen in bars. */
    private final Set<ServerEventType> visible = EnumSet.noneOf(ServerEventType.class);
    private BossBar bar;
    private BukkitTask task;

    ServerEventService(
            MGXAccessBridge plugin,
            ServerEventStore store,
            PersonalNotificationService notifications
    ) {
        this.plugin = plugin;
        this.store = store;
        this.notifications = notifications;
    }

    void start() {
        task = plugin.getServer().getScheduler().runTaskTimer(
                plugin, this::refresh, REFRESH_TICKS, REFRESH_TICKS
        );
    }

    void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (bar != null) {
            plugin.getServer().getOnlinePlayers().forEach(player -> player.hideBossBar(bar));
            bar = null;
        }
        visible.clear();
    }

    /** What a payout should be multiplied by right now. */
    int multiplier(ServerEventType type) {
        return store.multiplier(type, System.currentTimeMillis());
    }

    boolean active(ServerEventType type) {
        return store.active(type, System.currentTimeMillis());
    }

    List<ServerEventType> live() {
        return new ArrayList<>(store.snapshot(System.currentTimeMillis()).keySet());
    }

    /**
     * Starts or stops one event and tells everybody.
     *
     * @param seconds how long to run, or 0 to run until turned off
     * @return true when this changed anything
     */
    boolean set(ServerEventType type, boolean enabled, long seconds) {
        long now = System.currentTimeMillis();
        long deadline = enabled && seconds > 0
                ? now + Duration.ofSeconds(seconds).toMillis()
                : ServerEventStore.NO_DEADLINE;
        if (!store.set(type, enabled, deadline, now)) {
            return false;
        }
        if (!enabled) {
            visible.remove(type);
        }
        announce(type, enabled, seconds);
        refresh();
        return true;
    }

    private void announce(ServerEventType type, boolean enabled, long seconds) {
        Component headline = enabled
                ? Component.text(type.motdLabel(), colourOf(type), TextDecoration.BOLD)
                : Component.text(type.displayName() + " has ended", NamedTextColor.GRAY,
                        TextDecoration.BOLD);
        Component sub = enabled
                ? Component.text(
                        seconds > 0 ? "Running for " + humanDuration(seconds * 1_000L) : "Live now",
                        NamedTextColor.WHITE)
                : Component.text("Back to normal rates.", NamedTextColor.GRAY);
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            notifications.notify(
                    player,
                    Component.text("EVENT » ", TextColor.color(0xFF9900), TextDecoration.BOLD)
                            .append(headline)
                            .append(Component.text("  •  ", NamedTextColor.DARK_GRAY))
                            .append(sub),
                    Component.text(
                            enabled
                                    ? type.motdLabel() + "  •  "
                                            + (seconds > 0
                                            ? humanDuration(seconds * 1_000L) + " remaining"
                                            : "Live now")
                                    : type.displayName() + " has ended",
                            enabled ? colourOf(type) : NamedTextColor.GRAY,
                            TextDecoration.BOLD
                    )
            );
            player.showTitle(Title.title(headline, sub));
            player.playSound(
                    player.getLocation(),
                    enabled ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.BLOCK_NOTE_BLOCK_BASS,
                    SoundCategory.MASTER, 1f, enabled ? 1f : 0.7f
            );
        }
    }

    /**
     * The join banner. Delayed like the update notice so it does not land while
     * the client is still drawing the world.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            List<ServerEventType> running = live();
            if (running.isEmpty()) {
                return;
            }
            BossBar current = barFor();
            current.name(Component.text(stackedTitle(running), NamedTextColor.WHITE,
                    TextDecoration.BOLD));
            player.showBossBar(current);
            plugin.broadcasts().announceBanner(List.of(player), "EVENT LIVE", bannerBody(running));
            notifications.actionBar(player, eventActionBar(running));
        }, JOIN_DELAY_TICKS);
    }

    /**
     * Replaces the second line of the server list entry, never the first.
     *
     * <p>The MOTD is configured in server.properties and its first line is the
     * server's identity; only the strapline underneath is ours to take over.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPing(ServerListPingEvent event) {
        List<ServerEventType> running = live();
        if (running.isEmpty()) {
            return;
        }
        LegacyComponentSerializer legacy = LegacyComponentSerializer.legacySection();
        String firstLine = legacy.serialize(event.motd()).split("\n", 2)[0];
        StringBuilder line = new StringBuilder();
        for (ServerEventType type : running) {
            line.append(line.isEmpty() ? "" : " + ").append(type.motdLabel());
        }
        event.motd(legacy.deserialize(firstLine)
                .append(Component.newline())
                .append(Component.text(line.toString(), NamedTextColor.YELLOW, TextDecoration.BOLD)));
    }

    private Component bannerBody(List<ServerEventType> running) {
        Component body = Component.text("  ", NamedTextColor.WHITE);
        for (int index = 0; index < running.size(); index++) {
            ServerEventType type = running.get(index);
            if (index > 0) {
                body = body.append(Component.text("  and  ", NamedTextColor.GRAY));
            }
            body = body.append(Component.text(type.displayName(), colourOf(type), TextDecoration.BOLD));
        }
        return body.append(Component.text(" is live right now!", NamedTextColor.WHITE, TextDecoration.BOLD));
    }

    private Component eventActionBar(List<ServerEventType> running) {
        String names = running.stream().map(ServerEventType::motdLabel)
                .reduce((left, right) -> left + " + " + right)
                .orElse("EVENT LIVE");
        return Component.text(names + "  •  Live now", NamedTextColor.GOLD, TextDecoration.BOLD);
    }

    /** Drops finished events, then renders every live multiplier on one boss bar. */
    private void refresh() {
        long now = System.currentTimeMillis();
        List<ServerEventType> running = live();
        for (ServerEventType type : Set.copyOf(visible)) {
            if (!running.contains(type)) {
                visible.remove(type);
                // Manual stops already announced from set(); only an event that was
                // still visible here reached its own deadline.
                announce(type, false, 0L);
            }
        }
        store.prune(now);
        if (running.isEmpty()) {
            if (bar != null) {
                BossBar empty = bar;
                plugin.getServer().getOnlinePlayers().forEach(player -> player.hideBossBar(empty));
                bar = null;
            }
            visible.clear();
            return;
        }
        visible.addAll(running);
        BossBar current = barFor();
        current.name(Component.text(stackedTitle(running), NamedTextColor.WHITE,
                TextDecoration.BOLD));
        plugin.getServer().getOnlinePlayers().forEach(player -> player.showBossBar(current));
    }

    static String stackedTitle(List<ServerEventType> running) {
        return running.stream().map(ServerEventType::displayName)
                .reduce((left, right) -> left + " - " + right)
                .orElse("EVENT LIVE");
    }

    private BossBar barFor() {
        if (bar == null) {
            bar = BossBar.bossBar(
                    Component.text("EVENT LIVE", NamedTextColor.WHITE, TextDecoration.BOLD),
                    // Full and steady. Event lengths can differ, so one draining bar
                    // would lie about at least one of the stacked events.
                    1f,
                    BossBar.Color.YELLOW,
                    BossBar.Overlay.PROGRESS
            );
        }
        return bar;
    }

    static String humanDuration(long millis) {
        long totalMinutes = Math.max(0L, millis) / 60_000L;
        long days = totalMinutes / 1_440L;
        long hours = (totalMinutes % 1_440L) / 60L;
        long minutes = totalMinutes % 60L;
        if (days > 0) {
            return days + "d " + hours + "h";
        }
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        return Math.max(1L, minutes) + "m";
    }

    private static TextColor colourOf(ServerEventType type) {
        return switch (type) {
            case CRATE_LUCK -> TextColor.color(0xFFD54F);
            case FORTUNE -> TextColor.color(0x66BB6A);
            case KEY -> TextColor.color(0x4FC3F7);
            case MONEY -> TextColor.color(0xFF9900);
            case AIRDROP -> TextColor.color(0xB56CFF);
            case AMETHYST_BLOCK -> TextColor.color(0x9C5BE8);
            case MEGA_KEY -> TextColor.color(0x00E5FF);
        };
    }

}
