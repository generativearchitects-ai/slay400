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
 * Programs 6 to 10 through the whole pipeline, with a fake connection that does what each program is written to do: the
 * request is laid out, the fake computes the program's outputs from the input values and lays them out as bytes, and the
 * gateway's answer is the JSON that {@code GatewayIT} expects from the real system.
 */
class NewProgramsTest {

    @TempDir
    Path dir;

    /** The answer of program signs that GatewayIT expects from the real system too. */
    static final String EXPECTED_SIGNS = "{\"zonedNeg\":\"-12345.67\",\"zonedZero\":\"0.00\",\"zonedMax\":\"99999.99\",\"packedNeg\":\"-12345.67\",\"packedZero\":\"0.00\",\"packedMax\":\"99999.99\",\"int2Min\":-32768,\"int2Max\":32767,\"int4Min\":-2147483648,\"int4Max\":2147483647,\"int8Min\":-9223372036854775808,\"int8Max\":9223372036854775807,\"uns2Max\":65535,\"uns4Max\":4294967295,\"uns8Max\":18446744073709551615}";

    /** The answer of program texts that GatewayIT expects from the real system too. */
    static final String EXPECTED_TEXTS = "{\"echo2\":\"HELLO\",\"echo4\":\"WORLD!\",\"empty2\":\"\",\"part2\":\"ABC\",\"full2\":\"ABCDEFGHIJ\",\"empty4\":\"\",\"part4\":\"XYZ\",\"full4\":\"KLMNOPQRST\",\"echoText\":\"@!{}[]|~#$ÄÖÜß\",\"pages\":\"@!{}[]|~#$ÄÖÜß\"}";

    /** The answer of program dates that GatewayIT expects from the real system too. */
    static final String EXPECTED_DATES = "{\"isoNext\":\"2026-11-19\",\"isoLow\":\"0001-01-01\",\"isoHigh\":\"9999-12-31\",\"mdyLow\":\"1940-01-01\",\"mdyHigh\":\"2039-12-31\",\"eurDate\":\"2026-11-18\",\"julDate\":\"2026-11-18\",\"ymdDot\":\"2026-11-18\",\"hmsNext\":\"11:15:30\",\"usaTime\":\"10:15:00\",\"eurTime\":\"10:15:30\"}";

    /** The answer of program counts that GatewayIT expects from the real system too. */
    static final String EXPECTED_COUNTS = "{\"items\":[\"ITEM1\",\"ITEM2\",\"ITEM3\"],\"box\":{\"count\":3,\"lines\":[\"LINE1\",\"LINE2\",\"LINE3\",\"\",\"\"]}}";

    /** The answer of program 8 that GatewayIT expects from the real system too. */
    static final String EXPECTED_FLOATS = "{\"out4\":\"3.0\",\"out8\":\"2.5\",\"fixed4\":\"-1.5\",\"fixed8\":\"12500000000\"}";

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

    @Test
    void program6SignsAndRanges() {
        program("tstsigns", in -> List.of(in.get(0), new WireValue.Decimal(((WireValue.Decimal) in.get(0)).value().negate()), dec("0.00"), dec("99999.99"),
                dec("-12345.67"), dec("0.00"), dec("99999.99"), whole("-32768"), whole("32767"), whole("-2147483648"), whole("2147483647"),
                whole("-9223372036854775808"), whole("9223372036854775807"), whole("65535"), whole("4294967295"), whole("18446744073709551615")));
        RuntimeResponse response = post("/acme/test/signs", "{\"inZoned\":\"12345.67\"}");
        assertThat(response.status()).isEqualTo(200);
        assertThat(body(response)).isEqualTo(EXPECTED_SIGNS);
    }

    @Test
    void program7VariableLengthAndCodePageCharacters() {
        program("tstvarchar", in -> List.of(in.get(0), in.get(1), in.get(2), in.get(0), in.get(1), text(""), text("ABC"), text("ABCDEFGHIJ"), text(""), text("XYZ"),
                text("KLMNOPQRST"), in.get(2), text("@!{}[]|~#$ÄÖÜß  ")));
        fake.ccsid = 273;
        RuntimeResponse response = post("/acme/test/texts", "{\"in2\":\"HELLO\",\"in4\":\"WORLD!\",\"inText\":\"@!{}[]|~#$ÄÖÜß\"}");
        assertThat(response.status()).isEqualTo(200);
        assertThat(body(response)).isEqualTo(EXPECTED_TEXTS);
    }

