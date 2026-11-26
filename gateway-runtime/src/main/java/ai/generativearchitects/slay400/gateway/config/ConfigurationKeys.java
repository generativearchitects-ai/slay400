package ai.generativearchitects.slay400.gateway.config;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Every key of the gateway's configuration file, in one place: its path, type, whether it must be there, its default, its
 * limits, when a change takes effect, and what it means. {@link ConfigReader} takes the keys it accepts and the limits it checks
 * from here, and the configuration reference of the documentation is generated from here, so the two cannot drift apart.
 *
 * <p>A path is the dotted list of keys from the top of the file. A segment {@code <tenant>} or {@code <environment>} stands for a
 * name the operator chooses.
 */
public final class ConfigurationKeys {

    /** The segment of a path that stands for a tenant name. */
    public static final String TENANT = "<tenant>";
    /** The segment of a path that stands for an environment name. */
    public static final String ENVIRONMENT = "<environment>";

    /** The most libraries in {@code libraryList}. */
    public static final int MAX_LIBRARIES = 250;
    /** The most connections of a pool. */
    public static final int MAX_POOL = 64;
    /** The longest call timeout, in seconds. */
    public static final int MAX_CALL_TIMEOUT_SECONDS = 3600;
    /** The largest port number. */
    public static final int MAX_PORT = 65535;

    /** When a change of any key takes effect: the file is read once, at start. */
    public static final String AT_RESTART = "at a restart";

    /**
     * One key of the configuration file.
     *
     * @param path      the dotted path from the top of the file
     * @param type      the type of its value, in the words of the reference
     * @param required  when it must be there: {@code "yes"}, {@code "no"}, or a condition such as {@code "with backend: ibmi"}
     * @param byDefault the value used when it is absent, or {@code ""} when there is none
     * @param limits    the values accepted, in words, or {@code ""} when the type says it all
     * @param ibmiOnly  whether the key is accepted only in an environment with {@code backend: ibmi}
     * @param meaning   one sentence on what it does
     */
    public record Key(String path, String type, String required, String byDefault, String limits, boolean ibmiOnly, String meaning) {

        /** When a change of this key takes effect. */
        public String takesEffect() {
            return AT_RESTART;
        }

        /** The last segment of the path. */
        public String name() {
            return path.substring(path.lastIndexOf('.') + 1);
        }

        /** The path without its last segment, or {@code ""} for a key at the top of the file. */
        public String parent() {
            int dot = path.lastIndexOf('.');
            return dot < 0 ? "" : path.substring(0, dot);
        }
    }

    /** The path of a tenant's keys. */
    public static final String TENANT_PATH = "tenants." + TENANT;
    /** The path of an environment's keys. */
    public static final String ENVIRONMENT_PATH = TENANT_PATH + ".environments." + ENVIRONMENT;

