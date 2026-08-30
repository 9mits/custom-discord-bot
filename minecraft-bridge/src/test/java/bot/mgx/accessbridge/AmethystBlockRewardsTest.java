package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AmethystBlockRewardsTest {
    @Test
    void maxedPickaxesMeetTheTwoAndFivePlayerTimingTargets() {
        double twoPlayerDps = AmethystBlockRewards.groupDamagePerSecond(10d, 2);
        double fivePlayerDps = AmethystBlockRewards.groupDamagePerSecond(25d, 5);

        assertEquals(600d, AmethystBlockRewards.MAX_HEALTH / twoPlayerDps, 0.01d);
        assertEquals(300d, AmethystBlockRewards.MAX_HEALTH / fivePlayerDps, 0.01d);
    }

    @Test
    void contributionKeysIncreaseWithDamageWhileNonMinersGetNone() {
        UUID high = UUID.randomUUID();
        UUID low = UUID.randomUUID();
        UUID none = UUID.randomUUID();
        Map<UUID, Double> damage = new LinkedHashMap<>();
        damage.put(high, 4_000d);
        damage.put(low, 1_000d);

        assertTrue(AmethystBlockRewards.contributionKeys(high, damage)
                > AmethystBlockRewards.contributionKeys(low, damage));
        assertEquals(0, AmethystBlockRewards.contributionKeys(none, damage));
    }

    @Test
    void rewardWavesCoverEveryTwentyPercentThreshold() {
        assertEquals(java.util.List.of(80, 60, 40, 20),
                java.util.Arrays.stream(AmethystBlockRewards.REWARD_HEALTH_PERCENTAGES)
                        .boxed().toList());
    }
}
