package ai.generativearchitects.slay400.gateway.ibmi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.generativearchitects.slay400.gateway.ibmi.Temporal.DateFormat;
import ai.generativearchitects.slay400.gateway.ibmi.Temporal.TimeFormat;
import ai.generativearchitects.slay400.pcml.codec.WireValue;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Date and time layouts against bytes written out by hand in EBCDIC CCSID 37 (digits F0 to F9, '-' 60, '/' 61, '.' 4B,
 * ':' 7A, blank 40), in the formats and with the separators the compiler wrote for program 9.
 */
class TemporalLayoutTest {

    static String hex(byte[] bytes) {
        return HexFormat.of().withUpperCase().formatHex(bytes);
    }

    static byte[] bytes(String hex) {
        return HexFormat.of().parseHex(hex);
    }

    @ParameterizedTest
    @CsvSource({
            "ISO,-,2026-11-18,F2F0F2F660F1F160F1F8", "ISO,-,0001-01-01,F0F0F0F160F0F160F0F1", "ISO,-,9999-12-31,F9F9F9F960F1F260F3F1",
            "MDY,/,1940-01-01,F0F161F0F161F4F0", "MDY,/,2039-12-31,F1F261F3F161F3F9", "MDY,/,1999-07-04,F0F761F0F461F9F9",
            "EUR,.,2026-11-18,F1F84BF1F14BF2F0F2F6", "JUL,/,2026-11-18,F2F661F3F2F2", "JUL,/,2024-12-31,F2F461F3F6F6",
            "YMD,.,2026-11-18,F2F64BF1F14BF1F8"})
    void datesInTheFormatAndWithTheSeparatorOfTheirPcml(DateFormat format, char separator, LocalDate date, String expected) {
        Layout layout = new Layout.Date("D", format, separator);
        assertThat(layout.size()).isEqualTo(expected.length() / 2);
        assertThat(hex(layout.toBytes(new WireValue.Date(date), 37))).isEqualTo(expected);
        assertThat(layout.read(bytes(expected), 0, 37)).isEqualTo(new WireValue.Date(date));
    }

    @ParameterizedTest
    @CsvSource({"ISO,.,10:15:30,F1F04BF1F54BF3F0", "ISO,.,00:00:00,F0F04BF0F04BF0F0", "HMS,:,23:59:59,F2F37AF5F97AF5F9", "EUR,.,11:15:30,F1F14BF1F54BF3F0",
            "USA,:,10:15:00,F1F07AF1F540C1D4", "USA,:,00:00:00,F0F07AF0F040C1D4", "USA,:,12:00:00,F1F27AF0F040D7D4", "USA,:,13:05:00,F0F17AF0F540D7D4"})
    void timesInTheFormatAndWithTheSeparatorOfTheirPcml(TimeFormat format, char separator, LocalTime time, String expected) {
        Layout layout = new Layout.Time("T", format, separator);
        assertThat(layout.size()).isEqualTo(8);
        assertThat(hex(layout.toBytes(new WireValue.Time(time), 37))).isEqualTo(expected);
        assertThat(layout.read(bytes(expected), 0, 37)).isEqualTo(new WireValue.Time(time));
    }

    @Test
    void aDateOutsideTheYearsOfItsFormatIsRefusedNotWrapped() {
        assertThatThrownBy(() -> new Layout.Date("D", DateFormat.MDY, '/').toBytes(new WireValue.Date(LocalDate.of(2040, 1, 1)), 37))
                .isInstanceOf(LayoutException.class).hasMessage("D: the date is outside the years that the format MDY holds");
        assertThatThrownBy(() -> new Layout.Date("D", DateFormat.JUL, '/').toBytes(new WireValue.Date(LocalDate.of(1939, 12, 31)), 37))
                .isInstanceOf(LayoutException.class);
        assertThatThrownBy(() -> new Layout.Date("D", DateFormat.ISO, '-').toBytes(new WireValue.Date(LocalDate.of(0, 12, 31)), 37))
                .isInstanceOf(LayoutException.class);
        assertThatThrownBy(() -> new Layout.Date("D", DateFormat.ISO, '-').toBytes(new WireValue.Date(LocalDate.of(10000, 1, 1)), 37))
                .isInstanceOf(LayoutException.class);
    }

    @Test
    void aTimeWithAPartItsFormatCannotHoldIsRefused() {
        assertThatThrownBy(() -> new Layout.Time("T", TimeFormat.USA, ':').toBytes(new WireValue.Time(LocalTime.of(10, 15, 30)), 37))
                .isInstanceOf(LayoutException.class).hasMessage("T: the time has a part that the format USA cannot hold");
        assertThatThrownBy(() -> new Layout.Time("T", TimeFormat.ISO, '.').toBytes(new WireValue.Time(LocalTime.of(10, 15, 30, 1)), 37))
                .isInstanceOf(LayoutException.class);
    }

