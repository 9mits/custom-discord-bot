package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerStatsParserTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void aStatsFileNameIsThePlayerUuid() {
        UUID uuid = UUID.fromString("11111111-1111-1111-1111-111111111111");
        Path file = temporaryDirectory.resolve(uuid + ".json");

        assertEquals(uuid, PlayerStatsParser.uuidFromFileName(file).orElseThrow());
        assertTrue(PlayerStatsParser.uuidFromFileName(temporaryDirectory.resolve("notes.txt")).isEmpty());
    }

    @Test
    void customAndMinedSectionsAreReadWithoutTheRestOfTheFile() throws Exception {
        UUID uuid = UUID.fromString("22222222-2222-2222-2222-222222222222");
        Path file = temporaryDirectory.resolve(uuid + ".json");
        Files.writeString(file, """
                {
                  "stats": {
                    "minecraft:custom": {
                      "minecraft:player_kills": 3,
                      "minecraft:deaths": 1,
                      "minecraft:play_time": 40,
                      "minecraft:walk_one_cm": 200
                    },
                    "minecraft:mined": {
                      "minecraft:stone": 10,
                      "minecraft:dirt": 5
                    }
                  }
                }
                """, StandardCharsets.UTF_8);

        PlayerStatsParser.Snapshot snapshot = PlayerStatsParser.read(file).orElseThrow();

        assertEquals(3, snapshot.kills());
        assertEquals(1, snapshot.deaths());
        assertEquals(40, snapshot.playTimeTicks());
        assertEquals(15, snapshot.blocksMined());
        assertEquals(200, snapshot.walkedCm());
    }

    @Test
    void aRememberedNameAvoidsAFreshLookup() {
        UUID uuid = UUID.fromString("33333333-3333-3333-3333-333333333333");
        Map<UUID, String> online = new HashMap<>();
        Map<UUID, String> remembered = new HashMap<>();
        remembered.put(uuid, "CachedName");

        assertEquals("CachedName", PlayerStatsParser.cachedUsername(uuid, online, remembered));
        online.put(uuid, "OnlineName");
        assertEquals("OnlineName", PlayerStatsParser.cachedUsername(uuid, online, remembered));
        assertEquals("33333333", PlayerStatsParser.fallbackUsername(uuid));
    }
}
