package ai.generativearchitects.slay400.gateway.server;

import ai.generativearchitects.slay400.gateway.log.JsonLogging;
import io.helidon.logging.common.spi.LoggingProvider;

/**
 * Helidon's logging, configured by the gateway (kickoff 11, A2): Helidon finds this provider through {@code ServiceLoader} when it
 * initialises its logging, and the provider makes every record of Java logging one JSON line on standard error. Without a
 * provider Helidon prints a line of text of its own ("There is no Helidon logging implementation on classpath"), which is not a
 * record and would be the one line of the running gateway that is not JSON.
 */
public final class GatewayLoggingProvider implements LoggingProvider {

    @Override
    public void initialization() {
        JsonLogging.install();
    }

    @Override
    public void runTime() {
        // Nothing to do at run time: the handler of initialization() stays.
    }
}
