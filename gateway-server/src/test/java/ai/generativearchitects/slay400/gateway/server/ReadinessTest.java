package ai.generativearchitects.slay400.gateway.server;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.slay400.gateway.Fixtures;
import ai.generativearchitects.slay400.gateway.TestCertificates;
import ai.generativearchitects.slay400.gateway.load.StartResult;
import ai.generativearchitects.slay400.gateway.load.Started;
import io.helidon.webserver.WebServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code /health/ready} means that the API listener accepts connections (kickoff 11, A1): observed, not assumed. A probe asks
 * the management listener while the gateway starts; the moment it first hears 200, a call on the API listener must succeed. And
 * from the moment a stop begins, ready is 503 while a request in flight still finishes.
 */
class ReadinessTest {

    private static final int STARTS = 8;
    private static final String TLS = "  tls: { certificate: tls/tls.crt, key: tls/tls.key }\n";

    @TempDir
    Path dir;

    private final List<String> access = new CopyOnWriteArrayList<>();

    @Test
    void readyOnTheManagementPortNeverComesBeforeTheApiListenerAccepts() throws Exception {
        for (int i = 0; i < STARTS; i++) {
            observeAStart("", HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(), "http");
        }
    }

    @Test
    void withTlsToo() throws Exception {
        TestCertificates.Pair pair = TestCertificates.rsa(dir.resolve("tls"), "tls", 365);
        SSLContext trusting = pair.trustingContext();
        for (int i = 0; i < STARTS; i++) {
            observeAStart(TLS, HttpClient.newBuilder().sslContext(trusting).connectTimeout(Duration.ofSeconds(2)).build(), "https");
        }
    }

    @Test
    void fromTheMomentAStopBeginsReadyIs503WhileARequestInFlightFinishes() throws Exception {
        int[] ports = ServerFixture.freePorts(2);
        Started started = start(ports, "");
        WebServer server = HelidonServer.start(started);
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        try {
            assertThat(status(client, "http://127.0.0.1:" + ports[1] + "/health/ready")).isEqualTo(200);
            // Order 5 of the shop mock answers after 300 ms.
            CompletableFuture<HttpResponse<String>> slow = client.sendAsync(get("http://127.0.0.1:" + ports[0] + "/acme/test/orders/5/status"),
                    HttpResponse.BodyHandlers.ofString());
            Thread.sleep(100);
            // The first thing a stop does.
            started.gateway().ready(false);
            assertThat(status(client, "http://127.0.0.1:" + ports[1] + "/health/ready")).isEqualTo(503);
            assertThat(status(client, "http://127.0.0.1:" + ports[0] + "/health/ready")).isEqualTo(503);
            assertThat(status(client, "http://127.0.0.1:" + ports[1] + "/health/live")).isEqualTo(200);
            assertThat(slow.isDone()).isFalse();
            HttpResponse<String> answer = slow.get(5, TimeUnit.SECONDS);
            assertThat(answer.statusCode()).isEqualTo(200);
            assertThat(answer.body()).contains("\"status\":\"P\"");
        } finally {
            HelidonServer.stop(server, started.gateway());
            started.close();
        }
        assertThat(started.gateway().ready()).isFalse();
    }

    @Test
    void theStopOfTheServerClearsReadinessBeforeTheListenersStop() throws Exception {
        int[] ports = ServerFixture.freePorts(2);
        Started started = start(ports, "");
        WebServer server = HelidonServer.start(started);
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        CompletableFuture<HttpResponse<String>> slow = client.sendAsync(get("http://127.0.0.1:" + ports[0] + "/acme/test/orders/5/status"),
                HttpResponse.BodyHandlers.ofString());
        Thread.sleep(100);
        AtomicBoolean readyWhileStopping = new AtomicBoolean(true);
        Thread stopper = Thread.ofVirtual().start(() -> HelidonServer.stop(server, started.gateway()));
        // While the listeners wait for the request in flight, ready is already off.
        Thread.sleep(50);
        readyWhileStopping.set(started.gateway().ready());
        assertThat(slow.get(5, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        stopper.join(10_000);
        started.close();
        assertThat(readyWhileStopping).isFalse();
    }

    /** Starts the gateway while a probe asks the management listener; at its first 200, calls the API listener. */
    private void observeAStart(String serverSection, HttpClient api, String scheme) throws Exception {
        int[] ports = ServerFixture.freePorts(2);
        Started started = start(ports, serverSection);
        HttpClient probe = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(200)).build();
        List<Integer> heard = new ArrayList<>();
        CompletableFuture<Integer> callAtFirstReady = CompletableFuture.supplyAsync(() -> {
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (System.nanoTime() < until) {
                int status = status(probe, "http://127.0.0.1:" + ports[1] + "/health/ready");
                synchronized (heard) {
                    heard.add(status);
                }
                if (status == 200) {
                    return status(api, scheme + "://127.0.0.1:" + ports[0] + "/acme/test/orders/1001/status");
                }
            }
            return -2;
        });
        WebServer server = HelidonServer.start(started);
        try {
            assertThat(callAtFirstReady.get(30, TimeUnit.SECONDS)).as("the call on the API listener at the first 200 of the probe").isEqualTo(200);
            synchronized (heard) {
                // Before the first 200 the probe heard nothing (the port was closed) or 503, never anything else.
                assertThat(heard.subList(0, heard.size() - 1)).allMatch(status -> status == -1 || status == 503);
            }
        } finally {
            HelidonServer.stop(server, started.gateway());
            started.close();
        }
    }

    private Started start(int[] ports, String serverSection) throws Exception {
        Path folder = Files.createTempDirectory(dir, "start");
        if (!serverSection.isEmpty()) {
            Files.createDirectories(folder.resolve("tls"));
            Files.copy(dir.resolve("tls/tls.crt"), folder.resolve("tls/tls.crt"));
            Files.copy(dir.resolve("tls/tls.key"), folder.resolve("tls/tls.key"));
        }
        String config = "server:\n  port: " + ports[0] + "\n" + serverSection + "management:\n  port: " + ports[1] + "\n" + Fixtures.SHOP_CONFIG;
        StartResult result = Fixtures.start(folder, config, Map.of(), access);
        return result.started().orElseThrow(() -> new IllegalStateException(result.diagnostics().toString()));
    }

    private static HttpRequest get(String url) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build();
    }

    /** The status of a GET, or -1 when no connection could be made. */
    private static int status(HttpClient client, String url) {
        try {
            return client.send(get(url), HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (java.io.IOException e) {
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }
}
