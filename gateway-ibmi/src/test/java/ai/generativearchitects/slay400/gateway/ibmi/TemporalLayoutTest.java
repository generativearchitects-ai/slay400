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
            "USA,:,10:15:00,F1F07AF1F540C1D4", "USA,:,00:00:00,F1F27AF0F040C1D4", "USA,:,12:00:00,F1F27AF0F040D7D4", "USA,:,13:05:00,F0F17AF0F540D7D4"})
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
    @CsvSource({"ISO,.,F2F44BF0F04BF0F0", "ISO,.,F1F07AF1F54BF3F0", "ISO,.,F1F04BF1F57AF3F0", "HMS,:,F1F07AF6F07AF0F0", "USA,:,F1F37AF0F040C1D4",
            "USA,:,F0F07AF0F040C1D4", "USA,:,F1F07AF1F540C1C1", "USA,:,F1F04BF1F540C1D4", "EUR,.,F1F04BF1F54BF6F0"})
    void bytesThatAreNotATimeOfTheFormatAreRefused(TimeFormat format, char separator, String bytes) {
        assertThatThrownBy(() -> new Layout.Time("T", format, separator).read(bytes(bytes), 0, 37)).isInstanceOf(LayoutException.class)
                .hasMessage("T: the bytes are not a time in the format " + format);
    }

    @Test
    void theFormatsAndSeparatorsOfThePcmlAreReadWhateverTheirCase() {
        assertThat(Temporal.dateFormat("iso")).contains(DateFormat.ISO);
        assertThat(Temporal.dateFormat("DMY")).isEmpty();
        assertThat(Temporal.timeFormat("Usa")).contains(TimeFormat.USA);
        assertThat(Temporal.timeFormat("JIS")).isEmpty();
        assertThat(Temporal.separator("HYPHEN")).contains('-');
        assertThat(Temporal.separator("ampersand")).isEmpty();
        assertThatThrownBy(() -> new Layout.Date("D", DateFormat.ISO, '-').toBytes(new WireValue.Text("2026-11-18"), 37)).hasMessage("D: a date was expected");
        assertThatThrownBy(() -> new Layout.Time("T", TimeFormat.ISO, '.').toBytes(new WireValue.Text("10.15.30"), 37)).hasMessage("T: a time was expected");
        assertThat(Temporal.parseDate("2026-11-1", DateFormat.ISO, '-')).isEmpty();
        assertThat(Temporal.parseTime("10.15.3", TimeFormat.ISO, '.')).isEmpty();
    }
}
