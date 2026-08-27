package bot.mgx.accessbridge;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BridgeClientTest {
    @Test
    void successfulReverseApprovalReplayRetriesLobbyRelease() {
        JsonObject payload = new JsonObject();
        payload.addProperty("minecraft_uuid", "123e4567-e89b-12d3-a456-426614174000");
        payload.addProperty("reverse_request_id", "request-one");

        assertTrue(BridgeClient.shouldReplayVerificationRelease(
                "APPROVE", payload, new ProcessedActionStore.Result(true, "")
        ));
        assertFalse(BridgeClient.shouldReplayVerificationRelease(
                "APPROVE", payload, new ProcessedActionStore.Result(false, "failed")
        ));
        assertFalse(BridgeClient.shouldReplayVerificationRelease(
                "REVOKE", payload, new ProcessedActionStore.Result(true, "")
        ));
    }

    @Test
    void ordinaryApprovalReplayDoesNotTryToReleaseALobbySession() {
        JsonObject payload = new JsonObject();
        payload.addProperty("minecraft_uuid", "123e4567-e89b-12d3-a456-426614174000");

        assertFalse(BridgeClient.shouldReplayVerificationRelease(
                "APPROVE", payload, new ProcessedActionStore.Result(true, "")
        ));
    }
}
