package ai.generativearchitects.slay400.gateway.config;

import java.util.Objects;

/**
 * The {@code management} section: the plain-HTTP listener for health and metrics, which never serves an operation (kickoff 08).
 *
 * @param address the address to listen on; the loopback address by default
 * @param port    the port; 0 turns the listener off
 */
public record ManagementConfig(String address, int port) {

    /** The default address: the loopback address, as for the API listener (decision 101). */
    public static final String DEFAULT_ADDRESS = "127.0.0.1";
    /** The default port. */
    public static final int DEFAULT_PORT = 9090;
    /** The listener on its defaults. */
    public static final ManagementConfig DEFAULTS = new ManagementConfig(DEFAULT_ADDRESS, DEFAULT_PORT);

    /**
     * @throws NullPointerException     if {@code address} is {@code null}
     * @throws IllegalArgumentException if {@code port} is not 0 to 65535
     */
    public ManagementConfig {
        Objects.requireNonNull(address, "address");
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port must be 0 to 65535");
        }
    }

    /** Whether the listener is on. */
    public boolean enabled() {
        return port != 0;
    }
}
