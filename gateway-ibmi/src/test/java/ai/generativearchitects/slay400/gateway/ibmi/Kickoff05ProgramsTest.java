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
import ai.generativearchitects.slay400.pcml.api.ApiOperation;
import ai.generativearchitects.slay400.pcml.codec.WireValue;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Programs 11, 12 and the timestamp probe of kickoff 05 through the whole pipeline, with a fake connection that does what each program is written to do: the
 * request is laid out, the fake computes the program's outputs from the input values and lays them out as bytes, and the
 * gateway's answer is the JSON that {@code GatewayIT} expects from the real system.
 */
class Kickoff05ProgramsTest {

    @TempDir
    Path dir;

    final List<String> access = new ArrayList<>();
    Started started;
    FakeIbmi fake;
    Map<String, ApiOperation> operations;

    @BeforeEach
    void start() {
        operations = IbmiFixtures.operations(dir.resolve("ops"));
        fake = new FakeIbmi();
        Fixtures.bundle(dir, "ibmi");
        Path file = Fixtures.write(dir, "gateway.yaml", IbmiPipelineTest.CONFIG);
        IbmiBackend backend = new IbmiBackend(settings -> fake, Duration.ofMillis(200), settings -> Duration.ofSeconds(5));
        StartResult result = GatewayLoader.load(file, IbmiPipelineTest.VARIABLES::get, access::add, new StartOptions(List.of(backend), true));
        assertThat(result.started()).as(result.diagnostics().toString()).isPresent();
        started = result.started().get();
    }

    /** A fake program: from the values it received, the values of every parameter after the call. */
    void program(String id, Function<List<WireValue>, List<WireValue>> behaviour) {
        ProgramLayout layout = ProgramLayout.of(operations.get(id));
        fake.program = (path, parameters) -> {
            List<WireValue> received = new ArrayList<>();
            for (int i = 0; i < parameters.size(); i++) {
                Layout field = layout.parameters().get(i).layout();
                received.add(parameters.get(i).input().map(bytes -> field.read(bytes, 0, fake.ccsid)).orElse(new WireValue.Blank()));
            }
            List<WireValue> after = behaviour.apply(received);
            List<byte[]> output = new ArrayList<>();
            for (int i = 0; i < parameters.size(); i++) {
                output.add(parameters.get(i).outputLength() == 0 ? new byte[0] : layout.parameters().get(i).layout().toBytes(after.get(i), fake.ccsid));
            }
            return new CallReply.Completed(output);
        };
    }

    RuntimeResponse post(String path, String json) {
        return started.gateway().handle(new RuntimeRequest("POST", path, Map.of(), Map.of(), Optional.of("application/json"), () -> json.getBytes(StandardCharsets.UTF_8)));
    }

    RuntimeResponse get(String path, String name, String value) {
        return started.gateway().handle(new RuntimeRequest("GET", path, Map.of(name, List.of(value)), Map.of(), Optional.empty(), () -> new byte[0]));
    }

