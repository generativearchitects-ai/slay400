package ai.generativearchitects.slay400.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.slay400.bundle.Bundle;
import ai.generativearchitects.slay400.bundle.BundleReader;
import ai.generativearchitects.slay400.gateway.call.CallOutcome;
import ai.generativearchitects.slay400.gateway.call.ProgramCaller;
import ai.generativearchitects.slay400.gateway.pipeline.CompiledOperation;
import ai.generativearchitects.slay400.gateway.pipeline.EnvironmentRuntime;
import ai.generativearchitects.slay400.gateway.pipeline.Gateway;
import ai.generativearchitects.slay400.gateway.pipeline.RuntimeRequest;
import ai.generativearchitects.slay400.gateway.pipeline.RuntimeResponse;
import ai.generativearchitects.slay400.gateway.pipeline.TenantRuntime;
import ai.generativearchitects.slay400.http.HttpBinding;
import ai.generativearchitects.slay400.http.ReadLimits;
import ai.generativearchitects.slay400.http.RouteTable;
import ai.generativearchitects.slay400.pcml.api.ApiOperation;
import ai.generativearchitects.slay400.pcml.codec.OperationCodec;
import ai.generativearchitects.slay400.pcml.codec.WireValue;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.stream.Stream;

/** Each row of the pipeline table, without HTTP. */
class PipelineTest {

    @TempDir
    Path dir;

    private final List<String> log = new ArrayList<>();
    private Gateway gateway;

    @BeforeEach
    void start() {
        gateway = Fixtures.start(dir, log).started().orElseThrow().gateway();
    }

    static RuntimeRequest get(String path) {
        return request("GET", path, Map.of(), Map.of(), Optional.empty(), () -> new byte[0]);
    }

    static RuntimeRequest request(String method, String path, Map<String, List<String>> query, Map<String, List<String>> headers,
                                  Optional<String> contentType, Supplier<byte[]> body) {
        return new RuntimeRequest(method, path, query, headers, contentType, body);
    }

