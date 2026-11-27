package ai.generativearchitects.slay400.gateway.ibmi;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.slay400.gateway.Fixtures;
import ai.generativearchitects.slay400.gateway.LogCapture;
import ai.generativearchitects.slay400.gateway.load.GatewayLoader;
import ai.generativearchitects.slay400.gateway.load.StartOptions;
import ai.generativearchitects.slay400.gateway.load.StartResult;
import ai.generativearchitects.slay400.gateway.load.Started;
import ai.generativearchitects.slay400.gateway.pipeline.RuntimeRequest;
import ai.generativearchitects.slay400.gateway.pipeline.RuntimeResponse;
import ai.generativearchitects.slay400.pcml.diagnostics.Diagnostic;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The whole pipeline with the ibmi backend on a fake connection: statuses, bodies and log lines. Every value is invented. */
class IbmiPipelineTest {

    @TempDir
    Path dir;

    static final Map<String, String> VARIABLES = Map.of("T_HOST", "host-marker.invalid", "T_USER", "USERMARK", "T_PASSWORD", "PW-MARKER-5e1");

    static final String CONFIG = """
            tenants:
              acme:
                bundles: [./ibmi-1.0.0.rpgapi]
                environments:
                  test:
                    code: T0001
                    backend: ibmi
                    host: ${T_HOST}
                    libraryList: [LIBMARK]
                    credentials:
                      user: ${T_USER}
                      password: ${T_PASSWORD}
            """;

    final List<String> access = new ArrayList<>();

    private StartResult start(FakeIbmi fake, boolean connectCheck) {
        Fixtures.bundle(dir, "ibmi");
        Path file = Fixtures.write(dir, "gateway.yaml", CONFIG);
        IbmiBackend backend = new IbmiBackend(settings -> fake, Duration.ofMillis(200), settings -> Duration.ofMillis(300));
        return GatewayLoader.load(file, VARIABLES::get, access::add, new StartOptions(List.of(backend), connectCheck));
    }

    static RuntimeRequest get(String path) {
        int query = path.indexOf('?');
        Map<String, List<String>> parameters = query < 0 ? Map.of() : Map.of(path.substring(query + 1).split("=")[0], List.of(path.substring(query + 1).split("=")[1]));
        return new RuntimeRequest("GET", query < 0 ? path : path.substring(0, query), parameters, Map.of(), Optional.empty(), () -> new byte[0]);
    }

