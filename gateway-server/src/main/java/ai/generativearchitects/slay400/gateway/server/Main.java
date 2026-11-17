package ai.generativearchitects.slay400.gateway.server;

import ai.generativearchitects.slay400.gateway.load.GatewayLoader;
import ai.generativearchitects.slay400.gateway.load.StartResult;
import ai.generativearchitects.slay400.report.Report;
import ai.generativearchitects.slay400.report.TextReport;
import java.nio.file.Path;

/**
 * The command line of the gateway: {@code --config <file>}. Exit codes: 0 after a normal stop, 1 when the start is refused
 * (the report goes to standard error), 2 for a usage error.
 */
public final class Main {

    private Main() {
    }

    /** Starts the gateway. */
    public static void main(String[] args) {
        if (args.length != 2 || !args[0].equals("--config")) {
            System.err.println("Usage: gateway --config <file>");
            System.exit(2);
            return;
        }
        Path config = Path.of(args[1]).toAbsolutePath();
        StartResult result = GatewayLoader.load(config, System::getenv, System.out::println);
        if (!result.diagnostics().isEmpty()) {
            System.err.print(TextReport.render(new Report("start", config.getParent(), result.diagnostics())));
        }
        if (result.started().isEmpty()) {
            System.exit(1);
            return;
        }
        var server = HelidonServer.start(result.started().get());
        System.out.println(result.started().get().startLine());
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
    }
}
