package ai.generativearchitects.slay400.gateway.pipeline;

import ai.generativearchitects.slay400.gateway.call.ProgramCaller;
import ai.generativearchitects.slay400.pcml.api.ApiOperation;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One environment, ready to serve: the implementation of the call interface its backend selects, and the value of every
 * injected parameter of every operation.
 *
 * @param name     the environment name
 * @param caller   the implementation of the call interface
 * @param injected the text of every injected parameter, by operation instance and parameter name
 */
public record EnvironmentRuntime(String name, ProgramCaller caller, Map<ApiOperation, Map<String, String>> injected) {

    /** @throws NullPointerException if an argument is {@code null} */
    public EnvironmentRuntime {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(caller, "caller");
        IdentityHashMap<ApiOperation, Map<String, String>> copy = new IdentityHashMap<>();
        injected.forEach((operation, values) -> copy.put(operation, Map.copyOf(values)));
        injected = java.util.Collections.unmodifiableMap(copy);
    }
}
