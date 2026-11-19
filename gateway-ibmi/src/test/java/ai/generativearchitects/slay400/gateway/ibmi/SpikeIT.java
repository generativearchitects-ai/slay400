package ai.generativearchitects.slay400.gateway.ibmi;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.slay400.bundle.BundleReader;
import ai.generativearchitects.slay400.pcml.api.ApiOperation;
import ai.generativearchitects.slay400.pcml.codec.ApiValue;
import ai.generativearchitects.slay400.pcml.codec.DecodeResult;
import ai.generativearchitects.slay400.pcml.codec.OperationCodec;
import ai.generativearchitects.slay400.pcml.codec.WireValue;
import ai.generativearchitects.slay400.tasks.BundleRequest;
import ai.generativearchitects.slay400.tasks.TaskResult;
import ai.generativearchitects.slay400.tasks.Tasks;
import com.ibm.as400.access.AS400;
import com.ibm.as400.access.AS400Message;
import com.ibm.as400.data.ProgramCallDocument;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/**
 * The spike of kickoff 03: programs 1 to 4 called in two ways, with the same input values.
 *
 * <ul>
 *   <li>Document: JT400's {@code ProgramCallDocument} with the compiler's PCML, values set and read by name.</li>
 *   <li>Raw: JT400's {@code ProgramCall} with one byte array per top-level parameter, from the codec's wire values and the
 *       layout code of the ibmi backend, built through the generator from the same compiler PCML.</li>
 * </ul>
 *
 * <p>The report goes to {@code target/ibmi/spike-report.md} and to the log, with host, user and library taken out. The test
 * fails only when it cannot write the report: what works and what does not is the content of the report. One connection at a
 * time: all Document calls, then all Raw calls. Programs 6 to 12 and the timestamp probe (kickoffs 04 and 05) are called with
 * the raw call only, and the report shows every byte they returned. Sixteen calls in all.
 */
@EnabledIfEnvironmentVariable(named = IbmiSystem.HOST, matches = ".+")
@EnabledIfEnvironmentVariable(named = IbmiSystem.USER, matches = ".+")
@EnabledIfEnvironmentVariable(named = IbmiSystem.PASSWORD, matches = ".+")
@EnabledIfEnvironmentVariable(named = IbmiSystem.LIBRARY, matches = ".+")
class SpikeIT {

    @TempDir
    Path dir;

    /** One program of the spike: its shape and the request sent to it. */
    record Shape(String program, String id, String shape, Map<String, ApiValue> request) {
    }

    static final List<Shape> SHAPES = List.of(
            new Shape("TSTSCALAR", "tstscalar", "1. scalars", Map.of("text", new ApiValue.Text("HELLO"), "packed", new ApiValue.Decimal(new BigDecimal("-12.34")),
                    "zoned", new ApiValue.Integer(BigInteger.valueOf(42)), "whole", new ApiValue.Integer(BigInteger.valueOf(-7)))),
            new Shape("TSTSTRUCT", "tststruct", "2. one struct", Map.of("item", new ApiValue.Obj(Map.of("code", new ApiValue.Text("AB12"),
                    "quantity", new ApiValue.Integer(BigInteger.valueOf(5)), "price", new ApiValue.Decimal(new BigDecimal("10.25")), "status", new ApiValue.Text("N"))))),
            new Shape("TSTARRAY", "tstarray", "3. array of structs with a count", Map.of("wanted", new ApiValue.Integer(BigInteger.valueOf(3)))),
            new Shape("TSTNESTED", "tstnested", "4. struct holding an array of structs, error fields", Map.of("key", new ApiValue.Text("K1"))));

