package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class CommandArgsTest {
    @Test
    void aLeadingCopyOfTheSenderNameIsDropped() {
        assertArrayEquals(
                new String[0],
                CommandArgs.withoutEchoedSender("Steve", new String[]{"Steve"})
        );
        assertArrayEquals(
                new String[]{"hand"},
                CommandArgs.withoutEchoedSender("Steve", new String[]{"Steve", "hand"})
        );
    }

    @Test
    void aRealSubcommandIsLeftAlone() {
        assertArrayEquals(
                new String[]{"set", "Alex", "100"},
                CommandArgs.withoutEchoedSender("Steve", new String[]{"set", "Alex", "100"})
        );
    }

    @Test
    void aPlayerNamedAfterASubcommandKeepsThatWord() {
        assertArrayEquals(
                new String[]{"clan", "Alex", "100"},
                CommandArgs.withoutEchoedSender("clan", new String[]{"clan", "Alex", "100"})
        );
        assertArrayEquals(
                new String[]{"hand"},
                CommandArgs.withoutEchoedSender("hand", new String[]{"hand"})
        );
    }

    @Test
    void aFloodgateDotPrefixStillMatches() {
        assertArrayEquals(
                new String[]{"wealth"},
                CommandArgs.withoutEchoedSender(".Steve", new String[]{"Steve", "wealth"})
        );
        assertArrayEquals(
                new String[0],
                CommandArgs.withoutEchoedSender("Steve", new String[]{".Steve"})
        );
    }
}
