package ai.generativearchitects.slay400.gateway.config;

import ai.generativearchitects.slay400.pcml.diagnostics.Diagnostic;
import ai.generativearchitects.slay400.pcml.diagnostics.Severity;
import ai.generativearchitects.slay400.pcml.model.SourceLocation;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.api.lowlevel.Compose;
import org.snakeyaml.engine.v2.exceptions.Mark;
import org.snakeyaml.engine.v2.exceptions.MarkedYamlEngineException;
import org.snakeyaml.engine.v2.exceptions.YamlEngineException;
import org.snakeyaml.engine.v2.nodes.MappingNode;
import org.snakeyaml.engine.v2.nodes.Node;
import org.snakeyaml.engine.v2.nodes.NodeTuple;
import org.snakeyaml.engine.v2.nodes.ScalarNode;
import org.snakeyaml.engine.v2.nodes.SequenceNode;
import org.snakeyaml.engine.v2.schema.CoreSchema;

/**
 * Reads and checks the configuration file. The YAML library and its safety limits are those of {@code slay400-core}: no
 * duplicate keys, no recursive or non-scalar keys, at most 50 aliases, only the core tags. Every problem is a diagnostic with
 * the position in the file; nothing is thrown for the content of the file, and no value of an environment variable is ever
 * put in a message.
 *
 * <p>Diagnostic codes: {@code GWY001} the file cannot be read or is not YAML for this tool; {@code GWY002} unknown key;
 * {@code GWY003} wrong type; {@code GWY004} empty section or value; {@code GWY005} missing key; {@code GWY006} invalid value;
 * {@code GWY007} environment variable not set; {@code GWY008} edition limit ({@link EditionLimits}).
 */
public final class ConfigReader {

