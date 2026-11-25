package ai.generativearchitects.slay400.gateway.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** The command line: {@code --config <file>} and {@code --no-connect-check}, in any order, each once. */
class MainTest {

    private static Main.Arguments run(String file, boolean connectCheck) {
        return new Main.Arguments(Main.Command.RUN, Path.of(file).toAbsolutePath(), connectCheck);
    }

    @Test
    void runWithTheConfigurationFileAndTheConnectCheck() {
        assertThat(Main.parse(new String[] {"run", "--config", "g.yaml"})).contains(run("g.yaml", true));
        assertThat(Main.parse(new String[] {"run", "--config", "g.yaml", "--no-connect-check"})).contains(run("g.yaml", false));
        assertThat(Main.parse(new String[] {"run", "--no-connect-check", "--config", "g.yaml"})).contains(run("g.yaml", false));
    }

    @Test
    void theConfigurationFileAloneStillRuns() {
        assertThat(Main.parse(new String[] {"--config", "g.yaml"})).contains(run("g.yaml", true));
        assertThat(Main.parse(new String[] {"--config", "g.yaml", "--no-connect-check"})).contains(run("g.yaml", false));
        assertThat(Main.parse(new String[] {"--no-connect-check", "--config", "g.yaml"})).contains(run("g.yaml", false));
    }

    @Test
    void checkVersionAndHelp() {
        assertThat(Main.parse(new String[] {"check", "--config", "g.yaml"}))
                .contains(new Main.Arguments(Main.Command.CHECK, Path.of("g.yaml").toAbsolutePath(), false));
        assertThat(Main.parse(new String[] {"--version"})).map(Main.Arguments::command).contains(Main.Command.VERSION);
        assertThat(Main.parse(new String[] {"version"})).map(Main.Arguments::command).contains(Main.Command.VERSION);
        assertThat(Main.parse(new String[] {"help"})).map(Main.Arguments::command).contains(Main.Command.HELP);
        assertThat(Main.parse(new String[] {"--help"})).map(Main.Arguments::command).contains(Main.Command.HELP);
        assertThat(Main.version()).isNotBlank();
    }

    @Test
    void anythingElseIsAUsageError() {
        assertThat(Main.parse(new String[] {})).isEmpty();
        assertThat(Main.parse(new String[] {"--config"})).isEmpty();
        assertThat(Main.parse(new String[] {"--no-connect-check"})).isEmpty();
        assertThat(Main.parse(new String[] {"--config", "a", "--config", "b"})).isEmpty();
        assertThat(Main.parse(new String[] {"--config", "a", "--no-connect-check", "--no-connect-check"})).isEmpty();
        assertThat(Main.parse(new String[] {"--config", "a", "--verbose"})).isEmpty();
        assertThat(Main.parse(new String[] {"g.yaml"})).isEmpty();
        assertThat(Main.parse(new String[] {"run"})).isEmpty();
        assertThat(Main.parse(new String[] {"run", "g.yaml"})).isEmpty();
        assertThat(Main.parse(new String[] {"check"})).isEmpty();
        assertThat(Main.parse(new String[] {"check", "--config", "g.yaml", "--no-connect-check"})).isEmpty();
        assertThat(Main.parse(new String[] {"--version", "x"})).isEmpty();
        assertThat(Main.parse(new String[] {"help", "run"})).isEmpty();
        assertThat(Main.parse(new String[] {"start", "--config", "g.yaml"})).isEmpty();
        assertThat(Main.USAGE).isEqualTo("""
                Usage: slay400-gateway run --config <file> [--no-connect-check]
                       slay400-gateway check --config <file>
                       slay400-gateway --version
                       slay400-gateway help""");
    }

    // ---- check

    @org.junit.jupiter.api.io.TempDir
    Path dir;

    /** Runs {@code check} with standard output and error captured. */
    private String[] check(String config) {
        ai.generativearchitects.slay400.gateway.Fixtures.bundle(dir, "shop");
        Path file = ai.generativearchitects.slay400.gateway.Fixtures.write(dir, "gateway.yaml", config);
        java.io.PrintStream out = System.out;
        java.io.PrintStream err = System.err;
        java.io.ByteArrayOutputStream outBytes = new java.io.ByteArrayOutputStream();
        java.io.ByteArrayOutputStream errBytes = new java.io.ByteArrayOutputStream();
        int status;
        try {
            System.setOut(new java.io.PrintStream(outBytes, true, java.nio.charset.StandardCharsets.UTF_8));
            System.setErr(new java.io.PrintStream(errBytes, true, java.nio.charset.StandardCharsets.UTF_8));
            status = Main.check(file, ai.generativearchitects.slay400.gateway.load.StartOptions.mockOnly());
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
        return new String[] {String.valueOf(status), outBytes.toString(java.nio.charset.StandardCharsets.UTF_8), errBytes.toString(java.nio.charset.StandardCharsets.UTF_8)};
    }

    @Test
    void checkOnAGoodConfigurationSaysSoAndListensNowhere() throws Exception {
        int port = ServerFixture.freePort();
        String[] result = check("server:\n  port: " + port + "\n" + ai.generativearchitects.slay400.gateway.Fixtures.SHOP_CONFIG);
        assertThat(result[0]).isEqualTo("0");
        assertThat(result[1].trim()).isEqualTo("slay400-gateway check: gateway.yaml can be served, 2 operations, 0 warnings");
        assertThat(result[2]).isEmpty();
        // Nothing listens on the port of the configuration.
        try (java.net.ServerSocket free = new java.net.ServerSocket(port)) {
            assertThat(free.getLocalPort()).isEqualTo(port);
        }
    }

    @Test
    void checkOnABadConfigurationGivesTheDiagnosticsOfAStart() {
        String config = ai.generativearchitects.slay400.gateway.Fixtures.SHOP_CONFIG.replace("./shop-1.0.0.rpgapi", "./missing.rpgapi");
        String[] result = check(config);
        assertThat(result[0]).isEqualTo("1");
        assertThat(result[1].trim()).isEqualTo("slay400-gateway check: gateway.yaml cannot be served");
        var start = ai.generativearchitects.slay400.gateway.load.GatewayLoader.load(dir.resolve("gateway.yaml"), name -> null, line -> { });
        assertThat(start.started()).isEmpty();
        String report = ai.generativearchitects.slay400.report.TextReport.render(new ai.generativearchitects.slay400.report.Report("check", dir, start.diagnostics()));
        assertThat(result[2]).isEqualTo(report);
        assertThat(start.diagnostics()).isNotEmpty();
    }
}
