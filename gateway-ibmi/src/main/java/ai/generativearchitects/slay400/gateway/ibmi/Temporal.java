package ai.generativearchitects.slay400.gateway.ibmi;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The date and time formats of PCML ({@code dateformat}, {@code timeformat}) and their separators ({@code dateseparator},
 * {@code timeseparator}), as the compiler writes them for RPG date and time parameters. Only the formats and separators that a
 * real system has returned in a recorded run are accepted (decision 121); the others are refused at start.
 */
final class Temporal {

    private Temporal() {
    }

    /** A date format: the order of its parts and whether the year has two digits. */
    enum DateFormat {
        /** {@code yyyy-mm-dd}. */
        ISO(true),
        /** {@code mm/dd/yy}, years 1940 to 2039. */
        MDY(false),
        /** {@code dd.mm.yyyy}. */
        EUR(true),
        /** {@code yy/ddd}, the day of the year, years 1940 to 2039. */
        JUL(false),
        /** {@code yy.mm.dd}, years 1940 to 2039. */
        YMD(false),
        /** {@code dd/mm/yy}, years 1940 to 2039. */
        DMY(false),
        /** {@code mm/dd/yyyy}. */
        USA(true),
        /** {@code yyyy-mm-dd}. */
        JIS(true);

        final boolean fourDigitYear;

        DateFormat(boolean fourDigitYear) {
            this.fourDigitYear = fourDigitYear;
        }

        /** The length of the text with one-character separators. */
        int length() {
            return switch (this) {
                case ISO, EUR, USA, JIS -> 10;
                case MDY, YMD, DMY -> 8;
                case JUL -> 6;
            };
        }

        LocalDate lowest() {
            return fourDigitYear ? LocalDate.of(1, 1, 1) : LocalDate.of(1940, 1, 1);
        }

        LocalDate highest() {
            return fourDigitYear ? LocalDate.of(9999, 12, 31) : LocalDate.of(2039, 12, 31);
        }
    }

    /** A time format. */
    enum TimeFormat {
        /** {@code hh.mm.ss}. */
        ISO,
        /** {@code hh:mm:ss}. */
        HMS,
        /** {@code hh:mm AM}: hours 1 to 12 and no seconds. */
        USA,
        /** {@code hh.mm.ss}. */
        EUR,
        /** {@code hh:mm:ss}. */
        JIS;

        int length() {
            return 8;
        }
    }

    /** The separators of PCML that a real system has returned, by attribute value. */
    static final Map<String, Character> SEPARATORS = Map.of("hyphen", '-', "slash", '/', "period", '.', "colon", ':', "comma", ',', "blank", ' ');

