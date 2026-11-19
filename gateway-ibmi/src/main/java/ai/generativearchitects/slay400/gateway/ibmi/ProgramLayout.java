package ai.generativearchitects.slay400.gateway.ibmi;

import ai.generativearchitects.slay400.pcml.api.ApiOperation;
import ai.generativearchitects.slay400.pcml.api.FieldBinding;
import ai.generativearchitects.slay400.pcml.codec.WireValue;
import ai.generativearchitects.slay400.pcml.model.Count;
import ai.generativearchitects.slay400.pcml.model.FieldDef;
import ai.generativearchitects.slay400.pcml.model.Length;
import ai.generativearchitects.slay400.pcml.model.ProgramDef;
import ai.generativearchitects.slay400.pcml.model.Usage;
import ai.generativearchitects.slay400.pcml.model.WireType;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The raw call of an operation's program: the byte layout of every top-level parameter, built from the wire types of the
 * descriptor, and the program's address through the library list. One layout code serves scalars, structs and arrays
 * (decision 114).
 *
 * <p>The program is called as {@code /QSYS.LIB/%LIBL%.LIB/<name>.PGM}, never through the library written in the PCML
 * (decision 117). The name is the object name of the PCML path, or the program name when there is no path.
 */
public final class ProgramLayout {

    /** How a parameter travels. */
    public enum Passing {
        /** Sent only ({@code usage="input"}). */
        INPUT,
        /** Returned only ({@code usage="output"}). */
        OUTPUT,
        /** Sent and returned ({@code inputoutput}, or {@code inherit} at the top level). */
        BOTH
    }

    /**
     * One top-level parameter.
     *
     * @param name    the parameter name in the PCML
     * @param layout  its byte layout
     * @param passing how it travels
     */
    public record Parameter(String name, Layout layout, Passing passing) {
        /** @throws NullPointerException if an argument is {@code null} */
        public Parameter {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(layout, "layout");
            Objects.requireNonNull(passing, "passing");
        }
    }

    /** The CCSID of character data that is not converted: bytes. */
    private static final int NO_CONVERSION = 65535;
    private static final Pattern OBJECT_NAME = Pattern.compile("[A-Z$#@][A-Z0-9$#@_.]{0,9}");
    private static final Pattern PROGRAM_PATH = Pattern.compile("(?i)/QSYS\\.LIB/(?:[^/]+\\.LIB/)?([^/]+)\\.PGM");

    private final String programPath;
    private final List<Parameter> parameters;

    private ProgramLayout(String programPath, List<Parameter> parameters) {
        this.programPath = programPath;
        this.parameters = List.copyOf(parameters);
    }

    /**
     * Why the raw call cannot serve an operation yet, one message per reason; empty when it can. Messages name fields and
     * features, never a value.
     */
    public static List<String> unsupported(ApiOperation operation) {
        List<String> problems = new ArrayList<>();
        build(operation, problems);
        return problems;
    }

