package bot.mgx.accessbridge;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class OnlineRewardDisplayTest {
    @Test
    void boostedBarAlternatesAHeadlineWithTheCountdown() {
        OnlineRewardDisplay.Status status = new OnlineRewardDisplay.Status(
                3, 5, 12, 2, 60, 17 * 60_000L
        );

        String headline = plain(OnlineRewardDisplay.bossBar(status, 0L));
        String countdown = plain(OnlineRewardDisplay.bossBar(status, 5_000L));
        assertEquals(
                "★ REWARDS BOOSTED! 12 PLAYERS ONLINE ★",
                headline
        );
        assertEquals(
                "NEXT BOOSTED REWARD IN 17M",
                countdown
        );
        assertTrue(headline.length() <= 45);
        assertTrue(countdown.length() <= 45);
    }

    @Test
    void readyRewardAndTierUpgradeStaySimple() {
        OnlineRewardDisplay.Status status = new OnlineRewardDisplay.Status(
                6, 14, 30, 4, 60, 0L
        );

        assertEquals(
                "★ BOOSTED REWARD READY! ★",
                plain(OnlineRewardDisplay.bossBar(status, 0L))
        );
        assertTrue(plain(OnlineRewardDisplay.tierUp(status)).contains(
                "You reached Online Tier 6. Your next payout includes 14 bonus keys."
        ));
    }

    @Test
    void noPopulationBoostKeepsThePlainOnlineRewardTimer() {
        OnlineRewardDisplay.Status status = new OnlineRewardDisplay.Status(
                1, 1, 3, 0, 30, 90_000L
        );

        assertEquals(
                "NEXT ONLINE REWARD IN 1M",
                plain(OnlineRewardDisplay.bossBar(status, 0L))
        );
    }

    @Test
    void largerPopulationBoostsChangeTheWholeBarColour() {
        assertEquals(net.kyori.adventure.bossbar.BossBar.Color.YELLOW,
                OnlineRewardDisplay.barColor(0));
        assertEquals(net.kyori.adventure.bossbar.BossBar.Color.GREEN,
                OnlineRewardDisplay.barColor(1));
        assertEquals(net.kyori.adventure.bossbar.BossBar.Color.BLUE,
                OnlineRewardDisplay.barColor(2));
        assertEquals(net.kyori.adventure.bossbar.BossBar.Color.PINK,
                OnlineRewardDisplay.barColor(4));
    }

    private static String plain(net.kyori.adventure.text.Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
