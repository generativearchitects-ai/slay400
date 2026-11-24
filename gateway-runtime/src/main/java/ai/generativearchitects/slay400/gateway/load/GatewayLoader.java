package ai.generativearchitects.slay400.gateway.load;

import ai.generativearchitects.slay400.bundle.Bundle;
import ai.generativearchitects.slay400.bundle.BundleReadResult;
import ai.generativearchitects.slay400.bundle.BundleReader;
import ai.generativearchitects.slay400.gateway.call.BackendProvider;
import ai.generativearchitects.slay400.gateway.call.BackendStart;
import ai.generativearchitects.slay400.gateway.call.OpenBackend;
import ai.generativearchitects.slay400.gateway.call.MockCaller;
import ai.generativearchitects.slay400.gateway.call.ProgramCaller;
import ai.generativearchitects.slay400.gateway.config.ConfigReader;
import ai.generativearchitects.slay400.gateway.config.Backend;
import ai.generativearchitects.slay400.gateway.config.ConfigResult;
import ai.generativearchitects.slay400.gateway.config.EnvironmentConfig;
import ai.generativearchitects.slay400.gateway.config.GatewayConfig;
import ai.generativearchitects.slay400.gateway.config.TenantConfig;
import ai.generativearchitects.slay400.gateway.pipeline.CompiledOperation;
import ai.generativearchitects.slay400.gateway.pipeline.EnvironmentRuntime;
import ai.generativearchitects.slay400.gateway.pipeline.Gateway;
import ai.generativearchitects.slay400.gateway.pipeline.ServedSet;
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
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
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
 *   <li>list the bundle entries: a folder stands for the bundle files directly in it, and must hold one ({@code GWY019}); copy
 *       every file once, privately, up to a bound ({@code GWY021} for a file over it, or one that cannot be read or changes
 *       while it is copied);</li>
 *   <li>read every bundle ({@code GWY010} when a bundle gives nothing, and the diagnostics of the reader);</li>
 *   <li>for every operation, {@code OperationCodec.check} must be empty ({@code GWY011}) and every injected parameter must
 *       have a value in the environment's configuration that fits its field ({@code GWY012});</li>
 *   <li>one route table per tenant, where the same method and route twice is an error ({@code GWY013}); every mock must be
 *       valid ({@code GWY014});</li>
 *   <li>every environment's backend is available in this distribution ({@code GWY016}) and can call every operation of its
 *       tenant ({@code GWY017});</li>
 *   <li>only then, every backend other than the mock is opened, with its connection check unless it is turned off
 *       ({@code GWY018}). When one cannot be opened, those already opened are closed again.</li>
 * </ol>
 *
 * <p>Steps 2 to 6 are also what a reload does ({@link Reloader}): a new set of bundles is compiled beside the running one with
 * every check of a start, and served over the backends opened at start. At a reload a folder may be empty, but a tenant must
 * keep at least one bundle ({@code GWY020}).
 *
 * <p>Nothing is thrown for the content of a file, and no message contains the value of an environment variable.
 */
public final class GatewayLoader {

    private static final String EXPRESSION_START = "${";
    private static final String ENVIRONMENT_CODE = "${environment.code}";

    private GatewayLoader() {
    }

    /**
     * Starts the gateway from a configuration file, with the mock backend only.
     *
     * @param configFile  the configuration file
     * @param environment the value of an environment variable, or {@code null} when it is not set
     * @param accessLog   receives the JSON line of every request
     */
    public static StartResult load(Path configFile, Function<String, String> environment, Consumer<String> accessLog) {
        return load(configFile, environment, accessLog, StartOptions.mockOnly());
    }

