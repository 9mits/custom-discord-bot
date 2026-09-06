package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CrateKeyStackingTest {
    @Test
    void aStackHoldsEverythingUpToMinecraftsOwnCeiling() {
        assertEquals(List.of(99L), CrateItems.keyPortions(99L, CrateItems.MAX_REAL_STACK));
    }

    @Test
    void theNextKeyStartsAnotherStackWithoutLosingValue() {
        assertEquals(List.of(99L, 1L), CrateItems.keyPortions(100L, CrateItems.MAX_REAL_STACK));
        assertEquals(10_000L, CrateItems.keyPortions(10_000L, CrateItems.MAX_REAL_STACK)
                .stream().mapToLong(Long::longValue).sum());
    }

    @Test
    void invalidAmountsCannotCreateCorruptStacks() {
        assertEquals(List.of(), CrateItems.keyPortions(0L, CrateItems.MAX_REAL_STACK));
        assertThrows(IllegalArgumentException.class, () -> CrateItems.keyPortions(-1L, 99));
        assertThrows(IllegalArgumentException.class, () -> CrateItems.keyPortions(1L, 0));
    }

    /**
     * Minecraft validates the {@code max_stack_size} component as {@code <= 99}, so a
     * larger value is not a display choice the plugin can make — it throws when the
     * first key is built. The ceiling is asserted here so raising it needs a deliberate
     * change rather than an edit to a config that looks like it accepts anything.
     */
    @Test
    void theCeilingIsMinecraftsAndNotAPreference() {
        assertEquals(99, CrateItems.MAX_REAL_STACK);
    }

    /**
     * A key must carry nothing that varies between two of them.
     *
     * <p>This is what makes a key a real stack: the client merges two items only when
     * their components match, so a count printed into the display name or lore — which
     * is how keys used to report their balance — silently makes every key unstackable
     * again. Asserted against the source because building an {@code ItemStack} needs a
     * running server.
     */
    @Test
    void nothingPerStackIsWrittenOntoAKeyItem() throws Exception {
        String source = Files.readString(
                Path.of("src/main/java/bot/mgx/accessbridge/CrateItems.java")
        );
        int start = source.indexOf("ItemStack key(long amount) {");
        int end = source.indexOf("List<ItemStack> keyStacks(long amount)", start);
        assertTrue(start > 0 && end > start, "could not locate the key item builder");
        String body = source.substring(start, end);
        assertFalse(body.contains("+ amount"), "key text must not embed its own count");
        assertFalse(body.contains("keyCountMarker"),
                "a new key must not carry a virtual count; the stack size is the count");
        assertTrue(body.contains("new ItemStack(Material.TRIAL_KEY, (int) amount)"),
                "the balance must be the item count");
        assertTrue(body.contains("setMaxStackSize(MAX_REAL_STACK)"),
                "keys must declare the real stack ceiling");
    }
}
