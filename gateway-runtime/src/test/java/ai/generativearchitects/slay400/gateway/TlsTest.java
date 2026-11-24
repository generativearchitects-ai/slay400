package ai.generativearchitects.slay400.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.slay400.gateway.config.ConfigResult;
import ai.generativearchitects.slay400.gateway.config.ConfigReader;
import ai.generativearchitects.slay400.gateway.config.GatewayConfig;
import ai.generativearchitects.slay400.gateway.config.ManagementConfig;
import ai.generativearchitects.slay400.gateway.config.TlsSettings;
import ai.generativearchitects.slay400.gateway.load.GatewayLoader;
import ai.generativearchitects.slay400.gateway.load.StartResult;
import ai.generativearchitects.slay400.gateway.tls.TlsMaterial;
import ai.generativearchitects.slay400.pcml.diagnostics.Diagnostic;
import ai.generativearchitects.slay400.pcml.diagnostics.Severity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The certificate and key of the API listener, and the configuration of the listeners (kickoff 08, Part A). */
class TlsTest {

    @TempDir
    Path dir;

    private static final String PASSWORD = "pw-MARKER-31415";

    private static TlsMaterial.Result load(Path certificate, Path key, char[] password) {
        return TlsMaterial.load(new TlsSettings(certificate, key, Optional.ofNullable(password)), Instant.now());
    }

    private static String codes(List<Diagnostic> diagnostics) {
        return diagnostics.stream().map(Diagnostic::code).collect(Collectors.joining(","));
    }

    /** Every message and file of the diagnostics, which must not show the key, its PEM body or the password. */
    private static String text(List<Diagnostic> diagnostics) {
        return diagnostics.stream().map(d -> d.message() + " " + d.location()).collect(Collectors.joining("\n"));
    }

    // ---- the pair

    @Test
    void anRsaAndAnEcPairLoad() {
        TestCertificates.Pair rsa = TestCertificates.rsa(dir, "rsa", 365);
        TestCertificates.Pair ec = TestCertificates.ec(dir, "ec", 365);
        for (TestCertificates.Pair pair : List.of(rsa, ec)) {
            TlsMaterial.Result result = load(pair.certificateFile(), pair.keyFile(), null);
            assertThat(result.diagnostics()).isEmpty();
            assertThat(result.material()).isPresent();
            assertThat(result.material().get().notAfter()).isEqualTo(pair.certificate().getNotAfter().toInstant());
            assertThat(result.material().get().sslContext().getProtocol()).isEqualTo("TLS");
            assertThat(result.material().get().toString()).doesNotContain(pair.keyText().substring(0, 40));
        }
        assertThat(TlsMaterial.PROTOCOLS).containsExactly("TLSv1.3", "TLSv1.2");
    }

    @Test
    void anEncryptedKeyLoadsWithItsPasswordOnly() {
        TestCertificates.Pair pair = TestCertificates.rsa(dir, "rsa", 365);
        Path encrypted = pair.encryptedKey(dir.resolve("encrypted.key"), PASSWORD.toCharArray());
        assertThat(load(pair.certificateFile(), encrypted, PASSWORD.toCharArray()).material()).isPresent();

        TlsMaterial.Result wrong = load(pair.certificateFile(), encrypted, "not-it".toCharArray());
        assertThat(codes(wrong.diagnostics())).isEqualTo("GWY022");
        assertThat(wrong.diagnostics().get(0).message()).isEqualTo("The key in encrypted.key cannot be decrypted with server.tls.keyPassword");
        TlsMaterial.Result none = load(pair.certificateFile(), encrypted, null);
        assertThat(none.diagnostics().get(0).message()).isEqualTo("The key in encrypted.key is encrypted; server.tls.keyPassword is needed");
        TlsMaterial.Result extra = load(pair.certificateFile(), pair.keyFile(), PASSWORD.toCharArray());
        assertThat(extra.diagnostics().get(0).message()).isEqualTo("The key in rsa.key is not encrypted, but server.tls.keyPassword is set");
        assertThat(text(wrong.diagnostics()) + text(none.diagnostics()) + text(extra.diagnostics())).doesNotContain(PASSWORD).doesNotContain("not-it");
    }

