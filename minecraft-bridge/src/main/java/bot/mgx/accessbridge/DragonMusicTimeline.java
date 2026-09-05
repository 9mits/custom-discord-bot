package bot.mgx.accessbridge;

import java.io.IOException;
import java.io.InputStream;

/** The 100 ms energy and onset envelope extracted from the supplied Dragon song. */
final class DragonMusicTimeline {
    static final long DURATION_MILLIS = 133_283L;
    static final int SAMPLE_MILLIS = 100;
    static final int SAMPLE_COUNT = 1_333;
    private static final int CHANNELS = 4;
    private static final byte[] DATA = load();

    private DragonMusicTimeline() { }

    static MusicAuraTimeline.Sample at(long elapsedMillis) {
        long phase = Math.floorMod(elapsedMillis, DURATION_MILLIS);
        int index = Math.min(SAMPLE_COUNT - 1, (int) (phase / SAMPLE_MILLIS));
        int offset = index * CHANNELS;
        return new MusicAuraTimeline.Sample(
                unsigned(DATA[offset]), unsigned(DATA[offset + 1]),
                unsigned(DATA[offset + 2]), unsigned(DATA[offset + 3]));
    }

    private static double unsigned(byte value) {
        return Byte.toUnsignedInt(value) / 255.0d;
    }

    private static byte[] load() {
        String resource = "/music/amethyst_dragon_ascendant.envelope";
        try (InputStream input = DragonMusicTimeline.class.getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("Missing Dragon music envelope " + resource);
            byte[] loaded = input.readAllBytes();
            if (loaded.length != SAMPLE_COUNT * CHANNELS) {
                throw new IllegalStateException("Dragon music envelope has " + loaded.length + " bytes");
            }
            return loaded;
        } catch (IOException exception) {
            throw new IllegalStateException("Could not load Dragon music envelope", exception);
        }
    }
}
