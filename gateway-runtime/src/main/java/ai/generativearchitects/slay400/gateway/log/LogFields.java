package ai.generativearchitects.slay400.gateway.log;

import java.util.Objects;
import java.util.Optional;

/**
 * What the gateway knows about a log record beyond its message: tenant, environment, operation, correlation id and diagnostic
 * code (kickoff 11, A2). Passed as a parameter of the record, so that a log call stays a call of {@link System.Logger}; the JSON
 * formatter writes each one as a field of its own, never as part of the message. Immutable.
 */
public record LogFields(Optional<String> tenant, Optional<String> environment, Optional<String> operation, Optional<String> correlationId,
                        Optional<String> code, Optional<Throwable> thrown) {

    private static final LogFields NONE = new LogFields(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty());

    /** @throws NullPointerException if an argument is {@code null} */
    public LogFields {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(thrown, "thrown");
    }

    /** No field. */
    public static LogFields none() {
        return NONE;
    }

    /** The tenant and the environment. */
    public static LogFields of(String tenant, String environment) {
        return NONE.tenant(tenant).environment(environment);
    }

    /** Only the correlation id. */
    public static LogFields request(String correlationId) {
        return NONE.correlationId(correlationId);
    }

    /** With this tenant; an empty text leaves it out. */
    public LogFields tenant(String value) {
        return new LogFields(text(value), environment, operation, correlationId, code, thrown);
    }

    /** With this environment; an empty text leaves it out. */
    public LogFields environment(String value) {
        return new LogFields(tenant, text(value), operation, correlationId, code, thrown);
    }

    /** With this operation id; an empty text leaves it out. */
    public LogFields operation(String value) {
        return new LogFields(tenant, environment, text(value), correlationId, code, thrown);
    }

    /** With this correlation id; an empty text leaves it out. */
    public LogFields correlationId(String value) {
        return new LogFields(tenant, environment, operation, text(value), code, thrown);
    }

    /** With this diagnostic code; an empty text leaves it out. */
    public LogFields code(String value) {
        return new LogFields(tenant, environment, operation, correlationId, text(value), thrown);
    }

    /**
     * With an exception. {@link System.Logger} has no call with both parameters and an exception, so the exception travels here; the
     * formatter writes it as it writes the exception of a record.
     */
    public LogFields thrown(Throwable value) {
        return new LogFields(tenant, environment, operation, correlationId, code, Optional.of(Objects.requireNonNull(value, "value")));
    }

    private static Optional<String> text(String value) {
        return Optional.ofNullable(value).filter(v -> !v.isEmpty());
    }

    /** The fields in the words of a text log, for a formatter that is not the gateway's. */
    @Override
    public String toString() {
        StringBuilder out = new StringBuilder();
        tenant.ifPresent(v -> out.append(" tenant=").append(v));
        environment.ifPresent(v -> out.append(" environment=").append(v));
        operation.ifPresent(v -> out.append(" operation=").append(v));
        correlationId.ifPresent(v -> out.append(" correlationId=").append(v));
        code.ifPresent(v -> out.append(" code=").append(v));
        return out.toString().strip();
    }
}
