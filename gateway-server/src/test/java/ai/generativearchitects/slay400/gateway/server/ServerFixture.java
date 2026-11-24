package ai.generativearchitects.slay400.gateway.server;

import ai.generativearchitects.slay400.gateway.Fixtures;
import ai.generativearchitects.slay400.gateway.load.GatewayLoader;
import ai.generativearchitects.slay400.gateway.load.StartResult;
import io.helidon.webserver.WebServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** A running server on a free port with the {@code shop} fixture, and a raw HTTP client that rewrites nothing. */
final class ServerFixture implements AutoCloseable {

    /** What came back. Header names are lower-cased. */
    record Raw(int status, Map<String, String> headers, String body, String head) {
    }

    final int port;
    final List<String> log = new CopyOnWriteArrayList<>();
    private final WebServer server;

    ServerFixture(Path dir) {
        this(dir, ServerLimits.DEFAULTS);
    }

    ServerFixture(Path dir, ServerLimits limits) {
        this.port = freePort();
        // A free port for the management listener too, so that no test needs its default port.
        String config = Fixtures.SHOP_CONFIG.replace("tenants:", "server:\n  port: " + port + "\nmanagement:\n  port: " + freePort() + "\ntenants:");
        StartResult result = Fixtures.start(dir, config, Map.of(), log);
        server = HelidonServer.start(result.started().orElseThrow(() -> new IllegalStateException(result.diagnostics().toString())), limits);
        // The server's own access lines go to the same list as the pipeline's.
    }

    static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    /** Sends the bytes as they are and reads the answer until the server closes the connection. */
    Raw raw(String request) {
        return raw(request.getBytes(StandardCharsets.ISO_8859_1));
    }

    Raw raw(byte[] request) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            socket.setSoTimeout(10000);
            socket.getOutputStream().write(request);
            socket.getOutputStream().flush();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            InputStream in = socket.getInputStream();
            byte[] buffer = new byte[8192];
            try {
                int n;
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                }
            } catch (IOException ignored) {
                // The server may reset the connection after it answered.
            }
            return parse(out.toString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Raw parse(String response) {
        int split = response.indexOf("\r\n\r\n");
        String head = split < 0 ? response : response.substring(0, split);
        String body = split < 0 ? "" : response.substring(split + 4);
        String[] lines = head.split("\r\n");
        int status = lines.length > 0 && lines[0].startsWith("HTTP/") ? Integer.parseInt(lines[0].split(" ")[1]) : -1;
        Map<String, String> headers = new TreeMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) {
                headers.put(lines[i].substring(0, colon).toLowerCase(Locale.ROOT), lines[i].substring(colon + 1).trim());
            }
        }
        return new Raw(status, headers, body, head);
    }

    @Override
    public void close() {
        server.stop();
    }
}