    static String text(RuntimeResponse response) {
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    private static void assertProblem(RuntimeResponse response, int status, String type) {
        assertThat(response.status()).isEqualTo(status);
        assertThat(response.contentType()).contains("application/problem+json");
        assertThat(text(response)).contains("\"type\":\"https://slay400.dev/problems/" + type + "\"").contains("\"status\":" + status);
    }

    static Stream<String> goodIds() {
        return Stream.of("abc", "A.b_c-9", "0", "a".repeat(64));
    }

    static Stream<String> badIds() {
        return Stream.of("", " ", "a b", "a/b", "a\"b", "a\nb", "é", "a".repeat(65), "<script>", "a:b");
    }

    // ---- tenant and environment: 404

    @ParameterizedTest
    @ValueSource(strings = {"/", "/acme", "/acme/", "/nobody/test/orders/1001/status", "/acme/nowhere/orders/1001/status",
            "/ACME/test/orders/1001/status", "/ac%6De/test/orders/1001/status", "//test/orders/1/status", "orders/1001/status", "/health", "/health/other"})
    void unknownTenantOrEnvironmentIsA404ThatNamesNeitherOfThem(String path) {
        AtomicInteger bodyReads = new AtomicInteger();
        RuntimeResponse response = gateway.handle(request("GET", path, Map.of(), Map.of(), Optional.empty(), () -> {
            bodyReads.incrementAndGet();
            return new byte[0];
        }));
        assertProblem(response, 404, "not-found");
        assertThat(text(response)).doesNotContain("\"tenant\"").doesNotContain("\"environment\"").contains("\"correlationId\"");
        assertThat(bodyReads).hasValue(0);
        assertThat(log).hasSize(1);
        assertThat(log.get(0)).doesNotContain("\"tenant\"").doesNotContain("\"operation\"").contains("\"status\":404").contains("\"problems\":[\"not-found\"]");
    }

    // ---- route: 404 or 405

    @Test
    void anUnknownRouteIsA404WithTenantAndEnvironment() {
        assertProblem(gateway.handle(get("/acme/test")), 404, "not-found");
        log.clear();
        RuntimeResponse response = gateway.handle(get("/acme/test/nothing/here"));
        assertProblem(response, 404, "not-found");
        assertThat(text(response)).contains("\"tenant\":\"acme\"").contains("\"environment\":\"test\"");
        assertThat(log.get(0)).contains("\"tenant\":\"acme\"").contains("\"environment\":\"test\"").doesNotContain("\"route\"").doesNotContain("\"operation\"");
    }

    @Test
    void aKnownRouteWithAnotherMethodIsA405WithAnAllowHeader() {
        RuntimeResponse response = gateway.handle(request("DELETE", "/acme/test/orders/1001/status", Map.of(), Map.of(), Optional.empty(), () -> new byte[0]));
        assertProblem(response, 405, "method-not-allowed");
        assertThat(response.headers()).containsEntry("Allow", "GET");
        assertThat(response.headers()).hasSize(4);
    }

    @ParameterizedTest
    @ValueSource(strings = {"HEAD", "OPTIONS", "TRACE", "CONNECT", "get", "BREW", "PROPFIND"})
    void aMethodTheGatewayDoesNotServeIsA405OnARouteAndA404Elsewhere(String method) {
        RuntimeResponse onRoute = gateway.handle(request(method, "/acme/test/orders/1001/status", Map.of(), Map.of(), Optional.empty(), () -> new byte[0]));
        assertProblem(onRoute, 405, "method-not-allowed");
        assertThat(onRoute.headers()).containsEntry("Allow", "GET");
        RuntimeResponse elsewhere = gateway.handle(request(method, "/acme/test/nothing", Map.of(), Map.of(), Optional.empty(), () -> new byte[0]));
        assertProblem(elsewhere, 404, "not-found");
        assertThat(log.get(0)).contains("\"method\":\"OTHER\"");
    }

    @Test
    void theBodyIsReadOnlyWhenTheRouteIsFound() {
        AtomicInteger reads = new AtomicInteger();
        Supplier<byte[]> body = () -> {
            reads.incrementAndGet();
            return new byte[0];
        };
        gateway.handle(request("POST", "/acme/test/orders/1001/status", Map.of(), Map.of(), Optional.empty(), body));
        gateway.handle(request("GET", "/acme/test/nothing", Map.of(), Map.of(), Optional.empty(), body));
        assertThat(reads).hasValue(0);
        gateway.handle(request("GET", "/acme/test/orders/1001/status", Map.of(), Map.of(), Optional.empty(), body));
        assertThat(reads).hasValue(1);
    }

    // ---- request: 400

    @Test
    void aValueThatIsNotTheDeclaredTypeIsA400WithTheProblemsAndNoValue() {
        RuntimeResponse response = gateway.handle(get("/acme/test/orders/not-a-number/status"));
        assertProblem(response, 400, "request-invalid");
        assertThat(text(response)).contains("\"errors\"").doesNotContain("not-a-number");
        assertThat(log.get(0)).contains("\"status\":400").doesNotContain("not-a-number").containsPattern("\"problems\":\\[\"[A-Z]+[0-9]+\"");
    }

    @Test
    void aBodyThatIsNotJsonIsA400() {
        RuntimeResponse response = gateway.handle(request("POST", "/acme/test/items/ABC12345/quantity", Map.of("warehouse", List.of("W01")),
                Map.of("X-Channel", List.of("WEB")), Optional.of("application/json"), () -> "{not json".getBytes(StandardCharsets.UTF_8)));
        assertProblem(response, 400, "request-invalid");
    }

    @Test
    void aBodyOverTheLimitIsA400AndTheLimitPlusOneByteIsEnoughToKnow() {
        byte[] body = new byte[gateway.maxBodyBytes() + 1];
        java.util.Arrays.fill(body, (byte) ' ');
        RuntimeResponse response = gateway.handle(request("POST", "/acme/test/items/ABC12345/quantity", Map.of("warehouse", List.of("W01")),
                Map.of("X-Channel", List.of("WEB")), Optional.of("application/json"), () -> body));
        assertProblem(response, 400, "request-invalid");
        assertThat(gateway.maxBodyBytes()).isEqualTo(ReadLimits.defaults().maxBodyBytes());
        assertThat(gateway.limits()).isEqualTo(ReadLimits.defaults());
    }

    @Test
    void aMissingRequiredFieldIsA400() {
        RuntimeResponse response = gateway.handle(request("POST", "/acme/test/items/ABC12345/quantity", Map.of("warehouse", List.of("W01")),
                Map.of("X-Channel", List.of("WEB")), Optional.of("application/json"), () -> "{}".getBytes(StandardCharsets.UTF_8)));
        assertProblem(response, 400, "request-invalid");
    }

    // ---- success, one for each parameter location

    @Test
    void successForPathParametersAndAnInjectedValue() {
        RuntimeResponse response = gateway.handle(get("/acme/test/orders/1001/status"));
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.contentType()).contains("application/json");
        assertThat(text(response)).isEqualTo("{\"status\":\"S\",\"deliveryDate\":\"2026-11-20\"}");
        assertThat(log.get(0)).contains("\"operation\":\"ordstatus\"").contains("\"route\":\"/orders/{orderNumber}/status\"").doesNotContain("problems");
    }

