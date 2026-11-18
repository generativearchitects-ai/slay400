package ai.generativearchitects.slay400.gateway.load;

import ai.generativearchitects.slay400.gateway.call.ProgramCaller;
import ai.generativearchitects.slay400.gateway.config.GatewayConfig;
import ai.generativearchitects.slay400.gateway.pipeline.Gateway;
import java.util.List;
import java.util.Objects;

/**
 * A gateway that passed every check of the start.
 *
 * @param config       the configuration
 * @param gateway      the request pipeline
 * @param environments how many environments are served
 * @param operations   how many operations are served, over all tenants
 * @param backends     the callers of the backends that were opened, other than the mock; {@link #close()} closes them
 */
public record Started(GatewayConfig config, Gateway gateway, int environments, int operations, List<ProgramCaller> backends) implements AutoCloseable {

    /** @throws NullPointerException if an argument is {@code null} */
    public Started {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(gateway, "gateway");
        backends = List.copyOf(backends);
    }

    /** A gateway with the mock backend only. */
    public Started(GatewayConfig config, Gateway gateway, int environments, int operations) {
        this(config, gateway, environments, operations, List.of());
    }

    /** The one line the server logs when it listens: address, port, tenants, environments and operations. */
    public String startLine() {
        return "{\"event\":\"started\",\"address\":\"" + config.host().replace("\\", "\\\\").replace("\"", "\\\"") + "\",\"port\":" + config.port() + ",\"tenants\":" + config.tenants().size()
                + ",\"environments\":" + environments + ",\"operations\":" + operations + "}";
    }

    /** Closes the backends that were opened, such as the connections to an IBM i. Never throws. */
    @Override
    public void close() {
        for (ProgramCaller backend : backends) {
            try {
                backend.close();
            } catch (RuntimeException e) {
                System.getLogger("slay400.gateway").log(System.Logger.Level.WARNING, "A backend could not be closed: " + e.getClass().getSimpleName());
            }
        }
    }
}
