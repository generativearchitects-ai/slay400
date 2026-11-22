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
 * Programs 13 and 14 of kickoff 06 through the whole pipeline, with the bytes the system returned in run 1, with a fake connection that does what each program is written to do: the
 * request is laid out, the fake computes the program's outputs from the input values and lays them out as bytes, and the
 * gateway's answer is the JSON that {@code GatewayIT} expects from the real system.
 */
class Kickoff06ProgramsTest {

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

    /** A fake whose every output parameter comes back as the given bytes, in order; input-only parameters get none. */
    void returns(String... hex) {
        fake.ccsid = 273;
        fake.program = (path, parameters) -> {
            List<byte[]> output = new ArrayList<>();
            int next = 0;
            for (ParameterBytes parameter : parameters) {
                output.add(parameter.outputLength() == 0 ? new byte[0] : java.util.HexFormat.of().parseHex(hex[next++]));
            }
            return new CallReply.Completed(output);
        };
    }

    /** The bytes run 1 returned for program 13, padded to the size of each field with what the gateway sent: zero bytes. */
    static final String[] BYTES_RETURNED = {"00FF7F80C1F0400DFF00807F3E0FBFF2", "000500FF7F80C1" + "00".repeat(27),
            "0000001000FF7F80C1F0400D00FF7F80C1F0400D" + "00".repeat(16), "00FF7F80C1F0400D"};

    /** The answer of program 13 that GatewayIT expects from the real system too. */
    static final String EXPECTED_BYTES = "{\"fixed\":\"AP9/gMHwQA3/AIB/Pg+/8g==\",\"varying2\":\"AP9/gME=\",\"varying4\":\"AP9/gMHwQA0A/3+AwfBADQ==\","
            + "\"asText\":\"\\u0000\u009f\\\"\u00d8A0 \\r\"}";

    /** The answer of program 14 that GatewayIT expects from the real system too. */
    static final String EXPECTED_GROUPS = "{\"groups\":[{\"count\":0,\"items\":[]},{\"count\":1,\"items\":[\"I21\"]},{\"count\":2,\"items\":[\"I31\",\"I32\"]},"
            + "{\"count\":3,\"items\":[\"I41\",\"I42\",\"I43\"]}]}";

    @Test
    void program13RawBytesFixedAndVaryingAndOneFieldAsText() {
        returns(BYTES_RETURNED);
        RuntimeResponse response = post("/acme/test/bytes", "{\"inBytes\":\"AP9/gMHwQA0=\"}");
        assertThat(response.status()).isEqualTo(200);
        assertThat(body(response)).isEqualTo(EXPECTED_BYTES);
    }

    @Test
    void program13SendsTheInputBytesUnconvertedAndZerosAfterAVaryingLength() {
        List<List<ParameterBytes>> sent = new ArrayList<>();
        fake.ccsid = 273;
        fake.program = (path, parameters) -> {
            sent.add(parameters);
            return FakeIbmi.completed(parameters);
        };
        post("/acme/test/bytes", "{\"inBytes\":\"AP9/gMHwQA0=\"}");
        assertThat(java.util.HexFormat.of().withUpperCase().formatHex(sent.get(0).get(0).input().orElseThrow())).isEqualTo("00FF7F80C1F0400D");
        // The outputs are sent blank: zero bytes, never blanks, the varying ones with a length of zero.
        assertThat(sent.get(0).get(1).input().orElseThrow()).containsOnly(0);
        assertThat(sent.get(0).get(2).input().orElseThrow()).hasSize(34).containsOnly(0);
        assertThat(sent.get(0).get(3).input().orElseThrow()).hasSize(36).containsOnly(0);
    }

    @Test
    void program14GroupsEachTrimmedByItsOwnCount() {
        returns("0000000040404040404040404040404000000001C9F2F140404040404040404000000002C9F3F140C9F3F2404040404000000003C9F4F140C9F4F240C9F4F340");
        RuntimeResponse response = started.gateway().handle(new RuntimeRequest("GET", "/acme/test/groups",
                Map.of("fill1", List.of("0"), "fill2", List.of("1"), "fill3", List.of("2"), "fill4", List.of("3")), Map.of(), Optional.empty(), () -> new byte[0]));
        assertThat(response.status()).isEqualTo(200);
        assertThat(body(response)).isEqualTo(EXPECTED_GROUPS);
    }