    /**
     * Starts the gateway from a configuration file. The bundles are not polled until {@link Started#startReloading()}.
     *
     * @param configFile  the configuration file
     * @param environment the value of an environment variable, or {@code null} when it is not set
     * @param accessLog   receives the JSON line of every request
     * @param options     the backends available, whether they are checked by connecting, and how bundles are reloaded
     */
    public static StartResult load(Path configFile, Function<String, String> environment, Consumer<String> accessLog, StartOptions options) {
        ConfigResult read = ConfigReader.read(configFile, environment);
        if (read.config().isEmpty()) {
            return new StartResult(Optional.empty(), read.diagnostics());
        }
        GatewayConfig config = read.config().get();
        Map<Backend, BackendProvider> providers = new EnumMap<>(Backend.class);
        options.providers().forEach(provider -> providers.put(provider.backend(), provider));
        List<Diagnostic> found = new ArrayList<>();
        // Step 2: the entries, folders listed, every file copied once.
        Map<String, List<BundleListing.Entry>> entries = entries(config, options.files());
        BundleListing listing;
        try {
            listing = BundleListing.of(entries, options.files());
        } catch (IOException e) {
            found.add(error("GWY019", "A bundle folder or file cannot be read (" + e.getClass().getSimpleName() + ")", config.tenants().get(0).at()));
            return new StartResult(Optional.empty(), found);
        }
        entries.forEach((tenant, tenantEntries) -> {
            for (BundleListing.Entry entry : tenantEntries) {
                if (entry.folder() && listing.tenants().get(tenant).stream().noneMatch(listed -> listed.entry().equals(entry))) {
                    found.add(error("GWY019", "The folder " + entry.path().getFileName() + " has no bundle file (*" + BundleFiles.EXTENSION + ")", entry.at()));
                }
            }
        });
        if (errors(found) > 0) {
            return new StartResult(Optional.empty(), found);
        }
        ReadLimits limits = ReadLimits.defaults();
        Compiled compiled;
        Map<String, List<String>> content;
        try (BundleSnapshot snapshot = BundleSnapshot.take(listing, options.files())) {
            if (!snapshot.stable()) {
                found.add(error("GWY021", "A bundle file changed while it was read; start again when it is complete", config.tenants().get(0).at()));
                return new StartResult(Optional.empty(), found);
            }
            compiled = compile(config, listing, snapshot, providers, limits, false);
            content = snapshot.content(listing);
        } catch (IOException e) {
            found.add(error("GWY021", "A bundle file cannot be read (" + e.getClass().getSimpleName() + ")", config.tenants().get(0).at()));
            return new StartResult(Optional.empty(), found);
        }
        found.addAll(compiled.diagnostics());
        if (errors(found) > 0) {
            return new StartResult(Optional.empty(), found);
        }
        // Step 7: open the backends, now that nothing else stops the start. They are kept for as long as the gateway runs.
        Map<String, OpenBackend> backends = new LinkedHashMap<>();
        for (TenantDraft draft : compiled.drafts()) {
            for (EnvironmentDraft environmentDraft : draft.environments()) {
                EnvironmentConfig environmentConfig = environmentDraft.config();
                if (environmentConfig.backend() == Backend.MOCK) {
                    continue;
                }
                BackendStart start = providers.get(environmentConfig.backend()).start(draft.name(), environmentConfig, draft.operations(), options.connectCheck());
                if (start.backend().isEmpty()) {
                    start.problems().forEach(problem -> found.add(error("GWY018", "The backend " + backendName(environmentConfig.backend())
                            + " of the environment " + environmentConfig.name() + " cannot be opened: " + problem, environmentConfig.at())));
                    backends.values().forEach(OpenBackend::close);
                    return new StartResult(Optional.empty(), found);
                }
                backends.put(key(draft.name(), environmentConfig.name()), start.backend().get());
            }
        }
        ServedSet served = assemble(compiled.drafts(), backends);
        int environments = served.tenants().values().stream().mapToInt(t -> t.environments().size()).sum();
        Gateway gateway = new Gateway(served, limits, accessLog);
        Reloader reloader = new Reloader(config, entries, providers, backends, gateway, limits, options, listing, content);
        return new StartResult(Optional.of(new Started(config, gateway, environments, served.operationCount(), List.copyOf(backends.values()), reloader)), found);
    }

    /** What compiling a set of bundles came to: the tenants, before they are served, and every diagnostic. */
    record Compiled(List<TenantDraft> drafts, List<Diagnostic> diagnostics) {
    }

