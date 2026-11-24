package ai.generativearchitects.slay400.gateway.server;

import ai.generativearchitects.slay400.gateway.ibmi.IbmiBackend;
import ai.generativearchitects.slay400.gateway.load.GatewayLoader;
import ai.generativearchitects.slay400.gateway.load.StartOptions;
import ai.generativearchitects.slay400.gateway.load.StartResult;
import ai.generativearchitects.slay400.gateway.load.Started;
import ai.generativearchitects.slay400.report.Report;
import ai.generativearchitects.slay400.report.TextReport;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The command line of the gateway: {@code --config <file>}, and {@code --no-connect-check} to start without checking, at start,
 * that every {@code ibmi} environment can connect and sign on. Exit codes: 0 after a normal stop, 1 when the start is refused
 * (the report goes to standard error), 2 for a usage error.
 */
public final class Main {

    static final String USAGE = "Usage: gateway --config <file> [--no-connect-check]";

    /** What the command line asks for. */
    record Arguments(Path config, boolean connectCheck) {
    }

    private Main() {
    }

    /** The arguments, or empty for a usage error. */
    static Optional<Arguments> parse(String[] args) {
        Path config = null;
        boolean connectCheck = true;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--config" -> {
                    if (config != null || i + 1 >= args.length) {
                        return Optional.empty();
                    }
                    config = Path.of(args[++i]);
                }
                case "--no-connect-check" -> {
                    if (!connectCheck) {
                        return Optional.empty();
                    }
                    connectCheck = false;
                }
                default -> {
                    return Optional.empty();
                }
            }
        }
        return config == null ? Optional.empty() : Optional.of(new Arguments(config.toAbsolutePath(), connectCheck));
    }

    /** Starts the gateway. */
    public static void main(String[] args) {
        Optional<Arguments> arguments = parse(args);
        if (arguments.isEmpty()) {
            System.err.println(USAGE);
            System.exit(2);
            return;
        }
        Path config = arguments.get().config();
        StartResult result = GatewayLoader.load(config, System::getenv, System.out::println,
                new StartOptions(List.of(new IbmiBackend()), arguments.get().connectCheck()).withEvents(System.out::println));
        if (!result.diagnostics().isEmpty()) {
            System.err.print(TextReport.render(new Report("start", config.getParent(), result.diagnostics())));
        }
        if (result.started().isEmpty()) {
            System.exit(1);
            return;
        }
        Started started = result.started().get();
        var server = HelidonServer.start(started);
        System.out.println(started.startLine());
        // The bundle files are polled from now on; the configuration file is not read again (kickoff 07).
        started.startReloading();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
            started.close();
        }));
    }
}
