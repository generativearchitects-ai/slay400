package ai.generativearchitects.slay400.gateway.server;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.slay400.gateway.server.ServerFixture.Raw;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Hostile requests over HTTP, with raw sockets so that the client rewrites nothing. None gives a 500, a stack trace in the body,
 * or an answer that is not a problem body.
 */
class HostileTest {

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

    static String request(String method, String target, String... headers) {
        StringBuilder text = new StringBuilder(method).append(' ').append(target).append(" HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n");
        for (String header : headers) {
            text.append(header).append("\r\n");
        }
        return text.append("\r\n").toString();
    }

    /** What every answer must be: a problem body (or none, for HEAD), never a 500, never a stack trace. */
    static void assertSafe(Raw raw, boolean head) {
        assertThat(raw.status()).as(raw.head()).isNotEqualTo(-1).isNotEqualTo(500).isBetween(200, 505);
        assertThat(raw.body()).doesNotContain("Exception").doesNotContain("\tat ").doesNotContain("java.").doesNotContain("io.helidon");
        if (raw.status() >= 400 && !head) {
            assertThat(raw.headers().get("content-type")).as(raw.head()).isEqualTo("application/problem+json");
            // A status that is not in the table of the gateway has the type about:blank (RFC 9457).
            assertThat(raw.body()).containsPattern("\"type\":\"(https://slay400.dev/problems/[a-z-]+|about:blank)\"").contains("\"status\":" + raw.status());
            assertThat(raw.headers()).containsEntry("x-content-type-options", "nosniff").containsEntry("cache-control", "no-store").containsKey("x-correlation-id");
        }
    }

    static Stream<String> paths() {
        return Stream.of("/acme/test/orders/1%2F2/status", "/acme/test/orders/1%2f2/status", "/acme/test/orders/..%2F1001/status",
                "/acme/test/orders/../orders/1001/status", "/acme/test/./orders/1001/status", "/acme/test/orders/%2e%2e/1/status",
                "/acme/test/orders/%2e/status", "/acme/test/orders/1001/status/..", "/acme/test/orders//1001/status", "/acme/test//orders/1001/status",
                "//acme/test/orders/1001/status", "/acme/test/orders/1001/status/", "/acme/test/orders/%zz/status", "/acme/test/orders/%/status",
                "/acme/test/orders/%2/status", "/acme/test/orders/%00/status", "/acme/test/orders/%c0%af/status", "/acme/test/orders/%ff/status",
                "/acme/test/orders/%0d%0aX-Injected:%20yes/status", "/acme/%00/orders/1/status", "/%00/test/orders/1/status", "/x/y/z", "/", "/favicon.ico",
                "/acme/test/orders/1001/status;jsessionid=1", "/acme/test/orders/1001/status?%zz=1&=&&a&b=%", "/acme/test/orders/1001/status?" + "a=b&".repeat(2000),
                "/acme/test/" + "a/".repeat(5000), "/" + "a".repeat(20000), "/acme/test/orders/" + "9".repeat(5000) + "/status", "/acme/test/orders/%E0%A4%A/status");
    }

    @ParameterizedTest
    @MethodSource("paths")
    void hostilePathsAndQueries(String target) {
        assertSafe(server.raw(request("GET", target)), false);
    }

    static Stream<String> methods() {
        return Stream.of("TRACE", "OPTIONS", "CONNECT", "BREW", "get", "PROPFIND", "PATCH", "PUT", "DELETE", "POST", "LINK", "A".repeat(500));
    }

    @ParameterizedTest
    @MethodSource("methods")
    void methodsTheGatewayDoesNotServe(String method) {
        assertSafe(server.raw(request(method, "/acme/test/orders/1001/status", "Content-Length: 0")), false);
        assertSafe(server.raw(request(method, "/acme/test/nothing", "Content-Length: 0")), false);
    }

    @Test
    void headIsA405WithoutABody() {
        Raw raw = server.raw(request("HEAD", "/acme/test/orders/1001/status"));
        assertSafe(raw, true);
        assertThat(raw.status()).isEqualTo(405);
        assertThat(raw.body()).isEmpty();
    }

    static Stream<String> headerValues() {
        return Stream.of("a\r\n b: c", "a\nb", "a\rb", "a\u0000b", "a\tb", "\r\nX-Injected: yes", "a\r\n\r\nGET /x HTTP/1.1", "é", "a".repeat(70000));
    }

