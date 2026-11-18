package ai.generativearchitects.slay400.gateway.ibmi;

import ai.generativearchitects.slay400.bundle.BundleReader;
import ai.generativearchitects.slay400.gateway.Fixtures;
import ai.generativearchitects.slay400.pcml.api.ApiOperation;
import ai.generativearchitects.slay400.tasks.BundleRequest;
import ai.generativearchitects.slay400.tasks.TaskResult;
import ai.generativearchitects.slay400.tasks.Tasks;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The operations of the invented {@code ibmi} fixture repository, and of small repositories written by a test. */
final class IbmiFixtures {

    private IbmiFixtures() {
    }

    /** The operations of {@code fixtures/ibmi}, by id, built into a bundle in {@code dir}. */
    static Map<String, ApiOperation> operations(Path dir) {
        Path bundle = Fixtures.bundle(dir, "ibmi");
        Map<String, ApiOperation> operations = new LinkedHashMap<>();
        BundleReader.read(bundle).bundle().orElseThrow().model().operations().forEach(op -> operations.put(op.id(), op));
        return operations;
    }

    /** The one operation of a repository with one program, written into {@code dir}; empty if it does not build. */
    static Optional<ApiOperation> operation(Path dir, String program, String pcml, String overlay) {
        Path repository = dir.resolve("repo-" + program);
        Fixtures.write(repository, "apis/" + program + "/" + program + ".pcml", pcml);
        Fixtures.write(repository, "apis/" + program + "/api.yaml", overlay);
        Path target = dir.resolve(program + ".rpgapi");
        TaskResult result = new Tasks(dir).bundle(repository, new BundleRequest(program.toLowerCase(), "1.0.0", target, Optional.empty(), Optional.empty()));
        if (result.hasError()) {
            return Optional.empty();
        }
        List<ApiOperation> operations = BundleReader.read(target).bundle().orElseThrow().model().operations();
        return operations.isEmpty() ? Optional.empty() : Optional.of(operations.get(0));
    }

    /** A PCML document with one program. */
    static String pcml(String program, String path, String parameters, String structs) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<pcml version=\"6.0\">\n" + structs + "  <program name=\"" + program + "\"" + path + ">\n" + parameters
                + "  </program>\n</pcml>\n";
    }
}
