package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SelfDestructTest {
    private static final Logger LOG = Logger.getLogger("test");

    @Test
    void anUnlicensedCopyIsRefusedButTheLocalTestServerIsExempt(@TempDir Path root) throws Exception {
        // A thief's copy: the jar and nothing else. No keyfile at the server root.
        assertFalse(SelfDestruct.licensed(root, "mysterious-smp-x"),
                "a real server with no keyfile must not run");
        assertTrue(SelfDestruct.licensed(root, SelfDestruct.LOCAL_TEST_ID),
                "the local test server runs without a key");

        Files.writeString(root.resolve(SelfDestruct.KEY_FILE), "license=owned-by-9mits\n");
        assertTrue(SelfDestruct.licensed(root, "mysterious-smp-x"),
                "the owner's keyfile licenses the real server");
    }

    @Test
    void onlyTheRealPassphraseArmsDetonation(@TempDir Path root) throws Exception {
        assertFalse(SelfDestruct.detonationAuthorised(root, "anything"),
                "no keyfile means the switch is disarmed");

        Files.writeString(root.resolve(SelfDestruct.KEY_FILE),
                "license=owned-by-9mits\ndestruct=" + SelfDestruct.sha256("correct horse") + "\n");
        assertFalse(SelfDestruct.detonationAuthorised(root, "wrong"), "a wrong passphrase is refused");
        assertFalse(SelfDestruct.detonationAuthorised(root, ""), "an empty passphrase is refused");
        assertTrue(SelfDestruct.detonationAuthorised(root, "correct horse"), "the real passphrase arms it");
    }

    @Test
    void aDisarmedOrGarbledDestructLineNeverMatches(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve(SelfDestruct.KEY_FILE), "license=owned\ndestruct=notahash\n");
        assertFalse(SelfDestruct.detonationAuthorised(root, "notahash"),
                "the destruct value is a hash to compare against, never a plaintext to echo");
    }

    @Test
    void terminationErasesEveryWorldButLeavesUnrelatedFolders(@TempDir Path root) throws Exception {
        Path container = Files.createDirectories(root.resolve("worlds"));
        Path overworld = Files.createDirectories(container.resolve("world"));
        Files.writeString(overworld.resolve("level.dat"), "x");
        Files.writeString(Files.createDirectories(overworld.resolve("region")).resolve("r.0.0.mca"), "data");
        Path plugins = Files.createDirectories(container.resolve("plugins"));
        Files.writeString(plugins.resolve("keep.jar"), "not a world");

        assertFalse(SelfDestruct.terminated(root));
        Files.writeString(root.resolve(SelfDestruct.TERMINATED_FLAG), "gone");
        assertTrue(SelfDestruct.terminated(root));

        SelfDestruct.enforceTerminationAtLoad(root, container, LOG);
        assertFalse(Files.exists(overworld), "the world with a level.dat is erased");
        assertTrue(Files.exists(plugins.resolve("keep.jar")), "a folder that is not a world is left alone");
    }

    @Test
    void sha256IsStableAndLowercaseHex() {
        assertEquals(64, SelfDestruct.sha256("x").length());
        assertEquals(SelfDestruct.sha256("mgx"), SelfDestruct.sha256("mgx"));
        assertTrue(SelfDestruct.sha256("mgx").matches("[0-9a-f]{64}"));
    }
}
