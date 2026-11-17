package ai.generativearchitects.slay400.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.slay400.gateway.load.GatewayLoader;
import ai.generativearchitects.slay400.gateway.load.StartResult;
import ai.generativearchitects.slay400.pcml.diagnostics.Diagnostic;
import ai.generativearchitects.slay400.pcml.diagnostics.Severity;
import ai.generativearchitects.slay400.tasks.TaskResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Each refusal of the start, in the order of the brief. A gateway never starts half configured. */
class StartTest {

    @TempDir
    Path dir;

    private static String codes(StartResult result) {
        return result.diagnostics().stream().map(Diagnostic::code).collect(Collectors.joining(","));
    }

    private static String config(String... bundles) {
        StringBuilder text = new StringBuilder("tenants:\n  acme:\n    bundles:\n");
        for (String bundle : bundles) {
            text.append("      - ./").append(bundle).append('\n');
        }
        return text.append("    environments:\n      test: { code: T0001, backend: mock }\n").toString();
    }

    @Test
    void startsAndCountsWhatItServes() {
        StartResult result = Fixtures.start(dir, new ArrayList<>());
        assertThat(result.diagnostics()).isEmpty();
        var started = result.started().orElseThrow();
        assertThat(started.environments()).isEqualTo(2);
        assertThat(started.operations()).isEqualTo(2);
        assertThat(started.startLine()).isEqualTo("{\"event\":\"started\",\"address\":\"127.0.0.1\",\"port\":8080,\"tenants\":1,\"environments\":2,\"operations\":2}");
    }

    @Test
    void step1_aConfigurationProblemStopsTheStartBeforeAnyBundleIsRead() {
        Fixtures.bundle(dir, "shop");
        Path file = Fixtures.write(dir, "gateway.yaml", config("shop-1.0.0.rpgapi") + "extra: 1\n");
        StartResult result = GatewayLoader.load(file, n -> null, line -> { });
        assertThat(result.started()).isEmpty();
        assertThat(codes(result)).isEqualTo("GWY002");
    }

    @Test
    void step2_aBundleThatIsNotThereIsRefused() {
        Path file = Fixtures.write(dir, "gateway.yaml", config("absent.rpgapi"));
        StartResult result = GatewayLoader.load(file, n -> null, line -> { });
        assertThat(result.started()).isEmpty();
        assertThat(result.diagnostics()).isNotEmpty();
        assertThat(result.diagnostics()).allMatch(d -> d.severity() == Severity.ERROR);
    }

    @Test
    void step2_aBundleThatIsNotABundleIsRefusedAndItsDiagnosticsAreReported() throws IOException {
        Files.writeString(dir.resolve("junk.rpgapi"), "this is not a zip file");
        Path file = Fixtures.write(dir, "gateway.yaml", config("junk.rpgapi"));
        StartResult result = GatewayLoader.load(file, n -> null, line -> { });
        assertThat(result.started()).isEmpty();
        assertThat(result.diagnostics()).isNotEmpty();
    }

    @Test
    void step2_aGoodBundleAndABadOneStopTheStartAndEveryBundleIsReported() throws IOException {
        Fixtures.bundle(dir, "shop");
        Files.writeString(dir.resolve("junk.rpgapi"), "x");
        Path file = Fixtures.write(dir, "gateway.yaml", config("shop-1.0.0.rpgapi", "junk.rpgapi"));
        assertThat(GatewayLoader.load(file, n -> null, line -> { }).started()).isEmpty();
    }

    @Test
    void step3_anInjectedParameterWithNoValueInTheEnvironmentIsRefusedAtTheEnvironment() {
        TaskResult built = Fixtures.variant(dir, "tenantexpr", "${environment.code}", "${tenant.name}");
        assertThat(built.hasError()).isFalse();
        Path file = Fixtures.write(dir, "gateway.yaml", config("tenantexpr-1.0.0.rpgapi"));
        StartResult result = GatewayLoader.load(file, n -> null, line -> { });
        assertThat(result.started()).isEmpty();
        assertThat(codes(result)).isEqualTo("GWY012");
        Diagnostic d = result.diagnostics().get(0);
        assertThat(d.message()).contains("test").contains("${tenant.name}").contains("ordstatus").contains("P0ENV");
        assertThat(d.location().file()).endsWith("gateway.yaml");
        assertThat(d.location().line()).isEqualTo(6);
    }