    @Test
    void successForQueryHeaderAndBodyParameters() {
        RuntimeResponse response = gateway.handle(request("POST", "/acme/test/items/ABC12345/quantity", Map.of("warehouse", List.of("W01")),
                Map.of("x-channel", List.of("WEB")), Optional.of("application/json"), () -> "{\"quantity\":7,\"note\":\"hello\"}".getBytes(StandardCharsets.UTF_8)));
        assertThat(response.status()).isEqualTo(200);
        assertThat(text(response)).isEqualTo("{\"onHand\":42}");
    }

    // ---- call: 503, 504

    @Test
    void aSimulatedUnavailableProgramIsA503() {
        RuntimeResponse response = gateway.handle(get("/acme/test/orders/6/status"));
        assertProblem(response, 503, "service-unavailable");
        assertThat(log.get(0)).contains("\"status\":503");
    }

    @Test
    void aSimulatedTimeoutIsA504() {
        assertProblem(gateway.handle(get("/acme/test/orders/7/status")), 504, "program-timeout");
    }

    @Test
    void aMockDelayIsHonouredOnTheCallingThread() {
        long started = System.nanoTime();
        RuntimeResponse response = gateway.handle(get("/acme/test/orders/5/status"));
        long millis = (System.nanoTime() - started) / 1_000_000;
        assertThat(response.status()).isEqualTo(200);
        assertThat(millis).isGreaterThanOrEqualTo(290);
        assertThat(log.get(0)).containsPattern("\"durationMs\":[0-9]{3,}");
    }

    // ---- business error

    @Test
    void aBusinessErrorIsTheMappedStatusWithTheProgramsCodeAndMessage() {
        RuntimeResponse refused = gateway.handle(get("/acme/test/orders/4/status"));
        assertProblem(refused, 422, "program-error");
        assertThat(text(refused)).contains("E999").contains("Order refused");
        RuntimeResponse notFound = gateway.handle(get("/acme/test/orders/9999/status"));
        assertThat(notFound.status()).isEqualTo(404);
        assertThat(text(notFound)).contains("Order not found").contains("\"tenant\":\"acme\"");
        assertThat(log.get(1)).contains("\"problems\":[\"program-error\"]").doesNotContain("E404").doesNotContain("Order not found");
    }

    // ---- failures that the mock cannot produce: a stub of the call interface

    private Gateway gatewayWith(ProgramCaller caller) {
        Bundle bundle = BundleReader.read(dir.resolve("shop-1.0.0.rpgapi")).bundle().orElseThrow();
        Map<ApiOperation, CompiledOperation> operations = new IdentityHashMap<>();
        Map<ApiOperation, Map<String, String>> injected = new IdentityHashMap<>();
        for (ApiOperation operation : bundle.model().operations()) {
            operations.put(operation, new CompiledOperation(operation, HttpBinding.of(operation), OperationCodec.of(operation)));
            injected.put(operation, Map.of("P0ENV", "T0001"));
        }
        TenantRuntime tenant = new TenantRuntime("acme", RouteTable.of(bundle.model()), operations,
                Map.of("test", new EnvironmentRuntime("test", caller, injected)));
        return new Gateway(Map.of("acme", tenant), ReadLimits.defaults(), log::add);
    }

