package ai.generativearchitects.slay400.gateway.call;

import ai.generativearchitects.slay400.mock.MockOutcome;
import ai.generativearchitects.slay400.mock.MockProgram;
import ai.generativearchitects.slay400.pcml.api.ApiOperation;
import ai.generativearchitects.slay400.pcml.codec.WireValue;
import java.time.Duration;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The mock call strategy: answers from the mock definitions of the bundle, with no IBM i. A delay of a case is honoured by
 * waiting on the calling thread, which is the request's own virtual thread.
 */
public final class MockCaller implements ProgramCaller {

    private final Map<ApiOperation, MockProgram> programs;

    /** @param programs the mock of every operation, by operation instance; copied */
    public MockCaller(Map<ApiOperation, MockProgram> programs) {
        this.programs = new IdentityHashMap<>(Objects.requireNonNull(programs, "programs"));
    }

    @Override
    public CallOutcome call(CallContext context, ApiOperation operation, List<WireValue> input) {
        MockProgram program = programs.get(operation);
        if (program == null) {
            return new CallOutcome.Failed("no mock for operation " + operation.id());
        }
        MockOutcome outcome = program.call(input);
        Duration delay = switch (outcome) {
            case MockOutcome.Returned returned -> returned.delay();
            case MockOutcome.Failed failed -> failed.delay();
        };
        if (!delay.isZero()) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new CallOutcome.Failed("interrupted");
            }
        }
        return switch (outcome) {
            case MockOutcome.Returned returned -> new CallOutcome.Returned(returned.output());
            case MockOutcome.Failed failed -> switch (failed.kind()) {
                case UNAVAILABLE -> new CallOutcome.Unavailable();
                case TIMEOUT -> new CallOutcome.TimedOut();
            };
        };
    }
}
