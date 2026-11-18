package ai.generativearchitects.slay400.gateway.ibmi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.generativearchitects.slay400.gateway.ibmi.ProgramLayout.Passing;
import ai.generativearchitects.slay400.pcml.api.ApiOperation;
import ai.generativearchitects.slay400.pcml.api.FieldBinding;
import ai.generativearchitects.slay400.pcml.model.Count;
import ai.generativearchitects.slay400.pcml.model.FieldDef;
import ai.generativearchitects.slay400.pcml.model.Length;
import ai.generativearchitects.slay400.pcml.model.ProgramDef;
import ai.generativearchitects.slay400.pcml.model.WireType;
import java.util.Optional;
import ai.generativearchitects.slay400.pcml.codec.ApiValue;
import ai.generativearchitects.slay400.pcml.codec.DecodeResult;
import ai.generativearchitects.slay400.pcml.codec.OperationCodec;
import ai.generativearchitects.slay400.pcml.codec.WireValue;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The layouts of the five test programs, built from the descriptor, and what the raw call does not support yet. */
class ProgramLayoutTest {

    @TempDir
    static Path dir;
    static Map<String, ApiOperation> operations;

    @BeforeAll
    static void build() {
        operations = operations(dir);
    }

    static Map<String, ApiOperation> operations(Path dir) {
        return IbmiFixtures.operations(dir);
    }

    static String hex(byte[] bytes) {
        return HexFormat.of().withUpperCase().formatHex(bytes);
    }

    @ParameterizedTest
    @CsvSource({"tstscalar,TSTSCALAR,8", "tststruct,TSTSTRUCT,1", "tstarray,TSTARRAY,3", "tstnested,TSTNESTED,5", "tstwait,TSTWAIT,2", "tstnone,TSTNONE,1"})
    void theProgramIsCalledThroughTheLibraryListNotTheLibraryOfThePcml(String id, String program, int parameters) {
        ProgramLayout layout = ProgramLayout.of(operations.get(id));
        assertThat(operations.get(id).program().path()).hasValue("/QSYS.LIB/OWNERLIB.LIB/" + program + ".PGM");
        assertThat(layout.programPath()).isEqualTo("/QSYS.LIB/%LIBL%.LIB/" + program + ".PGM");
        assertThat(layout.parameters()).hasSize(parameters);
        assertThat(ProgramLayout.unsupported(operations.get(id))).isEmpty();
    }

    @Test
    void theScalarsOfProgram1HaveTheSizesOfTheirWireTypes() {
        ProgramLayout layout = ProgramLayout.of(operations.get("tstscalar"));
        assertThat(layout.parameters()).extracting(p -> p.name() + ":" + p.layout().size() + ":" + p.passing()).containsExactly("INTEXT:10:INPUT",
                "INPACKED:4:INPUT", "INZONED:5:INPUT", "ININT:4:INPUT", "OUTTEXT:20:BOTH", "OUTPACKED:5:BOTH", "OUTZONED:7:BOTH", "OUTINT:8:BOTH");
    }

    @Test
    void program1EncodesTheRequestThroughTheCodecIntoTheBytesThatTheProgramReads() {
        ApiOperation operation = operations.get("tstscalar");
        OperationCodec codec = OperationCodec.of(operation);
        List<WireValue> wire = codec.encode(Map.of("text", new ApiValue.Text("HELLO"), "packed", new ApiValue.Decimal(new BigDecimal("-12.34")),
                "zoned", new ApiValue.Integer(BigInteger.valueOf(42)), "whole", new ApiValue.Integer(BigInteger.valueOf(-7))), Map.of()).parameters().orElseThrow();
        List<ParameterBytes> bytes = ProgramLayout.of(operation).encode(wire, 37);
        assertThat(hex(bytes.get(0).input().orElseThrow())).isEqualTo("C8C5D3D3D64040404040");
        assertThat(hex(bytes.get(1).input().orElseThrow())).isEqualTo("0001234D");
        assertThat(hex(bytes.get(2).input().orElseThrow())).isEqualTo("F0F0F0F4F2");
        assertThat(hex(bytes.get(3).input().orElseThrow())).isEqualTo("FFFFFFF9");
        assertThat(bytes.get(0).outputLength()).isZero();
        // The outputs are sent blank and come back at their full size.
        assertThat(hex(bytes.get(4).input().orElseThrow())).isEqualTo("40".repeat(20));
        assertThat(bytes.get(4).outputLength()).isEqualTo(20);
        assertThat(hex(bytes.get(5).input().orElseThrow())).isEqualTo("000000000F");
        assertThat(hex(bytes.get(7).input().orElseThrow())).isEqualTo("0000000000000000");
    }

