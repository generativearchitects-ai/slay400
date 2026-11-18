package ai.generativearchitects.slay400.gateway.ibmi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.generativearchitects.slay400.pcml.codec.WireValue;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Byte layouts against bytes written out by hand from the IBM i data formats, independent of JT400: EBCDIC CCSID 37 and 500,
 * packed and zoned decimals with their sign nibbles, big-endian binary integers, IEEE floats.
 */
class LayoutTest {

    static final int CCSID_37 = 37;

    static String hex(byte[] bytes) {
        return HexFormat.of().withUpperCase().formatHex(bytes);
    }

    static byte[] bytes(String hex) {
        return HexFormat.of().parseHex(hex);
    }

    static WireValue text(String value) {
        return new WireValue.Text(value);
    }

    static WireValue decimal(String value) {
        return new WireValue.Decimal(new BigDecimal(value));
    }

    static WireValue integer(long value) {
        return new WireValue.Integer(BigInteger.valueOf(value));
    }

    // ---- character data

    @Test
    void textIsPaddedWithEbcdicBlanksInTheJobCcsid() {
        Layout layout = new Layout.Text("F", 4, Optional.empty());
        assertThat(layout.size()).isEqualTo(4);
        assertThat(hex(layout.toBytes(text("AB"), CCSID_37))).isEqualTo("C1C24040");
        assertThat(hex(layout.toBytes(text("a1 Z"), CCSID_37))).isEqualTo("81F140E9");
        assertThat(layout.read(bytes("C1C24040"), 0, CCSID_37)).isEqualTo(text("AB  "));
        assertThat(hex(layout.toBytes(new WireValue.Blank(), CCSID_37))).isEqualTo("40404040");
    }

    @Test
    void theCcsidOfTheFieldWinsOverThatOfTheJob() {
        // '!' is 0x5A in CCSID 37 and 0x4F in CCSID 500.
        assertThat(hex(new Layout.Text("F", 1, Optional.empty()).toBytes(text("!"), CCSID_37))).isEqualTo("5A");
        assertThat(hex(new Layout.Text("F", 1, Optional.empty()).toBytes(text("!"), 500))).isEqualTo("4F");
        assertThat(hex(new Layout.Text("F", 1, Optional.of(500)).toBytes(text("!"), CCSID_37))).isEqualTo("4F");
        assertThat(new Layout.Text("F", 1, Optional.of(500)).read(bytes("4F"), 0, CCSID_37)).isEqualTo(text("!"));
    }

    @Test
    void aCharacterTheCcsidCannotHoldIsRefusedNotReplaced() {
        // CCSID 37 has no euro sign; CCSID 1140 has it at 0x9F.
        assertThatThrownBy(() -> new Layout.Text("P.NAME", 3, Optional.empty()).toBytes(text("A€"), CCSID_37)).isInstanceOf(LayoutException.class)
                .hasMessage("P.NAME: the text has a character that the CCSID of the field cannot hold");
        assertThat(hex(new Layout.Text("F", 1, Optional.of(1140)).toBytes(text("€"), CCSID_37))).isEqualTo("9F");
    }

    @Test
    void textLongerThanTheFieldIsRefusedWithoutItsValue() {
        assertThatThrownBy(() -> new Layout.Text("F", 2, Optional.empty()).toBytes(text("SECRET"), CCSID_37)).isInstanceOf(LayoutException.class)
                .hasMessage("F: the text is longer than the field").hasMessageNotContaining("SECRET");
    }

    @Test
    void anUnknownCcsidIsALayoutException() {
        assertThatThrownBy(() -> new Layout.Text("F", 2, Optional.of(99999)).toBytes(text("A"), CCSID_37)).isInstanceOf(LayoutException.class);
        assertThatThrownBy(() -> new Layout.Text("F", 2, Optional.of(99999)).read(bytes("C1C1"), 0, CCSID_37)).isInstanceOf(LayoutException.class);
    }

