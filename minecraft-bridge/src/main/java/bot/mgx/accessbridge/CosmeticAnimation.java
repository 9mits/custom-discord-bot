package bot.mgx.accessbridge;

import java.util.UUID;

/** Pure timing curves shared by the particle choreographies. */
final class CosmeticAnimation {
    private CosmeticAnimation() {
    }

    static int step(long frame, int frames) {
        if (frames < 1) {
            throw new IllegalArgumentException("Animation length must be positive");
        }
        return (int) Math.floorMod(frame, frames);
    }

    static double progress(long frame, int frames) {
        return step(frame, frames) / (double) frames;
    }

    static double phaseProgress(int step, int start, int end) {
        if (end <= start) {
            throw new IllegalArgumentException("Animation phase must have a positive length");
        }
        return clamp((step - start) / (double) (end - start));
    }

    static double smooth(double value) {
        double clamped = clamp(value);
        return clamped * clamped * (3d - 2d * clamped);
    }

    static double easeOutBack(double value) {
        double progress = clamp(value) - 1d;
        double overshoot = 1.70158d;
        return 1d + (overshoot + 1d) * progress * progress * progress
                + overshoot * progress * progress;
    }

    static double pingPong(double value) {
        double wrapped = value - Math.floor(value);
        return 1d - Math.abs(wrapped * 2d - 1d);
    }

    static long playerOffset(UUID playerId, int frames) {
        if (frames < 1) {
            throw new IllegalArgumentException("Animation length must be positive");
        }
        long mixed = playerId.getMostSignificantBits() ^ playerId.getLeastSignificantBits();
        return Math.floorMod(mixed, frames);
    }

    private static double clamp(double value) {
        return Math.max(0d, Math.min(1d, value));
    }
}