    /** The largest configuration file read, in bytes. */
    public static final int MAX_BYTES = 1024 * 1024;
    private static final int MAX_DEPTH = 16;
    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9-]*");
    private static final Pattern PORT = Pattern.compile("[0-9]{1,5}");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");
    private static final String RESERVED = "health";

    private final Path file;
    private final String shown;
    private final Function<String, String> environment;
    private final List<Diagnostic> problems = new ArrayList<>();

    private ConfigReader(Path file, Function<String, String> environment) {
        this.file = file;
        this.shown = file.toString();
        this.environment = environment;
    }

    /**
     * Reads the configuration.
     *
     * @param file        the configuration file
     * @param environment the value of an environment variable, or {@code null} when it is not set
     */
    public static ConfigResult read(Path file, Function<String, String> environment) {
        ConfigReader reader = new ConfigReader(file, environment);
        Optional<GatewayConfig> config = reader.run();
        if (config.isPresent() && reader.problems.isEmpty()) {
            List<Diagnostic> limits = EditionLimits.check(config.get());
            if (!limits.isEmpty()) {
                return new ConfigResult(Optional.empty(), limits);
            }
            return new ConfigResult(config, List.of());
        }
        return new ConfigResult(Optional.empty(), reader.problems);
    }

    private Optional<GatewayConfig> run() {
        String text;
        try {
            byte[] bytes = readLimited();
            if (bytes == null) {
                error("GWY001", "The configuration file is larger than " + MAX_BYTES + " bytes", here());
                return Optional.empty();
            }
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            error("GWY001", "The configuration file is not valid UTF-8", here());
            return Optional.empty();
        } catch (IOException e) {
            error("GWY001", "The configuration file cannot be read", here());
            return Optional.empty();
        }
        Optional<Node> root;
        try {
            LoadSettings settings = LoadSettings.builder().setAllowDuplicateKeys(false).setAllowRecursiveKeys(false)
                    .setAllowNonScalarKeys(false).setMaxAliasesForCollections(50).setUseMarks(true).setSchema(new CoreSchema())
                    .setLabel(shown).build();
            root = new Compose(settings).composeString(text);
        } catch (MarkedYamlEngineException e) {
            error("GWY001", "The configuration file is not valid YAML: " + e.getProblem(),
                    e.getProblemMark().or(e::getContextMark).map(this::at).orElse(here()));
            return Optional.empty();
        } catch (YamlEngineException | StackOverflowError e) {
            error("GWY001", "The configuration file is not valid YAML", here());
            return Optional.empty();
        }
        if (root.isEmpty()) {
            error("GWY004", "The configuration file is empty", here());
            return Optional.empty();
        }
        if (!depthOk(root.get(), 0)) {
            error("GWY001", "The configuration file is nested more than " + MAX_DEPTH + " levels deep", at(root.get()));
            return Optional.empty();
        }
        return top(root.get());
    }

    private byte[] readLimited() throws IOException {
        try (var in = Files.newInputStream(file)) {
            byte[] bytes = in.readNBytes(MAX_BYTES + 1);
            return bytes.length > MAX_BYTES ? null : bytes;
        }
    }

    private boolean depthOk(Node node, int depth) {
        if (depth > MAX_DEPTH) {
            return false;
        }
        if (node instanceof MappingNode mapping) {
            for (NodeTuple tuple : mapping.getValue()) {
                if (!depthOk(tuple.getValueNode(), depth + 1)) {
                    return false;
                }
            }
        } else if (node instanceof SequenceNode sequence) {
            for (Node item : sequence.getValue()) {
                if (!depthOk(item, depth + 1)) {
                    return false;
                }
            }
        }
        return true;
    }

    private Optional<GatewayConfig> top(Node root) {
        Optional<Map<String, Node>> entries = entries(root, Set.of("server", "tenants"), "The configuration");
        if (entries.isEmpty()) {
            return Optional.empty();
        }
        String host = GatewayConfig.DEFAULT_HOST;
        int port = GatewayConfig.DEFAULT_PORT;
        Node server = entries.get().get("server");
        if (server != null) {
            Optional<Map<String, Node>> settings = entries(server, Set.of("host", "port"), "The section server");
            if (settings.isPresent()) {
                if (settings.get().isEmpty()) {
                    error("GWY004", "The section server is empty", at(server));
                }
                Node hostNode = settings.get().get("host");
                if (hostNode != null) {
                    host = text(hostNode, "server.host").filter(h -> {
                        boolean ok = h.chars().noneMatch(Character::isWhitespace);
                        if (!ok) {
                            error("GWY006", "server.host must not contain white space", at(hostNode));
                        }
                        return ok;
                    }).orElse(host);
                }
                Node portNode = settings.get().get("port");
                if (portNode != null) {
                    port = port(portNode).orElse(port);
                }
            }
        }
        List<TenantConfig> tenants = new ArrayList<>();
        Node tenantsNode = entries.get().get("tenants");
        if (tenantsNode == null) {
            error("GWY005", "The key tenants is missing", at(root));
        } else {
            entries(tenantsNode, null, "The section tenants").ifPresent(map -> {
                if (map.isEmpty()) {
                    error("GWY004", "The section tenants is empty", at(tenantsNode));
                }
                map.forEach((name, node) -> tenant(name, node, keyNode(tenantsNode, name)).ifPresent(tenants::add));
            });
        }
        if (!problems.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new GatewayConfig(host, port, tenants, file));
    }

    private Optional<Integer> port(Node node) {
        Optional<String> text = text(node, "server.port");
        if (text.isEmpty()) {
            return Optional.empty();
        }
        if (PORT.matcher(text.get()).matches()) {
            int value = Integer.parseInt(text.get());
            if (value >= 1 && value <= 65535) {
                return Optional.of(value);
            }
        }
        error("GWY006", "server.port must be a whole number from 1 to 65535", at(node));
        return Optional.empty();
    }

    private Optional<TenantConfig> tenant(String name, Node node, Node keyNode) {
        checkName(name, "tenant", keyNode);
        Optional<Map<String, Node>> entries = entries(node, Set.of("bundles", "environments"), "The tenant " + safe(name));
        if (entries.isEmpty()) {
            return Optional.empty();
        }
        List<Path> bundles = new ArrayList<>();
        List<SourceLocation> sources = new ArrayList<>();
        Node bundlesNode = entries.get().get("bundles");
        if (bundlesNode == null) {
            error("GWY005", "The tenant " + safe(name) + " has no key bundles", at(node));
        } else if (!(bundlesNode instanceof SequenceNode sequence)) {
            error("GWY003", "bundles must be a list of file names", at(bundlesNode));
        } else if (sequence.getValue().isEmpty()) {
            error("GWY004", "The list bundles of the tenant " + safe(name) + " is empty", at(bundlesNode));
        } else {
            for (Node item : sequence.getValue()) {
                text(item, "a bundle file name").ifPresent(value -> {
                    try {
                        bundles.add(file.toAbsolutePath().getParent().resolve(value).normalize());
                        sources.add(at(item));
                    } catch (InvalidPathException e) {
                        error("GWY006", "This is not a valid file name", at(item));
                    }
                });
            }
        }
        List<EnvironmentConfig> environments = new ArrayList<>();
        Node environmentsNode = entries.get().get("environments");
        if (environmentsNode == null) {
            error("GWY005", "The tenant " + safe(name) + " has no key environments", at(node));
        } else {
            entries(environmentsNode, null, "The section environments").ifPresent(map -> {
                if (map.isEmpty()) {
                    error("GWY004", "The section environments of the tenant " + safe(name) + " is empty", at(environmentsNode));
                }
                map.forEach((envName, envNode) -> environment(envName, envNode, keyNode(environmentsNode, envName)).ifPresent(environments::add));
            });
        }
        return Optional.of(new TenantConfig(name, bundles, sources, environments, at(keyNode)));
    }

    private Optional<EnvironmentConfig> environment(String name, Node node, Node keyNode) {
        checkName(name, "environment", keyNode);
        Optional<Map<String, Node>> entries = entries(node, Set.of("code", "backend"), "The environment " + safe(name));
        if (entries.isEmpty()) {
            return Optional.empty();
        }
        Optional<String> code = Optional.empty();
        Node codeNode = entries.get().get("code");
        if (codeNode == null) {
            error("GWY005", "The environment " + safe(name) + " has no key code", at(node));
        } else {
            code = text(codeNode, "code");
        }
        Optional<Backend> backend = Optional.empty();
        Node backendNode = entries.get().get("backend");
        if (backendNode == null) {
            error("GWY005", "The environment " + safe(name) + " has no key backend", at(node));
        } else {
            Optional<String> value = text(backendNode, "backend");
            if (value.isPresent()) {
                if (value.get().equals("mock")) {
                    backend = Optional.of(Backend.MOCK);
                } else if (value.get().equals("ibmi")) {
                    error("GWY006", "The backend ibmi is not available in this version", at(backendNode));
                } else {
                    error("GWY006", "The backend must be mock", at(backendNode));
                }
            }
        }
        if (code.isEmpty() || backend.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new EnvironmentConfig(name, code.get(), backend.get(), at(keyNode)));
    }

    private void checkName(String name, String what, Node keyNode) {
        if (!NAME.matcher(name).matches()) {
            error("GWY006", "A " + what + " name must match [a-z0-9][a-z0-9-]*", at(keyNode));
        } else if (name.equals(RESERVED)) {
            error("GWY006", "The name " + RESERVED + " is reserved", at(keyNode));
        }
    }

    /** The entries of a mapping by key, in file order. A null allowed set accepts any key. */
    private Optional<Map<String, Node>> entries(Node node, Set<String> allowed, String what) {
        if (node instanceof ScalarNode scalar && scalar.getValue().isEmpty() && isNull(scalar)) {
            error("GWY004", what + " is empty", at(node));
            return Optional.empty();
        }
        if (!(node instanceof MappingNode mapping)) {
            error("GWY003", what + " must be a mapping", at(node));
            return Optional.empty();
        }
        Map<String, Node> entries = new LinkedHashMap<>();
        boolean ok = true;
        for (NodeTuple tuple : mapping.getValue()) {
            if (!(tuple.getKeyNode() instanceof ScalarNode key)) {
                error("GWY003", "A key must be a text", at(tuple.getKeyNode()));
                ok = false;
                continue;
            }
            String name = key.getValue();
            if (allowed != null && !allowed.contains(name)) {
                error("GWY002", "Unknown key " + safe(name), at(key));
                ok = false;
                continue;
            }
            entries.put(name, tuple.getValueNode());
        }
        return ok ? Optional.of(entries) : Optional.empty();
    }

    private Node keyNode(Node mapping, String name) {
        for (NodeTuple tuple : ((MappingNode) mapping).getValue()) {
            if (tuple.getKeyNode() instanceof ScalarNode key && key.getValue().equals(name)) {
                return key;
            }
        }
        return mapping;
    }

    private static boolean isNull(ScalarNode node) {
        return node.getTag().getValue().equals("tag:yaml.org,2002:null");
    }

    /** A scalar as text, with the environment placeholders replaced. A value that is empty is an error. */
    private Optional<String> text(Node node, String what) {
        if (!(node instanceof ScalarNode scalar)) {
            error("GWY003", what + " must be a text value", at(node));
            return Optional.empty();
        }
        String raw = scalar.getValue();
        if (raw.isEmpty()) {
            error("GWY004", what + " is empty", at(node));
            return Optional.empty();
        }
        StringBuilder out = new StringBuilder();
        Matcher matcher = PLACEHOLDER.matcher(raw);
        int last = 0;
        boolean ok = true;
        while (matcher.find()) {
            out.append(raw, last, matcher.start());
            String value = environment.apply(matcher.group(1));
            if (value == null) {
                error("GWY007", "The environment variable " + matcher.group(1) + " is not set", at(node));
                ok = false;
            } else {
                out.append(value);
            }
            last = matcher.end();
        }
        out.append(raw.substring(last));
        if (PLACEHOLDER.matcher(raw).replaceAll("").contains("${")) {
            error("GWY006", "A placeholder must be ${NAME}, with NAME made of letters, digits and underscores", at(node));
            return Optional.empty();
        }
        if (!ok) {
            return Optional.empty();
        }
        if (out.isEmpty()) {
            error("GWY004", what + " is empty", at(node));
            return Optional.empty();
        }
        return Optional.of(out.toString());
    }

    private static String safe(String text) {
        StringBuilder out = new StringBuilder();
        text.codePoints().limit(64).forEach(c -> out.append(Character.isISOControl(c) ? '?' : new String(Character.toChars(c))));
        return out.toString();
    }

    private void error(String code, String message, SourceLocation at) {
        problems.add(new Diagnostic(code, Severity.ERROR, message, at));
    }

    private SourceLocation here() {
        return new SourceLocation(shown, 1, 1);
    }

    private SourceLocation at(Node node) {
        return node.getStartMark().map(this::at).orElse(here());
    }

    private SourceLocation at(Mark mark) {
        return new SourceLocation(shown, mark.getLine() + 1, mark.getColumn() + 1);
    }
}
