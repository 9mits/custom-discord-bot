package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PendingVerificationCacheTest {
    @Test
    void javaNamesMatchCaseInsensitively() {
        PendingVerificationCache cache = new PendingVerificationCache();
        cache.put(new PendingVerification(
                1,
                MinecraftEdition.JAVA,
                "TestPlayer",
                "testplayer",
                Instant.now().getEpochSecond() + 60
        ));

        assertEquals(1, cache.match(MinecraftEdition.JAVA, "TESTPLAYER").orElseThrow().applicationId());
    }

    @Test
    void bedrockUsesRealNameWithSpacesRatherThanFloodgatePrefix() {
        PendingVerificationCache cache = new PendingVerificationCache();
        cache.put(new PendingVerification(
                2,
                MinecraftEdition.BEDROCK,
                "Real Name",
                "real name",
                Instant.now().getEpochSecond() + 60
        ));

        assertTrue(cache.match(MinecraftEdition.BEDROCK, "Real Name").isPresent());
        assertTrue(cache.matchLogin(".Real Name").isPresent());
    }

    @Test
    void fullSyncReplacesStaleEntries() {
        PendingVerificationCache cache = new PendingVerificationCache();
        cache.put(new PendingVerification(1, MinecraftEdition.JAVA, "First", "first", Long.MAX_VALUE));
        cache.replace(List.of(
                new PendingVerification(2, MinecraftEdition.JAVA, "Second", "second", Long.MAX_VALUE)
        ));

        assertTrue(cache.match(MinecraftEdition.JAVA, "First").isEmpty());
        assertEquals(2, cache.match(MinecraftEdition.JAVA, "Second").orElseThrow().applicationId());
    }

    @Test
    void automaticEditionMatchesJavaOrBedrock() {
        PendingVerification verification = new PendingVerification(
                3, MinecraftEdition.AUTO, "SharedName", "sharedname", Long.MAX_VALUE
        );
        assertTrue(verification.matches(MinecraftEdition.JAVA, "SharedName", 1));
        assertTrue(verification.matches(MinecraftEdition.BEDROCK, "SharedName", 1));
    }

    @Test
    void twoPendingRowsForTheSameNameMatchNeither() {
        PendingVerificationCache cache = new PendingVerificationCache();
        cache.put(new PendingVerification(1, MinecraftEdition.JAVA, "Steve", "steve", Long.MAX_VALUE));
        cache.put(new PendingVerification(2, MinecraftEdition.AUTO, "Steve", "steve", Long.MAX_VALUE));

        assertTrue(cache.match(MinecraftEdition.JAVA, "Steve").isEmpty());
    }

    @Test
    void aPrefixedBedrockLoginMatchesAJavaPendingRow() {
        PendingVerificationCache cache = new PendingVerificationCache();
        cache.put(new PendingVerification(
                9,
                MinecraftEdition.JAVA,
                "agentclanmanage",
                "agentclanmanage",
                Long.MAX_VALUE
        ));

        assertEquals(9, cache.matchLogin(".agentclanmanage").orElseThrow().applicationId());
    }

    @Test
    void floodgateUnderscoresMatchASpacedBedrockGamertag() {
        PendingVerificationCache cache = new PendingVerificationCache();
        cache.put(new PendingVerification(
                12,
                MinecraftEdition.BEDROCK,
                "FER GAMER3520",
                "fer gamer3520",
                Long.MAX_VALUE
        ));

        assertEquals(12, cache.matchLogin(".FER_GAMER3520").orElseThrow().applicationId());
        assertEquals(12, cache.match(MinecraftEdition.BEDROCK, "FER GAMER3520").orElseThrow().applicationId());
    }
}
