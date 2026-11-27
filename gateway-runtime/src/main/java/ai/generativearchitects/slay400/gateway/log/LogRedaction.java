package ai.generativearchitects.slay400.gateway.log;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.UnaryOperator;

/**
 * The redactors that every log record goes through before it is written (kickoff 11, A2). A backend that knows values of a
 * system, such as the host, user and libraries of an {@code ibmi} environment, registers its redactor while the environment is
 * open; the JSON formatter applies every registered one to the message, the exception text and the stack, so that a text that
 * came from the system cannot carry them into a log line, whoever logs it (the gateway or Helidon).
 */
public final class LogRedaction {

    private static final List<UnaryOperator<String>> REDACTORS = new CopyOnWriteArrayList<>();

    private LogRedaction() {
    }

    /** A registration, ended by {@link #close()}. */
    public interface Registration extends AutoCloseable {
        @Override
        void close();
    }

    /** Registers a redactor until the returned registration is closed. */
    public static Registration register(UnaryOperator<String> redactor) {
        Objects.requireNonNull(redactor, "redactor");
        REDACTORS.add(redactor);
        return () -> REDACTORS.remove(redactor);
    }

    /** The text with every registered redactor applied, in the order they were registered. */
    public static String apply(String text) {
        String out = text;
        for (UnaryOperator<String> redactor : REDACTORS) {
            out = redactor.apply(out);
        }
        return out;
    }
}