    /**
     * The layout of an operation.
     *
     * @throws IllegalArgumentException if the operation is {@link #unsupported(ApiOperation) unsupported}
     */
    public static ProgramLayout of(ApiOperation operation) {
        List<String> problems = new ArrayList<>();
        ProgramLayout layout = build(operation, problems);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("the operation " + operation.id() + " cannot be called: " + String.join("; ", problems));
        }
        return layout;
    }

    private static ProgramLayout build(ApiOperation operation, List<String> problems) {
        ProgramDef program = operation.program();
        if (program.entryPoint().isPresent()) {
            problems.add("a procedure of a service program is not supported yet");
        }
        if (program.returnValue().isPresent()) {
            problems.add("a return value is not supported yet");
        }
        String name = objectName(program, problems);
        List<FieldBinding> bindings = operation.bindings();
        List<FieldDef> fields = program.parameters();
        boolean sameParameters = bindings.size() == fields.size();
        for (int i = 0; sameParameters && i < fields.size(); i++) {
            sameParameters = bindings.get(i).field().name().equals(fields.get(i).name());
        }
        if (!sameParameters) {
            problems.add("the bindings of the operation are not the parameters of the program, in order");
            return null;
        }
        List<Parameter> parameters = new ArrayList<>();
        for (FieldBinding binding : bindings) {
            Layout layout = field(binding, binding.fieldPath(), problems);
            Passing passing = switch (binding.field().usage()) {
                case INPUT -> Passing.INPUT;
                case OUTPUT -> Passing.OUTPUT;
                case INPUT_OUTPUT, INHERIT -> Passing.BOTH;
            };
            if (layout != null) {
                parameters.add(new Parameter(binding.field().name(), layout, passing));
            }
        }
        return problems.isEmpty() ? new ProgramLayout("/QSYS.LIB/%LIBL%.LIB/" + name + ".PGM", parameters) : null;
    }

    private static String objectName(ProgramDef program, List<String> problems) {
        String name = program.name();
        if (program.path().isPresent()) {
            Matcher matcher = PROGRAM_PATH.matcher(program.path().get());
            if (!matcher.matches()) {
                problems.add("the PCML path is not that of a program object");
                return "";
            }
            name = matcher.group(1);
        }
        String upper = name.toUpperCase(Locale.ROOT);
        if (!OBJECT_NAME.matcher(upper).matches()) {
            problems.add("the program name is not an IBM i object name");
        }
        return upper;
    }

    /** The layout of a field with its count. */
    private static Layout field(FieldBinding binding, String path, List<String> problems) {
        Layout element = element(binding, path, problems);
        Count count = binding.field().count();
        return switch (count) {
            case Count.None none -> element;
            case Count.Fixed fixed -> element == null ? null : new Layout.Array(path, fixed.value(), element);
            case Count.Ref ref -> {
                problems.add(path + ": a count that names another field is not supported yet");
                yield null;
            }
        };
    }

    private static Layout element(FieldBinding binding, String path, List<String> problems) {
        FieldDef field = binding.field();
        return switch (field.wire()) {
            case WireType.Char c -> {
                if (c.ccsid().isPresent() && c.ccsid().get() == NO_CONVERSION) {
                    problems.add(path + ": a char field with CCSID 65535 holds bytes, which slay400-core cannot carry as bytes yet");
                    yield null;
                }
                yield fixed(c.length(), path, problems).map(length -> (Layout) new Layout.Text(path, length, c.ccsid())).orElse(null);
            }
            case WireType.VarChar v -> {
                if (v.ccsid().isPresent() && v.ccsid().get() == NO_CONVERSION) {
                    problems.add(path + ": a varchar field with CCSID 65535 holds bytes, which slay400-core cannot carry as bytes yet");
                    yield null;
                }
                int prefix = v.varLength().orElse(2);
                if (prefix != 2 && prefix != 4) {
                    problems.add(path + ": a varchar length prefix of " + prefix + " bytes is not supported");
                    yield null;
                }
                yield fixed(v.length(), path, problems).map(length -> (Layout) new Layout.VarText(path, length, prefix, v.ccsid())).orElse(null);
            }
            case WireType.Int i -> {
                int bits = i.lengthBytes() * 8;
                boolean sizeOk = i.lengthBytes() == 2 || i.lengthBytes() == 4 || i.lengthBytes() == 8;
                boolean signed = i.precision().isEmpty() || i.precision().get() == bits - 1;
                boolean unsigned = i.precision().isPresent() && i.precision().get() == bits;
                if (!sizeOk || !signed && !unsigned) {
                    problems.add(path + ": an int of this length and precision is not supported");
                    yield null;
                }
                yield new Layout.Int(path, i.lengthBytes(), signed);
            }
            case WireType.Packed p -> decimal(path, p.length(), p.precision(), true, problems);
            case WireType.Zoned z -> decimal(path, z.length(), z.precision(), false, problems);
            case WireType.Float f -> {
                if (f.lengthBytes() != 4 && f.lengthBytes() != 8) {
                    problems.add(path + ": a float of this length is not supported");
                    yield null;
                }
                yield new Layout.Float(path, f.lengthBytes());
            }
            case WireType.Byte b -> {
                problems.add(path + ": a byte field has not been returned by a real system yet (decision 121)");
                yield null;
            }
            case WireType.Struct s -> {
                List<Layout> members = new ArrayList<>();
                boolean ok = true;
                for (FieldBinding child : binding.children()) {
                    Layout member = field(child, child.fieldPath(), problems);
                    ok &= member != null;
                    members.add(member);
                }
                yield ok ? new Layout.Struct(path, members) : null;
            }
            case WireType.Date d -> date(field, path, problems);
            case WireType.Time t -> time(field, path, problems);
            case WireType.Timestamp t -> timestamp(field, path, problems);
        };
    }

    private static Optional<Integer> fixed(Length length, String path, List<String> problems) {
        return switch (length) {
            case Length.Fixed fixed -> Optional.of(fixed.value());
            case Length.Ref ref -> {
                problems.add(path + ": a length that names another field is not supported yet");
                yield Optional.empty();
            }
        };
    }

    private static Layout decimal(String path, int digits, int scale, boolean packed, List<String> problems) {
        if (digits < 1 || digits > 63 || scale < 0 || scale > digits) {
            problems.add(path + ": a decimal of this length and precision is not supported");
            return null;
        }
        return new Layout.Decimal(path, digits, scale, packed);
    }

    /** A date laid out as its PCML attributes say, never by assumption. */
    /** Date formats the compiler refused as a parameter (kickoff 05, run 1). */
    private static final java.util.Set<String> REFUSED_DATE_FORMATS = java.util.Set.of("LONGJUL", "CYMD", "CMDY", "CDMY");

    private static Layout date(FieldDef field, String path, List<String> problems) {
        String format = field.attributes().get("dateformat");
        if (format != null && REFUSED_DATE_FORMATS.contains(format.toUpperCase(Locale.ROOT))) {
            problems.add(path + ": the date format " + format + " was refused by the RPG compiler as a parameter, so no real system has returned it");
            return null;
        }
        String separator = field.attributes().get("dateseparator");
        if (format == null || separator == null) {
            problems.add(path + ": the PCML states no " + (format == null ? "dateformat" : "dateseparator") + " for the date, so it is not laid out by assumption");
            return null;
        }
        Optional<Temporal.DateFormat> known = Temporal.dateFormat(format);
        Optional<Character> character = Temporal.separator(separator);
        if (known.isEmpty() || character.isEmpty()) {
            problems.add(path + ": the date format " + format + " with the separator " + separator + " has not been returned by a real system yet (decision 121)");
            return null;
        }
        return new Layout.Date(path, known.get(), character.get());
    }

    /**
     * A timestamp: one fixed text whose length the PCML states when it is not the default. The lengths a real system returned
     * are accepted (decision 121): none (26 bytes, 6 fraction digits), 19 (none) and 32 (12).
     */
    private static Layout timestamp(FieldDef field, String path, List<String> problems) {
        String length = field.attributes().get("length");
        Integer digits = switch (length == null ? "" : length) {
            case "" -> 6;
            case "19" -> 0;
            case "32" -> 12;
            default -> null;
        };
        if (digits == null) {
            problems.add(path + ": a timestamp of length " + length + " has not been returned by a real system yet (decision 121)");
            return null;
        }
        return new Layout.Timestamp(path, digits);
    }

    /** A time laid out as its PCML attributes say, never by assumption. */
    private static Layout time(FieldDef field, String path, List<String> problems) {
        String format = field.attributes().get("timeformat");
        String separator = field.attributes().get("timeseparator");
        if (format == null || separator == null) {
            problems.add(path + ": the PCML states no " + (format == null ? "timeformat" : "timeseparator") + " for the time, so it is not laid out by assumption");
            return null;
        }
        Optional<Temporal.TimeFormat> known = Temporal.timeFormat(format);
        Optional<Character> character = Temporal.separator(separator);
        if (known.isEmpty() || character.isEmpty()) {
            problems.add(path + ": the time format " + format + " with the separator " + separator + " has not been returned by a real system yet (decision 121)");
            return null;
        }
        return new Layout.Time(path, known.get(), character.get());
    }

    /** The path of the program through the library list. */
    public String programPath() {
        return programPath;
    }

    /** The parameters in order. */
    public List<Parameter> parameters() {
        return parameters;
    }

    /**
     * The bytes of the parameters, from the values {@code OperationCodec.encode} produced.
     *
     * @param ccsid the CCSID of the connection's job
     * @throws LayoutException if a value does not fit its layout
     */
    public List<ParameterBytes> encode(List<WireValue> input, int ccsid) {
        if (input.size() != parameters.size()) {
            throw new LayoutException(programPath, "the call has " + input.size() + " values for " + parameters.size() + " parameters");
        }
        List<ParameterBytes> bytes = new ArrayList<>(parameters.size());
        for (int i = 0; i < parameters.size(); i++) {
            Parameter parameter = parameters.get(i);
            bytes.add(switch (parameter.passing()) {
                case INPUT -> ParameterBytes.in(parameter.layout().toBytes(input.get(i), ccsid));
                case OUTPUT -> ParameterBytes.out(parameter.layout().size());
                case BOTH -> ParameterBytes.both(parameter.layout().toBytes(input.get(i), ccsid));
            });
        }
        return bytes;
    }

    /**
     * The values of the parameters after the call, as {@code OperationCodec.decode} takes them. An input-only parameter keeps
     * the value it was sent with.
     *
     * @param output the bytes of every parameter after the call
     * @param input  the values the call was made with
     * @param ccsid  the CCSID of the connection's job
     * @throws LayoutException if the bytes of a parameter are not a value of its layout, or have the wrong length
     */
    public List<WireValue> decode(List<byte[]> output, List<WireValue> input, int ccsid) {
        if (output.size() != parameters.size() || input.size() != parameters.size()) {
            throw new LayoutException(programPath, "the program returned " + output.size() + " parameters instead of " + parameters.size());
        }
        List<WireValue> values = new ArrayList<>(parameters.size());
        for (int i = 0; i < parameters.size(); i++) {
            Parameter parameter = parameters.get(i);
            if (parameter.passing() == Passing.INPUT) {
                values.add(input.get(i));
                continue;
            }
            byte[] bytes = output.get(i);
            if (bytes.length != parameter.layout().size()) {
                throw new LayoutException(parameter.layout().path(), "the program returned " + bytes.length + " bytes instead of " + parameter.layout().size());
            }
            values.add(parameter.layout().read(bytes, 0, ccsid));
        }
        return values;
    }
}
