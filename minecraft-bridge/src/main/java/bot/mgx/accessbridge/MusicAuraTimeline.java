package bot.mgx.accessbridge;

import java.io.IOException;
import java.io.InputStream;

/**
 * The 100 ms energy/onset envelope extracted from the exact event song.
 *
 * <p>Each frame is four unsigned bytes: bass, mid, high, and transient onset. The
 * client starts the song and this clock on the same server tick, so the effect can
 * follow the recording itself instead of pretending a single BPM fits a five-minute mix.
 */
final class MusicAuraTimeline {
    static final long DURATION_MILLIS = 294_615L;
    static final int SAMPLE_MILLIS = 100;
    static final int SAMPLE_COUNT = 2_947;
    private static final int CHANNELS = 4;
    private static final byte[] DATA = load();

    record Sample(double bass, double mid, double high, double onset) {
        double energy() {
            return Math.max(bass, Math.max(mid, high));
        }
    }

    private MusicAuraTimeline() {
    }

    static Sample at(long elapsedMillis) {
        long phase = Math.floorMod(elapsedMillis, DURATION_MILLIS);
        int index = Math.min(SAMPLE_COUNT - 1, (int) (phase / SAMPLE_MILLIS));
        int offset = index * CHANNELS;
        return new Sample(
                unsigned(DATA[offset]),
                unsigned(DATA[offset + 1]),
                unsigned(DATA[offset + 2]),
                unsigned(DATA[offset + 3])
        );
    }

    private static double unsigned(byte value) {
        return Byte.toUnsignedInt(value) / 255.0d;
    }

    private static byte[] load() {
        String resource = "/music/iridescent_imperium.envelope";
        try (InputStream input = MusicAuraTimeline.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("Missing music-aura envelope " + resource);
            }
            byte[] loaded = input.readAllBytes();
            int expected = SAMPLE_COUNT * CHANNELS;
            if (loaded.length != expected) {
                throw new IllegalStateException(
                        "Music-aura envelope is " + loaded.length + " bytes; expected " + expected
                );
            }
            return loaded;
        } catch (IOException exception) {
            throw new IllegalStateException("Could not load music-aura envelope", exception);
        }
    }
}
