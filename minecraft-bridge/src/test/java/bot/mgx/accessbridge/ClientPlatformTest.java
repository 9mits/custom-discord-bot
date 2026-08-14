package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientPlatformTest {
    @Test
    void javaClientsUseThePcLabel() {
        assertEquals(new ClientPlatform("JAVA", "PC"), ClientPlatform.JAVA);
    }

    @Test
    void bedrockDevicesAreGroupedForThePlayerList() {
        assertEquals(new ClientPlatform("BEDROCK", "MOBILE"), ClientPlatform.bedrock("IOS"));
        assertEquals(new ClientPlatform("BEDROCK", "PC"), ClientPlatform.bedrock("UWP"));
        assertEquals(new ClientPlatform("BEDROCK", "CONSOLE"), ClientPlatform.bedrock("XBOX"));
        assertEquals(new ClientPlatform("BEDROCK", "VR"), ClientPlatform.bedrock("GEARVR"));
        assertEquals(new ClientPlatform("BEDROCK", "OTHER"), ClientPlatform.bedrock("UNKNOWN"));
    }

    @Test
    void onlyBedrockRowsSpendPlayerListWidthOnTheDevice() {
        assertFalse(ClientPlatform.JAVA.showsDevice());
        assertTrue(ClientPlatform.bedrock("XBOX").showsDevice());
        assertTrue(ClientPlatform.bedrock("IOS").showsDevice());
    }
}
