package bot.mgx.accessbridge;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

/**
 * The difference between a bug and an event is that somebody announced it.
 *
 * <p>An effect that simply happens reads as the server breaking. The same effect
 * with three seconds of countdown, a title card, a name on a boss bar and a
 * record playing underneath reads as something the server is <em>doing to
 * you</em>, on purpose, right now. This class is only that framing — it changes
 * nothing about the world and holds no state of its own.
 */
final class EventShow {
    /** How long the buildup runs before the effect actually lands. */
    static final long TELEGRAPH_TICKS = 70L;

    private static final Title.Times FLASH = Title.Times.times(
            Duration.ofMillis(100), Duration.ofMillis(700), Duration.ofMillis(200)
    );
    private static final Title.Times HOLD = Title.Times.times(
            Duration.ofMillis(150), Duration.ofMillis(1600), Duration.ofMillis(400)
    );

    private final MGXAccessBridge plugin;

    EventShow(MGXAccessBridge plugin) {
        this.plugin = plugin;
    }

    /**
     * Counts an event in, then runs it.
     *
     * <p>The audience is a supplier rather than a list because it is re-read on
     * every beat: somebody who walks into the area during the countdown should
     * see the rest of it, and get the event.
     */
    void telegraph(
            Supplier<List<Player>> audience,
            String name,
            String tease,
            TextColor colour,
            Runnable land
    ) {
        beat(audience, 0L, () -> {
            Component warning = Component.text("! INCOMING !", NamedTextColor.RED, TextDecoration.BOLD);
            Component sub = Component.text(tease, NamedTextColor.WHITE);
            for (Player player : audience.get()) {
                player.showTitle(Title.title(warning, sub, FLASH));
                player.playSound(player.getLocation(), Sound.BLOCK_BELL_USE, SoundCategory.MASTER, 1f, 0.6f);
            }
        });
        // Three rising beeps. Pitch climbing is what makes a countdown feel like
        // a countdown rather than three numbers appearing.
        for (int step = 3; step >= 1; step--) {
            int shown = step;
            float pitch = 0.8f + (3 - step) * 0.35f;
            beat(audience, 20L + (3L - step) * 15L, () -> {
                Component number = Component.text(shown, colour, TextDecoration.BOLD);
                for (Player player : audience.get()) {
                    player.showTitle(Title.title(number, Component.empty(), FLASH));
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING,
                            SoundCategory.MASTER, 1f, pitch);
                }
            });
        }
        beat(audience, TELEGRAPH_TICKS, () -> {
            Component headline = Component.text(name, colour, TextDecoration.BOLD);
            Component sub = Component.text(tease, NamedTextColor.WHITE);
            for (Player player : audience.get()) {
                player.showTitle(Title.title(headline, sub, HOLD));
                player.playSound(player.getLocation(), Sound.ITEM_TRIDENT_THUNDER,
                        SoundCategory.MASTER, 1f, 1.4f);
                player.playSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL,
                        SoundCategory.MASTER, 0.4f, 1.6f);
            }
            land.run();
        });
    }

    /** A named bar so nobody has to guess what is happening or how long is left. */
    BossBar bar(String name, BossBar.Color colour) {
        return BossBar.bossBar(
                Component.text(name, NamedTextColor.WHITE, TextDecoration.BOLD),
                1f,
                colour,
                BossBar.Overlay.NOTCHED_10
        );
    }

    void showBar(BossBar bar, List<Player> audience, float progress) {
        bar.progress(Math.max(0f, Math.min(1f, progress)));
        audience.forEach(player -> player.showBossBar(bar));
    }

    void hideBar(BossBar bar, List<Player> audience) {
        audience.forEach(player -> player.hideBossBar(bar));
    }

    /** Starts a record under the event. Records are their own volume slider. */
    void music(List<Player> audience, Sound track) {
        for (Player player : audience) {
            player.stopSound(SoundCategory.RECORDS);
            player.playSound(player.getLocation(), track, SoundCategory.RECORDS, 1f, 1f);
        }
    }

    void stopMusic(List<Player> audience) {
        audience.forEach(player -> player.stopSound(SoundCategory.RECORDS));
    }

    /** The curtain call, so the end reads as an ending rather than a stop. */
    void finale(List<Player> audience, String name) {
        Component headline = Component.text(name + " OVER", NamedTextColor.GOLD, TextDecoration.BOLD);
        Component sub = Component.text("Well played.", NamedTextColor.WHITE);
        for (Player player : audience) {
            player.showTitle(Title.title(headline, sub, HOLD));
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE,
                    SoundCategory.MASTER, 1f, 1f);
        }
    }

    private void beat(Supplier<List<Player>> audience, long delay, Runnable body) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (audience.get().isEmpty()) {
                return;
            }
            body.run();
        }, delay);
    }
}
