package ai.generativearchitects.slay400.gateway.config;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * The configuration of a gateway, read and checked.
 *
 * @param host    the address to listen on; the loopback address by default
 * @param port    the port to listen on
 * @param tenants the tenants, in file order
 * @param file    the configuration file
 */
public record GatewayConfig(String host, int port, List<TenantConfig> tenants, Path file) {

    /** The default bind address: listening on other addresses is a deliberate setting (decision 101). */
    public static final String DEFAULT_HOST = "127.0.0.1";
    /** The default port. */
    public static final int DEFAULT_PORT = 8080;

    /** @throws NullPointerException if an argument is {@code null} */
    public GatewayConfig {
        Objects.requireNonNull(host, "host");
        tenants = List.copyOf(tenants);
        Objects.requireNonNull(file, "file");
    }
}
