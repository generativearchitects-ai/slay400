package ai.generativearchitects.slay400.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.slay400.gateway.load.GatewayLoader;
import ai.generativearchitects.slay400.gateway.load.StartResult;
import ai.generativearchitects.slay400.gateway.pipeline.Gateway;
import ai.generativearchitects.slay400.gateway.pipeline.RuntimeRequest;
import ai.generativearchitects.slay400.gateway.pipeline.RuntimeResponse;
import ai.generativearchitects.slay400.tasks.BundleRequest;
import ai.generativearchitects.slay400.tasks.TaskResult;
import ai.generativearchitects.slay400.tasks.Tasks;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The limit of a bundle is the limit of its calls (kickoff 06): the gateway builds each codec with the bundle's
 * {@code defaults.maxResponseValues}, not the default of {@code slay400-core}. With the mock strategy only. The repositories
 * are written by the test, with invented names, and built into bundles here; nothing is committed.
 */
class ResponseLimitTest {

    @TempDir
    Path dir;

    static final String PCML = """
            <pcml version="7.0">
              <program name="LIMITS" path="/QSYS.LIB/OWNERLIB.LIB/LIMITS.PGM">
                <data name="ROWS" type="char" length="1" count="%d" usage="output"/>
              </program>
            </pcml>
            """;

    static final String OVERLAY = """
            program: LIMITS
            pcml: LIMITS.pcml
            route: /rows
            method: GET
            version: 1.0.0
            parameters:
              ROWS:
                name: rows
                trim: { dropBlank: true }
            """;

    /** Builds a repository whose program returns {@code returned} values of an array of {@code declared}, with a limit or the default. */
    private Gateway start(String name, int declared, int returned, Optional<Integer> limit, List<String> access) {
        Path repository = dir.resolve(name);
        Fixtures.write(repository, "apis/LIMITS/LIMITS.pcml", PCML.formatted(declared));
        Fixtures.write(repository, "apis/LIMITS/api.yaml", OVERLAY);
        // The flow form keeps a mock of 100,001 values under the core's limit of 1 MiB for a mock file.
        StringBuilder mock = new StringBuilder("cases:\n  - name: filled\n    returns:\n      ROWS: [");
        for (int i = 0; i < returned; i++) {
            mock.append(i == 0 ? "X" : ",X");
        }
        mock.append("]\n");
        Fixtures.write(repository, "apis/LIMITS/mock/limits.yaml", mock.toString());
        limit.ifPresent(value -> Fixtures.write(repository, "gateway.yaml", "defaults:\n  maxResponseValues: " + value + "\n"));
        Path bundle = dir.resolve(name + "-1.0.0.rpgapi");
        TaskResult built = new Tasks(dir).bundle(repository, new BundleRequest(name, "1.0.0", bundle, Optional.empty(), Optional.empty()));
        assertThat(built.hasError()).as(built.diagnostics().toString()).isFalse();
        Path config = Fixtures.write(dir, name + ".yaml", "tenants:\n  acme:\n    bundles: [./" + name + "-1.0.0.rpgapi]\n    environments:\n      test: { code: T1, backend: mock }\n");
        StartResult result = GatewayLoader.load(config, n -> null, access::add);
        assertThat(result.started()).as(result.diagnostics().toString()).isPresent();
        return result.started().get().gateway();
    }

    static RuntimeResponse get(Gateway gateway) {
        return gateway.handle(new RuntimeRequest("GET", "/acme/test/rows", Map.of(), Map.of(), Optional.empty(), () -> new byte[0]));
    }

    @Test
    void aBundleWithALowLimitRefusesAResponseTheDefaultLetsThrough() {
        List<String> access = new ArrayList<>();
        Gateway low = start("low", 10, 5, Optional.of(3), access);
        try (LogCapture log = new LogCapture("slay400.gateway")) {
            RuntimeResponse response = get(low);
            assertThat(response.status()).isEqualTo(502);
            assertThat(new String(response.body(), StandardCharsets.UTF_8)).doesNotContain("X\"");
            assertThat(log.lines()).singleElement().satisfies(line -> {
                assertThat(line.level()).isEqualTo(Level.WARNING);
                assertThat(line.message()).startsWith("The response of the operation limits has more values than the limit of 3 of its bundle, tenant acme, environment test");
            });
        }
        assertThat(access.get(0)).contains("\"status\":502").contains("VAL104");
        Gateway byDefault = start("default", 10, 5, Optional.empty(), new ArrayList<>());
        RuntimeResponse response = get(byDefault);
        assertThat(response.status()).isEqualTo(200);
        assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo("{\"rows\":[\"X\",\"X\",\"X\",\"X\",\"X\"]}");
    }

    @Test
    void aBundleWithAHighLimitLetsThroughAResponseTheDefaultRefuses() {
        int over = ai.generativearchitects.slay400.pcml.api.GatewayDefaults.DEFAULT_MAX_RESPONSE_VALUES + 1;
        Gateway high = start("high", over + 10, over, Optional.of(200_000), new ArrayList<>());
        RuntimeResponse response = get(high);
        assertThat(response.status()).isEqualTo(200);
        assertThat(new String(response.body(), StandardCharsets.UTF_8)).startsWith("{\"rows\":[\"X\",").hasSize("{\"rows\":[]}".length() + over * 4 - 1);
        List<String> access = new ArrayList<>();
        Gateway byDefault = start("default-high", over + 10, over, Optional.empty(), access);
        assertThat(get(byDefault).status()).isEqualTo(502);
        assertThat(access.get(0)).contains("VAL104");
    }

    @Test
    void aCompiledOperationNeedsAPositiveLimit() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new ai.generativearchitects.slay400.gateway.pipeline.CompiledOperation(null, null, null, 0))
                .isInstanceOf(NullPointerException.class);
    }
}
