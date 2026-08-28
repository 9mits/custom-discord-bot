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
    private static final int ONSET_CHANNEL = 3;
    private static final int MIN_BEAT_ONSET = 110;
    private static final int LOCAL_ONSET_MARGIN = 25;
    private static final int BEAT_REFRACTORY_SAMPLES = 4;
    private static final byte[] DATA = load();
    private static final BeatMap BEATS = detectBeats();
    static final int BEAT_COUNT = BEATS.count();

    record Sample(double bass, double mid, double high, double onset) {
        double energy() {
            return Math.max(bass, Math.max(mid, high));
        }
    }

    /** A short attack/recoil motion around one isolated onset peak in the recording. */
    record Beat(double pulse, double recoil, double strength, int ordinal, boolean strike) {
        private static final Beat NONE = new Beat(0d, 0d, 0d, -1, false);
    }

    private record BeatMap(int[] latestSample, int[] ordinal, int count) {
    }

    private MusicAuraTimeline() {
    }

    static Sample at(long elapsedMillis) {
        int index = sampleIndex(elapsedMillis);
        int offset = index * CHANNELS;
        return new Sample(
                unsigned(DATA[offset]),
                unsigned(DATA[offset + 1]),
                unsigned(DATA[offset + 2]),
                unsigned(DATA[offset + 3])
        );
    }

    /**
     * Returns a deliberate beat gesture instead of treating every non-zero onset
     * sample as a hit. Local-maximum suppression removes the dense 100 ms flicker
     * that made the aura look busy while preserving the attacks from the exact song.
     */
    static Beat beatAt(long elapsedMillis) {
        int current = sampleIndex(elapsedMillis);
        int beatSample = BEATS.latestSample()[current];
        if (beatSample < 0) {
            return Beat.NONE;
        }
        int age = current - beatSample;
        double strength = 0.7d + unsigned(DATA[beatSample * CHANNELS + ONSET_CHANNEL]) * 0.3d;
        double pulse = switch (age) {
            case 0 -> strength;
            case 1 -> strength * 0.42d;
            case 2 -> strength * 0.08d;
            default -> 0d;
        };
        double recoil = switch (age) {
            case 1 -> strength * 0.18d;
            case 2 -> strength * 0.55d;
            case 3 -> strength * 0.20d;
            default -> 0d;
        };
        return new Beat(
                pulse, recoil, strength, BEATS.ordinal()[beatSample], age == 0
        );
    }

    private static int sampleIndex(long elapsedMillis) {
        long phase = Math.floorMod(elapsedMillis, DURATION_MILLIS);
        return Math.min(SAMPLE_COUNT - 1, (int) (phase / SAMPLE_MILLIS));
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

    private static BeatMap detectBeats() {
        int[] latest = new int[SAMPLE_COUNT];
        int[] ordinal = new int[SAMPLE_COUNT];
        java.util.Arrays.fill(latest, -1);
        java.util.Arrays.fill(ordinal, -1);
        int[] selected = new int[SAMPLE_COUNT];
        int selectedCount = 0;
        for (int index = 1; index < SAMPLE_COUNT - 1; index++) {
            int onset = onset(index);
            if (onset < MIN_BEAT_ONSET
                    || onset < onset(index - 1)
                    || onset <= onset(index + 1)
                    || onset < localOnsetAverage(index) + LOCAL_ONSET_MARGIN) {
                continue;
            }
            if (selectedCount > 0
                    && index - selected[selectedCount - 1] < BEAT_REFRACTORY_SAMPLES) {
                if (onset > onset(selected[selectedCount - 1])) {
                    selected[selectedCount - 1] = index;
                }
                continue;
            }
            selected[selectedCount++] = index;
        }
        int nextSelected = 0;
        int latestBeat = -1;
        int latestOrdinal = -1;
        for (int index = 0; index < SAMPLE_COUNT; index++) {
            if (nextSelected < selectedCount && selected[nextSelected] == index) {
                latestBeat = index;
                latestOrdinal = nextSelected++;
                ordinal[index] = latestOrdinal;
            }
            latest[index] = latestBeat;
        }
        return new BeatMap(latest, ordinal, selectedCount);
    }

    private static int onset(int sample) {
        return Byte.toUnsignedInt(DATA[sample * CHANNELS + ONSET_CHANNEL]);
    }

    private static int localOnsetAverage(int sample) {
        int first = Math.max(0, sample - 5);
        int last = Math.min(SAMPLE_COUNT - 1, sample + 5);
        int sum = 0;
        int count = 0;
        for (int index = first; index <= last; index++) {
            if (index == sample) {
                continue;
            }
            sum += onset(index);
            count++;
        }
        return count == 0 ? 0 : sum / count;
    }
}