    /** Programs 6 to 10 (kickoff 04): raw call only. */
    static final List<Shape> RAW_ONLY = List.of(
            new Shape("TSTSIGNS", "tstsigns", "6. signs and ranges", Map.of("inZoned", new ApiValue.Decimal(new BigDecimal("12345.67")))),
            new Shape("TSTVARCHAR", "tstvarchar", "7. varchar and code-page characters", Map.of("in2", new ApiValue.Text("HELLO"), "in4", new ApiValue.Text("WORLD!"),
                    "inText", new ApiValue.Text("@!{}[]|~#$ÄÖÜß"))),
            new Shape("TSTFLOAT", "tstfloat", "8. floats", Map.of("in4", new ApiValue.Decimal(new BigDecimal("1.5")), "in8", new ApiValue.Decimal(new BigDecimal("10")))),
            new Shape("TSTDATES", "tstdates", "9. dates and times", Map.of("inDate", new ApiValue.Date(java.time.LocalDate.of(2026, 11, 18)),
                    "inTime", new ApiValue.Time(java.time.LocalTime.of(10, 15, 30)))),
            new Shape("TSTCOUNTS", "tstcounts", "10. fixed dimensions with separate counts", Map.of("wanted", new ApiValue.Integer(BigInteger.valueOf(3)))),
            new Shape("TSTMIDNGT", "tstmidngt", "11. midnight and the end of the day", Map.of("isoIn", new ApiValue.Time(java.time.LocalTime.MIDNIGHT),
                    "hmsIn", new ApiValue.Time(java.time.LocalTime.MIDNIGHT), "usaIn", new ApiValue.Time(java.time.LocalTime.MIDNIGHT),
                    "usaIn2", new ApiValue.Time(java.time.LocalTime.MIDNIGHT), "eurIn", new ApiValue.Time(java.time.LocalTime.MIDNIGHT),
                    "inStamp", new ApiValue.DateTime(java.time.LocalDateTime.of(2026, 11, 19, 0, 0)))),
            new Shape("TSTFORMAT", "tstformat", "12. the remaining formats and separators", Map.of("inDate", new ApiValue.Date(java.time.LocalDate.of(2026, 11, 19)),
                    "inTime", new ApiValue.Time(java.time.LocalTime.of(10, 15, 30)))),
            new Shape("TSTSTMP12", "tststmp12", "timestamps of 12 and 0 fraction digits", Map.of("inStamp",
                    new ApiValue.DateTime(java.time.LocalDateTime.of(2026, 11, 19, 10, 15, 30, 123456789)))));

    /** What one way gave for one program. */
    record Result(boolean worked, String failure, Map<String, String> outputs, long millis, List<byte[]> rawBytes) {
    }

    @Test
    void callsProgramsOneToFourInBothWaysAndWritesTheReport() throws Exception {
        Path pcmlDir = IbmiSystem.output().resolve("pcml");
        assertThat(pcmlDir.resolve("TSTSCALAR.pcml")).as("the setup tool runs first and writes the compiler's PCML").exists();
        Map<String, String> compilerPcml = new LinkedHashMap<>();
        for (Shape shape : SHAPES) {
            compilerPcml.put(shape.program(), Files.readString(pcmlDir.resolve(shape.program() + ".pcml")));
        }
        for (Shape shape : RAW_ONLY) {
            compilerPcml.put(shape.program(), Files.readString(pcmlDir.resolve(shape.program() + ".pcml")));
        }
        Map<String, ApiOperation> operations = operations(compilerPcml);
        Map<String, List<WireValue>> inputs = new LinkedHashMap<>();
        for (Shape shape : SHAPES) {
            inputs.put(shape.program(), OperationCodec.of(operations.get(shape.id())).encode(shape.request(), Map.of()).parameters().orElseThrow());
        }

        Map<String, Result> document = new LinkedHashMap<>();
        AS400 system = IbmiSystem.connect();
        int ccsid = system.getCcsid();
        try {
            for (Shape shape : SHAPES) {
                document.put(shape.program(), document(system, shape, compilerPcml.get(shape.program()), ProgramLayout.of(operations.get(shape.id())), inputs.get(shape.program())));
            }
        } finally {
            system.disconnectAllServices();
        }

        Map<String, Result> raw = new LinkedHashMap<>();
        Map<String, String> responses = new LinkedHashMap<>();
        IbmiConnection connection = new Jt400Connector(IbmiSystem.settings(List.of(IbmiSystem.library()), 30)).open();
        try {
            for (Shape shape : SHAPES) {
                ApiOperation operation = operations.get(shape.id());
                Result result = raw(connection, ProgramLayout.of(operation), inputs.get(shape.program()));
                raw.put(shape.program(), result);
                if (result.worked()) {
                    DecodeResult decoded = OperationCodec.of(operation).decode(rawValues(connection, ProgramLayout.of(operation), result, inputs.get(shape.program())));
                    responses.put(shape.program(), decoded.problems().isEmpty() ? String.valueOf(decoded.response().orElse(Map.of()))
                            : "decode problems: " + decoded.problems().stream().map(p -> p.code() + " " + p.fieldPath()).collect(Collectors.joining(", ")));
                }
            }
            for (Shape shape : RAW_ONLY) {
                ApiOperation operation = operations.get(shape.id());
                List<WireValue> input = OperationCodec.of(operation).encode(shape.request(), Map.of()).parameters().orElseThrow();
                inputs.put(shape.program(), input);
                Result result = raw(connection, ProgramLayout.of(operation), input);
                raw.put(shape.program(), result);
                if (result.worked()) {
                    try {
                        DecodeResult decoded = OperationCodec.of(operation).decode(rawValues(connection, ProgramLayout.of(operation), result, input));
                        responses.put(shape.program(), decoded.problems().isEmpty() ? String.valueOf(decoded.response().orElse(Map.of()))
                                : "decode problems: " + decoded.problems().stream().map(p -> p.code() + " " + p.fieldPath()).collect(Collectors.joining(", ")));
                    } catch (LayoutException e) {
                        responses.put(shape.program(), "none: the layout refuses " + e.getMessage() + " (the gateway answers 502)");
                    }
                }
            }
        } finally {
            connection.close();
        }

        String report = IbmiSystem.redact(report(ccsid, document, raw, responses) + rawOnlyReport(ccsid, operations, raw, responses));
        Files.createDirectories(IbmiSystem.output());
        Files.writeString(IbmiSystem.output().resolve("spike-report.md"), report);
        System.out.println(report);
    }