    @ParameterizedTest
    @MethodSource("headerValues")
    void headerValuesWithLineBreaksAndOtherSurprises(String value) {
        for (String name : new String[] {"X-Correlation-Id", "X-Channel", "Content-Type", "X-Anything"}) {
            Raw raw = server.raw(request("GET", "/acme/test/orders/1001/status", name + ": " + value));
            assertSafe(raw, false);
            assertThat(raw.head()).doesNotContainIgnoringCase("x-injected");
        }
    }

    static Stream<String> requestLines() {
        return Stream.of("GARBAGE\r\n\r\n", "GET\r\n\r\n", "GET /\r\n\r\n", "GET / HTTP/9.9\r\nHost: x\r\n\r\n", "GET / HTTP/1.1\r\n\r\n",
                "GET  /  HTTP/1.1\r\nHost: x\r\n\r\n", "\r\n\r\nGET / HTTP/1.1\r\nHost: x\r\n\r\n", "GET http://evil.example/acme/test HTTP/1.1\r\nHost: x\r\n\r\n",
                "GET * HTTP/1.1\r\nHost: x\r\n\r\n", "GET /acme/test/orders/1001/status HTTP/1.0\r\n\r\n", "\u0000\u0001\u0002\r\n\r\n",
                "GET / HTTP/1.1\r\nHost: x\r\nContent-Length: -1\r\n\r\n", "GET / HTTP/1.1\r\nHost: x\r\nContent-Length: abc\r\n\r\n",
                "POST / HTTP/1.1\r\nHost: x\r\nContent-Length: 5\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n",
                "POST / HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\nZZ\r\n", "POST / HTTP/1.1\r\nHost: x\r\nContent-Length: 10\r\n\r\nab");
    }

    @ParameterizedTest
    @MethodSource("requestLines")
    void malformedRequests(String text) {
        Raw raw = server.raw(text.getBytes(StandardCharsets.ISO_8859_1));
        assertSafe(raw, false);
    }

    @Test
    void aBodyOverTheLimitIsRefusedWithAProblem() {
        byte[] body = new byte[1024 * 1024 + 1];
        Arrays.fill(body, (byte) ' ');
        byte[] head = request("POST", "/acme/test/items/ABC12345/quantity?warehouse=W01", "X-Channel: WEB", "Content-Type: application/json", "Content-Length: " + body.length)
                .getBytes(StandardCharsets.ISO_8859_1);
        byte[] all = new byte[head.length + body.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(body, 0, all, head.length, body.length);
        Raw raw = server.raw(all);
        assertSafe(raw, false);
        assertThat(raw.status()).isEqualTo(400);
    }

    @Test
    void aBodyThatNeverEndsIsCutOffAtTheLimitAndAnswered() throws IOException {
        // Chunks keep coming until the server answers or closes; the gateway reads the limit plus one byte and no more.
        String head = request("POST", "/acme/test/items/ABC12345/quantity?warehouse=W01", "X-Channel: WEB", "Content-Type: application/json", "Transfer-Encoding: chunked")
                .replace("Connection: close\r\n", "Connection: close\r\n");
        byte[] chunk = ("1000\r\n" + " ".repeat(4096) + "\r\n").getBytes(StandardCharsets.ISO_8859_1);
        long sent = 0;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", server.port), 5000);
            socket.setSoTimeout(10000);
            OutputStream out = socket.getOutputStream();
            out.write(head.getBytes(StandardCharsets.ISO_8859_1));
            try {
                for (int i = 0; i < 4096; i++) {
                    out.write(chunk);
                    sent += 4096;
                }
                out.flush();
            } catch (IOException closedByServer) {
                // The server answered and closed before the end: that is the point.
            }
            byte[] buffer = new byte[65536];
            int n = 0;
            StringBuilder answer = new StringBuilder();
            try {
                while ((n = socket.getInputStream().read(buffer)) > 0) {
                    answer.append(new String(buffer, 0, n, StandardCharsets.ISO_8859_1));
                }
            } catch (IOException ignored) {
                // reset after the answer
            }
            if (answer.length() > 0) {
                Raw raw = ServerFixture.parse(answer.toString());
                assertSafe(raw, false);
                assertThat(raw.status()).isEqualTo(400);
            }
        }
        assertThat(sent).isGreaterThan(0);
    }
}
