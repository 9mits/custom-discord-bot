package bot.mgx.accessbridge;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * A hold on open-world PvP that leaves arranged {@code /pvp} duels alone.
 *
 * <p>Deliberately not the {@code PVP} game rule. Turning that off makes the server
 * refuse every player-on-player attack before any plugin sees it, so a duel inside
 * an arena dies with the ambushes — {@link PvpDuelService} never gets the event it
 * would have allowed. The rule therefore stays on and this flag makes
 * {@code openWorldPvpEnabled} report false, which the damage handler already reads
 * and which it already skips for anyone in a fight.
 *
 * <p>Persisted, because the reason to hold combat is a competition running over
 * days and a restart in the middle must not quietly reopen the world. An optional
 * expiry lets the hold end itself; without one it stays until someone lifts it.
 *
 * <p>Free of Bukkit imports so the parsing, the expiry and the wording are unit
 * tested. Anything the file does not say plainly is no hold at all: an unreadable
 * value reopens PvP rather than locking combat off with nothing to point at.
 */
final class CombatHold {
    /** Never expires. Stored for a hold somebody has to lift by hand. */
    static final long NO_EXPIRY = 0L;

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("d MMMM, HH:mm").withZone(ZoneOffset.UTC);

    private final Path file;
    private volatile boolean held;
    private volatile long until;

    CombatHold(Path file) throws IOException {
        this.file = file;
        Files.createDirectories(file.getParent());
        if (!Files.isRegularFile(file)) {
            return;
        }
        long parsed = parse(Files.readString(file));
        if (parsed == Long.MIN_VALUE) {
            return;
        }
        this.held = true;
        this.until = parsed;
    }

    /**
     * The expiry in a stored hold, or {@link Long#MIN_VALUE} when there is no hold.
     *
     * <p>Split out so the file format is tested without touching a disk.
     */
    static long parse(String raw) {
        if (raw == null) {
            return Long.MIN_VALUE;
        }
        String value = raw.strip();
        if (value.isEmpty()) {
            return Long.MIN_VALUE;
        }
        try {
            long stored = Long.parseLong(value);
            return stored < 0 ? Long.MIN_VALUE : stored;
        } catch (NumberFormatException malformed) {
            return Long.MIN_VALUE;
        }
    }

    /** Whether ordinary PvP is being held off at this moment. */
    boolean active() {
        return active(System.currentTimeMillis());
    }

    boolean active(long now) {
        if (!held) {
            return false;
        }
        if (until != NO_EXPIRY && now >= until) {
            // Reading is what retires an expired hold: there is no timer to miss and
            // no restart that can leave the world closed after its deadline passed.
            lift();
            return false;
        }
        return true;
    }

    long expiresAt() {
        return until;
    }

    /** @return true when this actually changed the state. */
    synchronized boolean hold(long expiresAtMillis) {
        long wanted = Math.max(NO_EXPIRY, expiresAtMillis);
        if (held && until == wanted) {
            return false;
        }
        write(Long.toString(wanted));
        held = true;
        until = wanted;
        return true;
    }

    /** @return true when this actually changed the state. */
    synchronized boolean lift() {
        if (!held) {
            return false;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        held = false;
        until = NO_EXPIRY;
        return true;
    }

    /** One line for a status command and for the plugin's startup log. */
    String describe(long now) {
        if (!active(now)) {
            return "Open-world PvP is on.";
        }
        if (until == NO_EXPIRY) {
            return "Open-world PvP is held off until an operator lifts it. /pvp still works.";
        }
        return "Open-world PvP is held off until " + STAMP.format(Instant.ofEpochMilli(until))
                + " UTC (" + PvpPin.describe(until - now) + " left). /pvp still works.";
    }

    private void write(String value) {
        try {
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, value, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupportedAtomicMove) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
