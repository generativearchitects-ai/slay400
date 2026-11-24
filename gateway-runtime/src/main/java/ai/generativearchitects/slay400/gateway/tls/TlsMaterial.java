package ai.generativearchitects.slay400.gateway.tls;

import ai.generativearchitects.slay400.gateway.config.TlsSettings;
import ai.generativearchitects.slay400.pcml.diagnostics.Diagnostic;
import ai.generativearchitects.slay400.pcml.diagnostics.Severity;
import ai.generativearchitects.slay400.pcml.model.SourceLocation;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.EncryptedPrivateKeyInfo;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * The certificate and key of the API listener, read from their PEM files and checked (kickoff 08): the certificate and its
 * chain, a PKCS#8 private key (encrypted with {@code server.tls.keyPassword}, or not), that match each other, and a
 * certificate valid now. With the JDK only.
 *
 * <p>Diagnostic codes: {@code GWY022} a file that cannot be read, is not PEM of the expected kind, or a key that cannot be
 * decrypted; {@code GWY023} a key that is not the key of the certificate; {@code GWY024} a certificate that has expired or is
 * not valid yet; {@code GWY025}, a warning, a certificate that expires within {@value #WARN_DAYS} days. A message names the
 * file and never shows key material or the password.
 */
public final class TlsMaterial {

    /** The protocols the listener speaks, and no other. */
    public static final List<String> PROTOCOLS = List.of("TLSv1.3", "TLSv1.2");

    /** A certificate that expires within this many days is a warning. */
    public static final int WARN_DAYS = 30;

    /** The largest PEM file read, in bytes. */
    static final int MAX_FILE_BYTES = 1024 * 1024;

    private static final Pattern BLOCK = Pattern.compile("-----BEGIN ([A-Z0-9 ]+)-----([A-Za-z0-9+/=\\s]*)-----END \\1-----");

    private final PrivateKey key;
    private final List<X509Certificate> chain;

    private TlsMaterial(PrivateKey key, List<X509Certificate> chain) {
        this.key = key;
        this.chain = List.copyOf(chain);
    }

    /** What reading the two files came to: the material, if nothing is an error, and every diagnostic. */
    public record Result(Optional<TlsMaterial> material, List<Diagnostic> diagnostics) {

        /** @throws NullPointerException if an argument is {@code null} */
        public Result {
            Objects.requireNonNull(material, "material");
            diagnostics = List.copyOf(diagnostics);
        }
    }

    /**
     * The private key, for the listener. Never log it. The server hands key and chain to the listener rather than a context, so
     * that the listener can replace them while it runs.
     */
    public PrivateKey privateKey() {
        return key;
    }

    /** The certificate the listener presents, then its chain. */
    public List<X509Certificate> certificateChain() {
        return chain;
    }

    /** The end of the validity of the certificate the listener presents. */
    public Instant notAfter() {
        return chain.get(0).getNotAfter().toInstant();
    }

    /**
     * Reads and checks the files of the settings.
     *
     * @param now the time against which the certificate's validity is checked
     */
    public static Result load(TlsSettings settings, Instant now) {
        List<Diagnostic> found = new ArrayList<>();
        Optional<List<X509Certificate>> chain = certificates(settings.certificate(), found);
        Optional<PrivateKey> key = key(settings.key(), settings.keyPassword(), found);
        if (chain.isEmpty() || key.isEmpty()) {
            return new Result(Optional.empty(), found);
        }
        X509Certificate leaf = chain.get().get(0);
        if (!matches(leaf, key.get())) {
            found.add(error("GWY023", "The key in " + name(settings.key()) + " is not the key of the certificate in " + name(settings.certificate()),
                    settings.key()));
            return new Result(Optional.empty(), found);
        }
        Instant notBefore = leaf.getNotBefore().toInstant();
        Instant notAfter = leaf.getNotAfter().toInstant();
        if (now.isAfter(notAfter)) {
            found.add(error("GWY024", "The certificate in " + name(settings.certificate()) + " expired on " + day(notAfter), settings.certificate()));
            return new Result(Optional.empty(), found);
        }
        if (now.isBefore(notBefore)) {
            found.add(error("GWY024", "The certificate in " + name(settings.certificate()) + " is not valid before " + day(notBefore), settings.certificate()));
            return new Result(Optional.empty(), found);
        }
        Duration left = Duration.between(now, notAfter);
        if (left.compareTo(Duration.ofDays(WARN_DAYS)) < 0) {
            found.add(new Diagnostic("GWY025", Severity.WARNING, "The certificate in " + name(settings.certificate()) + " expires on " + day(notAfter)
                    + ", in " + left.toDays() + " days", location(settings.certificate())));
        }
        return new Result(Optional.of(new TlsMaterial(key.get(), chain.get())), found);
    }

    private static Optional<List<X509Certificate>> certificates(Path file, List<Diagnostic> found) {
        Optional<String> text = read(file, "certificate", found);
        if (text.isEmpty()) {
            return Optional.empty();
        }
        List<X509Certificate> chain = new ArrayList<>();
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            Matcher block = BLOCK.matcher(text.get());
            while (block.find()) {
                if (block.group(1).equals("CERTIFICATE")) {
                    byte[] der = Base64.getMimeDecoder().decode(block.group(2));
                    chain.add((X509Certificate) factory.generateCertificate(new ByteArrayInputStream(der)));
                }
            }
        } catch (GeneralSecurityException | IllegalArgumentException | ClassCastException e) {
            found.add(error("GWY022", "The certificate file " + name(file) + " holds a certificate that cannot be read", file));
            return Optional.empty();
        }
        if (chain.isEmpty()) {
            found.add(error("GWY022", "The certificate file " + name(file) + " holds no PEM certificate (-----BEGIN CERTIFICATE-----)", file));
            return Optional.empty();
        }
        return Optional.of(chain);
    }

    private static Optional<PrivateKey> key(Path file, Optional<char[]> password, List<Diagnostic> found) {
        Optional<String> text = read(file, "key", found);
        if (text.isEmpty()) {
            return Optional.empty();
        }
        Matcher block = BLOCK.matcher(text.get());
        String kind = null;
        byte[] der = null;
        while (block.find()) {
            if (block.group(1).endsWith("PRIVATE KEY")) {
                kind = block.group(1);
                der = Base64.getMimeDecoder().decode(block.group(2));
                break;
            }
        }
        char[] secret = password.orElse(null);
        try {
            if (kind == null) {
                found.add(error("GWY022", "The key file " + name(file) + " holds no PEM private key (-----BEGIN PRIVATE KEY-----)", file));
                return Optional.empty();
            }
            if (kind.equals("ENCRYPTED PRIVATE KEY")) {
                if (secret == null) {
                    found.add(error("GWY022", "The key in " + name(file) + " is encrypted; server.tls.keyPassword is needed", file));
                    return Optional.empty();
                }
                Optional<PKCS8EncodedKeySpec> spec = decrypt(der, secret);
                if (spec.isEmpty()) {
                    found.add(error("GWY022", "The key in " + name(file) + " cannot be decrypted with server.tls.keyPassword", file));
                    return Optional.empty();
                }
                der = spec.get().getEncoded();
            } else if (!kind.equals("PRIVATE KEY")) {
                found.add(error("GWY022", "The key in " + name(file) + " is " + kind + ", not PKCS#8 (-----BEGIN PRIVATE KEY-----); convert it, for example with "
                        + "openssl pkcs8 -topk8", file));
                return Optional.empty();
            } else if (secret != null) {
                found.add(error("GWY022", "The key in " + name(file) + " is not encrypted, but server.tls.keyPassword is set", file));
                return Optional.empty();
            }
            Optional<PrivateKey> key = privateKey(der);
            if (key.isEmpty()) {
                found.add(error("GWY022", "The key in " + name(file) + " is not an RSA, EC or EdDSA private key that can be read", file));
            }
            return key;
        } finally {
            if (secret != null) {
                Arrays.fill(secret, '\0');
            }
            if (der != null) {
                Arrays.fill(der, (byte) 0);
            }
        }
    }

    private static Optional<PKCS8EncodedKeySpec> decrypt(byte[] der, char[] password) {
        try {
            EncryptedPrivateKeyInfo info = new EncryptedPrivateKeyInfo(der);
            // A PBES2 key names its scheme in its parameters; an older one in its algorithm.
            String algorithm = info.getAlgParameters() != null ? info.getAlgParameters().toString() : info.getAlgName();
            SecretKeyFactory factory = SecretKeyFactory.getInstance(algorithm);
            PBEKeySpec spec = new PBEKeySpec(password);
            try {
                Cipher cipher = Cipher.getInstance(algorithm);
                cipher.init(Cipher.DECRYPT_MODE, factory.generateSecret(spec), info.getAlgParameters());
                return Optional.of(info.getKeySpec(cipher));
            } finally {
                spec.clearPassword();
            }
        } catch (GeneralSecurityException | IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static Optional<PrivateKey> privateKey(byte[] der) {
        for (String algorithm : List.of("RSA", "EC", "EdDSA", "RSASSA-PSS")) {
            try {
                return Optional.of(KeyFactory.getInstance(algorithm).generatePrivate(new PKCS8EncodedKeySpec(der)));
            } catch (InvalidKeySpecException e) {
                // Another algorithm.
            } catch (GeneralSecurityException e) {
                // Not in this Java.
            }
        }
        return Optional.empty();
    }

    /** Whether the key is that of the certificate: a signature with the key that the certificate's public key verifies. */
    static boolean matches(X509Certificate certificate, PrivateKey key) {
        String algorithm = switch (key.getAlgorithm()) {
            case "RSA" -> "SHA256withRSA";
            case "EC" -> "SHA256withECDSA";
            case "EdDSA", "Ed25519", "Ed448" -> key.getAlgorithm();
            case "RSASSA-PSS" -> "RSASSA-PSS";
            default -> null;
        };
        if (algorithm == null) {
            return false;
        }
        try {
            byte[] challenge = new byte[32];
            new SecureRandom().nextBytes(challenge);
            Signature signer = Signature.getInstance(algorithm);
            Signature verifier = Signature.getInstance(algorithm);
            if (algorithm.equals("RSASSA-PSS")) {
                java.security.spec.PSSParameterSpec pss = new java.security.spec.PSSParameterSpec("SHA-256", "MGF1",
                        java.security.spec.MGF1ParameterSpec.SHA256, 32, 1);
                signer.setParameter(pss);
                verifier.setParameter(pss);
            }
            signer.initSign(key);
            signer.update(challenge);
            byte[] signature = signer.sign();
            verifier.initVerify(certificate.getPublicKey());
            verifier.update(challenge);
            return verifier.verify(signature);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    private static Optional<String> read(Path file, String what, List<Diagnostic> found) {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] bytes = in.readNBytes(MAX_FILE_BYTES + 1);
            if (bytes.length > MAX_FILE_BYTES) {
                found.add(error("GWY022", "The " + what + " file " + name(file) + " is larger than " + MAX_FILE_BYTES + " bytes", file));
                return Optional.empty();
            }
            return Optional.of(new String(bytes, StandardCharsets.US_ASCII));
        } catch (java.nio.file.NoSuchFileException e) {
            found.add(error("GWY022", "The " + what + " file " + name(file) + " does not exist", file));
        } catch (IOException e) {
            found.add(error("GWY022", "The " + what + " file " + name(file) + " cannot be read (" + e.getClass().getSimpleName() + ")", file));
        }
        return Optional.empty();
    }

    private static String name(Path file) {
        return String.valueOf(file.getFileName());
    }

    private static String day(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC).toLocalDate().toString();
    }

    private static SourceLocation location(Path file) {
        return new SourceLocation(file.toString(), 1, 1);
    }

    private static Diagnostic error(String code, String message, Path file) {
        return new Diagnostic(code, Severity.ERROR, message, location(file));
    }

    /** The end of validity only; no material. */
    @Override
    public String toString() {
        return "TlsMaterial[notAfter=" + notAfter() + "]";
    }
}
