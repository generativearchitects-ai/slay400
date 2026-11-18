package ai.generativearchitects.slay400.gateway.server;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.slay400.gateway.LogCapture;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.logging.Level;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A client that closes its connection in the middle of its body: one INFO line for the operator, no stack trace, one access line. */
class ClientGoneTest {

    @TempDir
    Path dir;

    @Test
    void aClientThatGoesAwayWhileItsBodyIsReadIsOneQuietLine() throws Exception {
        try (LogCapture operator = new LogCapture("slay400.gateway"); ServerFixture server = new ServerFixture(dir)) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", server.port), 5000);
                OutputStream out = socket.getOutputStream();
                out.write(("POST /acme/test/items/ABC12345/quantity?warehouse=W01 HTTP/1.1\r\nHost: x\r\nX-Channel: WEB\r\n"
                        + "Content-Type: application/json\r\nContent-Length: 1000\r\n\r\n{\"quantity\":").getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
            }
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (server.log.isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertThat(server.log).hasSize(1);
            assertThat(server.log.get(0)).contains("\"status\":400").contains("\"problems\":[\"client-gone\"]").contains("\"tenant\":\"acme\"");
            assertThat(operator.lines()).hasSize(1);
            assertThat(operator.lines().get(0).level()).isEqualTo(Level.INFO);
            assertThat(operator.lines().get(0).thrown()).isNull();
            assertThat(operator.text()).doesNotContain("Unexpected error");
        }
    }
}
