package ai.generativearchitects.slay400.gateway.ibmi;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.slay400.gateway.load.GatewayLoader;
import ai.generativearchitects.slay400.gateway.load.StartOptions;
import ai.generativearchitects.slay400.gateway.load.StartResult;
import ai.generativearchitects.slay400.gateway.log.JsonLogFormatter;
import ai.generativearchitects.slay400.report.Report;
import ai.generativearchitects.slay400.report.TextReport;
import ai.generativearchitects.slay400.tasks.BundleRequest;
import ai.generativearchitects.slay400.tasks.TaskResult;
import ai.generativearchitects.slay400.tasks.Tasks;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The recording of the refused sign-on of the tutorial Call a program on IBM i (kickoff 11, A2), made with the fake connection
 * instead of the real system: the tutorial's own configuration and bundle, a sign-on refused with the return code the real
 * system gave (8), and what {@code run} then writes on standard error, the gateway's JSON record followed by the diagnostics
 * printed for a person. The time of the record, which changes at every run, is written {@code <time>}.
 *
 * <p>Fails when the committed recording is not what the gateway writes; with {@code -Dslay400.docs.write=true} writes it.
 */
class SignOnRefusedRecordingTest {

    static final Path EXAMPLE = Path.of("..", "docs", "examples", "call-a-program-on-ibm-i");
    static final Path RECORDING = EXAMPLE.resolve("expected/sign-on-refused.out");
    static final Map<String, String> VARIABLES = Map.of("IBMI_HOST", "your-system.invalid", "IBMI_LIBRARY", "YOURLIB", "IBMI_USER", "YOURUSER",
            "IBMI_PASSWORD", "not-the-password");

    @TempDir
    Path dir;

    @Test
    void theRecordingIsWhatTheGatewayWritesWhenTheSignOnIsRefused() throws IOException {
        Path build = Files.createDirectories(dir.resolve("build"));
        TaskResult bundled = new Tasks(dir).bundle(EXAMPLE.resolve("input"), new BundleRequest("orders", "1.0.0", build.resolve("orders-1.0.0.rpgapi"),
                Optional.empty(), Optional.empty()));
        assertThat(bundled.hasError()).as(bundled.diagnostics().toString()).isFalse();
        Path config = dir.resolve("config.yaml");
        Files.writeString(config, tutorialConfig());

        FakeIbmi fake = new FakeIbmi();
        fake.openFailure = new IbmiFailure(IbmiFailure.Kind.SIGN_ON_REFUSED, "return code 8");
        IbmiBackend backend = new IbmiBackend(settings -> fake, Duration.ofMillis(200), settings -> Duration.ofMillis(300));
        List<String> records = new CopyOnWriteArrayList<>();
        Logger logger = Logger.getLogger("slay400.gateway.ibmi");
        JsonLogFormatter formatter = new JsonLogFormatter();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(formatter.format(record));
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(handler);
        StartResult result;
        try {
            result = GatewayLoader.load(config, VARIABLES::get, line -> { }, new StartOptions(List.of(backend), true));
        } finally {
            logger.removeHandler(handler);
        }
        assertThat(result.started()).isEmpty();
        // As Main writes a failed start: the records of Java logging as they happen, then the diagnostics for a person.
        String recording = String.join("", records).replaceAll("\"time\":\"[^\"]+\"", "\"time\":\"<time>\"")
                + TextReport.render(new Report("start", config.getParent(), result.diagnostics()));
        assertThat(recording).doesNotContain("your-system.invalid").doesNotContain("YOURLIB").doesNotContain("YOURUSER").doesNotContain("not-the-password");
        if (Boolean.getBoolean("slay400.docs.write")) {
            Files.writeString(RECORDING, recording);
        }
        assertThat(Files.readString(RECORDING)).as(RECORDING + " is stale; regenerate with: ./mvnw -pl gateway-ibmi -am test "
                + "-Dtest=SignOnRefusedRecordingTest -Dsurefire.failIfNoSpecifiedTests=false -Dslay400.docs.write=true").isEqualTo(recording);
    }

    /** The configuration the tutorial writes: the body of the heredoc of its region {@code config}. */
    static String tutorialConfig() throws IOException {
        String steps = Files.readString(EXAMPLE.resolve("steps.sh"));
        int region = steps.indexOf("# region: config");
        int start = steps.indexOf("<<'YAML'\n", region) + "<<'YAML'\n".length();
        int end = steps.indexOf("\nYAML\n", start);
        return steps.substring(start, end) + "\n";
    }

    static {
        // The level of the logger of the backend, as a gateway has it: INFO and above.
        Logger.getLogger("slay400.gateway.ibmi").setLevel(Level.INFO);
    }
}
