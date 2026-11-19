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
        YMD(false);

        final boolean fourDigitYear;

        DateFormat(boolean fourDigitYear) {
            this.fourDigitYear = fourDigitYear;
        }

        /** The length of the text with one-character separators. */
        int length() {
            return switch (this) {
                case ISO, EUR -> 10;
                case MDY, YMD -> 8;
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
        EUR;

        int length() {
            return 8;
        }
    }

    /** The separators of PCML that a real system has returned, by attribute value. */
    static final Map<String, Character> SEPARATORS = Map.of("hyphen", '-', "slash", '/', "period", '.', "colon", ':');

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
            case ISO -> four(date.getYear()) + separator + mm + separator + dd;
            case EUR -> dd + separator + mm + separator + four(date.getYear());
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
                case ISO -> date(number(text, 0, 4), number(text, 5, 2), number(text, 8, 2), text, separator, 4, 7);
                case EUR -> date(number(text, 6, 4), number(text, 3, 2), number(text, 0, 2), text, separator, 2, 5);
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
                if (hour < 1 || hour > 12 || !half.equals(" AM") && !half.equals(" PM")) {
                    return Optional.empty();
                }
                int hour24 = hour % 12 + (half.equals(" PM") ? 12 : 0);
                return Optional.of(LocalTime.of(hour24, number(text, 3, 2)));
            }
            if (text.charAt(5) != separator) {
                return Optional.empty();
            }
            return Optional.of(LocalTime.of(number(text, 0, 2), number(text, 3, 2), number(text, 6, 2)));
        } catch (DateTimeException | NumberFormatException e) {
            return Optional.empty();
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
