package ai.generativearchitects.slay400.gateway.log;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

/** One JSON line per record of Java logging (kickoff 11, A2), with hostile values. */
class JsonLogFormatterTest {

    private final JsonLogFormatter formatter = new JsonLogFormatter();

    private static LogRecord record(Level level, String message, Object... parameters) {
        LogRecord record = new LogRecord(level, message);
        record.setLoggerName("slay400.gateway");
        record.setInstant(Instant.parse("2026-11-27T10:13:03.123Z"));
        record.setParameters(parameters.length == 0 ? null : parameters);
        return record;
    }

    @Test
    void aRecordIsOneLineWithTimeLevelLoggerAndMessage() {
        String line = formatter.format(record(Level.WARNING, "The bundles changed but cannot be served"));
        assertThat(line).isEqualTo("{\"time\":\"2026-11-27T10:13:03.123Z\",\"level\":\"warning\",\"logger\":\"slay400.gateway\","
                + "\"message\":\"The bundles changed but cannot be served\"}\n");
    }

    @Test
    void levelsAreErrorWarningAndInfo() {
        assertThat(JsonLogFormatter.level(Level.SEVERE)).isEqualTo("error");
        assertThat(JsonLogFormatter.level(Level.WARNING)).isEqualTo("warning");
        assertThat(JsonLogFormatter.level(Level.INFO)).isEqualTo("info");
        assertThat(JsonLogFormatter.level(Level.CONFIG)).isEqualTo("info");
        assertThat(JsonLogFormatter.level(Level.FINEST)).isEqualTo("info");
    }

    @Test
    void theFieldsOfTheGatewayAreFieldsOfTheirOwn() {
        LogFields fields = LogFields.of("acme", "test").operation("ordstatus").correlationId("c-1").code("VAL104");
        String line = formatter.format(record(Level.WARNING, "The response has more values than the limit", fields));
        assertThat(line).isEqualTo("{\"time\":\"2026-11-27T10:13:03.123Z\",\"level\":\"warning\",\"logger\":\"slay400.gateway\","
                + "\"message\":\"The response has more values than the limit\",\"tenant\":\"acme\",\"environment\":\"test\",\"operation\":\"ordstatus\","
                + "\"correlationId\":\"c-1\",\"code\":\"VAL104\"}\n");
    }

    @Test
    void anExceptionGivesItsClassAndMessageAndOneStack() {
        LogRecord record = record(Level.SEVERE, "Unexpected error");
        record.setThrown(new IllegalStateException("broken", new RuntimeException("cause")));
        String line = formatter.format(record);
        assertThat(line).contains("\"level\":\"error\"").contains("\"exception\":\"java.lang.IllegalStateException: broken\"")
                .contains("\"stack\":\"java.lang.IllegalStateException: broken\\n\\tat ").contains("Caused by: java.lang.RuntimeException: cause");
        assertThat(line.indexOf('\n')).isEqualTo(line.length() - 1);
    }

    @Test
    void anExceptionCarriedByTheFieldsIsWrittenAsTheExceptionOfTheRecord() {
        String line = formatter.format(record(Level.SEVERE, "Unexpected error", LogFields.request("c-2").thrown(new IllegalArgumentException("x"))));
        assertThat(line).contains("\"correlationId\":\"c-2\"").contains("\"exception\":\"java.lang.IllegalArgumentException: x\"").contains("\"stack\":");
    }

    @Test
    void controlCharactersQuotesAndLineSeparatorsAreEscapedSoThatTheLineStaysOneLine() {
        String hostile = "a\"b\\c\nd\re\tf\u0000g\u001fh\u007fi j k";
        String line = formatter.format(record(Level.INFO, hostile));
        assertThat(line).contains("\"message\":\"a\\\"b\\\\c\\nd\\re\\tf\\u0000g\\u001fh\\u007fi\\u2028j\\u2029k\"");
        assertThat(line.chars().filter(c -> c < 0x20 || c == 0x7f || c == 0x2028 || c == 0x2029).count()).isEqualTo(1);
        assertThat(line).endsWith("}\n");
    }

    @Test
    void aMessageWithAJsonLookalikeCannotAddAField() {
        String line = formatter.format(record(Level.INFO, "\",\"level\":\"error\",\"x\":\""));
        assertThat(line).startsWith("{\"time\":\"2026-11-27T10:13:03.123Z\",\"level\":\"info\",").contains("\"message\":\"\\\",\\\"level\\\":\\\"error\\\"");
    }

    @Test
    void aRecordOverItsBoundIsCutAndSaysSo() {
        String line = formatter.format(record(Level.INFO, "x".repeat(JsonLogFormatter.MAX_MESSAGE + 100)));
        assertThat(line).contains("\"message\":\"" + "x".repeat(JsonLogFormatter.MAX_MESSAGE) + "\"").endsWith(",\"truncated\":true}\n");
        assertThat(formatter.format(record(Level.INFO, "short"))).doesNotContain("truncated");
    }

    @Test
    void aCutNeverSplitsASurrogatePair() {
        String text = "x".repeat(JsonLogFormatter.MAX_MESSAGE - 1) + "😀" + "tail";
        String line = formatter.format(record(Level.INFO, text));
        assertThat(line).contains("\"message\":\"" + "x".repeat(JsonLogFormatter.MAX_MESSAGE - 1) + "\"");
    }

    @Test
    void aRegisteredRedactorIsAppliedToMessageExceptionAndStack() {
        try (LogRedaction.Registration ignored = LogRedaction.register(text -> text.replace("box.example.invalid", "<host>"))) {
            LogRecord record = record(Level.WARNING, "No connection to box.example.invalid");
            record.setThrown(new IllegalStateException("box.example.invalid refused"));
            String line = formatter.format(record);
            assertThat(line).doesNotContain("box.example.invalid").contains("No connection to <host>").contains("<host> refused");
        }
        assertThat(formatter.format(record(Level.INFO, "box.example.invalid"))).contains("box.example.invalid");
    }

    @Test
    void aMessageWithParametersIsFormattedAndAMessageThatCannotBeIsTakenAsItIs() {
        assertThat(formatter.format(record(Level.INFO, "{0} of {1}", "one", "two"))).contains("\"message\":\"one of two\"");
        assertThat(formatter.format(record(Level.INFO, "a {0 b", "one"))).contains("\"message\":\"a {0 b\"");
        assertThat(formatter.format(record(Level.INFO, null))).contains("\"message\":\"\"");
    }

    @Test
    void installedLoggingWritesEveryRecordOfEveryLoggerAsOneLineAndNeverTwoHandlers() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream stream = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        try {
            JsonLogging.install(stream);
            JsonLogging.install(stream);
            assertThat(JsonLogging.isInstalled()).isTrue();
            assertThat(Logger.getLogger("").getHandlers()).hasSize(1);
            System.getLogger("io.helidon.webserver.Test").log(System.Logger.Level.INFO, "a line of Helidon");
            System.getLogger("slay400.gateway").log(System.Logger.Level.WARNING, "a line\nof the gateway", LogFields.of("acme", "test"));
            System.getLogger("slay400.gateway").log(System.Logger.Level.DEBUG, "below the level");
            String[] lines = bytes.toString(StandardCharsets.UTF_8).split("\n");
            assertThat(lines).hasSize(2);
            assertThat(lines[0]).contains("\"logger\":\"io.helidon.webserver.Test\"").contains("\"message\":\"a line of Helidon\"");
            assertThat(lines[1]).contains("\"message\":\"a line\\nof the gateway\"").contains("\"tenant\":\"acme\"");
        } finally {
            JsonLogging.install(System.err);
        }
    }
}
