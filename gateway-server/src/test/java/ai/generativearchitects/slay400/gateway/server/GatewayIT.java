package ai.generativearchitects.slay400.gateway.server;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.slay400.gateway.Fixtures;
import ai.generativearchitects.slay400.gateway.ibmi.IbmiBackend;
import ai.generativearchitects.slay400.gateway.load.GatewayLoader;
import ai.generativearchitects.slay400.gateway.load.StartOptions;
import ai.generativearchitects.slay400.gateway.load.StartResult;
import ai.generativearchitects.slay400.gateway.load.Started;
import io.helidon.webserver.WebServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/**
 * The whole gateway over HTTP against a real IBM i, with a bundle built from the five test programs as the repository keeps
 * them (kickoff 03): a success for each shape, the business error of program 4 as its mapped status, the time-out of program
 * 5 as 504, a program that does not exist as 502, and a wrong library list as 502. One connection at a time: two gateways one
 * after the other, each with a pool of one. Eight program calls and two sign-ons.
 *
 * <p>Host, user, password and library come from the environment variables of the ibmi workflow, through {@code ${NAME}}
 * placeholders in the configuration file, and are printed nowhere.
 */
@EnabledIfEnvironmentVariable(named = "IBMI_HOST", matches = ".+")
@EnabledIfEnvironmentVariable(named = "IBMI_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "IBMI_PASSWORD", matches = ".+")
@EnabledIfEnvironmentVariable(named = "IBMI_LIBRARY", matches = ".+")
class GatewayIT {

    @TempDir
    Path dir;

    static String config(int port, String environment, String libraryList) {
        return """
                server:
                  port: %d
                tenants:
                  acme:
                    bundles: [./ibmi-1.0.0.rpgapi]
                    environments:
                      %s:
                        code: T0001
                        backend: ibmi
                        host: ${IBMI_HOST}
                        libraryList: [%s]
                        credentials:
                          user: ${IBMI_USER}
                          password: ${IBMI_PASSWORD}
                        pool: { maxConnections: 1 }
                        callTimeoutSeconds: 2
                """.formatted(port, environment, libraryList);
    }

    record Answer(int status, String body) {
    }

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final List<String> access = new ArrayList<>();

    private Answer send(int port, String method, String path, String json) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(20));
        if (json == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json));
        }
        HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        // Statuses and bodies of the test programs carry nothing of the system; print them for the workflow log.
        System.out.println(method + " " + path + " -> " + response.statusCode() + " " + response.body());
        return new Answer(response.statusCode(), response.body());
    }

    private Started start(String configuration) {
        Path file = Fixtures.write(dir, "gateway.yaml", configuration);
        StartResult result = GatewayLoader.load(file, System::getenv, access::add, new StartOptions(List.of(new IbmiBackend()), true));
        assertThat(result.started()).as(result.diagnostics().toString()).isPresent();
        return result.started().get();
    }

    @Test
    void theFiveProgramsThroughTheWholeGateway() throws Exception {
        Fixtures.bundle(dir, "ibmi");
        int port = ServerFixture.freePort();
        Started started = start(config(port, "test", "\"${IBMI_LIBRARY}\""));
        WebServer server = HelidonServer.start(started);
        try {
            Answer scalars = send(port, "POST", "/acme/test/scalars", "{\"text\":\"HELLO\",\"packed\":\"-12.34\",\"zoned\":42,\"whole\":-7}");
            assertThat(scalars.status()).isEqualTo(200);
            assertThat(scalars.body()).isEqualTo("{\"echo\":\"ECHO:HELLO\",\"doubled\":\"-24.68\",\"plusOne\":43,\"tripled\":-21}");

            Answer struct = send(port, "POST", "/acme/test/items", "{\"item\":{\"code\":\"AB12\",\"quantity\":5,\"price\":\"10.25\",\"status\":\"N\"}}");
            assertThat(struct.status()).isEqualTo(200);
            assertThat(struct.body()).isEqualTo("{\"item\":{\"code\":\"XAB12\",\"quantity\":6,\"price\":\"20.50\",\"status\":\"U\"}}");

            Answer rows = send(port, "GET", "/acme/test/rows?wanted=3", null);
            assertThat(rows.status()).isEqualTo(200);
            assertThat(rows.body()).isEqualTo("{\"rows\":[{\"id\":1,\"label\":\"ROW 1\",\"amount\":\"1.50\"},{\"id\":2,\"label\":\"ROW 2\",\"amount\":\"3.00\"},"
                    + "{\"id\":3,\"label\":\"ROW 3\",\"amount\":\"4.50\"}]}");

            Answer order = send(port, "GET", "/acme/test/orders/K1", null);
            assertThat(order.status()).isEqualTo(200);
            assertThat(order.body()).isEqualTo("{\"order\":{\"total\":\"123.45\",\"lines\":[{\"sku\":\"K1-1\",\"quantity\":10},{\"sku\":\"K1-2\",\"quantity\":20},"
                    + "{\"sku\":\"K1-3\",\"quantity\":30}]}}");

            Answer business = send(port, "GET", "/acme/test/orders/FAIL", null);
            assertThat(business.status()).isEqualTo(409);
            assertThat(business.body()).contains("\"code\":\"TST0001\"").contains("The key is refused");

            Answer timeout = send(port, "GET", "/acme/test/wait?seconds=5", null);
            assertThat(timeout.status()).isEqualTo(504);

            Answer missing = send(port, "GET", "/acme/test/missing", null);
            assertThat(missing.status()).isEqualTo(502);
        } finally {
            server.stop();
            started.close();
        }

        // A library list without the test programs: the program is not found.
        Started wrong = start(config(port, "wronglib", "QGPL"));
        WebServer second = HelidonServer.start(wrong);
        try {
            Answer notFound = send(port, "POST", "/acme/wronglib/scalars", "{\"text\":\"HELLO\",\"packed\":\"1.00\",\"zoned\":1,\"whole\":1}");
            assertThat(notFound.status()).isEqualTo(502);
        } finally {
            second.stop();
            wrong.close();
        }
        access.forEach(System.out::println);
    }
}