    /** The operations of the fixture repository, built with the compiler's PCML in place of the fixtures' own. */
    private Map<String, ApiOperation> operations(Map<String, String> compilerPcml) throws Exception {
        Path copy = dir.resolve("ibmi-compiled");
        Path source = IbmiSystem.root().resolve("fixtures/ibmi");
        try (var files = Files.walk(source.resolve("apis"))) {
            for (Path file : (Iterable<Path>) files::iterator) {
                Path target = copy.resolve(source.relativize(file).toString());
                if (Files.isDirectory(file)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(file, target);
                }
            }
        }
        compilerPcml.forEach((program, pcml) -> {
            try {
                Files.writeString(copy.resolve("apis/" + program + "/" + program + ".pcml"), pcml);
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
        Path bundle = dir.resolve("ibmi-compiled.rpgapi");
        TaskResult result = new Tasks(dir).bundle(copy, new BundleRequest("ibmi", "1.0.0", bundle, Optional.empty(), Optional.empty()));
        assertThat(result.hasError()).as("the fixtures' overlays with the compiler's PCML: " + result.diagnostics()).isFalse();
        Map<String, ApiOperation> operations = new LinkedHashMap<>();
        BundleReader.read(bundle).bundle().orElseThrow().model().operations().forEach(op -> operations.put(op.id(), op));
        return operations;
    }

    // ---- Document way

    private static Result document(AS400 system, Shape shape, String redactedPcml, ProgramLayout layout, List<WireValue> input) {
        String pcml = redactedPcml.replace("/" + IbmiSystem.LIBRARY_PLACEHOLDER + ".LIB/", "/" + IbmiSystem.library() + ".LIB/");
        try {
            ProgramCallDocument call = new ProgramCallDocument(system, shape.program(), new ByteArrayInputStream(pcml.getBytes(StandardCharsets.UTF_8)), SpikeIT.class.getClassLoader(), null,
                    ProgramCallDocument.SOURCE_PCML);
            for (int i = 0; i < layout.parameters().size(); i++) {
                ProgramLayout.Parameter parameter = layout.parameters().get(i);
                if (parameter.passing() != ProgramLayout.Passing.OUTPUT) {
                    set(call, shape.program(), parameter.layout(), input.get(i), new int[0]);
                }
            }
            long started = System.nanoTime();
            boolean worked = call.callProgram(shape.program());
            long millis = (System.nanoTime() - started) / 1_000_000;
            if (!worked) {
                AS400Message[] messages = call.getMessageList(shape.program());
                return new Result(false, "callProgram returned false: " + (messages == null ? "no message"
                        : Arrays.stream(messages).map(m -> m.getID() + " " + m.getText()).collect(Collectors.joining("; "))), Map.of(), millis, List.of());
            }
            Map<String, String> outputs = new LinkedHashMap<>();
            for (ProgramLayout.Parameter parameter : layout.parameters()) {
                if (parameter.passing() != ProgramLayout.Passing.INPUT) {
                    get(call, shape.program(), parameter.layout(), new int[0], outputs);
                }
            }
            return new Result(true, "", outputs, millis, List.of());
        } catch (Exception e) {
            return new Result(false, e.getClass().getName() + ": " + e.getMessage(), Map.of(), 0, List.of());
        }
    }

    private static void set(ProgramCallDocument call, String program, Layout layout, WireValue value, int[] indices) throws Exception {
        switch (layout) {
            case Layout.Struct struct -> {
                for (int i = 0; i < struct.members().size(); i++) {
                    WireValue member = value instanceof WireValue.Struct s ? s.members().get(i) : new WireValue.Blank();
                    set(call, program, struct.members().get(i), member, indices);
                }
            }
            case Layout.Array array -> {
                for (int i = 0; i < array.count(); i++) {
                    WireValue element = value instanceof WireValue.Array a ? a.elements().get(i) : new WireValue.Blank();
                    set(call, program, array.element(), element, append(indices, i));
                }
            }
            default -> {
                Object object = documentValue(layout, value);
                if (indices.length == 0) {
                    call.setValue(program + "." + layout.path(), object);
                } else {
                    call.setValue(program + "." + layout.path(), indices, object);
                }
            }
        }
    }

    /** The Java value that ProgramCallDocument takes for a field. */
    private static Object documentValue(Layout layout, WireValue value) {
        boolean blank = value instanceof WireValue.Blank;
        return switch (layout) {
            case Layout.Text t -> blank ? "" : ((WireValue.Text) value).value();
            case Layout.VarText v -> blank ? "" : ((WireValue.Text) value).value();
            case Layout.Decimal d -> blank ? BigDecimal.ZERO : ((WireValue.Decimal) value).value();
            case Layout.Int i -> {
                BigInteger number = blank ? BigInteger.ZERO : ((WireValue.Integer) value).value();
                yield switch (i.bytes()) {
                    case 2 -> i.signed() ? (Object) number.shortValueExact() : number.intValueExact();
                    case 4 -> i.signed() ? (Object) number.intValueExact() : number.longValueExact();
                    default -> i.signed() ? (Object) number.longValueExact() : number;
                };
            }
            case Layout.Float f -> blank ? 0.0 : ((WireValue.Float) value).value();
            case Layout.Bytes b -> blank ? new byte[b.length()] : ((WireValue.Bytes) value).value();
            default -> throw new IllegalArgumentException(layout.path());
        };
    }

    private static void get(ProgramCallDocument call, String program, Layout layout, int[] indices, Map<String, String> outputs) throws Exception {
        switch (layout) {
            case Layout.Struct struct -> {
                for (Layout member : struct.members()) {
                    get(call, program, member, indices, outputs);
                }
            }
            case Layout.Array array -> {
                for (int i = 0; i < array.count(); i++) {
                    get(call, program, array.element(), append(indices, i), outputs);
                }
            }
            default -> {
                Object value = indices.length == 0 ? call.getValue(program + "." + layout.path()) : call.getValue(program + "." + layout.path(), indices);
                outputs.put(leaf(layout.path(), indices), normal(value));
            }
        }
    }

    // ---- Raw way

    private static Result raw(IbmiConnection connection, ProgramLayout layout, List<WireValue> input) {
        try {
            List<ParameterBytes> bytes = layout.encode(input, connection.ccsid());
            long started = System.nanoTime();
            CallReply reply = connection.call(layout.programPath(), bytes);
            long millis = (System.nanoTime() - started) / 1_000_000;
            if (reply instanceof CallReply.Escaped escaped) {
                return new Result(false, "escape: " + escaped.messages().stream().map(m -> m.id() + " " + m.text()).collect(Collectors.joining("; ")), Map.of(), millis, List.of());
            }
            List<byte[]> output = ((CallReply.Completed) reply).output();
            Map<String, String> outputs = new LinkedHashMap<>();
            for (int i = 0; i < layout.parameters().size(); i++) {
                ProgramLayout.Parameter parameter = layout.parameters().get(i);
                if (parameter.passing() != ProgramLayout.Passing.INPUT) {
                    // One parameter at a time, so that a value the layout refuses does not hide the others.
                    try {
                        flatten(parameter.layout(), parameter.layout().read(output.get(i), 0, connection.ccsid()), new int[0], outputs);
                    } catch (LayoutException e) {
                        outputs.put(parameter.name(), "refused by the layout: " + e.getMessage());
                    }
                }
            }
            return new Result(true, "", outputs, millis, output);
        } catch (Exception e) {
            return new Result(false, e.getClass().getName() + ": " + e.getMessage(), Map.of(), 0, List.of());
        }
    }

    private static List<WireValue> rawValues(IbmiConnection connection, ProgramLayout layout, Result result, List<WireValue> input) {
        return layout.decode(result.rawBytes(), input, connection.ccsid());
    }

    private static void flatten(Layout layout, WireValue value, int[] indices, Map<String, String> outputs) {
        switch (layout) {
            case Layout.Struct struct -> {
                for (int i = 0; i < struct.members().size(); i++) {
                    flatten(struct.members().get(i), ((WireValue.Struct) value).members().get(i), indices, outputs);
                }
            }
            case Layout.Array array -> {
                for (int i = 0; i < array.count(); i++) {
                    flatten(array.element(), ((WireValue.Array) value).elements().get(i), append(indices, i), outputs);
                }
            }
            default -> outputs.put(leaf(layout.path(), indices), normal(switch (value) {
                case WireValue.Text t -> t.value();
                case WireValue.Decimal d -> d.value();
                case WireValue.Integer n -> n.value();
                case WireValue.Float f -> f.value();
                case WireValue.Bytes b -> b.value();
                default -> String.valueOf(value);
            }));
        }
    }

    // ---- shared

    private static int[] append(int[] indices, int index) {
        int[] out = Arrays.copyOf(indices, indices.length + 1);
        out[indices.length] = index;
        return out;
    }

    private static String leaf(String path, int[] indices) {
        return path + (indices.length == 0 ? "" : Arrays.toString(indices));
    }

    /** A comparable text: trailing blanks removed, numbers without trailing zeros, bytes in hex. */
    static String normal(Object value) {
        return switch (value) {
            case null -> "null";
            case String s -> "\"" + Layout.Text.rtrim(s) + "\"";
            case BigDecimal d -> d.signum() == 0 ? "0" : d.stripTrailingZeros().toPlainString();
            case Double d -> normal(BigDecimal.valueOf(d));
            case Float f -> normal(BigDecimal.valueOf(f));
            case Number n -> normal(new BigDecimal(n.toString()));
            case byte[] b -> HexFormat.of().withUpperCase().formatHex(b);
            default -> value.getClass().getSimpleName() + ":" + value;
        };
    }

    private static String report(int ccsid, Map<String, Result> document, Map<String, Result> raw, Map<String, String> responses) {
        StringBuilder out = new StringBuilder("# Spike report: how structs travel (gateway kickoff 03)\n\n");
        out.append("Job CCSID of the connections: ").append(ccsid).append(". Library written as <library>.\n\n");
        out.append("## Which way works for each shape\n\n| Shape | Document | Raw |\n|---|---|---|\n");
        for (Shape shape : SHAPES) {
            out.append("| ").append(shape.shape()).append(" (").append(shape.program()).append(") | ").append(cell(document.get(shape.program()))).append(" | ")
                    .append(cell(raw.get(shape.program()))).append(" |\n");
        }
        out.append("\n## Same output values where both work\n\n");
        for (Shape shape : SHAPES) {
            Result d = document.get(shape.program());
            Result r = raw.get(shape.program());
            out.append("- ").append(shape.program()).append(": ");
            if (!d.worked() || !r.worked()) {
                out.append("not compared, one way failed.\n");
                continue;
            }
            List<String> differences = new ArrayList<>();
            r.outputs().forEach((leaf, value) -> {
                String other = d.outputs().get(leaf);
                if (!value.equals(other)) {
                    differences.add(leaf + ": Raw " + value + ", Document " + other);
                }
            });
            d.outputs().keySet().stream().filter(leaf -> !r.outputs().containsKey(leaf)).forEach(leaf -> differences.add(leaf + ": Document only"));
            out.append(differences.isEmpty() ? "the same " + r.outputs().size() + " output values.\n" : String.join("; ", differences) + "\n");
        }
        out.append("\n## Output values (Raw)\n\n");
        for (Shape shape : SHAPES) {
            Result r = raw.get(shape.program());
            out.append("- ").append(shape.program()).append(": ").append(r.worked() ? r.outputs().toString() : "failed").append('\n');
            if (responses.containsKey(shape.program())) {
                out.append("  - API response: ").append(responses.get(shape.program())).append('\n');
            }
        }
        out.append("\n## Round-trip time of one call, a single observation\n\n| Program | Document | Raw |\n|---|---|---|\n");
        for (Shape shape : SHAPES) {
            out.append("| ").append(shape.program()).append(" | ").append(document.get(shape.program()).millis()).append(" ms | ").append(raw.get(shape.program()).millis())
                    .append(" ms |\n");
        }
        out.append("\n## Bytes as the programs returned them (Raw), for CCSID, padding and signs\n\n");
        for (Shape shape : SHAPES) {
            Result r = raw.get(shape.program());
            if (!r.worked()) {
                continue;
            }
            out.append("- ").append(shape.program()).append(":\n");
            for (int i = 0; i < r.rawBytes().size(); i++) {
                byte[] bytes = r.rawBytes().get(i);
                if (bytes.length > 0) {
                    out.append("  - parameter ").append(i + 1).append(": ").append(HexFormat.of().withUpperCase().formatHex(bytes, 0, Math.min(bytes.length, 64)))
                            .append(bytes.length > 64 ? " ... (" + bytes.length + " bytes)" : "").append('\n');
                }
            }
        }
        return out.toString();
    }

    /** Programs 6 to 10: for each, every output parameter as bytes, its values, and the text of date and time fields. */
    private static String rawOnlyReport(int ccsid, Map<String, ApiOperation> operations, Map<String, Result> raw, Map<String, String> responses) {
        StringBuilder out = new StringBuilder("\n## Programs 6 to 12 and the timestamp probe, raw call only (kickoffs 04 and 05)\n\n| Program | Raw | Time |\n|---|---|---|\n");
        for (Shape shape : RAW_ONLY) {
            Result r = raw.get(shape.program());
            out.append("| ").append(shape.shape()).append(" (").append(shape.program()).append(") | ").append(cell(r)).append(" | ").append(r.millis()).append(" ms |\n");
        }
        for (Shape shape : RAW_ONLY) {
            Result r = raw.get(shape.program());
            out.append("\n### ").append(shape.program()).append("\n\n");
            if (!r.worked()) {
                out.append("Failed: `").append(r.failure()).append("`\n");
                continue;
            }
            ProgramLayout layout = ProgramLayout.of(operations.get(shape.id()));
            out.append("| Parameter | Bytes returned | Size |\n|---|---|---|\n");
            for (int i = 0; i < layout.parameters().size(); i++) {
                ProgramLayout.Parameter parameter = layout.parameters().get(i);
                byte[] bytes = r.rawBytes().get(i);
                if (bytes.length == 0) {
                    continue;
                }
                String hex = HexFormat.of().withUpperCase().formatHex(bytes);
                String text = parameter.layout() instanceof Layout.Date || parameter.layout() instanceof Layout.Time || parameter.layout() instanceof Layout.Timestamp
                        ? " (text `" + new com.ibm.as400.access.AS400Text(bytes.length, ccsid).toObject(bytes) + "`)" : "";
                out.append("| ").append(parameter.name()).append(" | `").append(hex).append('`').append(text).append(" | ").append(bytes.length).append(" |\n");
            }
            out.append("\nValues read: ").append(r.outputs()).append("\n\nAPI response: ").append(responses.getOrDefault(shape.program(), "none")).append('\n');
        }
        return out.toString();
    }

    private static String cell(Result result) {
        return result.worked() ? "works" : "fails: `" + result.failure().replace("|", "/").replace("\n", " ") + "`";
    }
}
