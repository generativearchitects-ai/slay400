package ai.generativearchitects.slay400.gateway.call;

import ai.generativearchitects.slay400.gateway.config.Backend;
import ai.generativearchitects.slay400.gateway.config.EnvironmentConfig;
import ai.generativearchitects.slay400.pcml.api.ApiOperation;
import java.util.List;

/**
 * A backend that the start can open for an environment, such as the {@code ibmi} backend of the module {@code gateway-ibmi}.
 * The start hands it the environment's configuration and the operations it will serve; nothing of the backend's own types
 * reaches this module.
 */
public interface BackendProvider {

    /** The backend this provider opens. */
    Backend backend();

    /**
     * Why this backend cannot call an operation, one message per reason; empty when it can. Messages name fields and
     * features, never a value.
     */
    List<String> unsupported(ApiOperation operation);

    /**
     * Opens the backend of one environment. Called only when every other check of the start passed.
     *
     * @param tenant       the tenant of the environment
     * @param environment  the environment, with its backend settings
     * @param operations   every operation the environment serves
     * @param connectCheck whether to check, as far as possible without calling a program, that the backend can be reached
     * @return the caller, or the problems that stop the start; a message never contains a host, a user, a password or a library
     */
    BackendStart start(String tenant, EnvironmentConfig environment, List<ApiOperation> operations, boolean connectCheck);
}
