package ai.generativearchitects.slay400.gateway.load;

import ai.generativearchitects.slay400.bundle.Bundle;
import ai.generativearchitects.slay400.bundle.BundleReadResult;
import ai.generativearchitects.slay400.bundle.BundleReader;
import ai.generativearchitects.slay400.gateway.call.MockCaller;
import ai.generativearchitects.slay400.gateway.call.ProgramCaller;
import ai.generativearchitects.slay400.gateway.config.ConfigReader;
import ai.generativearchitects.slay400.gateway.config.ConfigResult;
import ai.generativearchitects.slay400.gateway.config.EnvironmentConfig;
import ai.generativearchitects.slay400.gateway.config.GatewayConfig;
import ai.generativearchitects.slay400.gateway.config.TenantConfig;
import ai.generativearchitects.slay400.gateway.pipeline.CompiledOperation;
import ai.generativearchitects.slay400.gateway.pipeline.EnvironmentRuntime;
import ai.generativearchitects.slay400.gateway.pipeline.Gateway;
import ai.generativearchitects.slay400.gateway.pipeline.TenantRuntime;
import ai.generativearchitects.slay400.http.HttpBinding;
import ai.generativearchitects.slay400.http.ReadLimits;
import ai.generativearchitects.slay400.http.RouteTable;
import ai.generativearchitects.slay400.mock.MockProgram;
import ai.generativearchitects.slay400.mock.MockReadResult;
import ai.generativearchitects.slay400.mock.MockReader;
import ai.generativearchitects.slay400.pcml.api.ApiDefinition;
import ai.generativearchitects.slay400.pcml.api.ApiOperation;
import ai.generativearchitects.slay400.pcml.api.ApiRepositoryModel;
import ai.generativearchitects.slay400.pcml.api.Direction;
import ai.generativearchitects.slay400.pcml.api.FieldBinding;
import ai.generativearchitects.slay400.pcml.api.MockDefinition;
import ai.generativearchitects.slay400.pcml.codec.ConfigurationIssue;
import ai.generativearchitects.slay400.pcml.codec.OperationCodec;
import ai.generativearchitects.slay400.pcml.codec.ValueProblem;
import ai.generativearchitects.slay400.pcml.diagnostics.Diagnostic;
import ai.generativearchitects.slay400.pcml.diagnostics.Severity;
import ai.generativearchitects.slay400.pcml.model.SourceLocation;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The start, in the order of the brief, stopping at the first step that finds an error:
 *
 * <ol>
 *   <li>read and check the configuration ({@code GWY001} to {@code GWY008});</li>
 *   <li>read every bundle ({@code GWY010} when a bundle gives nothing, and the diagnostics of the reader);</li>
 *   <li>for every operation, {@code OperationCodec.check} must be empty ({@code GWY011}) and every injected parameter must
 *       have a value in the environment's configuration that fits its field ({@code GWY012});</li>
 *   <li>one route table per tenant, where the same method and route twice is an error ({@code GWY013}); every mock must be
 *       valid ({@code GWY014}).</li>
 * </ol>
 *
 * <p>Nothing is thrown for the content of a file, and no message contains the value of an environment variable.
 */
public final class GatewayLoader {

    private static final String EXPRESSION_START = "${";
    private static final String ENVIRONMENT_CODE = "${environment.code}";

    private GatewayLoader() {
    }

    /**
     * Starts the gateway from a configuration file.
     *
     * @param configFile  the configuration file
     * @param environment the value of an environment variable, or {@code null} when it is not set
     * @param accessLog   receives the JSON line of every request
     */
    public static StartResult load(Path configFile, Function<String, String> environment, Consumer<String> accessLog) {
        ConfigResult read = ConfigReader.read(configFile, environment);
        if (read.config().isEmpty()) {
            return new StartResult(Optional.empty(), read.diagnostics());
        }
        GatewayConfig config = read.config().get();
        List<Diagnostic> found = new ArrayList<>();
        Map<String, TenantRuntime> tenants = new LinkedHashMap<>();
        int environments = 0;
        int operations = 0;
        ReadLimits limits = ReadLimits.defaults();
        for (TenantConfig tenant : config.tenants()) {
            Optional<TenantRuntime> built = tenant(tenant, limits, found);
            if (built.isPresent()) {
                tenants.put(tenant.name(), built.get());
                environments += tenant.environments().size();
                operations += built.get().operations().size();
            }
        }
        if (found.stream().anyMatch(d -> d.severity() == Severity.ERROR)) {
            return new StartResult(Optional.empty(), found);
        }
        Gateway gateway = new Gateway(tenants, limits, accessLog);
        return new StartResult(Optional.of(new Started(config, gateway, environments, operations)), found);
    }

