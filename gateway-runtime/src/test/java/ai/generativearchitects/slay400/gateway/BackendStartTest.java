package ai.generativearchitects.slay400.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.generativearchitects.slay400.gateway.call.BackendProvider;
import ai.generativearchitects.slay400.gateway.call.BackendStart;
import ai.generativearchitects.slay400.gateway.call.CallOutcome;
import ai.generativearchitects.slay400.gateway.call.ProgramCaller;
import ai.generativearchitects.slay400.gateway.config.Backend;
import ai.generativearchitects.slay400.gateway.config.EnvironmentConfig;
import ai.generativearchitects.slay400.gateway.load.GatewayLoader;
import ai.generativearchitects.slay400.gateway.load.StartOptions;
import ai.generativearchitects.slay400.gateway.load.StartResult;
import ai.generativearchitects.slay400.gateway.load.Started;
import ai.generativearchitects.slay400.gateway.pipeline.RuntimeRequest;
import ai.generativearchitects.slay400.pcml.api.ApiOperation;
import ai.generativearchitects.slay400.pcml.diagnostics.Diagnostic;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** How the start opens a backend other than the mock, with a fake in place of the ibmi backend. */
class BackendStartTest {

    @TempDir
    Path dir;

    static final Map<String, String> VARIABLES = Map.of("H", "h.invalid", "U", "SOMEONE", "P", "PW-MARKER");

    static String config(String... environments) {
        StringBuilder text = new StringBuilder("tenants:\n  acme:\n    bundles: [./shop-1.0.0.rpgapi]\n    environments:\n");
        for (String environment : environments) {
            text.append("      ").append(environment).append('\n');
        }
        return text.toString();
    }

    static String ibmi(String name) {
        return name + ": { code: T0001, backend: ibmi, host: \"${H}\", libraryList: [APPLIB], credentials: { user: \"${U}\", password: \"${P}\" } }";
    }

    /** A backend that records what the start asked of it. */
    static final class FakeBackend implements BackendProvider {
        final List<String> calls = new ArrayList<>();
        final List<FakeCaller> opened = new ArrayList<>();
        Function<ApiOperation, List<String>> unsupported = operation -> List.of();
        Function<String, List<String>> problems = environment -> List.of();

        @Override
        public Backend backend() {
            return Backend.IBMI;
        }

        @Override
        public List<String> unsupported(ApiOperation operation) {
            calls.add("unsupported " + operation.id());
            return unsupported.apply(operation);
        }

        @Override
        public BackendStart start(String tenant, EnvironmentConfig environment, List<ApiOperation> operations, boolean connectCheck) {
            calls.add("start " + tenant + "/" + environment.name() + " " + operations.size() + " " + connectCheck);
            List<String> found = problems.apply(environment.name());
            if (!found.isEmpty()) {
                return BackendStart.failed(found);
            }
            FakeCaller caller = new FakeCaller();
            opened.add(caller);
            return BackendStart.opened(caller);
        }
    }

    static final class FakeCaller implements ProgramCaller {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger closed = new AtomicInteger();

        @Override
        public CallOutcome call(ai.generativearchitects.slay400.gateway.call.CallContext context, ApiOperation operation,
                                List<ai.generativearchitects.slay400.pcml.codec.WireValue> input) {
            calls.incrementAndGet();
            return new CallOutcome.Unavailable();
        }

        @Override
        public void close() {
            closed.incrementAndGet();
        }
    }

    private StartResult start(String config, StartOptions options) {
        Fixtures.bundle(dir, "shop");
        return GatewayLoader.load(Fixtures.write(dir, "gateway.yaml", config), VARIABLES::get, line -> { }, options);
    }

    private static String codes(StartResult result) {
        return result.diagnostics().stream().map(Diagnostic::code).collect(Collectors.joining(","));
    }

    @Test
    void anIbmiEnvironmentWithoutTheBackendInTheDistributionStopsTheStart() {
        StartResult result = start(config(ibmi("test")), StartOptions.mockOnly());
        assertThat(result.started()).isEmpty();
        assertThat(codes(result)).isEqualTo("GWY016");
        assertThat(result.diagnostics().get(0).message()).isEqualTo("The backend ibmi of the environment test is not available in this distribution");
    }