    @Test
    void aKeyEncryptedByOpensslLoads() {
        TestCertificates.Pair pair = TestCertificates.rsa(dir, "rsa", 365);
        Optional<Path> encrypted = pair.opensslEncryptedKey(dir.resolve("openssl.key"), PASSWORD);
        org.junit.jupiter.api.Assumptions.assumeTrue(encrypted.isPresent(), "no openssl with -v2prf here");
        assertThat(load(pair.certificateFile(), encrypted.get(), PASSWORD.toCharArray()).material()).isPresent();
        assertThat(codes(load(pair.certificateFile(), encrypted.get(), "wrong".toCharArray()).diagnostics())).isEqualTo("GWY022");
    }

    @Test
    void filesThatAreMissingOrNotPemOrNotPkcs8AreRefusedByName() throws IOException {
        TestCertificates.Pair pair = TestCertificates.rsa(dir, "rsa", 365);
        Path missing = dir.resolve("missing.crt");
        assertThat(load(missing, pair.keyFile(), null).diagnostics()).extracting(Diagnostic::message).containsExactly("The certificate file missing.crt does not exist");
        Path text = Files.writeString(dir.resolve("text.crt"), "not a certificate");
        assertThat(load(text, pair.keyFile(), null).diagnostics()).extracting(Diagnostic::message)
                .containsExactly("The certificate file text.crt holds no PEM certificate (-----BEGIN CERTIFICATE-----)");
        Path broken = TestCertificates.pem(dir.resolve("broken.crt"), "CERTIFICATE", new byte[] {1, 2, 3});
        assertThat(load(broken, pair.keyFile(), null).diagnostics()).extracting(Diagnostic::message)
                .containsExactly("The certificate file broken.crt holds a certificate that cannot be read");
        // The key as the certificate file, and the certificate as the key file.
        assertThat(load(pair.keyFile(), pair.keyFile(), null).diagnostics()).extracting(Diagnostic::code).containsExactly("GWY022");
        assertThat(load(pair.certificateFile(), pair.certificateFile(), null).diagnostics()).extracting(Diagnostic::message)
                .containsExactly("The key file rsa.crt holds no PEM private key (-----BEGIN PRIVATE KEY-----)");
        Path pkcs1 = TestCertificates.pem(dir.resolve("pkcs1.key"), "RSA PRIVATE KEY", new byte[] {1, 2, 3});
        assertThat(load(pair.certificateFile(), pkcs1, null).diagnostics()).extracting(Diagnostic::message).containsExactly(
                "The key in pkcs1.key is RSA PRIVATE KEY, not PKCS#8 (-----BEGIN PRIVATE KEY-----); convert it, for example with openssl pkcs8 -topk8");
        Path junk = TestCertificates.pem(dir.resolve("junk.key"), "PRIVATE KEY", new byte[] {1, 2, 3});
        assertThat(load(pair.certificateFile(), junk, null).diagnostics()).extracting(Diagnostic::message)
                .containsExactly("The key in junk.key is not an RSA, EC or EdDSA private key that can be read");
        Path large = Files.write(dir.resolve("large.crt"), new byte[1024 * 1024 + 1]);
        assertThat(load(large, pair.keyFile(), null).diagnostics()).extracting(Diagnostic::message).containsExactly("The certificate file large.crt is larger than 1048576 bytes");
        // Every refusal names its file in its location.
        assertThat(load(missing, pair.keyFile(), null).diagnostics().get(0).location().file()).isEqualTo(missing.toString());
    }

    @Test
    void aKeyThatIsNotTheKeyOfTheCertificateIsRefused() {
        TestCertificates.Pair one = TestCertificates.rsa(dir, "one", 365);
        TestCertificates.Pair other = TestCertificates.rsa(dir, "other", 365);
        TestCertificates.Pair ec = TestCertificates.ec(dir, "ec", 365);
        TlsMaterial.Result result = load(one.certificateFile(), other.keyFile(), null);
        assertThat(codes(result.diagnostics())).isEqualTo("GWY023");
        assertThat(result.diagnostics().get(0).message()).isEqualTo("The key in other.key is not the key of the certificate in one.crt");
        assertThat(codes(load(one.certificateFile(), ec.keyFile(), null).diagnostics())).isEqualTo("GWY023");
        assertThat(codes(load(ec.certificateFile(), one.keyFile(), null).diagnostics())).isEqualTo("GWY023");
        assertThat(text(result.diagnostics())).doesNotContain(other.keyText().substring(0, 40)).doesNotContain(one.keyText().substring(0, 40));
    }

