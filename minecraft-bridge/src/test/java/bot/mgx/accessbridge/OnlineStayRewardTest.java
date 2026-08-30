package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class OnlineStayRewardTest {
    @Test
    void rewardsConnectedTimeRatherThanLiteralAfkStatus() throws IOException {
        String crates = Files.readString(Path.of(
                "src/main/java/bot/mgx/accessbridge/CrateService.java"
        ));
        String afk = Files.readString(Path.of(
                "src/main/java/bot/mgx/accessbridge/AfkService.java"
        ));

        assertTrue(crates.contains("Statistic.PLAY_ONE_MINUTE"),
                "lifetime reward tiers must use persistent lifetime playtime");
        assertTrue(crates.contains("onlineRewardStarted.computeIfAbsent"),
                "every connected player needs a live reward interval");
        assertFalse(crates.contains("afk.sessionStartedAt"),
                "movement and active play must not disqualify stay rewards");
        assertFalse(afk.contains("Tier \" + tier + \" reward"),
                "/afk is a status toggle, not the reward trigger");
    }
}