    @Test
    void twoDigitYearsAreTheYears1940To2039() {
        Layout mdy = new Layout.Date("D", DateFormat.MDY, '/');
        assertThat(mdy.read(bytes("F1F261F3F161F3F9"), 0, 37)).isEqualTo(new WireValue.Date(LocalDate.of(2039, 12, 31)));
        assertThat(mdy.read(bytes("F0F161F0F161F4F0"), 0, 37)).isEqualTo(new WireValue.Date(LocalDate.of(1940, 1, 1)));
        assertThat(mdy.read(bytes("F0F161F0F161F0F0"), 0, 37)).isEqualTo(new WireValue.Date(LocalDate.of(2000, 1, 1)));
        assertThat(Temporal.year(39)).isEqualTo(2039);
        assertThat(Temporal.year(40)).isEqualTo(1940);
    }

    @Test
    void blanksAreWrittenAsTheLowestValueAndReadAsBlank() {
        assertThat(hex(new Layout.Date("D", DateFormat.ISO, '-').toBytes(new WireValue.Blank(), 37))).isEqualTo("F0F0F0F160F0F160F0F1");
        assertThat(hex(new Layout.Date("D", DateFormat.MDY, '/').toBytes(new WireValue.Blank(), 37))).isEqualTo("F0F161F0F161F4F0");
        assertThat(hex(new Layout.Time("T", TimeFormat.HMS, ':').toBytes(new WireValue.Blank(), 37))).isEqualTo("F0F07AF0F07AF0F0");
        assertThat(new Layout.Date("D", DateFormat.ISO, '-').read(bytes("40".repeat(10)), 0, 37)).isEqualTo(new WireValue.Blank());
        assertThat(new Layout.Time("T", TimeFormat.USA, ':').read(bytes("40".repeat(8)), 0, 37)).isEqualTo(new WireValue.Blank());
    }

    @ParameterizedTest
    @CsvSource({"ISO,-,F2F0F2F660F1F360F1F8", "ISO,-,F2F0F2F661F1F160F1F8", "ISO,-,F2F0F2F660F0F260F3F0", "ISO,-,F2F0C2F660F1F160F1F8",
            "MDY,/,F1F361F0F161F2F6", "EUR,.,F1F84BF1F160F2F0F2F6", "JUL,/,F2F561F3F6F6", "JUL,/,F2F561F0F0F0", "JUL,/,F2F54BF0F0F1", "YMD,.,F2F64BF1F14BF3F2"})
    void bytesThatAreNotADateOfTheFormatAreRefused(DateFormat format, char separator, String bytes) {
        assertThatThrownBy(() -> new Layout.Date("D", format, separator).read(bytes(bytes), 0, 37)).isInstanceOf(LayoutException.class)
                .hasMessage("D: the bytes are not a date in the format " + format);
    }

    @ParameterizedTest
    @CsvSource({"ISO,.,F2F44BF0F04BF0F1", "ISO,.,F2F54BF0F04BF0F0", "ISO,.,F1F07AF1F54BF3F0", "ISO,.,F1F04BF1F57AF3F0", "HMS,:,F1F07AF6F07AF0F0", "USA,:,F1F37AF0F040C1D4",
            "USA,:,F0F07AF0F040D7D4", "USA,:,F1F07AF1F540C1C1", "USA,:,F1F04BF1F540C1D4", "EUR,.,F1F04BF1F54BF6F0"})
    void bytesThatAreNotATimeOfTheFormatAreRefused(TimeFormat format, char separator, String bytes) {
        assertThatThrownBy(() -> new Layout.Time("T", format, separator).read(bytes(bytes), 0, 37)).isInstanceOf(LayoutException.class)
                .hasMessage("T: the bytes are not a time in the format " + format);
    }

    @Test
    void theFormatsAndSeparatorsOfThePcmlAreReadWhateverTheirCase() {
        assertThat(Temporal.dateFormat("iso")).contains(DateFormat.ISO);
        assertThat(Temporal.dateFormat("dmy")).contains(DateFormat.DMY);
        assertThat(Temporal.dateFormat("CYMD")).isEmpty();
        assertThat(Temporal.timeFormat("Usa")).contains(TimeFormat.USA);
        assertThat(Temporal.timeFormat("jis")).contains(TimeFormat.JIS);
        assertThat(Temporal.timeFormat("XYZ")).isEmpty();
        assertThat(Temporal.separator("Blank")).contains(' ');
        assertThat(Temporal.separator("comma")).contains(',');
        assertThat(Temporal.separator("HYPHEN")).contains('-');
        assertThat(Temporal.separator("ampersand")).isEmpty();
        assertThatThrownBy(() -> new Layout.Date("D", DateFormat.ISO, '-').toBytes(new WireValue.Text("2026-11-18"), 37)).hasMessage("D: a date was expected");
        assertThatThrownBy(() -> new Layout.Time("T", TimeFormat.ISO, '.').toBytes(new WireValue.Text("10.15.30"), 37)).hasMessage("T: a time was expected");
        assertThat(Temporal.parseDate("2026-11-1", DateFormat.ISO, '-')).isEmpty();
        assertThat(Temporal.parseTime("10.15.3", TimeFormat.ISO, '.')).isEmpty();
    }

