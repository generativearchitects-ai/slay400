package ai.generativearchitects.slay400.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.generativearchitects.slay400.gateway.config.Backend;
import ai.generativearchitects.slay400.gateway.config.ConfigReader;
import ai.generativearchitects.slay400.gateway.config.ConfigResult;
import ai.generativearchitects.slay400.gateway.config.Credentials;
import ai.generativearchitects.slay400.gateway.config.EnvironmentConfig;
import ai.generativearchitects.slay400.gateway.config.IbmiSettings;
import ai.generativearchitects.slay400.pcml.diagnostics.Diagnostic;
import ai.generativearchitects.slay400.pcml.model.SourceLocation;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The configuration of an environment with {@code backend: ibmi}. Every value here is invented. */
class IbmiConfigTest {

    @TempDir
    Path dir;

    /** Markers: if one of them appears in a diagnostic or a {@code toString}, a secret leaked. */
    static final String PASSWORD = "PW-MARKER-7f3a";
    static final String USER = "USER-MARKER-91c";
    static final String HOST = "host-marker.invalid";

    static final Map<String, String> VARIABLES = Map.of("T_HOST", HOST, "T_USER", USER, "T_PASSWORD", PASSWORD, "T_LIB", "applib_t");

    static String config(String environment) {
        return """
                tenants:
                  acme:
                    bundles: [./a.rpgapi]
                    environments:
                      test:
                """ + environment.stripTrailing().indent(8);
    }

    static final String VALID = """
            code: T0001
            backend: ibmi
            host: ${T_HOST}
            libraryList: [APPLIB_T, "${T_LIB}"]
            credentials:
              user: ${T_USER}
              password: ${T_PASSWORD}
            """;

    private ConfigResult read(String environment) {
        return read(environment, VARIABLES);
    }

    private ConfigResult read(String environment, Map<String, String> variables) {
        return ConfigReader.read(Fixtures.write(dir, "gateway.yaml", config(environment)), variables::get);
    }

    private static String codes(ConfigResult result) {
        return result.diagnostics().stream().map(Diagnostic::code).collect(Collectors.joining(","));
    }

    private static String everything(ConfigResult result) {
        return result.diagnostics().stream().map(Diagnostic::toString).collect(Collectors.joining("\n")) + result.config().map(Object::toString).orElse("");
    }

    private IbmiSettings settings(String environment) {
        ConfigResult result = read(environment);
        assertThat(result.diagnostics()).isEmpty();
        EnvironmentConfig env = result.config().orElseThrow().tenants().get(0).environments().get(0);
        assertThat(env.backend()).isEqualTo(Backend.IBMI);
        return env.ibmi().orElseThrow();
    }

    @Test
    void theExampleOfTheBriefWithItsDefaults() {
        IbmiSettings settings = settings(VALID);
        assertThat(settings.host()).isEqualTo(HOST);
        assertThat(settings.libraryList()).containsExactly("APPLIB_T", "APPLIB_T");
        assertThat(settings.credentials().user()).isEqualTo(USER);
        assertThat(settings.credentials().password()).containsExactly(PASSWORD.toCharArray());
        assertThat(settings.tls()).isTrue();
        assertThat(settings.maxConnections()).isEqualTo(4);
        assertThat(settings.callTimeoutSeconds()).isEqualTo(30);
    }

    @Test
    void tlsPoolAndTimeOutCanBeSet() {
        IbmiSettings settings = settings(VALID + "tls: false\npool: { maxConnections: 64 }\ncallTimeoutSeconds: 3600\n");
        assertThat(settings.tls()).isFalse();
        assertThat(settings.maxConnections()).isEqualTo(64);
        assertThat(settings.callTimeoutSeconds()).isEqualTo(3600);
        IbmiSettings lowest = settings(VALID + "tls: true\npool: { maxConnections: 1 }\ncallTimeoutSeconds: 1\n");
        assertThat(lowest.tls()).isTrue();
        assertThat(lowest.maxConnections()).isEqualTo(1);
        assertThat(lowest.callTimeoutSeconds()).isEqualTo(1);
    }

    @Test
    void noToStringShowsTheHostTheLibrariesOrTheCredentials() {
        ConfigResult result = read(VALID);
        String shown = result.config().orElseThrow().toString();
        assertThat(shown).doesNotContain(HOST).doesNotContain("APPLIB_T").doesNotContain(USER).doesNotContain(PASSWORD).doesNotContain("Credentials")
                .contains("IbmiSettings[tls=true, maxConnections=4, callTimeoutSeconds=30, libraries=2]");
    }

