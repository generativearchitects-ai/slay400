package ai.generativearchitects.slay400.gateway.server;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.slay400.bundle.Bundle;
import ai.generativearchitects.slay400.bundle.BundleReader;
import ai.generativearchitects.slay400.gateway.Fixtures;
import ai.generativearchitects.slay400.gateway.call.CallOutcome;
import ai.generativearchitects.slay400.gateway.call.ProgramCaller;
import ai.generativearchitects.slay400.gateway.pipeline.CompiledOperation;
import ai.generativearchitects.slay400.gateway.pipeline.EnvironmentRuntime;
import ai.generativearchitects.slay400.gateway.pipeline.Gateway;
import ai.generativearchitects.slay400.gateway.pipeline.RuntimeRequest;
import ai.generativearchitects.slay400.gateway.pipeline.RuntimeResponse;
import ai.generativearchitects.slay400.gateway.pipeline.TenantRuntime;
import ai.generativearchitects.slay400.http.HttpBinding;
import ai.generativearchitects.slay400.http.ProblemKind;
import ai.generativearchitects.slay400.http.ReadLimits;
import ai.generativearchitects.slay400.http.RouteTable;
import ai.generativearchitects.slay400.pcml.api.ApiOperation;
import ai.generativearchitects.slay400.pcml.codec.OperationCodec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The errors reference of the documentation (gateway kickoff 09): one real response for every status the gateway answers
 * with, recorded by this test into {@code docs/examples/reference/errors/}, and the page generated from them and the
 * hand-written notes of {@code docs/reference/_notes/errors/}. The responses come from a running server with the mock of the
 * {@code shop} fixture; the two that a mock cannot produce (502 and 500) come from the pipeline with a stub of the call
 * interface. Fails when a committed file is not what the gateway answers; with {@code -Dslay400.docs.write=true} writes them.
 *
 * <p>One substitution, so that a recording is the same at every run: a correlation ID the gateway made itself, because the
 * request was refused before its headers were read, is written {@code <generated>}.
 */
class ErrorsReferenceTest {

    static final Path DOCS = Path.of("..", "docs");
    static final Path PAGE = DOCS.resolve("reference/errors/index.md");
    static final Path NOTES = DOCS.resolve("reference/_notes/errors");
    static final String RESPONSES = "examples/reference/errors";
    static final String COMMAND = "./mvnw -pl gateway-server -am test -Dtest=ErrorsReferenceTest -Dsurefire.failIfNoSpecifiedTests=false -Dslay400.docs.write=true";
    static final Set<String> SHOWN_HEADERS = Set.of("content-type", "allow", "x-correlation-id");
    static final String ITEMS = "POST /acme/test/items/ABC12345/quantity?warehouse=W01 HTTP/1.1\r\nHost: x\r\nX-Channel: WEB\r\nContent-Type: application/json\r\n";

    @TempDir
    Path dir;

    /** One case of the page: the file name of its recording and of its note, and the heading. */
    record Case(String id, String heading) {
    }

    static final List<Case> CASES = List.of(
            new Case("request-invalid", "400 The request is not valid"),
            new Case("request-too-large", "400 Request line or headers too large"),
            new Case("not-found", "404 Not found"),
            new Case("program-error-mapped", "404 or another status: the program reported an error"),
            new Case("method-not-allowed", "405 Method not allowed"),
            new Case("payload-too-large", "413 Request body too large"),
            new Case("uri-too-long", "414 Address too long"),
            new Case("program-error", "422 The program reported an error"),
            new Case("internal-error", "500 Internal server error"),
            new Case("program-call-failed", "502 The program call failed"),
            new Case("service-unavailable", "503 Service unavailable"),
            new Case("program-timeout", "504 The program did not answer in time"));

