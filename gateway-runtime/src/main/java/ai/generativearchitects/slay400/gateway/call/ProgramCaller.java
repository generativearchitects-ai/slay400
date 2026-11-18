package ai.generativearchitects.slay400.gateway.call;

import ai.generativearchitects.slay400.pcml.api.ApiOperation;
import ai.generativearchitects.slay400.pcml.codec.WireValue;
import java.util.List;

/**
 * The call interface: calls the program of an operation with the values {@code OperationCodec.encode} produced. The
 * implementation is chosen by the {@code backend} of the environment; nothing above this interface knows which one runs, and
 * no JT400 type may appear in it.
 */
public interface ProgramCaller extends AutoCloseable {

    /**
     * Calls the program. An implementation reports every failure as an outcome and does not throw for the data it is given.
     *
     * @param context   tenant, environment and correlation id of the request
     * @param operation the operation whose program is called
     * @param input     one value per parameter, in PCML order
     */
    CallOutcome call(CallContext context, ApiOperation operation, List<WireValue> input);

    /** Releases what the implementation holds, such as connections. Called once, when the gateway stops; never throws. */
    @Override
    default void close() {
    }
}
