package bot.mgx.accessbridge;

import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BridgeTlsTest {
    @Test
    void emptyCertificatePinKeepsNormalHostnameVerification() {
        assertNull(BridgeTls.decodeSha256Fingerprint(""));
        assertNull(BridgeTls.decodeSha256Fingerprint("  "));
    }

    @Test
    void certificatePinAcceptsPlainOrColonSeparatedSha256() throws Exception {
        byte[] expected = MessageDigest.getInstance("SHA-256").digest("certificate".getBytes());
        String plain = HexFormat.of().formatHex(expected);
        String separated = plain.replaceAll("(..)(?!$)", "$1:");

        assertArrayEquals(expected, BridgeTls.decodeSha256Fingerprint(plain));
        assertArrayEquals(expected, BridgeTls.decodeSha256Fingerprint(separated));
    }

    @Test
    void invalidCertificatePinFailsConfiguration() {
        assertThrows(
                IllegalArgumentException.class,
                () -> BridgeTls.decodeSha256Fingerprint("1234")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> BridgeTls.decodeSha256Fingerprint("not-hex")
        );
    }

    @Test
    void exactCertificateFingerprintIsRequired() throws Exception {
        byte[] certificate = "certificate".getBytes();
        byte[] expected = MessageDigest.getInstance("SHA-256").digest(certificate);

        assertDoesNotThrow(() -> BridgeTls.requireFingerprint(certificate, expected));
        assertThrows(
                CertificateException.class,
                () -> BridgeTls.requireFingerprint(certificate, new byte[32])
        );
    }
}
