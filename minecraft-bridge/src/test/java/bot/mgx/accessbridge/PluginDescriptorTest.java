package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * plugin.yml is parsed by the server at boot, not by the compiler, so a broken one
 * builds and ships happily and then stops the whole plugin from loading. An unquoted
 * colon inside a description did exactly that once; this is the guard.
 */
class PluginDescriptorTest {
    private static Map<String, Object> descriptor() throws Exception {
        Path file = Path.of("src/main/resources/plugin.yml");
        assertTrue(Files.isRegularFile(file), "plugin.yml is missing at " + file.toAbsolutePath());
        // The Gradle token is not a YAML construct, so it survives parsing as plain text.
        return new Yaml().load(Files.readString(file));
    }

    @Test
    void pluginYamlParses() throws Exception {
        Map<String, Object> root = descriptor();
        assertEquals("MGXAccessBridge", root.get("name"));
        assertEquals("bot.mgx.accessbridge.MGXAccessBridge", root.get("main"));
        assertNotNull(root.get("api-version"), "api-version decides whether Paper loads this at all");
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyCommandAndPermissionIsAMappingWithADescription() throws Exception {
        Map<String, Object> root = descriptor();
        Map<String, Object> commands = (Map<String, Object>) root.get("commands");
        assertNotNull(commands, "commands block failed to parse");
        assertTrue(commands.containsKey("mgxadmin"), "the operator command must be declared");
        assertTrue(((Map<?, ?>) commands.get("mgxadmin")).get("usage").toString()
                        .contains("testairdrop"),
                "the local Airdrop test suite must be discoverable from command usage");
        for (Map.Entry<String, Object> entry : commands.entrySet()) {
            assertTrue(entry.getValue() instanceof Map,
                    entry.getKey() + " did not parse as a mapping; check for an unquoted colon");
            Map<String, Object> body = (Map<String, Object>) entry.getValue();
            assertTrue(body.get("description") instanceof String,
                    entry.getKey() + " has no plain-string description");
        }
        Map<String, Object> permissions = (Map<String, Object>) root.get("permissions");
        assertNotNull(permissions, "permissions block failed to parse");
        for (Map.Entry<String, Object> entry : permissions.entrySet()) {
            assertTrue(entry.getValue() instanceof Map,
                    entry.getKey() + " did not parse as a mapping; check for an unquoted colon");
        }
    }
}
