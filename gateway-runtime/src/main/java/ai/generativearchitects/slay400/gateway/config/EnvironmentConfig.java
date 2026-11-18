package ai.generativearchitects.slay400.gateway.config;

import ai.generativearchitects.slay400.pcml.model.SourceLocation;
import java.util.Objects;
import java.util.Optional;

/**
 * One environment of a tenant.
 *
 * @param name    the environment name, as it appears in the request path
 * @param code    the value of {@code ${environment.code}} for injected parameters
 * @param backend how the environment's programs are called
 * @param ibmi    the settings of an {@link Backend#IBMI} environment; present exactly for that backend
 * @param at      where the environment is configured
 */
public record EnvironmentConfig(String name, String code, Backend backend, Optional<IbmiSettings> ibmi, SourceLocation at) {

    /**
     * @throws NullPointerException     if an argument is {@code null}
     * @throws IllegalArgumentException if {@code ibmi} is present for another backend than {@link Backend#IBMI}, or missing for it
     */
    public EnvironmentConfig {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(backend, "backend");
        Objects.requireNonNull(ibmi, "ibmi");
        Objects.requireNonNull(at, "at");
        if (ibmi.isPresent() != (backend == Backend.IBMI)) {
            throw new IllegalArgumentException("ibmi settings are present exactly for the ibmi backend");
        }
    }

    /** An environment of a backend without settings of its own, such as {@link Backend#MOCK}. */
    public EnvironmentConfig(String name, String code, Backend backend, SourceLocation at) {
        this(name, code, backend, Optional.empty(), at);
    }
}
