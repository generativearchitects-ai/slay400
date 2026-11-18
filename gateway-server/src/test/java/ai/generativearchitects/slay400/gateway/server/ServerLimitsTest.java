package ai.generativearchitects.slay400.gateway.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.generativearchitects.slay400.gateway.LogCapture;
import ai.generativearchitects.slay400.gateway.pipeline.ClientGoneException;
import ai.generativearchitects.slay400.gateway.server.ServerFixture.Raw;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The limits of the HTTP server: each one set explicitly, and seen to act. Time limits are shortened for the tests, sizes are
 * the ones the gateway runs with.
 */
class ServerLimitsTest {

    @TempDir
    Path dir;

    private static final String ITEMS = "POST /acme/test/items/ABC12345/quantity?warehouse=W01 HTTP/1.1\r\nHost: x\r\nX-Channel: WEB\r\nContent-Type: application/json\r\n";

    /** What a connection received until it ended or {@code timeoutMs} passed, and whether it ended. */
    record Received(String text, boolean ended, long millis) {
    }

    static Received receive(Socket socket, int timeoutMs) throws IOException {
        socket.setSoTimeout(timeoutMs);
        long started = System.nanoTime();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        InputStream in = socket.getInputStream();
        byte[] buffer = new byte[8192];
        boolean ended;
        try {
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            ended = true;
        } catch (SocketTimeoutException e) {
            ended = false;
        } catch (IOException reset) {
            ended = true;
        }
        return new Received(out.toString(StandardCharsets.ISO_8859_1), ended, (System.nanoTime() - started) / 1_000_000);
    }

