package bot.mgx.accessbridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourcePackCatalogTest {
    private static final Path PACK = Path.of("..", "assets", "resourcepack");
    private static final Path SOURCE = PACK.resolve("src");

    @Test
    void packDeclaresTheSupportedJavaRange() throws Exception {
        JsonObject pack = JsonParser.parseString(
                Files.readString(SOURCE.resolve("pack.mcmeta"))
        ).getAsJsonObject().getAsJsonObject("pack");

        assertEquals(63, pack.get("pack_format").getAsInt());
        assertEquals(63, pack.get("min_format").getAsInt());
        assertEquals(75, pack.get("max_format").getAsInt());
        assertEquals(63, pack.getAsJsonArray("supported_formats").get(0).getAsInt());
        assertEquals(75, pack.getAsJsonArray("supported_formats").get(1).getAsInt());
    }

    @Test
    void everyCatalogModelResolvesToARealDefinitionModelAndTexture() throws Exception {
        assertModelResolves("mgx:lootbox_key");
        for (CosmeticCatalog.Definition definition : CosmeticCatalog.all()) {
            assertModelResolves(definition.modelKey());
        }
    }

    @Test
    void committedZipExactlyContainsEverySourceFile() throws Exception {
        try (ZipFile zip = new ZipFile(PACK.resolve("MysteriousSMPX.zip").toFile());
             var paths = Files.walk(SOURCE)) {
            for (Path source : paths.filter(Files::isRegularFile).toList()) {
                String relative = SOURCE.relativize(source).toString().replace('\\', '/');
                ZipEntry entry = zip.getEntry(relative);
                assertNotNull(entry, relative + " is missing from the resource-pack zip");
                try (InputStream input = zip.getInputStream(entry)) {
                    assertArrayEquals(Files.readAllBytes(source), input.readAllBytes(), relative);
                }
            }
        }
    }

    private static void assertModelResolves(String modelKey) throws Exception {
        String[] key = modelKey.split(":", 2);
        assertEquals(2, key.length, modelKey);
        Path itemFile = SOURCE.resolve("assets").resolve(key[0]).resolve("items")
                .resolve(key[1] + ".json");
        assertTrue(Files.isRegularFile(itemFile), modelKey + " has no item definition");

        JsonObject item = JsonParser.parseString(Files.readString(itemFile)).getAsJsonObject();
        String modelId = item.getAsJsonObject("model").get("model").getAsString();
        String[] model = modelId.split(":", 2);
        Path modelFile = SOURCE.resolve("assets").resolve(model[0]).resolve("models")
                .resolve(model[1] + ".json");
        assertTrue(Files.isRegularFile(modelFile), modelId + " has no model JSON");

        JsonObject modelJson = JsonParser.parseString(Files.readString(modelFile)).getAsJsonObject();
        String textureId = modelJson.getAsJsonObject("textures").get("layer0").getAsString();
        String[] texture = textureId.split(":", 2);
        Path textureFile = SOURCE.resolve("assets").resolve(texture[0]).resolve("textures")
                .resolve(texture[1] + ".png");
        assertTrue(Files.isRegularFile(textureFile), textureId + " has no texture PNG");
    }
}
