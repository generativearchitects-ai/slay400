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
 * 5 as 504, a program that does not exist as 502, and a wrong library list as 502; and one call for each of programs 6 to 10
 * (kickoff 04); and programs 11, 12 and the timestamp probe (kickoff 05). One connection at a time: two gateways one after the
 * other, each with a pool of one. Sixteen program calls.
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

            // Programs 6 to 10 (kickoff 04): one call each. The answers are those NewProgramsTest derives on a fake connection.
            Answer signs = send(port, "POST", "/acme/test/signs", "{\"inZoned\":\"12345.67\"}");
            assertThat(signs.status()).isEqualTo(200);
            assertThat(signs.body()).isEqualTo("{\"zonedNeg\":\"-12345.67\",\"zonedZero\":\"0.00\",\"zonedMax\":\"99999.99\",\"packedNeg\":\"-12345.67\","
                    + "\"packedZero\":\"0.00\",\"packedMax\":\"99999.99\",\"int2Min\":-32768,\"int2Max\":32767,\"int4Min\":-2147483648,\"int4Max\":2147483647,"
                    + "\"int8Min\":-9223372036854775808,\"int8Max\":9223372036854775807,\"uns2Max\":65535,\"uns4Max\":4294967295,\"uns8Max\":18446744073709551615}");

            Answer texts = send(port, "POST", "/acme/test/texts", "{\"in2\":\"HELLO\",\"in4\":\"WORLD!\",\"inText\":\"@!{}[]|~#$ÄÖÜß\"}");
            assertThat(texts.status()).isEqualTo(200);
            assertThat(texts.body()).isEqualTo("{\"echo2\":\"HELLO\",\"echo4\":\"WORLD!\",\"empty2\":\"\",\"part2\":\"ABC\",\"full2\":\"ABCDEFGHIJ\",\"empty4\":\"\","
                    + "\"part4\":\"XYZ\",\"full4\":\"KLMNOPQRST\",\"echoText\":\"@!{}[]|~#$ÄÖÜß\",\"pages\":\"@!{}[]|~#$ÄÖÜß\"}");

            Answer floats = send(port, "POST", "/acme/test/floats", "{\"in4\":\"1.5\",\"in8\":\"10\"}");
            assertThat(floats.status()).isEqualTo(200);
            assertThat(floats.body()).isEqualTo("{\"out4\":\"3.0\",\"out8\":\"2.5\",\"fixed4\":\"-1.5\",\"fixed8\":\"12500000000\"}");

            Answer dates = send(port, "POST", "/acme/test/dates", "{\"inDate\":\"2026-11-18\",\"inTime\":\"10:15:30\"}");
            assertThat(dates.status()).isEqualTo(200);
            assertThat(dates.body()).isEqualTo("{\"isoNext\":\"2026-11-19\",\"isoLow\":\"0001-01-01\",\"isoHigh\":\"9999-12-31\",\"mdyLow\":\"1940-01-01\","
                    + "\"mdyHigh\":\"2039-12-31\",\"eurDate\":\"2026-11-18\",\"julDate\":\"2026-11-18\",\"ymdDot\":\"2026-11-18\",\"hmsNext\":\"11:15:30\","
                    + "\"usaTime\":\"10:15:00\",\"eurTime\":\"10:15:30\"}");

            Answer counts = send(port, "GET", "/acme/test/counts?wanted=3", null);
            assertThat(counts.status()).isEqualTo(200);
            assertThat(counts.body()).isEqualTo("{\"items\":[\"ITEM1\",\"ITEM2\",\"ITEM3\"],\"box\":{\"count\":3,\"lines\":[\"LINE1\",\"LINE2\",\"LINE3\"]}}");

            // Programs 11 and 12 and the timestamp probe (kickoff 05). Every midnight and end of the day reads as 00:00:00 (decision
            // 123); the highest timestamp reads as the last instant the field can show (decision 125).
            Answer midnight = send(port, "POST", "/acme/test/midnight", "{\"isoIn\":\"00:00:00\",\"hmsIn\":\"00:00:00\",\"usaIn\":\"00:00:00\","
                    + "\"usaIn2\":\"00:00:00\",\"eurIn\":\"00:00:00\",\"inStamp\":\"2026-11-19T00:00:00\"}");
            assertThat(midnight.status()).isEqualTo(200);
            assertThat(midnight.body()).isEqualTo("{\"isoEcho\":\"00:00:00\",\"isoLow\":\"00:00:00\",\"isoHigh\":\"00:00:00\",\"hmsEcho\":\"00:00:00\","
                    + "\"hmsLow\":\"00:00:00\",\"hmsHigh\":\"00:00:00\",\"usaEcho\":\"00:00:00\",\"usaEcho2\":\"00:00:00\",\"usaLow\":\"00:00:00\",\"usaHigh\":\"00:00:00\","
                    + "\"eurEcho\":\"00:00:00\",\"eurLow\":\"00:00:00\",\"eurHigh\":\"00:00:00\",\"stampNext\":\"2026-11-19T00:00:01\",\"stampLow\":\"0001-01-01T00:00:00\","
                    + "\"stampHigh\":\"9999-12-31T23:59:59.999999\"}");

            Answer formats = send(port, "POST", "/acme/test/formats", "{\"inDate\":\"2026-11-19\",\"inTime\":\"10:15:30\"}");
            assertThat(formats.status()).isEqualTo(200);
            assertThat(formats.body()).isEqualTo("{\"dmyDate\":\"2026-11-19\",\"usaDate\":\"2026-11-19\",\"jisDate\":\"2026-11-19\",\"mdyComma\":\"2026-11-19\","
                    + "\"mdyBlank\":\"2026-11-19\",\"jisTime\":\"10:15:30\",\"hmsComma\":\"10:15:30\",\"hmsBlank\":\"10:15:30\"}");

            Answer stamps = send(port, "POST", "/acme/test/stamps", "{\"inStamp\":\"2026-11-19T10:15:30.123456789\"}");
            assertThat(stamps.status()).isEqualTo(200);
            assertThat(stamps.body()).isEqualTo("{\"outStamp\":\"2026-11-19T10:15:30.123456789\",\"outStamp0\":\"2026-11-19T10:15:30\"}");
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
