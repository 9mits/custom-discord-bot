package bot.mgx.accessbridge;

import java.util.List;
import java.util.Optional;

/**
 * The staff tools Discord can know about, in one place.
 *
 * <p>{@link CapabilityService} advertises every tool here that a player's permission
 * allows, and {@link MGXAccessBridge#runStaffAction} only ever dispatches a tool that
 * also has a {@link StaffTool#remote()} builder. A tool with no builder is investigative
 * or requires standing in the world, so it is listed but never remotely runnable.
 */
final class StaffTools {
    /** Builds the console command for one tool, or refuses with a message for the player. */
    @FunctionalInterface
    interface RemoteCommand {
        String build(String target, String reason, String duration) throws IllegalArgumentException;
    }

    record StaffTool(String key, String permission, boolean needsTarget, Optional<RemoteCommand> remote) {
        static StaffTool infoOnly(String key, String permission) {
            return new StaffTool(key, permission, false, Optional.empty());
        }

        static StaffTool remote(String key, String permission, RemoteCommand command) {
            return new StaffTool(key, permission, true, Optional.of(command));
        }

        /** A remote tool that acts on the whole server, so no player name to validate. */
        static StaffTool remoteUntargeted(String key, String permission, RemoteCommand command) {
            return new StaffTool(key, permission, false, Optional.of(command));
        }
    }

    static final List<StaffTool> ALL = List.of(
            StaffTool.infoOnly("inspect", "coreprotect.inspect"),
            StaffTool.infoOnly("lookup", "coreprotect.lookup"),
            StaffTool.infoOnly("rollback", "coreprotect.rollback"),
            StaffTool.infoOnly("restore", "coreprotect.restore"),
            StaffTool.infoOnly("alerts", "grim.alerts"),
            StaffTool.infoOnly("god", "essentials.god"),
            StaffTool.infoOnly("invsee", "essentials.invsee"),
            StaffTool.infoOnly("vanish", "essentials.vanish"),
            StaffTool.remote(
                    "heal", "essentials.heal",
                    (target, reason, duration) -> "heal " + target
            ),
            StaffTool.remote(
                    "kick", "essentials.kick",
                    (target, reason, duration) -> "kick " + target + suffix(reason)
            ),
            StaffTool.remote(
                    "mute", "essentials.mute",
                    (target, reason, duration) -> "mute " + target
                            + (duration.isEmpty() ? "" : " " + duration)
                            + suffix(reason)
            ),
            StaffTool.remote(
                    "ban", "essentials.ban",
                    (target, reason, duration) -> "ban " + target + suffix(reason)
            ),
            StaffTool.remote(
                    "tempban", "essentials.tempban",
                    (target, reason, duration) -> {
                        if (duration.isEmpty()) {
                            throw new IllegalArgumentException("A duration is required for a temporary ban.");
                        }
                        return "tempban " + target + " " + duration + suffix(reason);
                    }
            ),
            StaffTool.remote(
                    "unban", "essentials.unban",
                    (target, reason, duration) -> "unban " + target
            ),
            StaffTool.remoteUntargeted(
                    "broadcast", "essentials.broadcast",
                    (target, reason, duration) -> {
                        if (reason.isEmpty()) {
                            throw new IllegalArgumentException("A message is required to broadcast.");
                        }
                        return "broadcast " + reason;
                    }
            )
    );

    private StaffTools() {
    }

    private static String suffix(String reason) {
        return reason.isEmpty() ? "" : " " + reason;
    }

    static Optional<StaffTool> find(String key) {
        return ALL.stream().filter(tool -> tool.key().equals(key)).findFirst();
    }
}
