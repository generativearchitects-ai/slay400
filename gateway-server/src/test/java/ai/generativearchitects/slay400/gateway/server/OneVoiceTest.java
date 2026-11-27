package ai.generativearchitects.slay400.gateway.server;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.slay400.gateway.Fixtures;
import ai.generativearchitects.slay400.tasks.BundleRequest;
import ai.generativearchitects.slay400.tasks.TaskResult;
import ai.generativearchitects.slay400.tasks.Tasks;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every line the running gateway writes, on either stream, is one JSON line (kickoff 11, A2). The real {@code Main} runs in a
 * child JVM; the test drives a start, calls of every outcome, refusals of the listener of every kind, a reload, a refused reload
 * and a stop on SIGTERM, then parses every line of standard output and standard error.
 */
class OneVoiceTest {

    @TempDir
    Path dir;

    @Test
    void noLineOfTheRunningGatewayFailsToParseAsJson() throws Exception {
        Path bundles = Files.createDirectories(dir.resolve("bundles"));
        Fixtures.bundle(bundles, "shop");
        Path next = bundle("1.1.0");
        int[] ports = ServerFixture.freePorts(2);
        Files.writeString(dir.resolve("config.yaml"), "server: { port: " + ports[0] + " }\nmanagement: { port: " + ports[1] + " }\nreload:\n  intervalSeconds: 1\n"
                + "tenants:\n  acme:\n    bundles: [./bundles]\n    environments:\n      test: { code: T0001, backend: mock }\n");
        Path out = dir.resolve("stdout.log");
        Path err = dir.resolve("stderr.log");
        Process gateway = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", System.getProperty("java.class.path"),
                Main.class.getName(), "run", "--config", dir.resolve("config.yaml").toString())
                .redirectOutput(out.toFile()).redirectError(err.toFile()).start();
        try {
            awaitLine(out, "\"event\":\"started\"");
            // Calls of every outcome, a hostile path among them.
            for (String path : List.of("/acme/test/orders/1001/status", "/acme/test/orders/9999/status", "/acme/test/orders/4/status", "/acme/test/orders/6/status",
                    "/acme/test/orders/7/status", "/nowhere/x", "/acme/test/%0A%22%7D%7B%22level%22:%22error%22", "/health/live", "/health/ready")) {
                send(ports[0], "GET " + path + " HTTP/1.1\r\nHost: x\r\nX-Correlation-Id: \"}{\r\nConnection: close\r\n\r\n");
            }
            send(ports[0], "DELETE /acme/test/orders/1001/status HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n");
            send(ports[0], "POST /acme/test/items/ABC12345/quantity?warehouse=W01 HTTP/1.1\r\nHost: x\r\nX-Channel: WEB\r\nContent-Type: application/json\r\n"
                    + "Content-Length: 5\r\nConnection: close\r\n\r\n{\"q\":");
            send(ports[1], "GET /metrics HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n");
            // What the listener refuses by itself.
            send(ports[0], "POST /acme/test/items/ABC12345/quantity HTTP/1.1\r\nHost: x\r\nContent-Length: " + (ServerLimits.DEFAULTS.maxPayloadBytes() + 1)
                    + "\r\nConnection: close\r\n\r\n");
            send(ports[0], "GET /acme/test/" + "a".repeat(9000) + " HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n");
            send(ports[0], "GET /acme/test/orders/1001/status HTTP/1.1\r\nHost: x\r\nX-Big: " + "a".repeat(70000) + "\r\nConnection: close\r\n\r\n");
            send(ports[0], "NOT A REQUEST\r\n\r\n");
            // A reload, then a refused reload.
            Files.copy(next, bundles.resolve("shop-1.1.0.rpgapi.part"));
            Files.move(bundles.resolve("shop-1.1.0.rpgapi.part"), bundles.resolve("shop-1.0.0.rpgapi"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            awaitLine(out, "\"event\":\"reloaded\"");
            Files.writeString(bundles.resolve("broken.rpgapi"), "not a bundle");
            awaitLine(err, "cannot be served");
        } finally {
            gateway.destroy();
            assertThat(gateway.waitFor(30, TimeUnit.SECONDS)).isTrue();
        }
        List<String> stdout = Files.readAllLines(out, StandardCharsets.UTF_8);
        List<String> stderr = Files.readAllLines(err, StandardCharsets.UTF_8);
        List<String> broken = new ArrayList<>();
        for (String line : stdout) {
            if (!Json.isObject(line)) {
                broken.add("stdout: " + line);
            }
        }
        for (String line : stderr) {
            if (!Json.isObject(line)) {
                broken.add("stderr: " + line);
            }
        }
        assertThat(broken).as("lines that are not one JSON object").isEmpty();
        assertThat(String.join("\n", stdout)).contains("\"event\":\"started\"").contains("\"event\":\"reloaded\"").contains("\"event\":\"stopping\"")
                .contains("\"event\":\"stopped\"").contains("\"status\":422").contains("\"status\":413").contains("\"status\":414");
        assertThat(String.join("\n", stderr)).contains("\"logger\":\"io.helidon.").contains("\"level\":\"warning\",\"logger\":\"slay400.gateway\"")
                .doesNotContain("There is no Helidon logging implementation");
        // Nothing of the hostile request reached a line as a value.
        assertThat(String.join("\n", stdout) + String.join("\n", stderr)).doesNotContain("\"level\":\"error\",\"x\"").doesNotContain("%0A%22");
    }

    private Path bundle(String version) {
        Path target = dir.resolve("shop-" + version + ".rpgapi");
        TaskResult result = new Tasks(dir).bundle(Fixtures.root().resolve("shop"), new BundleRequest("shop", version, target, Optional.empty(), Optional.empty()));
        if (result.hasError()) {
            throw new IllegalStateException(result.diagnostics().toString());
        }
        return target;
    }

    private static void awaitLine(Path file, String text) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < until) {
            if (Files.exists(file) && Files.readString(file, StandardCharsets.UTF_8).contains(text)) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("no line with " + text + " in " + file.getFileName() + ":\n" + (Files.exists(file) ? Files.readString(file) : ""));
    }