    /** The entries of every tenant: each is a folder if it is one now, otherwise a file. */
    static Map<String, List<BundleListing.Entry>> entries(GatewayConfig config, BundleFiles files) {
        Map<String, List<BundleListing.Entry>> entries = new LinkedHashMap<>();
        for (TenantConfig tenant : config.tenants()) {
            List<BundleListing.Entry> list = new ArrayList<>();
            for (int i = 0; i < tenant.bundles().size(); i++) {
                Path path = tenant.bundles().get(i);
                list.add(new BundleListing.Entry(path, files.isFolder(path), tenant.bundleSource().get(i)));
            }
            entries.put(tenant.name(), List.copyOf(list));
        }
        return entries;
    }

    /**
     * Steps 3 to 6 for every tenant: reads the copies of the snapshot and checks everything a start checks, without opening a
     * backend.
     *
     * @param reload whether this is a reload, where a tenant without a bundle is an error of its own ({@code GWY020})
     */
    static Compiled compile(GatewayConfig config, BundleListing listing, BundleSnapshot snapshot, Map<Backend, BackendProvider> providers, ReadLimits limits,
                            boolean reload) {
        List<Diagnostic> found = new ArrayList<>();
        List<TenantDraft> drafts = new ArrayList<>();
        for (TenantConfig tenant : config.tenants()) {
            List<BundleListing.Listed> files = listing.tenants().getOrDefault(tenant.name(), List.of());
            if (reload && files.isEmpty()) {
                found.add(error("GWY020", "The tenant " + tenant.name() + " has no bundle file left; a tenant serves at least one bundle", tenant.at()));
                continue;
            }
            tenant(tenant, files, snapshot, limits, providers, found).ifPresent(drafts::add);
        }
        return new Compiled(List.copyOf(drafts), List.copyOf(found));
    }

    /** The set of compiled tenants, each environment with a caller: the mock of the set, or one over the environment's open backend. */
    static ServedSet assemble(List<TenantDraft> drafts, Map<String, OpenBackend> backends) {
        Map<String, TenantRuntime> tenants = new LinkedHashMap<>();
        List<ServedSet.Bundle> bundles = new ArrayList<>();
        for (TenantDraft draft : drafts) {
            Map<String, EnvironmentRuntime> runtimes = new LinkedHashMap<>();
            for (EnvironmentDraft environmentDraft : draft.environments()) {
                EnvironmentConfig environmentConfig = environmentDraft.config();
                ProgramCaller caller = environmentConfig.backend() == Backend.MOCK ? draft.mock()
                        : backends.get(key(draft.name(), environmentConfig.name())).callerFor(draft.operations());
                runtimes.put(environmentConfig.name(), new EnvironmentRuntime(environmentConfig.name(), caller, environmentDraft.injected()));
            }
            tenants.put(draft.name(), new TenantRuntime(draft.name(), draft.routes(), draft.compiled(), runtimes));
            bundles.addAll(draft.bundles());
        }
        return new ServedSet(tenants, bundles);
    }

    private static String key(String tenant, String environment) {
        return tenant + "/" + environment;
    }

    /** A tenant that passed the checks, before its backends are opened. */
    record TenantDraft(String name, RouteTable routes, Map<ApiOperation, CompiledOperation> compiled, List<ApiOperation> operations,
                       ProgramCaller mock, List<EnvironmentDraft> environments, List<ServedSet.Bundle> bundles) {
    }

    /** An environment that passed the checks, with the values it injects. */
    record EnvironmentDraft(EnvironmentConfig config, Map<ApiOperation, Map<String, String>> injected) {
    }

    private static String backendName(Backend backend) {
        return backend.name().toLowerCase(java.util.Locale.ROOT);
    }