    static String body(RuntimeResponse response) {
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    /** What program 4 returns for a key: three lines and a total, or the business error. */
    static CallReply nested(List<ParameterBytes> parameters, boolean fail) {
        HexFormat hex = HexFormat.of();
        String line = "C1C2F0F0F0F0F0F1" + "0000000A";
        byte[] order = hex.parseHex("012345000F" + line.repeat(3));
        byte[] flag = hex.parseHex(fail ? "D2D6" : "D6D2");
        byte[] code = hex.parseHex(fail ? "E3E2E3F0F0F0F1" : "40404040404040");
        byte[] text = hex.parseHex(fail ? "E38885409285A8" + "40".repeat(33) : "40".repeat(40));
        return new CallReply.Completed(List.of(new byte[0], order, flag, code, text));
    }

    @Test
    void everyRowOfTheOutcomesTableThroughThePipeline() {
        FakeIbmi fake = new FakeIbmi();
        StartResult result = start(fake, true);
        assertThat(result.diagnostics()).extracting(Diagnostic::code).doesNotContain("GWY016", "GWY017", "GWY018");
        Started started = result.started().orElseThrow();
        assertThat(fake.opened).hasValue(1);

        // Returned: program 4 with three lines.
        fake.program = (path, parameters) -> nested(parameters, false);
        RuntimeResponse ok = started.gateway().handle(get("/acme/test/orders/AB"));
        assertThat(ok.status()).as(body(ok)).isEqualTo(200);
        assertThat(body(ok)).isEqualTo("{\"order\":{\"total\":\"123450.00\",\"lines\":[{\"sku\":\"AB000001\",\"quantity\":10},{\"sku\":\"AB000001\",\"quantity\":10},"
                + "{\"sku\":\"AB000001\",\"quantity\":10}]}}");

        // The business error of program 4: its mapped status, with the program's code and message.
        fake.program = (path, parameters) -> nested(parameters, true);
        RuntimeResponse business = started.gateway().handle(get("/acme/test/orders/FAIL"));
        assertThat(business.status()).isEqualTo(409);
        assertThat(body(business)).contains("TST0001").contains("The key");

        // Failed: an escape message is 502 without its id or text in the body.
        fake.program = (path, parameters) -> new CallReply.Escaped(List.of(new IbmiMessage("MCH3401", "Cannot resolve to object TSTNONE in LIBMARK.")));
        RuntimeResponse missing = started.gateway().handle(get("/acme/test/missing"));
        assertThat(missing.status()).isEqualTo(502);
        assertThat(body(missing)).doesNotContain("MCH3401").doesNotContain("resolve").doesNotContain("LIBMARK");

        // TimedOut: 504.
        fake.program = (path, parameters) -> {
            Thread.sleep(3000);
            return FakeIbmi.completed(parameters);
        };
        assertThat(started.gateway().handle(get("/acme/test/wait?seconds=5")).status()).isEqualTo(504);

        // Unavailable: the host cannot be reached for a new connection.
        fake.openFailure = new IbmiFailure(IbmiFailure.Kind.UNREACHABLE, "ConnectException");
        assertThat(started.gateway().handle(get("/acme/test/wait?seconds=1")).status()).isEqualTo(503);
        started.close();
        assertThat(access).extracting(line -> line.replaceAll(".*\"status\":([0-9]+).*", "$1")).containsExactly("200", "409", "502", "504", "503");
    }

    @Test
    void theOperatorLogGetsTheMessageIdsAndTextWithTenantAndEnvironmentAndNeverAValueOrACredential() {
        FakeIbmi fake = new FakeIbmi();
        Started started = start(fake, true).started().orElseThrow();
        fake.program = (path, parameters) -> new CallReply.Escaped(List.of(new IbmiMessage("CPF9898", "Stopped in LIBMARK for usermark")));
        try (LogCapture gateway = new LogCapture("slay400.gateway"); LogCapture ibmi = new LogCapture("slay400.gateway.ibmi")) {
            started.gateway().handle(get("/acme/test/orders/MK9Z"));
            String all = gateway.text() + ibmi.text();
            assertThat(all).contains("\"tenant\":\"acme\",\"environment\":\"test\"").contains("CPF9898 Stopped in <library> for <user>").doesNotContain("MK9Z")
                    .doesNotContain("USERMARK").doesNotContain("usermark").doesNotContain("LIBMARK").doesNotContain("PW-MARKER").doesNotContain("host-marker");
        }
        assertThat(String.join("\n", access)).doesNotContain("MK9Z").doesNotContain("CPF9898");
        started.close();
    }

    @Test
    void aRefusedSignOnAtStartStopsTheStartWithoutTheUser() {
        FakeIbmi fake = new FakeIbmi();
        fake.openFailure = new IbmiFailure(IbmiFailure.Kind.SIGN_ON_REFUSED, "return code 2");
        StartResult result = start(fake, true);
        assertThat(result.started()).isEmpty();
        assertThat(result.diagnostics()).extracting(Diagnostic::code).containsExactly("GWY018");
        assertThat(result.diagnostics().stream().map(Diagnostic::toString).collect(Collectors.joining())).contains("the sign-on was refused (return code 2)")
                .doesNotContain("USERMARK").doesNotContain("PW-MARKER").doesNotContain("host-marker");
        assertThat(fake.opened).hasValue(1);
    }

    @Test
    void withoutTheConnectCheckTheStartOpensNothing() {
        FakeIbmi fake = new FakeIbmi();
        fake.openFailure = new IbmiFailure(IbmiFailure.Kind.UNREACHABLE, "the host name does not resolve");
        StartResult result = start(fake, false);
        assertThat(result.started()).isPresent();
        assertThat(fake.opened).hasValue(0);
        result.started().get().close();
    }

    @Test
    void theShopFixtureCanAlsoBeServedByTheIbmiBackend() {
        Fixtures.bundle(dir, "shop");
        Path file = Fixtures.write(dir, "gateway.yaml", CONFIG.replace("ibmi-1.0.0.rpgapi", "shop-1.0.0.rpgapi"));
        FakeIbmi fake = new FakeIbmi();
        IbmiBackend backend = new IbmiBackend(settings -> fake, Duration.ofMillis(200), settings -> Duration.ofMillis(300));
        StartResult result = GatewayLoader.load(file, VARIABLES::get, access::add, new StartOptions(List.of(backend), true));
        // The shop fixture serves both programs with char fields only: both can be called.
        assertThat(result.started()).isPresent();
        result.started().get().close();
        assertThat(new IbmiBackend().backend()).isEqualTo(ai.generativearchitects.slay400.gateway.config.Backend.IBMI);
        assertThat(new IbmiBackend().unsupported(IbmiFixtures.operations(dir.resolve("x")).get("tstwait"))).isEmpty();
    }
}
