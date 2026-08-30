package bot.mgx.accessbridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

/** Compact, edition-neutral copy and progress arithmetic for the live AFK reward bar. */
final class AfkRewardDisplay {
    record Status(
            int tier,
            int keys,
            int onlinePlayers,
            int onlineBonusKeys,
            int intervalMinutes,
            long rewardRemainingMillis,
            int nextTier,
            float tierProgress
    ) { }

    private AfkRewardDisplay() {
    }

    static Component bossBar(Status status) {
        String rewardLeft = KeyTimer.label(status.rewardRemainingMillis());
        String rate = status.keys() + " " + (status.keys() == 1 ? "key" : "keys")
                + (status.intervalMinutes() == 60 ? "/hr" : "/reward");
        String online = "+" + status.onlineBonusKeys() + " from "
                + status.onlinePlayers() + " online";
        String tierGoal = status.nextTier() <= 0
                ? "MAX"
                : "T" + status.nextTier() + " " + Math.round(status.tierProgress() * 100f) + "%";
        String reward = rewardLeft.isEmpty() ? "reward ready" : rewardLeft;
        return Component.text("AFK T" + status.tier(), NamedTextColor.LIGHT_PURPLE,
                        TextDecoration.BOLD)
                .append(Component.text(" • " + rate + " (" + online + ") • "
                        + reward + " • " + tierGoal, NamedTextColor.WHITE));
    }

    static Component tierUp(Status status) {
        return Component.text("AFK REWARDS BOOSTED • ", NamedTextColor.LIGHT_PURPLE,
                        TextDecoration.BOLD)
                .append(Component.text("Tier " + status.tier() + " now pays "
                        + status.keys() + " " + (status.keys() == 1 ? "key" : "keys")
                        + " per reward, including +" + status.onlineBonusKeys()
                        + " from " + status.onlinePlayers() + " online.", NamedTextColor.WHITE));
    }

    static float tierProgress(long lifetimeSeconds, int currentMinimumHours, int nextMinimumHours) {
        if (nextMinimumHours <= currentMinimumHours) {
            return 1f;
        }
        long current = Math.max(0L, currentMinimumHours) * 3_600L;
        long next = Math.max(0L, nextMinimumHours) * 3_600L;
        long earned = Math.max(0L, lifetimeSeconds - current);
        return Math.min(1f, (float) earned / (next - current));
    }
}
