package ai.generativearchitects.slay400.gateway.pipeline;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What the gateway serves at one moment: the tenants, each with the route table, operations, codecs, layouts and mocks of
 * its bundles. Immutable, and shares nothing mutable with another set; a reload builds a new one beside it and the gateway
 * switches to it at once (kickoff 07). The environments' open backends (connections, credentials) are not part of a set:
 * each set holds callers over them.
 *
 * @param tenants the tenants by name
 * @param bundles the bundles served, by tenant, in the order they were read
 */
public record ServedSet(Map<String, TenantRuntime> tenants, List<Bundle> bundles) {

    /**
     * One bundle of a set.
     *
     * @param tenant  the tenant that serves it
     * @param name    the bundle name of its manifest
     * @param version the bundle version of its manifest
     */
    public record Bundle(String tenant, String name, String version) {

        /** @throws NullPointerException if an argument is {@code null} */
        public Bundle {
            Objects.requireNonNull(tenant, "tenant");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(version, "version");
        }
    }

    /** @throws NullPointerException if an argument is {@code null} */
    public ServedSet {
        tenants = Map.copyOf(tenants);
        bundles = List.copyOf(bundles);
    }

    /** How many operations the set serves, over all tenants. */
    public int operationCount() {
        return tenants.values().stream().mapToInt(t -> t.operations().size()).sum();
    }
}
