package ai.generativearchitects.slay400.gateway.config;

import ai.generativearchitects.slay400.pcml.diagnostics.Diagnostic;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What reading a configuration file came to: the configuration, only if nothing is wrong, and the problems.
 *
 * @param config      the configuration; empty if there is any error
 * @param diagnostics what is wrong, in file order
 */
public record ConfigResult(Optional<GatewayConfig> config, List<Diagnostic> diagnostics) {

    /** @throws NullPointerException if an argument is {@code null} */
    public ConfigResult {
        Objects.requireNonNull(config, "config");
        diagnostics = List.copyOf(diagnostics);
    }
}
