package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class HologramRefreshTest {
    @Test
    void routineRefreshReusesLinesInsteadOfRespawningEveryBoard() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/bot/mgx/accessbridge/HologramService.java"
        ));
        String refresh = method(source, "    void refresh() {", "    /** Full replacement");

        assertTrue(refresh.contains("liveLines(world, placement, expected.size())"));
        assertTrue(refresh.contains("Objects.equals(stand.customName(), line)"));
        assertFalse(refresh.contains("clearStands()"));
        assertFalse(refresh.contains("spawn(world, placement, colours)"));
    }

    @Test
    void structuralChangesStillRemoveAbandonedLines() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/bot/mgx/accessbridge/HologramService.java"
        ));
        String rebuild = method(source, "    private void rebuild() {", "    /**\n     * Recovers");

        assertTrue(rebuild.contains("clearStands()"));
        assertTrue(rebuild.contains("spawn(world, placement, colours)"));
    }

    private static String method(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        if (from < 0 || to < 0) {
            throw new AssertionError("Could not find method boundaries");
        }
        return source.substring(from, to);
    }
}
