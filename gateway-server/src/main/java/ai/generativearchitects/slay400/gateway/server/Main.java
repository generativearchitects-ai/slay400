package ai.generativearchitects.slay400.gateway.server;

import ai.generativearchitects.slay400.gateway.load.GatewayLoader;
import ai.generativearchitects.slay400.gateway.load.OperatorLibraries;
import ai.generativearchitects.slay400.gateway.load.StartOptions;
import ai.generativearchitects.slay400.gateway.load.StartResult;
import ai.generativearchitects.slay400.gateway.load.Started;
import ai.generativearchitects.slay400.pcml.diagnostics.Diagnostic;
import ai.generativearchitects.slay400.pcml.diagnostics.Severity;
import ai.generativearchitects.slay400.report.Report;
import ai.generativearchitects.slay400.report.TextReport;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The command line of the gateway, {@value #NAME} (decision 147, gateway kickoff 10):
 *
 * <ul>
 *   <li>{@code run --config <file> [--no-connect-check]} starts the gateway; {@code --config <file>} alone does the same, so
 *       that nothing documented before breaks;</li>
 *   <li>{@code check --config <file>} reads and checks the configuration, the operator's libraries, the certificate and the
 *       bundles as a start does, opens no connection and does not listen, and exits;</li>
 *   <li>{@code --version} (and {@code version}) prints the version; {@code help} the usage.</li>
 * </ul>
 *
 * <p>Exit codes: 0 after a normal stop, a check without errors, the version or the usage asked for; 1 when the start or the
 * check finds an error (the report goes to standard error); 2 for a usage error; 3, from the launcher only, when no Java 21 or
 * later is found; 143 after {@code SIGTERM}.
 */
public final class Main {

    /** The name of the program, in every usage and error text. */
    static final String NAME = "slay400-gateway";

    static final String USAGE = String.join("\n",
            "Usage: " + NAME + " run --config <file> [--no-connect-check]",
            "       " + NAME + " check --config <file>",
            "       " + NAME + " --version",
            "       " + NAME + " help");

    /** What the command line asks for. */
    enum Command {
        RUN, CHECK, VERSION, HELP
    }

    /** What the command line asks for, with the configuration for {@code run} and {@code check}. */
    record Arguments(Command command, Path config, boolean connectCheck) {
    }

    private Main() {
    }

    /** The arguments, or empty for a usage error. */
    static Optional<Arguments> parse(String[] args) {
        if (args.length == 0) {
            return Optional.empty();
        }
        String[] rest = java.util.Arrays.copyOfRange(args, 1, args.length);
        return switch (args[0]) {
            case "run" -> options(rest, Command.RUN, true);
            case "check" -> options(rest, Command.CHECK, false);
            case "--version", "version" -> args.length == 1 ? Optional.of(new Arguments(Command.VERSION, null, false)) : Optional.empty();
            case "help", "--help" -> args.length == 1 ? Optional.of(new Arguments(Command.HELP, null, false)) : Optional.empty();
            case "--config", "--no-connect-check" -> options(args, Command.RUN, true);
            default -> Optional.empty();
        };
    }

    /** {@code --config <file>}, and for {@code run} {@code --no-connect-check}, once each, in any order. */
    private static Optional<Arguments> options(String[] args, Command command, boolean connectOption) {
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
                    if (!connectOption || !connectCheck) {
                        return Optional.empty();
                    }
                    connectCheck = false;
                }
                default -> {
                    return Optional.empty();
                }
            }
        }
        // A check opens no connection: it never signs on.
        return config == null ? Optional.empty() : Optional.of(new Arguments(command, config.toAbsolutePath(), command == Command.RUN && connectCheck));
    }

    /** The version of this build, from the manifest of its jar; {@code unknown} outside a distribution. */
    static String version() {
        String version = Main.class.getPackage().getImplementationVersion();
        return version == null ? "unknown" : version;
    }

    /** Runs the command line. */
    public static void main(String[] args) {
        Optional<Arguments> arguments = parse(args);
        if (arguments.isEmpty()) {
            System.err.println(USAGE);
            System.exit(2);
            return;
        }
        switch (arguments.get().command()) {
            case HELP -> {
                System.out.println(USAGE);
                return;
            }
            case VERSION -> {
                System.out.println(NAME + " " + version());
                return;
            }
            default -> {
            }
        }
        Path config = arguments.get().config();
        boolean check = arguments.get().command() == Command.CHECK;
        Optional<Libraries> libraries = libraries(System.err, config, check);
        if (libraries.isEmpty()) {
            System.exit(1);
            return;
        }
        StartOptions options = new StartOptions(libraries.get().loaded().providers(), arguments.get().connectCheck())
                .withUnavailable(OperatorLibraries.unavailable(libraries.get().found(), libraries.get().loaded()));
        if (check) {
            System.exit(check(config, options));
            return;
        }
        StartResult result = GatewayLoader.load(config, System::getenv, System.out::println, options.withEvents(System.out::println));
        if (!result.diagnostics().isEmpty()) {
            System.err.print(TextReport.render(new Report("start", config.getParent(), result.diagnostics())));
        }
        if (result.started().isEmpty()) {
            System.exit(1);
            return;
        }
        Started started = result.started().get();
        var server = HelidonServer.start(started);
        String line = started.startLine();
        System.out.println(line.substring(0, line.length() - 1) + libraries.get().found().startFields() + "}");
        // The bundle files are polled from now on; the configuration file is not read again (kickoff 07).
        started.startReloading();
        // On SIGTERM: the listeners stop taking connections and wait at most HelidonServer.SHUTDOWN_GRACE for the requests in
        // flight; then the poll thread stops and the backends close.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("{\"event\":\"stopping\"}");
            server.stop();
            started.close();
            System.out.println("{\"event\":\"stopped\"}");
        }, "slay400-shutdown"));
    }

    /** The operator's libraries and the backends loaded with them. */
    record Libraries(OperatorLibraries.Found found, OperatorLibraries.Loaded loaded) {
    }

    /**
     * Looks at the folder of the operator's libraries and loads the backends; reports its diagnostics. Empty when one of them
     * is an error.
     */
    static Optional<Libraries> libraries(PrintStream err, Path config, boolean check) {
        Optional<Path> folder = OperatorLibraries.folder(System::getenv, System.getProperties());
        OperatorLibraries.Found found;
        try {
            found = folder.map(OperatorLibraries::scan).orElse(new OperatorLibraries.Found(Optional.empty(), Optional.empty(), List.of()));
        } catch (UncheckedIOException e) {
            err.println(NAME + ": the folder " + folder.orElseThrow() + " cannot be read (" + e.getCause().getClass().getSimpleName() + ")");
            return Optional.empty();
        }
        if (!found.diagnostics().isEmpty()) {
            err.print(TextReport.render(new Report(check ? "check" : "start", config.getParent(), found.diagnostics())));
        }
        if (found.diagnostics().stream().anyMatch(d -> d.severity() == Severity.ERROR)) {
            return Optional.empty();
        }
        return Optional.of(new Libraries(found, OperatorLibraries.providers(found, backendJars(), Main.class.getClassLoader())));
    }

    /** The jars of {@code lib/ibmi} of the distribution; none outside one. */
    private static List<Path> backendJars() {
        String home = System.getProperty(OperatorLibraries.HOME_PROPERTY);
        if (home == null || home.isBlank()) {
            return List.of();
        }
        Path folder = Path.of(home, "lib", "ibmi");
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(folder)) {
            return new ArrayList<>(files.filter(f -> f.getFileName().toString().endsWith(".jar")).sorted().toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * {@code check}: everything a start does up to listening, with the same diagnostics, and no connection; then the gateway
     * is closed again. Returns the exit code.
     */
    static int check(Path config, StartOptions options) {
        StartResult result = GatewayLoader.load(config, System::getenv, line -> { }, options);
        List<Diagnostic> diagnostics = result.diagnostics();
        if (!diagnostics.isEmpty()) {
            System.err.print(TextReport.render(new Report("check", config.getParent(), diagnostics)));
        }
        result.started().ifPresent(Started::close);
        long warnings = diagnostics.stream().filter(d -> d.severity() == Severity.WARNING).count();
        if (result.started().isEmpty()) {
            System.out.println(NAME + " check: " + config.getFileName() + " cannot be served");
            return 1;
        }
        System.out.println(NAME + " check: " + config.getFileName() + " can be served, " + result.started().get().operations() + " operations, "
                + warnings + (warnings == 1 ? " warning" : " warnings"));
        return 0;
    }
}
