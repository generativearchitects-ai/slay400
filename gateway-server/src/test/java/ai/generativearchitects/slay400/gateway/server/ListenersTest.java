package ai.generativearchitects.slay400.gateway.server;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.slay400.gateway.Fixtures;
import ai.generativearchitects.slay400.gateway.LogCapture;
import ai.generativearchitects.slay400.gateway.TestCertificates;
import ai.generativearchitects.slay400.gateway.load.StartResult;
import ai.generativearchitects.slay400.gateway.load.Started;
import io.helidon.webserver.WebServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.stream.Collectors;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The API listener with TLS, its rotation, and the management listener (kickoff 08, Part A). */
class ListenersTest {

    @TempDir
    Path dir;

    private final List<String> access = new CopyOnWriteArrayList<>();
    private WebServer server;
    private Started started;
    private int port;
    private int management;
    private long tick = Instant.parse("2026-11-01T00:00:00Z").getEpochSecond();

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop();
        }
        if (started != null) {
            started.close();
        }
    }

    /** Starts the shop fixture with these sections before {@code tenants}; {@code MANAGEMENT} stands for a free port. */
    private void start(String server, String managementSection) {
        int[] ports = ServerFixture.freePorts(2);
        port = ports[0];
        management = ports[1];
        String config = "server:\n  port: " + port + "\n" + server + managementSection.replace("MANAGEMENT", String.valueOf(management)) + Fixtures.SHOP_CONFIG;
        StartResult result = Fixtures.start(dir, config, Map.of(), access);
        started = result.started().orElseThrow(() -> new IllegalStateException(result.diagnostics().toString()));
        this.server = HelidonServer.start(started);
    }

    private static HttpResponse<String> get(HttpClient client, String url) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Copies a certificate and a key over the configured files, with later times of last change. */
    private void install(TestCertificates.Pair certificate, TestCertificates.Pair key) throws IOException {
        Files.copy(certificate.certificateFile(), dir.resolve("tls.crt"), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(key.keyFile(), dir.resolve("tls.key"), StandardCopyOption.REPLACE_EXISTING);
        Files.setLastModifiedTime(dir.resolve("tls.crt"), FileTime.from(Instant.ofEpochSecond(++tick)));
        Files.setLastModifiedTime(dir.resolve("tls.key"), FileTime.from(Instant.ofEpochSecond(++tick)));
    }

    /** The serial number of the certificate the API listener presents to a new connection. */
    private BigInteger presented() throws Exception {
        SSLContext trustAll = SSLContext.getInstance("TLS");
        trustAll.init(null, new TrustManager[] {new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String type) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String type) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }}, null);
        try (SSLSocket socket = (SSLSocket) trustAll.getSocketFactory().createSocket("127.0.0.1", port)) {
            socket.setSoTimeout(10_000);
            socket.startHandshake();
            return ((X509Certificate) socket.getSession().getPeerCertificates()[0]).getSerialNumber();
        }
    }

    private void settle() {
        started.reloader().pollAll();
        started.reloader().pollAll();
    }

    private static final String TLS = "  tls: { certificate: tls.crt, key: tls.key }\n";
    private static final String MANAGEMENT = "management:\n  port: MANAGEMENT\n";

    // ---- TLS

    @Test
    void withTlsTheListenerSpeaksHttpsOnly() throws Exception {
        TestCertificates.Pair pair = TestCertificates.rsa(dir, "first", 365);
        install(pair, pair);
        start(TLS, MANAGEMENT);
        HttpClient client = HttpClient.newBuilder().sslContext(pair.trustingContext()).connectTimeout(Duration.ofSeconds(5)).build();
        HttpResponse<String> response = get(client, "https://127.0.0.1:" + port + "/acme/test/orders/1001/status");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"S\"");
        assertThat(response.sslSession().orElseThrow().getProtocol()).isIn("TLSv1.3", "TLSv1.2");
        // Plain HTTP on the same port gets no HTTP answer.
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            socket.setSoTimeout(5000);
            socket.getOutputStream().write("GET /health/live HTTP/1.1\r\nHost: x\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            assertThat(new String(readSome(socket.getInputStream()), StandardCharsets.ISO_8859_1)).doesNotStartWith("HTTP/");
        }
        // TLS 1.2 is spoken; TLS 1.1 is refused: no ServerHello comes back.
        assertThat(handshake(pair, "TLSv1.2")).isEqualTo("TLSv1.2");
        assertThat(clientHelloTls11()).isNotEqualTo(0x16);
    }

    /** The protocol a client gets that offers only this one. */
    private String handshake(TestCertificates.Pair pair, String protocol) throws Exception {
        try (SSLSocket socket = (SSLSocket) pair.trustingContext().getSocketFactory().createSocket("127.0.0.1", port)) {
            socket.setEnabledProtocols(new String[] {protocol});
            socket.setSoTimeout(10_000);
            socket.startHandshake();
            return socket.getSession().getProtocol();
        }
    }

    /**
     * Sends a TLS 1.1 ClientHello written by hand (the JDK's client no longer offers TLS 1.1) and returns the type of the first
     * record of the answer: 0x16 would be a handshake (a ServerHello), 0x15 is an alert, -1 a closed connection.
     */
    private int clientHelloTls11() throws IOException {
        String body = "0302" + "00".repeat(32) + "00" + "0002002f" + "0100";
        String handshake = "01" + String.format("%06x", body.length() / 2) + body;
        String record = "160301" + String.format("%04x", handshake.length() / 2) + handshake;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            out.write(HexFormat.of().parseHex(record));
            out.flush();
            try {
                return socket.getInputStream().read();
            } catch (IOException e) {
                return -1;
            }
        }
    }

    private static byte[] readSome(InputStream in) {
        try {
            byte[] buffer = new byte[64];
            int read = in.read(buffer);
            return read <= 0 ? new byte[0] : java.util.Arrays.copyOf(buffer, read);
        } catch (IOException e) {
            return new byte[0];
        }
    }

    @Test
    void aNewPairReplacesTheOldForNewConnectionsAPairThatDoesNotMatchIsRefusedAndTheOldOneComesBack() throws Exception {
        TestCertificates.Pair first = TestCertificates.rsa(dir, "first", 365);
        TestCertificates.Pair second = TestCertificates.ec(dir, "second", 365);
        TestCertificates.Pair third = TestCertificates.rsa(dir, "third", 365);
        install(first, first);
        start(TLS, MANAGEMENT);
        assertThat(presented()).isEqualTo(first.certificate().getSerialNumber());
        try (LogCapture log = new LogCapture("slay400.gateway")) {
            // A new pair: seen once, nothing; seen twice, taken.
            install(second, second);
            started.reloader().pollAll();
            assertThat(presented()).isEqualTo(first.certificate().getSerialNumber());
            started.reloader().pollAll();
            assertThat(presented()).isEqualTo(second.certificate().getSerialNumber());
            assertThat(log.lines()).filteredOn(l -> l.level() == Level.INFO).extracting(LogCapture.Line::message)
                    .anyMatch(m -> m.startsWith("The TLS certificate of the API listener was replaced for new connections"));
            // A certificate with the key of another: refused once, the pair in use stays.
            install(third, second);
            settle();
            settle();
            List<String> warnings = log.lines().stream().filter(l -> l.level() == Level.WARNING).map(LogCapture.Line::message).toList();
            assertThat(warnings).hasSize(1);
            assertThat(warnings.get(0)).startsWith("The TLS certificate and key changed but cannot be used; the listener keeps the pair in use.").contains("GWY023");
            assertThat(presented()).isEqualTo(second.certificate().getSerialNumber());
            // And back to the first pair.
            install(first, first);
            settle();
            assertThat(presented()).isEqualTo(first.certificate().getSerialNumber());
            String all = log.lines().stream().map(LogCapture.Line::message).collect(Collectors.joining("\n")) + String.join("\n", access);
            for (TestCertificates.Pair pair : List.of(first, second, third)) {
                assertThat(all).doesNotContain(pair.keyText().substring(0, 40));
            }
        }
    }

    // ---- management

    @Test
    void theManagementListenerAnswersHealthOnlyAndTheApiListenerNoMetrics() throws Exception {
        start("", MANAGEMENT);
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        String managementUrl = "http://127.0.0.1:" + management;
        assertThat(get(client, managementUrl + "/health/live").statusCode()).isEqualTo(200);
        assertThat(get(client, managementUrl + "/health/ready").body()).isEqualTo("{\"status\":\"ok\"}");
        // No operation on the management listener.
        HttpResponse<String> operation = get(client, managementUrl + "/acme/test/orders/1001/status");
        assertThat(operation.statusCode()).isEqualTo(404);
        assertThat(operation.headers().firstValue("content-type")).hasValue("application/problem+json");
        // The API listener still answers the health paths, and no metrics.
        assertThat(get(client, "http://127.0.0.1:" + port + "/health/live").statusCode()).isEqualTo(200);
        assertThat(get(client, "http://127.0.0.1:" + port + "/metrics").statusCode()).isEqualTo(404);
        assertThat(server.port(HelidonServer.MANAGEMENT)).isEqualTo(management);
    }

    @Test
    void aManagementPortOfZeroTurnsTheListenerOff() {
        start("", "management:\n  port: 0\n");
        assertThat(server.port(HelidonServer.MANAGEMENT)).isLessThanOrEqualTo(0);
        assertThat(server.port()).isEqualTo(port);
    }
}