    private static Optional<TenantRuntime> tenant(TenantConfig tenant, ReadLimits limits, List<Diagnostic> found) {
        int errorsBefore = errors(found);
        // Step 2: bundles.
        List<Bundle> bundles = new ArrayList<>();
        for (int i = 0; i < tenant.bundles().size(); i++) {
            Path path = tenant.bundles().get(i);
            BundleReadResult result = BundleReader.read(path);
            found.addAll(result.diagnostics());
            if (result.bundle().isPresent()) {
                bundles.add(result.bundle().get());
            } else if (result.diagnostics().stream().noneMatch(d -> d.severity() == Severity.ERROR)) {
                found.add(error("GWY010", "The bundle could not be read", tenant.bundleSource().get(i)));
            }
        }
        if (errors(found) > errorsBefore) {
            return Optional.empty();
        }
        // Step 3: every operation can be encoded, and mocks are valid.
        List<ApiOperation> allOperations = new ArrayList<>();
        List<ApiDefinition> allApis = new ArrayList<>();
        Map<ApiOperation, CompiledOperation> compiled = new IdentityHashMap<>();
        Map<ApiOperation, MockProgram> mocks = new IdentityHashMap<>();
        for (Bundle bundle : bundles) {
            for (ApiDefinition api : bundle.model().apis()) {
                allApis.add(api);
                Map<String, MockDefinition> definitions = mockDefinitions(bundle, api, found);
                for (ApiOperation operation : api.operations()) {
                    allOperations.add(operation);
                    List<ConfigurationIssue> issues = OperationCodec.check(operation);
                    for (ConfigurationIssue issue : issues) {
                        found.add(error("GWY011", "The operation " + operation.id() + " cannot be served: " + issue.fieldPath() + ": " + issue.message(),
                                operation.source()));
                    }
                    if (issues.isEmpty()) {
                        compiled.put(operation, new CompiledOperation(operation, HttpBinding.of(operation, limits), OperationCodec.of(operation)));
                        try {
                            MockDefinition definition = definitions.get(operation.id());
                            mocks.put(operation, definition == null ? MockProgram.of(operation) : MockProgram.of(operation, definition));
                        } catch (IllegalArgumentException e) {
                            found.add(error("GWY014", e.getMessage(), operation.source()));
                        }
                    }
                }
            }
        }
        // Step 4: routes.
        Map<String, ApiOperation> routes = new HashMap<>();
        for (ApiOperation operation : allOperations) {
            String key = operation.method() + " " + operation.route().replaceAll("\\{[^}]*}", "{}");
            ApiOperation other = routes.putIfAbsent(key, operation);
            if (other != null && other != operation) {
                found.add(error("GWY013", "The route " + operation.method() + " " + operation.route() + " is served by two operations of tenant "
                        + tenant.name() + ": " + other.id() + " and " + operation.id(), operation.source()));
            }
        }
        Map<String, EnvironmentRuntime> environments = new LinkedHashMap<>();
        ProgramCaller mock = new MockCaller(mocks);
        for (EnvironmentConfig environment : tenant.environments()) {
            Map<ApiOperation, Map<String, String>> injected = new IdentityHashMap<>();
            for (ApiOperation operation : allOperations) {
                injected.put(operation, injected(operation, environment, compiled.get(operation), found));
            }
            ProgramCaller caller = switch (environment.backend()) {
                case MOCK -> mock;
            };
            environments.put(environment.name(), new EnvironmentRuntime(environment.name(), caller, injected));
        }
        if (errors(found) > errorsBefore) {
            return Optional.empty();
        }
        ApiRepositoryModel model = new ApiRepositoryModel(bundles.get(0).model().defaults(), allOperations, allApis);
        return Optional.of(new TenantRuntime(tenant.name(), RouteTable.of(model), compiled, environments));
    }

    private static int errors(List<Diagnostic> found) {
        return (int) found.stream().filter(d -> d.severity() == Severity.ERROR).count();
    }

    private static Map<String, MockDefinition> mockDefinitions(Bundle bundle, ApiDefinition api, List<Diagnostic> found) {
        Map<String, MockDefinition> definitions = new HashMap<>();
        Optional<byte[]> file = bundle.file("mock/" + api.name() + ".json");
        if (file.isPresent()) {
            MockReadResult result = MockReader.read(new ByteArrayInputStream(file.get()), "mock/" + api.name() + ".json");
            found.addAll(result.diagnostics());
            result.file().ifPresent(mockFile -> mockFile.operations().forEach(d -> definitions.put(d.operationId(), d)));
        }
        return definitions;
    }

    /** The text of every injected parameter of an operation in an environment; an error for each that has no usable value. */
    private static Map<String, String> injected(ApiOperation operation, EnvironmentConfig environment, CompiledOperation compiled, List<Diagnostic> found) {
        Map<String, String> values = new LinkedHashMap<>();
        boolean unresolved = false;
        List<FieldBinding> injectedBindings = operation.bindings().stream().filter(b -> b.direction() == Direction.INJECTED && b.injection().isPresent()).toList();
        for (FieldBinding binding : injectedBindings) {
            String text = binding.injection().get();
            String value;
            if (text.equals(ENVIRONMENT_CODE)) {
                value = environment.code();
            } else if (text.contains(EXPRESSION_START)) {
                found.add(error("GWY012", "The environment " + environment.name() + " has no value for " + text + ", which the operation "
                        + operation.id() + " injects into " + binding.fieldPath() + "; only ${environment.code} is available in this version", environment.at()));
                unresolved = true;
                continue;
            } else {
                value = text;
            }
            values.put(binding.field().name(), value);
        }
        if (compiled != null && !unresolved && !injectedBindings.isEmpty()) {
            for (ValueProblem problem : compiled.codec().encode(Map.of(), values).problems()) {
                boolean injectedField = injectedBindings.stream().anyMatch(b -> b.fieldPath().equals(problem.fieldPath()));
                if (injectedField) {
                    found.add(error("GWY012", "The injected value of " + problem.fieldPath() + " in the environment " + environment.name()
                            + " does not fit its field (" + problem.code() + "), for the operation " + operation.id(), environment.at()));
                }
            }
        }
        return values;
    }

    private static Diagnostic error(String code, String message, SourceLocation at) {
        return new Diagnostic(code, Severity.ERROR, message, at);
    }
}
