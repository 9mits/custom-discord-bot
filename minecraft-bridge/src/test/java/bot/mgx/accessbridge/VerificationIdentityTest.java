package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerificationIdentityTest {
    private static final UUID BEDROCK = UUID.fromString("00000000-0000-0000-0009-01f9d1ebbeb2");
    private static final UUID JAVA = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

    @Test
    void floodgateUuidsAreDetectedFromThePrefix() {
        assertTrue(VerificationIdentity.isFloodgateUuid(BEDROCK));
        assertFalse(VerificationIdentity.isFloodgateUuid(JAVA));
        assertFalse(VerificationIdentity.isFloodgateUuid(null));
    }

    @Test
    void aDottedLoginNameIsTreatedAsBedrockEvenWithoutThePlayerObject() {
        VerificationIdentity.Resolved resolved = VerificationIdentity.resolve(BEDROCK, ".Real Name");

        assertEquals(MinecraftEdition.BEDROCK, resolved.edition());
        assertEquals("Real Name", resolved.username());
        assertEquals("2172480372402", resolved.xuid());
    }

    @Test
    void aJavaLoginStaysJava() {
        VerificationIdentity.Resolved resolved = VerificationIdentity.resolve(JAVA, "TestPlayer");

        assertEquals(MinecraftEdition.JAVA, resolved.edition());
        assertEquals("TestPlayer", resolved.username());
        assertNull(resolved.xuid());
    }

    @Test
    void aLeadingDotOnAJavaUuidStillMeansBedrock() {
        VerificationIdentity.Resolved resolved = VerificationIdentity.resolve(JAVA, ".Steve");

        assertEquals(MinecraftEdition.BEDROCK, resolved.edition());
        assertEquals("Steve", resolved.username());
    }
}