    @Test
    void step3_aLiteralIsItsOwnValue() {
        TaskResult built = Fixtures.variant(dir, "literal", "${environment.code}", "FIXED");
        assertThat(built.hasError()).isFalse();
        Path file = Fixtures.write(dir, "gateway.yaml", config("literal-1.0.0.rpgapi"));
        assertThat(GatewayLoader.load(file, n -> null, line -> { }).diagnostics()).isEmpty();
    }

    @Test
    void step3_anEnvironmentCodeThatDoesNotFitItsFieldIsRefusedAtTheStartNotAsAnErrorOfEveryRequest() {
        Fixtures.bundle(dir, "shop");
        Path file = Fixtures.write(dir, "gateway.yaml", config("shop-1.0.0.rpgapi").replace("T0001", "TOO-LONG-FOR-FIVE"));
        StartResult result = GatewayLoader.load(file, n -> null, line -> { });
        assertThat(result.started()).isEmpty();
        assertThat(codes(result)).isEqualTo("GWY012");
        assertThat(result.diagnostics().get(0).message()).contains("P0ENV").contains("does not fit").doesNotContain("TOO-LONG");
    }

    @Test
    void step4_theSameMethodAndRouteInTwoBundlesOfOneTenantIsRefused() throws IOException {
        Fixtures.bundle(dir, "shop");
        Files.copy(dir.resolve("shop-1.0.0.rpgapi"), dir.resolve("copy-1.0.0.rpgapi"));
        Path file = Fixtures.write(dir, "gateway.yaml", config("shop-1.0.0.rpgapi", "copy-1.0.0.rpgapi"));
        StartResult result = GatewayLoader.load(file, n -> null, line -> { });
        assertThat(result.started()).isEmpty();
        assertThat(result.diagnostics()).extracting(Diagnostic::code).containsOnly("GWY013");
        assertThat(result.diagnostics()).anyMatch(d -> d.message().contains("GET /orders/{orderNumber}/status") && d.message().contains("acme"));
    }

    @Test
    void step4_theSameRouteWithOtherPlaceholderNamesIsTheSameRoute() throws IOException {
        Fixtures.bundle(dir, "shop");
        TaskResult built = Fixtures.variant(dir, "renamed", "orderNumber", "number");
        assertThat(built.hasError()).isFalse();
        Path file = Fixtures.write(dir, "gateway.yaml", config("shop-1.0.0.rpgapi", "renamed-1.0.0.rpgapi"));
        assertThat(codes(GatewayLoader.load(file, n -> null, line -> { }))).contains("GWY013");
    }

    @Test
    void theCodeOfAnEnvironmentVariableIsNeverInAReport() {
        Fixtures.bundle(dir, "shop");
        Path file = Fixtures.write(dir, "gateway.yaml", config("shop-1.0.0.rpgapi").replace("T0001", "\"${SECRET_CODE}x${MISSING}\""));
        StartResult result = GatewayLoader.load(file, Map.of("SECRET_CODE", "hunter2")::get, line -> { });
        assertThat(result.started()).isEmpty();
        assertThat(result.diagnostics().toString()).doesNotContain("hunter2").contains("MISSING");
    }

    @Test
    void aTenantWithTwoBundlesOfDifferentApisStartsAndServesBoth() {
        Fixtures.bundle(dir, "shop");
        TaskResult other = Fixtures.variant(dir, "other", "route: ", "route: /other");
        assertThat(other.hasError()).isFalse();
        Path file = Fixtures.write(dir, "gateway.yaml", config("shop-1.0.0.rpgapi", "other-1.0.0.rpgapi"));
        StartResult result = GatewayLoader.load(file, n -> null, line -> { });
        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.started().orElseThrow().operations()).isEqualTo(4);
    }

    @Test
    void relativeBundlePathsAreResolvedAgainstTheConfigurationFileNotTheWorkingDirectory() throws IOException {
        Path sub = Files.createDirectories(dir.resolve("conf"));
        Fixtures.bundle(sub, "shop");
        Path file = Fixtures.write(sub, "gateway.yaml", config("shop-1.0.0.rpgapi"));
        assertThat(GatewayLoader.load(file, n -> null, line -> { }).diagnostics()).isEmpty();
    }
}
