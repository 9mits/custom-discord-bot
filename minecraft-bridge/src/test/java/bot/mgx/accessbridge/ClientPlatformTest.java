package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
