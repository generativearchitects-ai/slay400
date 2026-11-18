package ai.generativearchitects.slay400.gateway.server;

import java.time.Duration;
import java.util.Objects;

/**
 * The limits of the HTTP server, in one place. They are constants in this version, not configuration: {@link #DEFAULTS} is what
 * the gateway runs with; tests start servers with smaller values to see each limit act.
 *
 * <p>A body has two limits. The pipeline reads at most its own limit plus one byte (1 MiB, the read limits of
 * {@code pcml-http}) and answers 400 when the body is longer. The server's limit, {@link #maxPayloadBytes}, is larger. A request
 * that declares a body over the server's limit is answered 413 before anything is read. For a body over the pipeline's limit
 * and within the server's, the pipeline answers 400 and the server then reads and discards the rest, keeping the connection.
 * For a body sent in chunks, the server discards what follows the answer until the server's limit is passed, then closes the
 * connection.
 *
 * <p>Time. The whole body must arrive within {@link #readTimeout}, otherwise the connection is closed and the request ends as
 * a client that went away. Helidon 4.5.7 does not apply a read time-out of its own: a client that stops in the middle of its
 * request line or headers holds its connection until it closes it, bounded only by {@link #maxConnections}. Connections over
 * that number are not refused: they wait, unanswered, until another connection closes.
 *
 * @param maxPayloadBytes     the largest body the server accepts, in bytes
 * @param maxRequestLineBytes the request line (method, target and version) must be shorter than this, in bytes, line break not
 *                            counted; a longer one is answered 414
 * @param maxHeadersBytes     the largest size of all headers together, in bytes; larger headers are answered 400
 * @param readTimeout         how long the body of a request may take to arrive
 * @param idleTimeout         how long a connection may stay open between requests; Helidon closes it at about this age of
 *                            inactivity, at the next check
 * @param idleCheckPeriod     how often idle connections are looked for
 * @param maxConnections      the most connections open at the same time
 */
public record ServerLimits(long maxPayloadBytes, int maxRequestLineBytes, int maxHeadersBytes, Duration readTimeout, Duration idleTimeout,
                           Duration idleCheckPeriod, int maxConnections) {

    /** The limits the gateway runs with. */
    public static final ServerLimits DEFAULTS = new ServerLimits(2L * 1024 * 1024, 8192, 16 * 1024, Duration.ofSeconds(30), Duration.ofSeconds(60),
            Duration.ofSeconds(5), 1000);

    /** @throws IllegalArgumentException if a size or count is not positive */
    public ServerLimits {
        Objects.requireNonNull(readTimeout, "readTimeout");
        Objects.requireNonNull(idleTimeout, "idleTimeout");
        Objects.requireNonNull(idleCheckPeriod, "idleCheckPeriod");
        if (maxPayloadBytes < 1 || maxRequestLineBytes < 1 || maxHeadersBytes < 1 || maxConnections < 1) {
            throw new IllegalArgumentException("limits must be positive");
        }
    }
}
