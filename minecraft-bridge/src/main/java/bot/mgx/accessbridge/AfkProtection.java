package bot.mgx.accessbridge;

/**
 * What incoming damage should do to an AFK player.
 *
 * <p>Free of Bukkit so the contract can be unit tested. The interesting part is
 * not the invincibility — it is the two things it deliberately does not cover.
 */
final class AfkProtection {
    /** The outcome for one damage event aimed at a player. */
    enum Decision {
        /** Not our business; leave the event exactly as it was found. */
        IGNORE,
        /** Clear the AFK mark and let the damage land. */
        WAKE,
        /** Cancel the damage. */
        BLOCK
    }

    private AfkProtection() {
    }

    /**
     * @param enabled          whether invincibility is switched on at all
     * @param afk              whether the victim is currently marked AFK
     * @param attackerIsPlayer whether a player dealt the damage, directly or
     *                         through a projectile they fired
     * @param voidDamage       whether the damage is the out-of-world kill
     */
    /**
     * How far a player has to turn their head before it counts as them coming back.
     *
     * <p>Being shoved cannot rotate a player's camera, which makes looking around the
     * one movement signal a push can never fake. It is what releases the positional hold
     * on an AFK player, so it has to be generous enough that Bedrock's camera jitter —
     * the reason idle detection watches blocks rather than angles in the first place —
     * does not wake somebody who has not touched their mouse.
     */
    static final float TURN_DEGREES = 6f;

    /** Whether a look change is a real one rather than a client's idle drift. */
    static boolean turnedEnough(float fromYaw, float fromPitch, float toYaw, float toPitch) {
        float yaw = Math.abs(wrapDegrees(toYaw - fromYaw));
        float pitch = Math.abs(toPitch - fromPitch);
        return yaw + pitch >= TURN_DEGREES;
    }

    /** Yaw is unbounded and wraps, so 359 to 1 is two degrees rather than 358. */
    private static float wrapDegrees(float degrees) {
        float wrapped = degrees % 360f;
        if (wrapped >= 180f) {
            wrapped -= 360f;
        }
        if (wrapped < -180f) {
            wrapped += 360f;
        }
        return wrapped;
    }

    static Decision decide(boolean enabled, boolean afk, boolean attackerIsPlayer, boolean voidDamage) {
        if (!enabled || !afk) {
            return Decision.IGNORE;
        }
        // A player landing a hit ends the AFK rather than bouncing off it.
        // Otherwise /afk is an immunity button to press mid-fight.
        if (attackerIsPlayer) {
            return Decision.WAKE;
        }
        // The void is the one thing worth dying to. Cancelling it strands the
        // player under the world, still AFK, with nothing left able to kill them.
        if (voidDamage) {
            return Decision.IGNORE;
        }
        return Decision.BLOCK;
    }
}
