package ai.generativearchitects.slay400.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.slay400.gateway.config.ConfigurationKeys;
import ai.generativearchitects.slay400.gateway.load.GatewayLoader;
import ai.generativearchitects.slay400.gateway.load.StartOptions;
import ai.generativearchitects.slay400.gateway.load.StartResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

/**
 * The complete example of the configuration reference (gateway kickoff 09): it holds every key of {@link ConfigurationKeys},
 * and the gateway starts with it, its certificate, its bundle folder and its environment variables in place. The ibmi
 * environment is opened by a fake backend: no connection is made.
 */
class ConfigurationExampleTest {

    static final Path EXAMPLE = Path.of("..", "docs", "examples", "reference", "config.yaml");

    @TempDir
    Path dir;

    @Test
    void theExampleHoldsEveryKeyAndNoOther() throws IOException {
        Object root = new Load(LoadSettings.builder().build()).loadFromString(Files.readString(EXAMPLE));
        Set<String> found = new TreeSet<>();
        paths(root, "", found);
        Set<String> keys = new TreeSet<>();
        ConfigurationKeys.KEYS.forEach(key -> keys.add(key.path()));
        assertThat(found).isEqualTo(keys);
    }

    @Test
    void theGatewayStartsWithTheExample() throws IOException {
        Path config = dir.resolve("config.yaml");
        Files.copy(EXAMPLE, config);
        Fixtures.bundle(Files.createDirectories(dir.resolve("bundles")), "shop");
        char[] password = "example-key-password".toCharArray();
        TestCertificates.Pair pair = TestCertificates.rsa(dir.resolve("tls"), "tls", 365);
        pair.encryptedKey(dir.resolve("tls/tls.key"), password);
        Map<String, String> variables = Map.of("TLS_KEY_PASSWORD", new String(password), "IBMI_HOST", "your-system.invalid",
                "IBMI_USER", "SOMEONE", "IBMI_PASSWORD", "PW-MARKER");
        BackendStartTest.FakeBackend backend = new BackendStartTest.FakeBackend();

        StartResult result = GatewayLoader.load(config, variables::get, line -> { }, new StartOptions(List.of(backend), false));

        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.started()).isPresent();
        assertThat(backend.calls).contains("start acme/prod 2 false");
        result.started().get().close();
    }

    /** The paths of the keys of the file, with tenant and environment names replaced by their segment of the catalogue. */
    private static void paths(Object node, String prefix, Set<String> found) {
        if (!(node instanceof Map<?, ?> map)) {
            return;
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String name = entry.getKey().toString();
            if (prefix.equals("tenants")) {
                name = ConfigurationKeys.TENANT;
            } else if (prefix.endsWith(".environments")) {
                name = ConfigurationKeys.ENVIRONMENT;
            }
            String path = prefix.isEmpty() ? name : prefix + "." + name;
            boolean named = name.equals(ConfigurationKeys.TENANT) || name.equals(ConfigurationKeys.ENVIRONMENT);
            if (!named) {
                found.add(path);
            }
            paths(entry.getValue(), path, found);
        }
    }
}
