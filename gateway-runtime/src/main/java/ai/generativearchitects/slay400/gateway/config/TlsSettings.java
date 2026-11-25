package ai.generativearchitects.slay400.gateway.config;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * The {@code server.tls} section: the PEM files of the API listener's certificate and key, and the password of the key when it
 * is encrypted, resolved from an environment variable at start. The password is never logged and never part of a diagnostic:
 * {@link #toString()} does not show it.
 */
public final class TlsSettings {

    private final Path certificate;
    private final Path key;
    private final char[] keyPassword;

    /**
     * @param certificate the PEM file with the certificate and its chain
     * @param key         the PEM file with the private key, PKCS#8
     * @param keyPassword the password of an encrypted key; copied
     * @throws NullPointerException if an argument is {@code null}
     */
    public TlsSettings(Path certificate, Path key, Optional<char[]> keyPassword) {
        this.certificate = Objects.requireNonNull(certificate, "certificate");
        this.key = Objects.requireNonNull(key, "key");
        this.keyPassword = Objects.requireNonNull(keyPassword, "keyPassword").map(char[]::clone).orElse(null);
    }

    /** The PEM file with the certificate and its chain. */
    public Path certificate() {
        return certificate;
    }

    /** The PEM file with the private key. */
    public Path key() {
        return key;
    }

    /** A copy of the password of the key, if it is encrypted. Never log it. */
    public Optional<char[]> keyPassword() {
        return Optional.ofNullable(keyPassword).map(char[]::clone);
    }

    /** The two files; never the password. */
    @Override
    public String toString() {
        return "TlsSettings[certificate=" + certificate.getFileName() + ", key=" + key.getFileName() + (keyPassword == null ? "" : ", keyPassword=redacted") + "]";
    }
}
