package bot.mgx.accessbridge;

import org.bukkit.Bukkit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * The owner's kill switch and the licence lock that backs it.
 *
 * <p>Two jobs, both about one thing: this plugin is the owner's property, and a copy of
 * the jar taken off a shared control panel must be worthless to whoever took it.
 *
 * <ul>
 *   <li><b>Licence lock.</b> On a real server the plugin refuses to start unless a
 *       keyfile the owner placed is present at the server root — see {@link #licensed}.
 *       A thief who copies only the jar has no keyfile, so the plugin disables itself.
 *       (This does not defend against someone who also has filesystem access to the
 *       Minecraft host and copies the keyfile too; nothing running on a box can hide a
 *       secret from root on that box. It defends against the stated threat: the jar
 *       leaving the panel.)</li>
 *   <li><b>Self-destruct.</b> The owner, and only the owner, can detonate the server:
 *       every plugin data file, every world folder, gone, and a marker left so the jar
 *       refuses to ever run here again. Firing it needs the owner's Discord role, a
 *       passphrase that lives only in the owner's memory, and the plugin independently
 *       re-checks that passphrase against the keyfile — so a compromised bot alone can
 *       never trigger it.</li>
 * </ul>
 *
 * <p>Nothing here has a Bukkit dependency except {@link #detonate} and the shutdown, so
 * the licence and passphrase logic is unit tested.
 */
final class SelfDestruct {
    /** The keyfile the owner places at the server root. Git-ignored; never in the jar. */
    static final String KEY_FILE = "mgx-license.key";
    /** Once written, the jar treats this server as destroyed forever. */
    static final String TERMINATED_FLAG = "mgx-terminated.flag";
    /** The one server id allowed to run with no keyfile: the local Paper test server. */
    static final String LOCAL_TEST_ID = "mgx-local-test";

    private SelfDestruct() {
    }

    /** {@code license=…} and {@code destruct=…} from the keyfile, empty if it is absent. */
    static Map<String, String> readKeyfile(Path serverRoot) {
        Path file = serverRoot.resolve(KEY_FILE);
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        java.util.HashMap<String, String> values = new java.util.HashMap<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.strip();
                int equals = trimmed.indexOf('=');
                if (trimmed.isEmpty() || trimmed.startsWith("#") || equals <= 0) {
                    continue;
                }
                values.put(trimmed.substring(0, equals).strip().toLowerCase(Locale.ROOT),
                        trimmed.substring(equals + 1).strip());
            }
        } catch (IOException unreadable) {
            return Map.of();
        }
        return Map.copyOf(values);
    }

    /**
     * Whether this jar is allowed to run here at all.
     *
     * <p>The local test server ({@code server-id: mgx-local-test}) is exempt so day-to-day
     * development needs no key. Every other server must present a keyfile whose
     * {@code license} value is non-empty; that is the marker a thief's copy lacks.
     */
    static boolean licensed(Path serverRoot, String serverId) {
        if (LOCAL_TEST_ID.equals(serverId)) {
            return true;
        }
        String license = readKeyfile(serverRoot).getOrDefault("license", "");
        return !license.isBlank();
    }

    static boolean terminated(Path serverRoot) {
        return Files.isRegularFile(serverRoot.resolve(TERMINATED_FLAG));
    }

    /**
     * Whether {@code passphrase} is the one that may detonate this server.
     *
     * <p>The keyfile stores only {@code destruct=<sha256 of the passphrase>}. The
     * passphrase itself is written down nowhere — not in the jar, not on the panel, not
     * in the keyfile — so knowing the file's contents does not let you fire it. An absent
     * or unset {@code destruct} line means the switch is disarmed and no input matches.
     */
    static boolean detonationAuthorised(Path serverRoot, String passphrase) {
        String expected = readKeyfile(serverRoot).getOrDefault("destruct", "").toLowerCase(Locale.ROOT);
        if (expected.length() != 64 || passphrase == null || passphrase.isBlank()) {
            return false;
        }
        return constantTimeEquals(expected, sha256(passphrase));
    }

    static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is always available", impossible);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Runs at {@link org.bukkit.plugin.java.JavaPlugin#onLoad}, before any world is loaded.
     *
     * <p>If the server was terminated on a previous run, this is where its worlds are
     * erased — before Paper can open their region files and lock them. It keeps happening
     * on every boot until the owner deletes both the flag and, if they mean it, restores a
     * fresh world; the jar staying dead is the point.
     */
    static void enforceTerminationAtLoad(Path serverRoot, Path worldContainer, Logger log) {
        if (!terminated(serverRoot)) {
            return;
        }
        log.severe("MGXAccessBridge: this server was terminated by its owner. Erasing worlds.");
        eraseWorlds(worldContainer, log);
    }

    /** Deletes every world folder — a directory holding a {@code level.dat} — under the container. */
    static void eraseWorlds(Path worldContainer, Logger log) {
        if (!Files.isDirectory(worldContainer)) {
            return;
        }
        try (Stream<Path> entries = Files.list(worldContainer)) {
            for (Path entry : entries.toList()) {
                if (Files.isDirectory(entry) && Files.isRegularFile(entry.resolve("level.dat"))) {
                    deleteRecursively(entry, log);
                }
            }
        } catch (IOException failure) {
            log.severe("MGXAccessBridge: world erase pass failed: " + failure.getMessage());
        }
    }

    static void deleteRecursively(Path root, Logger log) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException locked) {
                    // A file the JVM still holds open cannot go now; the next boot's pass
                    // takes it once nothing has it loaded.
                    path.toFile().deleteOnExit();
                }
            });
        } catch (IOException failure) {
            log.severe("MGXAccessBridge: could not fully delete " + root + ": " + failure.getMessage());
        }
    }

    /**
     * The point of no return. Wipes plugin data, unloads and deletes every world, writes
     * the termination marker, and shuts the server down. The caller has already proven the
     * passphrase; this method does not second-guess that, so it is only ever reached from
     * the one place that checks.
     */
    static void detonate(MGXAccessBridge plugin, Path serverRoot, Path worldContainer,
            Path dataFolder, Optional<String> firedBy) {
        Logger log = plugin.getLogger();
        log.severe("MGXAccessBridge: SELF-DESTRUCT authorised"
                + firedBy.map(name -> " by " + name).orElse("") + ". Destroying everything.");

        // Marker first: if anything below throws or the box loses power mid-wipe, the next
        // boot still finishes the job rather than coming back to life half-destroyed.
        try {
            Files.writeString(serverRoot.resolve(TERMINATED_FLAG),
                    "Terminated by the owner at " + java.time.Instant.now(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            log.severe("MGXAccessBridge: could not write the termination marker: " + failure.getMessage());
        }

        for (org.bukkit.World world : java.util.List.copyOf(Bukkit.getWorlds())) {
            for (org.bukkit.entity.Player player : world.getPlayers()) {
                player.kick(net.kyori.adventure.text.Component.text("This server has been shut down."));
            }
            // The primary world cannot be unloaded while the server runs; its files are
            // deleted from onLoad on the next boot instead. Secondary worlds go now.
            if (!Bukkit.getWorlds().isEmpty() && !world.equals(Bukkit.getWorlds().get(0))) {
                Bukkit.unloadWorld(world, false);
            }
        }
        eraseWorlds(worldContainer, log);
        deleteRecursively(dataFolder, log);

        log.severe("MGXAccessBridge: destruction complete. Shutting the server down.");
        Bukkit.shutdown();
    }
}
