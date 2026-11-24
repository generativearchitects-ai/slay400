package ai.generativearchitects.slay400.gateway;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.AlgorithmParameters;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.crypto.Cipher;
import javax.crypto.EncryptedPrivateKeyInfo;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.PBEParameterSpec;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * Self-signed certificates for the TLS tests, made at run time with the JDK's {@code keytool} into the test's temporary
 * folder. None is ever committed (kickoff 08). Each is for {@code localhost} and {@code 127.0.0.1}.
 */
public final class TestCertificates {

    private TestCertificates() {
    }

    /**
     * A certificate and its key, in memory and as PEM files.
     *
     * @param certificate     the certificate
     * @param key             its private key
     * @param certificateFile the PEM file of the certificate
     * @param keyFile         the PEM file of the key, PKCS#8, not encrypted
     */
    public record Pair(X509Certificate certificate, PrivateKey key, Path certificateFile, Path keyFile) {

        /** A client context that trusts this certificate only. */
        public SSLContext trustingContext() {
            try {
                KeyStore trust = KeyStore.getInstance("PKCS12");
                trust.load(null, null);
                trust.setCertificateEntry("server", certificate);
                TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                factory.init(trust);
                SSLContext context = SSLContext.getInstance("TLS");
                context.init(null, factory.getTrustManagers(), null);
                return context;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        /**
         * Writes the key encrypted by the {@code openssl} command, AES-256 with HMAC-SHA256, as OpenSSL 3 does by default; empty
         * when there is no {@code openssl} that can.
         */
        public java.util.Optional<Path> opensslEncryptedKey(Path file, String password) {
            try {
                Process process = new ProcessBuilder("openssl", "pkcs8", "-topk8", "-in", keyFile.toString(), "-v2", "aes-256-cbc", "-v2prf", "hmacWithSHA256",
                        "-passout", "pass:" + password, "-out", file.toString()).redirectErrorStream(true).start();
                process.getInputStream().readAllBytes();
                return process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0 && Files.exists(file) ? java.util.Optional.of(file) : java.util.Optional.empty();
            } catch (IOException e) {
                return java.util.Optional.empty();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return java.util.Optional.empty();
            }
        }

        /** The base64 of the key, which must appear in no log line or message. */
        public String keyText() {
            return Base64.getEncoder().encodeToString(key.getEncoded());
        }

        /** Writes the key encrypted with this password (PBES2, HMAC-SHA1 and AES-256) to a file. */
        public Path encryptedKey(Path file, char[] password) {
            try {
                byte[] salt = new byte[16];
                byte[] iv = new byte[16];
                new SecureRandom().nextBytes(salt);
                new SecureRandom().nextBytes(iv);
                String algorithm = "PBEWithHmacSHA1AndAES_256";
                Cipher cipher = Cipher.getInstance(algorithm);
                cipher.init(Cipher.ENCRYPT_MODE, SecretKeyFactory.getInstance(algorithm).generateSecret(new PBEKeySpec(password)),
                        new PBEParameterSpec(salt, 10_000, new IvParameterSpec(iv)));
                byte[] encrypted = cipher.doFinal(key.getEncoded());
                // Written as PBES2, the form OpenSSL writes with -v2 aes-256-cbc. The JDK keeps the PRF of these parameters only
                // for HMAC-SHA1 when they are written as PBES2; the test with openssl covers HMAC-SHA256.
                AlgorithmParameters parameters = AlgorithmParameters.getInstance("PBES2");
                parameters.init(cipher.getParameters().getEncoded());
                return pem(file, "ENCRYPTED PRIVATE KEY", new EncryptedPrivateKeyInfo(parameters, encrypted).getEncoded());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** An RSA certificate valid from yesterday for {@code days} days. */
    public static Pair rsa(Path dir, String name, int days) {
        return make(dir, name, List.of("-keyalg", "RSA", "-keysize", "2048", "-startdate", "-1d", "-validity", String.valueOf(days + 1)));
    }

    /** An EC (P-256) certificate valid from yesterday for {@code days} days. */
    public static Pair ec(Path dir, String name, int days) {
        return make(dir, name, List.of("-keyalg", "EC", "-groupname", "secp256r1", "-startdate", "-1d", "-validity", String.valueOf(days + 1)));
    }

    /** An RSASSA-PSS certificate valid from yesterday for {@code days} days. */
    public static Pair rsaPss(Path dir, String name, int days) {
        return make(dir, name, List.of("-keyalg", "RSASSA-PSS", "-keysize", "2048", "-startdate", "-1d", "-validity", String.valueOf(days + 1)));
    }

    /** An RSA certificate valid from {@code startDays} days from now (negative: in the past) for {@code days} days. */
    public static Pair rsaFrom(Path dir, String name, int startDays, int days) {
        return make(dir, name, List.of("-keyalg", "RSA", "-keysize", "2048", "-startdate", (startDays >= 0 ? "+" : "") + startDays + "d", "-validity",
                String.valueOf(days)));
    }

    private static Pair make(Path dir, String name, List<String> options) {
        try {
            Files.createDirectories(dir);
            Path store = dir.resolve(name + ".p12");
            Files.deleteIfExists(store);
            String storePassword = "store-" + Long.toHexString(new SecureRandom().nextLong());
            List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(), "-genkeypair", "-alias", "server",
                    "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-storetype", "PKCS12", "-keystore", store.toString(),
                    "-storepass", storePassword, "-keypass", storePassword));
            command.addAll(options);
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output;
            try (InputStream in = process.getInputStream()) {
                output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IllegalStateException("keytool failed: " + output);
            }
            KeyStore keys = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(store)) {
                keys.load(in, storePassword.toCharArray());
            }
            PrivateKey key = (PrivateKey) keys.getKey("server", storePassword.toCharArray());
            Certificate[] chain = keys.getCertificateChain("server");
            X509Certificate certificate = (X509Certificate) chain[0];
            Files.delete(store);
            Path certificateFile = pem(dir.resolve(name + ".crt"), "CERTIFICATE", certificate.getEncoded());
            Path keyFile = pem(dir.resolve(name + ".key"), "PRIVATE KEY", key.getEncoded());
            return new Pair(certificate, key, certificateFile, keyFile);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Writes a PEM block. */
    public static Path pem(Path file, String kind, byte[] der) {
        String body = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, "-----BEGIN " + kind + "-----\n" + body + "\n-----END " + kind + "-----\n", StandardCharsets.US_ASCII);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
