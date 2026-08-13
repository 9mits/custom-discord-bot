package bot.mgx.accessbridge;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerificationEventStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void unacknowledgedVerificationSurvivesRestart() throws Exception {
        Path file = temporaryDirectory.resolve("verification-events.json");
        JsonObject payload = new JsonObject();
        payload.addProperty("application_id", 42);

        VerificationEventStore first = new VerificationEventStore(file);
        first.put("verification:42:uuid", payload);

        VerificationEventStore restarted = new VerificationEventStore(file);
        assertEquals(42, restarted.snapshot().get("verification:42:uuid").get("application_id").getAsInt());

        restarted.remove("verification:42:uuid");
        assertTrue(new VerificationEventStore(file).snapshot().isEmpty());
    }
}
