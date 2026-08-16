package bot.mgx.accessbridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class SignedProtocol {
    static final long MAX_CLOCK_SKEW_SECONDS = 30;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final byte[] secret;
    private final Clock clock;
    private final ConcurrentHashMap<String, Long> nonces = new ConcurrentHashMap<>();

    SignedProtocol(byte[] secret) {
        this(secret, Clock.systemUTC());
    }

    SignedProtocol(byte[] secret, Clock clock) {
        this.secret = secret.clone();
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    String create(String type, String idempotencyKey, JsonObject payload) {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("type", type);
        envelope.addProperty("timestamp", clock.instant().getEpochSecond());
        envelope.addProperty("nonce", UUID.randomUUID().toString().replace("-", ""));
        envelope.addProperty("idempotency_key", idempotencyKey);
        envelope.add("payload", payload.deepCopy());
        envelope.addProperty("signature", signature(envelope));
        return GSON.toJson(sort(envelope));
    }

    JsonObject verify(String text) {
        JsonObject envelope;
        try {
            envelope = JsonParser.parseString(text).getAsJsonObject();
            envelope.get("type").getAsString();
            envelope.get("idempotency_key").getAsString();
            envelope.getAsJsonObject("payload");
        } catch (RuntimeException exception) {
            throw new SecurityException("Malformed signed bridge message", exception);
        }
        long timestamp;
        String nonce;
        String provided;
        try {
            timestamp = envelope.get("timestamp").getAsLong();
            nonce = envelope.get("nonce").getAsString();
            provided = envelope.get("signature").getAsString();
        } catch (RuntimeException exception) {
            throw new SecurityException("Signed bridge fields are missing", exception);
        }
        long now = clock.instant().getEpochSecond();
        if (Math.abs(now - timestamp) > MAX_CLOCK_SKEW_SECONDS) {
            throw new SecurityException("Bridge message timestamp is outside the allowed window");
        }
        pruneNonces(now);
        String expected = signature(envelope);
        if (!MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                provided.getBytes(StandardCharsets.US_ASCII)
        )) {
            throw new SecurityException("Bridge message signature is invalid");
        }
        if (nonces.putIfAbsent(nonce, timestamp + MAX_CLOCK_SKEW_SECONDS + 1) != null) {
            throw new SecurityException("Bridge message nonce was already used");
        }
        return envelope;
    }

    private void pruneNonces(long now) {
        if (nonces.size() > 10_000) {
            nonces.entrySet().removeIf(entry -> entry.getValue() <= now);
        }
    }

    private String signature(JsonObject source) {
        JsonObject unsigned = source.deepCopy();
        unsigned.remove("signature");
        byte[] canonical = GSON.toJson(sort(unsigned)).getBytes(StandardCharsets.UTF_8);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(canonical));
        } catch (Exception exception) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", exception);
        }
    }

    private static JsonElement sort(JsonElement element) {
        if (element.isJsonObject()) {
            JsonObject sorted = new JsonObject();
            ArrayList<Map.Entry<String, JsonElement>> entries = new ArrayList<>(element.getAsJsonObject().entrySet());
            entries.sort(Comparator.comparing(Map.Entry::getKey));
            for (Map.Entry<String, JsonElement> entry : entries) {
                sorted.add(entry.getKey(), sort(entry.getValue()));
            }
            return sorted;
        }
        if (element.isJsonArray()) {
            JsonArray sorted = new JsonArray();
            for (JsonElement child : element.getAsJsonArray()) {
                sorted.add(sort(child));
            }
            return sorted;
        }
        return element.deepCopy();
    }
}
