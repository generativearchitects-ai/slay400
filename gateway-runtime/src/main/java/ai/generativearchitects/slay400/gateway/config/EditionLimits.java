package ai.generativearchitects.slay400.gateway.config;

import ai.generativearchitects.slay400.pcml.diagnostics.Diagnostic;
import ai.generativearchitects.slay400.pcml.diagnostics.Severity;
import java.util.ArrayList;
import java.util.List;

/**
 * The limits of the Community Edition, in one place and nowhere else: one tenant. The check is made on a configuration that
 * is otherwise valid.
 */
public final class EditionLimits {

    /** The most tenants the Community Edition serves. */
    public static final int MAX_TENANTS = 1;

    /** The diagnostic code of a limit that is exceeded. */
    public static final String CODE = "GWY008";

    private EditionLimits() {
    }

    /** One diagnostic for every tenant after the first, at the tenant; empty when the configuration is within the limits. */
    public static List<Diagnostic> check(GatewayConfig config) {
        List<Diagnostic> found = new ArrayList<>();
        List<TenantConfig> tenants = config.tenants();
        for (int i = MAX_TENANTS; i < tenants.size(); i++) {
            found.add(new Diagnostic(CODE, Severity.ERROR,
                    "The Community Edition serves " + MAX_TENANTS + " tenant; this configuration has " + tenants.size(), tenants.get(i).at()));
        }
        return found;
    }
}