    @Test
    void aCertificateThatExpiredOrIsNotValidYetIsRefusedAndOneNearItsEndIsAWarning() {
        TestCertificates.Pair expired = TestCertificates.rsaFrom(dir, "expired", -10, 5);
        TlsMaterial.Result old = load(expired.certificateFile(), expired.keyFile(), null);
        assertThat(codes(old.diagnostics())).isEqualTo("GWY024");
        assertThat(old.diagnostics().get(0).message()).startsWith("The certificate in expired.crt expired on ");
        TestCertificates.Pair future = TestCertificates.rsaFrom(dir, "future", 10, 30);
        assertThat(load(future.certificateFile(), future.keyFile(), null).diagnostics()).extracting(Diagnostic::message)
                .singleElement().asString().startsWith("The certificate in future.crt is not valid before ");
        TestCertificates.Pair soon = TestCertificates.rsa(dir, "soon", 10);
        TlsMaterial.Result near = load(soon.certificateFile(), soon.keyFile(), null);
        assertThat(near.material()).isPresent();
        assertThat(near.diagnostics()).singleElement().satisfies(d -> {
            assertThat(d.code()).isEqualTo("GWY025");
            assertThat(d.severity()).isEqualTo(Severity.WARNING);
            assertThat(d.message()).startsWith("The certificate in soon.crt expires on ").endsWith(" days");
        });
        // A certificate with more than 30 days left is no warning.
        TestCertificates.Pair later = TestCertificates.rsa(dir, "later", 40);
        assertThat(load(later.certificateFile(), later.keyFile(), null).diagnostics()).isEmpty();
    }

    @Test
    void theSettingsDoNotShowThePassword() {
        TlsSettings settings = new TlsSettings(dir.resolve("a.crt"), dir.resolve("a.key"), Optional.of(PASSWORD.toCharArray()));
        assertThat(settings.toString()).isEqualTo("TlsSettings[certificate=a.crt, key=a.key, keyPassword=redacted]").doesNotContain(PASSWORD);
        assertThat(new String(settings.keyPassword().orElseThrow())).isEqualTo(PASSWORD);
    }

    // ---- the configuration

    private ConfigResult read(String server, Map<String, String> environment) {
        String yaml = server + "tenants:\n  acme:\n    bundles: [./a.rpgapi]\n    environments:\n      test: { code: T0001, backend: mock }\n";
        return ConfigReader.read(Fixtures.write(dir, "gateway.yaml", yaml), environment::get);
    }

    @Test
    void theListenersHaveTheirDefaults() {
        GatewayConfig config = read("", Map.of()).config().orElseThrow();
        assertThat(config.address()).isEqualTo("127.0.0.1");
        assertThat(config.port()).isEqualTo(8080);
        assertThat(config.tls()).isEmpty();
        assertThat(config.management()).isEqualTo(new ManagementConfig("127.0.0.1", 9090));
        assertThat(config.management().enabled()).isTrue();
    }

    @Test
    void theTlsSectionNamesTwoFilesNextToTheConfigurationAndAPlaceholderPassword() {
        GatewayConfig config = read("server:\n  address: 0.0.0.0\n  port: 8443\n  tls:\n    certificate: tls/tls.crt\n    key: tls/tls.key\n"
                + "    keyPassword: ${TLS_KEY_PASSWORD}\nmanagement:\n  address: 0.0.0.0\n  port: 9191\n", Map.of("TLS_KEY_PASSWORD", PASSWORD)).config().orElseThrow();
        assertThat(config.address()).isEqualTo("0.0.0.0");
        TlsSettings tls = config.tls().orElseThrow();
        assertThat(tls.certificate()).isEqualTo(dir.resolve("tls/tls.crt").toAbsolutePath().normalize());
        assertThat(tls.key()).isEqualTo(dir.resolve("tls/tls.key").toAbsolutePath().normalize());
        assertThat(new String(tls.keyPassword().orElseThrow())).isEqualTo(PASSWORD);
        assertThat(config.management()).isEqualTo(new ManagementConfig("0.0.0.0", 9191));
        assertThat(read("server:\n  tls: { certificate: a.crt, key: a.key }\n", Map.of()).config().orElseThrow().tls().orElseThrow().keyPassword()).isEmpty();
    }