    /** Sends the bytes as they are and reads until the server closes the connection. */
    private static void send(int port, String request) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            socket.setSoTimeout(10000);
            socket.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            ByteArrayOutputStream ignored = new ByteArrayOutputStream();
            InputStream in = socket.getInputStream();
            byte[] buffer = new byte[8192];
            try {
                int n;
                while ((n = in.read(buffer)) > 0) {
                    ignored.write(buffer, 0, n);
                }
            } catch (IOException e) {
                // The server may reset the connection after it answered.
            }
        }
    }

    /** A strict check of one JSON object per line: RFC 8259, no raw control character in a string, nothing after it. */
    static final class Json {
        private final String text;
        private int at;

        private Json(String text) {
            this.text = text;
        }

        static boolean isObject(String line) {
            Json json = new Json(line);
            try {
                json.space();
                if (json.peek() != '{') {
                    return false;
                }
                json.value();
                json.space();
                return json.at == line.length();
            } catch (IllegalArgumentException e) {
                return false;
            }
        }

        private char peek() {
            if (at >= text.length()) {
                throw new IllegalArgumentException("end");
            }
            return text.charAt(at);
        }

        private void expect(char c) {
            if (peek() != c) {
                throw new IllegalArgumentException("expected " + c);
            }
            at++;
        }

        private void space() {
            while (at < text.length() && " \t".indexOf(text.charAt(at)) >= 0) {
                at++;
            }
        }

        private void value() {
            space();
            char c = peek();
            switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> word("true");
                case 'f' -> word("false");
                case 'n' -> word("null");
                default -> number();
            }
            space();
        }

        private void object() {
            expect('{');
            space();
            if (peek() == '}') {
                at++;
                return;
            }
            while (true) {
                space();
                string();
                space();
                expect(':');
                value();
                if (peek() == ',') {
                    at++;
                    continue;
                }
                expect('}');
                return;
            }
        }

        private void array() {
            expect('[');
            space();
            if (peek() == ']') {
                at++;
                return;
            }
            while (true) {
                value();
                if (peek() == ',') {
                    at++;
                    continue;
                }
                expect(']');
                return;
            }
        }

        private void string() {
            expect('"');
            while (true) {
                char c = peek();
                at++;
                if (c == '"') {
                    return;
                }
                if (c < 0x20) {
                    throw new IllegalArgumentException("control character");
                }
                if (c == '\\') {
                    char e = peek();
                    at++;
                    if (e == 'u') {
                        for (int i = 0; i < 4; i++) {
                            if (Character.digit(peek(), 16) < 0) {
                                throw new IllegalArgumentException("escape");
                            }
                            at++;
                        }
                    } else if ("\"\\/bfnrt".indexOf(e) < 0) {
                        throw new IllegalArgumentException("escape");
                    }
                }
            }
        }

        private void word(String word) {
            if (!text.startsWith(word, at)) {
                throw new IllegalArgumentException("word");
            }
            at += word.length();
        }

        private void number() {
            int start = at;
            if (peek() == '-') {
                at++;
            }
            while (at < text.length() && "0123456789.eE+-".indexOf(text.charAt(at)) >= 0) {
                at++;
            }
            if (at == start) {
                throw new IllegalArgumentException("value");
            }
        }
    }
}
