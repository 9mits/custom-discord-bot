package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ClanIconTest {
    @Test
    void catalogHasUniqueStableItemIdsAndADefault() {
        HashSet<String> ids = new HashSet<>();
        for (ClanIcon icon : ClanIcon.choices()) {
            assertTrue(ids.add(icon.id()), icon.id());
            assertEquals("item/" + icon.id(), icon.sprite());
            assertTrue(icon.material() != null, icon.id());
            assertEquals(icon, ClanIcon.find(icon.id()).orElseThrow());
        }
        assertTrue(ClanIcon.choices().contains(ClanIcon.DEFAULT));
        assertEquals(ClanIcon.DEFAULT, ClanIcon.resolve("not-a-real-icon"));
    }
}
