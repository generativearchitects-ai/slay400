package ai.generativearchitects.slay400.gateway.call;

import ai.generativearchitects.slay400.pcml.api.ApiOperation;
import java.util.List;

/**
 * The backend of one environment once it is open, such as the connection pool and credentials of an {@code ibmi}
 * environment. It is opened once, at start, and kept for as long as the gateway runs: a new set of bundles gets new callers
 * from it, not new connections (kickoff 07).
 */
public interface OpenBackend extends AutoCloseable {

    /**
     * A caller for the operations of one set of bundles. It uses this backend's connections and opens none of its own; a
     * caller of an earlier set keeps working until its requests are done.
     *
     * @param operations every operation of the set that the environment serves; each passed {@link BackendProvider#unsupported}
     */
    ProgramCaller callerFor(List<ApiOperation> operations);

    /** Releases what the backend holds, such as connections. Called once, when the gateway stops; never throws. */
    @Override
    default void close() {
    }
}