    @Test
    void aCallThatFailsIsA502WithNoReasonInTheBody() {
        Gateway stubbed = gatewayWith((context, operation, input) -> new CallOutcome.Failed("escape message CPF1234 from library SECRETLIB"));
        RuntimeResponse response = stubbed.handle(get("/acme/test/orders/1001/status"));
        assertProblem(response, 502, "program-call-failed");
        assertThat(text(response)).doesNotContain("CPF1234").doesNotContain("SECRETLIB");
        assertThat(String.join("\n", log)).doesNotContain("CPF1234");
    }

    @Test
    void outputThatTheCodecRefusesIsA502() {
        Gateway stubbed = gatewayWith((context, operation, input) -> {
            List<WireValue> output = new ArrayList<>(input);
            output.replaceAll(v -> new WireValue.Text("?"));
            output.set(3, new WireValue.Text("not a date"));
            return new CallOutcome.Returned(output);
        });
        RuntimeResponse response = stubbed.handle(get("/acme/test/orders/1001/status"));
        assertProblem(response, 502, "program-call-failed");
        assertThat(text(response)).doesNotContain("not a date");
        assertThat(log.get(0)).doesNotContain("not a date").containsPattern("\"problems\":\\[\"[A-Z]+[0-9]+\"");
    }

    @Test
    void theCallReceivesTenantEnvironmentAndCorrelationId() {
        List<String> seen = new ArrayList<>();
        Gateway stubbed = gatewayWith((context, operation, input) -> {
            seen.add(context.tenant() + "/" + context.environment() + "/" + context.correlationId() + "/" + operation.id() + "/" + input.size());
            return new CallOutcome.Unavailable();
        });
        stubbed.handle(request("GET", "/acme/test/orders/1/status", Map.of(), Map.of("X-Correlation-Id", List.of("req-1")), Optional.empty(), () -> new byte[0]));
        assertThat(seen).containsExactly("acme/test/req-1/ordstatus/7");
    }

    @Test
    void anExceptionNobodyExpectedIsA500WithNoDetailAndItIsLogged() {
        Gateway stubbed = gatewayWith((context, operation, input) -> {
            throw new IllegalStateException("secret internal detail");
        });
        RuntimeResponse response = stubbed.handle(get("/acme/test/orders/1001/status"));
        assertProblem(response, 500, "internal-error");
        assertThat(text(response)).doesNotContain("secret internal detail").doesNotContain("IllegalState").doesNotContain("\"detail\"");
        assertThat(log).hasSize(1);
        assertThat(log.get(0)).contains("\"status\":500").doesNotContain("secret internal detail");
    }

    @Test
    void aFailingAccessLogDoesNotFailTheRequest() {
        Gateway stubbed = new Gateway(Map.of(), ReadLimits.defaults(), line -> {
            throw new IllegalStateException("log is down");
        });
        assertThat(stubbed.handle(get("/x/y/z")).status()).isEqualTo(404);
    }

    // ---- headers and correlation

    @Test
    void everyResponseCarriesTheSecurityHeadersAndTheCorrelationId() {
        for (String path : List.of("/acme/test/orders/1001/status", "/acme/test/orders/x/status", "/nope/x/y", "/health/live", "/acme/test/orders/6/status")) {
            RuntimeResponse response = gateway.handle(get(path));
            assertThat(response.headers()).containsEntry("X-Content-Type-Options", "nosniff").containsEntry("Cache-Control", "no-store")
                    .containsKey("X-Correlation-Id");
        }
    }