    @ParameterizedTest
    @ValueSource(strings = {"user: someone", "user: \"${T_USER} \"", "user: x${T_USER}", "user: ${T_USER}${T_USER}", "user: [\"${T_USER}\"]", "user: ''"})
    void aUserThatIsNotOneSinglePlaceholderStopsTheStart(String user) {
        ConfigResult result = read(VALID.replace("user: ${T_USER}", user));
        assertThat(result.config()).isEmpty();
        assertThat(codes(result)).isEqualTo("GWY015");
        assertThat(result.diagnostics().get(0).message()).startsWith("credentials.user must be a single ${NAME} placeholder").doesNotContain("someone");
    }

    @ParameterizedTest
    @ValueSource(strings = {"hunter2-literal", "${T_PASSWORD}-literal", "\"\""})
    void aPasswordWrittenInTheFileStopsTheStartAndIsNeverShown(String password) {
        ConfigResult result = read(VALID.replace("password: ${T_PASSWORD}", "password: " + password));
        assertThat(result.config()).isEmpty();
        assertThat(codes(result)).isEqualTo("GWY015");
        assertThat(everything(result)).doesNotContain("hunter2").doesNotContain("literal").doesNotContain(PASSWORD);
    }

    @Test
    void anUnsetVariableNamesTheVariableOnly() {
        Map<String, String> variables = new HashMap<>(VARIABLES);
        variables.remove("T_PASSWORD");
        ConfigResult result = read(VALID, variables);
        assertThat(codes(result)).isEqualTo("GWY007");
        assertThat(result.diagnostics().get(0).message()).isEqualTo("The environment variable T_PASSWORD is not set");
        assertThat(everything(result)).doesNotContain(USER).doesNotContain(HOST);
    }

    @Test
    void hostLibraryListAndCredentialsAreRequired() {
        ConfigResult result = read("code: T0001\nbackend: ibmi\n");
        assertThat(codes(result)).isEqualTo("GWY005,GWY005,GWY005");
        assertThat(result.diagnostics()).extracting(Diagnostic::message).containsExactly("The environment test has no key host",
                "The environment test has no key libraryList", "The environment test has no key credentials");
        ConfigResult noPassword = read(VALID.replace("  password: ${T_PASSWORD}\n", ""));
        assertThat(noPassword.diagnostics()).extracting(Diagnostic::message).containsExactly("The section credentials has no key password");
        ConfigResult noUser = read(VALID.replace("  user: ${T_USER}\n", ""));
        assertThat(noUser.diagnostics()).extracting(Diagnostic::message).containsExactly("The section credentials has no key user");
        assertThat(codes(read(VALID.replace("credentials:\n  user: ${T_USER}\n  password: ${T_PASSWORD}\n", "credentials: ${T_USER}\n")))).isEqualTo("GWY003");
        assertThat(codes(read(VALID.replace("  password: ${T_PASSWORD}\n", "  password: ${T_PASSWORD}\n  token: x\n")))).isEqualTo("GWY002");
    }

    @ParameterizedTest
    @ValueSource(strings = {"a host", "http://h", "h/x", "h:8471/x", "h;x", "h\\\\x"})
    void aHostThatIsNotAHostNameIsRefusedWithoutBeingShown(String host) {
        ConfigResult result = read(VALID.replace("host: ${T_HOST}", "host: \"" + host + "\""));
        assertThat(codes(result)).isEqualTo("GWY006");
        assertThat(result.diagnostics().get(0).message()).isEqualTo("host must be a host name or an address, without white space, scheme, path or port");
    }

    @Test
    void aHostMayBeANameAnIPv4OrAnIPv6Address() {
        for (String host : List.of("system1.example", "10.0.0.1", "[fd00::1]", "fd00::1", "a".repeat(253))) {
            assertThat(settings(VALID.replace("host: ${T_HOST}", "host: \"" + host + "\"")).host()).isEqualTo(host);
        }
        assertThat(codes(read(VALID.replace("host: ${T_HOST}", "host: " + "a".repeat(254))))).isEqualTo("GWY006");
    }

    @ParameterizedTest
    @ValueSource(strings = {"1LIB", "_LIB", ".LIB", "ELEVENCHARS", "A B", "A;B", "A/B", "A*B", "\"*LIBL\"", "\"\""})
    void aLibraryNameThatIsNotAnIbmiNameIsRefused(String library) {
        ConfigResult result = read(VALID.replace("libraryList: [APPLIB_T, \"${T_LIB}\"]", "libraryList: [" + library + "]"));
        assertThat(codes(result)).as(library).isIn("GWY006", "GWY004");
        assertThat(everything(result)).doesNotContain("ELEVENCHARS");
    }

