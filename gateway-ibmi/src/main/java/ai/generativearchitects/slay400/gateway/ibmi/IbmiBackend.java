package ai.generativearchitects.slay400.gateway.ibmi;

import ai.generativearchitects.slay400.gateway.call.BackendProvider;
import ai.generativearchitects.slay400.gateway.call.BackendStart;
import ai.generativearchitects.slay400.gateway.config.Backend;
import ai.generativearchitects.slay400.gateway.config.EnvironmentConfig;
import ai.generativearchitects.slay400.gateway.config.IbmiSettings;
import ai.generativearchitects.slay400.pcml.api.ApiOperation;
import java.time.Duration;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * The {@code ibmi} backend: program calls on IBM i through JT400, with the raw call for every operation (decision 114). It
 * opens one bounded pool of connections per tenant and environment. At start, unless the connect check is turned off, it opens
 * one connection, which resolves the host, signs on and sets the library list, without calling a program; that connection
 * serves the first call.
 */
public final class IbmiBackend implements BackendProvider {

    /** How long a call waits for a connection when every connection of the pool is in use. */
    public static final Duration POOL_WAIT = Duration.ofSeconds(2);

    private final Function<IbmiSettings, IbmiConnector> connectors;
    private final Duration poolWait;
    private final Function<IbmiSettings, Duration> timeout;

    /** The backend with JT400 connections. */
    public IbmiBackend() {
        this(Jt400Connector::new, POOL_WAIT, settings -> Duration.ofSeconds(settings.callTimeoutSeconds()));
    }

    /**
     * A backend with another kind of connection, and other times, for tests.
     *
     * @param connectors the connector of an environment's settings
     * @param poolWait   how long a call waits for a connection
     * @param timeout    the call time-out of an environment's settings
     */
    IbmiBackend(Function<IbmiSettings, IbmiConnector> connectors, Duration poolWait, Function<IbmiSettings, Duration> timeout) {
        this.connectors = Objects.requireNonNull(connectors, "connectors");
        this.poolWait = Objects.requireNonNull(poolWait, "poolWait");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    @Override
    public Backend backend() {
        return Backend.IBMI;
    }

    @Override
    public List<String> unsupported(ApiOperation operation) {
        return ProgramLayout.unsupported(operation);
    }

    @Override
    public BackendStart start(String tenant, EnvironmentConfig environment, List<ApiOperation> operations, boolean connectCheck) {
        IbmiSettings settings = environment.ibmi().orElseThrow(() -> new IllegalArgumentException("not an ibmi environment"));
        Map<ApiOperation, ProgramLayout> layouts = new IdentityHashMap<>();
        for (ApiOperation operation : operations) {
            layouts.put(operation, ProgramLayout.of(operation));
        }
        String where = "tenant " + tenant + ", environment " + environment.name();
        ConnectionPool pool = new ConnectionPool(connectors.apply(settings), settings.maxConnections(), poolWait, where);
        if (connectCheck) {
            try {
                pool.prime();
            } catch (IbmiFailure failure) {
                pool.close();
                return BackendStart.failed(List.of(ConnectionPool.describe(failure)));
            }
        }
        return BackendStart.opened(new IbmiCaller(layouts, pool, timeout.apply(settings), new Redactor(settings), where));
    }
}