    @ParameterizedTest
    @MethodSource("goodIds")
    void aWellFormedCorrelationIdIsKeptAndReturned(String id) {
        RuntimeResponse response = gateway.handle(request("GET", "/acme/test/orders/x/status", Map.of(), Map.of("x-CORRELATION-id", List.of(id)), Optional.empty(), () -> new byte[0]));
        assertThat(response.headers()).containsEntry("X-Correlation-Id", id);
        assertThat(text(response)).contains("\"correlationId\":\"" + id + "\"");
        assertThat(log.get(0)).contains("\"correlationId\":\"" + id + "\"");
    }

    @ParameterizedTest
    @MethodSource("badIds")
    void aCorrelationIdOfAnotherFormIsReplacedByANewOne(String id) {
        RuntimeResponse response = gateway.handle(request("GET", "/acme/test/orders/x/status", Map.of(), Map.of("X-Correlation-Id", List.of(id)), Optional.empty(), () -> new byte[0]));
        String used = response.headers().get("X-Correlation-Id");
        assertThat(used).isNotEqualTo(id).matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}");
        assertThat(log.get(0)).doesNotContain("\"correlationId\":\"" + id + "\"");
    }

    @Test
    void severalCorrelationHeadersGiveANewId() {
        RuntimeResponse response = gateway.handle(request("GET", "/acme/test/orders/x/status", Map.of(), Map.of("X-Correlation-Id", List.of("one", "two")), Optional.empty(), () -> new byte[0]));
        assertThat(response.headers().get("X-Correlation-Id")).isNotIn("one", "two");
    }

    @Test
    void everyRequestWithoutAnIdGetsADifferentOne() {
        String a = gateway.handle(get("/x")).headers().get("X-Correlation-Id");
        String b = gateway.handle(get("/x")).headers().get("X-Correlation-Id");
        assertThat(a).isNotEqualTo(b);
    }

    // ---- health

    @Test
    void healthAnswers200WithABodyThatTellsNothing() {
        for (String path : List.of("/health/live", "/health/ready")) {
            RuntimeResponse response = gateway.handle(get(path));
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.contentType()).contains("application/json");
            assertThat(text(response)).isEqualTo("{\"status\":\"ok\"}");
        }
    }

    @Test
    void healthWithAnotherMethodIsA405WithAllowGet() {
        RuntimeResponse response = gateway.handle(request("POST", "/health/live", Map.of(), Map.of(), Optional.empty(), () -> new byte[0]));
        assertProblem(response, 405, "method-not-allowed");
        assertThat(response.headers()).containsEntry("Allow", "GET");
    }

    // ---- the log

    @Test
    void aMarkerInThePathTheQueryTheHeadersAndTheBodyIsNeverLogged() {
        String marker = "MARKER-7f3a9";
        gateway.handle(request("POST", "/acme/test/items/" + marker + "/quantity", Map.of("warehouse", List.of(marker), marker, List.of(marker)),
                Map.of("X-Channel", List.of(marker), "Cookie", List.of(marker), "Authorization", List.of(marker)), Optional.of("application/json"),
                () -> ("{\"quantity\":7,\"note\":\"" + marker + "\"}").getBytes(StandardCharsets.UTF_8)));
        gateway.handle(get("/acme/test/orders/" + marker + "/status"));
        gateway.handle(get("/" + marker + "/test/orders/1/status"));
        gateway.handle(get("/acme/" + marker + "/orders/1/status"));
        gateway.handle(request(marker, "/acme/test/" + marker, Map.of(marker, List.of(marker)), Map.of(marker, List.of(marker)), Optional.empty(), () -> new byte[0]));
        assertThat(log).hasSize(5);
        assertThat(String.join("\n", log)).doesNotContain(marker).doesNotContain("MARKER");
    }

    @Test
    void theLogLineIsOneLineOfJsonWithTheDocumentedFields() {
        gateway.handle(get("/acme/test/orders/1001/status"));
        assertThat(log).hasSize(1);
        assertThat(log.get(0)).doesNotContain("\n").matches("\\{\"time\":\"[^\"]+\",\"tenant\":\"acme\",\"environment\":\"test\",\"operation\":\"ordstatus\","
                + "\"method\":\"GET\",\"route\":\"/orders/\\{orderNumber}/status\",\"status\":200,\"durationMs\":[0-9]+,\"correlationId\":\"[^\"]+\"}");
    }
}