    @Test
    void program1DecodesWhatTheProgramWouldReturnIntoTheResponse() {
        ApiOperation operation = operations.get("tstscalar");
        OperationCodec codec = OperationCodec.of(operation);
        List<WireValue> wire = codec.encode(Map.of("text", new ApiValue.Text("HELLO"), "packed", new ApiValue.Decimal(new BigDecimal("-12.34")),
                "zoned", new ApiValue.Integer(BigInteger.valueOf(42)), "whole", new ApiValue.Integer(BigInteger.valueOf(-7))), Map.of()).parameters().orElseThrow();
        ProgramLayout layout = ProgramLayout.of(operation);
        List<byte[]> output = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            output.add(new byte[0]);
        }
        output.add(HexFormat.of().parseHex("C5C3C8D67AC8C5D3D3D6" + "40".repeat(10)));
        output.add(HexFormat.of().parseHex("000002468D"));
        output.add(HexFormat.of().parseHex("F0F0F0F0F4F4F3"));
        output.add(HexFormat.of().parseHex("FFFFFFFFFFFFFFEB"));
        List<WireValue> values = layout.decode(output, wire, 37);
        assertThat(values.subList(0, 4)).isEqualTo(wire.subList(0, 4));
        DecodeResult decoded = codec.decode(values);
        assertThat(decoded.problems()).isEmpty();
        assertThat(decoded.response().orElseThrow()).containsEntry("echo", new ApiValue.Text("ECHO:HELLO")).containsEntry("doubled", new ApiValue.Decimal(new BigDecimal("-24.68")))
                .containsEntry("plusOne", new ApiValue.Integer(BigInteger.valueOf(443))).containsEntry("tripled", new ApiValue.Integer(BigInteger.valueOf(-21)));
    }

    @Test
    void theStructsAndArraysOfPrograms2To4HaveTheirFullDeclaredSize() {
        assertThat(ProgramLayout.of(operations.get("tststruct")).parameters().get(0).layout().size()).isEqualTo(6 + 3 + 7 + 1);
        ProgramLayout array = ProgramLayout.of(operations.get("tstarray"));
        assertThat(array.parameters()).extracting(p -> p.name() + ":" + p.layout().size()).containsExactly("WANTED:4", "COUNT:4", "ROWS:" + 10 * (4 + 12 + 4));
        assertThat(array.parameters().get(2).layout()).isInstanceOf(Layout.Array.class);
        ProgramLayout nested = ProgramLayout.of(operations.get("tstnested"));
        assertThat(nested.parameters()).extracting(p -> p.name() + ":" + p.layout().size()).containsExactly("KEY:5", "ORDER:" + (5 + 3 * (8 + 4)), "ERRFLAG:2",
                "ERRCODE:7", "ERRTEXT:40");
    }

    @Test
    void program3RoundTripsThroughCodecAndLayout() {
        ApiOperation operation = operations.get("tstarray");
        OperationCodec codec = OperationCodec.of(operation);
        List<WireValue> wire = codec.encode(Map.of("wanted", new ApiValue.Integer(BigInteger.TWO)), Map.of()).parameters().orElseThrow();
        ProgramLayout layout = ProgramLayout.of(operation);
        List<ParameterBytes> sent = layout.encode(wire, 37);
        // What the program would leave: count 2, two rows filled, the rest cleared.
        byte[] rows = new byte[200];
        String row1 = "00000001" + "D9D6E640F1" + "40".repeat(7) + "0000150F";
        String row2 = "00000002" + "D9D6E640F2" + "40".repeat(7) + "0000300F";
        String empty = "00000000" + "40".repeat(12) + "0000000F";
        byte[] filled = HexFormat.of().parseHex(row1 + row2 + empty.repeat(8));
        System.arraycopy(filled, 0, rows, 0, 200);
        List<byte[]> output = List.of(new byte[0], HexFormat.of().parseHex("00000002"), rows);
        assertThat(sent.get(2).outputLength()).isEqualTo(200);
        DecodeResult decoded = codec.decode(layout.decode(output, wire, 37));
        assertThat(decoded.problems()).isEmpty();
        assertThat(decoded.response().orElseThrow().get("rows")).isEqualTo(new ApiValue.Arr(List.of(
                new ApiValue.Obj(Map.of("id", new ApiValue.Integer(BigInteger.ONE), "label", new ApiValue.Text("ROW 1"), "amount", new ApiValue.Decimal(new BigDecimal("1.50")))),
                new ApiValue.Obj(Map.of("id", new ApiValue.Integer(BigInteger.TWO), "label", new ApiValue.Text("ROW 2"), "amount", new ApiValue.Decimal(new BigDecimal("3.00")))))));
    }

    @Test
    void outputBytesOfTheWrongLengthOrCountAreRefused() {
        ProgramLayout layout = ProgramLayout.of(operations.get("tstwait"));
        List<WireValue> input = List.of(new WireValue.Integer(BigInteger.ONE), new WireValue.Text(" "));
        assertThatThrownBy(() -> layout.decode(List.of(new byte[0], new byte[2]), input, 37)).isInstanceOf(LayoutException.class)
                .hasMessage("DONE: the program returned 2 bytes instead of 1");
        assertThatThrownBy(() -> layout.decode(List.of(new byte[0]), input, 37)).isInstanceOf(LayoutException.class);
        assertThatThrownBy(() -> layout.decode(List.of(new byte[0], new byte[1]), List.of(), 37)).isInstanceOf(LayoutException.class);
        assertThatThrownBy(() -> layout.encode(List.of(), 37)).isInstanceOf(LayoutException.class)
                .hasMessage("/QSYS.LIB/%LIBL%.LIB/TSTWAIT.PGM: the call has 0 values for 2 parameters");
        assertThat(layout.decode(List.of(new byte[0], new byte[] {(byte) 0xE8}), input, 37)).containsExactly(input.get(0), new WireValue.Text("Y"));
    }

    // ---- what the raw call refuses at start

    static final String CHAR_OUT = "    <data name=\"OUT\" type=\"char\" length=\"1\" usage=\"output\"/>\n";
    static final String OVERLAY = "program: %s\npcml: %s.pcml\nroute: /x\nmethod: POST\nversion: 1.0.0\n";

    private static List<String> unsupported(String program, String path, String parameters, String structs) {
        ApiOperation operation = IbmiFixtures.operation(dir, program, IbmiFixtures.pcml(program, path, parameters, structs), OVERLAY.formatted(program, program))
                .orElseThrow(() -> new IllegalStateException("the test repository does not build"));
        List<String> reasons = ProgramLayout.unsupported(operation);
        if (!reasons.isEmpty()) {
            assertThatThrownBy(() -> ProgramLayout.of(operation)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining(reasons.get(0));
        }
        return reasons;
    }

    @Test
    void dateTimeAndTimestampFieldsAreNotSupportedYet() {
        assertThat(unsupported("TDATE", "", CHAR_OUT + "    <data name=\"D\" type=\"date\" usage=\"output\"/>\n    <data name=\"T\" type=\"time\" usage=\"output\"/>\n"
                + "    <data name=\"S\" type=\"timestamp\" usage=\"output\"/>\n", "")).containsExactly(
                "D: date, time and timestamp fields are not supported yet by the ibmi backend", "T: date, time and timestamp fields are not supported yet by the ibmi backend",
                "S: date, time and timestamp fields are not supported yet by the ibmi backend");
    }

    @Test
    void aCountThatNamesAnotherFieldIsNotSupportedYet() {
        assertThat(unsupported("TCOUNT", "", "    <data name=\"N\" type=\"int\" length=\"4\" precision=\"31\" usage=\"output\"/>\n"
                + "    <data name=\"V\" type=\"char\" length=\"2\" count=\"N\" usage=\"output\"/>\n", "")).containsExactly("V: a count that names another field is not supported yet");
    }

    @Test
    void aProgramPathThatIsNotAProgramIsRefused() {
        assertThat(unsupported("TPATH", " path=\"/QSYS.LIB/L.LIB/TPATH.SRVPGM\"", CHAR_OUT, "")).containsExactly("the PCML path is not that of a program object");
        assertThat(unsupported("TPATHOK", " path=\"/qsys.lib/l.lib/tpathok.pgm\"", CHAR_OUT, "")).isEmpty();
        assertThat(unsupported("TNOLIB", " path=\"/QSYS.LIB/TNOLIB.PGM\"", CHAR_OUT, "")).isEmpty();
    }

    @Test
    void aNameThatIsNotAnObjectNameIsRefusedAndOneWithoutPathIsUpperCased() {
        assertThat(unsupported("TOOLONGNAME", "", CHAR_OUT, "")).containsExactly("the program name is not an IBM i object name");
        ApiOperation lower = IbmiFixtures.operation(dir, "tlower", IbmiFixtures.pcml("tlower", "", CHAR_OUT, ""), OVERLAY.formatted("tlower", "tlower")).orElseThrow();
        assertThat(ProgramLayout.of(lower).programPath()).isEqualTo("/QSYS.LIB/%LIBL%.LIB/TLOWER.PGM");
    }

    @Test
    void everyScalarKindAndTheUsagesOfTheTopLevel() {
        ProgramLayout layout = ProgramLayout.of(IbmiFixtures.operation(dir, "TKINDS", IbmiFixtures.pcml("TKINDS", "", """
                    <data name="V2" type="varchar" length="8" usage="input"/>
                    <data name="V4" type="varchar" length="8" varlength="4" usage="input"/>
                    <data name="U2" type="int" length="2" precision="16" usage="input"/>
                    <data name="S8" type="int" length="8" usage="input"/>
                    <data name="F4" type="float" length="4" usage="input"/>
                    <data name="F8" type="float" length="8" usage="input"/>
                    <data name="B" type="byte" length="3" usage="input"/>
                    <data name="C" type="char" length="2" ccsid="500" usage="input"/>
                    <data name="I" type="char" length="1" usage="inherit"/>
                    <data name="O" type="char" length="1" usage="output"/>
                """, ""), OVERLAY.formatted("TKINDS", "TKINDS")).orElseThrow());
        assertThat(layout.parameters()).extracting(ProgramLayout.Parameter::layout).containsExactly(new Layout.VarText("V2", 8, 2, java.util.Optional.empty()),
                new Layout.VarText("V4", 8, 4, java.util.Optional.empty()), new Layout.Int("U2", 2, false), new Layout.Int("S8", 8, true), new Layout.Float("F4", 4),
                new Layout.Float("F8", 8), new Layout.Bytes("B", 3), new Layout.Text("C", 2, java.util.Optional.of(500)), new Layout.Text("I", 1, java.util.Optional.empty()),
                new Layout.Text("O", 1, java.util.Optional.empty()));
        assertThat(layout.parameters()).extracting(ProgramLayout.Parameter::passing).endsWith(Passing.BOTH, Passing.OUTPUT);
        List<ParameterBytes> bytes = layout.encode(List.of(new WireValue.Text("A"), new WireValue.Text(""), new WireValue.Integer(BigInteger.ONE), new WireValue.Integer(BigInteger.ONE),
                new WireValue.Float(1), new WireValue.Float(1), new WireValue.Bytes(new byte[] {1}), new WireValue.Text("!"), new WireValue.Text("X"), new WireValue.Blank()), 37);
        assertThat(bytes.get(9).input()).isEmpty();
        assertThat(bytes.get(9).outputLength()).isEqualTo(1);
        assertThat(bytes.get(8).outputLength()).isEqualTo(1);
        assertThat(hex(bytes.get(7).input().orElseThrow())).isEqualTo("4F40");
    }

    /** The operation of program 5 with the wire type of its first parameter replaced: shapes a descriptor could hold but the generator never writes. */
    static ApiOperation withWire(WireType wire, Count count) {
        ApiOperation operation = operations.get("tstwait");
        FieldBinding first = operation.bindings().get(0);
        FieldDef field = new FieldDef(first.field().name(), wire, first.field().usage(), count, first.field().attributes(), first.field().source());
        FieldBinding binding = new FieldBinding(first.fieldPath(), field, first.direction(), first.injection(), first.exposedName(), first.location(), first.semanticType(),
                first.codec(), first.required(), first.description(), first.example(), first.hidden(), first.trim(), first.children(), first.source());
        List<FieldBinding> bindings = new ArrayList<>(operation.bindings());
        bindings.set(0, binding);
        ProgramDef program = operation.program();
        List<FieldDef> parameters = new ArrayList<>(program.parameters());
        parameters.set(0, field);
        ProgramDef changed = new ProgramDef(program.name(), program.path(), program.entryPoint(), program.returnValue(), parameters, program.attributes(), program.source());
        return new ApiOperation(operation.id(), operation.version(), operation.summary(), operation.description(), operation.tags(), changed, operation.pcmlPath(),
                operation.overlayPath(), operation.route(), operation.method(), operation.callStrategy(), bindings, operation.errorMapping(), operation.cache(), operation.source());
    }

    @Test
    void sizesTheGeneratorNeverWritesAreStillRefusedByTheBackend() {
        assertThat(ProgramLayout.unsupported(withWire(new WireType.VarChar(new Length.Fixed(4), Optional.of(3), Optional.empty()), new Count.None())))
                .containsExactly("SECONDS: a varchar length prefix of 3 bytes is not supported");
        assertThat(ProgramLayout.unsupported(withWire(new WireType.Int(3, Optional.empty()), new Count.None())))
                .containsExactly("SECONDS: an int of this length and precision is not supported");
        assertThat(ProgramLayout.unsupported(withWire(new WireType.Int(4, Optional.of(20)), new Count.None())))
                .containsExactly("SECONDS: an int of this length and precision is not supported");
        assertThat(ProgramLayout.unsupported(withWire(new WireType.Int(4, Optional.of(32)), new Count.None()))).isEmpty();
        assertThat(ProgramLayout.unsupported(withWire(new WireType.Int(4, Optional.of(31)), new Count.None()))).isEmpty();
        assertThat(ProgramLayout.unsupported(withWire(new WireType.Float(2), new Count.None()))).containsExactly("SECONDS: a float of this length is not supported");
        assertThat(ProgramLayout.unsupported(withWire(new WireType.Packed(64, 0), new Count.None())))
                .containsExactly("SECONDS: a decimal of this length and precision is not supported");
        assertThat(ProgramLayout.unsupported(withWire(new WireType.Zoned(0, 0), new Count.None())))
                .containsExactly("SECONDS: a decimal of this length and precision is not supported");
        assertThat(ProgramLayout.unsupported(withWire(new WireType.Packed(3, 4), new Count.None())))
                .containsExactly("SECONDS: a decimal of this length and precision is not supported");
        assertThat(ProgramLayout.unsupported(withWire(new WireType.Packed(63, 63), new Count.None()))).isEmpty();
        assertThat(ProgramLayout.unsupported(withWire(new WireType.Char(new Length.Ref("X"), Optional.empty()), new Count.None())))
                .containsExactly("SECONDS: a length that names another field is not supported yet");
        assertThat(ProgramLayout.unsupported(withWire(new WireType.Byte(new Length.Ref("X")), new Count.None())))
                .containsExactly("SECONDS: a length that names another field is not supported yet");
        assertThat(ProgramLayout.unsupported(withWire(new WireType.VarChar(new Length.Ref("X"), Optional.empty(), Optional.empty()), new Count.None())))
                .containsExactly("SECONDS: a length that names another field is not supported yet");
        assertThat(ProgramLayout.of(withWire(new WireType.Int(2, Optional.empty()), new Count.Fixed(3))).parameters().get(0).layout())
                .isEqualTo(new Layout.Array("SECONDS", 3, new Layout.Int("SECONDS", 2, true)));
    }

    @Test
    void aServiceProgramAReturnValueAndBindingsThatAreNotTheParametersAreRefused() {
        ApiOperation operation = operations.get("tstwait");
        ProgramDef program = operation.program();
        ProgramDef service = new ProgramDef(program.name(), program.path(), Optional.of("PROC"), Optional.of("int"), program.parameters(), program.attributes(), program.source());
        ApiOperation changed = new ApiOperation(operation.id(), operation.version(), operation.summary(), operation.description(), operation.tags(), service,
                operation.pcmlPath(), operation.overlayPath(), operation.route(), operation.method(), operation.callStrategy(), operation.bindings(),
                operation.errorMapping(), operation.cache(), operation.source());
        assertThat(ProgramLayout.unsupported(changed)).containsExactly("a procedure of a service program is not supported yet", "a return value is not supported yet");
        ApiOperation fewer = new ApiOperation(operation.id(), operation.version(), operation.summary(), operation.description(), operation.tags(), program,
                operation.pcmlPath(), operation.overlayPath(), operation.route(), operation.method(), operation.callStrategy(), operation.bindings().subList(0, 1),
                operation.errorMapping(), operation.cache(), operation.source());
        assertThat(ProgramLayout.unsupported(fewer)).containsExactly("the bindings of the operation are not the parameters of the program, in order");
        List<FieldBinding> swapped = new ArrayList<>(operation.bindings());
        java.util.Collections.swap(swapped, 0, 1);
        ApiOperation other = new ApiOperation(operation.id(), operation.version(), operation.summary(), operation.description(), operation.tags(), program,
                operation.pcmlPath(), operation.overlayPath(), operation.route(), operation.method(), operation.callStrategy(), swapped,
                operation.errorMapping(), operation.cache(), operation.source());
        assertThat(ProgramLayout.unsupported(other)).containsExactly("the bindings of the operation are not the parameters of the program, in order");
    }

    @Test
    void aStructMemberThatCannotBeLaidOutMakesTheWholeStructUnsupported() {
        assertThat(unsupported("TSTRD", "", "    <data name=\"S\" type=\"struct\" struct=\"SD\" usage=\"output\"/>\n",
                "  <struct name=\"SD\">\n    <data name=\"A\" type=\"char\" length=\"1\"/>\n    <data name=\"D\" type=\"date\"/>\n  </struct>\n"))
                .containsExactly("S.D: date, time and timestamp fields are not supported yet by the ibmi backend");
    }

    @Test
    void aParameterBytesHasInputOutputOrBothAndShowsSizesOnly() {
        assertThat(ParameterBytes.in(new byte[] {1, 2})).hasToString("ParameterBytes[input=2 bytes, outputLength=0]");
        assertThat(ParameterBytes.out(3)).hasToString("ParameterBytes[input=none, outputLength=3]");
        byte[] input = {1};
        ParameterBytes both = ParameterBytes.both(input);
        input[0] = 9;
        assertThat(both.input().orElseThrow()).containsExactly(1);
        both.input().orElseThrow()[0] = 7;
        assertThat(both.input().orElseThrow()).containsExactly(1);
        assertThatThrownBy(() -> ParameterBytes.out(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ParameterBytes.out(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
