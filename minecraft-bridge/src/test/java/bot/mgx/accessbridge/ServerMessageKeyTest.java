package bot.mgx.accessbridge;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Holds the message layer to keys, and holds it to never throwing over one.
 *
 * <p>Making the Airdrop broadcasts configurable turned a sentence argument into a key
 * argument. Three call sites were converted and a fourth was not, so every looted
 * Airdrop threw {@code Unknown variable 'The Rare Amethyst Airdrop was looted.'} out of
 * the scheduled task that cleans the drop up. Nothing failed loudly: the task died, the
 * drop was never removed, and it looked from the outside like Airdrops had stopped.
 *
 * <p>Two properties keep that from recurring. A literal handed to the message layer must
 * look like a key, and the message layer must degrade to plain text rather than throw
 * when one slips through anyway.
 */
final class ServerMessageKeyTest {
    @TempDir
    Path temporary;

    private static final Pattern MESSAGE_CALL = Pattern.compile(
            "\\b(?:isSilenced|announce|render)\\(\\s*\"([^\"]*)\"");

    private static List<Path> mainSources() throws IOException {
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            return files.filter(path -> path.toString().endsWith(".java")).toList();
        }
    }

    /**
     * Follows the key one hop further, to methods that take one and pass it on.
     *
     * <p>This is the hop the outage actually happened on. {@code AirdropService.remove}
     * forwards its key to the message layer, so a sentence handed to <em>it</em> never
     * appears next to an {@code isSilenced} or {@code render} call and the direct check
     * above sails straight past it. Any method declaring a {@code String messageKey}
     * parameter is treated as part of the message layer, which is also why that
     * parameter is named that way rather than {@code message}.
     */
    @Test
    void everyLiteralHandedToAForwardingMethodIsAKey() throws Exception {
        List<String> sentences = new ArrayList<>();
        for (Path source : mainSources()) {
            String text = Files.readString(source, StandardCharsets.UTF_8);
            Matcher declarations = Pattern.compile(
                    "\\b(\\w+)\\([^)]*String messageKey").matcher(text);
            while (declarations.find()) {
                String method = declarations.group(1);
                Matcher calls = Pattern.compile(
                        "\\b" + Pattern.quote(method) + "\\(([^;]*?)\\)\\s*;").matcher(text);
                while (calls.find()) {
                    Matcher literals = Pattern.compile("\"([^\"]*)\"").matcher(calls.group(1));
                    while (literals.find()) {
                        String literal = literals.group(1);
                        if (!literal.startsWith("messages.")) {
                            sentences.add(source.getFileName() + ": " + method
                                    + "(... \"" + literal + "\")");
                        }
                    }
                }
            }
        }
        assertTrue(sentences.isEmpty(),
                "these pass a sentence to a method that forwards it to the message "
                        + "layer, which is exactly how looted Airdrops broke: " + sentences);
    }

    @Test
    void everyLiteralHandedToTheMessageLayerIsAKey() throws Exception {
        List<String> sentences = new ArrayList<>();
        for (Path source : mainSources()) {
            String text = Files.readString(source, StandardCharsets.UTF_8);
            Matcher calls = MESSAGE_CALL.matcher(text);
            while (calls.find()) {
                String literal = calls.group(1);
                if (!literal.startsWith("messages.")) {
                    sentences.add(source.getFileName() + ": \"" + literal + "\"");
                }
            }
        }
        assertTrue(sentences.isEmpty(),
                "these pass a sentence where a message key belongs, which is what broke "
                        + "looted Airdrops: " + sentences);
    }

    /**
     * The store throws for an unknown setting, which is right for a setting lookup and
     * wrong on a live server thread mid-broadcast. An unrecognised key is treated as the
     * literal text it appears to be.
     */
    @Test
    void anUnknownKeyIsPlainTextRatherThanAnException() throws Exception {
        GameVariableStore variables = new GameVariableStore(
                temporary.resolve("game-variables.json"), new YamlConfiguration());
        ServerMessages messages = new ServerMessages(variables);

        assertTrue(!messages.isSilenced("Not a key at all."),
                "an unknown key must not read as a silenced message — silently dropping "
                        + "the broadcast is the other way this bug hides");
        assertTrue(messages.isSilenced(""), "blank stays silenced");
    }
}