    @Test
    void anOperationTheBackendCannotCallStopsTheStartBeforeAnythingIsOpened() {
        FakeBackend backend = new FakeBackend();
        backend.unsupported = operation -> operation.id().equals("itemqty") ? List.of("first reason", "second reason") : List.of();
        StartResult result = start(config(ibmi("test"), ibmi("prod")), new StartOptions(List.of(backend), true));
        assertThat(result.started()).isEmpty();
        assertThat(codes(result)).isEqualTo("GWY017,GWY017");
        assertThat(result.diagnostics().get(0).message()).isEqualTo("The operation itemqty cannot be called by the backend ibmi: first reason");
        // Asked once per operation for the tenant, not once per environment; nothing started.
        assertThat(backend.calls).containsExactlyInAnyOrder("unsupported ordstatus", "unsupported itemqty");
    }

    @Test
    void anotherErrorOfTheStartMeansNoBackendIsOpened() {
        FakeBackend backend = new FakeBackend();
        StartResult result = start(config(ibmi("test"), "prod: { code: \"${MISSING}\", backend: mock }"), new StartOptions(List.of(backend), true));
        assertThat(codes(result)).isEqualTo("GWY007");
        assertThat(backend.calls).isEmpty();
    }

    @Test
    void theBackendIsOpenedWithTheOperationsAndTheConnectCheckAndServesTheCalls() {
        FakeBackend backend = new FakeBackend();
        StartResult result = start(config(ibmi("test"), "mock: { code: M0001, backend: mock }"), new StartOptions(List.of(backend), false));
        assertThat(result.diagnostics()).isEmpty();
        Started started = result.started().orElseThrow();
        assertThat(backend.calls).contains("start acme/test 2 false");
        assertThat(started.backends()).containsExactlyElementsOf(backend.opened);
        assertThat(started.gateway().handle(new RuntimeRequest("GET", "/acme/test/orders/1001/status", Map.of(), Map.of(), Optional.empty(), () -> new byte[0])).status())
                .isEqualTo(503);
        assertThat(backend.opened.get(0).calls).hasValue(1);
        // The mock environment still answers from the mock.
        assertThat(started.gateway().handle(new RuntimeRequest("GET", "/acme/mock/orders/1001/status", Map.of(), Map.of(), Optional.empty(), () -> new byte[0])).status())
                .isEqualTo(200);
        started.close();
        assertThat(backend.opened.get(0).closed).hasValue(1);
    }

    @Test
    void aBackendThatCannotBeOpenedStopsTheStartAndThoseAlreadyOpenedAreClosed() {
        FakeBackend backend = new FakeBackend();
        backend.problems = environment -> environment.equals("prod") ? List.of("the sign-on was refused") : List.of();
        StartResult result = start(config(ibmi("test"), ibmi("prod")), new StartOptions(List.of(backend), true));
        assertThat(result.started()).isEmpty();
        assertThat(codes(result)).isEqualTo("GWY018");
        assertThat(result.diagnostics().get(0).message()).isEqualTo("The backend ibmi of the environment prod cannot be opened: the sign-on was refused");
        assertThat(backend.opened).hasSize(1);
        assertThat(backend.opened.get(0).closed).hasValue(1);
        assertThat(result.diagnostics().toString()).doesNotContain("PW-MARKER").doesNotContain("SOMEONE").doesNotContain("h.invalid");
    }

    @Test
    void closingTheGatewayClosesEveryBackendEvenWhenOneFails() {
        FakeBackend backend = new FakeBackend();
        Started started = start(config(ibmi("test")), new StartOptions(List.of(backend), true)).started().orElseThrow();
        ProgramCaller failing = new ProgramCaller() {
            @Override
            public CallOutcome call(ai.generativearchitects.slay400.gateway.call.CallContext context, ApiOperation operation,
                                    List<ai.generativearchitects.slay400.pcml.codec.WireValue> input) {
                return new CallOutcome.Unavailable();
            }

            @Override
            public void close() {
                throw new IllegalStateException("cannot close");
            }
        };
        Started two = new Started(started.config(), started.gateway(), 1, 2, List.of(failing, backend.opened.get(0)));
        two.close();
        assertThat(backend.opened.get(0).closed).hasValue(1);
        assertThat(new Started(started.config(), started.gateway(), 1, 2).backends()).isEmpty();
    }

    @Test
    void aBackendStartHasACallerOrProblems() {
        assertThatThrownBy(() -> new BackendStart(Optional.of(new FakeCaller()), List.of("x"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BackendStart(Optional.empty(), List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThat(BackendStart.failed(List.of("x")).problems()).containsExactly("x");
        assertThat(StartOptions.mockOnly()).isEqualTo(new StartOptions(List.of(), true));
        new MockCallerCloses().run();
    }

    /** The default close of the call interface does nothing and does not throw. */
    static final class MockCallerCloses implements Runnable {
        @Override
        public void run() {
            new ai.generativearchitects.slay400.gateway.call.MockCaller(Map.of()).close();
        }
    }
}