    static Optional<DateFormat> dateFormat(String value) {
        try {
            return Optional.of(DateFormat.valueOf(value.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    static Optional<TimeFormat> timeFormat(String value) {
        try {
            return Optional.of(TimeFormat.valueOf(value.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    static Optional<Character> separator(String value) {
        return Optional.ofNullable(SEPARATORS.get(value.toLowerCase(Locale.ROOT)));
    }

    /** The text of a date; {@code null} if the format cannot hold it. */
    static String format(LocalDate date, DateFormat format, char separator) {
        if (date.isBefore(format.lowest()) || date.isAfter(format.highest())) {
            return null;
        }
        String yy = two(date.getYear() % 100);
        String mm = two(date.getMonthValue());
        String dd = two(date.getDayOfMonth());
        return switch (format) {
            case ISO, JIS -> four(date.getYear()) + separator + mm + separator + dd;
            case EUR -> dd + separator + mm + separator + four(date.getYear());
            case USA -> mm + separator + dd + separator + four(date.getYear());
            case DMY -> dd + separator + mm + separator + yy;
            case MDY -> mm + separator + dd + separator + yy;
            case YMD -> yy + separator + mm + separator + dd;
            case JUL -> yy + separator + three(date.getDayOfYear());
        };
    }

    /** The date of a text, or empty when the text is not a date of the format. */
    static Optional<LocalDate> parseDate(String text, DateFormat format, char separator) {
        if (text.length() != format.length()) {
            return Optional.empty();
        }
        try {
            return Optional.of(switch (format) {
                case ISO, JIS -> date(number(text, 0, 4), number(text, 5, 2), number(text, 8, 2), text, separator, 4, 7);
                case EUR -> date(number(text, 6, 4), number(text, 3, 2), number(text, 0, 2), text, separator, 2, 5);
                case USA -> date(number(text, 6, 4), number(text, 0, 2), number(text, 3, 2), text, separator, 2, 5);
                case DMY -> date(year(number(text, 6, 2)), number(text, 3, 2), number(text, 0, 2), text, separator, 2, 5);
                case MDY -> date(year(number(text, 6, 2)), number(text, 0, 2), number(text, 3, 2), text, separator, 2, 5);
                case YMD -> date(year(number(text, 0, 2)), number(text, 3, 2), number(text, 6, 2), text, separator, 2, 5);
                case JUL -> {
                    if (text.charAt(2) != separator) {
                        throw new DateTimeException("separator");
                    }
                    int year = year(number(text, 0, 2));
                    int day = number(text, 3, 3);
                    if (day < 1 || day > LocalDate.of(year, 1, 1).lengthOfYear()) {
                        throw new DateTimeException("day of year");
                    }
                    yield LocalDate.ofYearDay(year, day);
                }
            });
        } catch (DateTimeException | NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** The text of a time; {@code null} if the format cannot hold it (fractions of a second; seconds in USA). */
    static String format(LocalTime time, TimeFormat format, char separator) {
        if (time.getNano() != 0 || format == TimeFormat.USA && time.getSecond() != 0) {
            return null;
        }
        String mm = two(time.getMinute());
        if (format == TimeFormat.USA) {
            if (time.equals(LocalTime.MIDNIGHT)) {
                // The system's own lowest time, which it took as an input and returned unchanged (kickoff 05, run 1). "12:00 AM"
                // is also the form it gives the end of the day.
                return "00" + separator + "00 AM";
            }
            int hour = time.getHour() % 12 == 0 ? 12 : time.getHour() % 12;
            return two(hour) + separator + mm + (time.getHour() < 12 ? " AM" : " PM");
        }
        return two(time.getHour()) + separator + mm + separator + two(time.getSecond());
    }

    /** The time of a text, or empty when the text is not a time of the format. */
    static Optional<LocalTime> parseTime(String text, TimeFormat format, char separator) {
        if (text.length() != format.length() || text.charAt(2) != separator) {
            return Optional.empty();
        }
        try {
            if (format == TimeFormat.USA) {
                String half = text.substring(5);
                int hour = number(text, 0, 2);
                // 00 AM is the system's lowest time; 12:00 AM is both midnight and the end of the day: both read as 00:00:00.
                if (hour > 12 || hour == 0 && !half.equals(" AM") || !half.equals(" AM") && !half.equals(" PM")) {
                    return Optional.empty();
                }
                int hour24 = hour % 12 + (half.equals(" PM") ? 12 : 0);
                return Optional.of(LocalTime.of(hour24, number(text, 3, 2)));
            }
            if (text.charAt(5) != separator) {
                return Optional.empty();
            }
            int hour = number(text, 0, 2);
            int minute = number(text, 3, 2);
            int second = number(text, 6, 2);
            if (hour == 24 && minute == 0 && second == 0) {
                // The end of the day, which the API cannot say: given as 00:00:00 (decision 123).
                return Optional.of(LocalTime.MIDNIGHT);
            }
            return Optional.of(LocalTime.of(hour, minute, second));
        } catch (DateTimeException | NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** Why a timestamp text could not be read, or written. */
    static final class StampProblem extends Exception {
        private static final long serialVersionUID = 1L;

        StampProblem(String reason) {
            super(reason, null, false, false);
        }
    }

    /**
     * The text of a timestamp, as the system returned it: {@code yyyy-mm-dd-hh.mm.ss}, then a period and {@code digits} fraction
     * digits when there are any.
     *
     * @throws StampProblem if the year is outside 1 to 9999, or the value has fractions of a second the field cannot hold
     */
    static String format(java.time.LocalDateTime stamp, int digits) throws StampProblem {
        if (stamp.getYear() < 1 || stamp.getYear() > 9999) {
            throw new StampProblem("the timestamp is outside the years 1 to 9999");
        }
        String nanos = "0".repeat(9 - Integer.toString(stamp.getNano()).length()) + stamp.getNano();
        String fraction = digits <= 9 ? nanos.substring(0, digits) : nanos + "0".repeat(digits - 9);
        if (digits < 9 && !nanos.substring(digits).chars().allMatch(c -> c == '0')) {
            throw new StampProblem("the timestamp has fractions of a second that the field cannot hold");
        }
        String text = four(stamp.getYear()) + "-" + two(stamp.getMonthValue()) + "-" + two(stamp.getDayOfMonth()) + "-" + two(stamp.getHour()) + "."
                + two(stamp.getMinute()) + "." + two(stamp.getSecond());
        return digits == 0 ? text : text + "." + fraction;
    }

    /**
     * The timestamp of a text. {@code 24.00.00} is midnight of the next day; at 9999-12-31 it cannot be held. Fractions beyond
     * the nine digits the API holds must be zero.
     *
     * @throws StampProblem if the text is not a timestamp of the field, or cannot be held
     */
    static java.time.LocalDateTime parseStamp(String text, int digits) throws StampProblem {
        int length = digits == 0 ? 19 : 20 + digits;
        if (text.length() != length || text.charAt(4) != '-' || text.charAt(7) != '-' || text.charAt(10) != '-' || text.charAt(13) != '.'
                || text.charAt(16) != '.' || digits > 0 && text.charAt(19) != '.') {
            throw new StampProblem("the bytes are not a timestamp");
        }
        try {
            LocalDate date = LocalDate.of(number(text, 0, 4), number(text, 5, 2), number(text, 8, 2));
            int hour = number(text, 11, 2);
            int minute = number(text, 14, 2);
            int second = number(text, 17, 2);
            String fraction = digits == 0 ? "" : text.substring(20);
            if (!fraction.chars().allMatch(c -> c >= '0' && c <= '9')) {
                throw new StampProblem("the bytes are not a timestamp");
            }
            if (fraction.length() > 9 && !fraction.substring(9).chars().allMatch(c -> c == '0')) {
                throw new StampProblem("the timestamp has fractions of a second that the API cannot hold");
            }
            String nine = (fraction + "000000000").substring(0, 9);
            int nanos = Integer.parseInt(nine);
            if (hour == 24) {
                if (minute != 0 || second != 0 || nanos != 0) {
                    throw new StampProblem("the bytes are not a timestamp");
                }
                if (date.equals(LocalDate.of(9999, 12, 31))) {
                    throw new StampProblem("the timestamp is the end of the year 9999, which the API cannot hold");
                }
                return date.plusDays(1).atStartOfDay();
            }
            return java.time.LocalDateTime.of(date, LocalTime.of(hour, minute, second, nanos));
        } catch (DateTimeException | NumberFormatException e) {
            throw new StampProblem("the bytes are not a timestamp");
        }
    }

    private static LocalDate date(int year, int month, int day, String text, char separator, int first, int second) {
        if (text.charAt(first) != separator || text.charAt(second) != separator) {
            throw new DateTimeException("separator");
        }
        return LocalDate.of(year, month, day);
    }

    /** The year of a two-digit year: 40 to 99 are 1940 to 1999, 00 to 39 are 2000 to 2039. */
    static int year(int yy) {
        return yy >= 40 ? 1900 + yy : 2000 + yy;
    }

    private static int number(String text, int from, int length) {
        String digits = text.substring(from, from + length);
        if (!digits.chars().allMatch(c -> c >= '0' && c <= '9')) {
            throw new NumberFormatException("not digits");
        }
        return Integer.parseInt(digits);
    }

    private static String two(int value) {
        return value < 10 ? "0" + value : Integer.toString(value);
    }

    private static String three(int value) {
        return value < 10 ? "00" + value : value < 100 ? "0" + value : Integer.toString(value);
    }

    private static String four(int value) {
        return "0".repeat(4 - Integer.toString(value).length()) + value;
    }
}
