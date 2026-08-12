package bot.mgx.accessbridge;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SignedProtocolTest {
    private static final byte[] SECRET = new byte[32];

    @Test
    void signedEnvelopeRoundTrips() {
        Clock clock = Clock.fixed(Instant.ofEpochSecond(1_700_000_000), ZoneOffset.UTC);
        SignedProtocol protocol = new SignedProtocol(SECRET, clock);
        JsonObject payload = new JsonObject();
        payload.addProperty("server_id", "mysterious-smp-x");

        JsonObject verified = protocol.verify(protocol.create("HELLO", "hello-1", payload));

        assertEquals("HELLO", verified.get("type").getAsString());
        assertEquals("mysterious-smp-x", verified.getAsJsonObject("payload").get("server_id").getAsString());
    }

    @Test
    void expiredTimestampIsRejected() {
        SignedProtocol signer = new SignedProtocol(
                SECRET,
                Clock.fixed(Instant.ofEpochSecond(1_700_000_000), ZoneOffset.UTC)
        );
        SignedProtocol verifier = new SignedProtocol(
                SECRET,
                Clock.fixed(Instant.ofEpochSecond(1_700_000_031), ZoneOffset.UTC)
        );
        String message = signer.create("HEARTBEAT", "heartbeat-1", new JsonObject());

        assertThrows(SecurityException.class, () -> verifier.verify(message));
    }

    @Test
    void reusedNonceIsRejected() {
        Clock clock = Clock.fixed(Instant.ofEpochSecond(1_700_000_000), ZoneOffset.UTC);
        SignedProtocol protocol = new SignedProtocol(SECRET, clock);
        String message = protocol.create("HEARTBEAT", "heartbeat-1", new JsonObject());
        protocol.verify(message);

        assertThrows(SecurityException.class, () -> protocol.verify(message));
    }

    @Test
    void verifiesPythonCanonicalJsonVector() {
        byte[] sequentialSecret = new byte[32];
        for (int index = 0; index < sequentialSecret.length; index++) {
            sequentialSecret[index] = (byte) index;
        }
        SignedProtocol protocol = new SignedProtocol(
                sequentialSecret,
                Clock.fixed(Instant.ofEpochSecond(1_700_000_000), ZoneOffset.UTC)
        );
        String pythonEnvelope = "{\"idempotency_key\":\"cross-language-1\","
                + "\"nonce\":\"00112233445566778899aabbccddeeff\","
                + "\"payload\":{\"action\":\"SYNC_PENDING\",\"applications\":[{"
                + "\"application_id\":7,\"claimed_username\":\"Name <七>\","
                + "\"edition\":\"BEDROCK\",\"expires_at\":1700000600,"
                + "\"normalized_username\":\"name <七>\"}],\"full\":true},"
                + "\"signature\":\"702e3f62905b8ae1ee78dbfc75ce065f43b6da2a8819d243c6326715f656e77e\","
                + "\"timestamp\":1700000000,\"type\":\"ACTION\"}";

        JsonObject verified = protocol.verify(pythonEnvelope);

        assertEquals("SYNC_PENDING", verified.getAsJsonObject("payload").get("action").getAsString());
    }
}
