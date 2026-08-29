package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class HomeIconStoreTest {
    @TempDir
    Path directory;

    @Test
    void renamingOnlyTheCaseDoesNotLoseTheIcon() throws Exception {
        Path file = directory.resolve("home-icons.json");
        UUID player = UUID.randomUUID();
        HomeIconStore store = new HomeIconStore(file);

        store.setIcon(player, "Base", "item/diamond");
        store.rename(player, "Base", "BASE");

        assertEquals("item/diamond", store.iconOf(player, "base"));
        assertEquals("item/diamond", new HomeIconStore(file).iconOf(player, "base"));
    }
}
