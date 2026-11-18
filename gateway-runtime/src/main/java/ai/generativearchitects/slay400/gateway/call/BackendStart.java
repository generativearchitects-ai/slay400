package ai.generativearchitects.slay400.gateway.call;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What opening a backend for an environment came to.
 *
 * @param caller   the implementation of the call interface; present only if there is no problem
 * @param problems why the backend could not be opened
 */
public record BackendStart(Optional<ProgramCaller> caller, List<String> problems) {

    /**
     * @throws NullPointerException     if an argument is {@code null}
     * @throws IllegalArgumentException if there is both a caller and a problem, or neither
     */
    public BackendStart {
        Objects.requireNonNull(caller, "caller");
        problems = List.copyOf(problems);
        if (caller.isPresent() == !problems.isEmpty()) {
            throw new IllegalArgumentException("a backend start has a caller or problems, not both");
        }
    }

    /** A backend that opened. */
    public static BackendStart opened(ProgramCaller caller) {
        return new BackendStart(Optional.of(caller), List.of());
    }

    /** A backend that could not be opened. */
    public static BackendStart failed(List<String> problems) {
        return new BackendStart(Optional.empty(), problems);
    }
}