    @Test
    void libraryNamesOfEveryAllowedFormAreKeptInUpperCase() {
        assertThat(settings(VALID.replace("[APPLIB_T, \"${T_LIB}\"]", "[\"$A\", \"#B\", \"@C\", a1_.2, ABCDEFGHIJ]")).libraryList())
                .containsExactly("$A", "#B", "@C", "A1_.2", "ABCDEFGHIJ");
    }

    @Test
    void theLibraryListIsANonEmptyListOfAtMost250() {
        assertThat(codes(read(VALID.replace("[APPLIB_T, \"${T_LIB}\"]", "[]")))).isEqualTo("GWY004");
        assertThat(codes(read(VALID.replace("[APPLIB_T, \"${T_LIB}\"]", "APPLIB_T")))).isEqualTo("GWY003");
        String many = "[" + "L,".repeat(250) + "L]";
        assertThat(codes(read(VALID.replace("[APPLIB_T, \"${T_LIB}\"]", many)))).isEqualTo("GWY006");
        assertThat(settings(VALID.replace("[APPLIB_T, \"${T_LIB}\"]", "[" + "L,".repeat(249) + "L]")).libraryList()).hasSize(250);
    }

    @ParameterizedTest
    @ValueSource(strings = {"tls: yes", "tls: 1", "tls: TRUE", "pool: { maxConnections: 0 }", "pool: { maxConnections: 65 }", "pool: { maxConnections: x }",
            "pool: { maxConnections: -1 }", "callTimeoutSeconds: 0", "callTimeoutSeconds: 3601", "callTimeoutSeconds: 1.5", "callTimeoutSeconds: 9999999"})
    void tlsPoolAndTimeOutOutsideTheirRangeAreRefused(String line) {
        ConfigResult result = read(VALID + line + "\n");
        assertThat(codes(result)).as(line).isEqualTo("GWY006");
    }

    @Test
    void thePoolHasOneKey() {
        assertThat(codes(read(VALID + "pool: { maxConnections: 2, minConnections: 1 }\n"))).isEqualTo("GWY002");
        assertThat(codes(read(VALID + "pool: 4\n"))).isEqualTo("GWY003");
    }

    @ParameterizedTest
    @ValueSource(strings = {"host: h", "libraryList: [A]", "credentials: { user: \"${T_USER}\", password: \"${T_PASSWORD}\" }", "tls: true", "pool: { maxConnections: 1 }",
            "callTimeoutSeconds: 5"})
    void theKeysOfIbmiAreRefusedOnAMockEnvironment(String line) {
        ConfigResult result = read("code: T0001\nbackend: mock\n" + line + "\n");
        assertThat(codes(result)).isEqualTo("GWY002");
        assertThat(result.diagnostics().get(0).message()).endsWith("applies to the backend ibmi only");
    }

    @Test
    void anotherBackendIsRefused() {
        ConfigResult result = read("code: T0001\nbackend: odbc\n");
        assertThat(codes(result)).isEqualTo("GWY006");
        assertThat(result.diagnostics().get(0).message()).isEqualTo("The backend must be mock or ibmi");
    }

    @Test
    void credentialsShowNothingAndHandOutCopies() {
        char[] password = "s3cr3t".toCharArray();
        Credentials credentials = new Credentials("U", password);
        password[0] = 'X';
        assertThat(credentials.password()).containsExactly('s', '3', 'c', 'r', '3', 't');
        credentials.password()[0] = 'Y';
        assertThat(credentials.password()[0]).isEqualTo('s');
        assertThat(credentials).hasToString("Credentials[redacted]");
        credentials.clear();
        assertThat(credentials.password()).containsOnly('\0');
    }

    @Test
    void ibmiSettingsArePresentExactlyForTheIbmiBackend() {
        SourceLocation at = new SourceLocation("f", 1, 1);
        IbmiSettings settings = new IbmiSettings("h", List.of("L"), new Credentials("u", new char[] {'p'}), true, 1, 1);
        assertThatThrownBy(() -> new EnvironmentConfig("t", "c", Backend.MOCK, Optional.of(settings), at)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EnvironmentConfig("t", "c", Backend.IBMI, Optional.empty(), at)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new EnvironmentConfig("t", "c", Backend.IBMI, Optional.of(settings), at).ibmi()).contains(settings);
    }
}