    @Test
    void theRecordedResponsesAndThePageAreWhatTheGatewayAnswers() throws IOException {
        Map<String, String> recorded = record();
        if (Boolean.getBoolean("slay400.docs.write")) {
            Files.createDirectories(DOCS.resolve(RESPONSES));
            for (Map.Entry<String, String> entry : recorded.entrySet()) {
                Files.writeString(DOCS.resolve(RESPONSES).resolve(entry.getKey() + ".http"), entry.getValue());
            }
            Files.createDirectories(PAGE.getParent());
            Files.writeString(PAGE, render());
        }
        for (Map.Entry<String, String> entry : recorded.entrySet()) {
            assertThat(Files.readString(DOCS.resolve(RESPONSES).resolve(entry.getKey() + ".http")))
                    .as(RESPONSES + "/" + entry.getKey() + ".http is stale; regenerate with: " + COMMAND).isEqualTo(entry.getValue());
        }
        assertThat(Files.readString(PAGE)).as("docs/reference/errors/index.md is stale; regenerate with: " + COMMAND).isEqualTo(render());
    }

    @Test
    void everyStatusOfTheCommunityEditionHasACase() {
        Set<ProblemKind> kinds = EnumSet.allOf(ProblemKind.class);
        // Authentication and authorisation are Enterprise extension points: the Community Edition never answers 401 or 403.
        kinds.removeAll(EnumSet.of(ProblemKind.UNAUTHORIZED, ProblemKind.FORBIDDEN));
        Set<String> ids = new TreeSet<>();
        CASES.forEach(c -> ids.add(c.id()));
        for (ProblemKind kind : kinds) {
            assertThat(ids).as("a case for " + kind).contains(kind.type().substring(kind.type().lastIndexOf('/') + 1));
        }
    }

    /** Every recording, by case id. */
    private Map<String, String> record() {
        Map<String, String> recorded = new LinkedHashMap<>();
        try (ServerFixture server = new ServerFixture(dir)) {
            recorded.put("request-invalid", http(server, ITEMS + id("request-invalid") + "Content-Length: 16\r\nConnection: close\r\n\r\n{\"quantity\":\"x\"}"));
            recorded.put("request-too-large", http(server, "GET /acme/test/orders/1001/status HTTP/1.1\r\nHost: x\r\n" + id("request-too-large")
                    + "X-Big: " + "a".repeat(70000) + "\r\nConnection: close\r\n\r\n"));
            recorded.put("not-found", http(server, get("/acme/nowhere/orders/1001/status", "not-found")));
            recorded.put("program-error-mapped", http(server, get("/acme/test/orders/9999/status", "program-error-mapped")));
            recorded.put("method-not-allowed", http(server, "DELETE /acme/test/orders/1001/status HTTP/1.1\r\nHost: x\r\n" + id("method-not-allowed")
                    + "Connection: close\r\n\r\n"));
            recorded.put("payload-too-large", http(server, ITEMS + id("payload-too-large") + "Content-Length: " + (ServerLimits.DEFAULTS.maxPayloadBytes() + 1)
                    + "\r\nConnection: close\r\n\r\n"));
            recorded.put("uri-too-long", http(server, "GET /acme/test/" + "a".repeat(8200) + " HTTP/1.1\r\nHost: x\r\n" + id("uri-too-long") + "Connection: close\r\n\r\n"));
            recorded.put("program-error", http(server, get("/acme/test/orders/4/status", "program-error")));
            recorded.put("service-unavailable", http(server, get("/acme/test/orders/6/status", "service-unavailable")));
            recorded.put("program-timeout", http(server, get("/acme/test/orders/7/status", "program-timeout")));
        }
        recorded.put("internal-error", pipeline((context, operation, input) -> {
            throw new IllegalStateException("not shown");
        }, "internal-error"));
        recorded.put("program-call-failed", pipeline((context, operation, input) -> new CallOutcome.Failed("not shown"), "program-call-failed"));
        return recorded;
    }

    private static String id(String id) {
        return "X-Correlation-Id: example-" + id + "\r\n";
    }

    private static String get(String path, String id) {
        return "GET " + path + " HTTP/1.1\r\nHost: x\r\n" + id(id) + "Connection: close\r\n\r\n";
    }

    private static String http(ServerFixture server, String request) {
        ServerFixture.Raw raw = server.raw(request);
        StringBuilder out = new StringBuilder(raw.head().split("\r\n")[0]).append('\n');
        raw.headers().forEach((name, value) -> {
            if (SHOWN_HEADERS.contains(name)) {
                out.append(header(name)).append(": ").append(value).append('\n');
            }
        });
        out.append('\n').append(raw.body().strip()).append('\n');
        return generated(out.toString());
    }