    /** Every key, in the order of the reference. */
    public static final List<Key> KEYS = List.of(
            new Key("server", "section", "no", "", "", false, "The API listener: it serves the operations and the health checks."),
            new Key("server.address", "text", "no", GatewayConfig.DEFAULT_ADDRESS, "no white space", false,
                    "The address the API listener listens on; 0.0.0.0 for every address."),
            new Key("server.port", "whole number", "no", Integer.toString(GatewayConfig.DEFAULT_PORT), "1 to " + MAX_PORT, false,
                    "The port of the API listener."),
            new Key("server.tls", "section", "no", "", "", false,
                    "With this section the API listener speaks HTTPS only, TLS 1.3 and 1.2; without it, plain HTTP."),
            new Key("server.tls.certificate", "file name", "with server.tls", "", "PEM: the certificate, then its chain", false,
                    "The certificate of the API listener, relative to the configuration file."),
            new Key("server.tls.key", "file name", "with server.tls", "", "PEM, PKCS#8", false,
                    "The private key of the certificate, relative to the configuration file."),
            new Key("server.tls.keyPassword", "placeholder", "for an encrypted key", "", "a single ${NAME}", false,
                    "The environment variable that holds the password of an encrypted key."),
            new Key("management", "section", "no", "", "", false,
                    "The management listener, plain HTTP: health checks and metrics, never an operation."),
            new Key("management.address", "text", "no", ManagementConfig.DEFAULT_ADDRESS, "no white space", false,
                    "The address the management listener listens on."),
            new Key("management.port", "whole number", "no", Integer.toString(ManagementConfig.DEFAULT_PORT),
                    "0 to " + MAX_PORT + "; 0 turns the listener off", false, "The port of the management listener."),
            new Key("reload", "section", "no", "", "", false, "How the gateway looks for new bundles while it runs."),
            new Key("reload.intervalSeconds", "whole number", "no", Integer.toString(GatewayConfig.DEFAULT_RELOAD_INTERVAL_SECONDS),
                    "0 to " + GatewayConfig.MAX_RELOAD_INTERVAL_SECONDS + "; 0 turns reloading off", false,
                    "How often, in seconds, the bundle files and the certificate files are looked at."),
            new Key("tenants", "section", "yes", "", "Community Edition: " + EditionLimits.MAX_TENANTS + " tenant", false,
                    "The tenants, by name. A name has lower-case letters, digits and -, starts with a letter or a digit, and is not health."),
            new Key(TENANT_PATH + ".bundles", "list of file or folder names", "yes", "", "at least one", false,
                    "The bundles of the tenant, relative to the configuration file: a bundle file, or a folder whose *.rpgapi files are served, in name order."),
            new Key(TENANT_PATH + ".environments", "section", "yes", "", "", false,
                    "The environments of the tenant, by name, with the same rule for names as the tenants."),
            new Key(ENVIRONMENT_PATH + ".code", "text", "yes", "", "", false,
                    "The code of the environment, which a bundle can pass to a program as ${environment.code}."),
            new Key(ENVIRONMENT_PATH + ".backend", "mock or ibmi", "yes", "", "", false,
                    "mock answers from the mock definitions of the bundle; ibmi calls the programs on an IBM i."),
            new Key(ENVIRONMENT_PATH + ".host", "text", "with backend: ibmi", "", "a host name or an address, without scheme, path or port", true,
                    "The IBM i that the environment calls."),
            new Key(ENVIRONMENT_PATH + ".libraryList", "list of library names", "with backend: ibmi", "",
                    "1 to " + MAX_LIBRARIES + " names of 1 to 10 characters", true, "The library list of the jobs that run the programs."),
            new Key(ENVIRONMENT_PATH + ".credentials", "section", "with backend: ibmi", "", "", true, "The sign-on of the environment."),
            new Key(ENVIRONMENT_PATH + ".credentials.user", "placeholder", "with backend: ibmi", "", "a single ${NAME}", true,
                    "The environment variable that holds the user."),
            new Key(ENVIRONMENT_PATH + ".credentials.password", "placeholder", "with backend: ibmi", "", "a single ${NAME}", true,
                    "The environment variable that holds the password."),
            new Key(ENVIRONMENT_PATH + ".tls", "true or false", "no", "true", "", true, "Whether the connections to the IBM i use TLS."),
            new Key(ENVIRONMENT_PATH + ".pool", "section", "no", "", "", true, "The connections to the IBM i."),
            new Key(ENVIRONMENT_PATH + ".pool.maxConnections", "whole number", "no", Integer.toString(IbmiSettings.DEFAULT_MAX_CONNECTIONS),
                    "1 to " + MAX_POOL, true, "The most connections open at once to the IBM i."),
            new Key(ENVIRONMENT_PATH + ".callTimeoutSeconds", "whole number", "no", Integer.toString(IbmiSettings.DEFAULT_CALL_TIMEOUT_SECONDS),
                    "1 to " + MAX_CALL_TIMEOUT_SECONDS, true, "How long, in seconds, a call may take before it is given up."));

    private ConfigurationKeys() {
    }

    /** The names of the keys directly under {@code parent}, {@code ""} for the top of the file. */
    public static Set<String> children(String parent) {
        Set<String> names = new LinkedHashSet<>();
        for (Key key : KEYS) {
            if (key.parent().equals(parent)) {
                names.add(key.name());
            }
        }
        return Set.copyOf(names);
    }

    /** The names of the keys of an environment that are accepted only with {@code backend: ibmi}. */
    public static Set<String> ibmiOnly() {
        Set<String> names = new LinkedHashSet<>();
        for (Key key : KEYS) {
            if (key.parent().equals(ENVIRONMENT_PATH) && key.ibmiOnly()) {
                names.add(key.name());
            }
        }
        return Set.copyOf(names);
    }
}
