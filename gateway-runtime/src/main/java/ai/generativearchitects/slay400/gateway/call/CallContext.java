package ai.generativearchitects.slay400.gateway.call;

import java.util.Objects;

/**
 * Who and what a program call is for. The call interface knows nothing else about the request.
 *
 * @param tenant        the tenant of the request
 * @param environment   the environment of the request
 * @param correlationId the correlation id of the request
 */
public record CallContext(String tenant, String environment, String correlationId) {

    /** @throws NullPointerException if an argument is {@code null} */
    public CallContext {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(correlationId, "correlationId");
    }
}