    // ---- kickoff 05: the formats and separators of program 12, with the bytes run 1 returned (CCSID 273)

    @ParameterizedTest
    @CsvSource({"DMY,/,F1F961F1F161F2F6", "USA,/,F1F161F1F961F2F0F2F6", "JIS,-,F2F0F2F660F1F160F1F9", "MDY,',',F1F16BF1F96BF2F6", "MDY,' ',F1F140F1F940F2F6"})
    void theDateFormatsAndSeparatorsOfProgram12(DateFormat format, char separator, String hex) {
        Layout layout = new Layout.Date("D", format, separator);
        assertThat(layout.read(bytes(hex), 0, 273)).isEqualTo(new WireValue.Date(LocalDate.of(2026, 11, 19)));
        assertThat(hex(layout.toBytes(new WireValue.Date(LocalDate.of(2026, 11, 19)), 273))).isEqualTo(hex);
    }

    @ParameterizedTest
    @CsvSource({"JIS,:,F1F07AF1F57AF3F0", "HMS,',',F1F06BF1F56BF3F0", "HMS,' ',F1F040F1F540F3F0"})
    void theTimeFormatsAndSeparatorsOfProgram12(TimeFormat format, char separator, String hex) {
        Layout layout = new Layout.Time("T", format, separator);
        assertThat(layout.read(bytes(hex), 0, 273)).isEqualTo(new WireValue.Time(LocalTime.of(10, 15, 30)));
        assertThat(hex(layout.toBytes(new WireValue.Time(LocalTime.of(10, 15, 30)), 273))).isEqualTo(hex);
    }

    @ParameterizedTest
    @CsvSource({"ISO,.,F2F44BF0F04BF0F0", "HMS,:,F2F47AF0F07AF0F0", "EUR,.,F2F44BF0F04BF0F0", "JIS,:,F2F47AF0F07AF0F0"})
    void theEndOfTheDayReadsAsMidnightAndIsNeverWritten(TimeFormat format, char separator, String hex) {
        Layout layout = new Layout.Time("T", format, separator);
        assertThat(layout.read(bytes(hex), 0, 273)).isEqualTo(new WireValue.Time(LocalTime.MIDNIGHT));
        assertThat(hex(layout.toBytes(new WireValue.Time(LocalTime.MIDNIGHT), 273))).isEqualTo(hex.replace("F2F4", "F0F0"));
    }

    @Test
    void theUsaFormsOfMidnightAndTheEndOfTheDayAllReadAsMidnight() {
        Layout usa = new Layout.Time("T", TimeFormat.USA, ':');
        // 12:00 AM: an echoed midnight and the system's highest time; 00:00 AM: the system's lowest time, also taken as input.
        assertThat(usa.read(bytes("F1F27AF0F040C1D4"), 0, 273)).isEqualTo(new WireValue.Time(LocalTime.MIDNIGHT));
        assertThat(usa.read(bytes("F0F07AF0F040C1D4"), 0, 273)).isEqualTo(new WireValue.Time(LocalTime.MIDNIGHT));
        assertThat(usa.read(bytes("F0F07AF3F040C1D4"), 0, 273)).isEqualTo(new WireValue.Time(LocalTime.of(0, 30)));
        assertThat(hex(usa.toBytes(new WireValue.Time(LocalTime.MIDNIGHT), 273))).isEqualTo("F0F07AF0F040C1D4");
        assertThat(hex(usa.toBytes(new WireValue.Time(LocalTime.of(0, 30)), 273))).isEqualTo("F1F27AF3F040C1D4");
        assertThat(hex(usa.toBytes(new WireValue.Time(LocalTime.NOON), 273))).isEqualTo("F1F27AF0F040D7D4");
    }

    // ---- timestamps, with the bytes run 1 returned