    /** A response of the pipeline with a stub of the call interface, written as the server writes it. */
    private String pipeline(ProgramCaller caller, String id) {
        Path bundleFile = dir.resolve("shop-1.0.0.rpgapi");
        if (!Files.exists(bundleFile)) {
            Fixtures.bundle(dir, "shop");
        }
        Bundle bundle = BundleReader.read(bundleFile).bundle().orElseThrow();
        Map<ApiOperation, CompiledOperation> operations = new IdentityHashMap<>();
        Map<ApiOperation, Map<String, String>> injected = new IdentityHashMap<>();
        for (ApiOperation operation : bundle.model().operations()) {
            operations.put(operation, new CompiledOperation(operation, HttpBinding.of(operation), OperationCodec.of(operation)));
            injected.put(operation, Map.of("P0ENV", "T0001"));
        }
        TenantRuntime tenant = new TenantRuntime("acme", RouteTable.of(bundle.model()), operations,
                Map.of("test", new EnvironmentRuntime("test", caller, injected)));
        Gateway gateway = new Gateway(Map.of("acme", tenant), ReadLimits.defaults(), line -> { });
        RuntimeResponse response = gateway.handle(new RuntimeRequest("GET", "/acme/test/orders/1001/status", Map.of(),
                Map.of("X-Correlation-Id", List.of("example-" + id)), Optional.empty(), () -> new byte[0]));
        StringBuilder out = new StringBuilder("HTTP/1.1 ").append(response.status()).append(' ').append(reason(response.status())).append('\n');
        Map<String, String> headers = new TreeMap<>();
        response.contentType().ifPresent(type -> headers.put("content-type", type));
        response.headers().forEach((name, value) -> headers.put(name.toLowerCase(Locale.ROOT), value));
        headers.forEach((name, value) -> {
            if (SHOWN_HEADERS.contains(name)) {
                out.append(header(name)).append(": ").append(value).append('\n');
            }
        });
        out.append('\n').append(new String(response.body(), StandardCharsets.UTF_8).strip()).append('\n');
        return out.toString();
    }

    private static String reason(int status) {
        return switch (status) {
            case 500 -> "Internal Server Error";
            case 502 -> "Bad Gateway";
            default -> throw new IllegalArgumentException("no reason phrase for " + status);
        };
    }

    private static String header(String lowerCase) {
        return switch (lowerCase) {
            case "content-type" -> "Content-Type";
            case "allow" -> "Allow";
            case "x-correlation-id" -> "X-Correlation-Id";
            default -> lowerCase;
        };
    }

    /** A correlation ID that the gateway made itself, written {@code <generated>}. */
    private static String generated(String text) {
        return text.replaceAll("(X-Correlation-Id: |\"correlationId\":\"|urn:slay400:request:)(?!example-)[^\"\\n]+", "$1<generated>");
    }

    static String render() throws IOException {
        StringBuilder out = new StringBuilder();
        out.append("""
                ---
                title: Errors reference
                description: Every status the gateway answers with, when, and the problem-details body, with one real response each.
                section: reference
                slug: errors
                order: 50
                appliesTo: "0.1"
                ---

                """);
        out.append("<!-- Generated by ErrorsReferenceTest from the gateway's answers and docs/reference/_notes/errors/. ")
                .append("Do not edit by hand; regenerate with: ").append(COMMAND).append(" -->\n\n");
        out.append("# Errors reference\n\n");
        out.append(note("intro")).append('\n');
        for (Case c : CASES) {
            out.append("## ").append(c.heading()).append("\n\n");
            out.append(note(c.id())).append('\n');
            out.append("<!-- snippet: ").append(RESPONSES).append('/').append(c.id()).append(".http -->\n```http\n")
                    .append(Files.readString(DOCS.resolve(RESPONSES).resolve(c.id() + ".http")).stripTrailing()).append("\n```\n\n");
        }
        return out.toString().stripTrailing() + "\n";
    }

    private static String note(String name) throws IOException {
        return Files.readString(NOTES.resolve(name + ".md")).stripTrailing() + "\n";
    }
}
