package ai.generativearchitects.slay400.gateway.log;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * One JSON line per record of Java logging, the gateway's and Helidon's alike (kickoff 11, A2):
 *
 * <pre>{"time":"…Z","level":"warning","logger":"slay400.gateway","message":"…","tenant":"…","environment":"…","operation":"…",
 * "correlationId":"…","code":"GWY025","exception":"java.lang.IllegalStateException: …","stack":"…","truncated":true}</pre>
 *
 * <p>{@code time} is UTC, ISO 8601. {@code level} is {@code error} for {@code SEVERE}, {@code warning} for {@code WARNING}, and
 * {@code info} for everything below. The fields of {@link LogFields} come from a parameter of the record and are left out when
 * absent; {@code exception} (class and message) and {@code stack} (one string, causes included) when there is an exception.
 *
 * <p>A line is never broken: every control character is escaped. The message, the exception text and the stack go through
 * {@link LogRedaction} first, and each is cut at its bound, with {@code "truncated":true} when one was cut.
 */
public final class JsonLogFormatter extends Formatter {

    /** The longest message written, in characters. */
    public static final int MAX_MESSAGE = 4096;
    /** The longest exception text written, in characters. */
    public static final int MAX_EXCEPTION = 1024;
    /** The longest stack written, in characters. */
    public static final int MAX_STACK = 16384;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC);

    @Override
    public String format(LogRecord record) {
        LogFields fields = fields(record);
        StringBuilder out = new StringBuilder(256);
        boolean[] truncated = {false};
        out.append("{\"time\":");
        quote(out, TIME.format(record.getInstant()));
        out.append(",\"level\":");
        quote(out, level(record.getLevel()));
        out.append(",\"logger\":");
        quote(out, record.getLoggerName() == null ? "" : record.getLoggerName());
        out.append(",\"message\":");
        quote(out, bounded(LogRedaction.apply(message(record)), MAX_MESSAGE, truncated));
        field(out, "tenant", fields.tenant());
        field(out, "environment", fields.environment());
        field(out, "operation", fields.operation());
        field(out, "correlationId", fields.correlationId());
        field(out, "code", fields.code());
        Throwable thrown = record.getThrown() != null ? record.getThrown() : fields.thrown().orElse(null);
        if (thrown != null) {
            out.append(",\"exception\":");
            quote(out, bounded(LogRedaction.apply(String.valueOf(thrown)), MAX_EXCEPTION, truncated));
            out.append(",\"stack\":");
            quote(out, bounded(LogRedaction.apply(stack(thrown)), MAX_STACK, truncated));
        }
        if (truncated[0]) {
            out.append(",\"truncated\":true");
        }
        return out.append("}\n").toString();
    }

    /** The level in the words of the line. */
    static String level(Level level) {
        if (level.intValue() >= Level.SEVERE.intValue()) {
            return "error";
        }
        if (level.intValue() >= Level.WARNING.intValue()) {
            return "warning";
        }
        return "info";
    }

    private static LogFields fields(LogRecord record) {
        Object[] parameters = record.getParameters();
        if (parameters != null) {
            for (Object parameter : parameters) {
                if (parameter instanceof LogFields fields) {
                    return fields;
                }
            }
        }
        return LogFields.none();
    }

    /** The message with its parameters, as Java logging formats it; a message that cannot be formatted is taken as it is. */
    private String message(LogRecord record) {
        String raw = record.getMessage();
        if (raw == null) {
            return "";
        }
        try {
            return formatMessage(record);
        } catch (RuntimeException e) {
            return raw;
        }
    }

    private static String stack(Throwable thrown) {
        StringWriter text = new StringWriter();
        try (PrintWriter writer = new PrintWriter(text)) {
            thrown.printStackTrace(writer);
        }
        return text.toString().stripTrailing();
    }

    private static String bounded(String text, int max, boolean[] truncated) {
        if (text.length() <= max) {
            return text;
        }
        truncated[0] = true;
        int end = max;
        // Not between the two halves of a surrogate pair.
        if (Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end);
    }

    private static void field(StringBuilder out, String name, Optional<String> value) {
        value.ifPresent(v -> {
            out.append(",\"").append(name).append("\":");
            quote(out, v);
        });
    }

    /** A JSON string: quotes, backslashes and every control character escaped, so that the line stays one line. */
    static void quote(StringBuilder out, String text) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20 || c == 0x7f || c == ' ' || c == ' ') {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
