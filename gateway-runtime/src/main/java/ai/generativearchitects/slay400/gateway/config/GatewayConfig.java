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
 * @param reloadIntervalSeconds how often the bundle files are looked at, in seconds; 0 turns reloading off. The configuration
 *                              file itself is read once, at start
 */
public record GatewayConfig(String host, int port, List<TenantConfig> tenants, Path file, int reloadIntervalSeconds) {

    /** The default bind address: listening on other addresses is a deliberate setting (decision 101). */
    public static final String DEFAULT_HOST = "127.0.0.1";
    /** The default port. */
    public static final int DEFAULT_PORT = 8080;

    /** How often the bundle files are looked at, by default; {@code reload.intervalSeconds} (kickoff 07). */
    public static final int DEFAULT_RELOAD_INTERVAL_SECONDS = 10;
    /** The longest interval of {@code reload.intervalSeconds}. */
    public static final int MAX_RELOAD_INTERVAL_SECONDS = 3600;

    /**
     * @throws NullPointerException     if an argument is {@code null}
     * @throws IllegalArgumentException if {@code reloadIntervalSeconds} is not 0 to {@value #MAX_RELOAD_INTERVAL_SECONDS}
     */
    public GatewayConfig {
        Objects.requireNonNull(host, "host");
        tenants = List.copyOf(tenants);
        Objects.requireNonNull(file, "file");
        if (reloadIntervalSeconds < 0 || reloadIntervalSeconds > MAX_RELOAD_INTERVAL_SECONDS) {
            throw new IllegalArgumentException("reloadIntervalSeconds must be 0 to " + MAX_RELOAD_INTERVAL_SECONDS);
        }
    }

    /** A configuration with the default reload interval. */
    public GatewayConfig(String host, int port, List<TenantConfig> tenants, Path file) {
        this(host, port, tenants, file, DEFAULT_RELOAD_INTERVAL_SECONDS);
    }
}
