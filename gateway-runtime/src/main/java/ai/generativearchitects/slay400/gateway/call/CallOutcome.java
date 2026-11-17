package ai.generativearchitects.slay400.gateway.call;

import ai.generativearchitects.slay400.pcml.codec.WireValue;
import java.util.List;
import java.util.Objects;

/** What a program call came to. */
public sealed interface CallOutcome {

    /**
     * The program ran and returned its parameters.
     *
     * @param output one value per parameter, in PCML order, as {@code OperationCodec.decode} takes them
     */
    record Returned(List<WireValue> output) implements CallOutcome {
        /** @throws NullPointerException if {@code output} is {@code null} */
        public Returned {
            output = List.copyOf(output);
        }
    }

    /** The program cannot be reached: the HTTP status is 503. */
    record Unavailable() implements CallOutcome {
    }

    /** The program did not answer in time: the HTTP status is 504. */
    record TimedOut() implements CallOutcome {
    }

    /**
     * The call failed in some other way: the HTTP status is 502.
     *
     * @param reason what happened, for the log of the operator; never shown to the caller
     */
    record Failed(String reason) implements CallOutcome {
        /** @throws NullPointerException if {@code reason} is {@code null} */
        public Failed {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
