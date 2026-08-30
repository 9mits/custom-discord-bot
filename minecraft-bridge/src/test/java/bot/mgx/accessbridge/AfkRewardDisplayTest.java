package bot.mgx.accessbridge;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AfkRewardDisplayTest {
    @Test
    void boostedBarAlternatesAHeadlineWithTheCountdown() {
        AfkRewardDisplay.Status status = new AfkRewardDisplay.Status(
                3, 5, 12, 2, 60, 17 * 60_000L
        );

        String headline = plain(AfkRewardDisplay.bossBar(status, 0L));
        String countdown = plain(AfkRewardDisplay.bossBar(status, 5_000L));
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
        AfkRewardDisplay.Status status = new AfkRewardDisplay.Status(
                6, 14, 30, 4, 60, 0L
        );

        assertEquals(
                "★ BOOSTED REWARD READY! ★",
                plain(AfkRewardDisplay.bossBar(status, 0L))
        );
        assertTrue(plain(AfkRewardDisplay.tierUp(status)).contains(
                "You reached Online Tier 6. Your next payout includes 14 bonus keys."
        ));
    }

    @Test
    void noPopulationBoostKeepsThePlainOnlineRewardTimer() {
        AfkRewardDisplay.Status status = new AfkRewardDisplay.Status(
                1, 1, 3, 0, 30, 90_000L
        );

        assertEquals(
                "NEXT ONLINE REWARD IN 1M",
                plain(AfkRewardDisplay.bossBar(status, 0L))
        );
    }

    @Test
    void largerPopulationBoostsChangeTheWholeBarColour() {
        assertEquals(net.kyori.adventure.bossbar.BossBar.Color.YELLOW,
                AfkRewardDisplay.barColor(0));
        assertEquals(net.kyori.adventure.bossbar.BossBar.Color.GREEN,
                AfkRewardDisplay.barColor(1));
        assertEquals(net.kyori.adventure.bossbar.BossBar.Color.BLUE,
                AfkRewardDisplay.barColor(2));
        assertEquals(net.kyori.adventure.bossbar.BossBar.Color.PINK,
                AfkRewardDisplay.barColor(4));
    }

    private static String plain(net.kyori.adventure.text.Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
