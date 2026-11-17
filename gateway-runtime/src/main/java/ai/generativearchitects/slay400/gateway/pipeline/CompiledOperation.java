package ai.generativearchitects.slay400.gateway.pipeline;

import ai.generativearchitects.slay400.http.HttpBinding;
import ai.generativearchitects.slay400.pcml.api.ApiOperation;
import ai.generativearchitects.slay400.pcml.codec.OperationCodec;
import java.util.Objects;

/**
 * An operation ready to serve: its HTTP binding and its codec, built once at start.
 *
 * @param operation the operation
 * @param binding   the HTTP binding, with the read limits of the gateway
 * @param codec     the codec of the operation
 */
public record CompiledOperation(ApiOperation operation, HttpBinding binding, OperationCodec codec) {

    /** @throws NullPointerException if an argument is {@code null} */
    public CompiledOperation {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(codec, "codec");
    }
}
