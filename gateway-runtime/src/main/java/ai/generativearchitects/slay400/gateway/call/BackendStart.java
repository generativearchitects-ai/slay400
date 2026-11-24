package ai.generativearchitects.slay400.gateway.call;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What opening a backend for an environment came to.
 *
 * @param backend  the open backend of the environment; present only if there is no problem
 * @param problems why the backend could not be opened
 */
public record BackendStart(Optional<OpenBackend> backend, List<String> problems) {

    /**
     * @throws NullPointerException     if an argument is {@code null}
     * @throws IllegalArgumentException if there is both a caller and a problem, or neither
     */
    public BackendStart {
        Objects.requireNonNull(backend, "backend");
        problems = List.copyOf(problems);
        if (backend.isPresent() == !problems.isEmpty()) {
            throw new IllegalArgumentException("a backend start has a backend or problems, not both");
        }
    }

    /** A backend that opened. */
    public static BackendStart opened(OpenBackend backend) {
        return new BackendStart(Optional.of(backend), List.of());
    }

    /** A backend that could not be opened. */
    public static BackendStart failed(List<String> problems) {
        return new BackendStart(Optional.empty(), problems);
    }
}
