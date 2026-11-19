package ai.generativearchitects.slay400.gateway.ibmi;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.slay400.gateway.ibmi.Temporal.DateFormat;
import ai.generativearchitects.slay400.gateway.ibmi.Temporal.TimeFormat;
import ai.generativearchitects.slay400.pcml.codec.WireValue;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Bytes a real IBM i returned for the invented test programs 6 to 10, in the ibmi run 44515579603 (kickoff 04, job CCSID 273),
 * as test vectors of the layout (decision 121): each is read to the value the program was written to return, and that value
 * is written back to the same bytes. Only the invented values of the test programs are here.
 */
class SystemVectorsTest {

    static final int CCSID = 273;

    static byte[] bytes(String hex) {
        return HexFormat.of().parseHex(hex);
    }

    static void vector(Layout layout, String hex, WireValue value) {
        assertThat(layout.read(bytes(hex), 0, CCSID)).as(layout.path()).isEqualTo(value);
        assertThat(HexFormat.of().withUpperCase().formatHex(layout.toBytes(value, CCSID))).as(layout.path()).isEqualTo(hex);
    }

    static WireValue dec(String value) {
        return new WireValue.Decimal(new BigDecimal(value));
    }

    static WireValue whole(String value) {
        return new WireValue.Integer(new BigInteger(value));
    }

    @Test
    void program6ZonedAndPackedSignsAndIntegerLimits() {
        vector(new Layout.Decimal("ZONEDNEG", 7, 2, false), "F1F2F3F4F5F6D7", dec("-12345.67"));
        vector(new Layout.Decimal("ZONEDZERO", 7, 2, false), "F0F0F0F0F0F0F0", dec("0.00"));
        vector(new Layout.Decimal("ZONEDMAX", 7, 2, false), "F9F9F9F9F9F9F9", dec("99999.99"));
        vector(new Layout.Decimal("PACKEDNEG", 7, 2, true), "1234567D", dec("-12345.67"));
        vector(new Layout.Decimal("PACKEDZERO", 7, 2, true), "0000000F", dec("0.00"));
        vector(new Layout.Decimal("PACKEDMAX", 7, 2, true), "9999999F", dec("99999.99"));
        vector(new Layout.Int("INT2MIN", 2, true), "8000", whole("-32768"));
        vector(new Layout.Int("INT2MAX", 2, true), "7FFF", whole("32767"));
        vector(new Layout.Int("INT4MIN", 4, true), "80000000", whole("-2147483648"));
        vector(new Layout.Int("INT4MAX", 4, true), "7FFFFFFF", whole("2147483647"));
        vector(new Layout.Int("INT8MIN", 8, true), "8000000000000000", whole("-9223372036854775808"));
        vector(new Layout.Int("INT8MAX", 8, true), "7FFFFFFFFFFFFFFF", whole("9223372036854775807"));
        vector(new Layout.Int("UNS2MAX", 2, false), "FFFF", whole("65535"));
        vector(new Layout.Int("UNS4MAX", 4, false), "FFFFFFFF", whole("4294967295"));
        vector(new Layout.Int("UNS8MAX", 8, false), "FFFFFFFFFFFFFFFF", whole("18446744073709551615"));
    }

    @Test
    void program7VarcharPrefixesAndPaddingAndCodePage273() {
        // The system pads the rest of a varchar field with 0x00, as the layout writes it.
        vector(new Layout.VarText("ECHO2", 10, 2, Optional.empty()), "0005C8C5D3D3D60000000000", new WireValue.Text("HELLO"));
        vector(new Layout.VarText("ECHO4", 10, 4, Optional.empty()), "00000006E6D6D9D3C44F00000000", new WireValue.Text("WORLD!"));
        vector(new Layout.VarText("EMPTY2", 10, 2, Optional.empty()), "000000000000000000000000", new WireValue.Text(""));
        vector(new Layout.VarText("PART2", 10, 2, Optional.empty()), "0003C1C2C300000000000000", new WireValue.Text("ABC"));
        vector(new Layout.VarText("FULL2", 10, 2, Optional.empty()), "000AC1C2C3C4C5C6C7C8C9D1", new WireValue.Text("ABCDEFGHIJ"));
        vector(new Layout.VarText("EMPTY4", 10, 4, Optional.empty()), "0000000000000000000000000000", new WireValue.Text(""));
        vector(new Layout.VarText("PART4", 10, 4, Optional.empty()), "00000003E7E8E900000000000000", new WireValue.Text("XYZ"));
        vector(new Layout.VarText("FULL4", 10, 4, Optional.empty()), "0000000AD2D3D4D5D6D7D8D9E2E3", new WireValue.Text("KLMNOPQRST"));
        vector(new Layout.Text("PAGES", 16, Optional.empty()), "B54F43DC63FCBB597B5B4AE05AA14040", new WireValue.Text("@!{}[]|~#$ÄÖÜß  "));
    }

