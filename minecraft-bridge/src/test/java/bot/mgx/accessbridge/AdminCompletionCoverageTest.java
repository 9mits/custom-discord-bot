package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps tab completion level with the dispatcher.
 *
 * <p>A subcommand that dispatches but is not offered is invisible: nothing in game says
 * it exists, and the operator only finds it by remembering it. That is a real hazard,
 * but it is not the same thing as a deliberate legacy spelling. Three roots are
 * withheld on purpose — {@code airdrop}, {@code abuse} and {@code multiplier} — because
 * each duplicates an {@code event} path that does identical work and is itself offered.
 * They are named in {@link #SUPERSEDED} so the omission is a recorded decision rather
 * than something that merely looks like drift; anything else that stops being offered
 * fails here.
 *
 * <p>Only the canonical name of each subcommand is checked. Aliases are accepted on
 * purpose and deliberately not advertised, so completion offers one name per action
 * rather than every spelling of it — the second test holds that line in the other
 * direction.
 *
 * <p>Reads the switch rather than the running plugin because {@code onCommand} needs a
 * Bukkit server to invoke.
 */
final class AdminCompletionCoverageTest {
    private static final Path SOURCE =
            Path.of("src/main/java/bot/mgx/accessbridge/AdminCommandService.java");

    /** Reached through the empty-argument default rather than a label of its own. */
    private static final String DEFAULT_ACTION = "help";

    /**
     * Dispatched, but withheld from completion because an offered {@code event} path
     * already does the same work. Value is the replacement, so a reader does not have
     * to go looking for it.
     */
    private static final Map<String, String> SUPERSEDED = Map.of(
            "airdrop", "event airdrop start",
            "abuse", "event admin",
            "multiplier", "event multiplier"
    );

    @Test
    void everyDispatchedSubcommandIsOffered() throws IOException {
        List<String> missing = new ArrayList<>(canonicalActions());
        missing.removeAll(offered());
        missing.removeAll(SUPERSEDED.keySet());
        assertTrue(missing.isEmpty(),
                "subcommands that run but never tab-complete: " + missing
                        + " — add them to SUBCOMMANDS, or to SUPERSEDED with the path that replaces them");
    }

    @Test
    void everySupersededRootStillDispatchesAndItsReplacementIsOffered() throws IOException {
        Set<String> actions = canonicalActions();
        Set<String> offered = offered();
        List<String> broken = new ArrayList<>();
        SUPERSEDED.forEach((legacy, replacement) -> {
            // The legacy spelling must keep working; retiring it is a separate,
            // deliberate change, not something this list should hide.
            if (!actions.contains(legacy)) {
                broken.add(legacy + " no longer dispatches");
            }
            String family = replacement.split(" ")[0];
            if (!offered.contains(family)) {
                broken.add(legacy + " points at '" + replacement + "', which is not offered");
            }
        });
        assertTrue(broken.isEmpty(), "superseded roots are inconsistent: " + broken);
    }

    @Test
    void completionOffersCanonicalNamesOnly() throws IOException {
        List<String> unknown = new ArrayList<>(offered());
        unknown.removeAll(canonicalActions());
        unknown.remove(DEFAULT_ACTION);
        assertTrue(unknown.isEmpty(),
                "completion offers names that are aliases or handled by nothing: " + unknown
                        + " — offer one canonical name per action");
    }

    /**
     * The canonical name of every action {@code onCommand} dispatches.
     *
     * <p>Two shapes carry one: a {@code case} label group, whose first literal is the
     * canonical spelling, and the {@code action.equals(...)} guards that run ahead of
     * the switch because they carry their own permission check.
     */
    private static Set<String> canonicalActions() throws IOException {
        String body = between(source(), "String action = args.length == 0", "private void variables(");
        Set<String> actions = new LinkedHashSet<>();
        Matcher cases = Pattern.compile("case\\s+((?:\"[a-z-]+\"\\s*,\\s*)*\"[a-z-]+\")\\s*->")
                .matcher(body);
        while (cases.find()) {
            actions.add(firstLiteral(cases.group(1)));
        }
        Matcher guards = Pattern.compile("if\\s*\\(([^)]*action\\.equals\\([^)]*\\)[^{]*)\\)\\s*\\{")
                .matcher(body);
        while (guards.find()) {
            actions.add(firstLiteral(guards.group(1)));
        }
        return actions;
    }

    /** The string literals in the SUBCOMMANDS completion list. */
    private static Set<String> offered() throws IOException {
        String list = between(source(), "SUBCOMMANDS = List.of(", ");");
        Set<String> names = new LinkedHashSet<>();
        Matcher literal = Pattern.compile("\"([a-z-]+)\"").matcher(list);
        while (literal.find()) {
            names.add(literal.group(1).toLowerCase(Locale.ROOT));
        }
        return names;
    }

    private static String firstLiteral(String fragment) {
        Matcher literal = Pattern.compile("\"([a-z-]+)\"").matcher(fragment);
        assertTrue(literal.find(), "no subcommand literal in: " + fragment);
        return literal.group(1).toLowerCase(Locale.ROOT);
    }

    private static String source() throws IOException {
        return Files.readString(SOURCE, StandardCharsets.UTF_8);
    }

    private static String between(String text, String start, String end) {
        int from = text.indexOf(start);
        assertTrue(from >= 0, "AdminCommandService no longer contains: " + start);
        int to = text.indexOf(end, from);
        assertTrue(to > from, "AdminCommandService no longer contains: " + end);
        return text.substring(from, to);
    }
}