    static Socket connect(ServerFixture server) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", server.port), 5000);
        return socket;
    }

    static ServerLimits withTimes(Duration read, Duration idle, Duration period, int connections) {
        ServerLimits d = ServerLimits.DEFAULTS;
        return new ServerLimits(d.maxPayloadBytes(), d.maxRequestLineBytes(), d.maxHeadersBytes(), read, idle, period, connections);
    }

    @Test
    void theDefaultsAreTheDocumentedValues() {
        assertThat(ServerLimits.DEFAULTS).isEqualTo(new ServerLimits(2_097_152, 8192, 16_384, Duration.ofSeconds(30), Duration.ofSeconds(60),
                Duration.ofSeconds(5), 1000));
        assertThatThrownBy(() -> new ServerLimits(0, 1, 1, Duration.ZERO, Duration.ZERO, Duration.ZERO, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ServerLimits(1, 0, 1, Duration.ZERO, Duration.ZERO, Duration.ZERO, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ServerLimits(1, 1, 0, Duration.ZERO, Duration.ZERO, Duration.ZERO, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ServerLimits(1, 1, 1, Duration.ZERO, Duration.ZERO, Duration.ZERO, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aBodyDeclaredOverTheServerLimitIs413BeforeAnythingIsRead() throws IOException {
        try (ServerFixture server = new ServerFixture(dir); Socket socket = connect(server)) {
            // No body is sent: the answer comes without waiting for it.
            socket.getOutputStream().write((ITEMS + "Content-Length: " + (ServerLimits.DEFAULTS.maxPayloadBytes() + 1) + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            Received received = receive(socket, 5000);
            Raw raw = ServerFixture.parse(received.text());
            assertThat(raw.status()).isEqualTo(413);
            assertThat(raw.headers().get("content-type")).isEqualTo("application/problem+json");
            assertThat(received.ended()).isTrue();
            assertThat(server.log).hasSize(1);
            assertThat(server.log.get(0)).contains("\"status\":413").doesNotContain("\"tenant\"");
        }
    }

    @Test
    void theRestOfABodyOverThePipelineLimitIsReadAndDiscardedAndTheConnectionIsKept() throws IOException {
        try (ServerFixture server = new ServerFixture(dir); Socket socket = connect(server)) {
            int length = 1536 * 1024;
            OutputStream out = socket.getOutputStream();
            out.write((ITEMS + "Content-Length: " + length + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            byte[] body = new byte[length];
            Arrays.fill(body, (byte) ' ');
            out.write(body);
            out.write("GET /health/live HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            String text = receive(socket, 5000).text();
            assertThat(text).startsWith("HTTP/1.1 400 ").contains("HTTP/1.1 200 ").endsWith("{\"status\":\"ok\"}");
        }
    }

    @Test
    void aChunkedBodyThatNeverEndsIsAnsweredAndTheConnectionEndsSoonAfterTheServerLimit() throws IOException {
        try (ServerFixture server = new ServerFixture(dir); Socket socket = connect(server)) {
            OutputStream out = socket.getOutputStream();
            out.write((ITEMS + "Transfer-Encoding: chunked\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            byte[] chunk = ("1000\r\n" + " ".repeat(4096) + "\r\n").getBytes(StandardCharsets.ISO_8859_1);
            long sent = 0;
            try {
                for (int i = 0; i < 16 * 256; i++) {
                    out.write(chunk);
                    sent += 4096;
                }
            } catch (IOException closedByServer) {
                // expected
            }
            // Socket buffers hold a few MiB on top of what the server read.
            assertThat(sent).isLessThan(16L * 1024 * 1024);
            assertThat(receive(socket, 5000).text()).startsWith("HTTP/1.1 400 ");
        }
    }

    @Test
    void theRequestLineIsShorterThan8192Bytes() throws IOException {
        try (ServerFixture server = new ServerFixture(dir)) {
            String start = "GET /acme/test/";
            String end = " HTTP/1.1";
            // Helidon counts the line without its line break and accepts fewer bytes than the limit.
            Raw within = server.raw(start + "a".repeat(8191 - start.length() - end.length()) + end + "\r\nHost: x\r\nConnection: close\r\n\r\n");
            assertThat(within.status()).isEqualTo(404);
            Raw over = server.raw(start + "a".repeat(8192 - start.length() - end.length()) + end + "\r\nHost: x\r\nConnection: close\r\n\r\n");
            assertThat(over.status()).isEqualTo(414);
        }
    }

    @Test
    void headersMayBe16KiBTogether() throws IOException {
        try (ServerFixture server = new ServerFixture(dir)) {
            Raw within = server.raw("GET /health/live HTTP/1.1\r\nHost: x\r\nConnection: close\r\nX-Big: " + "a".repeat(15 * 1024) + "\r\n\r\n");
            assertThat(within.status()).isEqualTo(200);
            Raw over = server.raw("GET /health/live HTTP/1.1\r\nHost: x\r\nConnection: close\r\nX-Big: " + "a".repeat(17 * 1024) + "\r\n\r\n");
            assertThat(over.status()).isEqualTo(400);
        }
    }

    @Test
    void aBodyThatStopsArrivingEndsTheConnectionAtTheDeadlineAsClientGone() throws Exception {
        try (LogCapture operator = new LogCapture("slay400.gateway");
             ServerFixture server = new ServerFixture(dir, withTimes(Duration.ofMillis(500), Duration.ofSeconds(60), Duration.ofSeconds(5), 1000));
             Socket socket = connect(server)) {
            socket.getOutputStream().write((ITEMS + "Content-Length: 100\r\n\r\n{\"quantity\":").getBytes(StandardCharsets.ISO_8859_1));
            Received received = receive(socket, 10_000);
            assertThat(received.ended()).isTrue();
            assertThat(received.millis()).isLessThan(9_000);
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (server.log.isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertThat(server.log).hasSize(1);
            assertThat(server.log.get(0)).contains("\"problems\":[\"client-gone\"]").containsPattern("\"durationMs\":([5-9][0-9]{2}|[0-9]{4})[,}]");
            assertThat(operator.lines()).singleElement().satisfies(line -> {
                assertThat(line.level()).isEqualTo(Level.INFO);
                assertThat(line.thrown()).isNull();
            });
        }
    }

    @Test
    void knownGapHelidonDoesNotTimeOutHeadersThatStopArriving() throws IOException {
        // Helidon 4.5.7 does not apply its read time-out. When this test fails, Helidon has started to, and ServerLimits can say so.
        try (ServerFixture server = new ServerFixture(dir, withTimes(Duration.ofMillis(300), Duration.ofSeconds(60), Duration.ofSeconds(5), 1000));
             Socket socket = connect(server)) {
            socket.getOutputStream().write("GET /health/live HTTP/1.1\r\nHost: x\r\n".getBytes(StandardCharsets.ISO_8859_1));
            assertThat(receive(socket, 1500).ended()).isFalse();
        }
    }

    @Test
    void anIdleConnectionIsClosedAfterTheIdleTimeout() throws IOException {
        try (ServerFixture server = new ServerFixture(dir, withTimes(Duration.ofSeconds(30), Duration.ofSeconds(1), Duration.ofMillis(200), 1000));
             Socket socket = connect(server)) {
            socket.getOutputStream().write("GET /health/live HTTP/1.1\r\nHost: x\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            Received received = receive(socket, 10_000);
            assertThat(received.text()).startsWith("HTTP/1.1 200 ");
            assertThat(received.ended()).isTrue();
            assertThat(received.millis()).isLessThan(5_000);
        }
    }

    @Test
    void aConnectionOverTheLimitWaitsUntilAnotherCloses() throws Exception {
        try (ServerFixture server = new ServerFixture(dir, withTimes(Duration.ofSeconds(30), Duration.ofSeconds(60), Duration.ofSeconds(5), 2))) {
            Socket first = connect(server);
            Socket second = connect(server);
            try (Socket third = connect(server)) {
                third.getOutputStream().write("GET /health/live HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                assertThat(receive(third, 700).text()).isEmpty();
                first.close();
                assertThat(receive(third, 5_000).text()).startsWith("HTTP/1.1 200 ");
            } finally {
                first.close();
                second.close();
            }
        }
    }

    // ---- the deadline itself, without HTTP

    @Test
    void aReadThatDoesNotFinishInTimeIsClientGoneAndItsThreadIsInterrupted() throws InterruptedException {
        CountDownLatch interrupted = new CountDownLatch(1);
        assertThatThrownBy(() -> HelidonServer.readWithDeadline(() -> {
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                interrupted.countDown();
            }
            return new byte[0];
        }, Duration.ofMillis(100))).isInstanceOf(ClientGoneException.class);
        assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void aReadThatFailsIsClientGoneAndOneThatFinishesGivesItsBytes() {
        assertThatThrownBy(() -> HelidonServer.readWithDeadline(() -> {
            throw new IOException("connection reset");
        }, Duration.ofSeconds(5))).isInstanceOf(ClientGoneException.class);
        assertThatThrownBy(() -> HelidonServer.readWithDeadline(() -> {
            throw new IllegalStateException("insufficient data");
        }, Duration.ofSeconds(5))).isInstanceOf(ClientGoneException.class);
        assertThat(HelidonServer.readWithDeadline(() -> new byte[] {1, 2}, Duration.ofSeconds(5))).containsExactly(1, 2);
    }

    @Test
    void aCallerThatIsInterruptedWhileWaitingIsClientGoneAndKeepsItsInterrupt() throws InterruptedException {
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> HelidonServer.readWithDeadline(() -> {
                Thread.sleep(60_000);
                return new byte[0];
            }, Duration.ofSeconds(5))).isInstanceOf(ClientGoneException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }
}