    static String body(RuntimeResponse response) {
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    static WireValue dec(String value) {
        return new WireValue.Decimal(new BigDecimal(value));
    }

    static WireValue whole(String value) {
        return new WireValue.Integer(new BigInteger(value));
    }

    static WireValue text(String value) {
        return new WireValue.Text(value);
    }

    static java.time.LocalTime t(int h, int m, int s) {
        return java.time.LocalTime.of(h, m, s);
    }

    /** What program 11 does, with a given highest timestamp, from the values it received. */
    static List<WireValue> midnight(List<WireValue> in, java.time.LocalDateTime highest) {
        WireValue.Timestamp stamp = (WireValue.Timestamp) in.get(5);
        WireValue zero = new WireValue.Time(t(0, 0, 0));
        return List.of(in.get(0), in.get(1), in.get(2), in.get(3), in.get(4), in.get(5), in.get(0), zero, zero, in.get(1), zero, zero, in.get(2), in.get(3), zero,
                zero, in.get(4), zero, zero, new WireValue.Timestamp(stamp.value().plusSeconds(1)), new WireValue.Timestamp(java.time.LocalDateTime.of(1, 1, 1, 0, 0)),
                new WireValue.Timestamp(highest));
    }

    static final String MIDNIGHT_REQUEST = "{\"isoIn\":\"00:00:00\",\"hmsIn\":\"00:00:00\",\"usaIn\":\"00:00:00\",\"usaIn2\":\"00:00:00\",\"eurIn\":\"00:00:00\","
            + "\"inStamp\":\"2026-11-19T00:00:00\"}";

    /** The bytes of the program's highest timestamp, as run 1 returned it: 9999-12-31-24.00.00.000000. */
    static final String HIGHEST = "F9F9F9F960F1F260F3F160F2F44BF0F04BF0F04BF0F0F0F0F0F0";

    /** The answer of program 11 that GatewayIT expects from the real system too. */
    static final String EXPECTED_MIDNIGHT = "{\"isoEcho\":\"00:00:00\",\"isoLow\":\"00:00:00\",\"isoHigh\":\"00:00:00\",\"hmsEcho\":\"00:00:00\","
            + "\"hmsLow\":\"00:00:00\",\"hmsHigh\":\"00:00:00\",\"usaEcho\":\"00:00:00\",\"usaEcho2\":\"00:00:00\",\"usaLow\":\"00:00:00\",\"usaHigh\":\"00:00:00\","
            + "\"eurEcho\":\"00:00:00\",\"eurLow\":\"00:00:00\",\"eurHigh\":\"00:00:00\",\"stampNext\":\"2026-11-19T00:00:01\",\"stampLow\":\"0001-01-01T00:00:00\","
            + "\"stampHigh\":\"9999-12-31T23:59:59.999999\"}";

    @Test
    void program11WithTheBytesTheSystemReturned() {
        // Run 2 of kickoff 05: every output of program 11 as the system returned it for an input of midnight, highest timestamp included.
        String[] hex = {"F0F04BF0F04BF0F0", "F0F04BF0F04BF0F0", "F2F44BF0F04BF0F0", "F0F07AF0F07AF0F0", "F0F07AF0F07AF0F0", "F2F47AF0F07AF0F0",
                "F0F07AF0F040C1D4", "F0F07AF0F040C1D4", "F0F07AF0F040C1D4", "F1F27AF0F040C1D4", "F0F04BF0F04BF0F0", "F0F04BF0F04BF0F0", "F2F44BF0F04BF0F0",
                "F2F0F2F660F1F160F1F960F0F04BF0F04BF0F14BF0F0F0F0F0F0", "F0F0F0F160F0F160F0F160F0F04BF0F04BF0F04BF0F0F0F0F0F0", HIGHEST};
        fake.ccsid = 273;
        fake.program = (path, parameters) -> {
            List<byte[]> output = new ArrayList<>();
            for (int i = 0; i < parameters.size(); i++) {
                output.add(parameters.get(i).outputLength() == 0 ? new byte[0] : java.util.HexFormat.of().parseHex(hex[i - 6]));
            }
            return new CallReply.Completed(output);
        };
        RuntimeResponse response = post("/acme/test/midnight", MIDNIGHT_REQUEST);
        assertThat(response.status()).isEqualTo(200);
        assertThat(body(response)).isEqualTo(EXPECTED_MIDNIGHT);
        assertThat(ProgramLayout.of(operations.get("tstmidngt")).parameters()).hasSize(22);
    }

    @Test
    void program11WithAnOrdinaryEndOfDayGivesTheNextDay() {
        fake.ccsid = 273;
        program("tstmidngt", in -> midnight(in, java.time.LocalDateTime.of(2026, 11, 20, 0, 0)));
        RuntimeResponse response = post("/acme/test/midnight", MIDNIGHT_REQUEST);
        assertThat(response.status()).isEqualTo(200);
        assertThat(body(response)).endsWith("\"stampHigh\":\"2026-11-20T00:00:00\"}");
    }

    /** The answer of program 12 that GatewayIT expects from the real system too. */
    static final String EXPECTED_FORMATS = "{\"dmyDate\":\"2026-11-19\",\"usaDate\":\"2026-11-19\",\"jisDate\":\"2026-11-19\",\"mdyComma\":\"2026-11-19\","
            + "\"mdyBlank\":\"2026-11-19\",\"jisTime\":\"10:15:30\",\"hmsComma\":\"10:15:30\",\"hmsBlank\":\"10:15:30\"}";

    /** The answer of the timestamp probe that GatewayIT expects from the real system too. */
    static final String EXPECTED_STAMPS = "{\"outStamp\":\"2026-11-19T10:15:30.123456789\",\"outStamp0\":\"2026-11-19T10:15:30\"}";

    @Test
    void program12TheRemainingFormatsAndSeparators() {
        fake.ccsid = 273;
        program("tstformat", in -> List.of(in.get(0), in.get(1), in.get(0), in.get(0), in.get(0), in.get(0), in.get(0), in.get(1), in.get(1), in.get(1)));
        RuntimeResponse response = post("/acme/test/formats", "{\"inDate\":\"2026-11-19\",\"inTime\":\"10:15:30\"}");
        assertThat(response.status()).isEqualTo(200);
        assertThat(body(response)).isEqualTo(EXPECTED_FORMATS);
    }

    @Test
    void theTimestampProbeWithTwelveAndNoFractionDigits() {
        fake.ccsid = 273;
        program("tststmp12", in -> List.of(in.get(0), in.get(0), new WireValue.Timestamp(((WireValue.Timestamp) in.get(0)).value().withNano(0))));
        RuntimeResponse response = post("/acme/test/stamps", "{\"inStamp\":\"2026-11-19T10:15:30.123456789\"}");
        assertThat(response.status()).isEqualTo(200);
        assertThat(body(response)).isEqualTo(EXPECTED_STAMPS);
    }

    @Test
    void noValueOfAParameterOfTheNewTypesIsLogged() {
        try (LogCapture gateway = new LogCapture("slay400.gateway"); LogCapture ibmi = new LogCapture("slay400.gateway.ibmi")) {
            fake.program = (path, parameters) -> new CallReply.Escaped(List.of(new IbmiMessage("CPF9898", "Stopped")));
            post("/acme/test/stamps", "{\"inStamp\":\"1977-03-21T07:41:53.987654321\"}");
            post("/acme/test/formats", "{\"inDate\":\"1983-06-17\",\"inTime\":\"21:43:59\"}");
            String all = gateway.text() + ibmi.text() + String.join("\n", access);
            assertThat(all).contains("CPF9898").doesNotContain("1977-03-21").doesNotContain("07.41.53").doesNotContain("07:41:53").doesNotContain("987654321")
                    .doesNotContain("1983-06-17").doesNotContain("17/06/83").doesNotContain("06/17/1983").doesNotContain("21:43:59").doesNotContain("21,43,59");
        }
    }
}