    @Test
    void noValueOfABytesFieldIsLogged() {
        try (LogCapture gateway = new LogCapture("slay400.gateway"); LogCapture ibmi = new LogCapture("slay400.gateway.ibmi")) {
            fake.program = (path, parameters) -> new CallReply.Escaped(List.of(new IbmiMessage("CPF9898", "Stopped")));
            // "TUFSS0VSMDE=" is the base64 of the text MARKER01.
            post("/acme/test/bytes", "{\"inBytes\":\"TUFSS0VSMDE=\"}");
            String all = gateway.text() + ibmi.text() + String.join("\n", access);
            assertThat(all).contains("CPF9898").doesNotContain("TUFSS0VSMDE").doesNotContain("MARKER01").doesNotContain("4D41524B4552")
                    .doesNotContain("D4C1D9D2C5D9");
        }
    }

    static final String CLEARED_RETURNED = "D9F1404000125FF2F0F2F660F1F160F2F3F2F661F1F161F2F3F1F04BF0F14BF0F0F2F0F2F660F1F160F2F260F1F04BF0F04BF0F14BF0F0F0F0F0F0D9F2404000250FF2F0F2F660F1F160F2F4F2F661F1F161F2F4F1F04BF0F24BF0F0F2F0F2F660F1F160F2F260F1F04BF0F04BF0F24BF0F0F0F0F0F04040404000000FF0F0F0F160F0F160F0F1F4F061F0F161F0F1F0F04BF0F04BF0F0F0F0F0F160F0F160F0F160F0F04BF0F04BF0F04BF0F0F0F0F0F0";
    static final String UNTOUCHED_RETURNED = CLEARED_RETURNED.substring(0, 236) + "40".repeat(59);

    /** The two filled elements of program 15, as the system returned them. */
    static final String FILLED_RECORDS = "[{\"name\":\"R1\",\"amount\":\"1.25\",\"isoDate\":\"2026-11-23\",\"ymdDate\":\"2026-11-23\",\"atTime\":\"10:01:00\","
            + "\"stamp\":\"2026-11-22T10:00:01\"},{\"name\":\"R2\",\"amount\":\"2.50\",\"isoDate\":\"2026-11-24\",\"ymdDate\":\"2026-11-24\","
            + "\"atTime\":\"10:02:00\",\"stamp\":\"2026-11-22T10:00:02\"}]";

    private RuntimeResponse cleared() {
        return started.gateway().handle(new RuntimeRequest("GET", "/acme/test/cleared", Map.of("filled", List.of("2")), Map.of(), Optional.empty(), () -> new byte[0]));
    }

    @Test
    void program15ElementsLeftByClearAndInzAreTrimmed() {
        // Decision 126 on the system's bytes: blanks, a packed zero and the lowest date (YMD 40/01/01), time and timestamp.
        returns(CLEARED_RETURNED, CLEARED_RETURNED, CLEARED_RETURNED);
        RuntimeResponse response = cleared();
        assertThat(response.status()).isEqualTo(200);
        assertThat(body(response)).isEqualTo("{\"cleared\":" + FILLED_RECORDS + ",\"inzd\":" + FILLED_RECORDS + ",\"untouched\":" + FILLED_RECORDS + "}");
    }

    @Test
    void program15AnElementWithoutInzIsTodayAnErrorForItsBlankPackedField() {
        // The finding of run 1: after a declaration without inz, every byte is a blank. Blank dates, times and timestamps read
        // as blank, the blank packed number does not.
        returns(CLEARED_RETURNED, CLEARED_RETURNED, UNTOUCHED_RETURNED);
        try (LogCapture gateway = new LogCapture("slay400.gateway")) {
            assertThat(cleared().status()).isEqualTo(502);
            assertThat(gateway.text()).contains("UNTOUCHED.AMOUNT: the bytes are not a valid packed number");
        }
        // The same element with a packed zero in place of the blanks is trimmed.
        returns(CLEARED_RETURNED, CLEARED_RETURNED, CLEARED_RETURNED.substring(0, 236) + "4040404000000F" + "40".repeat(52));
        RuntimeResponse response = cleared();
        assertThat(response.status()).isEqualTo(200);
        assertThat(body(response)).endsWith("\"untouched\":" + FILLED_RECORDS + "}");
    }
}
