package ai.generativearchitects.slay400.gateway.load;

import ai.generativearchitects.slay400.gateway.config.GatewayConfig;
import ai.generativearchitects.slay400.gateway.pipeline.Gateway;
import java.util.Objects;

/**
 * A gateway that passed every check of the start.
 *
 * @param config       the configuration
 * @param gateway      the request pipeline
 * @param environments how many environments are served
 * @param operations   how many operations are served, over all tenants
 */
public record Started(GatewayConfig config, Gateway gateway, int environments, int operations) {

    /** @throws NullPointerException if an argument is {@code null} */
    public Started {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(gateway, "gateway");
    }

    /** The one line the server logs when it listens: address, port, tenants, environments and operations. */
    public String startLine() {
        return "{\"event\":\"started\",\"address\":\"" + config.host() + "\",\"port\":" + config.port() + ",\"tenants\":" + config.tenants().size()
                + ",\"environments\":" + environments + ",\"operations\":" + operations + "}";
    }
}