    @Test
    void varcharHasALengthPrefixAndAlwaysItsMaximumSize() {
        Layout two = new Layout.VarText("F", 5, 2, Optional.empty());
        assertThat(two.size()).isEqualTo(7);
        assertThat(hex(two.toBytes(text("AB"), CCSID_37))).isEqualTo("0002C1C2000000");
        assertThat(two.read(bytes("0002C1C2000000"), 0, CCSID_37)).isEqualTo(text("AB"));
        assertThat(hex(two.toBytes(new WireValue.Blank(), CCSID_37))).isEqualTo("00000000000000");
        assertThat(two.read(bytes("00000000000000"), 0, CCSID_37)).isEqualTo(text(""));
        Layout four = new Layout.VarText("F", 2, 4, Optional.empty());
        assertThat(hex(four.toBytes(text("A"), CCSID_37))).isEqualTo("00000001C100");
        assertThat(four.read(bytes("00000002C1C2"), 0, CCSID_37)).isEqualTo(text("AB"));
        assertThatThrownBy(() -> two.read(bytes("0006C1C2000000"), 0, CCSID_37)).isInstanceOf(LayoutException.class).hasMessage("F: the length prefix is outside the field");
        assertThatThrownBy(() -> four.read(bytes("FFFFFFFFC1C2"), 0, CCSID_37)).isInstanceOf(LayoutException.class);
        assertThatThrownBy(() -> two.toBytes(text("TOOLONG"), CCSID_37)).isInstanceOf(LayoutException.class);
        assertThatThrownBy(() -> new Layout.VarText("F", 5, 3, Optional.empty())).isInstanceOf(IllegalArgumentException.class);
    }

    // ---- packed and zoned

    @ParameterizedTest
    @CsvSource({"7,2,123.45,0012345F", "7,2,-12.34,0001234D", "7,2,0.00,0000000F", "7,2,99999.99,9999999F", "7,2,-99999.99,9999999D",
            "6,0,42,0000042F", "1,0,-5,5D", "9,2,123.45,000012345F"})
    void packedDecimalsAreTwoDigitsPerByteWithTheSignInTheLastNibble(int digits, int scale, String value, String expected) {
        Layout layout = new Layout.Decimal("F", digits, scale, true);
        assertThat(layout.size()).isEqualTo(digits / 2 + 1);
        assertThat(hex(layout.toBytes(decimal(value), CCSID_37))).isEqualTo(expected);
        assertThat(layout.read(bytes(expected), 0, CCSID_37)).isEqualTo(new WireValue.Decimal(new BigDecimal(value).setScale(scale)));
    }

    @Test
    void packedSignsCAndBAreReadAsPositiveAndNegative() {
        Layout layout = new Layout.Decimal("F", 5, 0, true);
        assertThat(layout.read(bytes("12345C"), 0, CCSID_37)).isEqualTo(decimal("12345"));
        assertThat(layout.read(bytes("12345B"), 0, CCSID_37)).isEqualTo(decimal("-12345"));
    }

    @Test
    void bytesThatAreNotAPackedNumberAreALayoutException() {
        assertThatThrownBy(() -> new Layout.Decimal("P.QTY", 5, 0, true).read(bytes("404040"), 0, CCSID_37)).isInstanceOf(LayoutException.class)
                .hasMessage("P.QTY: the bytes are not a valid packed number");
        assertThatThrownBy(() -> new Layout.Decimal("P.QTY", 3, 0, false).read(bytes("404040"), 0, CCSID_37)).isInstanceOf(LayoutException.class)
                .hasMessage("P.QTY: the bytes are not a valid zoned number");
    }

    @ParameterizedTest
    @CsvSource({"5,0,42,F0F0F0F4F2", "5,0,-42,F0F0F0F4D2", "7,2,-123.45,F0F0F1F2F3F4D5", "7,2,0,F0F0F0F0F0F0F0", "3,3,0.125,F1F2F5"})
    void zonedDecimalsAreOneDigitPerByteWithTheSignInTheLastZone(int digits, int scale, String value, String expected) {
        Layout layout = new Layout.Decimal("F", digits, scale, false);
        assertThat(layout.size()).isEqualTo(digits);
        assertThat(hex(layout.toBytes(decimal(value), CCSID_37))).isEqualTo(expected);
        assertThat(layout.read(bytes(expected), 0, CCSID_37)).isEqualTo(new WireValue.Decimal(new BigDecimal(value).setScale(scale)));
    }

