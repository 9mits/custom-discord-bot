package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatRelayServiceTest {
    @Test
    void exactNamesAndAtMentionsMatchCaseInsensitively() {
        assertTrue(ChatRelayService.mentionsPlayer("hello 9mits", "9mits"));
        assertTrue(ChatRelayService.mentionsPlayer("Hello, @Nitori!", "nitori"));
        assertTrue(ChatRelayService.mentionsPlayer("(PLAYER_NAME)", "player_name"));
    }

    @Test
    void substringsOfLongerMinecraftNamesDoNotMention() {
        assertFalse(ChatRelayService.mentionsPlayer("hello 9mitsuki", "9mits"));
        assertFalse(ChatRelayService.mentionsPlayer("hello x9mits", "9mits"));
        assertFalse(ChatRelayService.mentionsPlayer("hello 9mits_extra", "9mits"));
        assertFalse(ChatRelayService.mentionsPlayer("hello extra_9mits", "9mits"));
    }

    @Test
    void missingInputsNeverMention() {
        assertFalse(ChatRelayService.mentionsPlayer(null, "9mits"));
        assertFalse(ChatRelayService.mentionsPlayer("hello", null));
        assertFalse(ChatRelayService.mentionsPlayer("hello", " "));
    }
}
