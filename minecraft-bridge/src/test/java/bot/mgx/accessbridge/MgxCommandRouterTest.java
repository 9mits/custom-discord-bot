package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Holds the one administrative grammar together.
 *
 * <p>The old surface drifted because dispatch, completion and help were three separate
 * lists that nobody kept in step: three subcommands ran but never completed, roughly
 * twenty aliases were undocumented, and eight different verbs ended things depending on
 * which subsystem you were in. The route table is now the only list, and these hold the
 * properties that made it worth consolidating.
 */
final class MgxCommandRouterTest {
    /**
     * Verbs a route may end with.
     *
     * <p>Closed on purpose, not minimal. The point of the redesign is that a verb
     * learned in one area means the same thing everywhere, which only holds if a new
     * area cannot invent its own word for something that already has one. Adding a verb
     * here is allowed; doing it by accident is what this stops. It has already caught
     * two: a "spawn" that meant "start", and a "set" that had no entry.
     */
    private static final Set<String> VERBS = Set.of(
            // Changing things.
            "create", "edit", "set", "delete", "move", "reset",
            // Reading things.
            "list", "show",
            // Turning things on and off, and running them.
            "enable", "disable", "start", "stop", "reload", "run", "publish",
            // Domain verbs that earn their place: no general word means these.
            "hold", "release", "give", "schedule", "reserial", "reveal",
            "joinbonus", "screenshot", "airdrop"
    );

    @Test
    void everyRouteEndsInAVerbFromTheClosedSet() {
        List<String> odd = new ArrayList<>();
        for (MgxCommandRouter.Route route : MgxCommandRouter.routes()) {
            String[] words = route.path().split(" ");
            String last = words[words.length - 1];
            if (!VERBS.contains(last)) {
                odd.add(route.path());
            }
        }
        assertTrue(odd.isEmpty(),
                "routes ending in a verb outside the closed set: " + odd
                        + " — reuse an existing verb, or add it deliberately");
    }

    @Test
    void noTwoRoutesShareAPath() {
        Set<String> seen = new HashSet<>();
        List<String> duplicates = new ArrayList<>();
        for (MgxCommandRouter.Route route : MgxCommandRouter.routes()) {
            if (!seen.add(route.path())) {
                duplicates.add(route.path());
            }
        }
        assertTrue(duplicates.isEmpty(), "duplicate routes: " + duplicates);
    }

    /**
     * A route that delegates must delegate to something that exists.
     *
     * <p>This is the check the old surface never had: help described commands the
     * dispatcher did not handle, and the dispatcher handled commands help never
     * mentioned.
     */
    @Test
    void everyDelegatedRoutePointsAtARealSubcommand() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/bot/mgx/accessbridge/AdminCommandService.java"), StandardCharsets.UTF_8);
        String dispatch = source.substring(
                source.indexOf("String action = args.length == 0"),
                source.indexOf("private void variables("));
        Set<String> handled = new HashSet<>();
        Matcher cases = Pattern.compile("\"([a-z-]+)\"").matcher(dispatch);
        while (cases.find()) {
            handled.add(cases.group(1));
        }
        List<String> broken = new ArrayList<>();
        for (MgxCommandRouter.Route route : MgxCommandRouter.routes()) {
            if (route.legacy().isBlank()) {
                continue;
            }
            String head = route.legacy().split(" ")[0].toLowerCase(Locale.ROOT);
            if (!handled.contains(head)) {
                broken.add(route.path() + " -> " + route.legacy());
            }
        }
        assertTrue(broken.isEmpty(), "routes delegating to nothing: " + broken);
    }

    /** Every area a route names is reachable from the top of help. */
    @Test
    void everyAreaIsOneOfTheAdvertisedSix() {
        Set<String> areas = new java.util.TreeSet<>();
        MgxCommandRouter.routes().forEach(route -> areas.add(route.path().split(" ")[0]));
        assertEquals(
                Set.of("world", "event", "economy", "player", "server", "config", "dev"),
                areas);
    }

    /**
     * The whole point of the example the redesign started from.
     *
     * <p>Leaderboard boards and crate chests were separate commands with separate syntax.
     * They are one route now, told apart by what you name rather than by which command
     * you remembered.
     */
    @Test
    void oneRouteCoversBothKindsOfHologram() {
        List<MgxCommandRouter.Route> hologram = MgxCommandRouter.routes().stream()
                .filter(route -> route.path().startsWith("world hologram"))
                .toList();
        assertEquals(4, hologram.size(), "expected create, list, delete and reload");

        MgxCommandRouter.Route create = hologram.stream()
                .filter(route -> route.path().endsWith("create")).findFirst().orElseThrow();
        assertTrue(create.hints().contains("wealth"), "leaderboards must be offered");
        assertTrue(create.hints().contains("crate:amethyst"), "crates must be offered");
        assertTrue(create.legacy().isBlank(),
                "the unified route must not delegate to either old command");
    }

    /** Anything that clears data or changes live configuration sits at the top tier. */
    @Test
    void destructiveAndConfigurationRoutesAreOwnerOnly() {
        List<String> tooLow = new ArrayList<>();
        for (MgxCommandRouter.Route route : MgxCommandRouter.routes()) {
            boolean sensitive = route.path().startsWith("config ")
                    || route.path().startsWith("server reset")
                    || route.path().equals("player cosmetic delete")
                    || route.path().equals("event clanbattle delete");
            if (sensitive && route.tier() != MgxCommandRouter.Tier.OWNER) {
                tooLow.add(route.path() + " is " + route.tier());
            }
        }
        assertTrue(tooLow.isEmpty(), "these should be owner-only: " + tooLow);
    }

    /** Reading is never gated higher than the thing it reads about. */
    @Test
    void showingSomethingIsNeverHarderThanChangingIt() {
        List<String> wrong = new ArrayList<>();
        for (MgxCommandRouter.Route route : MgxCommandRouter.routes()) {
            if (!route.path().endsWith(" show")) {
                continue;
            }
            String area = route.path().substring(0, route.path().length() - " show".length());
            MgxCommandRouter.routes().stream()
                    .filter(other -> other.path().startsWith(area + " ")
                            && !other.path().endsWith(" show"))
                    .forEach(other -> {
                        if (route.tier().ordinal() > other.tier().ordinal()) {
                            wrong.add(route.path() + " (" + route.tier() + ") vs "
                                    + other.path() + " (" + other.tier() + ")");
                        }
                    });
        }
        assertTrue(wrong.isEmpty(), "reading gated above writing: " + wrong);
    }

    /** Both old spellings stay registered, so nothing anybody typed yesterday breaks. */
    @Test
    void theOldCommandsAreStillRegistered() throws Exception {
        String plugin = Files.readString(
                Path.of("src/main/resources/plugin.yml"), StandardCharsets.UTF_8);
        assertTrue(plugin.contains("\n  mgx:"), "the new root must be registered");
        assertTrue(plugin.contains("\n  mgxadmin:"), "/mgxadmin must keep working");
        assertTrue(plugin.contains("\n  cratehologram:"), "/cratehologram must keep working");
        for (MgxCommandRouter.Tier tier : MgxCommandRouter.Tier.values()) {
            assertTrue(plugin.contains(tier.node() + ":"),
                    tier.node() + " must be declared");
        }
    }
}
