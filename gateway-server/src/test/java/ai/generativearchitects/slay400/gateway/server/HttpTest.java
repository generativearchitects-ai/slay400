package ai.generativearchitects.slay400.gateway.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The gateway over HTTP: a server on a free port and the JDK HTTP client. */
class HttpTest {

    @TempDir
    static Path dir;
    static ServerFixture server;
    static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeAll
    static void start() {
        server = new ServerFixture(dir);
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    static HttpResponse<String> send(HttpRequest.Builder builder) throws IOException, InterruptedException {
        return CLIENT.send(builder.timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(URI.create(server.url(path))).GET());
    }

    /** The golden body with the correlation id in place. */
    static String golden(String name) throws IOException {
        return Files.readString(Path.of("src/test/resources/golden/" + name + ".json")).strip();
    }

    static String normalise(HttpResponse<String> response) {
        String id = response.headers().firstValue("X-Correlation-Id").orElseThrow();
        return response.body().replace(id, "<id>");
    }

    // ---- a success for each parameter location

    @Test
    void successWithPathAndInjectedParameters() throws Exception {
        HttpResponse<String> response = get("/acme/test/orders/1001/status");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(v -> assertThat(v).startsWith("application/json"));
        assertThat(response.body()).isEqualTo("{\"status\":\"S\",\"deliveryDate\":\"2026-11-20\"}");
    }

    @Test
    void successWithQueryHeaderAndBodyParameters() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(server.url("/acme/test/items/ABC12345/quantity?warehouse=W01")))
                .header("X-Channel", "WEB").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"quantity\":7,\"note\":\"hello\"}")));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("{\"onHand\":42}");
    }

    @Test
    void theSecondEnvironmentServesToo() throws Exception {
        assertThat(get("/acme/prod/orders/1001/status").statusCode()).isEqualTo(200);
    }

    // ---- each status of the table with its golden problem body

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            GET|/acme/test/nothing/here|404|route-not-found
            GET|/nobody/test/orders/1/status|404|unknown-tenant
            DELETE|/acme/test/orders/1001/status|405|method-not-allowed
            GET|/acme/test/orders/abc/status|400|request-invalid
            GET|/acme/test/orders/4/status|422|program-error
            GET|/acme/test/orders/9999/status|404|program-error-mapped
            GET|/acme/test/orders/6/status|503|service-unavailable
            GET|/acme/test/orders/7/status|504|program-timeout
            """)
    void problemBodiesAreGolden(String method, String path, int status, String golden) throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(server.url(path))).method(method, HttpRequest.BodyPublishers.noBody()));
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type")).contains("application/problem+json");
        assertThat(normalise(response)).isEqualTo(golden(golden));
    }

    @Test
    void aBodyOverTheLimitIsA400() throws Exception {
        byte[] body = new byte[1024 * 1024 + 1];
        java.util.Arrays.fill(body, (byte) ' ');
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(server.url("/acme/test/items/ABC12345/quantity?warehouse=W01")))
                .header("X-Channel", "WEB").header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(body)));
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.headers().firstValue("Content-Type")).contains("application/problem+json");
    }

    @Test
    void methodNotAllowedCarriesAnAllowHeader() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(server.url("/acme/test/orders/1001/status")))
                .POST(HttpRequest.BodyPublishers.noBody()));
        assertThat(response.statusCode()).isEqualTo(405);
        assertThat(response.headers().firstValue("Allow")).contains("GET");
    }

    // ---- correlation id, response headers, health

    @Test
    void aWellFormedCorrelationIdComesBackAndAnotherIsReplaced() throws Exception {
        HttpResponse<String> kept = send(HttpRequest.newBuilder(URI.create(server.url("/acme/test/orders/1001/status"))).header("X-Correlation-Id", "req-42.a_b").GET());
        assertThat(kept.headers().firstValue("X-Correlation-Id")).contains("req-42.a_b");
        HttpResponse<String> replaced = send(HttpRequest.newBuilder(URI.create(server.url("/acme/test/orders/1001/status"))).header("X-Correlation-Id", "bad id!").GET());
        assertThat(replaced.headers().firstValue("X-Correlation-Id").orElseThrow()).isNotEqualTo("bad id!").hasSize(36);
        assertThat(get("/acme/test/orders/x/status").headers().firstValue("X-Correlation-Id")).isPresent();
    }

    @Test
    void everyResponseCarriesTheSecurityHeaders() throws Exception {
        for (String path : new String[] {"/acme/test/orders/1001/status", "/acme/test/orders/x/status", "/nope", "/health/live", "/acme/test/orders/6/status"}) {
            HttpResponse<String> response = get(path);
            assertThat(response.headers().firstValue("X-Content-Type-Options")).as(path).contains("nosniff");
            assertThat(response.headers().firstValue("Cache-Control")).as(path).contains("no-store");
        }
    }

    @Test
    void healthEndpointsAnswer200WithABodyThatTellsNothing() throws Exception {
        for (String path : new String[] {"/health/live", "/health/ready"}) {
            HttpResponse<String> response = get(path);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo("{\"status\":\"ok\"}");
        }
    }

    // ---- the mock: a delay and each simulated failure

    @Test
    void aMockDelayIsHonoured() throws Exception {
        long started = System.nanoTime();
        HttpResponse<String> response = get("/acme/test/orders/5/status");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat((System.nanoTime() - started) / 1_000_000).isGreaterThanOrEqualTo(290);
    }

    @Test
    void requestsAreHandledOnVirtualThreadsSoSlowOnesDoNotBlockFastOnes() throws Exception {
        var slow = CLIENT.sendAsync(HttpRequest.newBuilder(URI.create(server.url("/acme/test/orders/5/status"))).build(), HttpResponse.BodyHandlers.ofString());
        long started = System.nanoTime();
        assertThat(get("/health/live").statusCode()).isEqualTo(200);
        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(250);
        assertThat(slow.get().statusCode()).isEqualTo(200);
    }

    // ---- the log over HTTP

    @Test
    void aMarkerInThePathTheQueryTheHeadersAndTheBodyIsNeverLogged() throws Exception {
        String marker = "MARKER-7f3a9";
        server.log.clear();
        send(HttpRequest.newBuilder(URI.create(server.url("/acme/test/items/" + marker + "/quantity?warehouse=" + marker + "&" + marker + "=" + marker)))
                .header("X-Channel", marker).header("Cookie", marker).header("Authorization", marker).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"quantity\":7,\"note\":\"" + marker + "\"}")));
        get("/" + marker + "/test/orders/1/status");
        get("/acme/" + marker + "/orders/1/status");
        get("/acme/test/" + marker);
        assertThat(server.log).hasSize(4);
        assertThat(String.join("\n", server.log)).doesNotContain(marker);
        assertThat(StandardCharsets.UTF_8.name()).isNotEmpty();
    }
}
