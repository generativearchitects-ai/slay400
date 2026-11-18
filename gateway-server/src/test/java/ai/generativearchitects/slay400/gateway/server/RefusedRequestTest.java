package ai.generativearchitects.slay400.gateway.server;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.slay400.gateway.server.ServerFixture.Raw;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Requests that Helidon refuses before the pipeline get one access line each, without tenant, environment or route. */
class RefusedRequestTest {

    @TempDir
    static Path dir;
    static ServerFixture server;

    @BeforeAll
    static void start() {
        server = new ServerFixture(dir);
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    @BeforeEach
    void clear() {
        server.log.clear();
    }

    /** Waits a little for the line: Helidon writes it on its own thread, possibly after the answer. */
    private static void awaitLines(int count) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (server.log.size() < count && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"GARBAGE\r\n\r\n", "GET / HTTP/9.9\r\nHost: x\r\n\r\n", "GET / HTTP/1.1\r\nHost: x\r\nContent-Length: abc\r\n\r\n"})
    void aMalformedRequestHasOneAccessLineWithoutTenant(String request) throws InterruptedException {
        Raw raw = server.raw(request);
        awaitLines(1);
        assertThat(server.log).as(raw.head()).hasSize(1);
        String line = server.log.get(0);
        assertThat(line).contains("\"status\":" + raw.status()).contains("\"correlationId\":\"" + raw.headers().get("x-correlation-id") + "\"")
                .contains("\"durationMs\":0").doesNotContain("\"tenant\"").doesNotContain("\"environment\"").doesNotContain("\"route\"")
                .doesNotContain("\"operation\"").containsPattern("\"problems\":\\[\"[a-z:-]+\"]");
    }

    @Test
    void headersOverTheLimitHaveOneAccessLineWithoutTenant() throws InterruptedException {
        Raw raw = server.raw("GET /acme/test/orders/1001/status HTTP/1.1\r\nHost: x\r\nX-Big: " + "a".repeat(70000) + "\r\nConnection: close\r\n\r\n");
        awaitLines(1);
        // Helidon answers headers over its limit with 400, not 431.
        assertThat(raw.status()).isEqualTo(400);
        assertThat(server.log).hasSize(1);
        assertThat(server.log.get(0)).contains("\"status\":400").doesNotContain("\"tenant\"").contains("\"problems\":[\"request-invalid\"]");
    }

    @Test
    void aRequestThatReachesThePipelineStillHasExactlyOneLine() throws InterruptedException {
        Raw raw = server.raw("GET /acme/test/orders/1001/status HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n");
        awaitLines(1);
        Thread.sleep(100);
        assertThat(raw.status()).isEqualTo(200);
        assertThat(server.log).hasSize(1);
        assertThat(server.log.get(0)).contains("\"tenant\":\"acme\"");
    }

    @Test
    void theSlugIsTheLastSegmentOfTheProblemType() {
        assertThat(HelidonServer.slug("https://slay400.dev/problems/request-invalid")).isEqualTo("request-invalid");
        assertThat(HelidonServer.slug("about:blank")).isEqualTo("about:blank");
    }
}
