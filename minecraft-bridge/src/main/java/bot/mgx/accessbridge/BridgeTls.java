package bot.mgx.accessbridge;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedTrustManager;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.HexFormat;

final class BridgeTls {
    private BridgeTls() {
    }

    static byte[] decodeSha256Fingerprint(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.replace(":", "").trim();
        byte[] decoded;
        try {
            decoded = HexFormat.of().parseHex(normalized);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "bridge-certificate-sha256 must be 64 hexadecimal characters",
                    exception
            );
        }
        if (decoded.length != 32) {
            throw new IllegalArgumentException(
                    "bridge-certificate-sha256 must decode to exactly 32 bytes"
            );
        }
        return decoded;
    }

    static SSLContext pinnedSslContext(byte[] expectedFingerprint) {
        if (expectedFingerprint == null || expectedFingerprint.length != 32) {
            throw new IllegalArgumentException("A 32-byte certificate fingerprint is required");
        }
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(
                    null,
                    new X509ExtendedTrustManager[]{new PinnedTrustManager(expectedFingerprint)},
                    null
            );
            return context;
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Could not initialize pinned bridge TLS", exception);
        }
    }

    static void requireFingerprint(byte[] certificateDer, byte[] expectedFingerprint)
            throws CertificateException {
        byte[] actual;
        try {
            actual = MessageDigest.getInstance("SHA-256").digest(certificateDer);
        } catch (GeneralSecurityException exception) {
            throw new CertificateException("SHA-256 is unavailable", exception);
        }
        if (!MessageDigest.isEqual(actual, expectedFingerprint)) {
            throw new CertificateException(
                    "Bridge certificate fingerprint mismatch; expected "
                            + HexFormat.of().formatHex(expectedFingerprint)
                            + " but received "
                            + HexFormat.of().formatHex(actual)
            );
        }
    }

    private static final class PinnedTrustManager extends X509ExtendedTrustManager {
        private final byte[] expectedFingerprint;

        private PinnedTrustManager(byte[] expectedFingerprint) {
            this.expectedFingerprint = Arrays.copyOf(
                    expectedFingerprint,
                    expectedFingerprint.length
            );
        }

        private void verifyPin(X509Certificate[] chain) throws CertificateException {
            if (chain == null || chain.length == 0) {
                throw new CertificateException("Bridge did not present a certificate");
            }
            chain[0].checkValidity();
            try {
                requireFingerprint(chain[0].getEncoded(), expectedFingerprint);
            } catch (CertificateEncodingException exception) {
                throw new CertificateException("Could not encode the bridge certificate", exception);
            }
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            throw new CertificateException("The bridge does not trust client certificates");
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            verifyPin(chain);
        }

        @Override
        public void checkClientTrusted(
                X509Certificate[] chain,
                String authType,
                Socket socket
        ) throws CertificateException {
            checkClientTrusted(chain, authType);
        }

        @Override
        public void checkServerTrusted(
                X509Certificate[] chain,
                String authType,
                Socket socket
        ) throws CertificateException {
            verifyPin(chain);
        }

        @Override
        public void checkClientTrusted(
                X509Certificate[] chain,
                String authType,
                SSLEngine engine
        ) throws CertificateException {
            checkClientTrusted(chain, authType);
        }

        @Override
        public void checkServerTrusted(
                X509Certificate[] chain,
                String authType,
                SSLEngine engine
        ) throws CertificateException {
            verifyPin(chain);
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