    private static Optional<TenantDraft> tenant(TenantConfig tenant, List<BundleListing.Listed> files, BundleSnapshot snapshot, ReadLimits limits,
                                                Map<Backend, BackendProvider> providers, List<Diagnostic> found) {
        int errorsBefore = errors(found);
        // Step 3: bundles, each read from its private copy; a file entry that names no file is read where it is, for the
        // reader's own diagnostic.
        List<Bundle> bundles = new ArrayList<>();
        for (BundleListing.Listed listed : files) {
            if (snapshot.tooLarge(listed.path())) {
                found.add(error("GWY021", "The bundle file " + listed.path().getFileName() + " is larger than " + snapshot.maxFileBytes()
                        + " bytes, more than any bundle can be; it is not read", listed.entry().at()));
                continue;
            }
            Optional<Path> copy = snapshot.copyOf(listed.path());
            BundleReadResult result = BundleReader.read(copy.orElse(listed.path()));
            found.addAll(copy.isPresent() ? named(result.diagnostics(), copy.get(), listed.path()) : result.diagnostics());
            if (result.bundle().isPresent()) {
                bundles.add(result.bundle().get());
            } else if (result.diagnostics().stream().noneMatch(d -> d.severity() == Severity.ERROR)) {
                found.add(error("GWY010", "The bundle could not be read", listed.entry().at()));
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
        List<ServedSet.Bundle> served = new ArrayList<>();
        for (Bundle bundle : bundles) {
            served.add(new ServedSet.Bundle(tenant.name(), bundle.manifest().name(), bundle.manifest().version()));
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
                        // The limit of the bundle is the limit of the call (kickoff 06).
                        int maxResponseValues = bundle.model().defaults().maxResponseValues();
                        compiled.put(operation, new CompiledOperation(operation, HttpBinding.of(operation, limits), OperationCodec.of(operation, maxResponseValues),
                                maxResponseValues, bundle.manifest().name(), bundle.manifest().version()));
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
        List<EnvironmentDraft> environments = new ArrayList<>();
        Map<Backend, Boolean> checked = new EnumMap<>(Backend.class);
        for (EnvironmentConfig environment : tenant.environments()) {
            Map<ApiOperation, Map<String, String>> injected = new IdentityHashMap<>();
            for (ApiOperation operation : allOperations) {
                injected.put(operation, injected(operation, environment, compiled.get(operation), found));
            }
            if (environment.backend() != Backend.MOCK) {
                BackendProvider provider = providers.get(environment.backend());
                if (provider == null) {
                    found.add(error("GWY016", "The backend " + backendName(environment.backend()) + " of the environment " + environment.name()
                            + " is not available in this distribution", environment.at()));
                } else if (checked.putIfAbsent(environment.backend(), true) == null) {
                    // Once per backend and tenant: the operations are the same for every environment of the tenant.
                    for (ApiOperation operation : allOperations) {
                        for (String reason : provider.unsupported(operation)) {
                            found.add(error("GWY017", "The operation " + operation.id() + " cannot be called by the backend " + backendName(environment.backend())
                                    + ": " + reason, operation.source()));
                        }
                    }
                }
            }
            environments.add(new EnvironmentDraft(environment, injected));
        }
        if (errors(found) > errorsBefore) {
            return Optional.empty();
        }
        ApiRepositoryModel model = new ApiRepositoryModel(bundles.get(0).model().defaults(), allOperations, allApis);
        return Optional.of(new TenantDraft(tenant.name(), RouteTable.of(model), compiled, allOperations, new MockCaller(mocks), environments, served));
    }

    /** The diagnostics of a private copy, naming the bundle file the copy was made from. */
    private static List<Diagnostic> named(List<Diagnostic> diagnostics, Path copy, Path original) {
        String from = copy.toString();
        List<Diagnostic> named = new ArrayList<>();
        for (Diagnostic diagnostic : diagnostics) {
            SourceLocation at = diagnostic.location();
            String message = diagnostic.message().replace(from, original.toString());
            if (at != null && at.file() != null && at.file().startsWith(from)) {
                at = new SourceLocation(original + at.file().substring(from.length()), at.line(), at.column());
            }
            named.add(new Diagnostic(diagnostic.code(), diagnostic.severity(), message, at));
        }
        return named;
    }

    static int errors(List<Diagnostic> found) {
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

    static Diagnostic error(String code, String message, SourceLocation at) {
        return new Diagnostic(code, Severity.ERROR, message, at);
    }
}
