package ai.generativearchitects.slay400.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.slay400.bundle.BundleReader;
import ai.generativearchitects.slay400.gateway.call.CallContext;
import ai.generativearchitects.slay400.gateway.call.CallOutcome;
import ai.generativearchitects.slay400.gateway.call.MockCaller;
import ai.generativearchitects.slay400.gateway.pipeline.Gateway;
import ai.generativearchitects.slay400.gateway.pipeline.RuntimeRequest;
import ai.generativearchitects.slay400.gateway.pipeline.RuntimeResponse;
import ai.generativearchitects.slay400.pcml.api.ApiOperation;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The two paths of the mock strategy that the pipeline tests do not reach: an interrupted delay and a missing mock. */
class MockCallerTest {

    @TempDir
    Path dir;

    @Test
    void anInterruptedDelayIsAFailedCallAndTheInterruptIsKept() {
        List<String> log = new ArrayList<>();
        Gateway gateway = Fixtures.start(dir, log).started().orElseThrow().gateway();
        // The case for order 5 waits 300 ms; on an interrupted thread the wait ends at once.
        Thread.currentThread().interrupt();
        RuntimeResponse response;
        boolean stillInterrupted;
        long started = System.nanoTime();
        try {
            response = gateway.handle(new RuntimeRequest("GET", "/acme/test/orders/5/status", Map.of(), Map.of(), Optional.empty(), () -> new byte[0]));
        } finally {
            stillInterrupted = Thread.interrupted();
        }
        long millis = (System.nanoTime() - started) / 1_000_000;
        assertThat(stillInterrupted).isTrue();
        assertThat(response.status()).isEqualTo(502);
        assertThat(millis).isLessThan(290);
        assertThat(log).singleElement().satisfies(line -> assertThat(line).contains("\"status\":502").contains("\"problems\":[\"program-call-failed\"]"));
    }

    @Test
    void anOperationWithoutAMockIsAFailedCallThatNamesTheOperation() {
        Path bundle = Fixtures.bundle(dir, "shop");
        ApiOperation operation = BundleReader.read(bundle).bundle().orElseThrow().model().operations().get(0);
        CallOutcome outcome = new MockCaller(Map.of()).call(new CallContext("acme", "test", "c-1"), operation, List.of());
        assertThat(outcome).isEqualTo(new CallOutcome.Failed("no mock for operation " + operation.id()));
    }
}