    @Test
    void aLiteralKeyPasswordAndMissingFilesAreRefused() {
        ConfigResult literal = read("server:\n  tls: { certificate: a.crt, key: a.key, keyPassword: " + PASSWORD + " }\n", Map.of());
        assertThat(codes(literal.diagnostics())).isEqualTo("GWY015");
        assertThat(literal.diagnostics().get(0).message()).isEqualTo(
                "server.tls.keyPassword must be a single ${NAME} placeholder for an environment variable; a value written in the configuration file is not accepted");
        assertThat(text(literal.diagnostics())).doesNotContain(PASSWORD);
        assertThat(codes(read("server:\n  tls: { key: a.key }\n", Map.of()).diagnostics())).isEqualTo("GWY005");
        assertThat(codes(read("server:\n  tls: { certificate: a.crt }\n", Map.of()).diagnostics())).isEqualTo("GWY005");
        assertThat(codes(read("server:\n  tls: { certificate: a.crt, key: a.key, keyPassword: \"${UNSET}\" }\n", Map.of()).diagnostics())).isEqualTo("GWY007");
        assertThat(codes(read("server:\n  tls: { certificate: a.crt, key: a.key, extra: 1 }\n", Map.of()).diagnostics())).isEqualTo("GWY002");
        assertThat(codes(read("server:\n  tls: yes\n", Map.of()).diagnostics())).isEqualTo("GWY003");
    }

    @Test
    void theOldNameHostIsRefusedWithTheNewName() {
        ConfigResult result = read("server:\n  host: 0.0.0.0\n", Map.of());
        assertThat(codes(result.diagnostics())).isEqualTo("GWY002");
        assertThat(result.diagnostics().get(0).message()).isEqualTo("Unknown key host: the address of the listener is server.address");
    }

    @Test
    void theManagementPortIsZeroToOff() {
        assertThat(read("management:\n  port: 0\n", Map.of()).config().orElseThrow().management().enabled()).isFalse();
        assertThat(read("management:\n  port: 65535\n", Map.of()).config().orElseThrow().management().port()).isEqualTo(65535);
        assertThat(codes(read("management:\n  port: 65536\n", Map.of()).diagnostics())).isEqualTo("GWY006");
        assertThat(codes(read("management:\n  port: -1\n", Map.of()).diagnostics())).isEqualTo("GWY006");
        assertThat(codes(read("management:\n  address: \"a b\"\n", Map.of()).diagnostics())).isEqualTo("GWY006");
        assertThat(codes(read("management:\n  colour: red\n", Map.of()).diagnostics())).isEqualTo("GWY002");
        assertThat(read("management:\n  address: 10.0.0.2\n", Map.of()).config().orElseThrow().management()).isEqualTo(new ManagementConfig("10.0.0.2", 9090));
    }

    // ---- the start

    private StartResult start(String server, Map<String, String> environment) {
        Fixtures.bundle(dir, "shop");
        String yaml = server + "tenants:\n  acme:\n    bundles: [./shop-1.0.0.rpgapi]\n    environments:\n      test: { code: T0001, backend: mock }\n";
        return GatewayLoader.load(Fixtures.write(dir, "gateway.yaml", yaml), environment::get, line -> { });
    }

    @Test
    void theStartLoadsThePairOrStopsWithItsDiagnostic() {
        TestCertificates.Pair pair = TestCertificates.rsa(dir, "tls", 365);
        StartResult ok = start("server:\n  tls: { certificate: tls.crt, key: tls.key }\n", Map.of());
        assertThat(ok.diagnostics()).isEmpty();
        assertThat(ok.started().orElseThrow().tls().orElseThrow().current().notAfter()).isEqualTo(pair.certificate().getNotAfter().toInstant());
        ok.started().get().close();

        TestCertificates.Pair other = TestCertificates.rsa(dir, "other", 365);
        StartResult mismatch = start("server:\n  tls: { certificate: tls.crt, key: other.key }\n", Map.of());
        assertThat(mismatch.started()).isEmpty();
        assertThat(codes(mismatch.diagnostics())).isEqualTo("GWY023");
        assertThat(text(mismatch.diagnostics())).doesNotContain(other.keyText().substring(0, 40));

        TestCertificates.rsa(dir, "soon", 10);
        StartResult warned = start("server:\n  tls: { certificate: soon.crt, key: soon.key }\n", Map.of());
        assertThat(warned.started()).isPresent();
        assertThat(codes(warned.diagnostics())).isEqualTo("GWY025");
        warned.started().get().close();

        assertThat(start("", Map.of()).started().orElseThrow().tls()).isEmpty();
    }
}
