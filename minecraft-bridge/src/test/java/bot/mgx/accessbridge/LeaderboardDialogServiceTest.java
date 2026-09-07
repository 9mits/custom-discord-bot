package bot.mgx.accessbridge;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class LeaderboardDialogServiceTest {
    @Test
    void playerRowsReadTheUsernamePublishedByTheLeaderboardSnapshot() {
        JsonObject row = new JsonObject();
        row.addProperty("username", "VisiblePlayer");

        assertEquals("VisiblePlayer", LeaderboardDialogService.playerName(row));
    }
}