    @Test
    void program8Floats() {
        program("tstfloat", in -> List.of(in.get(0), in.get(1), new WireValue.Float(((WireValue.Float) in.get(0)).value() * 2),
                new WireValue.Float(((WireValue.Float) in.get(1)).value() / 4), new WireValue.Float(-1.5), new WireValue.Float(1.25e10)));
        RuntimeResponse response = post("/acme/test/floats", "{\"in4\":\"1.5\",\"in8\":\"10\"}");
        assertThat(response.status()).isEqualTo(200);
        assertThat(body(response)).isEqualTo(EXPECTED_FLOATS);
    }

    @Test
    void program9DatesAndTimes() {
        program("tstdates", in -> {
            LocalDate date = ((WireValue.Date) in.get(0)).value();
            LocalTime time = ((WireValue.Time) in.get(1)).value();
            return List.of(in.get(0), in.get(1), new WireValue.Date(date.plusDays(1)), new WireValue.Date(LocalDate.of(1, 1, 1)),
                    new WireValue.Date(LocalDate.of(9999, 12, 31)), new WireValue.Date(LocalDate.of(1940, 1, 1)), new WireValue.Date(LocalDate.of(2039, 12, 31)),
                    new WireValue.Date(date), new WireValue.Date(date), new WireValue.Date(date), new WireValue.Time(time.plusHours(1)),
                    new WireValue.Time(time.withSecond(0)), new WireValue.Time(time));
        });
        RuntimeResponse response = post("/acme/test/dates", "{\"inDate\":\"2026-11-18\",\"inTime\":\"10:15:30\"}");
        assertThat(response.status()).isEqualTo(200);
        assertThat(body(response)).isEqualTo(EXPECTED_DATES);
    }

    @Test
    void program10CountsAsAParameterAndAsAMemberOfTheStruct() {
        program("tstcounts", in -> {
            int n = ((WireValue.Integer) in.get(0)).value().intValueExact();
            List<WireValue> items = new ArrayList<>();
            for (int i = 1; i <= 10; i++) {
                items.add(text(i <= n ? "ITEM" + i : ""));
            }
            List<WireValue> lines = new ArrayList<>();
            for (int i = 1; i <= 5; i++) {
                lines.add(text(i <= Math.min(n, 5) ? "LINE" + i : ""));
            }
            return List.of(in.get(0), whole(Integer.toString(n)), new WireValue.Array(items),
                    new WireValue.Struct(List.of(whole(Integer.toString(Math.min(n, 5))), new WireValue.Array(lines))));
        });
        RuntimeResponse response = get("/acme/test/counts", "wanted", "3");
        assertThat(response.status()).isEqualTo(200);
        assertThat(body(response)).isEqualTo(EXPECTED_COUNTS);
    }

    @Test
    void noValueOfAParameterOfTheNewTypesIsLogged() {
        try (LogCapture gateway = new LogCapture("slay400.gateway"); LogCapture ibmi = new LogCapture("slay400.gateway.ibmi")) {
            fake.program = (path, parameters) -> new CallReply.Escaped(List.of(new IbmiMessage("CPF9898", "Stopped")));
            post("/acme/test/texts", "{\"in2\":\"MKVAR2\",\"in4\":\"MKVAR4\",\"inText\":\"MKCHAR\"}");
            post("/acme/test/dates", "{\"inDate\":\"1977-03-21\",\"inTime\":\"07:41:53\"}");
            post("/acme/test/floats", "{\"in4\":\"7341.5\",\"in8\":\"88123.25\"}");
            post("/acme/test/signs", "{\"inZoned\":\"54321.09\"}");
            fake.program = (path, parameters) -> {
                Thread.sleep(10_000);
                return FakeIbmi.completed(parameters);
            };
            String all = gateway.text() + ibmi.text() + String.join("\n", access);
            assertThat(all).contains("CPF9898").doesNotContain("MKVAR").doesNotContain("MKCHAR").doesNotContain("1977").doesNotContain("07:41")
                    .doesNotContain("07.41").doesNotContain("7341").doesNotContain("88123").doesNotContain("54321");
        }
    }
}
