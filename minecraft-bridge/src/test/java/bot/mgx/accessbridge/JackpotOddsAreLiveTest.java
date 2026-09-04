package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The jackpot odds are a setting, so nothing may quote the shipped default.
 *
 * <p>{@code crate.hidden-amethyst-one-in} became owner-editable, and the roll was moved
 * onto it — but every place that <em>shows</em> the number kept reading the constant. A
 * server retuned to 1 in 250,000 rolled at 250,000 and announced "1 in 500,000", which is
 * the worst version of the bug: the caption is the only part a player can check.
 *
 * <p>A second roll in {@code CrateKind} still used the constant too. It was unreachable,
 * so it changed nothing, but wiring it up later would have silently restored the old
 * odds. It is gone; this keeps it gone.
 */
final class JackpotOddsAreLiveTest {
    /**
     * Where naming the constant is legitimate: its own declaration, the alias, the
     * setting's declared default, and the helper that reads the live value.
     */
    private static final Set<String> MAY_NAME_THE_CONSTANT = Set.of(
            "CosmeticCatalog.java", "CrateCatalog.java", "GameVariableStore.java");

    @Test
    void nothingElseReadsTheShippedDefault() throws Exception {
        List<String> offenders = new ArrayList<>();
        for (Path source : mainSources()) {
            String name = source.getFileName().toString();
            if (MAY_NAME_THE_CONSTANT.contains(name)) {
                continue;
            }
            if (Files.readString(source, StandardCharsets.UTF_8).contains("HIDDEN_AMETHYST_ONE_IN")) {
                offenders.add(name);
            }
        }
        assertTrue(offenders.isEmpty(),
                "these quote the shipped jackpot default instead of "
                        + "CrateCatalog.hiddenAmethystOneIn(): " + offenders);
    }

    /** The helper must go through the tuning hook, not hand back the constant. */
    @Test
    void theHelperReadsTheTuningHook() throws Exception {
        String source = Files.readString(
                Path.of("src/main/java/bot/mgx/accessbridge/CrateCatalog.java"),
                StandardCharsets.UTF_8);
        int at = source.indexOf("static int hiddenAmethystOneIn()");
        assertTrue(at > 0, "the live-odds helper must exist");
        String body = source.substring(at, source.indexOf('}', at));
        assertTrue(body.contains("tuned(\"crate.hidden-amethyst-one-in\""),
                "the helper must read the setting, not return the constant");
    }

    private static List<Path> mainSources() throws IOException {
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            return files.filter(path -> path.toString().endsWith(".java")).toList();
        }
    }
}
