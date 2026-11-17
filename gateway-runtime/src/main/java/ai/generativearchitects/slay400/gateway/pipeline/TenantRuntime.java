package ai.generativearchitects.slay400.gateway.pipeline;

import ai.generativearchitects.slay400.http.RouteTable;
import ai.generativearchitects.slay400.pcml.api.ApiOperation;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One tenant, ready to serve: one route table for all its bundles, its operations and its environments.
 *
 * @param name         the tenant name
 * @param routes       the route table of the tenant
 * @param operations   every operation, by operation instance
 * @param environments the environments by name
 */
public record TenantRuntime(String name, RouteTable routes, Map<ApiOperation, CompiledOperation> operations,
                            Map<String, EnvironmentRuntime> environments) {

    /** @throws NullPointerException if an argument is {@code null} */
    public TenantRuntime {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(routes, "routes");
        operations = java.util.Collections.unmodifiableMap(new IdentityHashMap<>(operations));
        environments = Map.copyOf(environments);
    }
}
