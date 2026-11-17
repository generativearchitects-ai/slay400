package ai.generativearchitects.slay400.gateway.config;

import ai.generativearchitects.slay400.pcml.model.SourceLocation;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * One tenant: the bundles it serves and its environments.
 *
 * @param name         the tenant name, as it appears in the request path
 * @param bundles      the bundle files, resolved against the directory of the configuration file
 * @param bundleSource where each bundle is named in the configuration, in the same order as {@code bundles}
 * @param environments the environments, in file order
 * @param at           where the tenant is configured
 */
public record TenantConfig(String name, List<Path> bundles, List<SourceLocation> bundleSource, List<EnvironmentConfig> environments,
                           SourceLocation at) {

    /** @throws NullPointerException if an argument is {@code null} */
    public TenantConfig {
        Objects.requireNonNull(name, "name");
        bundles = List.copyOf(bundles);
        bundleSource = List.copyOf(bundleSource);
        environments = List.copyOf(environments);
        Objects.requireNonNull(at, "at");
    }
}
