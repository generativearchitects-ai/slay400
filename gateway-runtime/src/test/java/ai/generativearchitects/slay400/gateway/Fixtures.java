package ai.generativearchitects.slay400.gateway;

import ai.generativearchitects.slay400.gateway.load.GatewayLoader;
import ai.generativearchitects.slay400.gateway.load.StartResult;
import ai.generativearchitects.slay400.tasks.BundleRequest;
import ai.generativearchitects.slay400.tasks.TaskResult;
import ai.generativearchitects.slay400.tasks.Tasks;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/** Test helpers: bundles built from the invented fixture repositories under {@code fixtures/}, and configuration files. */
public final class Fixtures {

    /** A valid configuration for the {@code shop} bundle, with the placeholders the tests do not use left out. */
    public static final String SHOP_CONFIG = """
            tenants:
              acme:
                bundles:
                  - ./shop-1.0.0.rpgapi
                environments:
                  test:
                    code: T0001
                    backend: mock
                  prod:
                    code: P0001
                    backend: mock
            """;

    private Fixtures() {
    }

    /** The directory of the fixture repositories. */
    public static Path root() {
        return Path.of("..", "fixtures").toAbsolutePath().normalize();
    }

    /** Builds the bundle of a fixture repository into {@code dir} and returns its file. */
    public static Path bundle(Path dir, String repository) {
        Path target = dir.resolve(repository + "-1.0.0.rpgapi");
        TaskResult result = new Tasks(dir).bundle(root().resolve(repository),
                new BundleRequest(repository, "1.0.0", target, Optional.empty(), Optional.empty()));
        if (result.hasError()) {
            throw new IllegalStateException("fixture " + repository + ": " + result.diagnostics());
        }
        return target;
    }

    /**
     * Builds the bundle of a copy of the {@code shop} repository in which {@code from} is replaced by {@code to} in the overlays, into {@code dir}, as {@code <name>-1.0.0.rpgapi}; the result is the build result, errors included.
     */
    public static TaskResult variant(Path dir, String name, String from, String to) {
        try {
            Path copy = dir.resolve("src-" + name);
            try (var files = Files.walk(root().resolve("shop"))) {
                for (Path source : (Iterable<Path>) files::iterator) {
                    Path target = copy.resolve(root().resolve("shop").relativize(source).toString());
                    if (Files.isDirectory(source)) {
                        Files.createDirectories(target);
                    } else {
                        Files.copy(source, target);
                    }
                }
            }
            boolean found = false;
            for (String api : List.of("ORDSTATUS", "ITEMQTY")) {
                Path overlay = copy.resolve("apis/" + api + "/api.yaml");
                String text = Files.readString(overlay);
                found |= text.contains(from);
                Files.writeString(overlay, text.replace(from, to));
            }
            if (!found) {
                throw new IllegalArgumentException("not in an overlay: " + from);
            }
            return new Tasks(dir).bundle(copy, new BundleRequest(name, "1.0.0", dir.resolve(name + "-1.0.0.rpgapi"), Optional.empty(), Optional.empty()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Writes a file. */
    public static Path write(Path dir, String name, String content) {
        try {
            Path file = dir.resolve(name);
            Files.createDirectories(file.getParent());
            Files.writeString(file, content);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Builds the {@code shop} bundle, writes the configuration and starts. */
    public static StartResult start(Path dir, String config, Map<String, String> environment, List<String> log) {
        bundle(dir, "shop");
        Path file = write(dir, "gateway.yaml", config);
        Function<String, String> variables = environment::get;
        return GatewayLoader.load(file, variables, log::add);
    }

    /** Starts the {@code shop} gateway with {@link #SHOP_CONFIG}. */
    public static StartResult start(Path dir, List<String> log) {
        return start(dir, SHOP_CONFIG, Map.of(), log);
    }
}
