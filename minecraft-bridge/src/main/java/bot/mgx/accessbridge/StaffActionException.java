package bot.mgx.accessbridge;

/**
 * A refusal that is a real answer for the requester — wrong permission, missing
 * argument, unknown tool — rather than a bug in the bridge. Carried back to Discord
 * as the message verbatim, the same way {@link ClanStore.ClanException} is.
 */
final class StaffActionException extends RuntimeException {
    StaffActionException(String message) {
        super(message);
    }
}
