package ai.generativearchitects.slay400.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.slay400.gateway.config.Backend;
import ai.generativearchitects.slay400.gateway.config.ConfigReader;
import ai.generativearchitects.slay400.gateway.config.ConfigResult;
import ai.generativearchitects.slay400.gateway.config.GatewayConfig;
import ai.generativearchitects.slay400.pcml.diagnostics.Diagnostic;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ConfigReaderTest {

    @TempDir
    Path dir;

    private ConfigResult read(String yaml) {
        return read(yaml, Map.of());
    }

    private ConfigResult read(String yaml, Map<String, String> environment) {
        return ConfigReader.read(Fixtures.write(dir, "gateway.yaml", yaml), environment::get);
    }

    private static String minimal(String extra) {
        return extra + """
                tenants:
                  acme:
                    bundles: [./a.rpgapi]
                    environments:
                      test: { code: T0001, backend: mock }
                """;
    }

    private static String codes(ConfigResult result) {
        return result.diagnostics().stream().map(Diagnostic::code).collect(Collectors.joining(","));
    }

    @Test
    void readsAValidFileWithDefaults() {
        GatewayConfig config = read(minimal("")).config().orElseThrow();
        assertThat(config.host()).isEqualTo("127.0.0.1");
        assertThat(config.port()).isEqualTo(8080);
        assertThat(config.tenants()).hasSize(1);
        var tenant = config.tenants().get(0);
        assertThat(tenant.name()).isEqualTo("acme");
        assertThat(tenant.bundles()).containsExactly(dir.resolve("a.rpgapi").toAbsolutePath().normalize());
        assertThat(tenant.environments().get(0).code()).isEqualTo("T0001");
        assertThat(tenant.environments().get(0).backend()).isEqualTo(Backend.MOCK);
        assertThat(config.file()).isEqualTo(dir.resolve("gateway.yaml"));
    }

    @ParameterizedTest
    @CsvSource({"1", "65535", "8080"})
    void thePortBoundariesAreAccepted(int port) {
        assertThat(read(minimal("server:\n  port: " + port + "\n")).config().orElseThrow().port()).isEqualTo(port);
    }

    @Test
    void nestingUpToTheLimitIsAcceptedAndOneMoreIsNot() {
        String ok = "a: " + "[".repeat(15) + "]".repeat(15) + "\n";
        assertThat(codes(read(ok))).doesNotContain("GWY001");
        String tooDeep = "a: " + "[".repeat(17) + "]".repeat(17) + "\n";
        assertThat(codes(read(tooDeep))).contains("GWY001");
    }

    @Test
    void readsServerSettings() {
        GatewayConfig config = read(minimal("server:\n  host: 0.0.0.0\n  port: 9090\n")).config().orElseThrow();
        assertThat(config.host()).isEqualTo("0.0.0.0");
        assertThat(config.port()).isEqualTo(9090);
    }

    @Test
    void replacesPlaceholdersInAnyScalar() {
        ConfigResult result = read("""
                server: { host: "${H}", port: "${P}" }
                tenants:
                  acme:
                    bundles: ["./${B}.rpgapi"]
                    environments:
                      test: { code: "${C}-1", backend: mock }
                """, Map.of("H", "10.0.0.1", "P", "8443", "B", "orders", "C", "T9"));
        GatewayConfig config = result.config().orElseThrow();
        assertThat(config.host()).isEqualTo("10.0.0.1");
        assertThat(config.port()).isEqualTo(8443);
        assertThat(config.tenants().get(0).bundles().get(0).getFileName().toString()).isEqualTo("orders.rpgapi");
        assertThat(config.tenants().get(0).environments().get(0).code()).isEqualTo("T9-1");
    }

    @Test
    void anUnsetVariableStopsTheStartNamingTheVariableAndThePlaceNeverAValue() {
        ConfigResult result = read(minimal("server:\n  host: \"${NOPE}\"\n"), Map.of("OTHER", "secret-value"));
        assertThat(result.config()).isEmpty();
        Diagnostic d = result.diagnostics().get(0);
        assertThat(d.code()).isEqualTo("GWY007");
        assertThat(d.message()).contains("NOPE").doesNotContain("secret-value");
        assertThat(d.location().line()).isEqualTo(2);
        assertThat(d.location().column()).isGreaterThan(1);
    }

    @Test
    void aValueIsNotRescannedForPlaceholders() {
        ConfigResult result = read(minimal("server:\n  host: \"${A}\"\n"), Map.of("A", "${B}"));
        assertThat(result.config().orElseThrow().host()).isEqualTo("${B}");
    }

    @Test
    void aMalformedPlaceholderIsRefused() {
        assertThat(codes(read(minimal("server:\n  host: \"a${1}b\"\n")))).isEqualTo("GWY006");
        assertThat(codes(read(minimal("server:\n  host: \"a${b\"\n")))).isEqualTo("GWY006");
    }

    @Test
    void aVariableThatIsSetButEmptyGivesAnEmptyValueError() {
        assertThat(codes(read("""
                tenants:
                  acme:
                    bundles: [./a.rpgapi]
                    environments:
                      test: { code: "${E}", backend: mock }
                """, Map.of("E", "")))).isEqualTo("GWY004");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            extra: 1\\ntenants:\\n  a:\\n    bundles: [x]\\n    environments:\\n      t: { code: C, backend: mock }|GWY002|1
            tenants:\\n  a:\\n    bundles: [x]\\n    colour: red\\n    environments:\\n      t: { code: C, backend: mock }|GWY002|4
            tenants:\\n  a:\\n    bundles: [x]\\n    environments:\\n      t: { code: C, backend: mock, host: h }|GWY002|5
            server:\\n  tls: true\\ntenants:\\n  a:\\n    bundles: [x]\\n    environments:\\n      t: { code: C, backend: mock }|GWY002|2
            server:\\n  port: abc\\ntenants:\\n  a:\\n    bundles: [x]\\n    environments:\\n      t: { code: C, backend: mock }|GWY006|2
            server:\\n  port: 0\\ntenants:\\n  a:\\n    bundles: [x]\\n    environments:\\n      t: { code: C, backend: mock }|GWY006|2
            server:\\n  port: 65536\\ntenants:\\n  a:\\n    bundles: [x]\\n    environments:\\n      t: { code: C, backend: mock }|GWY006|2
            server:\\n  port: [1]\\ntenants:\\n  a:\\n    bundles: [x]\\n    environments:\\n      t: { code: C, backend: mock }|GWY003|2
            server:\\n  host: "a b"\\ntenants:\\n  a:\\n    bundles: [x]\\n    environments:\\n      t: { code: C, backend: mock }|GWY006|2
            server: {}\\ntenants:\\n  a:\\n    bundles: [x]\\n    environments:\\n      t: { code: C, backend: mock }|GWY004|1
            server:\\ntenants:\\n  a:\\n    bundles: [x]\\n    environments:\\n      t: { code: C, backend: mock }|GWY004|1
            tenants: {}|GWY004|1
            tenants:|GWY004|1
            tenants: [a]|GWY003|1
            server: {}|GWY005|1
            tenants:\\n  a:\\n    environments:\\n      t: { code: C, backend: mock }|GWY005|2
            tenants:\\n  a:\\n    bundles: [x]|GWY005|2
            tenants:\\n  a:\\n    bundles: []\\n    environments:\\n      t: { code: C, backend: mock }|GWY004|3
            tenants:\\n  a:\\n    bundles: x\\n    environments:\\n      t: { code: C, backend: mock }|GWY003|3
            tenants:\\n  a:\\n    bundles: [[x]]\\n    environments:\\n      t: { code: C, backend: mock }|GWY003|3
            tenants:\\n  a:\\n    bundles: [x]\\n    environments: {}|GWY004|4
            tenants:\\n  a:\\n    bundles: [x]\\n    environments:\\n      t: {}|GWY004|5
            tenants:\\n  a:\\n    bundles: [x]\\n    environments:\\n      t: { backend: mock }|GWY005|5
            tenants:\\n  a:\\n    bundles: [x]\\n    environments:\\n      t: { code: C }|GWY005|5
            tenants:\\n  a:\\n    bundles: [x]\\n    environments:\\n      t: { code: "", backend: mock }|GWY004|5
            tenants:\\n  a:\\n    bundles: [x]\\n    environments:\\n      t: { code: [C], backend: mock }|GWY003|5
            tenants:\\n  a:\\n    bundles: [x]\\n    environments:\\n      t: { code: C, backend: mocked }|GWY006|5
            tenants:\\n  A:\\n    bundles: [x]\\n    environments:\\n      t: { code: C, backend: mock }|GWY006|2
            tenants:\\n  -a:\\n    bundles: [x]\\n    environments:\\n      t: { code: C, backend: mock }|GWY006|2
            tenants:\\n  health:\\n    bundles: [x]\\n    environments:\\n      t: { code: C, backend: mock }|GWY006|2
            tenants:\\n  a:\\n    bundles: [x]\\n    environments:\\n      Test: { code: C, backend: mock }|GWY006|5
            tenants:\\n  a:\\n    bundles: [x]\\n    environments:\\n      health: { code: C, backend: mock }|GWY006|5
            """)
    void refusesWithTheFilePosition(String yamlWithEscapes, String code, int line) {
        ConfigResult result = read(yamlWithEscapes.replace("\\n", "\n"));
        assertThat(result.config()).isEmpty();
        assertThat(result.diagnostics()).extracting(Diagnostic::code).contains(code);
        Diagnostic d = result.diagnostics().stream().filter(x -> x.code().equals(code)).findFirst().orElseThrow();
        assertThat(d.location().line()).isEqualTo(line);
        assertThat(d.location().file()).isEqualTo(dir.resolve("gateway.yaml").toString());
    }

    @Test
    void moreThanOneTenantIsAnEditionLimitAtTheSecondTenant() {
        ConfigResult result = read("""
                tenants:
                  one:
                    bundles: [./a.rpgapi]
                    environments:
                      test: { code: T1, backend: mock }
                  two:
                    bundles: [./a.rpgapi]
                    environments:
                      test: { code: T2, backend: mock }
                """);
        assertThat(result.config()).isEmpty();
        assertThat(result.diagnostics()).hasSize(1);
        assertThat(result.diagnostics().get(0).code()).isEqualTo("GWY008");
        assertThat(result.diagnostics().get(0).location().line()).isEqualTo(6);
        assertThat(result.diagnostics().get(0).message()).contains("1 tenant").contains("has 2");
    }

    @Test
    void theEditionLimitIsNotChecked_whenTheFileHasOtherErrors() {
        ConfigResult result = read("""
                tenants:
                  one:
                    bundles: [./a.rpgapi]
                  two:
                    bundles: [./a.rpgapi]
                """);
        assertThat(codes(result)).doesNotContain("GWY008");
    }

    @Test
    void reportsEveryProblemNotOnlyTheFirst() {
        ConfigResult result = read("server:\n  port: x\n  tls: 1\ntenants: {}\n");
        assertThat(codes(result)).isEqualTo("GWY002,GWY006,GWY004");
    }

    @Test
    void aFileThatIsNotYamlIsRefusedWithAPosition() {
        ConfigResult result = read("tenants: [\n  a: {\n");
        assertThat(result.diagnostics()).hasSize(1);
        assertThat(result.diagnostics().get(0).code()).isEqualTo("GWY001");
        assertThat(result.diagnostics().get(0).location().line()).isGreaterThan(0);
    }

    @Test
    void duplicateKeysAndEmptyFilesAndCustomTagsAreRefused() {
        assertThat(codes(read("tenants: {}\ntenants: {}\n"))).contains("GWY009");
        assertThat(codes(read(""))).isEqualTo("GWY004");
        assertThat(codes(read("# nothing\n"))).isEqualTo("GWY004");
        assertThat(codes(read("- a\n"))).isEqualTo("GWY003");
        assertThat(codes(read("tenants: !!java.lang.Object {}\n"))).isEqualTo("GWY001");
    }

    @Test
    void aKeyThatIsNotAScalarIsRefused() {
        assertThat(read("? [a]\n: b\n").config()).isEmpty();
    }

    @Test
    void aMissingFileAndAFileThatIsNotUtf8AndAFileThatIsTooLargeAreRefused() throws IOException {
        assertThat(codes(ConfigReader.read(dir.resolve("absent.yaml"), n -> null))).isEqualTo("GWY001");
        Path bad = dir.resolve("bad.yaml");
        Files.write(bad, new byte[] {(byte) 0xff, (byte) 0xfe, 'a'});
        assertThat(codes(ConfigReader.read(bad, n -> null))).isEqualTo("GWY001");
        Path large = dir.resolve("large.yaml");
        Files.write(large, new byte[ConfigReader.MAX_BYTES + 1]);
        ConfigResult result = ConfigReader.read(large, n -> null);
        assertThat(result.diagnostics().get(0).message()).contains("larger than");
        Files.write(large, "#".repeat(ConfigReader.MAX_BYTES - 1).concat("\n").getBytes());
        assertThat(codes(ConfigReader.read(large, n -> null))).isEqualTo("GWY004");
    }

    @Test
    void deepNestingIsRefused() {
        String deep = "a: ".repeat(1) + "[".repeat(40) + "]".repeat(40) + "\n";
        assertThat(codes(read(deep))).isEqualTo("GWY001");
    }

    @Test
    void controlCharactersInANameDoNotReachTheMessage() {
        ConfigResult result = read(minimal("").replace("acme:", "\"a\\u0007b\":"));
        assertThat(result.diagnostics()).isNotEmpty();
        assertThat(result.diagnostics().get(0).message()).doesNotContain("\u0007");
        ConfigResult unknown = read("\"x\\u0007y\": 1\n");
        assertThat(unknown.diagnostics().get(0).message()).isEqualTo("Unknown key x?y");
    }

    // ---- reload (kickoff 07)

    @Test
    void theReloadIntervalIsTenSecondsByDefault() {
        assertThat(read(minimal("")).config().orElseThrow().reloadIntervalSeconds()).isEqualTo(10);
    }

    @ParameterizedTest
    @CsvSource({"0", "1", "3600"})
    void theReloadIntervalBoundariesAreAccepted(int seconds) {
        assertThat(read(minimal("reload:\n  intervalSeconds: " + seconds + "\n")).config().orElseThrow().reloadIntervalSeconds()).isEqualTo(seconds);
    }

    @ParameterizedTest
    @CsvSource(value = {"-1|GWY006", "3601|GWY006", "ten|GWY006", "1.5|GWY006", "[1]|GWY003", "{ a: 1 }|GWY003", "\"\"|GWY004"}, delimiter = '|')
    void aReloadIntervalOutsideTheLimitsOrOfTheWrongTypeIsRefused(String value, String code) {
        ConfigResult result = read(minimal("reload:\n  intervalSeconds: " + value + "\n"));
        assertThat(result.config()).isEmpty();
        assertThat(codes(result)).isEqualTo(code);
    }

    @Test
    void theReloadSectionHasOneKey() {
        assertThat(codes(read(minimal("reload:\n  every: 5\n")))).isEqualTo("GWY002");
        assertThat(codes(read(minimal("reload: 5\n")))).isEqualTo("GWY003");
        assertThat(codes(read(minimal("reload:\n")))).isEqualTo("GWY004");
        assertThat(read(minimal("reload:\n  intervalSeconds: 6\n")).diagnostics()).isEmpty();
    }

    @Test
    void theRefusalOfABadIntervalSaysTheLimits() {
        assertThat(read(minimal("reload:\n  intervalSeconds: 3601\n")).diagnostics().get(0).message())
                .isEqualTo("reload.intervalSeconds must be a whole number from 0 to 3600");
    }
}
