package ai.generativearchitects.slay400.gateway.load;

import ai.generativearchitects.slay400.gateway.call.BackendProvider;
import java.util.List;

/**
 * How the gateway starts: the backends it can open beyond the mock, and whether an environment's backend is checked by
 * connecting to it.
 *
 * @param providers    the backends available, at most one per {@link ai.generativearchitects.slay400.gateway.config.Backend}
 * @param connectCheck whether each environment's backend is checked at start; {@code --no-connect-check} turns it off
 */
public record StartOptions(List<BackendProvider> providers, boolean connectCheck) {

    /** @throws NullPointerException if {@code providers} is {@code null} */
    public StartOptions {
        providers = List.copyOf(providers);
    }

    /** Only the mock backend; nothing to connect to. */
    public static StartOptions mockOnly() {
        return new StartOptions(List.of(), true);
    }
}