    @Test
    void program8FloatsAreIeeeBigEndian() {
        vector(new Layout.Float("OUT4", 4), "40400000", new WireValue.Float(3.0));
        vector(new Layout.Float("OUT8", 8), "4004000000000000", new WireValue.Float(2.5));
        vector(new Layout.Float("FIXED4", 4), "BFC00000", new WireValue.Float(-1.5));
        vector(new Layout.Float("FIXED8", 8), "42074876E8000000", new WireValue.Float(1.25e10));
    }

    @Test
    void program9DatesAndTimesInTheFormatsOfTheirPcml() {
        vector(new Layout.Date("ISONEXT", DateFormat.ISO, '-'), "F2F0F2F660F1F160F1F9", new WireValue.Date(LocalDate.of(2026, 11, 19)));
        vector(new Layout.Date("ISOLOW", DateFormat.ISO, '-'), "F0F0F0F160F0F160F0F1", new WireValue.Date(LocalDate.of(1, 1, 1)));
        vector(new Layout.Date("ISOHIGH", DateFormat.ISO, '-'), "F9F9F9F960F1F260F3F1", new WireValue.Date(LocalDate.of(9999, 12, 31)));
        vector(new Layout.Date("MDYLOW", DateFormat.MDY, '/'), "F0F161F0F161F4F0", new WireValue.Date(LocalDate.of(1940, 1, 1)));
        vector(new Layout.Date("MDYHIGH", DateFormat.MDY, '/'), "F1F261F3F161F3F9", new WireValue.Date(LocalDate.of(2039, 12, 31)));
        vector(new Layout.Date("EURDATE", DateFormat.EUR, '.'), "F1F84BF1F14BF2F0F2F6", new WireValue.Date(LocalDate.of(2026, 11, 18)));
        vector(new Layout.Date("JULDATE", DateFormat.JUL, '/'), "F2F661F3F2F2", new WireValue.Date(LocalDate.of(2026, 11, 18)));
        vector(new Layout.Date("YMDDOT", DateFormat.YMD, '.'), "F2F64BF1F14BF1F8", new WireValue.Date(LocalDate.of(2026, 11, 18)));
        vector(new Layout.Time("HMSNEXT", TimeFormat.HMS, ':'), "F1F17AF1F57AF3F0", new WireValue.Time(LocalTime.of(11, 15, 30)));
        vector(new Layout.Time("USATIME", TimeFormat.USA, ':'), "F1F07AF1F540C1D4", new WireValue.Time(LocalTime.of(10, 15)));
        vector(new Layout.Time("EURTIME", TimeFormat.EUR, '.'), "F1F04BF1F54BF3F0", new WireValue.Time(LocalTime.of(10, 15, 30)));
    }

    @Test
    void program10ArraysTravelAtTheirFullDeclaredSizeWhateverTheCount() {
        List<WireValue> items = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            items.add(new WireValue.Text(i <= 3 ? "ITEM" + i + "   " : "        "));
        }
        vector(new Layout.Array("ITEMS", 10, new Layout.Text("ITEMS", 8, Optional.empty())),
                "C9E3C5D4F1404040C9E3C5D4F2404040C9E3C5D4F3404040" + "40".repeat(56), new WireValue.Array(items));
        List<WireValue> lines = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            lines.add(new WireValue.Text(i <= 3 ? "LINE" + i + " " : "      "));
        }
        Layout box = new Layout.Struct("BOX", List.of(new Layout.Int("BOX.N", 4, true), new Layout.Array("BOX.LINES", 5, new Layout.Text("BOX.LINES", 6, Optional.empty()))));
        assertThat(box.size()).isEqualTo(34);
        vector(box, "00000003D3C9D5C5F140D3C9D5C5F240D3C9D5C5F340" + "40".repeat(12), new WireValue.Struct(List.of(whole("3"), new WireValue.Array(lines))));
    }
}
