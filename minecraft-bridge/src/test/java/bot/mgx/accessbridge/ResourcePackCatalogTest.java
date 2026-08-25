package bot.mgx.accessbridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourcePackCatalogTest {
    private static final Path PACK = Path.of("..", "assets", "resourcepack");
    private static final Path SOURCE = PACK.resolve("src");
    private static final Path BEDROCK = PACK.resolve("bedrock");

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
        assertModelResolves("mgx:crate_key");
        assertModelResolves("mgx:fortune_potion");
        assertModelResolves("mgx:crate_luck_potion");
        assertModelResolves(CosmeticCatalog.MASKED_MODEL_KEY);
        Map<String, String> textures = new HashMap<>();
        for (CosmeticCatalog.Definition definition : CosmeticCatalog.visualEntries()) {
            assertNotNull(Material.matchMaterial(definition.materialName()),
                    definition.id() + " uses an unknown Java material");
            assertModelResolves(definition.modelKey());
            String texture = resolvedTexture(definition.modelKey());
            assertTrue(textures.putIfAbsent(texture, definition.id()) == null,
                    definition.id() + " reuses the icon for " + textures.get(texture));
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

    @Test
    void customItemIconsUseCrispHighDefinitionSpritesWithARestrictedPalette() throws Exception {
        Set<Path> icons = new HashSet<>();
        Path textures = SOURCE.resolve("assets/mgx/textures/item");
        icons.add(textures.resolve("crate_key.png"));
        icons.add(textures.resolve("fortune_potion.png"));
        icons.add(textures.resolve("crate_luck_potion.png"));
        icons.add(SOURCE.resolve(resolvedTexture(CosmeticCatalog.MASKED_MODEL_KEY)));
        for (CosmeticCatalog.Definition definition : CosmeticCatalog.visualEntries()) {
            icons.add(SOURCE.resolve(resolvedTexture(definition.modelKey())));
        }
        for (Path icon : icons) {
            String name = icon.getFileName().toString();
            BufferedImage image = ImageIO.read(icon.toFile());
            assertNotNull(image, name);
            assertEquals(32, image.getWidth(), name);
            assertEquals(32, image.getHeight(), name);

            Set<Integer> opaqueColors = new HashSet<>();
            boolean hasTransparentPixel = false;
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int argb = image.getRGB(x, y);
                    int alpha = argb >>> 24;
                    assertTrue(alpha == 0 || alpha == 255,
                            name + " has an antialiased pixel at " + x + "," + y);
                    if (alpha == 0) {
                        hasTransparentPixel = true;
                    } else {
                        opaqueColors.add(argb);
                    }
                }
            }
            assertTrue(hasTransparentPixel, name + " needs transparent inventory padding");
            assertTrue(opaqueColors.size() <= 64, name + " uses too many colors: " + opaqueColors.size());
        }
    }

    @Test
    void trailIconsUseTheLargerHighDefinitionFootprint() throws Exception {
        Path textures = SOURCE.resolve("assets/mgx/textures/item/cosmetic");
        for (CosmeticCatalog.Definition definition : CosmeticCatalog.all()) {
            if (definition.category() != CosmeticCatalog.Category.TRAIL || definition.secret()) {
                continue;
            }
            BufferedImage image = ImageIO.read(
                    textures.resolve(definition.id() + ".png").toFile()
            );
            int minX = image.getWidth();
            int minY = image.getHeight();
            int maxX = -1;
            int maxY = -1;
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    if ((image.getRGB(x, y) >>> 24) == 0) {
                        continue;
                    }
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
            int footprint = Math.max(maxX - minX + 1, maxY - minY + 1);
            assertTrue(footprint >= 28 && footprint <= 30,
                    definition.id() + " has unexpected footprint " + footprint);
        }
    }

    @Test
    void bedrockPackAndGeyserMappingsCoverEveryCustomItem() throws Exception {
        JsonObject mappings = JsonParser.parseString(
                Files.readString(BEDROCK.resolve("mgx_items.json"))
        ).getAsJsonObject();
        assertEquals(2, mappings.get("format_version").getAsInt());

        Map<String, String> expectedBases = new HashMap<>();
        expectedBases.put("mgx:crate_key", "minecraft:trial_key");
        expectedBases.put("mgx:fortune_potion", "minecraft:potion");
        expectedBases.put("mgx:crate_luck_potion", "minecraft:potion");
        expectedBases.put(CosmeticCatalog.MASKED_MODEL_KEY, "minecraft:black_dye");
        for (CosmeticCatalog.Definition definition : CosmeticCatalog.visualEntries()) {
            expectedBases.put(
                    definition.modelKey(),
                    "minecraft:" + definition.materialName().toLowerCase(Locale.ROOT)
            );
        }

        Map<String, String> actualBases = new HashMap<>();
        Map<String, String> icons = new HashMap<>();
        Set<String> identifiers = new HashSet<>();
        for (Map.Entry<String, com.google.gson.JsonElement> base
                : mappings.getAsJsonObject("items").entrySet()) {
            for (com.google.gson.JsonElement raw : base.getValue().getAsJsonArray()) {
                JsonObject definition = raw.getAsJsonObject();
                assertEquals("definition", definition.get("type").getAsString());
                String model = definition.get("model").getAsString();
                String identifier = definition.get("bedrock_identifier").getAsString();
                assertTrue(identifiers.add(identifier), "duplicate Bedrock ID " + identifier);
                assertTrue(actualBases.put(model, base.getKey()) == null,
                        "duplicate Java model " + model);
                icons.put(model, definition.getAsJsonObject("bedrock_options")
                        .get("icon").getAsString());
            }
        }
        assertEquals(expectedBases, actualBases);

        try (ZipFile pack = new ZipFile(
                BEDROCK.resolve("MysteriousSMPX-Bedrock.mcpack").toFile()
        )) {
            JsonObject manifest = zipJson(pack, "manifest.json");
            assertEquals(2, manifest.get("format_version").getAsInt());
            assertEquals("resources", manifest.getAsJsonArray("modules").get(0)
                    .getAsJsonObject().get("type").getAsString());
            JsonObject atlas = zipJson(pack, "textures/item_texture.json")
                    .getAsJsonObject("texture_data");
            for (Map.Entry<String, String> item : icons.entrySet()) {
                String texture = atlas.getAsJsonObject(item.getValue())
                        .getAsJsonArray("textures").get(0).getAsString() + ".png";
                ZipEntry entry = pack.getEntry(texture);
                assertNotNull(entry, texture + " is missing from the Bedrock pack");
                try (InputStream input = pack.getInputStream(entry)) {
                    assertArrayEquals(
                            Files.readAllBytes(SOURCE.resolve(resolvedTexture(item.getKey()))),
                            input.readAllBytes(),
                            item.getKey()
                    );
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

    private static String resolvedTexture(String modelKey) throws Exception {
        String[] key = modelKey.split(":", 2);
        Path itemFile = SOURCE.resolve("assets").resolve(key[0]).resolve("items")
                .resolve(key[1] + ".json");
        JsonObject item = JsonParser.parseString(Files.readString(itemFile)).getAsJsonObject();
        String[] model = item.getAsJsonObject("model").get("model").getAsString().split(":", 2);
        Path modelFile = SOURCE.resolve("assets").resolve(model[0]).resolve("models")
                .resolve(model[1] + ".json");
        JsonObject modelJson = JsonParser.parseString(Files.readString(modelFile)).getAsJsonObject();
        String[] texture = modelJson.getAsJsonObject("textures").get("layer0")
                .getAsString().split(":", 2);
        return "assets/" + texture[0] + "/textures/" + texture[1] + ".png";
    }

    private static JsonObject zipJson(ZipFile zip, String name) throws Exception {
        ZipEntry entry = zip.getEntry(name);
        assertNotNull(entry, name + " is missing from the Bedrock pack");
        try (InputStream input = zip.getInputStream(entry)) {
            return JsonParser.parseString(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }
}
