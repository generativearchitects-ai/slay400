package ai.generativearchitects.slay400.gateway.config;

import ai.generativearchitects.slay400.pcml.model.SourceLocation;
import java.util.Objects;

/**
 * One environment of a tenant.
 *
 * @param name    the environment name, as it appears in the request path
 * @param code    the value of {@code ${environment.code}} for injected parameters
 * @param backend how the environment's programs are called
 * @param at      where the environment is configured
 */
public record EnvironmentConfig(String name, String code, Backend backend, SourceLocation at) {

    /** @throws NullPointerException if an argument is {@code null} */
    public EnvironmentConfig {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(backend, "backend");
        Objects.requireNonNull(at, "at");
    }
}
