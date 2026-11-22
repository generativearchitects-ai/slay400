package ai.generativearchitects.slay400.gateway.pipeline;

import ai.generativearchitects.slay400.http.HttpBinding;
import ai.generativearchitects.slay400.pcml.api.ApiOperation;
import ai.generativearchitects.slay400.pcml.codec.OperationCodec;
import java.util.Objects;

/**
 * An operation ready to serve: its HTTP binding and its codec, built once at start.
 *
 * @param operation         the operation
 * @param binding           the HTTP binding, with the read limits of the gateway
 * @param codec             the codec of the operation, built with {@code maxResponseValues}
 * @param maxResponseValues the most scalar values a response of the operation may hold: {@code defaults.maxResponseValues} of
 *                          the operation's bundle
 */
public record CompiledOperation(ApiOperation operation, HttpBinding binding, OperationCodec codec, int maxResponseValues) {

    /**
     * @throws NullPointerException     if an argument is {@code null}
     * @throws IllegalArgumentException if {@code maxResponseValues} is less than 1
     */
    public CompiledOperation {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(codec, "codec");
        if (maxResponseValues < 1) {
            throw new IllegalArgumentException("maxResponseValues must be at least 1");
        }
    }

    /** An operation whose codec has the default limit of {@code slay400-core}. */
    public CompiledOperation(ApiOperation operation, HttpBinding binding, OperationCodec codec) {
        this(operation, binding, codec, ai.generativearchitects.slay400.pcml.api.GatewayDefaults.DEFAULT_MAX_RESPONSE_VALUES);
    }
}
