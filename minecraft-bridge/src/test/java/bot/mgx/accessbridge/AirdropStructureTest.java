package bot.mgx.accessbridge;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AirdropStructureTest {
    @Test
    void blueprintIsAUniquePurpleAltarWithOneCentralChest() {
        Set<String> positions = new HashSet<>();
        long chests = 0L;
        boolean amethyst = false;
        boolean beaconLikeLight = false;
        for (AirdropStructure.Placement placement : AirdropStructure.blueprint()) {
            assertTrue(positions.add(placement.x() + ":" + placement.y() + ":" + placement.z()));
            assertTrue(Math.abs(placement.x()) <= AirdropStructure.radius());
            assertTrue(Math.abs(placement.z()) <= AirdropStructure.radius());
            assertTrue(placement.y() >= 0 && placement.y() <= AirdropStructure.height());
            chests += placement.material() == Material.CHEST ? 1 : 0;
            amethyst |= placement.material() == Material.AMETHYST_BLOCK
                    || placement.material() == Material.BUDDING_AMETHYST;
            beaconLikeLight |= placement.material() == Material.END_ROD;
        }

        assertEquals(1L, chests);
        assertTrue(amethyst);
        assertTrue(beaconLikeLight);
        assertTrue(AirdropStructure.blueprint().size() >= 60);
    }
}
