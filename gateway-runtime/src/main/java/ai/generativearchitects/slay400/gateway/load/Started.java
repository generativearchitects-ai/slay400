package ai.generativearchitects.slay400.gateway.load;

import ai.generativearchitects.slay400.gateway.call.OpenBackend;
import ai.generativearchitects.slay400.gateway.config.GatewayConfig;
import ai.generativearchitects.slay400.gateway.pipeline.Gateway;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A gateway that passed every check of the start.
 *
 * @param config       the configuration
 * @param gateway      the request pipeline
 * @param environments how many environments are served
 * @param operations   how many operations were served at start, over all tenants
 * @param backends     the open backends of the environments, other than the mock; kept across reloads, closed by
 *                     {@link #close()}
 * @param reloader     polls the bundle files once {@link #startReloading()} is called
 * @param tls          the certificate and key of the API listener and their rotation, when it speaks TLS
 */
public record Started(GatewayConfig config, Gateway gateway, int environments, int operations, List<OpenBackend> backends, Reloader reloader,
                      Optional<TlsWatcher> tls) implements AutoCloseable {

    /** @throws NullPointerException if an argument is {@code null} */
    public Started {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(gateway, "gateway");
        backends = List.copyOf(backends);
        Objects.requireNonNull(reloader, "reloader");
        Objects.requireNonNull(tls, "tls");
    }

    /** A gateway without TLS. */
    public Started(GatewayConfig config, Gateway gateway, int environments, int operations, List<OpenBackend> backends, Reloader reloader) {
        this(config, gateway, environments, operations, backends, reloader, Optional.empty());
    }

    /** The one line the server logs when it listens: address, port, tenants, environments and operations. */
    public String startLine() {
        return "{\"event\":\"started\",\"address\":\"" + config.address().replace("\\", "\\\\").replace("\"", "\\\"") + "\",\"port\":" + config.port() + ",\"tenants\":" + config.tenants().size()
                + ",\"environments\":" + environments + ",\"operations\":" + operations + "}";
    }

    /** Starts polling the bundle files every {@code reload.intervalSeconds}; nothing when it is 0. Called once. */
    public void startReloading() {
        reloader.start();
    }

    /** Stops the polling, then closes the backends that were opened, such as the connections to an IBM i. Never throws. */
    @Override
    public void close() {
        reloader.close();
        for (OpenBackend backend : backends) {
            try {
                backend.close();
            } catch (RuntimeException e) {
                System.getLogger("slay400.gateway").log(System.Logger.Level.WARNING, "A backend could not be closed: " + e.getClass().getSimpleName());
            }
        }
    }
}
