package ai.generativearchitects.slay400.gateway.ibmi;

import ai.generativearchitects.slay400.gateway.log.LogFields;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * The connections of one tenant and environment: at most {@code maxConnections}, each in use by one call at a time. A
 * connection is checked before use and replaced when it no longer works; a new one is opened, signed on and given its library
 * list by the connector.
 *
 * <p>A sign-on that is refused is not tried again: repeated bad sign-ons can disable the user profile. From then on, every call
 * is answered as unavailable until the gateway restarts with other credentials. It is logged once, at error level, without
 * the user.
 */
final class ConnectionPool implements AutoCloseable {

    /** Why no connection could be had; the call is then unavailable. */
    static final class NoConnection extends Exception {
        private static final long serialVersionUID = 1L;

        NoConnection(String reason) {
            super(reason, null, false, false);
        }
    }

    private static final System.Logger LOG = System.getLogger("slay400.gateway.ibmi");

    private final IbmiConnector connector;
    private final Duration wait;
    private final LogFields where;
    private final Semaphore permits;
    private final Deque<IbmiConnection> idle = new ArrayDeque<>();
    private volatile boolean signOnRefused;
    private volatile boolean closed;

    /**
     * @param connector      opens connections
     * @param maxConnections the most connections open at the same time
     * @param wait           how long a call waits for a connection when all are in use
     * @param where          tenant and environment, for the log
     */
    ConnectionPool(IbmiConnector connector, int maxConnections, Duration wait, LogFields where) {
        this.connector = Objects.requireNonNull(connector, "connector");
        this.wait = Objects.requireNonNull(wait, "wait");
        this.where = Objects.requireNonNull(where, "where");
        this.permits = new Semaphore(maxConnections, true);
    }

    /**
     * Opens one connection now and keeps it for the first call: the check at start. Its failure is returned to the start, which
     * stops; a refused sign-on is remembered all the same.
     */
    void prime() throws IbmiFailure {
        if (!permits.tryAcquire()) {
            return;
        }
        try {
            IbmiConnection connection = open();
            synchronized (idle) {
                idle.push(connection);
            }
        } finally {
            permits.release();
        }
    }

    /** A working connection for one call; give it back with {@link #release} or {@link #discard}. */
    IbmiConnection borrow() throws NoConnection {
        if (closed) {
            throw new NoConnection("the gateway is stopping");
        }
        if (signOnRefused) {
            throw new NoConnection("the sign-on was refused earlier; the gateway does not sign on again until it restarts");
        }
        try {
            if (!permits.tryAcquire(wait.toNanos(), TimeUnit.NANOSECONDS)) {
                throw new NoConnection("every connection is in use and none became free within " + wait.toMillis() + " ms");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NoConnection("interrupted while waiting for a connection");
        }
        try {
            while (true) {
                IbmiConnection connection;
                synchronized (idle) {
                    connection = idle.poll();
                }
                if (connection == null) {
                    break;
                }
                if (connection.alive()) {
                    return connection;
                }
                connection.close();
                LOG.log(System.Logger.Level.INFO, "A connection to the IBM i no longer worked and is replaced", where);
            }
            return open();
        } catch (IbmiFailure failure) {
            permits.release();
            throw new NoConnection(describe(failure));
        } catch (RuntimeException e) {
            permits.release();
            throw e;
        }
    }

    private IbmiConnection open() throws IbmiFailure {
        if (signOnRefused) {
            throw new IbmiFailure(IbmiFailure.Kind.SIGN_ON_REFUSED, "the sign-on was refused earlier");
        }
        try {
            return connector.open();
        } catch (IbmiFailure failure) {
            if (failure.kind() == IbmiFailure.Kind.SIGN_ON_REFUSED) {
                refused();
            } else {
                LOG.log(System.Logger.Level.WARNING, "No connection to the IBM i: " + describe(failure), where);
            }
            throw failure;
        }
    }

    private synchronized void refused() {
        if (!signOnRefused) {
            signOnRefused = true;
            LOG.log(System.Logger.Level.ERROR, "The IBM i refused the sign-on. The gateway does not sign on again until it restarts, "
                    + "since repeated refused sign-ons can disable the user profile", where);
        }
    }

    /** Gives a connection back after a call that worked. */
    void release(IbmiConnection connection) {
        if (closed) {
            connection.close();
        } else {
            synchronized (idle) {
                idle.push(connection);
            }
        }
        permits.release();
    }

    /** Closes a connection that is no longer usable and frees its place. */
    void discard(IbmiConnection connection) {
        try {
            connection.close();
        } finally {
            permits.release();
        }
    }

    /** Closes the idle connections; a connection in use is closed when it is given back. */
    @Override
    public void close() {
        closed = true;
        synchronized (idle) {
            idle.forEach(IbmiConnection::close);
            idle.clear();
        }
    }

    /** A text for a failure, without anything of the configuration. */
    static String describe(IbmiFailure failure) {
        return switch (failure.kind()) {
            case UNREACHABLE -> "the host cannot be reached (" + failure.getMessage() + ")";
            case SIGN_ON_REFUSED -> "the sign-on was refused (" + failure.getMessage() + ")";
            case LIBRARY_LIST -> "the library list cannot be set (" + failure.getMessage() + ")";
            case BROKEN -> "the connection does not work (" + failure.getMessage() + ")";
        };
    }
}