    @Test
    void timestampsOfTheDefaultLengthAndOfLength32And19() {
        Layout six = new Layout.Timestamp("S", 6);
        assertThat(six.size()).isEqualTo(26);
        String next = "F2F0F2F660F1F160F1F960F0F04BF0F04BF0F14BF0F0F0F0F0F0";
        assertThat(six.read(bytes(next), 0, 273)).isEqualTo(new WireValue.Timestamp(java.time.LocalDateTime.of(2026, 11, 19, 0, 0, 1)));
        assertThat(hex(six.toBytes(new WireValue.Timestamp(java.time.LocalDateTime.of(2026, 11, 19, 0, 0, 1)), 273))).isEqualTo(next);
        String low = "F0F0F0F160F0F160F0F160F0F04BF0F04BF0F04BF0F0F0F0F0F0";
        assertThat(six.read(bytes(low), 0, 273)).isEqualTo(new WireValue.Timestamp(java.time.LocalDateTime.of(1, 1, 1, 0, 0)));
        assertThat(hex(six.toBytes(new WireValue.Blank(), 273))).isEqualTo(low);
        Layout twelve = new Layout.Timestamp("S", 12);
        assertThat(twelve.size()).isEqualTo(32);
        String picos = "F2F0F2F660F1F160F1F960F1F04BF1F54BF3F04BF1F2F3F4F5F6F7F8F9F0F1F2";
        assertThatThrownBy(() -> twelve.read(bytes(picos), 0, 273)).isInstanceOf(LayoutException.class)
                .hasMessage("S: the timestamp has fractions of a second that the API cannot hold");
        java.time.LocalDateTime nanos = java.time.LocalDateTime.of(2026, 11, 19, 10, 15, 30, 123456789);
        assertThat(twelve.read(twelve.toBytes(new WireValue.Timestamp(nanos), 273), 0, 273)).isEqualTo(new WireValue.Timestamp(nanos));
        assertThat(hex(twelve.toBytes(new WireValue.Timestamp(nanos), 273))).isEqualTo(picos.substring(0, 58) + "F0F0F0");
        Layout zero = new Layout.Timestamp("S", 0);
        assertThat(zero.size()).isEqualTo(19);
        String seconds = "F2F0F2F660F1F160F1F960F1F04BF1F54BF3F0";
        assertThat(zero.read(bytes(seconds), 0, 273)).isEqualTo(new WireValue.Timestamp(java.time.LocalDateTime.of(2026, 11, 19, 10, 15, 30)));
        assertThatThrownBy(() -> zero.toBytes(new WireValue.Timestamp(nanos), 273)).isInstanceOf(LayoutException.class)
                .hasMessage("S: the timestamp has fractions of a second that the field cannot hold");
    }

    @Test
    void aTimestampAt24IsMidnightOfTheNextDayAndTheEndOf9999IsRefused() {
        Layout six = new Layout.Timestamp("S", 6);
        String high = "F9F9F9F960F1F260F3F160F2F44BF0F04BF0F04BF0F0F0F0F0F0";
        assertThatThrownBy(() -> six.read(bytes(high), 0, 273)).isInstanceOf(LayoutException.class)
                .hasMessage("S: the timestamp is the end of the year 9999, which the API cannot hold");
        byte[] endOfDay = six.toBytes(new WireValue.Timestamp(java.time.LocalDateTime.of(2026, 11, 19, 0, 0)), 273);
        String text = hex(endOfDay).replace("F1F960F0F04B", "F1F860F2F44B");
        assertThat(six.read(bytes(text), 0, 273)).isEqualTo(new WireValue.Timestamp(java.time.LocalDateTime.of(2026, 11, 19, 0, 0)));
        assertThatThrownBy(() -> six.read(bytes(hex(endOfDay).replace("F1F960F0F04BF0F0", "F1F860F2F44BF0F1")), 0, 273)).isInstanceOf(LayoutException.class)
                .hasMessage("S: the bytes are not a timestamp");
        assertThat(six.read(bytes("40".repeat(26)), 0, 273)).isEqualTo(new WireValue.Blank());
        assertThatThrownBy(() -> six.read(bytes("F2F0F2F64BF1F160F1F960F0F04BF0F04BF0F14BF0F0F0F0F0F0"), 0, 273)).isInstanceOf(LayoutException.class);
        assertThatThrownBy(() -> six.read(bytes("F2F0F2F660F1F360F1F960F0F04BF0F04BF0F14BF0F0F0F0F0F0"), 0, 273)).isInstanceOf(LayoutException.class);
        assertThatThrownBy(() -> six.read(bytes("F2F0F2F660F1F160F1F960F0F04BF0F04BF0F14BF0F0F0C1F0F0"), 0, 273)).isInstanceOf(LayoutException.class);
        assertThatThrownBy(() -> six.toBytes(new WireValue.Text("x"), 273)).hasMessage("S: a timestamp was expected");
        assertThatThrownBy(() -> new Layout.Timestamp("S", 13)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Layout.Timestamp("S", -1)).isInstanceOf(IllegalArgumentException.class);
    }
}
