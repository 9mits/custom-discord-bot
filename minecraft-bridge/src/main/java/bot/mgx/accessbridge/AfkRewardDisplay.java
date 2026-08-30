package bot.mgx.accessbridge;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

/** Short, edition-neutral copy for rewards earned by staying connected. */
final class AfkRewardDisplay {
    record Status(
            int tier,
            int keys,
            int onlinePlayers,
            int onlineBonusKeys,
            int intervalMinutes,
            long rewardRemainingMillis
    ) { }

    private AfkRewardDisplay() {
    }

    static Component bossBar(Status status, long nowMillis) {
        if (status.rewardRemainingMillis() <= 0L) {
            return Component.text(
                    status.onlineBonusKeys() > 0
                            ? "★ BOOSTED REWARD READY! ★"
                            : "★ ONLINE REWARD READY! ★",
                    NamedTextColor.GREEN,
                    TextDecoration.BOLD
            );
        }
        boolean showBoost = status.onlineBonusKeys() > 0
                && Math.floorMod(nowMillis / 5_000L, 2L) == 0L;
        if (showBoost) {
            return Component.text("★ REWARDS BOOSTED! ", NamedTextColor.GREEN,
                            TextDecoration.BOLD)
                    .append(Component.text(status.onlinePlayers() + " PLAYERS ONLINE ",
                            NamedTextColor.WHITE))
                    .append(Component.text("★", NamedTextColor.GREEN, TextDecoration.BOLD));
        }
        return Component.text(
                "NEXT " + (status.onlineBonusKeys() > 0 ? "BOOSTED " : "ONLINE ")
                        + "REWARD IN " + compactTime(status.rewardRemainingMillis()),
                NamedTextColor.WHITE,
                TextDecoration.BOLD
        );
    }

    static Component tierUp(Status status) {
        return Component.text("REWARDS BOOSTED! ", NamedTextColor.LIGHT_PURPLE,
                        TextDecoration.BOLD)
                .append(Component.text("You reached Online Tier " + status.tier()
                        + ". Your next payout includes " + status.keys() + " bonus "
                        + (status.keys() == 1 ? "key" : "keys") + ".", NamedTextColor.WHITE));
    }

    static BossBar.Color barColor(int onlineBonusKeys) {
        return switch (Math.max(0, onlineBonusKeys)) {
            case 0 -> BossBar.Color.YELLOW;
            case 1 -> BossBar.Color.GREEN;
            case 2 -> BossBar.Color.BLUE;
            case 3 -> BossBar.Color.PURPLE;
            default -> BossBar.Color.PINK;
        };
    }

    private static String compactTime(long remainingMillis) {
        long seconds = Math.max(1L, (remainingMillis + 999L) / 1_000L);
        long hours = seconds / 3_600L;
        long minutes = (seconds % 3_600L) / 60L;
        if (hours > 0L) {
            return hours + "H" + (minutes > 0L ? " " + minutes + "M" : "");
        }
        return minutes > 0L ? minutes + "M" : seconds + "S";
    }
}
