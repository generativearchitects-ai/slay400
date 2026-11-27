package ai.generativearchitects.slay400.gateway.ibmi;

import ai.generativearchitects.slay400.gateway.log.LogFields;

import ai.generativearchitects.slay400.gateway.call.CallContext;
import ai.generativearchitects.slay400.gateway.call.CallOutcome;
import ai.generativearchitects.slay400.gateway.call.ProgramCaller;
import ai.generativearchitects.slay400.pcml.api.ApiOperation;
import ai.generativearchitects.slay400.pcml.codec.WireValue;
import java.time.Duration;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * The ibmi strategy of the call interface for one tenant and environment: the raw call, with one layout per operation.
 *
 * <table>
 *   <caption>Outcomes</caption>
 *   <tr><th>Situation</th><th>Outcome</th></tr>
 *   <tr><td>Program returned</td><td>{@code Returned}</td></tr>
 *   <tr><td>Cannot connect, sign-on refused, no free connection</td><td>{@code Unavailable} (503)</td></tr>
 *   <tr><td>No answer within the call time-out</td><td>{@code TimedOut} (504); the connection is closed</td></tr>
 *   <tr><td>Escape message, program not found</td><td>{@code Failed} with the message ids (502)</td></tr>
 *   <tr><td>The connection broke during the call; returned bytes that are not a value</td><td>{@code Failed} (502)</td></tr>
 * </table>
 *
 * <p>The pool belongs to the environment ({@link IbmiBackend.Environment}), which closes it; the caller closes nothing.
 *
 * <p>The operator's log gets message ids and the first-level text of IBM i messages, with the host, user, password and
 * libraries taken out; the caller gets neither. No parameter value is logged at any level.
 */
final class IbmiCaller implements ProgramCaller {

    private static final System.Logger LOG = System.getLogger("slay400.gateway.ibmi");

    private final Map<ApiOperation, ProgramLayout> layouts;
    private final ConnectionPool pool;
    private final Duration timeout;
    private final Redactor redactor;
    private final LogFields where;

    IbmiCaller(Map<ApiOperation, ProgramLayout> layouts, ConnectionPool pool, Duration timeout, Redactor redactor, LogFields where) {
        this.layouts = new IdentityHashMap<>(layouts);
        this.pool = Objects.requireNonNull(pool, "pool");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.redactor = Objects.requireNonNull(redactor, "redactor");
        this.where = Objects.requireNonNull(where, "where");
    }

    @Override
    public CallOutcome call(CallContext context, ApiOperation operation, List<WireValue> input) {
        ProgramLayout layout = layouts.get(operation);
        if (layout == null) {
            return new CallOutcome.Failed("the operation " + operation.id() + " has no layout");
        }
        IbmiConnection connection;
        try {
            connection = pool.borrow();
        } catch (ConnectionPool.NoConnection e) {
            LOG.log(System.Logger.Level.WARNING, "No connection for " + operation.program().name() + ": " + e.getMessage(),
                    where.operation(operation.id()).correlationId(context.correlationId()));
            return new CallOutcome.Unavailable();
        }
        List<ParameterBytes> parameters;
        try {
            parameters = layout.encode(input, connection.ccsid());
        } catch (LayoutException e) {
            pool.release(connection);
            return new CallOutcome.Failed("the input of " + operation.program().name() + " cannot be laid out: " + e.getMessage());
        }
        FutureTask<CallReply> task = new FutureTask<>(() -> connection.call(layout.programPath(), parameters));
        Thread.ofVirtual().name("ibmi-call").start(task);
        CallReply reply;
        try {
            reply = task.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            // The connection may be in the middle of the call: it cannot be used again. Closing it ends the wait of the call.
            pool.discard(connection);
            LOG.log(System.Logger.Level.WARNING, "The call of " + operation.program().name() + " did not answer within " + timeout.toMillis()
                    + " ms; its connection is closed", where.operation(operation.id()).correlationId(context.correlationId()));
            return new CallOutcome.TimedOut();
        } catch (ExecutionException e) {
            pool.discard(connection);
            String reason = e.getCause() instanceof IbmiFailure failure ? ConnectionPool.describe(failure) : "the call failed (" + e.getCause().getClass().getSimpleName() + ")";
            return new CallOutcome.Failed("the connection broke during the call of " + operation.program().name() + ": " + reason);
        } catch (InterruptedException e) {
            pool.discard(connection);
            Thread.currentThread().interrupt();
            return new CallOutcome.Failed("interrupted during the call of " + operation.program().name());
        }
        return switch (reply) {
            case CallReply.Escaped escaped -> {
                pool.release(connection);
                List<String> ids = escaped.messages().stream().map(IbmiMessage::id).filter(id -> !id.isEmpty()).toList();
                String messages = escaped.messages().stream().map(m -> (m.id().isEmpty() ? "" : m.id() + " ") + redactor.apply(m.text()))
                        .collect(Collectors.joining("; "));
                yield new CallOutcome.Failed("the call of " + operation.program().name() + " ended with: " + (messages.isEmpty() ? "no message" : messages), ids);
            }
            case CallReply.Completed completed -> {
                int ccsid = connection.ccsid();
                pool.release(connection);
                try {
                    yield new CallOutcome.Returned(layout.decode(completed.output(), input, ccsid));
                } catch (LayoutException e) {
                    yield new CallOutcome.Failed("the output of " + operation.program().name() + " cannot be read: " + e.getMessage());
                }
            }
        };
    }
}
