package ai.generativearchitects.slay400.gateway.config;

import java.util.List;
import java.util.Objects;

/**
 * The settings of an environment with {@code backend: ibmi}.
 *
 * <p>{@link #toString()} names neither the host, nor the libraries, nor the credentials: a log line or a diagnostic that
 * shows these settings says nothing about the system they point to.
 *
 * @param host               the host name or address of the IBM i
 * @param libraryList        the user part of the library list programs are called through, in order, in upper case
 * @param credentials        the user and password, from environment variables
 * @param tls                whether the secure host servers are used, with certificate verification against the JVM's trust
 *                           store; {@code true} by default
 * @param maxConnections     the most connections the environment opens; 4 by default
 * @param callTimeoutSeconds how long a program call may take before it is answered 504; 30 by default
 */
public record IbmiSettings(String host, List<String> libraryList, Credentials credentials, boolean tls, int maxConnections, int callTimeoutSeconds) {

    /** The default of {@code pool.maxConnections}. */
    public static final int DEFAULT_MAX_CONNECTIONS = 4;
    /** The default of {@code callTimeoutSeconds}. */
    public static final int DEFAULT_CALL_TIMEOUT_SECONDS = 30;

    /** @throws NullPointerException if an argument is {@code null} */
    public IbmiSettings {
        Objects.requireNonNull(host, "host");
        libraryList = List.copyOf(libraryList);
        Objects.requireNonNull(credentials, "credentials");
    }

    /** The settings that say nothing about the system: TLS, pool size, time-out and the number of libraries. */
    @Override
    public String toString() {
        return "IbmiSettings[tls=" + tls + ", maxConnections=" + maxConnections + ", callTimeoutSeconds=" + callTimeoutSeconds + ", libraries="
                + libraryList.size() + "]";
    }
}
