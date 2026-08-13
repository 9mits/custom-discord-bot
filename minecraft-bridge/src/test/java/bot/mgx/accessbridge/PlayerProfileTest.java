package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerProfileTest {
    @Test
    void profileValuesAreBoundedToDiscordMilestones() {
        PlayerProfile profile = new PlayerProfile(99, 20, true);

        assertEquals(50, profile.level());
        assertEquals(5, profile.extraHearts());
        assertTrue(profile.elite());
    }

    @Test
    void elitePerkRequiresLevelFifty() {
        PlayerProfile profile = new PlayerProfile(40, 5, true);

        assertFalse(profile.elite());
    }

    @Test
    void negativeValuesBecomeUnranked() {
        PlayerProfile profile = new PlayerProfile(-5, -1, false);

        assertEquals(PlayerProfile.NONE, profile);
    }
}