    @Test
    void aDecimalThatDoesNotFitIsRefusedNotRoundedOrCut() {
        Layout layout = new Layout.Decimal("F", 5, 2, true);
        assertThatThrownBy(() -> layout.toBytes(decimal("1000.00"), CCSID_37)).isInstanceOf(LayoutException.class).hasMessage("F: the number does not fit the field");
        assertThatThrownBy(() -> layout.toBytes(decimal("1.234"), CCSID_37)).isInstanceOf(LayoutException.class).hasMessage("F: the number has more decimals than the field");
        assertThat(hex(layout.toBytes(decimal("1.2300"), CCSID_37))).isEqualTo("00123F");
        assertThat(hex(layout.toBytes(decimal("999.99"), CCSID_37))).isEqualTo("99999F");
        assertThat(hex(layout.toBytes(decimal("0.00000"), CCSID_37))).isEqualTo("00000F");
        assertThat(hex(layout.toBytes(new WireValue.Blank(), CCSID_37))).isEqualTo("00000F");
        assertThatThrownBy(() -> new Layout.Decimal("F", 3, 0, false).toBytes(decimal("1000"), CCSID_37)).isInstanceOf(LayoutException.class);
        assertThatThrownBy(() -> new Layout.Decimal("F", 0, 0, true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Layout.Decimal("F", 64, 0, true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Layout.Decimal("F", 3, 4, true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Layout.Decimal("F", 3, -1, true)).isInstanceOf(IllegalArgumentException.class);
    }

    // ---- integers, floats, bytes

    @ParameterizedTest
    @CsvSource({"2,true,-2,FFFE", "2,true,32767,7FFF", "2,true,-32768,8000", "4,true,-2,FFFFFFFE", "4,true,2147483647,7FFFFFFF",
            "8,true,-9223372036854775808,8000000000000000", "8,true,1,0000000000000001", "2,false,65535,FFFF", "4,false,4294967295,FFFFFFFF",
            "8,false,18446744073709551615,FFFFFFFFFFFFFFFF", "8,false,0,0000000000000000"})
    void integersAreBigEndianTwosComplementOrUnsigned(int size, boolean signed, String value, String expected) {
        Layout layout = new Layout.Int("F", size, signed);
        assertThat(layout.size()).isEqualTo(size);
        WireValue number = new WireValue.Integer(new BigInteger(value));
        assertThat(hex(layout.toBytes(number, CCSID_37))).isEqualTo(expected);
        assertThat(layout.read(bytes(expected), 0, CCSID_37)).isEqualTo(number);
    }

    @ParameterizedTest
    @CsvSource({"2,true,32768", "2,true,-32769", "2,false,-1", "2,false,65536", "4,true,2147483648", "4,false,4294967296", "8,true,9223372036854775808",
            "8,false,18446744073709551616", "8,false,-1"})
    void anIntegerOutsideItsFieldIsRefused(int size, boolean signed, String value) {
        assertThatThrownBy(() -> new Layout.Int("F", size, signed).toBytes(new WireValue.Integer(new BigInteger(value)), CCSID_37))
                .isInstanceOf(LayoutException.class).hasMessage("F: the number does not fit the field");
    }

    @Test
    void integerBlanksAreZeroAndOtherSizesAreRefused() {
        assertThat(hex(new Layout.Int("F", 4, true).toBytes(new WireValue.Blank(), CCSID_37))).isEqualTo("00000000");
        assertThatThrownBy(() -> new Layout.Int("F", 3, true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Layout.Int("F", 1, true)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void floatsAreIeeeBigEndian() {
        assertThat(hex(new Layout.Float("F", 8).toBytes(new WireValue.Float(1.5), CCSID_37))).isEqualTo("3FF8000000000000");
        assertThat(hex(new Layout.Float("F", 4).toBytes(new WireValue.Float(1.5), CCSID_37))).isEqualTo("3FC00000");
        assertThat(new Layout.Float("F", 4).read(bytes("C0200000"), 0, CCSID_37)).isEqualTo(new WireValue.Float(-2.5));
        assertThat(new Layout.Float("F", 8).read(bytes("C004000000000000"), 0, CCSID_37)).isEqualTo(new WireValue.Float(-2.5));
        assertThat(hex(new Layout.Float("F", 4).toBytes(new WireValue.Blank(), CCSID_37))).isEqualTo("00000000");
        assertThatThrownBy(() -> new Layout.Float("F", 8).toBytes(new WireValue.Float(Double.NaN), CCSID_37)).isInstanceOf(LayoutException.class);
        assertThatThrownBy(() -> new Layout.Float("F", 8).toBytes(new WireValue.Float(Double.POSITIVE_INFINITY), CCSID_37)).isInstanceOf(LayoutException.class);
        assertThatThrownBy(() -> new Layout.Float("F", 4).toBytes(new WireValue.Float(1e39), CCSID_37)).isInstanceOf(LayoutException.class);
        assertThat(hex(new Layout.Float("F", 4).toBytes(new WireValue.Float(Float.MAX_VALUE), CCSID_37))).isEqualTo("7F7FFFFF");
        assertThatThrownBy(() -> new Layout.Float("F", 2)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void bytesAreCopiedAndFollowedByZeros() {
        Layout layout = new Layout.Bytes("F", 4);
        assertThat(hex(layout.toBytes(new WireValue.Bytes(bytes("0102")), CCSID_37))).isEqualTo("01020000");
        assertThat(layout.read(bytes("0A0B0C0D"), 0, CCSID_37)).isEqualTo(new WireValue.Bytes(bytes("0A0B0C0D")));
        assertThat(hex(layout.toBytes(new WireValue.Blank(), CCSID_37))).isEqualTo("00000000");
        assertThatThrownBy(() -> layout.toBytes(new WireValue.Bytes(new byte[5]), CCSID_37)).isInstanceOf(LayoutException.class);
    }

    // ---- structs and arrays

    @Test
    void aStructIsItsMembersOneAfterTheOtherWithoutAlignment() {
        Layout layout = new Layout.Struct("ITEM", List.of(new Layout.Text("ITEM.CODE", 3, Optional.empty()), new Layout.Decimal("ITEM.QTY", 5, 0, true),
                new Layout.Int("ITEM.N", 4, true)));
        assertThat(layout.size()).isEqualTo(10);
        WireValue value = new WireValue.Struct(List.of(text("AB "), decimal("12"), integer(7)));
        assertThat(hex(layout.toBytes(value, CCSID_37))).isEqualTo("C1C240" + "00012F" + "00000007");
        assertThat(layout.read(bytes("C1C24000012F00000007"), 0, CCSID_37)).isEqualTo(value);
        assertThat(hex(layout.toBytes(new WireValue.Blank(), CCSID_37))).isEqualTo("404040" + "00000F" + "00000000");
        assertThatThrownBy(() -> layout.toBytes(new WireValue.Struct(List.of(text("A"))), CCSID_37)).isInstanceOf(LayoutException.class)
                .hasMessage("ITEM: the struct does not have the members of the field");
    }

    @Test
    void anArrayIsItsElementsOneAfterTheOtherAtTheFullDeclaredSize() {
        Layout element = new Layout.Struct("ROWS", List.of(new Layout.Int("ROWS.ID", 2, true), new Layout.Text("ROWS.L", 1, Optional.empty())));
        Layout layout = new Layout.Array("ROWS", 3, element);
        assertThat(layout.size()).isEqualTo(9);
        WireValue value = new WireValue.Array(List.of(new WireValue.Struct(List.of(integer(1), text("A"))), new WireValue.Blank(),
                new WireValue.Struct(List.of(integer(3), text("C")))));
        assertThat(hex(layout.toBytes(value, CCSID_37))).isEqualTo("0001C1" + "000040" + "0003C3");
        assertThat(layout.read(bytes("0001C10002C20003C3"), 0, CCSID_37)).isEqualTo(new WireValue.Array(List.of(
                new WireValue.Struct(List.of(integer(1), text("A"))), new WireValue.Struct(List.of(integer(2), text("B"))),
                new WireValue.Struct(List.of(integer(3), text("C"))))));
        assertThat(hex(layout.toBytes(new WireValue.Blank(), CCSID_37))).isEqualTo("000040000040000040");
        assertThatThrownBy(() -> layout.toBytes(new WireValue.Array(List.of(new WireValue.Blank())), CCSID_37)).isInstanceOf(LayoutException.class)
                .hasMessage("ROWS: the list does not have the number of elements of the field");
        assertThat(new Layout.Array("E", 0, element).size()).isZero();
        assertThatThrownBy(() -> new Layout.Array("E", -1, element)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aStructHoldingAnArrayOfStructs() {
        Layout line = new Layout.Struct("O.LINES", List.of(new Layout.Text("O.LINES.SKU", 2, Optional.empty()), new Layout.Int("O.LINES.QTY", 2, true)));
        Layout order = new Layout.Struct("O", List.of(new Layout.Decimal("O.TOTAL", 3, 1, true), new Layout.Array("O.LINES", 2, line)));
        assertThat(order.size()).isEqualTo(2 + 2 * 4);
        WireValue value = new WireValue.Struct(List.of(decimal("12.5"), new WireValue.Array(List.of(new WireValue.Struct(List.of(text("A1"), integer(10))),
                new WireValue.Struct(List.of(text("B2"), integer(20)))))));
        String expected = "125F" + "C1F1000A" + "C2F20014";
        assertThat(hex(order.toBytes(value, CCSID_37))).isEqualTo(expected);
        assertThat(order.read(bytes(expected), 0, CCSID_37)).isEqualTo(value);
    }

    @Test
    void aValueOfTheWrongKindIsALayoutExceptionNamingTheField() {
        WireValue wrong = new WireValue.Float(1);
        assertThatThrownBy(() -> new Layout.Text("A", 1, Optional.empty()).toBytes(wrong, CCSID_37)).hasMessage("A: text was expected");
        assertThatThrownBy(() -> new Layout.VarText("A", 1, 2, Optional.empty()).toBytes(wrong, CCSID_37)).hasMessage("A: text was expected");
        assertThatThrownBy(() -> new Layout.Int("A", 2, true).toBytes(wrong, CCSID_37)).hasMessage("A: a whole number was expected");
        assertThatThrownBy(() -> new Layout.Decimal("A", 2, 0, true).toBytes(wrong, CCSID_37)).hasMessage("A: a decimal was expected");
        assertThatThrownBy(() -> new Layout.Float("A", 4).toBytes(text("x"), CCSID_37)).hasMessage("A: a floating-point number was expected");
        assertThatThrownBy(() -> new Layout.Bytes("A", 1).toBytes(wrong, CCSID_37)).hasMessage("A: bytes was expected");
        assertThatThrownBy(() -> new Layout.Struct("A", List.of()).toBytes(wrong, CCSID_37)).hasMessage("A: a struct was expected");
        assertThatThrownBy(() -> new Layout.Array("A", 0, new Layout.Bytes("A", 1)).toBytes(wrong, CCSID_37)).hasMessage("A: a list was expected");
        assertThat(new LayoutException("X.Y", "r").path()).isEqualTo("X.Y");
        assertThat(new LayoutException("X.Y", "r").getStackTrace()).isEmpty();
    }

    @Test
    void writingAtAnOffsetLeavesTheRestAlone() {
        byte[] into = bytes("FFFFFFFFFF");
        new Layout.Int("F", 2, true).write(integer(1), into, 2, CCSID_37);
        assertThat(hex(into)).isEqualTo("FFFF0001FF");
        assertThat(new Layout.Int("F", 2, true).read(into, 2, CCSID_37)).isEqualTo(integer(1));
        byte[] text = bytes("FFFFFF");
        new Layout.Text("F", 1, Optional.empty()).write(text("A"), text, 1, CCSID_37);
        assertThat(hex(text)).isEqualTo("FFC1FF");
        byte[] var = bytes("FFFFFFFFFF");
        new Layout.VarText("F", 2, 2, Optional.empty()).write(text("A"), var, 1, CCSID_37);
        assertThat(hex(var)).isEqualTo("FF0001C100");
        byte[] raw = bytes("FFFFFF");
        new Layout.Bytes("F", 1).write(new WireValue.Bytes(bytes("01")), raw, 1, CCSID_37);
        assertThat(hex(raw)).isEqualTo("FF01FF");
    }
}
