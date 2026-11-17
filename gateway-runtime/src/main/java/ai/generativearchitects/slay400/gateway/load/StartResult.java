package ai.generativearchitects.slay400.gateway.load;

import ai.generativearchitects.slay400.pcml.diagnostics.Diagnostic;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What the start came to. A gateway is present only if there is no error: it never starts half configured.
 *
 * @param started     the gateway, if the start succeeded
 * @param diagnostics every diagnostic found, warnings included
 */
public record StartResult(Optional<Started> started, List<Diagnostic> diagnostics) {

    /** @throws NullPointerException if an argument is {@code null} */
    public StartResult {
        Objects.requireNonNull(started, "started");
        diagnostics = List.copyOf(diagnostics);
    }
}
