package bot.mgx.accessbridge;

import org.bukkit.Material;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The small crystalline altar built around every Amethyst Airdrop chest. */
final class AirdropStructure {
    record Placement(int x, int y, int z, Material material) {
        Placement {
            if (material == null
                    || material == Material.AIR
                    || material == Material.CAVE_AIR
                    || material == Material.VOID_AIR) {
                throw new IllegalArgumentException("Airdrop structure blocks must be solid");
            }
        }
    }

    private static final List<Placement> BLUEPRINT = build();

    private AirdropStructure() {
    }

    static List<Placement> blueprint() {
        return BLUEPRINT;
    }

    static int radius() {
        return 3;
    }

    static int height() {
        return 6;
    }

    private static List<Placement> build() {
        Map<String, Placement> blocks = new LinkedHashMap<>();
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                if (Math.abs(x) == 3 && Math.abs(z) == 3) {
                    continue;
                }
                boolean rim = Math.abs(x) == 3 || Math.abs(z) == 3;
                boolean cross = x == 0 || z == 0;
                Material material = rim
                        ? Material.POLISHED_BLACKSTONE_BRICKS
                        : cross ? Material.AMETHYST_BLOCK : Material.PURPLE_STAINED_GLASS;
                put(blocks, x, 0, z, material);
            }
        }
        put(blocks, 0, 0, 0, Material.CRYING_OBSIDIAN);
        put(blocks, 0, 1, 0, Material.CHEST);

        for (int x : new int[]{-3, 3}) {
            for (int z : new int[]{-3, 3}) {
                put(blocks, x, 0, z, Material.POLISHED_BLACKSTONE_BRICKS);
                put(blocks, x, 1, z, Material.CRYING_OBSIDIAN);
                put(blocks, x, 2, z, Material.AMETHYST_BLOCK);
                put(blocks, x, 3, z, Material.BUDDING_AMETHYST);
                put(blocks, x, 4, z, Material.END_ROD);
            }
        }

        for (int direction : new int[]{-1, 1}) {
            put(blocks, direction * 3, 1, 0, Material.CRYING_OBSIDIAN);
            put(blocks, direction * 3, 2, 0, Material.AMETHYST_BLOCK);
            put(blocks, direction * 2, 3, 0, Material.PURPLE_STAINED_GLASS);
            put(blocks, direction, 4, 0, Material.AMETHYST_BLOCK);

            put(blocks, 0, 1, direction * 3, Material.CRYING_OBSIDIAN);
            put(blocks, 0, 2, direction * 3, Material.AMETHYST_BLOCK);
            put(blocks, 0, 3, direction * 2, Material.PURPLE_STAINED_GLASS);
            put(blocks, 0, 4, direction, Material.AMETHYST_BLOCK);
        }
        put(blocks, 0, 5, 0, Material.TINTED_GLASS);
        put(blocks, 0, 6, 0, Material.AMETHYST_BLOCK);
        return List.copyOf(blocks.values());
    }

    private static void put(
            Map<String, Placement> blocks, int x, int y, int z, Material material
    ) {
        blocks.put(x + ":" + y + ":" + z, new Placement(x, y, z, material));
    }
}
