package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps a JDK trap out of the plugin, because it took the live server down.
 *
 * <p>{@code /autobuy} recorded when an order last ran by calling {@code entry.setValue}
 * from inside {@code autoOrders.entrySet().removeIf(...)}. ConcurrentHashMap hands that
 * predicate an immutable entry, so the call threw {@link UnsupportedOperationException}
 * — after the money had been taken and the items dropped, but before the timestamp was
 * written. Every standing order therefore stayed permanently due and fired once a second
 * instead of once an interval. One dropping order buried the world in item entities, the
 * server thread stalled merging them, Paper's watchdog fired, and every player on the
 * server was disconnected at the same instant.
 */
final class ConcurrentMapMutationTest {
    /** The JDK behaviour the plugin tripped over, pinned so the reason stays legible. */
    @Test
    void concurrentHashMapRefusesSetValueDuringRemoveIf() {
        Map<String, Integer> map = new ConcurrentHashMap<>(Map.of("a", 1));
        assertThrows(UnsupportedOperationException.class, () ->
                map.entrySet().removeIf(entry -> {
                    entry.setValue(2);
                    return false;
                }));
    }

    /**
     * No main-source {@code removeIf} may write through its entry.
     *
     * <p>The scan is deliberately blunt: it takes everything between {@code removeIf(}
     * and its matching parenthesis and refuses a {@code setValue} anywhere inside. A
     * false positive here is a lambda that should be rewritten anyway.
     */
    @Test
    void noRemoveIfPredicateWritesThroughItsEntry() throws Exception {
        List<String> offenders = new ArrayList<>();
        for (Path source : mainSources()) {
            String text = Files.readString(source, StandardCharsets.UTF_8);
            int at = text.indexOf("removeIf(");
            while (at >= 0) {
                String body = balanced(text, at + "removeIf".length());
                if (body.contains("setValue(")) {
                    offenders.add(source.getFileName() + " near offset " + at);
                }
                at = text.indexOf("removeIf(", at + 1);
            }
        }
        assertTrue(offenders.isEmpty(),
                "removeIf predicates that call setValue throw on a ConcurrentHashMap and "
                        + "abort the whole pass: " + offenders);
    }

    /** The text of one parenthesised argument list, starting at its opening bracket. */
    private static String balanced(String text, int open) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return text.substring(open, i + 1);
                }
            }
        }
        return text.substring(open);
    }

    private static List<Path> mainSources() throws IOException {
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            return files.filter(path -> path.toString().endsWith(".java")).toList();
        }
    }
}
